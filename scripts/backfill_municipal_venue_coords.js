/**
 * backfill_municipal_venue_coords.js — Fix wrong venue coords on
 * municipal-feed events.
 *
 * `sync_municipal_feeds.js` previously defaulted `venue_lat / venue_lng`
 * to the FEED's metro center (e.g. downtown Boston for `municipal_boston`)
 * when the feed entry didn't include venue coords. Result: events at MASS
 * MoCA (North Adams, 150 mi west) were tagged at 42.36/-71.06 (Boston),
 * showing up as "<1 mi" away to a Boston user. Reported on 2026-05-18.
 *
 * Fix at write time: sync_municipal_feeds.js now calls resolveVenueCoords
 * which consults the venues table + Claude before falling back. This
 * script does the equivalent retroactive cleanup for rows already in the
 * DB:
 *   - For each active municipal_* event with a non-null venue_name
 *   - If a matching venue row exists in `venues` with non-null coords
 *   - And the event's venue_lat/lng is meaningfully different from
 *     the venue's lat/lng (>0.05° apart, ~3.5 mi)
 *   - Copy the venue's coords onto the event.
 *
 * Conservative defaults:
 *   - Only updates events where the venues table has a confident answer.
 *     Events at venues we don't know stay where they are (still
 *     incorrect, but not made worse).
 *   - Doesn't call Claude — keep this script free / deterministic. Run
 *     sync_municipal_feeds.js itself to backfill any Claude-resolvable
 *     venues going forward.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/backfill_municipal_venue_coords.js          # dry run
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 node scripts/backfill_municipal_venue_coords.js  # apply
 */

const { SUPABASE_URL, getSecrets } = require("./lib/secrets");
const SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").replace(/\s/g, "");
const WRITE = process.env.WRITE === "1";

if (!SERVICE_KEY) { console.error("Missing SUPABASE_SERVICE_KEY"); process.exit(1); }

let ANTHROPIC_API_KEY = null;

const headers = {
  apikey: SERVICE_KEY,
  Authorization: `Bearer ${SERVICE_KEY}`,
  "Content-Type": "application/json",
};

// How far apart (in degrees) the existing coords must be from the venue's
// real coords before we overwrite. ~0.05° ≈ 3.5 miles — anything closer is
// noise (geocoder rounding, etc.) and not worth churning the row.
const MIN_DELTA = 0.05;

function geoDistance(a, b) {
  return Math.max(Math.abs(a.lat - b.lat), Math.abs(a.lng - b.lng));
}

async function fetchMunicipalEvents() {
  // Keyset pagination on id, retry on 5xx. The events table is large and
  // the ILIKE filter blew the 8s statement timeout on offset-based fetches.
  const all = [];
  const limit = 1000;
  let cursor = null;
  while (true) {
    let url =
      `${SUPABASE_URL}/rest/v1/events` +
      `?select=id,title,venue_name,venue_city,venue_lat,venue_lng,source` +
      `&source=like.municipal_*` +
      `&is_active=eq.true` +
      `&venue_name=not.is.null` +
      `&venue_lat=not.is.null` +
      `&venue_lng=not.is.null` +
      `&order=id.asc` +
      `&limit=${limit}`;
    if (cursor) url += `&id=gt.${cursor}`;
    let rows = null;
    for (let attempt = 1; attempt <= 3; attempt++) {
      const res = await fetch(url, { headers });
      if (res.ok) { rows = await res.json(); break; }
      const txt = await res.text();
      console.error(`fetch attempt ${attempt}/3: ${res.status} ${txt.slice(0, 200)}`);
      if (res.status < 500 || attempt === 3) break;
      await new Promise((r) => setTimeout(r, 1000 * attempt));
    }
    if (rows === null) {
      console.error(`gave up after 3 attempts at cursor=${cursor || "(start)"} — proceeding with ${all.length} rows`);
      break;
    }
    all.push(...rows);
    process.stdout.write(`  fetched ${all.length}\r`);
    if (rows.length < limit) break;
    cursor = rows[rows.length - 1].id;
  }
  console.log("");
  return all;
}

