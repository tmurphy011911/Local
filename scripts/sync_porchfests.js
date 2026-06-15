/**
 * sync_porchfests.js — Ingest porchfests as one-day `upcoming` events.
 *
 * Porchfests are annual one-day music festivals with no central date source:
 * porchfest.org/porchfests-elsewhere/ lists ~200 of them (city + link) but NO
 * dates. So we drill each festival's own site (Scrapfly, since they're DIY/
 * anti-bot/JS pages), Claude-extract the next upcoming date, geocode the city,
 * and upsert an `upcoming` event. Past-dated rows auto-deactivate via the
 * hourly deactivate_past_events job.
 *
 * NOT a weekly job — porchfest dates are stable/annual. Run twice a year
 * (spring + fall) or monthly at most:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/sync_porchfests.js
 *   DRY_RUN=1 ... node scripts/sync_porchfests.js          # drill + write JSON, no DB write
 *   SUPABASE_URL=https://<dev-ref>.supabase.co ...         # target dev
 *
 * Scoped to live states (the app only serves these); edit LIVE_STATES to widen.
 * Writes scripts/data/porchfests.json as a versioned record of what was found.
 */

const fs = require("fs");
const path = require("path");
const { getSecrets, SUPABASE_URL } = require("./lib/secrets");
const { fetchHtml } = require("./lib/scrapfly_fetch");

const SUPABASE_SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").trim();
if (!SUPABASE_SERVICE_KEY) { console.error("Missing SUPABASE_SERVICE_KEY"); process.exit(1); }
const DRY_RUN = process.env.DRY_RUN === "1";
const CONCURRENCY = 5;
const DIRECTORY = "https://www.porchfest.org/porchfests-elsewhere/";
const LIVE_STATES = new Set(["MA", "NY", "PA", "NJ", "RI", "CA"]);
const OUT = path.join(__dirname, "data", "porchfests.json");
const TODAY = new Date().toISOString().slice(0, 10);

const BROWSER_HEADERS = {
  "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0 Safari/537.36",
  Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
  "Accept-Language": "en-US,en;q=0.9",
};
const slug = (s) => (s || "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 60);

// ── 1. Parse the directory → live-state entries ──
async function fetchDirectory() {
  const res = await fetch(DIRECTORY, { headers: BROWSER_HEADERS });
  if (!res.ok) throw new Error(`directory fetch ${res.status}`);
  const html = await res.text();
  const entries = [];
  const seen = new Set();
  for (const m of html.matchAll(/<a[^>]*href="(https?:\/\/[^"]+)"[^>]*>([^<]{3,70})<\/a>/g)) {
    const url = m[1];
    const text = m[2].replace(/&#?[a-z0-9]+;/gi, " ").replace(/\s+/g, " ").trim();
    const st = (text.match(/,\s?([A-Z]{2})\b/) || [])[1];
    if (!st || !LIVE_STATES.has(st)) continue;
    if (/porchfest\.org|facebook|twitter|instagram|youtube|spotify/i.test(url)) continue;
    const city = text.split(",")[0].trim();
    const neighborhood = (text.match(/\(([^)]+)\)/) || [])[1] || null;
    const key = `${city}|${st}|${neighborhood || ""}`;
    if (seen.has(key)) continue;
    seen.add(key);
    entries.push({ city, state: st, neighborhood, url, label: text });
  }
  return entries;
}

// ── 2. Claude-extract the next upcoming date from a festival page ──
async function extractDate(html, entry, anthropicKey) {
  const text = html.replace(/<script[\s\S]*?<\/script>/gi, " ").replace(/<style[\s\S]*?<\/style>/gi, " ")
    .replace(/<[^>]+>/g, " ").replace(/&#?[a-z0-9]+;/gi, " ").replace(/\s+/g, " ").trim().slice(0, 9000);
  const prompt = `This is the website for a "porchfest" (a one-day neighborhood music festival) in ${entry.city}, ${entry.state}. Today is ${TODAY}.

Find the date of the NEXT upcoming porchfest — it must be today or in the future. If the page only mentions past dates (e.g. a prior year) and no upcoming one, return null for the date.

Return ONLY strict JSON, no prose:
{"date":"YYYY-MM-DD"|null,"start_time":"HH:MM"|null,"title":string|null}

Page text:
${text}`;
  try {
    const res = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: { "x-api-key": anthropicKey, "anthropic-version": "2023-06-01", "content-type": "application/json" },
      body: JSON.stringify({ model: "claude-haiku-4-5-20251001", max_tokens: 200, messages: [{ role: "user", content: prompt }] }),
    });
    if (!res.ok) return null;
    const j = await res.json();
    const raw = (j.content?.[0]?.text || "").match(/\{[\s\S]*\}/);
    if (!raw) return null;
    const out = JSON.parse(raw[0]);
    if (!out.date || !/^\d{4}-\d{2}-\d{2}$/.test(out.date) || out.date < TODAY) return null;
    return out;
  } catch (_) { return null; }
}

