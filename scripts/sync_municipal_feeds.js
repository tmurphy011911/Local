/**
 * sync_municipal_feeds.js — Sync events from municipal / city-government feeds
 *
 * Reads enabled feed configs from the `municipal_feeds` Supabase table. Each
 * row specifies a feed type (drupal_jsonapi, localist, ical, wp_tribe) and URL.
 * The script fetches, parses, normalizes to the events table schema, and
 * upserts to Supabase. Result of each feed (event count, ok/error) is written
 * back to the row so the discovery script can prune persistent failures.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/sync_municipal_feeds.js
 *
 * Adding feeds is now done by inserting rows into municipal_feeds (manually,
 * or automatically via discover_municipal_feeds.js).
 */

const crypto = require("crypto");
const { getSecrets, SUPABASE_URL } = require("./lib/secrets");
const { fetchHtml } = require("./lib/scrapfly_fetch");
const { extractEventsFromHtml, findPageUrls } = require("./lib/claude_html_extract");

let SCRAPFLY_API_KEY = null;
let ANTHROPIC_API_KEY = null;
let GOOGLE_PLACES_API_KEY = null;

const SUPABASE_SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").trim();
if (!SUPABASE_SERVICE_KEY) {
  console.error("Missing SUPABASE_SERVICE_KEY env var");
  process.exit(1);
}

// ── Helpers ──

function hashEvent(source, sourceId) {
  return `${source}:${sourceId}`;
}

// Decode HTML/XML entities. Used both inside stripHtml and standalone in
// buildEvent for titles (which don't go through stripHtml in any parser).
// Loops up to 5 times so double-encoded values like `&amp;#8211;` unwind
// fully — pass 1: &amp; → &, pass 2: &#8211; → en-dash. Without the loop,
// raw `&#8211;` leaked through municipal-feed titles like "Veteran Grave
// Flagging &#8211; Melrose Cemetery".
function decodeEntities(s) {
  let result = String(s || "");
  for (let i = 0; i < 5; i++) {
    const before = result;
    result = result
      .replace(/&amp;/g, "&")
      .replace(/&lt;/g, "<")
      .replace(/&gt;/g, ">")
      .replace(/&quot;/g, '"')
      .replace(/&apos;/g, "'")
      .replace(/&#39;/g, "'")
      .replace(/&nbsp;/g, " ")
      .replace(/&mdash;/g, "—")
      .replace(/&ndash;/g, "–")
      .replace(/&hellip;/g, "…")
      .replace(/&lsquo;/g, "‘")
      .replace(/&rsquo;/g, "’")
      .replace(/&ldquo;/g, "“")
      .replace(/&rdquo;/g, "”")
      .replace(/&#(\d+);/g, (_, n) => {
        const code = parseInt(n, 10);
        return Number.isFinite(code) ? String.fromCodePoint(code) : "";
      })
      .replace(/&#x([0-9a-fA-F]+);/g, (_, n) => {
        const code = parseInt(n, 16);
        return Number.isFinite(code) ? String.fromCodePoint(code) : "";
      });
    if (result === before) break;
  }
  return result;
}

// ── Geocoding (Google Places) ──
// Mirrors fix_municipal_centroid_coords.js: a US bbox + per-state bboxes so a
// geocode that lands in the wrong state (a "Doylestown, PA" result for an event
// the source says is in NJ) is rejected rather than stamped. This bbox guard is
// what enforces "drop rather than mislocate."
const US_BBOX = { latMin: 24, latMax: 50, lngMin: -125, lngMax: -66 };
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

function inBbox(lat, lng, box) {
  return lat >= box.latMin && lat <= box.latMax && lng >= box.lngMin && lng <= box.lngMax;
}

// Sanity-check a coordinate against a claimed state. Used to apply the same
// bbox guard to CLAUDE geocode results that googleGeocode already applies to
// Google's — without it, a Claude mis-geocode slips through (e.g. "Indiana, PA"
// resolved to Indiana the STATE at lng −86). Unknown state codes only get the
// coarse US check, so a bogus parsed state can't over-reject.
function coordsInState(lat, lng, state) {
  if (lat == null || lng == null) return false;
  if (!inBbox(lat, lng, US_BBOX)) return false;
  const box = state ? STATE_BBOXES[String(state).toUpperCase()] : null;
  if (box && !inBbox(lat, lng, box)) return false;
  return true;
}

// In-memory cache for the run, keyed by "query|expectState". Aggregator feeds
// reference the same venues/towns repeatedly — geocode each unique query once.
const GOOGLE_CACHE = new Map();

// Run-level tally of events dropped for lack of a confident location (see the
// drop path in buildEvent). Printed in the final totals so a run's drop RATE is
// visible at a glance — the key health signal when the fix can't be dev-tested.
let TOTAL_DROPPED = 0;

/**
 * Geocode a free-text query via Google Places, returning canonical
 * {lat, lng, city, state} or null. Works for both a venue ("Convention Hall,
 * Wildwood, NJ") and a bare town ("Wildwood, NJ"): we take the first result
 * that (a) lands in the US bbox, (b) lands in its own state's bbox, and (c) —
 * when the caller passes expectState (the state the SOURCE claimed) — actually
 * sits in that state. A mismatch returns null so the caller drops the event
 * instead of mislocating it.
 */
async function googleGeocode(query, expectState) {
  if (!GOOGLE_PLACES_API_KEY || !query) return null;
  const key = `${query.toLowerCase()}|${(expectState || "").toLowerCase()}`;
  if (GOOGLE_CACHE.has(key)) return GOOGLE_CACHE.get(key);

  let result = null;
  try {
    const res = await fetch("https://places.googleapis.com/v1/places:searchText", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": GOOGLE_PLACES_API_KEY,
        "X-Goog-FieldMask": "places.location,places.formattedAddress,places.addressComponents",
      },
      body: JSON.stringify({ textQuery: query, maxResultCount: 5 }),
    });
    if (res.ok) {
      const data = await res.json();
      const want = (expectState || "").toUpperCase();
      for (const place of data.places || []) {
        const loc = place.location;
        if (!loc || typeof loc.latitude !== "number" || typeof loc.longitude !== "number") continue;
        const lat = loc.latitude, lng = loc.longitude;
        if (!inBbox(lat, lng, US_BBOX)) continue;
        const comps = place.addressComponents || [];
        const comp = (type) => comps.find((c) => (c.types || []).includes(type));
        const stateShort = comp("administrative_area_level_1")?.shortText || null;
        const cityLong =
          (comp("locality") || comp("postal_town") ||
           comp("administrative_area_level_3") || comp("sublocality"))?.longText || null;
        // Reject if the result sits outside its own state's box (bad geocode).
        const rBox = stateShort ? STATE_BBOXES[stateShort.toUpperCase()] : null;
        if (rBox && !inBbox(lat, lng, rBox)) continue;
        // Reject if the source claimed a state and this result is elsewhere.
        if (want) {
          if (stateShort && stateShort.toUpperCase() !== want) continue;
          const wBox = STATE_BBOXES[want];
          if (wBox && !inBbox(lat, lng, wBox)) continue;
        }
        result = { lat, lng, city: cityLong, state: stateShort };
        break;
      }
    }
  } catch (err) {
    console.log(`    Google geocode error for "${query}": ${err.message}`);
  }
  if (result) {
    console.log(`    Google geocoded "${query}" → ${result.lat.toFixed(4)},${result.lng.toFixed(4)} (${result.city || "?"}, ${result.state || "?"})`);
  }
  GOOGLE_CACHE.set(key, result);
  return result;
}

