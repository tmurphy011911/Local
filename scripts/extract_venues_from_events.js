/**
 * extract_venues_from_events.js — Extract unique venues from the events table and insert into venues
 *
 * Scans all events that have venue_name + venue_lat/venue_lng, deduplicates by
 * normalized name + city, checks against existing venues (by name+city or lat/lng
 * proximity), and inserts any new ones with source="event_extraction".
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/extract_venues_from_events.js
 *
 * Optional:
 *   DRY_RUN=1   (preview without writing to Supabase)
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

let SUPABASE_SERVICE_KEY;

const DRY_RUN = process.env.DRY_RUN === "1";

// ── Helpers ──

function normalize(name) {
  return (name || "")
    .toLowerCase()
    .replace(/[^a-z0-9\s]/g, "")
    .replace(/^the\s+/, "")
    .trim();
}

async function supaFetch(path, options = {}) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/${path}`, {
    ...options,
    headers: {
      apikey: SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      "Content-Type": "application/json",
      Prefer: options.prefer || "return=minimal",
      ...options.headers,
    },
  });
  return res;
}

// ── Fetch events with venue data (paginated) ──

async function fetchEventsWithVenues() {
  const events = [];
  let offset = 0;
  const limit = 1000;

  console.log("Fetching events with venue data...");

  while (true) {
    const res = await supaFetch(
      `events?select=venue_name,venue_address,venue_city,venue_state,venue_lat,venue_lng` +
      `&venue_name=not.is.null&venue_lat=not.is.null&venue_lng=not.is.null` +
      `&limit=${limit}&offset=${offset}`
    );

    if (!res.ok) {
      const body = await res.text();
      throw new Error(`Failed to fetch events: ${res.status} ${body.substring(0, 200)}`);
    }

    const batch = await res.json();
    if (!batch.length) break;

    events.push(...batch);
    console.log(`  Fetched ${events.length} events so far...`);
    offset += limit;
  }

  console.log(`  Total events with venue data: ${events.length}`);
  return events;
}

// ── Fetch all existing venues (paginated) ──

async function fetchAllVenues() {
  const venues = [];
  let offset = 0;
  const limit = 1000;

  while (true) {
    const res = await supaFetch(
      `venues?select=id,name,city,lat,lng&limit=${limit}&offset=${offset}`
    );

    if (!res.ok) {
      const body = await res.text();
      throw new Error(`Failed to fetch venues: ${res.status} ${body.substring(0, 200)}`);
    }

    const batch = await res.json();
    if (!batch.length) break;

    venues.push(...batch);
    offset += limit;
  }

  return venues;
}

// ── Deduplicate events into unique venues ──

function deduplicateVenues(events) {
  const seen = new Map(); // key: normalized_name|normalized_city -> venue

  for (const e of events) {
    if (!e.venue_name || e.venue_lat == null || e.venue_lng == null) continue;

    const nameKey = normalize(e.venue_name);
    const cityKey = normalize(e.venue_city || "");
    const key = `${nameKey}|${cityKey}`;

    if (!nameKey) continue;

    if (!seen.has(key)) {
      seen.set(key, {
        name: e.venue_name.trim(),
        city: (e.venue_city || "").trim() || null,
        state: (e.venue_state || "").trim() || null,
        lat: e.venue_lat,
        lng: e.venue_lng,
        metro_area: (e.venue_city || "").trim().toLowerCase() || null,
        is_active: true,
      });
    }
  }

  return Array.from(seen.values());
}

// ── Check if a venue already exists ──

const LAT_LNG_THRESHOLD = 0.001; // ~111 meters

function venueExists(venue, existingVenues) {
  const normName = normalize(venue.name);
  const normCity = normalize(venue.city || "");

  for (const existing of existingVenues) {
    // Match by normalized name + city
    const existingNormName = normalize(existing.name);
    const existingNormCity = normalize(existing.city || "");

    if (existingNormName === normName && existingNormCity === normCity) {
      return true;
    }

    // Match by lat/lng proximity (within ~0.001 degrees)
    if (existing.lat != null && existing.lng != null &&
        venue.lat != null && venue.lng != null) {
      const latDiff = Math.abs(existing.lat - venue.lat);
      const lngDiff = Math.abs(existing.lng - venue.lng);

      if (latDiff < LAT_LNG_THRESHOLD && lngDiff < LAT_LNG_THRESHOLD) {
        return true;
      }
    }
  }

  return false;
}

// ── Batch insert new venues ──

async function insertVenues(venues) {
  if (DRY_RUN) {
    console.log(`  [DRY RUN] Would insert ${venues.length} venues`);
    return { inserted: venues.length, errors: 0 };
  }

  const batchSize = 50;
  let inserted = 0;
  let errors = 0;

  for (let i = 0; i < venues.length; i += batchSize) {
    const batch = venues.slice(i, i + batchSize);
    const res = await supaFetch("venues", {
      method: "POST",
      body: JSON.stringify(batch),
    });

    if (!res.ok) {
      const body = await res.text();
      console.error(`  Supabase error (batch ${Math.floor(i / batchSize)}): ${res.status} ${body.substring(0, 500)}`);
      errors += batch.length;
    } else {
      inserted += batch.length;
    }
  }

  return { inserted, errors };
}

// ── Main ──

async function main() {
  const secrets = await getSecrets([]);
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  if (!SUPABASE_SERVICE_KEY) {
    console.error("Missing SUPABASE_SERVICE_KEY");
    process.exit(1);
  }

  console.log("=== Extract Venues from Events ===\n");
  if (DRY_RUN) console.log("MODE: DRY RUN\n");

  // 1. Fetch all events with venue data
  const events = await fetchEventsWithVenues();

  if (events.length === 0) {
    console.log("\nNo events with venue data found. Exiting.");
    return;
  }

  // 2. Deduplicate into unique venues
  const uniqueVenues = deduplicateVenues(events);
  console.log(`\nUnique venues from events: ${uniqueVenues.length}`);

  // 3. Fetch existing venues
  console.log("\nFetching existing venues...");
  const existingVenues = await fetchAllVenues();
  console.log(`  Existing venues in DB: ${existingVenues.length}`);

  // 4. Filter out venues that already exist
  const newVenues = uniqueVenues.filter((v) => !venueExists(v, existingVenues));
  const alreadyExisting = uniqueVenues.length - newVenues.length;

  console.log(`  Already existing: ${alreadyExisting}`);
  console.log(`  New venues to insert: ${newVenues.length}`);

  if (newVenues.length === 0) {
    console.log("\nNo new venues to insert. Done.");
    return;
  }

  // 5. Insert new venues
  console.log(`\nInserting ${newVenues.length} new venues...`);
  const { inserted, errors } = await insertVenues(newVenues);

  // 6. Summary
  console.log("\n=== Summary ===");
  console.log(`  Total events scanned: ${events.length}`);
  console.log(`  Unique venues found: ${uniqueVenues.length}`);
  console.log(`  Already existing: ${alreadyExisting}`);
  console.log(`  Newly inserted: ${inserted}`);
  console.log(`  Errors: ${errors}`);

  // City breakdown of new venues
  if (newVenues.length > 0) {
    const cities = {};
    newVenues.forEach((v) => {
      const city = v.city || "(unknown)";
      cities[city] = (cities[city] || 0) + 1;
    });
    console.log("\n  New venues by city:");
    Object.entries(cities)
      .sort((a, b) => b[1] - a[1])
      .forEach(([city, count]) => {
        console.log(`    ${city}: ${count}`);
      });
  }
}

main().catch((err) => {
  console.error("Fatal error:", err);
  process.exit(1);
});
