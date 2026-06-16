/**
 * sync_ag_fairs.js — Ingest Massachusetts agricultural fairs as multi-day
 * `upcoming` events.
 *
 * mass.gov/info-details/agricultural-fairs lists ~30 MA fairs (name + link to
 * each fair's own site) but NO dates, and mass.gov 403s a plain fetch (Akamai).
 * So: Scrapfly-render the directory, then for each fair let Claude web-search
 * the next edition's date RANGE (fairs run multiple days) + town, and upsert a
 * multi-day event. Free admission is NOT assumed — most fairs charge.
 *
 * Same shape as sync_porchfests.js: NOT weekly (annual events). Run monthly /
 * a few times a year. SELECTIVE by default (skips fairs already stored as an
 * active future event). FROM_JSON=1 re-upserts the committed record; DRY_RUN=1
 * drills + writes JSON without DB writes; SKIP_SELECTIVE=1 forces a full
 * re-drill.
 *
 *   SUPABASE_SERVICE_KEY=ey... node scripts/sync_ag_fairs.js
 */

const fs = require("fs");
const path = require("path");
const { getSecrets, SUPABASE_URL } = require("./lib/secrets");
const { fetchHtml } = require("./lib/scrapfly_fetch");

const SUPABASE_SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").trim();
if (!SUPABASE_SERVICE_KEY) { console.error("Missing SUPABASE_SERVICE_KEY"); process.exit(1); }
const DRY_RUN = process.env.DRY_RUN === "1";
const CONCURRENCY = 5;
const DIRECTORY = "https://www.mass.gov/info-details/agricultural-fairs";
const OUT = path.join(__dirname, "data", "ag_fairs.json");
const TODAY = new Date().toISOString().slice(0, 10);

const slug = (s) => (s || "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 60);

// Eastern offset for a date, so a fair's local time stores as the right instant.
function easternOffset(dateStr) {
  const d = new Date(`${dateStr}T12:00:00Z`);
  const s = d.toLocaleString("en-US", { timeZone: "America/New_York", timeZoneName: "longOffset" });
  const m = s.match(/GMT([+-]\d{2}):?(\d{2})/);
  return m ? `${m[1]}:${m[2]}` : "-05:00";
}

// ── 1. Directory (Scrapfly — mass.gov 403s a plain fetch) ──
async function fetchDirectory(scrapflyKey) {
  const { html } = await fetchHtml(DIRECTORY, { scrapflyKey, renderJs: false, timeoutMs: 40000 });
  const seen = new Set();
  const out = [];
  for (const m of html.matchAll(/<a[^>]*href="(https?:\/\/[^"]+)"[^>]*>([^<]{3,60})<\/a>/g)) {
    const url = m[1];
    const name = m[2].replace(/&[a-z#0-9]+;/gi, " ").replace(/\s+/g, " ").trim();
    if (!/fair/i.test(name)) continue;
    if (/mass\.gov|unpkg|google|farmlocator|near you/i.test(url + " " + name)) continue;
    const key = slug(name);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ name, url });
  }
  return out;
}

// Town guess from the fair name ("Topsfield Fair" → "Topsfield"); web search
// can correct it. Strips common qualifiers.
function townFromName(name) {
  return name
    .replace(/\d+\w*\s+Annual\s+/i, "")
    .replace(/\b(County|Grange|Agricultural|Ag|4-?H|MA|Massachusetts)\b/gi, " ")
    .replace(/\bFair\b/i, " ")
    .replace(/\s+/g, " ").trim();
}

// ── 2. Web-search the next edition's date range + town ──
async function extractFair(fair, anthropicKey) {
  const prompt = `Find the dates of the NEXT upcoming "${fair.name}" (a Massachusetts agricultural fair). Today is ${TODAY}. Agricultural fairs run for multiple days, so find the full date RANGE. Search the web — the fair's official site (${fair.url}), Facebook, and local news.

Return ONLY strict JSON, no prose:
{"start_date":"YYYY-MM-DD"|null,"end_date":"YYYY-MM-DD"|null,"town":"<MA town>"|null}
The fair must not be fully over: end_date must be today or later. If you can only find past editions, set start_date and end_date to null.`;
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
    const ok = (d) => d && /^\d{4}-\d{2}-\d{2}$/.test(d);
    if (!ok(out.start_date) || !ok(out.end_date) || out.end_date < TODAY) return null;
    return out;
  } catch (_) { return null; }
}

async function geocode(town, key) {
  if (!key || !town) return null;
  try {
    const r = await fetch(`https://maps.googleapis.com/maps/api/geocode/json?address=${encodeURIComponent(town + ", MA")}&key=${key}`);
    const d = await r.json();
    if (d.status === "OK" && d.results[0]) return d.results[0].geometry.location;
  } catch (_) { /* ignore */ }
  return null;
}

