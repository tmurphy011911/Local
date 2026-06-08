/**
 * _tmp_count_boston_events.js — Tier-1 read-only count.
 * Active events within 25mi of Boston, occurring a given date window.
 *
 * Run: SUPABASE_SERVICE_KEY=ey... node scripts/_tmp_count_boston_events.js
 *
 * Mirrors search_events_stratified v3 geo logic:
 *   venues row matched by lower(name)=lower(e.venue_name); prefer v.lat/lng,
 *   else e.venue_lat/lng. dist_mi = sqrt((dlat*69)^2 + (dlng*50)^2) <= radius.
 */
const { SUPABASE_URL } = require("./lib/secrets");

const KEY = (process.env.SUPABASE_SERVICE_KEY || "").replace(/\s/g, "");
const LAT = parseFloat(process.env.CENTER_LAT || "42.3601");   // Boston
const LNG = parseFloat(process.env.CENTER_LNG || "-71.0589");
const RADIUS = parseFloat(process.env.RADIUS_MILES || "25");
const FROM = process.env.FROM || "2026-06-05T00:00:00Z";
const TO   = process.env.TO   || "2026-06-07T23:59:59Z";

if (!KEY) { console.error("Missing SUPABASE_SERVICE_KEY"); process.exit(1); }
const h = { apikey: KEY, Authorization: `Bearer ${KEY}` };
const dist = (lat, lng) => Math.sqrt(((lat - LAT) * 69) ** 2 + ((lng - LNG) * 50) ** 2);

async function pageAll(path) {
  let offset = 0, page = 1000, out = [];
  for (;;) {
    const res = await fetch(`${SUPABASE_URL}/rest/v1/${path}&limit=${page}&offset=${offset}`, { headers: h });
    if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
    const rows = await res.json();
    out.push(...rows);
    if (rows.length < page) break;
    offset += rows.length;
  }
  return out;
}

async function main() {
  const events = await pageAll(
    `events?select=id,venue_name,venue_lat,venue_lng` +
    `&is_active=eq.true` +
    `&start_at=gte.${encodeURIComponent(FROM)}&start_at=lte.${encodeURIComponent(TO)}` +
    `&order=id.asc`);

  // venues coord map keyed by lower(name) — authoritative source per RPC v3.
  const venues = await pageAll(`venues?select=name,lat,lng&order=name.asc`);
  const vmap = new Map();
  for (const v of venues) if (v.name) vmap.set(v.name.toLowerCase(), v);

  let count = 0, noCoord = 0;
  for (const e of events) {
    const v = e.venue_name ? vmap.get(e.venue_name.toLowerCase()) : null;
    const lat = (v && v.lat != null) ? v.lat : e.venue_lat;
    const lng = (v && v.lng != null) ? v.lng : e.venue_lng;
    if (lat == null || lng == null) { noCoord++; continue; }
    if (dist(lat, lng) <= RADIUS) count++;
  }
  console.log(`Window ${FROM}..${TO}  active=${events.length}  (no-coord skipped=${noCoord})`);
  console.log(`Within ${RADIUS}mi of (${LAT},${LNG}): ${count}`);
}
main().catch((e) => { console.error(e); process.exit(1); });
