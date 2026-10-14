/**
 * fix_boston_centroid_venue_coords.js — Repair events whose venue_lat/lng
 * were stamped with the literal Boston centroid (42.3601, -71.0589) by an
 * older version of sync_municipal_feeds.js. The fix in commit 525c0c8
 * (2026-05-20) stopped *new* events from picking up this default, but
 * thousands of pre-existing rows still have the bogus coords, so distance
 * calculations in the For You feed + Explore tab render Berkshires events
 * as "Here" / "0.9 mi" when the user is in Boston.
 *
 * Strategy: find events where venue_lat,lng equals the Boston centroid AND
 * venue_city is *not* a Boston-area neighborhood/town, then geocode the
 * city via Google Places (reusing geo_city_cache from
 * backfill_event_coords.js) and overwrite events.venue_lat/lng with the
 * city centroid. Distance renders correctly across all client paths.
 *
 * Why this is safe to skip the drift cap:
 *   - The exact (42.3601, -71.0589) match is a smoking-gun default; there
 *     is no real venue at that coord. Any geocoded result with a real
 *     locality type in the right state is strictly better.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/fix_boston_centroid_venue_coords.js
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 node scripts/fix_boston_centroid_venue_coords.js
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 LIMIT=200 node scripts/fix_boston_centroid_venue_coords.js
 *
 * Re-uses the geocoding/validation/cache code by requiring backfill_event_coords.js
 * to have been run at least once (so geo_city_cache has city entries).
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

const WRITE = process.env.WRITE === "1";
const LIMIT = parseInt(process.env.LIMIT || "0", 10) || null;

// The literal default that the pre-2026-05-20 sync_municipal_feeds.js
// stamped on events whose feed didn't provide coords. Match exactly.
const BOSTON_LAT = 42.3601;
const BOSTON_LNG = -71.0589;

// Boston neighborhoods + adjacent core cities — events truly in these
// places legitimately sit near the Boston centroid, so skip them.
const BOSTON_AREA_CITIES = new Set([
  "boston", "cambridge", "somerville", "brookline", "watertown", "allston",
  "brighton", "charlestown", "dorchester", "roxbury", "south boston",
  "east boston", "jamaica plain", "hyde park", "mattapan", "west roxbury",
  "roslindale", "fenway", "back bay", "beacon hill", "downtown", "north end",
  "south end", "chinatown", "seaport",
]);

// Reused from backfill_event_coords.js — same bbox + locality logic, copied
// to avoid module wiring. Keep in sync if backfill_event_coords.js changes.
const STATE_BBOXES = {
  MA: { latMin: 41.10, latMax: 42.95, lngMin: -73.55, lngMax: -69.85 },
  RI: { latMin: 41.10, latMax: 42.10, lngMin: -71.95, lngMax: -71.05 },
  NH: { latMin: 42.65, latMax: 45.35, lngMin: -72.60, lngMax: -70.55 },
  CT: { latMin: 40.95, latMax: 42.10, lngMin: -73.80, lngMax: -71.75 },
  VT: { latMin: 42.70, latMax: 45.05, lngMin: -73.50, lngMax: -71.40 },
  ME: { latMin: 42.95, latMax: 47.50, lngMin: -71.10, lngMax: -66.85 },
  NY: { latMin: 40.45, latMax: 45.05, lngMin: -79.85, lngMax: -71.80 },
  PA: { latMin: 39.65, latMax: 42.30, lngMin: -80.55, lngMax: -74.65 },
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
      // Re-validate the same way backfill_event_coords.js does.
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

async function resolveCity(city, state, cache) {
  const key = `${city.toLowerCase()}|${(state || "").toLowerCase()}|us`;
  if (cache.has(key)) return cache.get(key);
  const { lat, lng } = await geocodeCity(city, state);
  const entry = { city, state: state || "", country: "US", lat, lng };
  cache.set(key, entry);
  if (WRITE) {
    await supaUpsert("geo_city_cache", { ...entry, source: "google_places" });
  }
  return entry;
}

async function main() {
  const secrets = await getSecrets(["GOOGLE_PLACES_API_KEY"]);
  SUPABASE_KEY = secrets.SUPABASE_SERVICE_KEY;
  GOOGLE_KEY = secrets.GOOGLE_PLACES_API_KEY || process.env.GOOGLE_PLACES_API_KEY;
  if (!GOOGLE_KEY) {
    console.error("Missing GOOGLE_PLACES_API_KEY");
    process.exit(1);
  }

  console.log(`Mode: ${WRITE ? "WRITE" : "DRY RUN"}${LIMIT ? ` (LIMIT=${LIMIT})` : ""}\n`);

  const cityCache = await loadCityCache();
  console.log(`Cached cities: ${cityCache.size}\n`);

  let geocodedCalls = 0;
  let fixed = 0;
  let skippedBostonArea = 0;
  let skippedNoState = 0;
  let skippedNoGeocode = 0;
  let totalSeen = 0;

  const PAGE_SIZE = 500;
  const startIso = new Date().toISOString();
  let cursor = null;

  outer: while (true) {
    const cursorClause = cursor ? `&id=gt.${cursor}` : "";
    // Narrow the candidate set on the server: only rows with the exact
    // Boston-centroid coords, active, future, with a venue_city set.
    // Cheap, well-indexed (venue_lat,lng + start_at + is_active).
    const pageEvents = await supaGet(
      `events?select=id,venue_city,venue_state,venue_lat,venue_lng` +
        `&venue_lat=eq.${BOSTON_LAT}&venue_lng=eq.${BOSTON_LNG}` +
        `&is_active=eq.true&start_at=gte.${startIso}` +
        `&venue_city=not.is.null&order=id.asc&limit=${PAGE_SIZE}${cursorClause}`,
    );
    if (pageEvents.length === 0) break;
    console.log(`Page: ${pageEvents.length} candidates (cursor=${cursor || "start"})`);

    for (const e of pageEvents) {
      totalSeen++;
      if (LIMIT && totalSeen > LIMIT) break outer;

      const rawCity = e.venue_city;
      const cityName = cleanCityName(rawCity);
      const stateName = (e.venue_state || "").trim() || null;

      if (!cityName) { skippedNoGeocode++; continue; }

      // Don't touch genuinely Boston-area events — their coords are fine.
      if (BOSTON_AREA_CITIES.has(cityName.toLowerCase())) {
        skippedBostonArea++;
        continue;
      }

      if (!stateName) { skippedNoState++; continue; }

      const cacheKeyBefore = cityCache.size;
      const resolved = await resolveCity(cityName, stateName, cityCache);
      if (cityCache.size > cacheKeyBefore && WRITE) geocodedCalls++;
      if (resolved.lat == null) { skippedNoGeocode++; continue; }

      if (!WRITE) {
        if (fixed <= 10) {
          console.log(`  ${e.id} (${cityName}, ${stateName}) → lat=${resolved.lat.toFixed(4)} lng=${resolved.lng.toFixed(4)}`);
        }
        fixed++;
        continue;
      }

      const ok = await supaPatch("events", "id", e.id, {
        venue_lat: resolved.lat,
        venue_lng: resolved.lng,
      });
      if (ok) fixed++;
    }
    cursor = pageEvents[pageEvents.length - 1].id;
    if (pageEvents.length < PAGE_SIZE) break;
  }

  console.log(`\nSummary  (candidates scanned: ${totalSeen})`);
  console.log(`  fixed (venue_lat/lng overwritten):     ${fixed}`);
  console.log(`  skipped (truly Boston-area city):      ${skippedBostonArea}`);
  console.log(`  skipped (no state):                    ${skippedNoState}`);
  console.log(`  skipped (no city or geocode rejected): ${skippedNoGeocode}`);
  if (WRITE) console.log(`  geocoder calls billed:                 ${geocodedCalls}`);
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