async function pool(items, n, fn) {
  const out = []; let i = 0;
  await Promise.all(Array.from({ length: n }, async () => {
    while (i < items.length) { const idx = i++; out[idx] = await fn(items[idx], idx); }
  }));
  return out;
}

async function fetchCoveredSourceIds() {
  const url = `${SUPABASE_URL}/rest/v1/events?source=eq.ma_ag_fair&is_active=eq.true&end_at=gte.${TODAY}&select=source_id`;
  try {
    const res = await fetch(url, { headers: { apikey: SUPABASE_SERVICE_KEY, Authorization: `Bearer ${SUPABASE_SERVICE_KEY}` } });
    if (!res.ok) return new Set();
    return new Set((await res.json()).map((r) => r.source_id));
  } catch (_) { return new Set(); }
}

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

function buildEvent(f) {
  // Multi-day: open ~10am on the first day, close ~10pm on the last day (ET).
  const startAt = `${f.start_date}T10:00:00${easternOffset(f.start_date)}`;
  const endAt = `${f.end_date}T22:00:00${easternOffset(f.end_date)}`;
  return {
    title: f.name,
    description: `${f.name} — a Massachusetts agricultural fair in ${f.town}, with livestock and agricultural exhibits, competitions, food, rides, and entertainment.`,
    category: "upcoming",
    subcategory: "fair",
    tags: ["agricultural-fair", "fair", "festival", "family-friendly", "outdoor"],
    start_at: startAt,
    end_at: endAt,
    time_known: false,
    is_free: false, // most fairs charge admission
    ticket_url: f.url,
    image_url: null,
    status: "active",
    is_active: true,
    venue_name: f.town,
    venue_address: null,
    venue_city: f.town,
    venue_state: "MA",
    venue_lat: f.lat,
    venue_lng: f.lng,
    source: "ma_ag_fair",
    source_id: slug(f.name),
    source_url: f.url,
    confidence_score: 0.85,
    needs_review: false,
    dedupe_hash: `ma_ag_fair:${slug(f.name)}`,
    city: f.town,
    last_synced_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  };
}

async function main() {
  console.log(`Ag-fair sync → ${SUPABASE_URL}${DRY_RUN ? "  [DRY RUN]" : ""}  (today ${TODAY})`);

  if (process.env.FROM_JSON === "1") {
    const fairs = JSON.parse(fs.readFileSync(OUT, "utf8"));
    console.log(`FROM_JSON: ${fairs.length} fairs from ${OUT}`);
    const { ok, errors } = await upsert(fairs.map(buildEvent));
    console.log(`Upserted ${ok} ag-fair events, ${errors} errors.`);
    if (errors) process.exitCode = 1;
    return;
  }

  const secrets = await getSecrets(["SCRAPFLY_API_KEY", "ANTHROPIC_API_KEY", "GOOGLE_PLACES_API_KEY"]);
  const entries = await fetchDirectory(secrets.SCRAPFLY_API_KEY);

  const covered = process.env.SKIP_SELECTIVE === "1" ? new Set() : await fetchCoveredSourceIds();
  const toDrill = entries.filter((e) => !covered.has(slug(e.name)));
  console.log(`Directory: ${entries.length} MA fairs; ${covered.size} already current, drilling ${toDrill.length} (concurrency ${CONCURRENCY})…`);

  let drilled = 0;
  const found = await pool(toDrill, CONCURRENCY, async (e) => {
    if (++drilled % 10 === 0) console.log(`  …${drilled}/${toDrill.length}`);
    const d = await extractFair(e, secrets.ANTHROPIC_API_KEY);
    if (!d) return null;
    const town = (d.town || townFromName(e.name)).trim();
    const coords = await geocode(town, secrets.GOOGLE_PLACES_API_KEY);
    if (!coords) return null;
    return { ...e, start_date: d.start_date, end_date: d.end_date, town, lat: coords.lat, lng: coords.lng };
  });

  const fresh = found.filter(Boolean);
  console.log(`\nNewly dated fairs: ${fresh.length}/${toDrill.length} drilled (${covered.size} already current).`);

  const prior = fs.existsSync(OUT) ? JSON.parse(fs.readFileSync(OUT, "utf8")) : [];
  const freshIds = new Set(fresh.map((f) => slug(f.name)));
  const merged = [...fresh, ...prior.filter((f) => !freshIds.has(slug(f.name)) && f.end_date >= TODAY)];
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify(merged, null, 1));
  for (const f of fresh.slice(0, 12)) console.log(`  • ${f.name} [${f.town}] — ${f.start_date}→${f.end_date}`);

  if (DRY_RUN) { console.log(`\nDRY_RUN: wrote ${OUT} (${merged.length} total), no DB write.`); return; }
  const { ok, errors } = await upsert(fresh.map(buildEvent));
  console.log(`\nUpserted ${ok} new ag-fair events, ${errors} errors. (${covered.size} already current, untouched.)`);
  if (errors) process.exitCode = 1;
}

main().catch((e) => { console.error(e); process.exit(1); });
