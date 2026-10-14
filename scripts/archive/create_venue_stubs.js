/**
 * create_venue_stubs.js — create venue records for events whose venue_name
 * doesn't match any existing venue (typically aggregator-split events:
 * Harvard Book Store, Bonde Fine Wine Shop, Mount Auburn Cemetery, etc.).
 *
 * For each unique (venue_name, city, state), call Google Places Text Search
 * to get the canonical place + details (address, lat/lng, website, phone,
 * rating, photo, types). Insert a venue row with the verified data.
 *
 * After creation, events that referenced the new venue by name will pick it
 * up via the existing fuzzy-match in carousels and search.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/create_venue_stubs.js                          # dry run
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 node scripts/create_venue_stubs.js                  # apply
 *   SUPABASE_SERVICE_KEY=ey... STATE=MA node scripts/create_venue_stubs.js                 # scope
 *   SUPABASE_SERVICE_KEY=ey... LIMIT=20 node scripts/create_venue_stubs.js                 # cap calls
 */

const { getSecrets, SUPABASE_URL } = require("../lib/secrets");
const SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").replace(/\s/g, "");
const WRITE = process.env.WRITE === "1";
const STATE = process.env.STATE || null;
const LIMIT = parseInt(process.env.LIMIT || "0", 10) || null;

if (!SERVICE_KEY) { console.error("Missing SUPABASE_SERVICE_KEY"); process.exit(1); }

let GOOGLE_PLACES_API_KEY;

const headers = {
  apikey: SERVICE_KEY,
  Authorization: `Bearer ${SERVICE_KEY}`,
  "Content-Type": "application/json",
};

function normalizeName(s) {
  return (s || "")
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/[^\w\s]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

// ── Pull events with venue_name not matching any existing venue ─────────

async function loadVenueNames() {
  const set = new Set();
  let offset = 0;
  while (true) {
    const url = `${SUPABASE_URL}/rest/v1/venues?select=name&offset=${offset}&limit=1000`;
    const res = await fetch(url, { headers });
    if (!res.ok) throw new Error(`venues: ${res.status}`);
    const rows = await res.json();
    for (const r of rows) set.add(normalizeName(r.name));
    if (rows.length < 1000) break;
    offset += 1000;
  }
  return set;
}

async function findOrphanVenueNames() {
  const existing = await loadVenueNames();
  console.log(`Existing venues (normalized): ${existing.size}`);

  const since = new Date(Date.now() - 7 * 86400 * 1000).toISOString().slice(0, 10);
  // Group by venue_name client-side
  const counts = new Map(); // norm → { name, address, city, state, count, sampleEventId }
  let offset = 0;
  while (true) {
    let url =
      `${SUPABASE_URL}/rest/v1/events` +
      `?select=id,venue_name,venue_address,venue_city,venue_state,venue_lat,venue_lng` +
      `&is_active=eq.true&start_at=gte.${since}` +
      `&venue_name=not.is.null` +
      `&order=start_at.asc&offset=${offset}&limit=1000`;
    if (STATE) url += `&venue_state=eq.${STATE}`;
    const res = await fetch(url, { headers });
    if (!res.ok) throw new Error(`events: ${res.status} ${await res.text()}`);
    const rows = await res.json();
    for (const r of rows) {
      const norm = normalizeName(r.venue_name);
      if (!norm || existing.has(norm)) continue;
      if (!counts.has(norm)) {
        counts.set(norm, {
          name: r.venue_name,
          address: r.venue_address,
          city: r.venue_city,
          state: r.venue_state,
          lat: r.venue_lat,
          lng: r.venue_lng,
          count: 0,
        });
      }
      counts.get(norm).count++;
    }
    process.stdout.write(`  scanned ${offset + rows.length} events, orphan venues ${counts.size}\r`);
    if (rows.length < 1000) break;
    offset += 1000;
  }
  console.log("");
  // Sort by event count desc — most-impactful first
  return [...counts.values()].sort((a, b) => b.count - a.count);
}

// ── Google Places ───────────────────────────────────────────────────────

const PLACES_FIELD_MASK = [
  "places.id",
  "places.displayName",
  "places.formattedAddress",
  "places.location",
  "places.types",
  "places.primaryType",
  "places.websiteUri",
  "places.nationalPhoneNumber",
  "places.rating",
  "places.userRatingCount",
  "places.priceLevel",
  "places.regularOpeningHours",
  "places.editorialSummary",
  "places.photos",
].join(",");

async function searchPlace(name, city, state, anchorLat, anchorLng) {
  const query = [name, city, state].filter(Boolean).join(", ");
  const body = {
    textQuery: query,
    locationBias: anchorLat && anchorLng
      ? { circle: { center: { latitude: anchorLat, longitude: anchorLng }, radius: 8000 } }
      : undefined,
  };
  const res = await fetch("https://places.googleapis.com/v1/places:searchText", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Goog-Api-Key": GOOGLE_PLACES_API_KEY,
      "X-Goog-FieldMask": PLACES_FIELD_MASK,
    },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const text = await res.text();
    console.warn(`  places api ${res.status} for "${query}": ${text.slice(0, 200)}`);
    return null;
  }
  const data = await res.json();
  return data.places?.[0] || null;
}

