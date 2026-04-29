#!/usr/bin/env node
/**
 * Backfill city/neighborhood photos using Google Places.
 * Searches for "{city} landmark" or "{city} skyline" and stores the photo URL.
 * Falls back to the top-ranked venue photo in that city.
 *
 * Stores results in the venues table as a city_photo on the highest-ranked venue per city,
 * OR creates/updates a city_photos lookup. For simplicity, stores in a dedicated table.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... GOOGLE_PLACES_API_KEY=AI... node backfill_city_photos.js
 */

const { getSecrets, SUPABASE_URL: SUPA_URL } = require("./lib/secrets");

const SUPABASE_URL = SUPA_URL;
let SUPABASE_KEY, GOOGLE_KEY;

async function supabaseGet(table, query) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/${table}?${query}`, {
    headers: { apikey: SUPABASE_KEY, Authorization: `Bearer ${SUPABASE_KEY}` },
  });
  return res.json();
}

async function supabaseUpsert(table, data) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/${table}`, {
    method: "POST",
    headers: {
      apikey: SUPABASE_KEY,
      Authorization: `Bearer ${SUPABASE_KEY}`,
      "Content-Type": "application/json",
      Prefer: "resolution=merge-duplicates",
    },
    body: JSON.stringify(data),
  });
  return res.ok;
}

async function searchCityPhoto(cityName, state) {
  const query = `${cityName}${state ? ` ${state}` : ""} downtown aerial view`;
  try {
    const res = await fetch("https://places.googleapis.com/v1/places:searchText", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": GOOGLE_KEY,
        "X-Goog-FieldMask": "places.photos,places.displayName",
      },
      body: JSON.stringify({ textQuery: query, maxResultCount: 1 }),
    });
    if (!res.ok) return null;
    const data = await res.json();
    const place = data.places?.[0];
    if (place?.photos?.[0]) {
      const photoRef = place.photos[0].name;
      return `https://places.googleapis.com/v1/${photoRef}/media?maxWidthPx=800&key=${GOOGLE_KEY}`;
    }
  } catch (_) {}
  return null;
}

async function main() {
  // Pull GOOGLE_PLACES_API_KEY from the Supabase `secrets` table the same
  // way ticketmaster_sync.js / eventbrite_sync.js do. SUPABASE_SERVICE_KEY
  // bootstraps; the helper falls back to env vars if the DB fetch fails.
  const secrets = await getSecrets(["GOOGLE_PLACES_API_KEY"]);
  SUPABASE_KEY = secrets.SUPABASE_SERVICE_KEY;
  GOOGLE_KEY = secrets.GOOGLE_PLACES_API_KEY || process.env.GOOGLE_PLACES_API_KEY;
  if (!GOOGLE_KEY) {
    console.error("Missing GOOGLE_PLACES_API_KEY (not in Supabase secrets table or env vars)");
    process.exit(1);
  }

  console.log("Backfilling city photos...");

  // Get all unique cities from events
  const events = await supabaseGet("events", "select=venue_city&is_active=eq.true&limit=10000");
  const cities = [...new Set(events.map((e) => e.venue_city).filter(Boolean))].sort();
  console.log(`Found ${cities.length} unique cities`);

  // Get top venue photo per city as fallback
  const venues = await supabaseGet(
    "venues",
    "select=city,photo_url,public_interest_rank&photo_url=not.is.null&is_active=eq.true&order=public_interest_rank.desc.nullslast"
  );
  const topVenuePhoto = {};
  for (const v of venues) {
    if (v.city && v.photo_url && !topVenuePhoto[v.city]) {
      topVenuePhoto[v.city] = v.photo_url;
    }
  }

  const results = [];
  for (let i = 0; i < cities.length; i++) {
    const city = cities[i];

    // Try Google Places landmark search
    const googlePhoto = await searchCityPhoto(city);

    if (googlePhoto) {
      console.log(`[${i + 1}/${cities.length}] ${city} -> Google landmark photo`);
      results.push({ city: city, photo_url: googlePhoto, source: "google_landmark" });
    } else if (topVenuePhoto[city]) {
      console.log(`[${i + 1}/${cities.length}] ${city} -> Fallback to top venue photo`);
      results.push({ city: city, photo_url: topVenuePhoto[city], source: "venue_fallback" });
    } else {
      console.log(`[${i + 1}/${cities.length}] ${city} -> No photo found`);
    }

    // Rate limit
    if (googlePhoto) await new Promise((r) => setTimeout(r, 200));
  }

  // Upsert to city_photos table
  if (results.length > 0) {
    const ok = await supabaseUpsert("city_photos", results);
    console.log(`\nUpserted ${results.length} city photos: ${ok ? "SUCCESS" : "FAILED"}`);
    if (!ok) {
      console.log("Table 'city_photos' may not exist. Create it with:");
      console.log("CREATE TABLE city_photos (city TEXT PRIMARY KEY, photo_url TEXT, source TEXT);");
    }
  }

  console.log(`Done. ${results.length}/${cities.length} cities have photos.`);
}

main().catch((e) => { console.error("Fatal:", e); process.exit(1); });