// Pull a trailing "City, ST" (optionally with ZIP) out of an address/location
// string. Used by the iCal + RSS parsers, whose feeds carry the location only
// as free text. Returns { city, state } with nulls when nothing matches.
function parseCityState(str) {
  if (!str) return { city: null, state: null };
  // Preferred: a ", City, ST" span inside an address string.
  const m = String(str).match(/,\s*([A-Za-z][A-Za-z .'-]+?),\s*([A-Z]{2})(?:\s+\d{5})?\b/);
  if (m) return { city: m[1].trim(), state: m[2] };
  // Fallback for text with no leading comma (RSS title "Pirate Fest — Wildwood,
  // NJ", body "… in Doylestown, PA"). Cap the city to ≤3 words so free-form
  // prose ("Join us in Doylestown, PA") doesn't swallow the leading words.
  const m2 = String(str).match(
    /(?:^|[—–-]\s*|\bin\s+)([A-Za-z][A-Za-z.'-]*(?:\s+[A-Za-z][A-Za-z.'-]*){0,2}),\s*([A-Z]{2})\b/,
  );
  if (m2) return { city: m2[1].trim(), state: m2[2] };
  return { city: null, state: null };
}

function stripHtml(html) {
  if (!html) return "";
  // Tag-strip first, then run the full entity decoder, then collapse
  // whitespace. The previous implementation handled only 6 named entities
  // (amp/lt/gt/quot/#39/nbsp) and missed numeric/hex/curly-quote entities,
  // which is why description bodies still contained raw `&mdash;` and
  // `&#8211;`.
  return decodeEntities(html.replace(/<[^>]+>/g, " ")).replace(/\s+/g, " ").trim();
}

function detectCategory(title, body) {
  const text = `${title} ${body}`.toLowerCase();
  if (/\b(concert|music|band|dj|jazz|rock|hip.?hop|orchestra)\b/.test(text)) return "music";
  if (/\b(comedy|stand.?up|improv|open mic)\b/.test(text)) return "comedy";
  if (/\b(theater|theatre|play|musical|opera|ballet|dance)\b/.test(text)) return "arts";
  if (/\b(museum|gallery|exhibit|art walk|installation)\b/.test(text)) return "arts";
  if (/\b(sport|game|run|marathon|race|tournament|league)\b/.test(text)) return "sports";
  if (/\b(food|drink|tasting|brunch|dinner|chef|cook)\b/.test(text)) return "food";
  if (/\b(festival|parade|carnival|fair|block party|fireworks)\b/.test(text)) return "festivals";
  if (/\b(kids|family|children|storytime|puppet)\b/.test(text)) return "family";
  if (/\b(yoga|fitness|wellness|hike|bike|walk)\b/.test(text)) return "sports";
  return "community";
}

// Metro lat/lng cache — populated from metros table at startup. Lets us
// default an event's venue coords to the metro center when the parser
// can't extract them (most municipal sources don't expose venue geo).
// Without this, events fail the carousel edge function's radius filter
// `(distance_miles ?? 999) <= maxRadius` and get silently dropped.
const METRO_COORDS = new Map(); // metro slug → {lat, lng}

// Cache for resolveVenueCoords lookups. Key: lowercase "name|city|state".
// Many municipal-feed entries reference the same venue dozens of times
// (a museum running 50 events). Without the cache we'd hit Supabase /
// Claude once per event; with it, once per unique venue.
const VENUE_COORDS_CACHE = new Map();

// Cache for town-center coords. Key: lowercase "city|state". A town feed
// (e.g. Waltham) emits many at-large civic events (venue_name=null) that all
// resolve to the same town center — geocode it once per run.
const CITY_COORDS_CACHE = new Map();

/**
 * Resolve venue coordinates with a 3-step fallback chain:
 *   1. venues table — exact-ish match by name (cheap, accurate when we know it).
 *   2. Claude — for unknown venues. Strict confidence gate so ambiguous names
 *      ("Lincoln Park" could be many places) don't ship wrong coords.
 *   3. null — caller decides what to do (drop the event or fall back further).
 *
 * Returns {lat, lng} or null. Cached for the lifetime of the run.
 */
async function resolveVenueCoords(name, city, state) {
  if (!name) return null;
  const key = `${name.toLowerCase()}|${(city || "").toLowerCase()}|${(state || "").toLowerCase()}`;
  if (VENUE_COORDS_CACHE.has(key)) return VENUE_COORDS_CACHE.get(key);

  let coords = null;

  // 1. venues table lookup. Match on name + optionally city. Reject venue
  //    rows without coords. PostgREST `ilike` is case-insensitive.
  try {
    let url = `${SUPABASE_URL}/rest/v1/venues?name=ilike.${encodeURIComponent(name)}&lat=not.is.null&lng=not.is.null&select=lat,lng,city&limit=5`;
    const res = await fetch(url, {
      headers: { apikey: SUPABASE_SERVICE_KEY, Authorization: `Bearer ${SUPABASE_SERVICE_KEY}` },
    });
    if (res.ok) {
      const rows = await res.json();
      // When the event has a city, REQUIRE the venue row to be in that city.
      // The old `pick || rows[0]` fallback stamped an event with a same-named
      // venue in the WRONG city (e.g. "Memorial Park" in Scranton getting the
      // coords of "Memorial Park" in Philadelphia) — a sibling of the
      // metro-centroid default bug. Only fall back to rows[0] when the event
      // has no city to disambiguate. Mirrors backfill_municipal_venue_coords.js.
      let pick = null;
      if (city) {
        pick = rows.find((r) => (r.city || "").toLowerCase() === city.toLowerCase()) || null;
      } else {
        pick = rows[0] || null;
      }
      if (pick && pick.lat != null && pick.lng != null) {
        coords = { lat: pick.lat, lng: pick.lng };
      }
    }
  } catch (err) {
    console.log(`    venues lookup error for "${name}": ${err.message}`);
  }

  // 2. Claude fallback. Only when we have an API key AND the lookup missed.
  //    Restrict scope to {city, state} for disambiguation. Reject low
  //    confidence — better to ship null than a Lincoln, NE event tagged in
  //    Lincoln, MA. Then bbox-guard the result against `state`: Claude
  //    occasionally reads "Indiana, PA" as the state of Indiana (lng −86);
  //    without this check that sails through at conf 0.85.
  if (!coords && ANTHROPIC_API_KEY) {
    coords = await askClaudeForCoords(name, city, state);
    if (coords && !coordsInState(coords.lat, coords.lng, state)) {
      console.log(`    Claude venue coords for "${name}" out of ${state || "US"} bbox — rejected`);
      coords = null;
    }
  }

  VENUE_COORDS_CACHE.set(key, coords);
  return coords;
}

async function askClaudeForCoords(name, city, state) {
  const ctx = [city, state].filter(Boolean).join(", ");
  const prompt =
    `What are the approximate latitude and longitude of the venue "${name}"${ctx ? ` in ${ctx}` : ""}?\n\n` +
    `Return ONLY a JSON object: {"lat": <number>, "lng": <number>, "confidence": <0..1>}.\n` +
    `Confidence reflects how sure you are this is the correct venue — drop below 0.6 if the name is ambiguous (multiple places could share it, you can't tell which).\n` +
    `If the venue truly doesn't exist or you have no idea, return {"lat": 0, "lng": 0, "confidence": 0}.`;
  try {
    const res = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        "x-api-key": ANTHROPIC_API_KEY,
        "anthropic-version": "2023-06-01",
        "content-type": "application/json",
      },
      body: JSON.stringify({
        model: "claude-haiku-4-5-20251001",
        max_tokens: 200,
        messages: [{ role: "user", content: prompt }],
      }),
    });
    if (!res.ok) {
      console.log(`    Claude geocode ${name}: HTTP ${res.status}`);
      return null;
    }
    const data = await res.json();
    const text = data.content?.[0]?.text || "";
    const match = text.match(/\{[\s\S]*\}/);
    if (!match) return null;
    const obj = JSON.parse(match[0]);
    if (typeof obj.lat !== "number" || typeof obj.lng !== "number") return null;
    const conf = typeof obj.confidence === "number" ? obj.confidence : 0;
    if (conf < 0.8) {
      console.log(`    Claude geocode "${name}" in ${ctx || "?"}: conf=${conf.toFixed(2)} — rejected`);
      return null;
    }
    // Sanity-check: coords within plausible lat/lng range, not 0/0.
    if (Math.abs(obj.lat) < 0.01 && Math.abs(obj.lng) < 0.01) return null;
    if (Math.abs(obj.lat) > 90 || Math.abs(obj.lng) > 180) return null;
    console.log(`    Claude geocoded "${name}" → ${obj.lat.toFixed(4)},${obj.lng.toFixed(4)} (conf ${conf.toFixed(2)})`);
    return { lat: obj.lat, lng: obj.lng };
  } catch (err) {
    console.log(`    Claude geocode error for "${name}": ${err.message}`);
    return null;
  }
}

