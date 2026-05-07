/**
 * sync_municipal_feeds.js — Sync events from municipal / city-government feeds
 *
 * Reads enabled feed configs from the `municipal_feeds` Supabase table. Each
 * row specifies a feed type (drupal_jsonapi, localist, ical, wp_tribe) and URL.
 * The script fetches, parses, normalizes to the events table schema, and
 * upserts to Supabase. Result of each feed (event count, ok/error) is written
 * back to the row so the discovery script can prune persistent failures.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/sync_municipal_feeds.js
 *
 * Adding feeds is now done by inserting rows into municipal_feeds (manually,
 * or automatically via discover_municipal_feeds.js).
 */

const crypto = require("crypto");
const { getSecrets, SUPABASE_URL } = require("./lib/secrets");
const { fetchHtml } = require("./lib/scrapfly_fetch");
const { extractEventsFromHtml } = require("./lib/claude_html_extract");

let SCRAPFLY_API_KEY = null;
let ANTHROPIC_API_KEY = null;

const SUPABASE_SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").trim();
if (!SUPABASE_SERVICE_KEY) {
  console.error("Missing SUPABASE_SERVICE_KEY env var");
  process.exit(1);
}

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

// Metro lat/lng cache — populated from metros table at startup. Lets us
// default an event's venue coords to the metro center when the parser
// can't extract them (most municipal sources don't expose venue geo).
// Without this, events fail the carousel edge function's radius filter
// `(distance_miles ?? 999) <= maxRadius` and get silently dropped.
const METRO_COORDS = new Map(); // metro slug → {lat, lng}

function buildEvent(feed, raw) {
  // raw is { title, description, startAt, endAt, sourceId, sourceUrl,
  //          venueName, venueAddress, venueCity, venueState, isFree, imageUrl,
  //          venueLat, venueLng }
  // Fall back to the feed's metro center for venue coords. Eventually we
  // should geocode venue_address through Google for true accuracy, but
  // metro-center is enough to land the event in the right radius bucket.
  const metro = METRO_COORDS.get(feed.metro);
  return {
    title: raw.title,
    description: raw.description || null,
    category: detectCategory(raw.title, raw.description || ""),
    subcategory: detectCategory(raw.title, raw.description || "") === "community" ? "civic" : null,
    start_at: raw.startAt,
    end_at: raw.endAt || null,
    is_free: raw.isFree ?? true,
    price_min: null,
    price_max: null,
    ticket_url: raw.sourceUrl || null,
    image_url: raw.imageUrl || null,
    status: "active",
    venue_name: raw.venueName || null,
    venue_address: raw.venueAddress || null,
    venue_city: raw.venueCity || feed.city,
    venue_state: raw.venueState || feed.state,
    venue_lat: raw.venueLat ?? metro?.lat ?? null,
    venue_lng: raw.venueLng ?? metro?.lng ?? null,
    source: `municipal_${feed.metro}`,
    source_id: raw.sourceId,
    source_url: raw.sourceUrl || null,
    confidence_score: 0.9,
    needs_review: false,
    dedupe_hash: hashEvent(`municipal_${feed.metro}_${feed.city}`, raw.sourceId),
    city: feed.metro,
    last_synced_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  };
}

// ── Parsers ──

