/**
 * backfill_event_coords.js — Populate events.event_lat / event_lng by
 * geocoding the event's city (event_city if set, else venue_city), using a
 * shared cache (geo_city_cache) so each unique (city, state) pair costs at
 * most one Google Places call.
 *
 * Two reasons to set event_lat:
 *   1. event_city was set explicitly (curator marked it different from the
 *      venue's HQ city) → geocode and store, no questions asked.
 *   2. venue_city is set but venue_lat looks suspicious (the geocoded
 *      venue_city is more than DRIFT_THRESHOLD_MILES away from venue_lat).
 *      This is the "Mass Audubon Lenox event tagged with Boston coords"
 *      class of bug — venue_city says Lenox but venue_lat is near Boston
 *      because the venues row is the org HQ.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/backfill_event_coords.js                 # dry
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 node scripts/backfill_event_coords.js         # apply
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 LIMIT=500 node scripts/backfill_event_coords.js   # cap test
 *
 * Cost guard: every uncached city → 1 Google Places searchText call. MA has
 * ~350 towns; even with full backfill the geocode bill is bounded by the
 * geo_city_cache. Re-runs are free for cached cities.
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

const WRITE = process.env.WRITE === "1";
const LIMIT = parseInt(process.env.LIMIT || "0", 10) || null;
const DRIFT_THRESHOLD_MILES = 10;
// Drifts > this are usually city-name ambiguity (multiple "Penn Township"s
// in PA) rather than a defaulted venue centroid. Google's text search
// returns the most populous match for ambiguous names, which is often
// wrong, and there's no second candidate to disambiguate from. The cap
// surfaces these for manual review instead of writing wrong coords.
const MAX_DRIFT_MILES = 100;

// Bounding boxes for sanity-checking geocoded results. If venue_state is set,
// the geocoded coords must fall inside the state's box (otherwise the state
// label is probably wrong — see the Providence-tagged-MA case that wrote
// garbage on the first run). Boxes are loose: ~5 mi outside the real
// borders so coastal/border towns aren't false-rejected.
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

// Only Google Place types that represent an actual city/town/neighborhood.
// Filters out hits like "Providence Road" or "Foo Cafe in Providence" that
// the geocoder returns when the literal query has no matching locality.
//
// Deliberately excludes `administrative_area_level_2` — that's "county" in
// most US contexts, and a county centroid can land offshore (the
// "Providence, MA" → 42.05, -70.18 ocean-coord case was Plymouth County's
// centroid, which sits in Cape Cod Bay).
const LOCALITY_TYPES = new Set([
  "locality",
  "administrative_area_level_3",
  "neighborhood",
  "sublocality",
  "postal_town",
]);

let SUPABASE_KEY = null;
let GOOGLE_KEY = null;

function inBbox(lat, lng, box) {
  return lat >= box.latMin && lat <= box.latMax && lng >= box.lngMin && lng <= box.lngMax;
}

// Strip editorial parentheticals from city names. "Lenox (library)" geocodes
// worse than "Lenox" because Google searches the literal phrase. The
// parenthetical was added by a crawler annotating which venue type, not the
// city name itself.
function cleanCityName(s) {
  return String(s || "").replace(/\s*\([^)]*\)\s*/g, "").trim();
}