/**
 * Resolve a TOWN's center coordinates. Used as the at-large fallback for
 * civic events with no named venue (city-wide festivals, parades, "on the
 * Common") — these belong at the feed's OWN town, not its metro center.
 *
 * Stamping a Waltham city festival with downtown-Boston metro coords (~10mi
 * off) is the same class of bug as the MASS MoCA case: it lands the event in
 * the wrong place on the map and in the wrong radius bucket. A town is an
 * unambiguous geocode (city + state), so we resolve it cheaply:
 *   1. metros table, when the town happens to be a metro center.
 *   2. cities table (if present) by name + state.
 *   3. Claude geocode of "Town, ST" (gated at confidence ≥ 0.8).
 *   4. null — caller falls back to metro center or null.
 *
 * Cached per town for the run.
 */
async function resolveCityCoords(rawCity, state) {
  if (!rawCity) return null;
  // Strip discovery-added qualifiers like "Cambridge (visitor)" /
  // "Johnstown (tourism)" so the geocoder sees a clean town name.
  const city = rawCity.replace(/\s*\([^)]*\)\s*/g, " ").trim();
  if (!city) return null;
  const key = `${city.toLowerCase()}|${(state || "").toLowerCase()}`;
  if (CITY_COORDS_CACHE.has(key)) return CITY_COORDS_CACHE.get(key);

  let coords = METRO_COORDS.get(city.toLowerCase()) || null;

  // cities table lookup (best-effort — table may not exist in every env).
  if (!coords) {
    try {
      const url =
        `${SUPABASE_URL}/rest/v1/cities?name=ilike.${encodeURIComponent(city)}` +
        `${state ? `&state=eq.${encodeURIComponent(state)}` : ""}` +
        `&lat=not.is.null&lng=not.is.null&select=lat,lng&limit=1`;
      const res = await fetch(url, {
        headers: { apikey: SUPABASE_SERVICE_KEY, Authorization: `Bearer ${SUPABASE_SERVICE_KEY}` },
      });
      if (res.ok) {
        const rows = await res.json();
        if (rows[0]?.lat != null && rows[0]?.lng != null) coords = { lat: rows[0].lat, lng: rows[0].lng };
      }
    } catch (_) { /* table may not exist — fall through to Claude */ }
  }

  // Google Places geocode of the town center (bbox-guarded) — preferred over
  // Claude for a plain "Town, ST", which is an unambiguous lookup.
  if (!coords && GOOGLE_PLACES_API_KEY) {
    const g = await googleGeocode(`${city}, ${state}`, state);
    if (g) coords = { lat: g.lat, lng: g.lng };
  }

  // Claude geocode of the town center. A dedicated municipality prompt (NOT
  // the venue prompt, which scores a bare "Town, ST" at low confidence and
  // rejects it) — a US town + state is an unambiguous, high-confidence
  // geocode.
  if (!coords && ANTHROPIC_API_KEY) {
    coords = await askClaudeForCityCenter(city, state);
    if (coords && !coordsInState(coords.lat, coords.lng, state)) {
      console.log(`    Claude town coords for "${city}, ${state}" out of bbox — rejected`);
      coords = null;
    }
  }

  CITY_COORDS_CACHE.set(key, coords);
  return coords;
}