async function fetchDrupalJsonApi(feed) {
  const events = [];
  let url = `${feed.url}?page[limit]=50&sort=-created`;
  let pages = 0;
  const maxPages = 10;

  while (url && pages < maxPages) {
    pages++;
    const res = await fetch(url, { headers: { Accept: "application/vnd.api+json" } });
    if (!res.ok) throw new Error(`Drupal API ${res.status}`);
    const data = await res.json();
    for (const item of data.data || []) {
      const a = item.attributes || {};
      if (!a.title) continue;
      const dr = a.field_event_date_recur || {};
      const startAt = dr.value || null;
      if (!startAt || new Date(startAt) < new Date()) continue;
      const addr = a.field_address || {};
      const detailsLink = a.field_details_link?.uri || null;
      const eventPath = a.path?.alias ? `https://${new URL(feed.url).host}${a.path.alias}` : null;
      events.push(buildEvent(feed, {
        title: a.title,
        description: stripHtml(a.body?.value || "").slice(0, 2000),
        startAt,
        endAt: dr.end_value || null,
        sourceId: String(a.drupal_internal__nid || item.id),
        sourceUrl: eventPath || detailsLink,
        venueName: addr.organization || null,
        venueAddress: [addr.address_line1, addr.address_line2].filter(Boolean).join(", ") || null,
        venueCity: addr.locality || null,
        venueState: addr.administrative_area || null,
        isFree: !a.field_cost || /free|no cost|\$0/i.test(String(a.field_cost)),
      }));
    }
    url = data.links?.next?.href || null;
  }
  return events;
}

async function fetchLocalist(feed) {
  const events = [];
  let page = 1;
  const maxPages = 10;
  while (page <= maxPages) {
    const url = `${feed.url}?days=180&pp=50&page=${page}`;
    const res = await fetch(url);
    if (!res.ok) throw new Error(`Localist ${res.status}`);
    const data = await res.json();
    const items = data.events || [];
    if (items.length === 0) break;
    for (const wrap of items) {
      const ev = wrap.event || wrap;
      const inst = (ev.event_instances || [])[0]?.event_instance;
      const startAt = inst?.start || ev.first_date;
      if (!startAt || new Date(startAt) < new Date()) continue;
      events.push(buildEvent(feed, {
        title: ev.title,
        description: stripHtml(ev.description_text || ev.description || "").slice(0, 2000),
        startAt,
        endAt: inst?.end || null,
        sourceId: String(ev.id),
        sourceUrl: ev.url || ev.localist_url || null,
        venueName: ev.location_name || ev.venue_name || null,
        venueAddress: ev.address || null,
        venueCity: ev.city || null,
        venueState: ev.state || null,
        venueLat: ev.geo?.latitude ? Number(ev.geo.latitude) : null,
        venueLng: ev.geo?.longitude ? Number(ev.geo.longitude) : null,
        isFree: !ev.ticket_cost || /free/i.test(ev.ticket_cost),
        imageUrl: ev.photo_url || null,
      }));
    }
    page++;
  }
  return events;
}

async function fetchIcal(feed) {
  const res = await fetch(feed.url, { headers: { Accept: "text/calendar" } });
  if (!res.ok) throw new Error(`iCal ${res.status}`);
  const text = await res.text();
  return parseIcal(text, feed);
}

function parseIcal(text, feed) {
  // Minimal iCal parser. Handles VEVENT blocks, unfolds line continuations,
  // ignores recurring expansions (we take the DTSTART as-is).
  const unfolded = text.replace(/\r?\n[ \t]/g, "");
  const events = [];
  const blocks = unfolded.split(/BEGIN:VEVENT/).slice(1);
  for (const block of blocks) {
    const end = block.indexOf("END:VEVENT");
    const body = end >= 0 ? block.slice(0, end) : block;
    const lines = body.split(/\r?\n/);
    const map = {};
    for (const line of lines) {
      const colon = line.indexOf(":");
      if (colon < 0) continue;
      const keyPart = line.slice(0, colon);
      const value = line.slice(colon + 1).trim();
      const key = keyPart.split(";")[0].toUpperCase();
      if (!map[key]) map[key] = value;
    }
    if (!map.SUMMARY) continue;
    const startAt = parseIcalDate(map.DTSTART);
    if (!startAt || new Date(startAt) < new Date()) continue;
    events.push(buildEvent(feed, {
      title: map.SUMMARY.replace(/\\,/g, ",").replace(/\\n/g, " "),
      description: (map.DESCRIPTION || "").replace(/\\,/g, ",").replace(/\\n/g, " ").slice(0, 2000),
      startAt,
      endAt: parseIcalDate(map.DTEND),
      sourceId: map.UID || crypto.createHash("md5").update(map.SUMMARY + startAt).digest("hex"),
      sourceUrl: map.URL || null,
      venueName: map.LOCATION ? map.LOCATION.split(",")[0] : null,
      venueAddress: map.LOCATION || null,
      isFree: true,
    }));
  }
  return events;
}