async function fetchAllVenues() {
  // Pull every venue with coords. Index by lowercased name for fast
  // lookup. There are at most ~14k venues so a single in-memory map is
  // fine — way faster than per-event PostgREST queries.
  const all = [];
  const limit = 1000;
  let cursor = null;
  while (true) {
    let url =
      `${SUPABASE_URL}/rest/v1/venues` +
      `?select=id,name,city,lat,lng` +
      `&lat=not.is.null` +
      `&lng=not.is.null` +
      `&order=id.asc` +
      `&limit=${limit}`;
    if (cursor) url += `&id=gt.${cursor}`;
    const res = await fetch(url, { headers });
    if (!res.ok) throw new Error(`venues fetch ${res.status}: ${await res.text()}`);
    const rows = await res.json();
    all.push(...rows);
    process.stdout.write(`  fetched ${all.length} venues\r`);
    if (rows.length < limit) break;
    cursor = rows[rows.length - 1].id;
  }
  console.log("");
  return all;
}

// ── Phase 2: at-large (venue_name = null) events ──
// These are city-wide civic events (festivals, parades, "on the Common").
// The old sync stamped them at the FEED's metro center, so a Waltham festival
// landed at downtown Boston (~10mi) and a Berkshires one ~130mi away. The
// named-venue pass above can't touch them (no venue_name to match). Here we
// re-stamp each to its OWN town center, geocoded once per town via Claude.

const TOWN_CACHE = new Map(); // "city|state" -> {lat,lng} | null

async function geocodeTown(rawCity, state) {
  if (!rawCity) return null;
  // Strip qualifiers like "Cambridge (visitor)" / "Johnstown (tourism)".
  const city = rawCity.replace(/\s*\([^)]*\)\s*/g, " ").trim();
  if (!city) return null;
  const key = `${city.toLowerCase()}|${(state || "").toLowerCase()}`;
  if (TOWN_CACHE.has(key)) return TOWN_CACHE.get(key);
  let coords = null;
  if (ANTHROPIC_API_KEY) {
    const prompt =
      `What are the latitude and longitude of the geographic center (downtown / city hall) of the ` +
      `municipality of ${city}, ${state}, USA?\n\n` +
      `Return ONLY a JSON object: {"lat": <number>, "lng": <number>, "confidence": <0..1>}.\n` +
      `For any real, uniquely-identifiable US town/city the confidence should be ≥ 0.9. ` +
      `Drop below 0.6 ONLY if "${city}, ${state}" does not identify a real place or is genuinely ambiguous.`;
    try {
      const res = await fetch("https://api.anthropic.com/v1/messages", {
        method: "POST",
        headers: { "x-api-key": ANTHROPIC_API_KEY, "anthropic-version": "2023-06-01", "content-type": "application/json" },
        body: JSON.stringify({ model: "claude-haiku-4-5-20251001", max_tokens: 200, messages: [{ role: "user", content: prompt }] }),
      });
      if (res.ok) {
        const data = await res.json();
        const m = (data.content?.[0]?.text || "").match(/\{[\s\S]*\}/);
        if (m) {
          const o = JSON.parse(m[0]);
          const conf = typeof o.confidence === "number" ? o.confidence : 0;
          if (typeof o.lat === "number" && typeof o.lng === "number" && conf >= 0.8 &&
              !(Math.abs(o.lat) < 0.01 && Math.abs(o.lng) < 0.01) &&
              Math.abs(o.lat) <= 90 && Math.abs(o.lng) <= 180) {
            coords = { lat: o.lat, lng: o.lng };
          }
        }
      }
    } catch (_) { /* leave null */ }
  }
  TOWN_CACHE.set(key, coords);
  return coords;
}

async function fetchAtLargeEvents() {
  // Active, FUTURE municipal events with no named venue but a known town.
  // Future-only: past events don't surface in the app, so we don't spend
  // writes (or Claude calls) correcting them.
  const all = [];
  const limit = 1000;
  const nowIso = new Date().toISOString();
  let cursor = null;
  while (true) {
    let url =
      `${SUPABASE_URL}/rest/v1/events` +
      `?select=id,title,venue_city,venue_state,venue_lat,venue_lng,source,start_at` +
      `&source=like.municipal_*` +
      `&is_active=eq.true` +
      `&venue_name=is.null` +
      `&venue_city=not.is.null` +
      `&start_at=gte.${nowIso}` +
      `&order=id.asc&limit=${limit}`;
    if (cursor) url += `&id=gt.${cursor}`;
    const res = await fetch(url, { headers });
    if (!res.ok) { console.error(`at-large fetch ${res.status}: ${(await res.text()).slice(0,200)}`); break; }
    const rows = await res.json();
    all.push(...rows);
    process.stdout.write(`  fetched ${all.length} at-large\r`);
    if (rows.length < limit) break;
    cursor = rows[rows.length - 1].id;
  }
  console.log("");
  return all;
}

