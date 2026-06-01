/**
 * fix_municipal_centroid_coords.js — Retroactive, ALL-METRO / ALL-STATE repair
 * for events (and venues) whose coords were stamped with a metro CENTER instead
 * of their real location.
 *
 * Background: an older sync_municipal_feeds.js (and the un-gated
 * backfill_municipal_coords.js) defaulted venue_lat/lng to the FEED's metro
 * center when a feed entry omitted coords. Because discover_municipal_feeds.js
 * assigns whole regions to one `nearest_metro`, this stamped out-of-town events
 * (Scranton, Haverford → "philadelphia"; MASS MoCA → "boston") with the metro
 * centroid. To a user whose location IS that centroid they render "Here" / "0
 * mi". The write-time leak is fixed; this repairs rows already in the DB.
 *
 * Generalization vs the old Boston-only script: instead of a hardcoded centroid
 * + a hand-maintained neighborhood allowlist, this reads EVERY metro from the
 * `metros` table and treats "venue_city != the metro's own city" as the
 * smoking gun. Geocoding the real venue_city is always >= as accurate as the
 * metro centroid, so no allowlist is needed and it works for every state we
 * launch — no code change per metro.
 *
 * What it does, per metro centroid (lat,lng from metros):
 *   - EVENTS: active, future rows sitting within ~50m of that centroid, WITH a
 *     venue_name (at-large civic events legitimately use the center — skipped),
 *     WHERE cleanCity(venue_city) != the metro's city. Geocode venue_city +
 *     venue_state via Google Places (cached in geo_city_cache), overwrite
 *     events.venue_lat/lng.
 *   - VENUES (only with INCLUDE_VENUES=1): venues sitting within ~50m of a
 *     centroid WHERE city != the metro's city. State derived from the metro's
 *     state_code (venues has no state column). Overwrite venues.lat/lng.
 *
 * Safety:
 *   - Exact-ish centroid match (~50m box) is a smoking-gun default; no real
 *     distinct venue sits on a metro centroid. Geocoded locality result in the
 *     right state bbox is strictly better.
 *   - Skips rows whose venue_city matches the metro city (genuinely downtown).
 *   - Skips events with no venue_name (at-large — center is correct for them).
 *   - DRY RUN by default. Prints per-metro / per-city counts. WRITE=1 to apply.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/fix_municipal_centroid_coords.js
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 node scripts/fix_municipal_centroid_coords.js
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 INCLUDE_VENUES=1 node scripts/fix_municipal_centroid_coords.js
 *   SUPABASE_SERVICE_KEY=ey... METRO=philadelphia node scripts/fix_municipal_centroid_coords.js
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 LIMIT=200 node scripts/fix_municipal_centroid_coords.js
 *
 * Re-uses geo_city_cache (shared with backfill_event_coords.js /
 * fix_boston_centroid_venue_coords.js). On a cache miss it geocodes and, in
 * WRITE mode, persists the city so re-runs are cheap.
 */

const fs = require("fs");
const path = require("path");
const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

const WRITE = process.env.WRITE === "1";
const REVERT = process.env.REVERT || null; // path to a manifest to undo

// Rollback manifest: one entry per applied write so the run is fully
// reversible (matches the dedupe_venues_fuzzy.js pattern).
const MANIFEST = [];
function recordWrite(table, id, before, after) {
  MANIFEST.push({ table, id, before, after });
}
function flushManifest() {
  if (!WRITE || MANIFEST.length === 0) return null;
  const dir = path.join(__dirname, ".manifests");
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `centroid_coords_${Date.now()}.json`);
  fs.writeFileSync(file, JSON.stringify(MANIFEST, null, 2));
  return file;
}
const INCLUDE_VENUES = process.env.INCLUDE_VENUES === "1";
const LIMIT = parseInt(process.env.LIMIT || "0", 10) || null;
const ONLY_METRO = (process.env.METRO || "").trim().toLowerCase() || null;

// Half-width of the centroid match box, in degrees. ~0.0005° ≈ 50m. A real
// distinct venue effectively never sits this close to the exact metro centroid.
const EPS = 0.0005;