function parseIcalDate(raw) {
  if (!raw) return null;
  // Strip timezone params; raw is YYYYMMDD or YYYYMMDDTHHMMSS(Z)
  const s = raw.replace(/[^0-9TZ]/g, "");
  if (s.length === 8) return `${s.slice(0,4)}-${s.slice(4,6)}-${s.slice(6,8)}T00:00:00`;
  if (s.length >= 15) {
    const date = `${s.slice(0,4)}-${s.slice(4,6)}-${s.slice(6,8)}`;
    const time = `${s.slice(9,11)}:${s.slice(11,13)}:${s.slice(13,15)}`;
    return `${date}T${time}${s.endsWith("Z") ? "Z" : ""}`;
  }
  return null;
}

async function fetchWpTribe(feed) {
  const events = [];
  let page = 1;
  const maxPages = 10;
  while (page <= maxPages) {
    const url = `${feed.url}?per_page=50&page=${page}&start_date=${new Date().toISOString().slice(0,10)}`;
    const res = await fetch(url);
    if (!res.ok) {
      if (res.status === 400 || res.status === 404) break; // pagination over
      throw new Error(`WP Tribe ${res.status}`);
    }
    const data = await res.json();
    const items = data.events || [];
    if (items.length === 0) break;
    for (const ev of items) {
      const startAt = ev.utc_start_date || ev.start_date;
      if (!startAt || new Date(startAt) < new Date()) continue;
      events.push(buildEvent(feed, {
        title: ev.title,
        description: stripHtml(ev.description || "").slice(0, 2000),
        startAt,
        endAt: ev.utc_end_date || ev.end_date || null,
        sourceId: String(ev.id),
        sourceUrl: ev.url || null,
        venueName: ev.venue?.venue || null,
        venueAddress: ev.venue?.address || null,
        venueCity: ev.venue?.city || null,
        venueState: ev.venue?.state || ev.venue?.region || null,
        isFree: !ev.cost || /free/i.test(ev.cost),
        imageUrl: ev.image?.url || null,
      }));
    }
    page++;
  }
  return events;
}

async function fetchRss(feed) {
  const res = await fetch(feed.url, { headers: { Accept: "application/rss+xml, application/atom+xml" } });
  if (!res.ok) throw new Error(`RSS ${res.status}`);
  const text = await res.text();
  return parseRss(text, feed);
}

function parseRss(text, feed) {
  // Lightweight RSS/Atom parser. Captures <item> or <entry> blocks and pulls
  // title, description, link, pubDate. Date parsing is permissive: tries
  // pubDate, dc:date, published in that order.
  const events = [];
  const isAtom = /<feed[\s>]/i.test(text);
  const blockRe = isAtom ? /<entry\b[^>]*>([\s\S]*?)<\/entry>/gi : /<item\b[^>]*>([\s\S]*?)<\/item>/gi;
  for (const m of text.matchAll(blockRe)) {
    const body = m[1];
    const get = tag => {
      const re = new RegExp(`<${tag}\\b[^>]*>([\\s\\S]*?)<\\/${tag}>`, "i");
      const r = re.exec(body);
      return r ? r[1].replace(/<!\[CDATA\[/g, "").replace(/\]\]>/g, "").trim() : null;
    };
    const title = stripHtml(get("title") || "");
    if (!title) continue;
    const dateRaw = get("pubDate") || get("dc:date") || get("published") || get("updated") || get("dc\\:date");
    const startAt = dateRaw ? new Date(dateRaw).toISOString() : null;
    if (!startAt || new Date(startAt) < new Date()) continue;
    const link = isAtom
      ? (/<link\b[^>]*?href\s*=\s*["']([^"']+)["'][^>]*?\/?>/i.exec(body)?.[1] || null)
      : get("link");
    const guid = get("guid") || get("id") || link || title;
    const description = stripHtml(get("description") || get("summary") || get("content") || "").slice(0, 2000);
    events.push(buildEvent(feed, {
      title,
      description,
      startAt,
      sourceId: guid,
      sourceUrl: link,
      isFree: true,
    }));
  }
  return events;
}

