/**
 * Backfill venue photos AND websites from Google Places API.
 *
 * One Places Text Search per venue returns both the photo and the authoritative
 * websiteUri, so we fill whichever of photo_url / website the venue is missing.
 * The website is what fix_venue_urls.js scrapes to discover an events_url, so a
 * venue with no website is invisible to URL discovery and never crawls — keep
 * this in sync with that dependency.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/backfill_venue_photos.js
 *
 * Options:
 *   MIN_RANK=70             Only backfill venues with rank >= this (default: 50)
 *   MAX_VENUES=50           Limit number of venues to process (default: 100)
 *   STATE=PA                Only backfill venues in this state (default: all states)
 *   CITIES="Doylestown,New Hope"  Comma-separated city list (default: all cities in state)
 *   CREATED_AFTER=2026-06-25      Only venues created on/after this date (ISO) (default: all)
 *   DRY_RUN=1               Don't update DB, just show what would change
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

let GOOGLE_PLACES_API_KEY, SUPABASE_SERVICE_KEY;
const MIN_RANK = parseInt(process.env.MIN_RANK || "50", 10);
const MAX_VENUES = parseInt(process.env.MAX_VENUES || "100", 10);
const STATE = (process.env.STATE || "").trim().toUpperCase() || null;
const CITIES = (process.env.CITIES || "").split(",").map((c) => c.trim()).filter(Boolean);
const CREATED_AFTER = (process.env.CREATED_AFTER || "").trim() || null;
const DRY_RUN = process.env.DRY_RUN === "1";

async function main() {
  const secrets = await getSecrets(["GOOGLE_PLACES_API_KEY"]);
  GOOGLE_PLACES_API_KEY = secrets.GOOGLE_PLACES_API_KEY;
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  if (!GOOGLE_PLACES_API_KEY) {
    console.error("Missing GOOGLE_PLACES_API_KEY");
    process.exit(1);
  }

  console.log(`Backfilling venue photos (min rank: ${MIN_RANK}, max: ${MAX_VENUES}, state: ${STATE || "ALL"}, cities: ${CITIES.length || "ALL"}, created_after: ${CREATED_AFTER || "any"}, dry run: ${DRY_RUN})\n`);

  // Fetch venues without photos. Pull city+state so the textQuery can scope
  // by location for venues that lack lat/lng — without that scoping, "Hope
  // Lodge" or "The Mill" matches arbitrary places globally and pollutes
  // photo_url with wrong venues.
  let url = `${SUPABASE_URL}/rest/v1/venues?is_active=eq.true&or=(photo_url.is.null,website.is.null)&public_interest_rank=gte.${MIN_RANK}&select=id,name,lat,lng,city,state,public_interest_rank,photo_url,website&order=public_interest_rank.desc&limit=${MAX_VENUES}`;
  if (STATE) url += `&state=eq.${STATE}`;
  if (CITIES.length) {
    // PostgREST in.() value list: quote each city to preserve spaces/commas
    const quoted = CITIES.map((c) => `"${c.replace(/"/g, '\\"')}"`).join(",");
    url += `&city=in.(${encodeURIComponent(quoted)})`;
  }
  if (CREATED_AFTER) url += `&created_at=gte.${encodeURIComponent(CREATED_AFTER)}`;
  const res = await fetch(url, {
    headers: {
      apikey: SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
    },
  });
  const venues = await res.json();
  console.log(`Found ${venues.length} venues missing a photo or website\n`);

  let updated = 0;
  let failed = 0;
  let noMatch = 0;

  for (const venue of venues) {
    try {
      const details = await fetchPlaceDetails(venue.name, venue.lat, venue.lng, venue.city, venue.state);
      // Only fill the fields the venue is actually missing; never overwrite an
      // existing photo_url / website with a (possibly different) Places match.
      const patch = {};
      if (!venue.photo_url && details?.photoUrl) patch.photo_url = details.photoUrl;
      if (!venue.website && details?.website) patch.website = details.website;

      if (Object.keys(patch).length) {
        if (!DRY_RUN) await updateVenueFields(venue.id, patch);
        console.log(`  ✓ ${venue.public_interest_rank} ${venue.name} → ${Object.keys(patch).join(" + ")}`);
        updated++;
      } else {
        console.log(`  - ${venue.public_interest_rank} ${venue.name} → no match`);
        noMatch++;
      }
      // Rate limit: 10 requests/sec max for Places API
      await new Promise((r) => setTimeout(r, 150));
    } catch (err) {
      console.log(`  ✗ ${venue.public_interest_rank} ${venue.name} → error: ${err.message}`);
      failed++;
    }
  }

  console.log(`\nDone: ${updated} updated, ${failed} failed, ${noMatch} no match`);
}

async function fetchPlaceDetails(name, lat, lng, city, state) {
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
      "X-Goog-FieldMask": "places.photos,places.websiteUri",
    },
    body: JSON.stringify(body),
  });

  if (!res.ok) return null;

  const data = await res.json();
  const place = data.places?.[0];
  if (!place) return null;

  const photoUrl = place.photos?.length
    ? `https://places.googleapis.com/v1/${place.photos[0].name}/media?maxHeightPx=800&maxWidthPx=800&key=${GOOGLE_PLACES_API_KEY}`
    : null;
  return { photoUrl, website: place.websiteUri || null };
}

async function updateVenueFields(venueId, patch) {
  const url = `${SUPABASE_URL}/rest/v1/venues?id=eq.${venueId}`;
  await fetch(url, {
    method: "PATCH",
    headers: {
      apikey: SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      "Content-Type": "application/json",
      Prefer: "return=minimal",
    },
    body: JSON.stringify(patch),
  });
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