// Map Google primaryType to our `category` taxonomy. Coarse — refine later.
const TYPE_TO_CATEGORY = {
  bookstore: "bookstore",
  book_store: "bookstore",
  museum: "museum",
  art_gallery: "gallery",
  performing_arts_theater: "theater",
  movie_theater: "theater",
  night_club: "nightlife",
  bar: "bar",
  restaurant: "restaurant",
  cafe: "cafe",
  bakery: "bakery",
  library: "library",
  park: "park",
  church: "religious",
  university: "education",
  school: "education",
  stadium: "sports_venue",
  cemetery: "landmark",
  tourist_attraction: "landmark",
  shopping_mall: "shopping",
  store: "shopping",
};

function mapCategory(types, primaryType) {
  if (primaryType && TYPE_TO_CATEGORY[primaryType]) return TYPE_TO_CATEGORY[primaryType];
  for (const t of types || []) if (TYPE_TO_CATEGORY[t]) return TYPE_TO_CATEGORY[t];
  return "other";
}

function placeToVenue(place, fallback) {
  const photoRef = place.photos?.[0]?.name;
  const photoUrl = photoRef
    ? `https://places.googleapis.com/v1/${photoRef}/media?maxHeightPx=800&maxWidthPx=800&key=${GOOGLE_PLACES_API_KEY}`
    : null;
  return {
    name: place.displayName?.text || fallback.name,
    address: place.formattedAddress || fallback.address,
    city: fallback.city,
    state: fallback.state,
    lat: place.location?.latitude ?? fallback.lat,
    lng: place.location?.longitude ?? fallback.lng,
    category: mapCategory(place.types, place.primaryType),
    place_type: place.primaryType || (place.types?.[0] ?? null),
    website: place.websiteUri || null,
    google_rating: place.rating ?? null,
    photo_url: photoUrl,
    description: place.editorialSummary?.text || null,
    is_active: true,
    is_aggregator: false,
  };
}

// ── Insert ──────────────────────────────────────────────────────────────

async function insertVenue(venue) {
  const url = `${SUPABASE_URL}/rest/v1/venues`;
  const res = await fetch(url, {
    method: "POST",
    headers: { ...headers, Prefer: "return=representation" },
    body: JSON.stringify(venue),
  });
  if (!res.ok) throw new Error(`insert venue: ${res.status} ${await res.text()}`);
  return (await res.json())[0];
}

// ── Main ────────────────────────────────────────────────────────────────

async function main() {
  console.log(`Mode: ${WRITE ? "WRITE" : "DRY RUN"}${STATE ? ` (STATE=${STATE})` : ""}`);
  const secrets = await getSecrets(["GOOGLE_PLACES_API_KEY"]);
  GOOGLE_PLACES_API_KEY = secrets.GOOGLE_PLACES_API_KEY;
  if (!GOOGLE_PLACES_API_KEY) { console.error("Missing GOOGLE_PLACES_API_KEY"); process.exit(1); }

  let orphans = await findOrphanVenueNames();
  console.log(`Total orphan venues: ${orphans.length}`);
  if (LIMIT) orphans = orphans.slice(0, LIMIT);
  console.log(`Will process: ${orphans.length}`);
  console.log("");

  const created = [];
  const failed = [];
  for (const o of orphans) {
    process.stdout.write(`  [${o.count} events] ${o.name.slice(0, 50)}\r`);
    try {
      const place = await searchPlace(o.name, o.city, o.state, o.lat, o.lng);
      if (!place) { failed.push({ ...o, reason: "no place result" }); continue; }
      const venue = placeToVenue(place, o);
      if (WRITE) {
        const inserted = await insertVenue(venue);
        created.push({ ...venue, id: inserted.id, count: o.count });
      } else {
        created.push({ ...venue, count: o.count });
      }
    } catch (e) {
      failed.push({ ...o, reason: e.message });
    }
    await new Promise(r => setTimeout(r, 100));
  }
  console.log("");

  console.log(`\n=== Results ===`);
  console.log(`Created: ${created.length}`);
  console.log(`Failed: ${failed.length}`);
  console.log("");
  console.log("Sample new venues:");
  for (const v of created.slice(0, 30)) {
    console.log(`  [${v.count} ev] ${v.name} — ${v.category} — ${v.address || "(no addr)"}${v.google_rating ? ` ★${v.google_rating}` : ""}${v.website ? " 🌐" : ""}${v.photo_url ? " 📷" : ""}`);
  }
  if (failed.length) {
    console.log("\nFailures:");
    for (const f of failed.slice(0, 10)) console.log(`  ${f.name} (${f.city}): ${f.reason}`);
  }
  if (!WRITE) console.log("\nDRY RUN — pass WRITE=1 to apply.");
}

main().catch(e => { console.error(e); process.exit(1); });
