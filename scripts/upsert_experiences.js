/**
 * upsert_experiences.js — Upsert curated always-on experiences to Supabase
 *
 * Matches venue names against existing venues table. Creates new venue records
 * for unmatched venues. Upserts experiences as events.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/upsert_experiences.js path/to/experiences.json
 */

const fs = require("fs");
const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

let SUPABASE_SERVICE_KEY;

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
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Supabase ${options.method || "GET"} ${path} failed: ${res.status} ${body.substring(0, 200)}`);
  }
  return res;
}

// ── Fetch all existing venues ──

async function fetchAllVenues() {
  const venues = [];
  let offset = 0;
  while (true) {
    const res = await supaFetch(
      `venues?select=id,name,city,state,lat,lng&is_active=eq.true&limit=1000&offset=${offset}`
    );
    const batch = await res.json();
    if (!batch.length) break;
    venues.push(...batch);
    offset += 1000;
  }
  return venues;
}

// ── Match venue by normalized name ──

function findVenueMatch(venueName, existingVenues) {
  const norm = normalize(venueName);
  if (!norm) return null;

  // Exact normalized match
  let match = existingVenues.find((v) => normalize(v.name) === norm);
  if (match) return match;

  // Containment match (e.g. "Museum of Fine Arts" matches "Museum of Fine Arts, Boston")
  match = existingVenues.find((v) => {
    const vNorm = normalize(v.name);
    return vNorm.includes(norm) || norm.includes(vNorm);
  });
  return match || null;
}

// ── Create new venue ──

async function createVenue(exp) {
  const venue = {
    name: exp.venue_name,
    city: exp.venue_city || "Boston",
    state: exp.venue_state || "MA",
    lat: exp.venue_lat || null,
    lng: exp.venue_lng || null,
    is_active: true,
    category: "attraction",
    website: exp.ticket_url || null,
  };

  const res = await supaFetch("venues?on_conflict=name,city", {
    method: "POST",
    body: JSON.stringify(venue),
    prefer: "return=representation,resolution=merge-duplicates",
  });
  const result = await res.json();
  const row = Array.isArray(result) ? result[0] : result;
  console.log(`  + Venue: ${venue.name} (${venue.city})`);
  return row;
}

// ── Upsert events ──

async function upsertEvents(events) {
  const batchSize = 50;
  let inserted = 0;
  let errors = 0;

  for (let i = 0; i < events.length; i += batchSize) {
    const batch = events.slice(i, i + batchSize);
    try {
      await supaFetch("events", {
        method: "POST",
        body: JSON.stringify(batch),
        prefer: "return=minimal",
      });
      inserted += batch.length;
    } catch (err) {
      console.error(`  Error upserting batch ${i}-${i + batch.length}: ${err.message}`);
      errors += batch.length;
    }
  }

  return { inserted, errors };
}

// ── Main ──

async function main() {
  const filePath = process.argv[2];
  if (!filePath) {
    console.error("Usage: node upsert_experiences.js <path/to/experiences.json>");
    process.exit(1);
  }

  const secrets = await getSecrets([]);
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  const experiences = JSON.parse(fs.readFileSync(filePath, "utf-8"));
  console.log(`=== Upsert Experiences ===`);
  console.log(`File: ${filePath}`);
  console.log(`Experiences: ${experiences.length}\n`);

  // 1. Fetch existing venues
  console.log("Fetching existing venues...");
  const existingVenues = await fetchAllVenues();
  console.log(`  Found ${existingVenues.length} existing venues\n`);

  // 2. Match venues and create missing ones
  let matched = 0;
  let created = 0;
  const eventRows = [];

  for (const exp of experiences) {
    const match = findVenueMatch(exp.venue_name, existingVenues);

    if (match) {
      matched++;
      // Use the matched venue's data
      exp.venue_name = match.name;
      exp.venue_city = match.city;
      exp.venue_state = match.state;
      exp.venue_lat = match.lat || exp.venue_lat;
      exp.venue_lng = match.lng || exp.venue_lng;
    } else {
      // Create new venue
      const newVenue = await createVenue(exp);
      existingVenues.push(newVenue); // add to list for future matches
      created++;
    }

    // Build event row
    eventRows.push({
      title: exp.title,
      description: exp.description || null,
      category: exp.category || "always_on",
      subcategory: exp.subcategory || null,
      sub_subcategory: exp.sub_subcategory || null,
      start_at: exp.start_at || null,
      end_at: exp.end_at || null,
      is_free: exp.is_free || false,
      price_min: exp.price_min ?? null,
      price_max: exp.price_max ?? null,
      ticket_url: exp.ticket_url || null,
      image_url: exp.image_url || null,
      status: "active",
      venue_name: exp.venue_name,
      venue_address: exp.venue_address || null,
      venue_city: exp.venue_city,
      venue_state: exp.venue_state || null,
      venue_lat: exp.venue_lat || null,
      venue_lng: exp.venue_lng || null,
      source: "curated",
      source_id: exp.source_id,
      city: exp.city || "boston",
      is_active: true,
    });
  }

  console.log(`\nVenue matching: ${matched} matched, ${created} created\n`);

  // 3. Upsert events
  console.log(`Upserting ${eventRows.length} experiences...`);
  const { inserted, errors } = await upsertEvents(eventRows);

  console.log(`\n=== Done ===`);
  console.log(`  Matched venues: ${matched}`);
  console.log(`  Created venues: ${created}`);
  console.log(`  Upserted events: ${inserted}`);
  console.log(`  Errors: ${errors}`);
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
