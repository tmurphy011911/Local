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
  console.log(`Directory: ${entries.length} porchfests in live states. Drilling (concurrency ${CONCURRENCY})…`);

  let drilled = 0;
  const found = await pool(entries, CONCURRENCY, async (e) => {
    const fetched = await fetchHtml(e.url, { scrapflyKey: secrets.SCRAPFLY_API_KEY, renderJs: true, renderingWait: 3000, timeoutMs: 45000 }).catch(() => null);
    if (++drilled % 10 === 0) console.log(`  …${drilled}/${entries.length}`);
    if (!fetched?.html) return null;
    const date = await extractDate(fetched.html, e, secrets.ANTHROPIC_API_KEY);
    if (!date) return null;
    const coords = await geocode(e, secrets.GOOGLE_PLACES_API_KEY);
    if (!coords) return null;
    return { ...e, date: date.date, start_time: date.start_time, title: date.title, lat: coords.lat, lng: coords.lng };
  });

  const porchfests = found.filter(Boolean);
  console.log(`\nFound upcoming dated porchfests: ${porchfests.length}/${entries.length}`);
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify(porchfests, null, 1));
  for (const p of porchfests.slice(0, 10)) console.log(`  • ${p.city}, ${p.state} — ${p.date}${p.start_time ? " " + p.start_time : ""}`);

  if (DRY_RUN) { console.log(`\nDRY_RUN: wrote ${OUT}, no DB write.`); return; }
  const { ok, errors } = await upsert(porchfests.map(buildEvent));
  console.log(`\nUpserted ${ok} porchfest events, ${errors} errors.`);
  if (errors) process.exitCode = 1;
}

main().catch((e) => { console.error(e); process.exit(1); });