// Optional per-state sanity bbox for geocoded results. States not listed skip
// the per-state check and fall back to the US bbox only.
const STATE_BBOXES = {
  MA: { latMin: 41.10, latMax: 42.95, lngMin: -73.55, lngMax: -69.85 },
  RI: { latMin: 41.10, latMax: 42.10, lngMin: -71.95, lngMax: -71.05 },
  NH: { latMin: 42.65, latMax: 45.35, lngMin: -72.60, lngMax: -70.55 },
  CT: { latMin: 40.95, latMax: 42.10, lngMin: -73.80, lngMax: -71.75 },
  VT: { latMin: 42.70, latMax: 45.05, lngMin: -73.50, lngMax: -71.40 },
  ME: { latMin: 42.95, latMax: 47.50, lngMin: -71.10, lngMax: -66.85 },
  NY: { latMin: 40.45, latMax: 45.05, lngMin: -79.85, lngMax: -71.80 },
  NJ: { latMin: 38.85, latMax: 41.40, lngMin: -75.60, lngMax: -73.85 },
  PA: { latMin: 39.65, latMax: 42.30, lngMin: -80.55, lngMax: -74.65 },
  MD: { latMin: 37.85, latMax: 39.75, lngMin: -79.50, lngMax: -75.00 },
  DE: { latMin: 38.40, latMax: 39.85, lngMin: -75.80, lngMax: -75.00 },
};
const US_BBOX = { latMin: 24, latMax: 50, lngMin: -125, lngMax: -66 };
const LOCALITY_TYPES = new Set([
  "locality", "administrative_area_level_3", "neighborhood",
  "sublocality", "postal_town",
]);

let SUPABASE_KEY = null;
let GOOGLE_KEY = null;

function inBbox(lat, lng, box) {
  return lat >= box.latMin && lat <= box.latMax && lng >= box.lngMin && lng <= box.lngMax;
}

function cleanCityName(s) {
  return String(s || "").replace(/\s*\([^)]*\)\s*/g, "").trim();
}

async function supaGet(path) {
  for (let attempt = 1; attempt <= 3; attempt++) {
    const res = await fetch(`${SUPABASE_URL}/rest/v1/${path}`, {
      headers: { apikey: SUPABASE_KEY, Authorization: `Bearer ${SUPABASE_KEY}` },
    });
    if (res.ok) return res.json();
    const body = await res.text();
    if (res.status < 500 || attempt === 3) {
      throw new Error(`GET ${path}: ${res.status} ${body}`);
    }
    console.warn(`  retry ${attempt}/3 (status ${res.status})`);
    await new Promise((r) => setTimeout(r, 1500 * attempt));
  }
}

async function supaPatch(table, idCol, idVal, patch) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/${table}?${idCol}=eq.${encodeURIComponent(idVal)}`, {
    method: "PATCH",
    headers: {
      apikey: SUPABASE_KEY,
      Authorization: `Bearer ${SUPABASE_KEY}`,
      "Content-Type": "application/json",
      Prefer: "return=minimal",
    },
    body: JSON.stringify(patch),
  });
  return res.ok;
}

async function supaUpsert(table, row) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/${table}`, {
    method: "POST",
    headers: {
      apikey: SUPABASE_KEY,
      Authorization: `Bearer ${SUPABASE_KEY}`,
      "Content-Type": "application/json",
      Prefer: "resolution=merge-duplicates",
    },
    body: JSON.stringify(row),
  });
  return res.ok;
}

async function geocodeCity(city, state) {
  const query = state ? `${city}, ${state}` : city;
  try {
    const res = await fetch("https://places.googleapis.com/v1/places:searchText", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": GOOGLE_KEY,
        "X-Goog-FieldMask": "places.location,places.displayName,places.types,places.formattedAddress",
      },
      body: JSON.stringify({ textQuery: query, maxResultCount: 20 }),
    });
    if (!res.ok) return { lat: null, lng: null };
    const data = await res.json();
    const cityLc = city.toLowerCase();
    for (const place of data.places || []) {
      const types = place.types || [];
      if (!types.some((t) => LOCALITY_TYPES.has(t))) continue;
      const addr = (place.formattedAddress || "").toLowerCase();
      if (addr && !addr.includes(cityLc)) continue;
      const loc = place.location;
      if (!loc || typeof loc.latitude !== "number" || typeof loc.longitude !== "number") continue;
      const lat = loc.latitude;
      const lng = loc.longitude;
      if (!inBbox(lat, lng, US_BBOX)) continue;
      if (state) {
        const box = STATE_BBOXES[state.toUpperCase()];
        if (box && !inBbox(lat, lng, box)) continue;
      }
      return { lat, lng };
    }
    return { lat: null, lng: null };
  } catch (_) {
    return { lat: null, lng: null };
  }
}

async function loadCityCache() {
  const rows = await supaGet("geo_city_cache?select=city,state,country,lat,lng&limit=10000");
  const map = new Map();
  for (const r of rows) {
    if (r.lat != null && r.lng != null) {
      if (!inBbox(r.lat, r.lng, US_BBOX)) continue;
      if (r.state) {
        const box = STATE_BBOXES[r.state.toUpperCase()];
        if (box && !inBbox(r.lat, r.lng, box)) continue;
      }
    }
    map.set(
      `${r.city.toLowerCase()}|${(r.state || "").toLowerCase()}|${(r.country || "US").toLowerCase()}`,
      r,
    );
  }
  return map;
}