async function askClaudeForCityCenter(city, state) {
  const prompt =
    `What are the latitude and longitude of the geographic center (downtown / city hall) of the ` +
    `municipality of ${city}, ${state}, USA?\n\n` +
    `Return ONLY a JSON object: {"lat": <number>, "lng": <number>, "confidence": <0..1>}.\n` +
    `For any real, uniquely-identifiable US town/city the confidence should be ≥ 0.9. ` +
    `Drop below 0.6 ONLY if "${city}, ${state}" does not identify a real place or is genuinely ambiguous.`;
  try {
    const res = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        "x-api-key": ANTHROPIC_API_KEY,
        "anthropic-version": "2023-06-01",
        "content-type": "application/json",
      },
      body: JSON.stringify({
        model: "claude-haiku-4-5-20251001",
        max_tokens: 200,
        messages: [{ role: "user", content: prompt }],
      }),
    });
    if (!res.ok) {
      console.log(`    Claude city geocode ${city}, ${state}: HTTP ${res.status}`);
      return null;
    }
    const data = await res.json();
    const text = data.content?.[0]?.text || "";
    const match = text.match(/\{[\s\S]*\}/);
    if (!match) return null;
    const obj = JSON.parse(match[0]);
    if (typeof obj.lat !== "number" || typeof obj.lng !== "number") return null;
    const conf = typeof obj.confidence === "number" ? obj.confidence : 0;
    if (conf < 0.8) {
      console.log(`    Claude city geocode "${city}, ${state}": conf=${conf.toFixed(2)} — rejected`);
      return null;
    }
    if (Math.abs(obj.lat) < 0.01 && Math.abs(obj.lng) < 0.01) return null;
    if (Math.abs(obj.lat) > 90 || Math.abs(obj.lng) > 180) return null;
    console.log(`    Claude geocoded town "${city}, ${state}" → ${obj.lat.toFixed(4)},${obj.lng.toFixed(4)} (conf ${conf.toFixed(2)})`);
    return { lat: obj.lat, lng: obj.lng };
  } catch (err) {
    console.log(`    Claude city geocode error for "${city}, ${state}": ${err.message}`);
    return null;
  }
}

async function buildEvent(feed, raw) {
  // raw is { title, description, startAt, endAt, sourceId, sourceUrl,
  //          venueName, venueAddress, venueCity, venueState, isFree, imageUrl,
  //          venueLat, venueLng }
  // Fall back to the feed's metro center for venue coords. Eventually we
  // should geocode venue_address through Google for true accuracy, but
  // metro-center is enough to land the event in the right radius bucket.
  const metro = METRO_COORDS.get(feed.metro);
  // Decode entities on title + venue_name + description here so every
  // parser path (Drupal / Localist / iCal / RSS / claude_html) writes
  // clean strings, regardless of whether the parser ran stripHtml. The
  // description is double-decoded defensively — if stripHtml already
  // handled it, decodeEntities is idempotent and no-ops. Detection
  // (detectCategory) also benefits from a clean title.
  const title = decodeEntities(raw.title);
  const description = raw.description ? decodeEntities(raw.description) : null;
  const venueName = raw.venueName ? decodeEntities(raw.venueName) : null;

  // Location resolution chain. The bug this replaces: `venue_city` defaulted to
  // `feed.city` and coords defaulted to the feed town, so a regional-aggregator
  // feed configured with one home city (e.g. "Bucks County Parent" → Doylestown)
  // stamped EVERY event — including cross-town and cross-state (NJ) ones — onto
  // that one city. New rule: trust the event's OWN location from the source; when
  // that's missing, geocode it (Google, bbox-guarded); only fall back to the feed
  // city for genuinely at-large civic events (no venue, no city in the source),
  // which is correct for single-town gov calendars. Anything with a location
  // signal we can't confidently place is DROPPED rather than mislocated.
  //
  //   1. raw.venueLat / raw.venueLng if the feed provided them (best — Localist).
  //   2. Source-extracted city/state (drupal locality, wp_tribe venue.city, the
  //      claude_html/iCal/RSS location text) — NEVER feed.city.
  //   3. venues table lookup, scoped to the SOURCE city/state (not feed.city).
  //   4. Google Places geocode of venue+city+state, rejected if it lands in the
  //      wrong state (the mislocation guard). Fills canonical city/state too.
  //   5. TOWN center ONLY when there is no venue AND no source city — a truly
  //      at-large civic event (parade / "on the Common"). Uses the feed's own
  //      town; this is the sole place feed.city is still used as a location.
  //   6. Drop (return null) — better absent than wrong.
  let venueLat = raw.venueLat ?? null;
  let venueLng = raw.venueLng ?? null;
  // Source-truth locality — no feed.city default (that default WAS the bug).
  let cityLabel = raw.venueCity || null;
  let stateLabel = raw.venueState || null;
  const hasLocationSignal = Boolean(venueName || raw.venueAddress || cityLabel);

  if (venueLat == null || venueLng == null) {
    // 3. venues table, scoped to whatever real city/state the source gave.
    if (venueName) {
      const resolved = await resolveVenueCoords(venueName, cityLabel, stateLabel || feed.state);
      if (resolved) {
        if (venueLat == null) venueLat = resolved.lat;
        if (venueLng == null) venueLng = resolved.lng;
      }
    }
    // 4. Google geocode of the strongest query we can build. feed.city is used
    //    only as a disambiguation HINT in the query when the source gave no
    //    city; the STORED city comes back from Google (canonical), and the
    //    bbox/expectState guard rejects a cross-state result.
    //    expectState = the source's state, or the FEED's state when the source
    //    gave none — municipal feeds are overwhelmingly in-state, so a result in
    //    another state (e.g. "Cathedral Gardens, PA" matching a NYC address) is
    //    rejected and the loop tries the next candidate, then drops. A genuine
    //    cross-state event still carries its own state from the source, so this
    //    only tightens the no-state case.
    if ((venueLat == null || venueLng == null) && hasLocationSignal) {
      const hintCity = cityLabel || feed.city;
      const hintState = stateLabel || feed.state;
      const query = [venueName, raw.venueAddress, raw.venueAddress ? null : hintCity, hintState]
        .filter(Boolean).join(", ");
      const g = await googleGeocode(query, stateLabel || feed.state);
      if (g) {
        venueLat = g.lat;
        venueLng = g.lng;
        if (!cityLabel && g.city) cityLabel = g.city;
        if (!stateLabel && g.state) stateLabel = g.state;
      }
    }
    // 5. Truly at-large civic event — no venue and no source city. Feed's own
    //    town center (correct for single-town gov feeds).
    if ((venueLat == null || venueLng == null) && !hasLocationSignal) {
      const town = await resolveCityCoords(feed.city, feed.state);
      if (town) {
        if (venueLat == null) venueLat = town.lat;
        if (venueLng == null) venueLng = town.lng;
      }
      cityLabel = cityLabel || feed.city;
      stateLabel = stateLabel || feed.state;
      // Last-resort metro center, only when the feed IS the metro hub.
      const feedIsMetro = (feed.city || "").toLowerCase() === (feed.metro || "").toLowerCase();
      if ((venueLat == null || venueLng == null) && feedIsMetro) {
        venueLat = venueLat ?? metro?.lat ?? null;
        venueLng = venueLng ?? metro?.lng ?? null;
      }
    }
  }

  // 6. Drop rather than mislocate: a row with a location signal we couldn't
  //    confidently place gets no coords → it would fail the carousel radius
  //    filter anyway, and shipping it with a guessed city is exactly the bug.
  if (venueLat == null || venueLng == null) {
    TOTAL_DROPPED++;
    console.log(`    drop "${title.slice(0, 60)}" — no confident location (city=${cityLabel || "?"}, venue=${venueName || "?"})`);
    return null;
  }

  return {
    title,
    description,
    category: detectCategory(title, description || ""),
    subcategory: detectCategory(title, description || "") === "community" ? "civic" : null,
    start_at: raw.startAt,
    end_at: raw.endAt || null,
    is_free: raw.isFree ?? true,
    price_min: null,
    price_max: null,
    ticket_url: raw.sourceUrl || null,
    image_url: raw.imageUrl || null,
    status: "active",
    venue_name: venueName,
    venue_address: raw.venueAddress || null,
    venue_city: cityLabel,
    venue_state: stateLabel,
    venue_lat: venueLat,
    venue_lng: venueLng,
    source: `municipal_${feed.metro}`,
    source_id: raw.sourceId,
    source_url: raw.sourceUrl || null,
    confidence_score: 0.9,
    needs_review: false,
    dedupe_hash: hashEvent(`municipal_${feed.metro}_${feed.city}`, raw.sourceId),
    city: feed.metro,
    last_synced_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  };
}