async function backfillAtLarge() {
  console.log("\n── Phase 2: at-large (venue_name=null) town-center fix ──");
  const events = await fetchAtLargeEvents();
  console.log(`${events.length} active future at-large events`);

  // Resolve each unique town once.
  const towns = [...new Set(events.map((e) => `${e.venue_city}|${e.venue_state || ""}`))];
  console.log(`Geocoding ${towns.length} unique towns...`);
  for (const t of towns) {
    const [city, state] = t.split("|");
    await geocodeTown(city, state);
  }

  const fixes = [];
  let unresolved = 0;
  for (const e of events) {
    const cleanCity = (e.venue_city || "").replace(/\s*\([^)]*\)\s*/g, " ").trim();
    const town = TOWN_CACHE.get(`${cleanCity.toLowerCase()}|${(e.venue_state || "").toLowerCase()}`);
    if (!town) { unresolved++; continue; }
    // Only rewrite when current coords are missing OR meaningfully off.
    const off = e.venue_lat == null || e.venue_lng == null ||
      geoDistance({ lat: e.venue_lat, lng: e.venue_lng }, town) >= MIN_DELTA;
    if (!off) continue;
    fixes.push({
      eventId: e.id, title: e.title, venueCity: e.venue_city, source: e.source,
      before: { lat: e.venue_lat, lng: e.venue_lng },
      after: { lat: town.lat, lng: town.lng },
      delta: (e.venue_lat == null) ? 999 : geoDistance({ lat: e.venue_lat, lng: e.venue_lng }, town),
    });
  }
  fixes.sort((a, b) => b.delta - a.delta);
  console.log(`${fixes.length} at-large rows to update; ${unresolved} skipped (town unresolved)\n`);
  for (const f of fixes.slice(0, 30)) {
    const b = f.before.lat == null ? "(null)" : `${f.before.lat.toFixed(4)},${f.before.lng.toFixed(4)}`;
    console.log(`Δ${f.delta === 999 ? "∞" : f.delta.toFixed(2)}° [${f.source}] "${(f.title||"").slice(0,55)}" (${f.venueCity})`);
    console.log(`  ${b} → ${f.after.lat.toFixed(4)},${f.after.lng.toFixed(4)}`);
  }
  if (fixes.length > 30) console.log(`  ... and ${fixes.length - 30} more`);

  if (!WRITE) { console.log("\nDRY RUN — pass WRITE=1 to apply Phase 2."); return; }
  if (fixes.length === 0) return;
  let ok = 0, index = 0;
  async function worker() {
    while (index < fixes.length) {
      const i = index++; const f = fixes[i];
      try {
        const res = await fetch(`${SUPABASE_URL}/rest/v1/events?id=eq.${f.eventId}`, {
          method: "PATCH",
          headers: { ...headers, Prefer: "return=minimal" },
          body: JSON.stringify({ venue_lat: f.after.lat, venue_lng: f.after.lng, updated_at: new Date().toISOString() }),
        });
        if (res.ok) ok++;
      } catch (_) {}
      if ((i + 1) % 50 === 0 || i === fixes.length - 1) process.stdout.write(`  patched ${ok}/${i + 1}\r`);
    }
  }
  await Promise.all(Array.from({ length: 8 }, worker));
  console.log(`\nPhase 2 done. Updated ${ok}/${fixes.length} at-large events.`);
}