function distanceMiles(lat1, lng1, lat2, lng2) {
  // Same haversine the edge function uses.
  const R = 3958.8;
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLng = (lng2 - lng1) * Math.PI / 180;
  const rLat1 = lat1 * Math.PI / 180;
  const rLat2 = lat2 * Math.PI / 180;
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(rLat1) * Math.cos(rLat2) * Math.sin(dLng / 2) ** 2;
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

async function supaGet(path) {
  // Retry on 5xx — PostgREST statement_timeout fires transiently under load
  // (the events table is busy with classify/refresh jobs). Three attempts
  // with backoff before giving up.
  for (let attempt = 1; attempt <= 3; attempt++) {
    const res = await fetch(`${SUPABASE_URL}/rest/v1/${path}`, {
      headers: { apikey: SUPABASE_KEY, Authorization: `Bearer ${SUPABASE_KEY}` },
    });
    if (res.ok) return res.json();
    const body = await res.text();
    if (res.status < 500 || attempt === 3) {
      throw new Error(`GET ${path}: ${res.status} ${body}`);
    }
    console.warn(`  retry ${attempt}/3 (status ${res.status}): ${body.slice(0, 120)}`);
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

async function geocodeCity(city, state, biasLat = null, biasLng = null) {
  // Google Places searchText returns the place's geometry under
  // `places[0].location`. Also fetch `types` so we can reject non-locality
  // hits like "Providence Road" — the geocoder returns *something* for any
  // query, so without a type filter we end up writing garbage when the
  // literal city/state pair doesn't exist (Providence, MA).
  //
  // locationBias is a soft nudge toward biasLat/biasLng — disambiguates
  // city names with multiple matches in the same state (e.g. four "Penn
  // Township"s in PA, one of which is near Penn State while another is in
  // Westmoreland County). Without the bias, Google returns the most
  // populous match, which is wrong about a third of the time for these.
  const query = state ? `${city}, ${state}` : city;
  // Pull more candidates (20) so disambiguation has a fighting chance.
  // Common-name cities like "Penn Township, PA" have ≥4 matches; Google's
  // default ranking returns the most populous, which is usually the wrong
  // one when the actual event venue is in a small-population township
  // with the same name.
  const body = { textQuery: query, maxResultCount: 20 };
  if (biasLat != null && biasLng != null) {
    body.locationBias = {
      circle: {
        center: { latitude: biasLat, longitude: biasLng },
        radius: 50000.0, // 50 km — wide enough to cover defaulted metro centroids
      },
    };
  }
  try {
    const res = await fetch("https://places.googleapis.com/v1/places:searchText", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": GOOGLE_KEY,
        "X-Goog-FieldMask": "places.location,places.displayName,places.types,places.formattedAddress",
      },
      body: JSON.stringify(body),
    });
    if (!res.ok) return { lat: null, lng: null, reject: "http" };
    const data = await res.json();
    // Collect all valid locality candidates (passing type/address/bbox
    // filters), then pick the one closest to biasLat/biasLng when known.
    // Without this tiebreaker, queries like "Penn Township, PA" return the
    // most populous match (Westmoreland County) even when the actual event
    // venue is near a less-populous township with the same name (Centre
    // County). Proximity to venue_lat is the right disambiguator.
    const cityLc = city.toLowerCase();
    const candidates = [];
    for (const place of data.places || []) {
      const types = place.types || [];
      const isLocality = types.some((t) => LOCALITY_TYPES.has(t));
      if (!isLocality) continue;
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
      candidates.push({ lat, lng });
    }
    if (candidates.length === 0) return { lat: null, lng: null, reject: "no-locality-match" };
    if (biasLat != null && biasLng != null) {
      candidates.sort((a, b) =>
        distanceMiles(a.lat, a.lng, biasLat, biasLng) -
        distanceMiles(b.lat, b.lng, biasLat, biasLng)
      );
    }
    return candidates[0];
  } catch (_) {
    return { lat: null, lng: null, reject: "exception" };
  }
}

async function loadCityCache() {
  const rows = await supaGet("geo_city_cache?select=city,state,country,lat,lng&limit=10000");
  const map = new Map();
  let dropped = 0;
  for (const r of rows) {
    // Re-validate cached entries against the same bbox rules we use for
    // fresh geocodes. The first version of this script wrote unvalidated
    // results (e.g. "Providence, MA" → ocean coords); without this check
    // every subsequent dry-run loaded those rows back and "confirmed" the
    // bad coords. Dropping on load forces a re-geocode through the new
    // validated path.
    if (r.lat == null || r.lng == null) {
      // Cached "no result" rows are fine — keep them, they save API calls
      // on cities that genuinely don't resolve.
      map.set(`${r.city.toLowerCase()}|${(r.state || "").toLowerCase()}|${(r.country || "US").toLowerCase()}`, r);
      continue;
    }
    if (!inBbox(r.lat, r.lng, US_BBOX)) { dropped++; continue; }
    if (r.state) {
      const box = STATE_BBOXES[r.state.toUpperCase()];
      if (box && !inBbox(r.lat, r.lng, box)) { dropped++; continue; }
    }
    map.set(`${r.city.toLowerCase()}|${(r.state || "").toLowerCase()}|${(r.country || "US").toLowerCase()}`, r);
  }
  if (dropped > 0) console.log(`  Dropped ${dropped} cached entries that failed bbox checks (will re-geocode)`);
  return map;
}

async function resolveCity(city, state, cache, biasLat = null, biasLng = null) {
  // Cache key intentionally omits biasLat/biasLng — for a given (city, state)
  // we only want to resolve once. Bias only matters on the first miss; if
  // the cached coords ever look wrong for some events, the
  // STATE_BBOXES validation on load + manual cache delete is the recovery
  // path (the Penn Township case taught us this).
  const key = `${city.toLowerCase()}|${(state || "").toLowerCase()}|us`;
  if (cache.has(key)) return cache.get(key);
  const { lat, lng } = await geocodeCity(city, state, biasLat, biasLng);
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
    console.error("Missing GOOGLE_PLACES_API_KEY (not in Supabase secrets or env)");
    process.exit(1);
  }

  console.log(`Mode: ${WRITE ? "WRITE" : "DRY RUN"}${LIMIT ? ` (LIMIT=${LIMIT})` : ""}\n`);

  const cityCache = await loadCityCache();
  console.log(`Cached cities: ${cityCache.size}\n`);

  let geocodedCalls = 0;
  let drift = 0;       // updates because venue_lat looked wrong vs. geocoded city
  let novel = 0;       // updates because event_lat was simply null and we now have coords
  let skipped = 0;     // had cities but geocoder returned nothing
  let noState = 0;     // skipped because we refuse to geocode without a state
  let bigDrift = 0;    // skipped because drift > MAX_DRIFT_MILES (likely ambiguity)
  let totalSeen = 0;

  // Keyset pagination: walk events ordered by id, using id.gt.<cursor> to
  // step. PostgREST caps single responses at ~1000 rows; this loop lets a
  // single WRITE=1 run cover the entire backlog without re-invocation.
  // LIMIT (env var) still acts as a hard ceiling for sample runs.
  // Page size kept modest. The events table is busy and statement_timeout
  // fires on larger pages — 500 trades a few more round-trips for far fewer
  // timeouts. Combined with supaGet's retry-on-5xx, this gets through the
  // whole table reliably.
  const PAGE_SIZE = 500;
  const startIso = new Date().toISOString();
  let cursor = null;
  let pageEvents = [];

  outer: while (true) {
    const cursorClause = cursor ? `&id=gt.${cursor}` : "";
    pageEvents = await supaGet(
      `events?select=id,venue_city,venue_state,venue_lat,venue_lng,event_city,event_state` +
        `&event_lat=is.null&is_active=eq.true&start_at=gte.${startIso}` +
        `&venue_city=not.is.null&order=id.asc&limit=${PAGE_SIZE}${cursorClause}`,
    );
    if (pageEvents.length === 0) break;
    console.log(`Page: ${pageEvents.length} candidates (cursor=${cursor || "start"})`);

    for (const e of pageEvents) {
      totalSeen++;
      if (LIMIT && totalSeen > LIMIT) break outer;
    const rawCity = e.event_city || e.venue_city;
    const cityName = cleanCityName(rawCity);
    // Do NOT default the state. Defaulting to MA caused the original
    // Providence-tagged-MA garbage writes (Providence, RI venues with
    // venue_state mislabeled "MA" were geocoded as "Providence, MA"
    // which returned ocean coords).
    const stateName = (e.event_state || e.venue_state || "").trim() || null;
    if (!cityName) { skipped++; continue; }
    // Refuse to geocode without a state. Cities like "Lenox", "Lancaster",
    // "Springfield" exist in many states; without a state filter Google
    // returns whichever is most prominent globally and we end up writing
    // coords for the wrong place. Better to skip and surface via "noState"
    // than to write wrong coordinates.
    if (!stateName) { noState++; continue; }

    const cacheKeyBefore = cityCache.size;
    const resolved = await resolveCity(cityName, stateName, cityCache, e.venue_lat, e.venue_lng);
    if (cityCache.size > cacheKeyBefore && WRITE) geocodedCalls++;
    if (resolved.lat == null) { skipped++; continue; }

    let reason = null;
    if (e.event_city) {
      // Explicit event_city → trust it.
      reason = "novel-event_city";
      novel++;
    } else if (e.venue_lat == null) {
      // No venue coords at all, just adopt the city centroid.
      reason = "novel-no-venue-lat";
      novel++;
    } else {
      const off = distanceMiles(resolved.lat, resolved.lng, e.venue_lat, e.venue_lng);
      if (off > MAX_DRIFT_MILES) {
        // Too big a drift — likely city-name ambiguity, not bad venue
        // coords. Skip rather than write the wrong place.
        bigDrift++;
        if (!WRITE && bigDrift <= 5) {
          console.log(`  ${e.id} BIG-DRIFT-SKIP-${off.toFixed(1)}mi (${cityName}, ${stateName})`);
        }
        continue;
      }
      if (off > DRIFT_THRESHOLD_MILES) {
        reason = `drift-${off.toFixed(1)}mi`;
        drift++;
      } else {
        // venue_lat agrees with the city centroid — no need to set event_lat.
        continue;
      }
    }

    if (!WRITE) {
      if (novel + drift <= 10) {
        console.log(`  ${e.id} ${reason} (${cityName}, ${stateName}) → lat=${resolved.lat.toFixed(4)} lng=${resolved.lng.toFixed(4)}`);
      }
      continue;
    }

      await supaPatch("events", "id", e.id, {
        event_lat: resolved.lat,
        event_lng: resolved.lng,
        // Only persist event_city if it wasn't already set. Keeps the
        // column semantically "real event city, when known different from
        // venue". Use the cleaned city (parentheticals stripped).
        ...(e.event_city ? {} : { event_city: cityName, event_state: stateName }),
      });
    }
    // Advance cursor to last id in this page. If this page came back
    // shorter than PAGE_SIZE, we've hit the tail — break.
    cursor = pageEvents[pageEvents.length - 1].id;
    if (pageEvents.length < PAGE_SIZE) break;
  }

  console.log(`\nSummary  (events scanned: ${totalSeen})`);
  console.log(`  novel (no prior event_lat):  ${novel}`);
  console.log(`  drift (venue_lat overruled): ${drift}`);
  console.log(`  big-drift skipped (> ${MAX_DRIFT_MILES} mi — likely city-name ambiguity): ${bigDrift}`);
  console.log(`  skipped (no state — won't geocode without one): ${noState}`);
  console.log(`  skipped (no city or geocode rejected): ${skipped}`);
  if (WRITE) console.log(`  geocoder calls billed:       ${geocodedCalls}`);
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