// Build an event and push it only if buildEvent resolved a confident location
// (it returns null for rows it would otherwise mislocate — see the chain above).
async function addEvent(events, feed, raw) {
  const e = await buildEvent(feed, raw);
  if (e) events.push(e);
}

// ── Parsers ──

async function fetchDrupalJsonApi(feed) {
  const events = [];
  let url = `${feed.url}?page[limit]=50&sort=-created`;
  let pages = 0;
  const maxPages = 10;

  while (url && pages < maxPages) {
    pages++;
    const res = await fetch(url, { headers: { Accept: "application/vnd.api+json" } });
    if (!res.ok) throw new Error(`Drupal API ${res.status}`);
    const data = await res.json();
    for (const item of data.data || []) {
      const a = item.attributes || {};
      if (!a.title) continue;
      const dr = a.field_event_date_recur || {};
      const startAt = dr.value || null;
      if (!startAt || new Date(startAt) < new Date()) continue;
      const addr = a.field_address || {};
      const detailsLink = a.field_details_link?.uri || null;
      const eventPath = a.path?.alias ? `https://${new URL(feed.url).host}${a.path.alias}` : null;
      await addEvent(events, feed, {
        title: a.title,
        description: stripHtml(a.body?.value || "").slice(0, 2000),
        startAt,
        endAt: dr.end_value || null,
        sourceId: String(a.drupal_internal__nid || item.id),
        sourceUrl: eventPath || detailsLink,
        venueName: addr.organization || null,
        venueAddress: [addr.address_line1, addr.address_line2].filter(Boolean).join(", ") || null,
        venueCity: addr.locality || null,
        venueState: addr.administrative_area || null,
        isFree: !a.field_cost || /free|no cost|\$0/i.test(String(a.field_cost)),
      });
    }
    url = data.links?.next?.href || null;
  }
  return events;
}

async function fetchLocalist(feed) {
  const events = [];
  let page = 1;
  const maxPages = 10;
  while (page <= maxPages) {
    const url = `${feed.url}?days=180&pp=50&page=${page}`;
    const res = await fetch(url);
    if (!res.ok) throw new Error(`Localist ${res.status}`);
    const data = await res.json();
    const items = data.events || [];
    if (items.length === 0) break;
    for (const wrap of items) {
      const ev = wrap.event || wrap;
      const inst = (ev.event_instances || [])[0]?.event_instance;
      const startAt = inst?.start || ev.first_date;
      if (!startAt || new Date(startAt) < new Date()) continue;
      await addEvent(events, feed, {
        title: ev.title,
        description: stripHtml(ev.description_text || ev.description || "").slice(0, 2000),
        startAt,
        endAt: inst?.end || null,
        sourceId: String(ev.id),
        sourceUrl: ev.url || ev.localist_url || null,
        venueName: ev.location_name || ev.venue_name || null,
        venueAddress: ev.address || null,
        venueCity: ev.city || null,
        venueState: ev.state || null,
        venueLat: ev.geo?.latitude ? Number(ev.geo.latitude) : null,
        venueLng: ev.geo?.longitude ? Number(ev.geo.longitude) : null,
        isFree: !ev.ticket_cost || /free/i.test(ev.ticket_cost),
        imageUrl: ev.photo_url || null,
      });
    }
    page++;
  }
  return events;
}

async function fetchIcal(feed) {
  const res = await fetch(feed.url, { headers: { Accept: "text/calendar" } });
  if (!res.ok) throw new Error(`iCal ${res.status}`);
  const text = await res.text();
  return parseIcal(text, feed);
}