async function main() {
  console.log(`Mode: ${WRITE ? "WRITE" : "DRY RUN"}\n`);
  try {
    const s = await getSecrets(["ANTHROPIC_API_KEY"]);
    ANTHROPIC_API_KEY = s.ANTHROPIC_API_KEY || process.env.ANTHROPIC_API_KEY || null;
  } catch (_) { ANTHROPIC_API_KEY = process.env.ANTHROPIC_API_KEY || null; }
  console.log(`Claude town geocode: ${ANTHROPIC_API_KEY ? "available" : "UNAVAILABLE — Phase 2 will skip"}\n`);

  console.log("Loading municipal events with venue + coords...");
  const events = await fetchMunicipalEvents();
  console.log(`${events.length} candidate events`);

  console.log("Loading venues table for name lookup...");
  const venues = await fetchAllVenues();
  console.log(`${venues.length} venues with coords\n`);

  // Index venues by lowercase name. Duplicate names get an array —
  // pick by city later.
  const byName = new Map();
  for (const v of venues) {
    if (!v.name) continue;
    const k = v.name.toLowerCase();
    if (!byName.has(k)) byName.set(k, []);
    byName.get(k).push(v);
  }

  const fixes = [];
  let skippedAmbiguous = 0;
  for (const e of events) {
    const k = (e.venue_name || "").toLowerCase();
    const candidates = byName.get(k) || [];
    if (candidates.length === 0) continue;

    // Match disambiguation. Generic names like "Memorial Park" or
    // "Community Center" appear in many cities. Even when only 1 candidate
    // exists in the venues table, the match is still WRONG if it's in a
    // different city than the event — e.g. "Memorial Park" at Indiana, PA
    // (the municipal Pittsburgh source) cannot be the "Memorial Park" in
    // Providence, RI just because Providence's is the only one we have.
    //
    // Rule: when BOTH the event and the candidate have a city, the cities
    // MUST match. We don't fall back to "any candidate" — better to leave
    // the bad coords than overwrite with worse ones from a random city.
    let v = null;
    const eventCity = (e.venue_city || "").toLowerCase();
    const cityMatches = candidates.filter(
      (c) => eventCity && (c.city || "").toLowerCase() === eventCity,
    );
    if (cityMatches.length > 0) {
      v = cityMatches[0];
    } else if (candidates.length === 1 && !eventCity) {
      // Edge case: event has no city info and exactly 1 candidate exists.
      // The name had to be specific enough to be unique in our venues
      // table, so this is probably safe. Most municipal feeds DO set
      // venue_city though, so this branch is rare.
      v = candidates[0];
    }
    if (!v) {
      skippedAmbiguous++;
      continue;
    }

    const delta = geoDistance(
      { lat: e.venue_lat, lng: e.venue_lng },
      { lat: v.lat, lng: v.lng },
    );
    if (delta < MIN_DELTA) continue;
    fixes.push({
      eventId: e.id,
      title: e.title,
      venueName: e.venue_name,
      venueCity: e.venue_city,
      before: { lat: e.venue_lat, lng: e.venue_lng },
      after: { lat: v.lat, lng: v.lng, city: v.city },
      source: e.source,
      delta,
    });
  }

  fixes.sort((a, b) => b.delta - a.delta);
  console.log(`${fixes.length} rows to update (Δ ≥ ${MIN_DELTA}°)`);
  console.log(`${skippedAmbiguous} rows skipped due to ambiguous venue names with no city match\n`);
  for (const f of fixes.slice(0, 30)) {
    console.log(`Δ${f.delta.toFixed(2)}° [${f.source}] "${f.title.slice(0, 60)}" @ ${f.venueName} (${f.venueCity || "?"})`);
    console.log(`  ${f.before.lat.toFixed(4)},${f.before.lng.toFixed(4)} → ${f.after.lat.toFixed(4)},${f.after.lng.toFixed(4)} (${f.after.city || "?"})`);
  }
  if (fixes.length > 30) console.log(`  ... and ${fixes.length - 30} more\n`);

  if (!WRITE) {
    console.log("\nDRY RUN — pass WRITE=1 to apply Phase 1.");
    await backfillAtLarge();
    return;
  }
  if (fixes.length === 0) { await backfillAtLarge(); return; }

  // PATCH each event individually — the new coords differ per row, so we
  // can't batch them into a single in.() call.
  let ok = 0;
  let index = 0;
  async function worker() {
    while (index < fixes.length) {
      const i = index++;
      const f = fixes[i];
      const url = `${SUPABASE_URL}/rest/v1/events?id=eq.${f.eventId}`;
      try {
        const res = await fetch(url, {
          method: "PATCH",
          headers: { ...headers, Prefer: "return=minimal" },
          body: JSON.stringify({
            venue_lat: f.after.lat,
            venue_lng: f.after.lng,
            updated_at: new Date().toISOString(),
          }),
        });
        if (res.ok) ok++;
      } catch (_) {}
      if ((i + 1) % 50 === 0 || i === fixes.length - 1) {
        process.stdout.write(`  patched ${ok}/${i + 1}\r`);
      }
    }
  }
  await Promise.all(Array.from({ length: 8 }, worker));
  console.log("");
  console.log(`Phase 1 done. Updated ${ok}/${fixes.length} named-venue events.`);
  await backfillAtLarge();
}

main().catch((e) => { console.error("Fatal:", e); process.exit(1); });
