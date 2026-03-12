const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

let TICKETMASTER_API_KEY, SUPABASE_SERVICE_KEY;

const CITY = "boston";
const METRO = "boston";
const GEO_LAT = "42.3601";
const GEO_LNG = "-71.0589";
const GEO_RADIUS = "75";
const GEO_UNIT = "miles";

// ── Classification segments to query separately (gets us past the 1000-result cap) ──
const SEGMENTS = [
  { name: "Music", id: "KZFzniwnSyZfZ7v7nJ" },
  { name: "Sports", id: "KZFzniwnSyZfZ7v7nE" },
  { name: "Arts & Theatre", id: "KZFzniwnSyZfZ7v7na" },
  { name: "Miscellaneous", id: "KZFzniwnSyZfZ7v7n1" },
];

// ── Category mapping ──

const SEGMENT_MAP = {
  music: "music",
  sports: "sports",
  "arts & theatre": "arts",
  film: "arts",
  miscellaneous: "other",
  comedy: "comedy",
  dance: "arts",
  "food & drink": "food",
  outdoor: "outdoors",
  nightlife: "nightlife",
};

const SPORTS_GENRES = [
  "hockey", "football", "baseball", "basketball", "soccer", "tennis",
  "boxing", "wrestling", "racing", "mma", "golf", "rugby", "lacrosse",
  "volleyball", "skating", "cricket", "motorsports", "equestrian",
];

function mapCategory(classifications) {
  if (!classifications || classifications.length === 0) return "other";
  const segment = (classifications[0].segment?.name || "").toLowerCase();
  const genre = (classifications[0].genre?.name || "").toLowerCase();
  const subGenre = (classifications[0].subGenre?.name || "").toLowerCase();

  if (segment && SEGMENT_MAP[segment]) return SEGMENT_MAP[segment];
  if (SPORTS_GENRES.some((s) => genre.includes(s) || subGenre.includes(s))) return "sports";
  if (genre && SEGMENT_MAP[genre]) return SEGMENT_MAP[genre];
  return "other";
}

// ── Subcategory detection (for venue-based filtering: museum, library, tour, theatre) ──

function detectSubcategory(tm) {
  const venueName = (tm._embedded?.venues?.[0]?.name || "").toLowerCase();
  const eventName = (tm.name || "").toLowerCase();
  const genre = (tm.classifications?.[0]?.genre?.name || "").toLowerCase();
  const subGenre = (tm.classifications?.[0]?.subGenre?.name || "").toLowerCase();

  // Venue-based
  if (venueName.includes("museum")) return "museum";
  if (venueName.includes("library")) return "library";
  if (venueName.includes("gallery") || venueName.includes("art center")) return "gallery";

  // Genre-based
  if (genre.includes("theatre") || genre.includes("theater") ||
      genre === "musical" || subGenre.includes("play") || subGenre.includes("musical"))
    return "theatre";

  // Guided/venue tours (not concert tours or comedy tours)
  const isConcertOrComedy = ["music", "rock", "pop", "country", "hip-hop", "r&b", "comedy", "blues", "jazz", "latin", "metal", "alternative", "classical"]
    .some((g) => genre.includes(g) || subGenre.includes(g));
  if (!isConcertOrComedy &&
      (eventName.includes("guided tour") || eventName.includes("walking tour") ||
       eventName.includes("tour of ") || eventName.includes("ghost tour") ||
       eventName.includes("food tour") || eventName.includes("history tour") ||
       eventName.includes("brewery tour") || eventName.includes("boat tour") ||
       (venueName.includes("tour") && !venueName.includes("tournament"))))
    return "tour";

  return genre || null;
}

// ── Helpers ──

function hashEvent(source, sourceId) {
  return `${source}:${sourceId}`;
}

function bestImage(images) {
  if (!images || images.length === 0) return null;
  const ranked = images
    .filter((img) => img.url)
    .sort((a, b) => {
      const aRatio = a.ratio === "16_9" ? 1 : 0;
      const bRatio = b.ratio === "16_9" ? 1 : 0;
      if (aRatio !== bRatio) return bRatio - aRatio;
      return (b.width || 0) - (a.width || 0);
    });
  return ranked[0]?.url || null;
}

function parsePrice(priceRanges) {
  if (!priceRanges || priceRanges.length === 0) return { min: null, max: null };
  const range = priceRanges[0];
  return {
    min: range.min != null ? range.min : null,
    max: range.max != null ? range.max : null,
  };
}

function normalizeEvent(tm) {
  const venue = tm._embedded?.venues?.[0];
  const prices = parsePrice(tm.priceRanges);
  const startAt = tm.dates?.start?.dateTime || tm.dates?.start?.localDate;
  const endAt = tm.dates?.end?.dateTime || null;

  return {
    title: tm.name,
    description: tm.info || tm.pleaseNote || null,
    category: mapCategory(tm.classifications),
    subcategory: detectSubcategory(tm),
    start_at: startAt,
    end_at: endAt,
    is_free: prices.min === 0 && prices.max === 0,
    price_min: prices.min,
    price_max: prices.max,
    ticket_url: tm.url || null,
    image_url: bestImage(tm.images),
    status: "active",
    venue_name: venue?.name || null,
    venue_address: venue?.address?.line1 || null,
    venue_city: venue?.city?.name || "Boston",
    venue_lat: venue?.location?.latitude
      ? parseFloat(venue.location.latitude)
      : null,
    venue_lng: venue?.location?.longitude
      ? parseFloat(venue.location.longitude)
      : null,
    source: "ticketmaster",
    source_id: tm.id,
    source_url: tm.url || null,
    confidence_score: 1.0,
    needs_review: false,
    dedupe_hash: hashEvent("ticketmaster", tm.id),
    city: CITY,
    metro_area: METRO,
    last_synced_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  };
}

