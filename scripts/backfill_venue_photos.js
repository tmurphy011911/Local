/**
 * Backfill venue photos from Google Places API
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/backfill_venue_photos.js
 *
 * Options:
 *   MIN_RANK=70    Only backfill venues with rank >= this (default: 50)
 *   MAX_VENUES=50  Limit number of venues to process (default: 100)
 *   STATE=PA       Only backfill venues in this state (default: all states)
 *   DRY_RUN=1      Don't update DB, just show what would change
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

let GOOGLE_PLACES_API_KEY, SUPABASE_SERVICE_KEY;
const MIN_RANK = parseInt(process.env.MIN_RANK || "50", 10);
const MAX_VENUES = parseInt(process.env.MAX_VENUES || "100", 10);
const STATE = (process.env.STATE || "").trim().toUpperCase() || null;
const DRY_RUN = process.env.DRY_RUN === "1";

async function main() {
  const secrets = await getSecrets(["GOOGLE_PLACES_API_KEY"]);
  GOOGLE_PLACES_API_KEY = secrets.GOOGLE_PLACES_API_KEY;
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  if (!GOOGLE_PLACES_API_KEY) {
    console.error("Missing GOOGLE_PLACES_API_KEY");
    process.exit(1);
  }

  console.log(`Backfilling venue photos (min rank: ${MIN_RANK}, max: ${MAX_VENUES}, state: ${STATE || "ALL"}, dry run: ${DRY_RUN})\n`);

  // Fetch venues without photos. Pull city+state so the textQuery can scope
  // by location for venues that lack lat/lng — without that scoping, "Hope
  // Lodge" or "The Mill" matches arbitrary places globally and pollutes
  // photo_url with wrong venues.
  let url = `${SUPABASE_URL}/rest/v1/venues?is_active=eq.true&photo_url=is.null&public_interest_rank=gte.${MIN_RANK}&select=id,name,lat,lng,city,state,public_interest_rank&order=public_interest_rank.desc&limit=${MAX_VENUES}`;
  if (STATE) url += `&state=eq.${STATE}`;
  const res = await fetch(url, {
    headers: {
      apikey: SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
    },
  });
  const venues = await res.json();
  console.log(`Found ${venues.length} venues without photos\n`);

  let updated = 0;
  let failed = 0;

  for (const venue of venues) {
    try {
      const photoUrl = await fetchPlacePhoto(venue.name, venue.lat, venue.lng, venue.city, venue.state);
      if (photoUrl) {
        if (!DRY_RUN) {
          await updateVenuePhoto(venue.id, photoUrl);
        }
        console.log(`  ✓ ${venue.public_interest_rank} ${venue.name} → photo found`);
        updated++;
      } else {
        console.log(`  - ${venue.public_interest_rank} ${venue.name} → no photo`);
      }
      // Rate limit: 10 requests/sec max for Places API
      await new Promise((r) => setTimeout(r, 150));
    } catch (err) {
      console.log(`  ✗ ${venue.public_interest_rank} ${venue.name} → error: ${err.message}`);
      failed++;
    }
  }

  console.log(`\nDone: ${updated} updated, ${failed} failed, ${venues.length - updated - failed} no photo found`);
}

async function fetchPlacePhoto(name, lat, lng, city, state) {
  // When we have coords, lat/lng + plain name is the most accurate combo
  // (5km circle filters out distant same-name venues). When we don't, fall
  // back to scoping the text query with city + state so "Hope Lodge" or
  // "The Mill" doesn't match an unrelated global match. City alone if state
  // is missing, name alone if both are missing.
  const locationScope = [city, state].filter(Boolean).join(", ");
  const textQuery = (!lat || !lng) && locationScope ? `${name}, ${locationScope}` : name;
  const body = {
    textQuery,
    locationBias: lat && lng ? {
      circle: { center: { latitude: lat, longitude: lng }, radius: 5000 },
    } : undefined,
  };

  const res = await fetch("https://places.googleapis.com/v1/places:searchText", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Goog-Api-Key": GOOGLE_PLACES_API_KEY,
      "X-Goog-FieldMask": "places.photos",
    },
    body: JSON.stringify(body),
  });

  if (!res.ok) return null;

  const data = await res.json();
  const place = data.places?.[0];
  if (!place?.photos?.length) return null;

  const photoRef = place.photos[0].name;
  return `https://places.googleapis.com/v1/${photoRef}/media?maxHeightPx=800&maxWidthPx=800&key=${GOOGLE_PLACES_API_KEY}`;
}

async function updateVenuePhoto(venueId, photoUrl) {
  const url = `${SUPABASE_URL}/rest/v1/venues?id=eq.${venueId}`;
  await fetch(url, {
    method: "PATCH",
    headers: {
      apikey: SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      "Content-Type": "application/json",
      Prefer: "return=minimal",
    },
    body: JSON.stringify({ photo_url: photoUrl }),
  });
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