// claude_html — Scrapfly fetches the calendar page (with JS rendering for
// CivicPlus/Granicus/SPA), then Claude extracts events as structured JSON.
// The extracted records use the same shape as the other parsers via buildEvent.
async function fetchClaudeHtml(feed) {
  if (!SCRAPFLY_API_KEY || !ANTHROPIC_API_KEY) {
    throw new Error("claude_html requires SCRAPFLY_API_KEY and ANTHROPIC_API_KEY");
  }
  const { html } = await fetchHtml(feed.url, {
    scrapflyKey: SCRAPFLY_API_KEY,
    renderJs: true,
    timeoutMs: 45000,
  });
  const extracted = await extractEventsFromHtml({
    html,
    sourceUrl: feed.url,
    anthropicKey: ANTHROPIC_API_KEY,
    city: feed.city,
    state: feed.state,
  });
  // Stable per-event hash so weekly re-extraction upserts instead of dup-ing.
  // Title + start_at + venue is the strongest natural key for these.
  const events = [];
  for (const e of extracted) {
    if (!e?.title || !e?.start_at) continue;
    if (new Date(e.start_at) < new Date()) continue;
    const sourceId = crypto
      .createHash("sha1")
      .update(`${e.title}|${e.start_at}|${e.venue_name || feed.city}`)
      .digest("hex")
      .slice(0, 24);
    events.push(buildEvent(feed, {
      title: e.title,
      description: e.description || null,
      startAt: e.start_at,
      endAt: e.end_at || null,
      sourceId,
      sourceUrl: e.source_url || feed.url,
      venueName: e.venue_name || null,
      venueAddress: e.venue_address || null,
      venueCity: feed.city,
      venueState: feed.state,
      isFree: e.is_free ?? null,
    }));
  }
  return events;
}

const PARSERS = {
  drupal_jsonapi: fetchDrupalJsonApi,
  localist: fetchLocalist,
  ical: fetchIcal,
  wp_tribe: fetchWpTribe,
  rss: fetchRss,
  claude_html: fetchClaudeHtml,
};

// ── Supabase I/O ──

async function fetchEnabledFeeds() {
  const url = `${SUPABASE_URL}/rest/v1/municipal_feeds?enabled=eq.true&select=*`;
  const res = await fetch(url, {
    headers: { apikey: SUPABASE_SERVICE_KEY, Authorization: `Bearer ${SUPABASE_SERVICE_KEY}` },
  });
  if (!res.ok) throw new Error(`Failed to load feeds: ${res.status}`);
  return res.json();
}

