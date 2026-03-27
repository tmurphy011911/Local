/**
 * eventbrite_sync.js — Eventbrite event sync pipeline
 *
 * Fetches upcoming events from the Eventbrite API for all active metros,
 * normalizes them, and upserts to Supabase.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/eventbrite_sync.js
 *
 * Optional:
 *   MAX_PAGES=0            — limit pages to fetch per category (0 = all)
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

let EVENTBRITE_API_KEY, SUPABASE_SERVICE_KEY;

const MAX_PAGES = parseInt(process.env.MAX_PAGES || "0", 10);

// Per-metro variables set during each iteration
let CURRENT_CITY, CURRENT_LAT, CURRENT_LNG, CURRENT_WITHIN;

// ── Eventbrite categories → our categories ──

const EB_CATEGORY_MAP = {
  103: "music",        // Music
  101: "arts",         // Business (skip most, but include)
  105: "arts",         // Performing & Visual Arts
  104: "arts",         // Film, Media & Entertainment
  110: "food",         // Food & Drink
  108: "sports",       // Sports & Fitness
  113: "other",        // Community & Culture
  109: "other",        // Travel & Outdoor
  107: "arts",         // Health & Wellness → arts (yoga, mindfulness events)
  106: "arts",         // Fashion & Beauty
  102: "arts",         // Science & Technology
  111: "other",        // Charity & Causes
  112: "other",        // Government & Politics
  114: "other",        // Religion & Spirituality
  115: "other",        // Family & Education
  116: "other",        // Seasonal & Holiday
  117: "other",        // Home & Lifestyle
  199: "other",        // Other
};

// Categories worth querying (skip business, government, religion, etc.)
const CATEGORIES_TO_FETCH = [
  { id: "103", name: "Music" },
  { id: "105", name: "Performing & Visual Arts" },
  { id: "104", name: "Film & Entertainment" },
  { id: "110", name: "Food & Drink" },
  { id: "108", name: "Sports & Fitness" },
  { id: "113", name: "Community & Culture" },
  { id: "115", name: "Family & Education" },
];

// ── Subcategory detection ──

function detectSubcategory(eb) {
  const name = (eb.name?.text || "").toLowerCase();
  const venueName = (eb.venue?.name || "").toLowerCase();
  const catId = eb.category_id ? parseInt(eb.category_id) : null;
  const subcatId = eb.subcategory_id ? parseInt(eb.subcategory_id) : null;

  // Venue-based
  if (venueName.includes("museum")) return "museum";
  if (venueName.includes("library")) return "library";
  if (venueName.includes("gallery") || venueName.includes("art center")) return "gallery";
  if (venueName.includes("theater") || venueName.includes("theatre")) return "theatre";
  if (venueName.includes("comedy") || name.includes("stand-up") || name.includes("standup") || name.includes("comedy show")) return "comedy";

  // Category-based
  if (catId === 103) return "music";
  if (catId === 108) return "sports";
  if (catId === 110) return "food";
  if (catId === 105) {
    if (name.includes("comedy") || name.includes("improv")) return "comedy";
    if (name.includes("dance")) return "dance";
    return "theatre";
  }

  // Name-based
  if (name.includes("tour") && !name.includes("tournament")) return "tour";
  if (name.includes("festival")) return "festival";

  return null;
}

// ── Helpers ──

function hashEvent(sourceId) {
  return `eventbrite:${sourceId}`;
}

function bestImage(logo) {
  if (!logo) return null;
  // Eventbrite provides logo.original.url or logo.url
  return logo.original?.url || logo.url || null;
}

function normalizeEvent(eb) {
  const venue = eb.venue || null;
  const isFree = eb.is_free === true;
  const catId = eb.category_id ? parseInt(eb.category_id) : null;

  return {
    title: eb.name?.text || eb.name?.html || "Untitled",
    description: eb.summary || eb.description?.text?.slice(0, 500) || null,
    category: EB_CATEGORY_MAP[catId] || "other",
    subcategory: detectSubcategory(eb),
    start_at: eb.start?.utc || null,
    end_at: eb.end?.utc || null,
    is_free: isFree,
    price_min: null, // Eventbrite doesn't return price in search results
    price_max: null,
    ticket_url: eb.url || null,
    image_url: bestImage(eb.logo),
    status: "active",
    venue_name: venue?.name || null,
    venue_address: venue?.address?.localized_address_display || null,
    venue_city: venue?.address?.city || CURRENT_CITY.charAt(0).toUpperCase() + CURRENT_CITY.slice(1),
    venue_state: venue?.address?.region || null,
    venue_lat: venue?.latitude ? parseFloat(venue.latitude) : null,
    venue_lng: venue?.longitude ? parseFloat(venue.longitude) : null,
    source: "eventbrite",
    source_id: eb.id,
    source_url: eb.url || null,
    confidence_score: 1.0,
    needs_review: false,
    dedupe_hash: hashEvent(eb.id),
    city: CURRENT_CITY,
    last_synced_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  };
}

// ── Fetch events from Eventbrite ──

async function fetchCategoryEvents(category) {
  const events = [];
  let page = 1;
  let hasMore = true;

  while (hasMore) {
    if (MAX_PAGES > 0 && page > MAX_PAGES) break;

    const url = new URL("https://www.eventbriteapi.com/v3/events/search/");
    url.searchParams.set("location.latitude", CURRENT_LAT);
    url.searchParams.set("location.longitude", CURRENT_LNG);
    url.searchParams.set("location.within", CURRENT_WITHIN);
    url.searchParams.set("categories", category.id);
    // 60-day lookahead instead of just current month
    const now = new Date();
    const sixtyDaysOut = new Date(now.getTime() + 60 * 24 * 60 * 60 * 1000);
    url.searchParams.set("start_date.range_start", now.toISOString().replace(/\.\d{3}Z$/, "Z"));
    url.searchParams.set("start_date.range_end", sixtyDaysOut.toISOString().replace(/\.\d{3}Z$/, "Z"));
    url.searchParams.set("expand", "venue");
    url.searchParams.set("page", page.toString());

    const res = await fetch(url.toString(), {
      headers: {
        Authorization: `Bearer ${EVENTBRITE_API_KEY}`,
      },
    });

    if (!res.ok) {
      if (res.status === 429) {
        console.log("      Rate limited, waiting 3s...");
        await new Promise((r) => setTimeout(r, 3000));
        continue;
      }
      const body = await res.text();
      console.error(`    API error: ${res.status} ${body.slice(0, 200)}`);
      break;
    }

    const data = await res.json();
    const batch = data.events || [];
    events.push(...batch);

    hasMore = data.pagination?.has_more_items || false;
    page++;

    // Respect rate limits
    await new Promise((r) => setTimeout(r, 300));
  }

  return events;
}

async function fetchAllEvents() {
  const seen = new Set();
  const allEvents = [];

  for (const category of CATEGORIES_TO_FETCH) {
    console.log(`\n${category.name}:`);
    const events = await fetchCategoryEvents(category);

    let added = 0;
    for (const e of events) {
      if (!seen.has(e.id)) {
        seen.add(e.id);
        allEvents.push(e);
        added++;
      }
    }
    console.log(`  ${events.length} fetched, ${added} new`);
  }

  console.log(`\nTotal unique events: ${allEvents.length}`);
  return allEvents;
}

// ── Upsert to Supabase ──

async function upsertToSupabase(rows) {
  const url = `${SUPABASE_URL}/rest/v1/events?on_conflict=dedupe_hash`;
  const batchSize = 50;
  let inserted = 0;
  let errors = 0;

  // Deduplicate by dedupe_hash
  const deduped = [...new Map(rows.map((r) => [r.dedupe_hash, r])).values()];
  if (deduped.length < rows.length) {
    console.log(`  Deduped: ${rows.length} -> ${deduped.length}`);
  }

  for (let i = 0; i < deduped.length; i += batchSize) {
    const batch = deduped.slice(i, i + batchSize);
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
      console.error(`  Supabase upsert error (batch ${Math.floor(i / batchSize)}): ${res.status} ${body}`);
      errors += batch.length;
    } else {
      inserted += batch.length;
    }
  }

  return { inserted, errors };
}

// ── Fetch active metros ──

async function fetchActiveMetros() {
  const res = await fetch(
    `${SUPABASE_URL}/rest/v1/metros?is_active=eq.true&select=*`,
    {
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      },
    }
  );

  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Failed to fetch metros: ${res.status} ${body}`);
  }

  return res.json();
}

// ── Main ──

async function main() {
  const secrets = await getSecrets(["EVENTBRITE_API_KEY"]);
  EVENTBRITE_API_KEY = secrets.EVENTBRITE_API_KEY;
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  if (!EVENTBRITE_API_KEY) {
    console.error("Missing EVENTBRITE_API_KEY. Add it to the Supabase secrets table or pass as env var.");
    process.exit(1);
  }

  console.log("=== Eventbrite -> Supabase Sync ===\n");

  // Fetch all active metros
  const metros = await fetchActiveMetros();
  console.log(`Found ${metros.length} active metro(s): ${metros.map((m) => m.name).join(", ")}\n`);

  if (metros.length === 0) {
    console.log("No active metros found. Run seed_metros.js first.");
    return;
  }

  let grandTotal = { fetched: 0, valid: 0, upserted: 0, skipped: 0, errors: 0 };

  for (const metro of metros) {
    // Set per-metro variables
    CURRENT_CITY = metro.name.toLowerCase();
    CURRENT_LAT = metro.lat.toString();
    CURRENT_LNG = metro.lng.toString();
    CURRENT_WITHIN = `${metro.radius_miles || 75}mi`;

    console.log(`\n${"=".repeat(50)}`);
    console.log(`Syncing metro: ${metro.name} (${CURRENT_LAT}, ${CURRENT_LNG}, ${CURRENT_WITHIN})`);
    console.log(`${"=".repeat(50)}`);

    // 1. Fetch from Eventbrite
    const ebEvents = await fetchAllEvents();

    if (ebEvents.length === 0) {
      console.log(`No events found for ${metro.name}. Skipping.`);
      continue;
    }

    // 2. Normalize
    const rows = ebEvents.map(normalizeEvent);
    const valid = rows.filter((r) => r.start_at);
    const skipped = rows.length - valid.length;
    console.log(`\nNormalized: ${valid.length} valid, ${skipped} skipped (no date)`);

    // 3. Upsert to Supabase
    console.log(`\nUpserting ${valid.length} events to Supabase...`);
    const { inserted, errors } = await upsertToSupabase(valid);

    // 4. Per-metro summary
    console.log(`\n--- ${metro.name} Summary ---`);
    console.log(`  Fetched:   ${ebEvents.length}`);
    console.log(`  Valid:     ${valid.length}`);
    console.log(`  Upserted:  ${inserted}`);
    console.log(`  Skipped:   ${skipped}`);
    console.log(`  Errors:    ${errors}`);

    grandTotal.fetched += ebEvents.length;
    grandTotal.valid += valid.length;
    grandTotal.upserted += inserted;
    grandTotal.skipped += skipped;
    grandTotal.errors += errors;

    // Category breakdown
    const cats = {};
    valid.forEach((r) => {
      cats[r.category] = (cats[r.category] || 0) + 1;
    });
    console.log("\n  By category:");
    Object.entries(cats)
      .sort((a, b) => b[1] - a[1])
      .forEach(([cat, count]) => {
        console.log(`    ${cat}: ${count}`);
      });

    // Subcategory breakdown
    const subs = {};
    valid.forEach((r) => {
      if (r.subcategory) {
        subs[r.subcategory] = (subs[r.subcategory] || 0) + 1;
      }
    });
    if (Object.keys(subs).length > 0) {
      console.log("\n  Top subcategories:");
      Object.entries(subs)
        .sort((a, b) => b[1] - a[1])
        .slice(0, 15)
        .forEach(([sub, count]) => {
          console.log(`    ${sub}: ${count}`);
        });
    }
  }

  // Grand total
  console.log(`\n${"=".repeat(50)}`);
  console.log("=== Grand Total ===");
  console.log(`  Metros synced: ${metros.length}`);
  console.log(`  Fetched:   ${grandTotal.fetched}`);
  console.log(`  Valid:     ${grandTotal.valid}`);
  console.log(`  Upserted:  ${grandTotal.upserted}`);
  console.log(`  Skipped:   ${grandTotal.skipped}`);
  console.log(`  Errors:    ${grandTotal.errors}`);
}

main().catch((err) => {
  console.error("Fatal error:", err);
  process.exit(1);
});
