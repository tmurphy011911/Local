/**
 * sync_municipal_feeds.js — Sync events from municipal / city-government feeds
 *
 * Reads feed configs from municipal_feeds.json. Each entry specifies a feed
 * type (drupal_jsonapi, ical, rss, etc.) and URL. The script fetches, parses,
 * normalizes to the events table schema, and upserts to Supabase.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/sync_municipal_feeds.js
 *
 * To add a new feed: edit municipal_feeds.json and add an entry. The script
 * handles the rest.
 */

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { SUPABASE_URL } = require("./lib/secrets");

const SUPABASE_SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").trim();
if (!SUPABASE_SERVICE_KEY) {
  console.error("Missing SUPABASE_SERVICE_KEY env var");
  process.exit(1);
}

// ── Feed config ──

const FEEDS_PATH = path.join(__dirname, "municipal_feeds.json");
const feeds = JSON.parse(fs.readFileSync(FEEDS_PATH, "utf-8")).filter(
  (f) => f.enabled !== false
);

// ── Helpers ──

function hashEvent(source, sourceId) {
  return `${source}:${sourceId}`;
}

function stripHtml(html) {
  if (!html) return "";
  return html
    .replace(/<[^>]+>/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&nbsp;/g, " ")
    .replace(/\s+/g, " ")
    .trim();
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

// ── Parsers by feed type ──

async function fetchDrupalJsonApi(feed) {
  const events = [];
  let url = `${feed.url}?page[limit]=50&sort=-created`;
  let pages = 0;
  const maxPages = 10;

  while (url && pages < maxPages) {
    pages++;
    console.log(`  Page ${pages}...`);
    const res = await fetch(url, {
      headers: { Accept: "application/vnd.api+json" },
    });
    if (!res.ok) {
      console.error(`  API error: ${res.status}`);
      break;
    }
    const data = await res.json();
    const items = data.data || [];

    for (const item of items) {
      const attrs = item.attributes || {};
      if (!attrs.title) continue;

      const dateRecur = attrs.field_event_date_recur || {};
      const startAt = dateRecur.value || null;
      const endAt = dateRecur.end_value || null;

      if (!startAt) continue;
      // Skip past events
      if (new Date(startAt) < new Date()) continue;

      const addr = attrs.field_address || {};
      const title = attrs.title;
      const bodyHtml = attrs.body?.value || "";
      const description = stripHtml(bodyHtml).slice(0, 2000);
      const category = detectCategory(title, description);

      const costRaw = attrs.field_cost;
      const isFree =
        !costRaw ||
        costRaw === "0" ||
        /free|no cost|\$0/i.test(String(costRaw));

      const sourceId = String(
        attrs.drupal_internal__nid || item.id
      );

      const detailsLink = attrs.field_details_link?.uri || null;
      const eventPath = attrs.path?.alias
        ? `https://${new URL(feed.url).host}${attrs.path.alias}`
        : null;

      events.push({
        title,
        description: description || null,
        category,
        subcategory: category === "community" ? "civic" : category,
        start_at: startAt,
        end_at: endAt,
        is_free: isFree,
        price_min: null,
        price_max: null,
        ticket_url: detailsLink || eventPath,
        image_url: null,
        status: "active",
        venue_name: addr.organization || null,
        venue_address: [addr.address_line1, addr.address_line2]
          .filter(Boolean)
          .join(", ") || null,
        venue_city: addr.locality || feed.city,
        venue_state: addr.administrative_area || feed.state,
        venue_lat: null,
        venue_lng: null,
        source: `municipal_${feed.metro}`,
        source_id: sourceId,
        source_url: eventPath || detailsLink,
        confidence_score: 0.9,
        needs_review: false,
        dedupe_hash: hashEvent(`municipal_${feed.metro}`, sourceId),
        city: feed.metro,
        last_synced_at: new Date().toISOString(),
        updated_at: new Date().toISOString(),
      });
    }

    // Follow pagination
    url = data.links?.next?.href || null;
  }

  return events;
}

// Registry of parsers by feed type. Add new types here.
const PARSERS = {
  drupal_jsonapi: fetchDrupalJsonApi,
  // ical: fetchIcal,       // TODO
  // rss: fetchRss,         // TODO
  // wordpress_tribe: ...   // Already handled by run_crawls.js
};

// ── Upsert ──

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
      console.error(
        `  Supabase upsert error (batch ${Math.floor(i / batchSize)}): ${res.status} ${body.slice(0, 200)}`
      );
      errors += batch.length;
    } else {
      inserted += batch.length;
    }
  }

  return { inserted, errors };
}

// ── Main ──

async function main() {
  console.log(`Municipal Feed Sync — ${feeds.length} feed(s) configured\n`);

  let totalFetched = 0;
  let totalUpserted = 0;
  let totalErrors = 0;

  for (const feed of feeds) {
    console.log(`━━━ ${feed.name} (${feed.type}) ━━━`);
    const parser = PARSERS[feed.type];
    if (!parser) {
      console.error(`  Unknown feed type: ${feed.type}. Skipping.`);
      continue;
    }

    try {
      const events = await parser(feed);
      console.log(`  Fetched ${events.length} upcoming events`);
      totalFetched += events.length;

      if (events.length === 0) {
        console.log("  Nothing to upsert.\n");
        continue;
      }

      const { inserted, errors } = await upsertToSupabase(events);
      console.log(`  Upserted: ${inserted}, Errors: ${errors}\n`);
      totalUpserted += inserted;
      totalErrors += errors;
    } catch (err) {
      console.error(`  Failed: ${err.message}\n`);
      totalErrors++;
    }
  }

  console.log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
  console.log(`Total:  ${totalFetched} fetched, ${totalUpserted} upserted, ${totalErrors} errors`);
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