async function parseIcal(text, feed) {
  // Minimal iCal parser. Handles VEVENT blocks, unfolds line continuations,
  // ignores recurring expansions (we take the DTSTART as-is).
  const unfolded = text.replace(/\r?\n[ \t]/g, "");
  const events = [];
  const blocks = unfolded.split(/BEGIN:VEVENT/).slice(1);
  for (const block of blocks) {
    const end = block.indexOf("END:VEVENT");
    const body = end >= 0 ? block.slice(0, end) : block;
    const lines = body.split(/\r?\n/);
    const map = {};
    for (const line of lines) {
      const colon = line.indexOf(":");
      if (colon < 0) continue;
      const keyPart = line.slice(0, colon);
      const value = line.slice(colon + 1).trim();
      const key = keyPart.split(";")[0].toUpperCase();
      if (!map[key]) map[key] = value;
    }
    if (!map.SUMMARY) continue;
    const startAt = parseIcalDate(map.DTSTART);
    if (!startAt || new Date(startAt) < new Date()) continue;
    // LOCATION is free text ("Venue, 123 Main St, Wildwood, NJ 08260"). Pull the
    // event's own city/state out of it rather than defaulting to the feed city.
    const icalLoc = map.LOCATION ? map.LOCATION.replace(/\\,/g, ",").replace(/\\n/g, " ") : null;
    const icalCS = parseCityState(icalLoc);
    await addEvent(events, feed, {
      title: map.SUMMARY.replace(/\\,/g, ",").replace(/\\n/g, " "),
      description: (map.DESCRIPTION || "").replace(/\\,/g, ",").replace(/\\n/g, " ").slice(0, 2000),
      startAt,
      endAt: parseIcalDate(map.DTEND),
      sourceId: map.UID || crypto.createHash("md5").update(map.SUMMARY + startAt).digest("hex"),
      sourceUrl: map.URL || null,
      venueName: icalLoc ? icalLoc.split(",")[0] : null,
      venueAddress: icalLoc,
      venueCity: icalCS.city,
      venueState: icalCS.state,
      isFree: true,
    });
  }
  return events;
}

function parseIcalDate(raw) {
  if (!raw) return null;
  // Strip timezone params; raw is YYYYMMDD or YYYYMMDDTHHMMSS(Z)
  const s = raw.replace(/[^0-9TZ]/g, "");
  if (s.length === 8) return `${s.slice(0,4)}-${s.slice(4,6)}-${s.slice(6,8)}T00:00:00`;
  if (s.length >= 15) {
    const date = `${s.slice(0,4)}-${s.slice(4,6)}-${s.slice(6,8)}`;
    const time = `${s.slice(9,11)}:${s.slice(11,13)}:${s.slice(13,15)}`;
    return `${date}T${time}${s.endsWith("Z") ? "Z" : ""}`;
  }
  return null;
}

async function fetchWpTribe(feed) {
  const events = [];
  let page = 1;
  const maxPages = 10;
  while (page <= maxPages) {
    const url = `${feed.url}?per_page=50&page=${page}&start_date=${new Date().toISOString().slice(0,10)}`;
    const res = await fetch(url);
    if (!res.ok) {
      if (res.status === 400 || res.status === 404) break; // pagination over
      throw new Error(`WP Tribe ${res.status}`);
    }
    const data = await res.json();
    const items = data.events || [];
    if (items.length === 0) break;
    for (const ev of items) {
      const startAt = ev.utc_start_date || ev.start_date;
      if (!startAt || new Date(startAt) < new Date()) continue;
      await addEvent(events, feed, {
        title: ev.title,
        description: stripHtml(ev.description || "").slice(0, 2000),
        startAt,
        endAt: ev.utc_end_date || ev.end_date || null,
        sourceId: String(ev.id),
        sourceUrl: ev.url || null,
        venueName: ev.venue?.venue || null,
        venueAddress: ev.venue?.address || null,
        venueCity: ev.venue?.city || null,
        venueState: ev.venue?.state || ev.venue?.region || null,
        isFree: !ev.cost || /free/i.test(ev.cost),
        imageUrl: ev.image?.url || null,
      });
    }
    page++;
  }
  return events;
}

async function fetchRss(feed) {
  const res = await fetch(feed.url, { headers: { Accept: "application/rss+xml, application/atom+xml" } });
  if (!res.ok) throw new Error(`RSS ${res.status}`);
  const text = await res.text();
  return parseRss(text, feed);
}

async function parseRss(text, feed) {
  // Lightweight RSS/Atom parser. Captures <item> or <entry> blocks and pulls
  // title, description, link, pubDate. Date parsing is permissive: tries
  // pubDate, dc:date, published in that order.
  const events = [];
  const isAtom = /<feed[\s>]/i.test(text);
  const blockRe = isAtom ? /<entry\b[^>]*>([\s\S]*?)<\/entry>/gi : /<item\b[^>]*>([\s\S]*?)<\/item>/gi;
  for (const m of text.matchAll(blockRe)) {
    const body = m[1];
    const get = tag => {
      const re = new RegExp(`<${tag}\\b[^>]*>([\\s\\S]*?)<\\/${tag}>`, "i");
      const r = re.exec(body);
      return r ? r[1].replace(/<!\[CDATA\[/g, "").replace(/\]\]>/g, "").trim() : null;
    };
    const title = stripHtml(get("title") || "");
    if (!title) continue;
    const dateRaw = get("pubDate") || get("dc:date") || get("published") || get("updated") || get("dc\\:date");
    const startAt = dateRaw ? new Date(dateRaw).toISOString() : null;
    if (!startAt || new Date(startAt) < new Date()) continue;
    const link = isAtom
      ? (/<link\b[^>]*?href\s*=\s*["']([^"']+)["'][^>]*?\/?>/i.exec(body)?.[1] || null)
      : get("link");
    const guid = get("guid") || get("id") || link || title;
    const description = stripHtml(get("description") || get("summary") || get("content") || "").slice(0, 2000);
    // RSS carries no structured location — the town/state, if present, is in the
    // title or body text ("… — Wildwood, NJ"). Pull it out so a regional feed's
    // out-of-town events aren't defaulted to the feed city. When nothing parses,
    // venueCity/State stay null and buildEvent drops rather than mislocates.
    const titleCS = parseCityState(title);
    const rssCS = titleCS.state ? titleCS : parseCityState(description);
    await addEvent(events, feed, {
      title,
      description,
      startAt,
      sourceId: guid,
      sourceUrl: link,
      venueCity: rssCS.city,
      venueState: rssCS.state,
      isFree: true,
    });
  }
  return events;
}