let geocodedCalls = 0;
async function resolveCity(city, state, cache) {
  const key = `${city.toLowerCase()}|${(state || "").toLowerCase()}|us`;
  if (cache.has(key)) return cache.get(key);
  const { lat, lng } = await geocodeCity(city, state);
  geocodedCalls++;
  const entry = { city, state: state || "", country: "US", lat, lng };
  cache.set(key, entry);
  if (WRITE && lat != null) {
    await supaUpsert("geo_city_cache", { ...entry, source: "google_places" });
  }
  return entry;
}

// Page a table for rows sitting within EPS of (lat,lng). Keyset on id.
async function* pageNearCentroid(table, selectCols, lat, lng, extraFilters) {
  const PAGE = 500;
  let cursor = null;
  while (true) {
    const cursorClause = cursor ? `&id=gt.${cursor}` : "";
    const rows = await supaGet(
      `${table}?select=${selectCols}` +
        `&venue_lat=gte.${(lat - EPS).toFixed(7)}&venue_lat=lte.${(lat + EPS).toFixed(7)}` +
        `&venue_lng=gte.${(lng - EPS).toFixed(7)}&venue_lng=lte.${(lng + EPS).toFixed(7)}` +
        `${extraFilters}&order=id.asc&limit=${PAGE}${cursorClause}`,
    );
    if (rows.length === 0) return;
    yield rows;
    if (rows.length < PAGE) return;
    cursor = rows[rows.length - 1].id;
  }
}

// venues uses lat/lng (not venue_lat/lng) — small wrapper.
async function* pageVenuesNearCentroid(lat, lng) {
  const PAGE = 500;
  let cursor = null;
  while (true) {
    const cursorClause = cursor ? `&id=gt.${cursor}` : "";
    const rows = await supaGet(
      `venues?select=id,name,city,metro_area,lat,lng` +
        `&lat=gte.${(lat - EPS).toFixed(7)}&lat=lte.${(lat + EPS).toFixed(7)}` +
        `&lng=gte.${(lng - EPS).toFixed(7)}&lng=lte.${(lng + EPS).toFixed(7)}` +
        `&city=not.is.null&order=id.asc&limit=${PAGE}${cursorClause}`,
    );
    if (rows.length === 0) return;
    yield rows;
    if (rows.length < PAGE) return;
    cursor = rows[rows.length - 1].id;
  }
}