// ── 2b. Fallback: web search ──
// When the festival's own site is dead, stale, JS-only, or just doesn't state
// the date, don't give up — let Claude search the live web (official site +
// Facebook events + local news + Eventbrite) via the web_search server tool.
// Sonnet 4.6 (not Opus) deliberately: this is a bulk background lookup, ~dozens
// of calls, and Sonnet supports web_search_20260209.
async function extractDateViaWebSearch(entry, anthropicKey) {
  const where = [entry.neighborhood, entry.city, entry.state].filter(Boolean).join(", ");
  const prompt = `Find the date of the NEXT upcoming porchfest (a one-day neighborhood music festival) in ${where}. Today is ${TODAY}. Search the web — check the festival's official site, its Facebook events page, local news, and Eventbrite. The date must be today or later; if you can only find past dates, the date is null.

Return ONLY strict JSON, no prose:
{"date":"YYYY-MM-DD"|null,"start_time":"HH:MM"|null,"title":string|null}`;
  try {
    const res = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: { "x-api-key": anthropicKey, "anthropic-version": "2023-06-01", "content-type": "application/json" },
      body: JSON.stringify({
        model: "claude-sonnet-4-6",
        max_tokens: 1024,
        tools: [{ type: "web_search_20260209", name: "web_search", max_uses: 4 }],
        messages: [{ role: "user", content: prompt }],
      }),
    });
    if (!res.ok) return null;
    const j = await res.json();
    const text = (j.content || []).filter((b) => b.type === "text").map((b) => b.text).join("\n");
    const m = text.match(/\{[\s\S]*\}/);
    if (!m) return null;
    const out = JSON.parse(m[0]);
    if (!out.date || !/^\d{4}-\d{2}-\d{2}$/.test(out.date) || out.date < TODAY) return null;
    return out;
  } catch (_) { return null; }
}

// ── 3. Geocode the city ──
async function geocode(entry, key) {
  if (!key) return null;
  const q = [entry.neighborhood, entry.city, entry.state].filter(Boolean).join(", ");
  try {
    const r = await fetch(`https://maps.googleapis.com/maps/api/geocode/json?address=${encodeURIComponent(q)}&key=${key}`);
    const d = await r.json();
    if (d.status === "OK" && d.results[0]) return d.results[0].geometry.location;
  } catch (_) { /* ignore */ }
  return null;
}

// ── concurrency pool ──
async function pool(items, n, fn) {
  const out = []; let i = 0;
  await Promise.all(Array.from({ length: n }, async () => {
    while (i < items.length) { const idx = i++; out[idx] = await fn(items[idx], idx); }
  }));
  return out;
}

// Active, future-dated porchfests already in the DB — skip re-drilling these.
async function fetchCoveredSourceIds() {
  const url = `${SUPABASE_URL}/rest/v1/events?source=eq.porchfest_org&is_active=eq.true&start_at=gte.${TODAY}&select=source_id`;
  try {
    const res = await fetch(url, { headers: { apikey: SUPABASE_SERVICE_KEY, Authorization: `Bearer ${SUPABASE_SERVICE_KEY}` } });
    if (!res.ok) return new Set();
    return new Set((await res.json()).map((r) => r.source_id));
  } catch (_) { return new Set(); }
}