// claude_html — Scrapfly fetches the calendar page (with JS rendering for
// CivicPlus/Granicus/SPA), then Claude extracts events as structured JSON.
// The extracted records use the same shape as the other parsers via buildEvent.
async function fetchClaudeHtml(feed) {
  if (!SCRAPFLY_API_KEY || !ANTHROPIC_API_KEY) {
    throw new Error("claude_html requires SCRAPFLY_API_KEY and ANTHROPIC_API_KEY");
  }
  // Cap the number of calendar pages we fetch per feed. Each page is a
  // separate Scrapfly renderJs call (credits) + a Claude extraction, so 4 is
  // a deliberate ceiling: enough to capture a town's real upcoming events
  // without runaway cost on calendars with dozens of pages of repeats.
  const MAX_PAGES = 4;
  const keyOf = (e) => `${e?.title || ""}|${e?.start_at || ""}|${e?.venue_name || ""}`;

  const { html } = await fetchHtml(feed.url, {
    scrapflyKey: SCRAPFLY_API_KEY,
    renderJs: true,
    timeoutMs: 45000,
  });
  const extracted = await extractEventsFromHtml({
    html,
    sourceUrl: feed.url,
    anthropicKey: ANTHROPIC_API_KEY,
    city: feed.city,
    state: feed.state,
  });

  // Follow the calendar's own pagination (pages 2..MAX_PAGES) so later-dated
  // events aren't dropped — single-page extraction only sees the soonest ~15.
  // Stop early when a page contributes no NEW events (empty / out-of-range
  // page) to avoid burning Scrapfly credits on trailing blank pages.
  const seen = new Set(extracted.map(keyOf));
  const pageUrls = findPageUrls(html, feed.url, MAX_PAGES - 1);
  for (const pageUrl of pageUrls) {
    let pageHtml;
    try {
      ({ html: pageHtml } = await fetchHtml(pageUrl, {
        scrapflyKey: SCRAPFLY_API_KEY,
        renderJs: true,
        timeoutMs: 45000,
      }));
    } catch { continue; }
    let pageEvents = [];
    try {
      pageEvents = await extractEventsFromHtml({
        html: pageHtml,
        sourceUrl: pageUrl,
        anthropicKey: ANTHROPIC_API_KEY,
        city: feed.city,
        state: feed.state,
      });
    } catch { continue; }
    let added = 0;
    for (const e of pageEvents) {
      const k = keyOf(e);
      if (!e?.title || seen.has(k)) continue;
      seen.add(k);
      extracted.push(e);
      added++;
    }
    if (added === 0) break;
  }
  // Stable per-event hash so weekly re-extraction upserts instead of dup-ing.
  // Title + start_at + venue is the strongest natural key for these.
  const events = [];
  for (const e of extracted) {
    if (!e?.title || !e?.start_at) continue;
    if (new Date(e.start_at) < new Date()) continue;
    const sourceId = crypto
      .createHash("sha1")
      .update(`${e.title}|${e.start_at}|${e.venue_name || feed.city}`)
      .digest("hex")
      .slice(0, 24);
    // Use the event's OWN city/state as extracted by Claude (added to the
    // extractor schema); fall back to parsing the address. Do NOT default to
    // feed.city — that hardcode is what stamped every aggregator event onto the
    // publisher's home town. When neither yields a location, buildEvent drops it.
    const addrCS = parseCityState(e.venue_address);
    await addEvent(events, feed, {
      title: e.title,
      description: e.description || null,
      startAt: e.start_at,
      endAt: e.end_at || null,
      sourceId,
      sourceUrl: e.source_url || feed.url,
      venueName: e.venue_name || null,
      venueAddress: e.venue_address || null,
      venueCity: e.venue_city || addrCS.city || null,
      venueState: e.venue_state || addrCS.state || null,
      isFree: e.is_free ?? null,
    });
  }
  return events;
}

const PARSERS = {
  drupal_jsonapi: fetchDrupalJsonApi,
  localist: fetchLocalist,
  ical: fetchIcal,
  wp_tribe: fetchWpTribe,
  rss: fetchRss,
  claude_html: fetchClaudeHtml,
};

// ── Supabase I/O ──

async function fetchEnabledFeeds() {
  // Stalest-first: under a per-run time budget (SYNC_MAX_MINUTES) we may not
  // reach every feed in one run. Ordering by last_synced_at ascending (never-
  // synced first) guarantees each feed refreshes within a few runs instead of
  // the tail perpetually starving.
  const url = `${SUPABASE_URL}/rest/v1/municipal_feeds?enabled=eq.true&select=*&order=last_synced_at.asc.nullsfirst`;
  const res = await fetch(url, {
    headers: { apikey: SUPABASE_SERVICE_KEY, Authorization: `Bearer ${SUPABASE_SERVICE_KEY}` },
  });
  if (!res.ok) throw new Error(`Failed to load feeds: ${res.status}`);
  return res.json();
}