// ── Date windows: split into monthly chunks so each (segment × month) can return up to 1000 ──

function buildDateWindows(months) {
  const windows = [];
  const now = new Date();
  for (let i = 0; i < months; i++) {
    const start = new Date(now);
    start.setMonth(start.getMonth() + i);
    if (i > 0) start.setDate(1); // first window starts now, rest start on the 1st
    const end = new Date(start);
    end.setMonth(end.getMonth() + 1);
    end.setDate(1); // end at start of next month
    windows.push({
      start: start.toISOString().replace(/\.\d+Z$/, "Z"),
      end: end.toISOString().replace(/\.\d+Z$/, "Z"),
      label: start.toLocaleString("en-US", { month: "short", year: "numeric" }),
    });
  }
  return windows;
}

async function fetchWindow(segment, window) {
  const events = [];
  let page = 0;
  const size = 200;

  while (true) {
    const url = new URL(
      "https://app.ticketmaster.com/discovery/v2/events.json"
    );
    url.searchParams.set("apikey", TICKETMASTER_API_KEY);
    url.searchParams.set("geoPoint", `${GEO_LAT},${GEO_LNG}`);
    url.searchParams.set("radius", GEO_RADIUS);
    url.searchParams.set("unit", GEO_UNIT);
    url.searchParams.set("size", size.toString());
    url.searchParams.set("page", page.toString());
    url.searchParams.set("sort", "date,asc");
    url.searchParams.set("segmentId", segment.id);
    url.searchParams.set("startDateTime", window.start);
    url.searchParams.set("endDateTime", window.end);

    const res = await fetch(url.toString());
    if (!res.ok) {
      if (res.status === 429) {
        console.log("      Rate limited, waiting 2s...");
        await new Promise((r) => setTimeout(r, 2000));
        continue;
      }
      console.error(`      API error: ${res.status} ${res.statusText}`);
      break;
    }

    const data = await res.json();
    const batch = data._embedded?.events || [];
    events.push(...batch);

    const totalPages = data.page?.totalPages || 1;
    page++;
    if (page >= totalPages || page >= 5) break;

    await new Promise((r) => setTimeout(r, 220));
  }

  return events;
}

async function fetchAllEvents() {
  const seen = new Set();
  const allEvents = [];
  const windows = buildDateWindows(12); // 12 months of events

  for (const segment of SEGMENTS) {
    console.log(`\n${segment.name}:`);
    for (const window of windows) {
      const events = await fetchWindow(segment, window);
      let added = 0;
      for (const e of events) {
        if (!seen.has(e.id)) {
          seen.add(e.id);
          allEvents.push(e);
          added++;
        }
      }
      if (events.length > 0) {
        console.log(`  ${window.label}: ${events.length} fetched, ${added} new`);
      }
      await new Promise((r) => setTimeout(r, 250));
    }
  }

  console.log(`\nTotal unique events: ${allEvents.length}`);
  return allEvents;
}

async function upsertToSupabase(rows) {
  const url = `${SUPABASE_URL}/rest/v1/events?on_conflict=dedupe_hash`;
  const batchSize = 50;
  let inserted = 0;
  let errors = 0;

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
      console.error(`Supabase upsert error (batch ${i / batchSize}): ${res.status} ${body}`);
      errors += batch.length;
    } else {
      inserted += batch.length;
    }
  }

  return { inserted, errors };
}

async function main() {
  const secrets = await getSecrets(["TICKETMASTER_API_KEY"]);
  TICKETMASTER_API_KEY = secrets.TICKETMASTER_API_KEY;
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  console.log("=== Ticketmaster -> Supabase Sync ===\n");

  // 1. Fetch from Ticketmaster (per segment × monthly windows for max coverage)
  const tmEvents = await fetchAllEvents();

  if (tmEvents.length === 0) {
    console.log("No events found. Exiting.");
    return;
  }

  // 2. Normalize
  const rows = tmEvents.map(normalizeEvent);

  const valid = rows.filter((r) => r.start_at);
  const skipped = rows.length - valid.length;
  console.log(`Normalized: ${valid.length} valid, ${skipped} skipped (no date)\n`);

  // 3. Upsert to Supabase
  const { inserted, errors } = await upsertToSupabase(valid);

  // 4. Summary
  console.log("\n=== Sync Summary ===");
  console.log(`  Fetched:   ${tmEvents.length}`);
  console.log(`  Valid:     ${valid.length}`);
  console.log(`  Upserted:  ${inserted}`);
  console.log(`  Skipped:   ${skipped}`);
  console.log(`  Errors:    ${errors}`);

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
  console.log("\n  Top subcategories:");
  Object.entries(subs)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 15)
    .forEach(([sub, count]) => {
      console.log(`    ${sub}: ${count}`);
    });
}

main().catch((err) => {
  console.error("Fatal error:", err);
  process.exit(1);
});