async function main() {
  const secrets = await getSecrets(["GOOGLE_PLACES_API_KEY"]);
  SUPABASE_KEY = secrets.SUPABASE_SERVICE_KEY;
  GOOGLE_KEY = secrets.GOOGLE_PLACES_API_KEY || process.env.GOOGLE_PLACES_API_KEY;

  // ── REVERT: restore coords from a prior run's manifest, then exit. ──
  if (REVERT) {
    const entries = JSON.parse(fs.readFileSync(REVERT, "utf8"));
    console.log(`REVERT: restoring ${entries.length} rows from ${REVERT}\n`);
    let restored = 0;
    for (const e of entries) {
      const patch = e.table === "events"
        ? { venue_lat: e.before.lat, venue_lng: e.before.lng }
        : { lat: e.before.lat, lng: e.before.lng };
      if (await supaPatch(e.table, "id", e.id, patch)) restored++;
      if (restored % 100 === 0) process.stdout.write(`  restored ${restored}\r`);
    }
    console.log(`\nDone. Restored ${restored}/${entries.length} rows.`);
    return;
  }

  if (!GOOGLE_KEY) {
    console.error("Missing GOOGLE_PLACES_API_KEY");
    process.exit(1);
  }

  console.log(
    `Mode: ${WRITE ? "WRITE" : "DRY RUN"}` +
      `${INCLUDE_VENUES ? " +venues" : ""}` +
      `${ONLY_METRO ? ` (metro=${ONLY_METRO})` : ""}` +
      `${LIMIT ? ` (LIMIT=${LIMIT})` : ""}\n`,
  );

  let metros = await supaGet("metros?is_active=eq.true&select=name,state_code,lat,lng");
  metros = metros.filter((m) => m.lat != null && m.lng != null);
  if (ONLY_METRO) metros = metros.filter((m) => (m.name || "").toLowerCase() === ONLY_METRO);
  console.log(`Metros to scan: ${metros.length} (${metros.map((m) => m.name).join(", ")})\n`);

  const cityCache = await loadCityCache();
  console.log(`Cached cities: ${cityCache.size}\n`);

  const startIso = new Date().toISOString();
  let totalSeen = 0;
  let eventsFixed = 0;
  let venuesFixed = 0;
  let skippedSameCity = 0;
  let skippedNoCity = 0;
  let skippedNoGeocode = 0;

  for (const m of metros) {
    const metroCity = (m.name || "").toLowerCase();
    const metroState = (m.state_code || "").trim() || null;
    let metroEvents = 0;

    // ── EVENTS ──
    for await (const page of pageNearCentroid(
      "events",
      "id,venue_city,venue_state,venue_lat,venue_lng",
      m.lat,
      m.lng,
      `&is_active=eq.true&start_at=gte.${startIso}&venue_name=not.is.null&venue_city=not.is.null`,
    )) {
      for (const e of page) {
        totalSeen++;
        if (LIMIT && totalSeen > LIMIT) { console.log(`\nLIMIT ${LIMIT} reached.`); printSummary(); return; }

        const cityName = cleanCityName(e.venue_city);
        if (!cityName) { skippedNoCity++; continue; }
        // Genuinely in the metro's own city → center is ~right, leave it.
        if (cityName.toLowerCase() === metroCity) { skippedSameCity++; continue; }

        const stateName = (e.venue_state || "").trim() || metroState;
        const resolved = await resolveCity(cityName, stateName, cityCache);
        if (resolved.lat == null) { skippedNoGeocode++; continue; }

        if (!WRITE) {
          if (metroEvents < 8) {
            console.log(`  [${m.name}] event ${e.id} (${cityName}, ${stateName || "?"}) ` +
              `${(+e.venue_lat).toFixed(4)},${(+e.venue_lng).toFixed(4)} → ${resolved.lat.toFixed(4)},${resolved.lng.toFixed(4)}`);
          }
          metroEvents++; eventsFixed++; continue;
        }
        const ok = await supaPatch("events", "id", e.id, {
          venue_lat: resolved.lat,
          venue_lng: resolved.lng,
          updated_at: new Date().toISOString(),
        });
        if (ok) {
          recordWrite("events", e.id, { lat: +e.venue_lat, lng: +e.venue_lng }, { lat: resolved.lat, lng: resolved.lng });
          metroEvents++; eventsFixed++;
        }
      }
    }

    // ── VENUES (opt-in) ──
    let metroVenues = 0;
    if (INCLUDE_VENUES) {
      for await (const page of pageVenuesNearCentroid(m.lat, m.lng)) {
        for (const v of page) {
          totalSeen++;
          if (LIMIT && totalSeen > LIMIT) { console.log(`\nLIMIT ${LIMIT} reached.`); printSummary(); return; }
          const cityName = cleanCityName(v.city);
          if (!cityName) { skippedNoCity++; continue; }
          if (cityName.toLowerCase() === metroCity) { skippedSameCity++; continue; }
          const resolved = await resolveCity(cityName, metroState, cityCache);
          if (resolved.lat == null) { skippedNoGeocode++; continue; }
          if (!WRITE) {
            if (metroVenues < 5) {
              console.log(`  [${m.name}] venue ${v.id} "${(v.name || "").slice(0, 40)}" (${cityName}) ` +
                `→ ${resolved.lat.toFixed(4)},${resolved.lng.toFixed(4)}`);
            }
            metroVenues++; venuesFixed++; continue;
          }
          const ok = await supaPatch("venues", "id", v.id, { lat: resolved.lat, lng: resolved.lng });
          if (ok) {
            recordWrite("venues", v.id, { lat: +v.lat, lng: +v.lng }, { lat: resolved.lat, lng: resolved.lng });
            metroVenues++; venuesFixed++;
          }
        }
      }
    }

    if (metroEvents || metroVenues) {
      console.log(`  ${m.name}: ${metroEvents} events${INCLUDE_VENUES ? `, ${metroVenues} venues` : ""}`);
    }
  }

  printSummary();

  function printSummary() {
    console.log(`\nSummary (rows scanned: ${totalSeen})`);
    console.log(`  events ${WRITE ? "fixed" : "to fix"}:           ${eventsFixed}`);
    if (INCLUDE_VENUES) console.log(`  venues ${WRITE ? "fixed" : "to fix"}:           ${venuesFixed}`);
    console.log(`  skipped (city == metro city):  ${skippedSameCity}`);
    console.log(`  skipped (no city):             ${skippedNoCity}`);
    console.log(`  skipped (geocode rejected):    ${skippedNoGeocode}`);
    console.log(`  geocoder calls:                ${geocodedCalls}`);
    if (!WRITE) {
      console.log(`\nDRY RUN — pass WRITE=1 to apply.`);
    } else {
      const file = flushManifest();
      if (file) console.log(`\nRollback manifest: ${file}\n  Undo with: REVERT=${file} node scripts/fix_municipal_centroid_coords.js`);
    }
  }
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
