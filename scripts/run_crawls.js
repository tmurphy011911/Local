/**
 * run_crawls.js — Venue-driven event crawling pipeline
 *
 * Reads active venues from the venues table, fetches each venue's events page,
 * uses Claude to extract structured events, upserts to Supabase.
 *
 * Usage:
 *   ANTHROPIC_API_KEY=sk-... SUPABASE_SERVICE_KEY=ey... node scripts/run_crawls.js
 *
 * Optional:
 *   METRO=boston                    — metro area to crawl (default: boston)
 *   CATEGORY=museum,theater        — only crawl specific venue categories
 *   CONCURRENCY=3                  — parallel Claude calls (default: 3)
 *   MAX_VENUES=0                   — limit venues to crawl (0 = all)
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

const MODEL = "claude-sonnet-4-20250514";
const MAX_HTML_CHARS = 50_000;

let ANTHROPIC_API_KEY, SUPABASE_SERVICE_KEY;
const METRO = process.env.METRO || "boston";
const CATEGORY_FILTER = process.env.CATEGORY
  ? process.env.CATEGORY.split(",").map((s) => s.trim())
  : null;
const CONCURRENCY = parseInt(process.env.CONCURRENCY || "3", 10);
const MAX_VENUES = parseInt(process.env.MAX_VENUES || "0", 10);
const CITY = "boston";

// ── Category mapping from venue category to event category ──

const VENUE_TO_EVENT_CATEGORY = {
  music_venue: "music",
  theater: "arts",
  museum: "arts",
  library: "arts",
  comedy_club: "comedy",
  sports_venue: "sports",
  community_center: "other",
  college: "other",
  bar_events: "nightlife",
  outdoor_amphitheater: "outdoors",
  arts_center: "arts",
  bar: "nightlife",
  brewery: "food",
  winery: "food",
  restaurant: "food",
  cafe: "food",
  bakery: "food",
  stadium: "sports",
  park: "outdoors",
  public_space: "other",
  tour: "other",
};

const VENUE_TO_SUBCATEGORY = {
  museum: "museum",
  library: "library",
  theater: "theatre",
  comedy_club: "comedy",
  sports_venue: "sports",
  outdoor_amphitheater: "outdoor",
  college: "college",
  bar: "bar",
  brewery: "brewery",
  winery: "winery",
  restaurant: "restaurant",
  cafe: "cafe",
  bakery: "bakery",
  stadium: "stadium",
  park: "park",
  public_space: "community",
  tour: "tour",
};

// ── Fetch venues from Supabase ──

async function fetchVenues() {
  let baseUrl = `${SUPABASE_URL}/rest/v1/venues?is_active=eq.true&select=*`;
  if (CATEGORY_FILTER) {
    baseUrl += `&category=in.(${CATEGORY_FILTER.join(",")})`;
  }
  baseUrl += "&order=events_url.desc.nullslast,crawl_success_rate.desc";

  if (MAX_VENUES > 0) {
    baseUrl += `&limit=${MAX_VENUES}`;
    const res = await fetch(baseUrl, {
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      },
    });
    if (!res.ok) {
      const body = await res.text();
      throw new Error(`Failed to fetch venues: ${res.status} ${body}`);
    }
    return res.json();
  }

  const allVenues = [];
  let offset = 0;
  const limit = 1000;

  while (true) {
    const res = await fetch(`${baseUrl}&offset=${offset}&limit=${limit}`, {
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      },
    });
    if (!res.ok) {
      const body = await res.text();
      throw new Error(`Failed to fetch venues: ${res.status} ${body}`);
    }
    const venues = await res.json();
    allVenues.push(...venues);
    if (venues.length < limit) break;
    offset += limit;
  }

  return allVenues;
}

// ── HTML fetching and cleaning ──

const FETCH_HEADERS = {
  "User-Agent":
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
  Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
  "Accept-Language": "en-US,en;q=0.5",
};

async function fetchPage(url) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 30_000);
  try {
    const res = await fetch(url, {
      headers: FETCH_HEADERS,
      signal: controller.signal,
      redirect: "follow",
    });
    clearTimeout(timeout);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.text();
  } catch (err) {
    clearTimeout(timeout);
    throw err;
  }
}

function stripHtml(html) {
  let cleaned = html
    .replace(/<script[\s\S]*?<\/script>/gi, "")
    .replace(/<style[\s\S]*?<\/style>/gi, "")
    .replace(/<svg[\s\S]*?<\/svg>/gi, "")
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/<header[\s\S]*?<\/header>/gi, "")
    .replace(/<footer[\s\S]*?<\/footer>/gi, "")
    .replace(/<nav[\s\S]*?<\/nav>/gi, "");

  cleaned = cleaned
    .replace(/<(a|link)[^>]*href="([^"]*)"[^>]*>/gi, "[$2] ")
    .replace(/<img[^>]*alt="([^"]*)"[^>]*>/gi, "(image: $1) ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&#\d+;/g, " ")
    .replace(/&[a-z]+;/gi, " ")
    .replace(/\s{2,}/g, " ")
    .trim();

  if (cleaned.length > MAX_HTML_CHARS) {
    cleaned = cleaned.slice(0, MAX_HTML_CHARS) + "\n\n[TRUNCATED]";
  }
  return cleaned;
}

// ── Claude extraction ──

const TODAY = new Date().toISOString().slice(0, 10);
const YEAR = new Date().getFullYear();

const SYSTEM_PROMPT = `You are an event data extraction assistant. You receive raw text scraped from a venue's events page. Extract EVERY individual event you can find and return them as structured JSON.

Rules:
- Extract ALL events — do not skip any
- Dates in ISO 8601 (YYYY-MM-DD or YYYY-MM-DDTHH:MM:SS)
- If year is missing, assume ${YEAR} (or ${YEAR + 1} if month already passed)
- Today is ${TODAY}
- For exhibitions/runs, set both start_date and end_date
- Set confidence 0.0-1.0 based on clarity of extracted data
- Return ONLY valid JSON, no other text`;

async function extractEventsFromHtml(venue, html) {
  const userPrompt = `Extract all upcoming events from this venue's events page.

Venue: ${venue.name}
City: ${venue.city}
Category: ${venue.category}
Page URL: ${venue.events_url}

Return a JSON object with an "events" array. Each event:
- title (string, required)
- description (string — brief)
- start_date (ISO 8601, required)
- end_date (ISO 8601 or null)
- is_free (boolean)
- price_min (number or null)
- price_max (number or null)
- ticket_url (string or null)
- source_url (string or null — link to this specific event)
- confidence (number 0.0-1.0)

Scraped content:

${html}`;

  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "x-api-key": ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
      "content-type": "application/json",
    },
    body: JSON.stringify({
      model: MODEL,
      max_tokens: 8192,
      system: SYSTEM_PROMPT,
      messages: [{ role: "user", content: userPrompt }],
    }),
  });

  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Claude API ${res.status}: ${body.slice(0, 200)}`);
  }

  const data = await res.json();
  const text = data.content?.[0]?.text || "";
  const jsonMatch = text.match(/\{[\s\S]*\}/);
  if (!jsonMatch) throw new Error("No JSON in Claude response");
  try {
    return JSON.parse(jsonMatch[0]);
  } catch (parseErr) {
    // Try to salvage truncated JSON by finding the last complete event object
    const partial = jsonMatch[0];
    const lastBracket = partial.lastIndexOf("}]");
    if (lastBracket > 0) {
      const truncated = partial.slice(0, lastBracket + 2) + "}";
      return JSON.parse(truncated);
    }
    throw new Error(`JSON parse failed: ${parseErr.message}`);
  }
}

// ── Normalize event rows ──

function normalizeEvent(event, venue) {
  const now = new Date().toISOString();
  const sourceId = `crawl-${METRO}-${Buffer.from(event.title + event.start_date + venue.name).toString("base64url").slice(0, 40)}`;

  return {
    title: event.title,
    description: event.description || null,
    category: VENUE_TO_EVENT_CATEGORY[venue.category] || "other",
    subcategory: VENUE_TO_SUBCATEGORY[venue.category] || null,
    start_at: event.start_date || null,
    end_at: event.end_date || null,
    is_free: event.is_free || false,
    price_min: event.price_min ?? null,
    price_max: event.price_max ?? null,
    ticket_url: event.ticket_url || null,
    image_url: null,
    status: "active",
    venue_name: venue.name,
    venue_address: null,
    venue_city: venue.city,
    venue_state: venue.state || "MA",
    venue_lat: venue.lat ? parseFloat(venue.lat) : null,
    venue_lng: venue.lng ? parseFloat(venue.lng) : null,
    source: "crawl",
    source_id: sourceId,
    source_url: event.source_url || venue.events_url || null,
    confidence_score: event.confidence ?? 0.5,
    needs_review: (event.confidence ?? 0.5) < 0.7,
    dedupe_hash: `crawl:${sourceId}`,
    city: CITY,
    last_synced_at: now,
    updated_at: now,
  };
}

// ── Update venue crawl metadata ──

async function updateVenueCrawlStatus(venueId, success, currentRate) {
  const newRate = success
    ? Math.min(1.0, currentRate * 0.8 + 0.2)
    : Math.max(0.0, currentRate * 0.8);

  const url = `${SUPABASE_URL}/rest/v1/venues?id=eq.${venueId}`;
  await fetch(url, {
    method: "PATCH",
    headers: {
      apikey: SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      last_crawled_at: new Date().toISOString(),
      crawl_success_rate: parseFloat(newRate.toFixed(2)),
    }),
  });
}

// ── Upsert events to Supabase ──

async function upsertEvents(rows) {
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
      console.error(`  Supabase upsert error (batch ${Math.floor(i / batchSize)}): ${res.status} ${body}`);
      errors += batch.length;
    } else {
      inserted += batch.length;
    }
  }

  return { inserted, errors };
}

// ── Crawl a single venue ──

async function crawlVenue(venue) {
  if (!venue.events_url) {
    return { venue: venue.name, events: 0, status: "skipped", reason: "no events_url" };
  }

  try {
    // Fetch HTML
    const rawHtml = await fetchPage(venue.events_url);
    const cleanedHtml = stripHtml(rawHtml);

    if (cleanedHtml.length < 100) {
      await updateVenueCrawlStatus(venue.id, false, venue.crawl_success_rate);
      return { venue: venue.name, events: 0, status: "empty", reason: "page too short" };
    }

    // Extract events via Claude
    const result = await extractEventsFromHtml(venue, cleanedHtml);
    const events = (result.events || []).filter((e) => e.title && e.start_date);
    const rows = events.map((e) => normalizeEvent(e, venue));

    await updateVenueCrawlStatus(venue.id, true, venue.crawl_success_rate);

    return { venue: venue.name, events: rows.length, status: "ok", rows };
  } catch (err) {
    await updateVenueCrawlStatus(venue.id, false, venue.crawl_success_rate);
    return { venue: venue.name, events: 0, status: "error", reason: err.message };
  }
}

// ── Process venues with concurrency control ──

async function crawlWithConcurrency(venues, concurrency) {
  const results = [];
  let index = 0;

  async function worker() {
    while (index < venues.length) {
      const i = index++;
      const venue = venues[i];
      console.log(`  [${i + 1}/${venues.length}] ${venue.name} (${venue.category})...`);
      const result = await crawlVenue(venue);

      if (result.status === "ok") {
        console.log(`    -> ${result.events} events extracted`);
      } else {
        console.log(`    -> ${result.status}: ${result.reason || ""}`);
      }

      results.push(result);

      // Brief pause to respect rate limits
      await new Promise((r) => setTimeout(r, 1000));
    }
  }

  const workers = Array.from({ length: Math.min(concurrency, venues.length) }, () => worker());
  await Promise.all(workers);

  return results;
}

// ── Main ──

async function main() {
  const secrets = await getSecrets(["ANTHROPIC_API_KEY"]);
  ANTHROPIC_API_KEY = secrets.ANTHROPIC_API_KEY;
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  console.log("=== Venue-Driven Event Crawls ===\n");
  console.log(`Metro: ${METRO}`);
  console.log(`Model: ${MODEL}`);
  console.log(`Concurrency: ${CONCURRENCY}`);
  console.log(`Date: ${TODAY}\n`);

  // 1. Fetch venues
  console.log("Fetching venues from Supabase...");
  const venues = await fetchVenues();
  console.log(`  Found ${venues.length} active venues`);

  const withUrl = venues.filter((v) => v.events_url);
  const withoutUrl = venues.length - withUrl.length;
  console.log(`  With events_url: ${withUrl.length}, Without: ${withoutUrl}\n`);

  if (withUrl.length === 0) {
    console.log("No venues with events_url. Run discover_venues.js first.");
    return;
  }

  // 2. Crawl venues
  console.log("Crawling venues...\n");
  const results = await crawlWithConcurrency(withUrl, CONCURRENCY);

  // 3. Collect all event rows
  const allRows = results.flatMap((r) => r.rows || []);

  if (allRows.length === 0) {
    console.log("\nNo events extracted. Check venue URLs and try again.");
    return;
  }

  // 3b. Save to disk so we can re-upsert without re-crawling
  const fs = require("fs");
  const outPath = `${__dirname}/crawl_results.json`;
  fs.writeFileSync(outPath, JSON.stringify(allRows, null, 2));
  console.log(`\nSaved ${allRows.length} events to ${outPath}`);

  // 4. Upsert to Supabase
  console.log(`\nUpserting ${allRows.length} events to Supabase...`);
  const { inserted, errors } = await upsertEvents(allRows);

  // 5. Summary
  console.log("\n=== Crawl Summary ===");
  console.log(`  Venues crawled: ${results.filter((r) => r.status === "ok").length}/${withUrl.length}`);
  console.log(`  Skipped (no URL): ${withoutUrl}`);
  console.log(`  Errors: ${results.filter((r) => r.status === "error").length}`);
  console.log(`  Empty pages: ${results.filter((r) => r.status === "empty").length}`);
  console.log(`\n  Total events extracted: ${allRows.length}`);
  console.log(`  Upserted: ${inserted}`);
  console.log(`  Upsert errors: ${errors}`);

  // Per-venue breakdown
  console.log("\n  Events per venue:");
  results
    .filter((r) => r.events > 0)
    .sort((a, b) => b.events - a.events)
    .forEach((r) => {
      console.log(`    ${r.venue}: ${r.events}`);
    });

  // Failed venues
  const failed = results.filter((r) => r.status === "error");
  if (failed.length > 0) {
    console.log("\n  Failed venues:");
    failed.forEach((r) => {
      console.log(`    ${r.venue}: ${r.reason}`);
    });
  }

  // Category breakdown
  const cats = {};
  allRows.forEach((r) => {
    cats[r.category] = (cats[r.category] || 0) + 1;
  });
  console.log("\n  Events by category:");
  Object.entries(cats)
    .sort((a, b) => b[1] - a[1])
    .forEach(([cat, count]) => {
      console.log(`    ${cat}: ${count}`);
    });
}

main().catch((err) => {
  console.error("Fatal error:", err);
  process.exit(1);
});