// ── upsert upcoming events ──
async function upsert(rows) {
  const url = `${SUPABASE_URL}/rest/v1/events?on_conflict=dedupe_hash`;
  let ok = 0, errors = 0;
  for (let i = 0; i < rows.length; i += 50) {
    const batch = rows.slice(i, i + 50);
    const res = await fetch(url, {
      method: "POST",
      headers: { apikey: SUPABASE_SERVICE_KEY, Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`, "Content-Type": "application/json", Prefer: "resolution=merge-duplicates" },
      body: JSON.stringify(batch),
    });
    if (!res.ok) { console.error(`  upsert ${res.status}: ${(await res.text()).slice(0, 200)}`); errors += batch.length; }
    else ok += batch.length;
  }
  return { ok, errors };
}

function buildEvent(p) {
  const startAt = p.start_time ? `${p.date}T${p.start_time}:00` : `${p.date}T12:00:00`;
  const name = p.title && /porch/i.test(p.title) ? p.title : `${p.city} Porchfest`;
  return {
    title: name,
    description: `${p.city}, ${p.state} porchfest — a one-day neighborhood music festival with live performances on porches, lawns, and stoops.`,
    category: "upcoming",
    subcategory: "festival",
    tags: ["porchfest", "festival", "music", "outdoor", "free", "family-friendly"],
    start_at: startAt,
    end_at: null,
    time_known: !!p.start_time,
    is_free: true,
    ticket_url: p.url,
    image_url: null,
    status: "active",
    is_active: true,
    venue_name: p.neighborhood ? `${p.neighborhood}, ${p.city}` : p.city,
    venue_address: null,
    venue_city: p.city,
    venue_state: p.state,
    venue_lat: p.lat,
    venue_lng: p.lng,
    source: "porchfest_org",
    source_id: slug(p.label),
    source_url: p.url,
    confidence_score: 0.85,
    needs_review: false,
    dedupe_hash: `porchfest:${slug(p.label)}`,
    city: p.city,
    last_synced_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  };
}

async function main() {
  console.log(`Porchfest sync → ${SUPABASE_URL}${DRY_RUN ? "  [DRY RUN]" : ""}  (today ${TODAY})`);

  // FROM_JSON: skip the slow drill and just (re)upsert the committed record.
  // Use between drills (the drill is the twice-a-year part; upserting is cheap).
  if (process.env.FROM_JSON === "1") {
    const porchfests = JSON.parse(fs.readFileSync(OUT, "utf8"));
    console.log(`FROM_JSON: ${porchfests.length} porchfests from ${OUT}`);
    const { ok, errors } = await upsert(porchfests.map(buildEvent));
    console.log(`Upserted ${ok} porchfest events, ${errors} errors.`);
    if (errors) process.exitCode = 1;
    return;
  }

  const secrets = await getSecrets(["SCRAPFLY_API_KEY", "ANTHROPIC_API_KEY", "GOOGLE_PLACES_API_KEY"]);
  const entries = await fetchDirectory();

  // Selective drilling: skip porchfests we already have as an active, future-
  // dated event. The drill (Scrapfly + web search) is the expensive part, and
  // porchfest dates don't change once announced. Past-dated rows auto-deactivate
  // hourly, so once a fest passes it drops out of `covered` and gets re-drilled
  // next run to pick up the following year's date. SKIP_SELECTIVE=1 forces a
  // full re-drill (e.g. to refresh dates mid-season).
  const covered = process.env.SKIP_SELECTIVE === "1" ? new Set() : await fetchCoveredSourceIds();
  const toDrill = entries.filter((e) => !covered.has(slug(e.label)));
  console.log(`Directory: ${entries.length} live-state porchfests; ${covered.size} already current, drilling ${toDrill.length} (concurrency ${CONCURRENCY})…`);

  let drilled = 0;
  const found = await pool(toDrill, CONCURRENCY, async (e) => {
    if (++drilled % 10 === 0) console.log(`  …${drilled}/${toDrill.length}`);
    // 1) Cheap path: the festival's own page via Scrapfly + Claude Haiku.
    let date = null;
    const fetched = await fetchHtml(e.url, { scrapflyKey: secrets.SCRAPFLY_API_KEY, renderJs: true, renderingWait: 3000, timeoutMs: 45000 }).catch(() => null);
    if (fetched?.html) date = await extractDate(fetched.html, e, secrets.ANTHROPIC_API_KEY);
    // 2) Fallback: web search — recovers dead/stale/JS-only sites instead of
    //    giving up. This is what lifts coverage toward "all live-state fests".
    if (!date) date = await extractDateViaWebSearch(e, secrets.ANTHROPIC_API_KEY);
    if (!date) return null;
    const coords = await geocode(e, secrets.GOOGLE_PLACES_API_KEY);
    if (!coords) return null;
    return { ...e, date: date.date, start_time: date.start_time, title: date.title, lat: coords.lat, lng: coords.lng };
  });

  const fresh = found.filter(Boolean);
  console.log(`\nNewly dated porchfests: ${fresh.length}/${toDrill.length} drilled (${covered.size} already current).`);

  // Merge with the prior committed record so the JSON stays complete: keep prior
  // entries that are still in the future and weren't just re-drilled.
  const prior = fs.existsSync(OUT) ? JSON.parse(fs.readFileSync(OUT, "utf8")) : [];
  const freshIds = new Set(fresh.map((p) => slug(p.label)));
  const merged = [...fresh, ...prior.filter((p) => !freshIds.has(slug(p.label)) && p.date >= TODAY)];
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify(merged, null, 1));
  for (const p of fresh.slice(0, 10)) console.log(`  • ${p.city}, ${p.state} — ${p.date}${p.start_time ? " " + p.start_time : ""}`);

  if (DRY_RUN) { console.log(`\nDRY_RUN: wrote ${OUT} (${merged.length} total), no DB write.`); return; }
  // Upsert only the freshly-drilled rows; already-covered ones stay untouched.
  const { ok, errors } = await upsert(fresh.map(buildEvent));
  console.log(`\nUpserted ${ok} new porchfest events, ${errors} errors. (${covered.size} already current, untouched.)`);
  if (errors) process.exitCode = 1;
}

main().catch((e) => { console.error(e); process.exit(1); });