async function recordSyncResult(feedId, status, eventCount) {
  const url = `${SUPABASE_URL}/rest/v1/municipal_feeds?id=eq.${feedId}`;
  const isOk = status === "ok";
  const body = isOk
    ? { last_synced_at: new Date().toISOString(), last_event_count: eventCount, last_status: "ok", consecutive_failures: 0 }
    : { last_synced_at: new Date().toISOString(), last_status: status };
  // For failures, we also bump consecutive_failures via separate fetch since
  // PostgREST doesn't support arithmetic-on-update. Use a simple read-modify-write.
  if (!isOk) {
    const cur = await fetch(`${SUPABASE_URL}/rest/v1/municipal_feeds?id=eq.${feedId}&select=consecutive_failures`, {
      headers: { apikey: SUPABASE_SERVICE_KEY, Authorization: `Bearer ${SUPABASE_SERVICE_KEY}` },
    }).then(r => r.json());
    body.consecutive_failures = (cur[0]?.consecutive_failures || 0) + 1;
  }
  await fetch(url, {
    method: "PATCH",
    headers: {
      apikey: SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
}

async function upsertToSupabase(rows) {
  const url = `${SUPABASE_URL}/rest/v1/events?on_conflict=dedupe_hash`;
  const batchSize = 50;
  let inserted = 0, errors = 0;
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
      console.error(`  Upsert batch ${Math.floor(i / batchSize)}: ${res.status} ${body.slice(0, 200)}`);
      errors += batch.length;
    } else inserted += batch.length;
  }
  return { inserted, errors };
}

// ── Main ──

async function main() {
  // Pull Scrapfly + Claude keys for the claude_html parser. Both are optional
  // — if a feed of type claude_html exists but keys are missing, it'll error
  // for that one feed and continue with the rest.
  try {
    const secrets = await getSecrets(["SCRAPFLY_API_KEY", "ANTHROPIC_API_KEY"]);
    SCRAPFLY_API_KEY = secrets.SCRAPFLY_API_KEY || null;
    ANTHROPIC_API_KEY = secrets.ANTHROPIC_API_KEY || null;
    console.log(
      `Scrapfly: ${SCRAPFLY_API_KEY ? "available" : "not configured"}, ` +
      `Claude: ${ANTHROPIC_API_KEY ? "available" : "not configured"}`
    );
  } catch (e) { console.warn(`Secret fetch failed: ${e.message}`); }

  // Load metro centers so events without parser-extracted venue coords
  // can default to their feed's metro lat/lng. Required for them to pass
  // the carousel edge function's radius filter.
  const metrosRes = await fetch(
    `${SUPABASE_URL}/rest/v1/metros?is_active=eq.true&select=name,lat,lng`,
    { headers: { apikey: SUPABASE_SERVICE_KEY, Authorization: `Bearer ${SUPABASE_SERVICE_KEY}` } },
  );
  if (metrosRes.ok) {
    const metros = await metrosRes.json();
    for (const m of metros) {
      // metro slug = lowercased name (matches `feed.metro` set by discover script)
      METRO_COORDS.set((m.name || "").toLowerCase(), { lat: m.lat, lng: m.lng });
    }
    console.log(`Loaded ${METRO_COORDS.size} metro centers`);
  } else {
    console.warn(`Metro fetch failed: ${metrosRes.status} — events without coords will be dropped from carousels`);
  }

  const feeds = await fetchEnabledFeeds();
  console.log(`Municipal Feed Sync — ${feeds.length} enabled feed(s)\n`);

  let totalFetched = 0, totalUpserted = 0, totalErrors = 0;

  for (const feed of feeds) {
    console.log(`━━━ ${feed.name} (${feed.type}, ${feed.city}, ${feed.state}) ━━━`);
    const parser = PARSERS[feed.type];
    if (!parser) {
      console.error(`  Unknown feed type: ${feed.type}. Skipping.`);
      await recordSyncResult(feed.id, `error: unknown type ${feed.type}`, 0);
      continue;
    }
    try {
      const events = await parser(feed);
      console.log(`  Fetched ${events.length} upcoming events`);
      totalFetched += events.length;
      if (events.length === 0) {
        await recordSyncResult(feed.id, "ok", 0);
        console.log("  Nothing to upsert.\n");
        continue;
      }
      const { inserted, errors } = await upsertToSupabase(events);
      console.log(`  Upserted: ${inserted}, Errors: ${errors}\n`);
      totalUpserted += inserted;
      totalErrors += errors;
      await recordSyncResult(feed.id, errors > 0 ? `error: ${errors} upsert failures` : "ok", events.length);
    } catch (err) {
      console.error(`  Failed: ${err.message}\n`);
      totalErrors++;
      await recordSyncResult(feed.id, `error: ${err.message.slice(0, 200)}`, 0);
    }
  }

  console.log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
  console.log(`Total:  ${totalFetched} fetched, ${totalUpserted} upserted, ${totalErrors} errors`);
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