async function recordSyncResult(feedId, status, eventCount) {
  const url = `${SUPABASE_URL}/rest/v1/municipal_feeds?id=eq.${feedId}`;
  const isOk = status === "ok";
  const body = isOk
    ? { last_synced_at: new Date().toISOString(), last_event_count: eventCount, last_status: "ok", consecutive_failures: 0 }
    : { last_synced_at: new Date().toISOString(), last_status: status };
  // For failures, we also bump consecutive_failures via separate fetch since
  // PostgREST doesn't support arithmetic-on-update. Use a simple read-modify-write.
  if (!isOk) {
    const cur = await fetch(`${SUPABASE_URL}/rest/v1/municipal_feeds?id=eq.${feedId}&select=consecutive_failures`, {
      headers: { apikey: SUPABASE_SERVICE_KEY, Authorization: `Bearer ${SUPABASE_SERVICE_KEY}` },
    }).then(r => r.json());
    body.consecutive_failures = (cur[0]?.consecutive_failures || 0) + 1;
  }
  await fetch(url, {
    method: "PATCH",
    headers: {
      apikey: SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
}

async function upsertToSupabase(rows) {
  const url = `${SUPABASE_URL}/rest/v1/events?on_conflict=dedupe_hash`;
  // Collapse rows that share a dedupe_hash before sending. PostgREST's upsert
  // rejects the WHOLE batch with error 21000 ("ON CONFLICT DO UPDATE command
  // cannot affect row a second time") if two proposed rows carry the same
  // conflict key — which happens when a claude_html feed extracts a near-dup
  // (same title/start/venue → same sourceId → same hash). Keep the last.
  const byHash = new Map();
  for (const r of rows) byHash.set(r.dedupe_hash, r);
  if (byHash.size < rows.length) {
    console.log(`  Collapsed ${rows.length - byHash.size} in-batch duplicate dedupe_hash row(s)`);
    rows = [...byHash.values()];
  }
  const batchSize = 50;
  let inserted = 0, errors = 0;
  for (let i = 0; i < rows.length; i += batchSize) {
    const batch = rows.slice(i, i + batchSize);
    const res = await fetch(url, {
      method: "POST",
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
        "Content-Type": "application/json",
        Prefer: "resolution=merge-duplicates",
      },
      body: JSON.stringify(batch),
    });
    if (!res.ok) {
      const body = await res.text();
      console.error(`  Upsert batch ${Math.floor(i / batchSize)}: ${res.status} ${body.slice(0, 200)}`);
      errors += batch.length;
    } else inserted += batch.length;
  }
  return { inserted, errors };
}

// ── Main ──

async function main() {
  // Pull Scrapfly + Claude keys for the claude_html parser. Both are optional
  // — if a feed of type claude_html exists but keys are missing, it'll error
  // for that one feed and continue with the rest.
  try {
    const secrets = await getSecrets(["SCRAPFLY_API_KEY", "ANTHROPIC_API_KEY", "GOOGLE_PLACES_API_KEY"]);
    SCRAPFLY_API_KEY = secrets.SCRAPFLY_API_KEY || null;
    ANTHROPIC_API_KEY = secrets.ANTHROPIC_API_KEY || null;
    GOOGLE_PLACES_API_KEY = secrets.GOOGLE_PLACES_API_KEY || null;
    console.log(
      `Scrapfly: ${SCRAPFLY_API_KEY ? "available" : "not configured"}, ` +
      `Claude: ${ANTHROPIC_API_KEY ? "available" : "not configured"}, ` +
      `Google Places: ${GOOGLE_PLACES_API_KEY ? "available" : "not configured"}`
    );
    if (!GOOGLE_PLACES_API_KEY) {
      console.warn(
        "  ⚠ GOOGLE_PLACES_API_KEY not configured — per-event geocoding is disabled; " +
        "events whose location can't be resolved from the venues table will be dropped.",
      );
    }
  } catch (e) { console.warn(`Secret fetch failed: ${e.message}`); }

  // Load metro centers so events without parser-extracted venue coords
  // can default to their feed's metro lat/lng. Required for them to pass
  // the carousel edge function's radius filter.
  const metrosRes = await fetch(
    `${SUPABASE_URL}/rest/v1/metros?is_active=eq.true&select=name,lat,lng`,
    { headers: { apikey: SUPABASE_SERVICE_KEY, Authorization: `Bearer ${SUPABASE_SERVICE_KEY}` } },
  );
  if (metrosRes.ok) {
    const metros = await metrosRes.json();
    for (const m of metros) {
      // metro slug = lowercased name (matches `feed.metro` set by discover script)
      METRO_COORDS.set((m.name || "").toLowerCase(), { lat: m.lat, lng: m.lng });
    }
    console.log(`Loaded ${METRO_COORDS.size} metro centers`);
  } else {
    console.warn(`Metro fetch failed: ${metrosRes.status} — events without coords will be dropped from carousels`);
  }

  let feeds = await fetchEnabledFeeds();
  // Optional scoping for targeted/manual runs (e.g. push one town live now or
  // re-sync a single state). Unset in CI, so the weekly job still syncs all.
  const cityFilter = (process.env.FEED_CITY || "").trim().toLowerCase();
  const stateFilter = (process.env.FEED_STATE || process.env.STATE || "").trim().toUpperCase();
  if (cityFilter) feeds = feeds.filter((f) => (f.city || "").toLowerCase() === cityFilter);
  if (stateFilter) feeds = feeds.filter((f) => (f.state || "").toUpperCase() === stateFilter);
  if (cityFilter || stateFilter) {
    console.log(`Scope filter — city=${cityFilter || "*"} state=${stateFilter || "*"}`);
  }
  console.log(`Municipal Feed Sync — ${feeds.length} enabled feed(s)\n`);

  // Self-imposed time budget so the job exits 0 (green) instead of being killed
  // at the GitHub `timeout-minutes` hard cap — which marks the whole daily-crawl
  // run as failed. Feeds not reached this run are the stalest, so the next run
  // (ordered last_synced_at.asc) picks them up first. Mirrors DISCOVER_MAX_MINUTES
  // in discover_municipal_feeds.js. Unset (0) in local/manual runs = sync all.
  const MAX_MINUTES = parseFloat(process.env.SYNC_MAX_MINUTES || "0");
  const startedAt = Date.now();

  let totalFetched = 0, totalUpserted = 0, totalErrors = 0;
  let processed = 0;

  for (const feed of feeds) {
    if (MAX_MINUTES > 0 && (Date.now() - startedAt) / 60000 >= MAX_MINUTES) {
      const remaining = feeds.length - processed;
      console.log(
        `\n⏱ Time budget of ${MAX_MINUTES}m reached — synced ${processed}/${feeds.length} feed(s); ` +
          `${remaining} stalest deferred to next run (they sync first next time).`
      );
      break;
    }
    processed++;
    console.log(`━━━ ${feed.name} (${feed.type}, ${feed.city}, ${feed.state}) ━━━`);
    const parser = PARSERS[feed.type];
    if (!parser) {
      console.error(`  Unknown feed type: ${feed.type}. Skipping.`);
      await recordSyncResult(feed.id, `error: unknown type ${feed.type}`, 0);
      continue;
    }
    try {
      const events = await parser(feed);
      console.log(`  Fetched ${events.length} upcoming events`);
      totalFetched += events.length;
      if (events.length === 0) {
        await recordSyncResult(feed.id, "ok", 0);
        console.log("  Nothing to upsert.\n");
        continue;
      }
      const { inserted, errors } = await upsertToSupabase(events);
      console.log(`  Upserted: ${inserted}, Errors: ${errors}\n`);
      totalUpserted += inserted;
      totalErrors += errors;
      await recordSyncResult(feed.id, errors > 0 ? `error: ${errors} upsert failures` : "ok", events.length);
    } catch (err) {
      console.error(`  Failed: ${err.message}\n`);
      totalErrors++;
      await recordSyncResult(feed.id, `error: ${err.message.slice(0, 200)}`, 0);
    }
  }

  console.log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
  console.log(`Processed ${processed}/${feeds.length} feed(s) in ${((Date.now() - startedAt) / 60000).toFixed(1)}m`);
  console.log(`Total:  ${totalFetched} fetched, ${totalUpserted} upserted, ${totalErrors} errors, ${TOTAL_DROPPED} dropped (no confident location)`);
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
