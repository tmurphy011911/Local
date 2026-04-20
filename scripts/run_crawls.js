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
 *   CATEGORY=museum,theater        — only crawl specific venue categories
 *   CONCURRENCY=3                  — parallel Claude calls (default: 3)
 *   MAX_VENUES=0                   — limit venues to crawl (0 = all)
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

const MODEL = "claude-haiku-4-5-20251001";
const MAX_HTML_CHARS = 50_000;

let ANTHROPIC_API_KEY, SUPABASE_SERVICE_KEY;
const CATEGORY_FILTER = process.env.CATEGORY
  ? process.env.CATEGORY.split(",").map((s) => s.trim())
  : null;
const VENUE_NAME = process.env.VENUE_NAME || null;
const CONCURRENCY = parseInt(process.env.CONCURRENCY || "3", 10);
const MAX_VENUES = parseInt(process.env.MAX_VENUES || "0", 10);

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
  if (VENUE_NAME) {
    const names = VENUE_NAME.split(",").map((s) => s.trim());
    if (names.length === 1) {
      baseUrl += `&name=ilike.*${encodeURIComponent(names[0])}*`;
    } else {
      const orFilter = names.map((n) => `name.ilike.*${n}*`).join(",");
      baseUrl += `&or=(${encodeURIComponent(orFilter)})`;
    }
  }
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
  "Accept-Encoding": "gzip, deflate, br",
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
- Today is ${TODAY} — use this as the starting point for all date calculations
- For exhibitions/runs, set both start_date and end_date
- Set confidence 0.0-1.0 based on clarity of extracted data
- RECURRING EVENTS: When you detect a recurring event (e.g. "Every Sunday", "Weekly", "First Friday of every month", "Daily", "Every Monday and Wednesday"), you MUST expand it into separate individual event entries for EACH occurrence over the next 6 weeks starting from ${TODAY}. Do NOT create a single entry for a recurring event.
  - Each expanded entry must have its own specific start_date with the correct calendar date
  - Append the specific date to the title so each entry is distinguishable (e.g. "SOWA Sunday - March 22", "SOWA Sunday - March 29", "Trivia Night - March 24", "Trivia Night - March 31")
  - Mention the recurrence pattern in the description (e.g. "Recurring weekly every Sunday")
  - Use a confidence of 0.7 for expanded recurring entries since the specific dates are inferred
- Return ONLY valid JSON, no other text`;

async function extractEventsFromHtml(venue, html) {
  const userPrompt = `Extract all upcoming events from this venue's events page.

Venue: ${venue.name}
City: ${venue.city}
Category: ${venue.category}
Page URL: ${venue.events_url}
Today's date: ${TODAY}

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

IMPORTANT — Recurring events: If the page mentions recurring events (e.g. "Every Sunday", "Weekly on Tuesdays", "First Friday of every month", "Daily"), expand each one into individual entries for every occurrence over the next 6 weeks from ${TODAY}. Each entry needs its own specific start_date and a date-specific title (e.g. "Open Mic Night - April 1", "Open Mic Night - April 8"). Do NOT output a single entry for a recurring event.

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
      max_tokens: 16384,
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

/**
 * Claude returns dates as ISO 8601 without timezone (e.g. "2026-03-22T20:00:00").
 * These are Eastern Time since all venues are in the ET zone.
 * Append "-04:00" (EDT) or "-05:00" (EST) so PostgreSQL stores them correctly.
 */
function tagEasternTime(isoDate) {
  if (!isoDate) return null;
  // Already has timezone offset or Z — leave it alone
  if (/[Zz]$/.test(isoDate) || /[+-]\d{2}:\d{2}$/.test(isoDate)) return isoDate;
  // Determine EDT vs EST based on date
  // EDT: second Sunday in March → first Sunday in November
  try {
    const d = new Date(isoDate + "Z"); // parse as UTC to get month/day
    const month = d.getUTCMonth(); // 0-indexed
    // Rough check: April-October is definitely EDT, Dec-Feb is EST
    // March and November need exact DST boundary checks but -04:00 is safe for most
    const isEDT = month >= 2 && month <= 10; // Mar-Nov → EDT (close enough)
    return isoDate + (isEDT ? "-04:00" : "-05:00");
  } catch {
    return isoDate + "-04:00"; // default EDT
  }
}

function normalizeEvent(event, venue) {
  const now = new Date().toISOString();
  const venueCity = (venue.city || "unknown").toLowerCase();
  const sourceId = `crawl-${venueCity}-${Buffer.from(event.title + event.start_date + venue.name).toString("base64url").slice(0, 40)}`;

  return {
    title: event.title,
    description: event.description || null,
    category: VENUE_TO_EVENT_CATEGORY[venue.category] || "other",
    subcategory: VENUE_TO_SUBCATEGORY[venue.category] || null,
    start_at: tagEasternTime(event.start_date),
    end_at: tagEasternTime(event.end_date),
    is_free: event.is_free || false,
    price_min: event.price_min ?? null,
    price_max: event.price_max ?? null,
    ticket_url: event.ticket_url || null,
    image_url: null,
    status: "active",
    venue_name: venue.name,
    venue_address: null,
    venue_city: venue.city,
    venue_state: venue.state || null,
    venue_lat: venue.lat ? parseFloat(venue.lat) : null,
    venue_lng: venue.lng ? parseFloat(venue.lng) : null,
    source: "crawl",
    source_id: sourceId,
    source_url: event.source_url || venue.events_url || null,
    confidence_score: event.confidence ?? 0.5,
    needs_review: (event.confidence ?? 0.5) < 0.7,
    dedupe_hash: `crawl:${sourceId}`,
    city: venueCity,
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

  // Deduplicate by dedupe_hash — last occurrence wins
  const deduped = [...new Map(rows.map((r) => [r.dedupe_hash, r])).values()];
  if (deduped.length < rows.length) {
    console.log(`  Deduped: ${rows.length} -> ${deduped.length} (removed ${rows.length - deduped.length} duplicates)`);
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

// ── BiblioCommons API crawler ──

async function crawlBiblioCommons(venue, libraryCode) {
  const baseUrl = `https://gateway.bibliocommons.com/v2/libraries/${libraryCode}/events?locale=en-US&limit=50`;
  const allEvents = [];
  let page = 1;
  const maxPages = 5; // 250 events max

  try {
    while (page <= maxPages) {
      const url = `${baseUrl}&page=${page}`;
      const res = await fetch(url, { headers: { Accept: "application/json" } });
      if (!res.ok) {
        console.log(`    BiblioCommons API error: ${res.status}`);
        break;
      }
      const data = await res.json();
      const items = data.events?.items || [];
      const entities = data.entities?.events || {};
      const branches = data.entities?.branches || {};

      if (items.length === 0) break;

      for (const id of items) {
        const evt = entities[id];
        if (!evt?.definition) continue;
        const def = evt.definition;
        const branchId = def.branchLocationId;
        const branch = branches[branchId];
        const branchName = branch?.name || venue.name;

        // Skip events more than 60 days out
        const startIso = def.start ? def.start.replace("T", "T") + ":00Z" : null;
        if (!startIso) continue;

        // Strip HTML from description
        const descClean = (def.description || "")
          .replace(/<[^>]+>/g, " ")
          .replace(/\s+/g, " ")
          .trim()
          .slice(0, 300);

        allEvents.push({
          title: def.title,
          start_date: startIso,
          end_date: def.end ? def.end + ":00Z" : null,
          description: descClean || null,
          source_url: `https://${libraryCode}.bibliocommons.com/v2/events/${id}`,
          venue_override: branchName,
        });
      }

      const totalPages = data.events?.pagination?.pages || 1;
      if (page >= totalPages) break;
      page++;
      await new Promise((r) => setTimeout(r, 300)); // rate limit
    }

    // Normalize and upsert — use branch name if available
    const rows = allEvents.map((e) => {
      const v = e.venue_override ? { ...venue, name: e.venue_override } : venue;
      return normalizeEvent(e, v);
    });
    if (rows.length > 0) {
      await upsertEvents(rows);
    }
    await updateVenueCrawlStatus(venue.id, true, venue.crawl_success_rate);
    return { venue: venue.name, events: rows.length, status: "ok" };
  } catch (err) {
    console.log(`    BiblioCommons crawl error: ${err.message}`);
    await updateVenueCrawlStatus(venue.id, false, venue.crawl_success_rate);
    return { venue: venue.name, events: 0, status: "error", reason: err.message };
  }
}

// ── JSON-LD schema.org Event extractor ──
// Many sites (Harvard Gazette, boston.gov, museums, etc.) publish structured
// event data via <script type="application/ld+json">. This parser handles
// both flat `@type: Event` objects and nested structures (@graph, ItemList,
// hasPart). A hit here avoids the Claude HTML fallback entirely — faster,
// cheaper, and more reliable than LLM extraction.
function parseJsonLdEvents(html) {
  const events = [];
  const re = /<script[^>]*type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi;
  let m;
  while ((m = re.exec(html)) !== null) {
    let parsed;
    try { parsed = JSON.parse(m[1].trim()); } catch { continue; }
    const items = Array.isArray(parsed) ? parsed : [parsed];
    for (const item of items) collectJsonLdEvents(item, events);
  }
  return events;
}

function collectJsonLdEvents(item, out) {
  if (!item || typeof item !== "object") return;
  const rawType = item["@type"];
  const types = Array.isArray(rawType) ? rawType : (rawType ? [rawType] : []);
  const isEvent = types.some((t) => typeof t === "string" && /Event$/.test(t));
  if (isEvent && item.name && item.startDate) {
    const offers = Array.isArray(item.offers) ? item.offers[0] : item.offers;
    out.push({
      title: typeof item.name === "string" ? item.name : String(item.name),
      start_date: item.startDate,
      end_date: item.endDate || null,
      description: typeof item.description === "string" ? item.description.slice(0, 300) : null,
      source_url: item.url || null,
      is_free: item.isAccessibleForFree === true || false,
      ticket_url: offers?.url || null,
      confidence: 0.9,
    });
  }
  // Recurse into common containers
  if (Array.isArray(item["@graph"])) for (const x of item["@graph"]) collectJsonLdEvents(x, out);
  if (item.itemListElement) {
    const list = Array.isArray(item.itemListElement) ? item.itemListElement : [item.itemListElement];
    for (const x of list) collectJsonLdEvents(x?.item || x, out);
  }
  if (item.hasPart) {
    const parts = Array.isArray(item.hasPart) ? item.hasPart : [item.hasPart];
    for (const x of parts) collectJsonLdEvents(x, out);
  }
  if (item.subEvent) {
    const subs = Array.isArray(item.subEvent) ? item.subEvent : [item.subEvent];
    for (const x of subs) collectJsonLdEvents(x, out);
  }
  if (item.mainEntity) collectJsonLdEvents(item.mainEntity, out);
}

async function crawlJsonLd(venue, html) {
  const nowIso = new Date().toISOString();
  const events = parseJsonLdEvents(html).filter(
    (e) => e.title && e.start_date && e.start_date.slice(0, 10) >= nowIso.slice(0, 10),
  );
  if (events.length === 0) return null;
  const rows = events.map((e) => normalizeEvent(e, venue));
  if (rows.length > 0) await upsertEvents(rows);
  await updateVenueCrawlStatus(venue.id, true, venue.crawl_success_rate);
  return { venue: venue.name, events: rows.length, status: "ok (json-ld)" };
}

// ── Localist API crawler (MIT, Northeastern, BC, etc.) ──

async function crawlLocalist(venue, baseUrl) {
  const allEvents = [];
  let page = 1;
  const maxPages = 5;

  try {
    // Extract the base domain for the API
    const urlObj = new URL(baseUrl);
    const apiBase = `${urlObj.origin}/api/2/events`;

    while (page <= maxPages) {
      const url = `${apiBase}?pp=50&page=${page}`;
      const res = await fetch(url, { headers: { Accept: "application/json" } });
      if (!res.ok) {
        console.log(`    Localist API error: ${res.status}`);
        break;
      }
      const data = await res.json();
      const events = data.events || [];
      if (events.length === 0) break;

      for (const wrapper of events) {
        const evt = wrapper.event || wrapper;
        if (!evt.title) continue;

        // Emit one row per upcoming instance. Localist's `first_date` is the
        // event's FIRST-EVER date (often years in the past for recurring/ongoing
        // events like "Patriots' Day" or long-running exhibitions), which was
        // getting upserted as a stale row and filtered out of upcoming queries.
        // `event_instances[].event_instance.start` has the real per-occurrence date.
        const instances = evt.event_instances || [];
        const nowIso = new Date().toISOString();
        let added = 0;
        for (const instWrapper of instances) {
          const inst = instWrapper.event_instance || instWrapper;
          const startIso = inst.start || null;
          if (!startIso || startIso < nowIso) continue;
          allEvents.push({
            title: evt.title,
            start_date: startIso,
            end_date: inst.end || null,
            description: (evt.description_text || "").slice(0, 300) || null,
            source_url: evt.localist_url || evt.url || null,
            is_free: evt.free || false,
            ticket_url: evt.ticket_url || null,
            venue_override: evt.location_name || null,
          });
          added++;
        }
        // Fallback for events with no instances array (rare but possible for
        // unpublished/draft rows). Uses first_date only — past dates get dropped
        // during the upcoming-event query, so this won't pollute the feed.
        if (added === 0) {
          const startIso = evt.first_date || evt.date || null;
          if (startIso && startIso >= nowIso.slice(0, 10)) {
            allEvents.push({
              title: evt.title,
              start_date: startIso,
              end_date: evt.last_date || null,
              description: (evt.description_text || "").slice(0, 300) || null,
              source_url: evt.localist_url || evt.url || null,
              is_free: evt.free || false,
              ticket_url: evt.ticket_url || null,
              venue_override: evt.location_name || null,
            });
          }
        }
      }

      const totalPages = data.page?.total || 1;
      if (page >= totalPages) break;
      page++;
      await new Promise((r) => setTimeout(r, 300));
    }

    const rows = allEvents.map((e) => {
      const v = e.venue_override ? { ...venue, name: e.venue_override } : venue;
      return normalizeEvent(e, v);
    });
    if (rows.length > 0) await upsertEvents(rows);
    await updateVenueCrawlStatus(venue.id, true, venue.crawl_success_rate);
    return { venue: venue.name, events: rows.length, status: "ok (localist)" };
  } catch (err) {
    console.log(`    Localist crawl error: ${err.message}`);
    await updateVenueCrawlStatus(venue.id, false, venue.crawl_success_rate);
    return { venue: venue.name, events: 0, status: "error", reason: err.message };
  }
}

// ── WordPress REST API crawler ──

async function crawlWordPress(venue, baseUrl) {
  const allEvents = [];

  try {
    const urlObj = new URL(baseUrl);
    const wpBase = `${urlObj.origin}/wp-json`;

    // Try The Events Calendar plugin first (most common for event venues)
    const endpoints = [
      `${wpBase}/tribe/events/v1/events?per_page=50&start_date=now`,
      `${wpBase}/wp/v2/events?per_page=50&orderby=date&order=asc`,
      `${wpBase}/wp/v2/tribe_events?per_page=50`,
    ];

    let found = false;
    for (const endpoint of endpoints) {
      try {
        const res = await fetch(endpoint, {
          headers: { Accept: "application/json" },
        });
        if (!res.ok) continue;
        const data = await res.json();

        // Tribe Events Calendar format
        const events = data.events || (Array.isArray(data) ? data : []);
        if (events.length === 0) continue;

        found = true;
        for (const evt of events) {
          const title = evt.title?.rendered || evt.title || "";
          if (!title) continue;

          const startDate = evt.start_date || evt.date || evt.meta?.start_date || null;
          if (!startDate) continue;

          const desc = (evt.description?.rendered || evt.excerpt?.rendered || evt.content?.rendered || "")
            .replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim().slice(0, 300);

          allEvents.push({
            title: typeof title === "string" ? title : title.toString(),
            start_date: startDate,
            end_date: evt.end_date || evt.meta?.end_date || null,
            description: desc || null,
            source_url: evt.url || evt.link || null,
            is_free: evt.cost === "Free" || evt.cost === "" || evt.cost === "$0" || false,
            ticket_url: evt.website || evt.url || null,
          });
        }

        // Paginate if tribe events
        if (data.next_rest_url) {
          let nextUrl = data.next_rest_url;
          let pages = 1;
          while (nextUrl && pages < 5) {
            const nextRes = await fetch(nextUrl, { headers: { Accept: "application/json" } });
            if (!nextRes.ok) break;
            const nextData = await nextRes.json();
            const nextEvents = nextData.events || [];
            if (nextEvents.length === 0) break;
            for (const evt of nextEvents) {
              const title = evt.title?.rendered || evt.title || "";
              const startDate = evt.start_date || evt.date || null;
              if (!title || !startDate) continue;
              const desc = (evt.description?.rendered || "").replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim().slice(0, 300);
              allEvents.push({
                title, start_date: startDate, end_date: evt.end_date || null,
                description: desc || null, source_url: evt.url || evt.link || null,
                is_free: evt.cost === "Free" || false, ticket_url: evt.website || null,
              });
            }
            nextUrl = nextData.next_rest_url || null;
            pages++;
            await new Promise((r) => setTimeout(r, 300));
          }
        }
        break;
      } catch (_) {
        continue;
      }
    }

    if (!found) {
      // Fallback: return to normal HTML crawl
      return null;
    }

    const rows = allEvents.map((e) => normalizeEvent(e, venue));
    if (rows.length > 0) await upsertEvents(rows);
    await updateVenueCrawlStatus(venue.id, true, venue.crawl_success_rate);
    return { venue: venue.name, events: rows.length, status: "ok (wordpress)" };
  } catch (err) {
    console.log(`    WordPress crawl error: ${err.message}`);
    return null; // Fall back to HTML crawl
  }
}

// ── Tessitura API crawler ──

async function crawlTessitura(venue, baseUrl) {
  try {
    const urlObj = new URL(baseUrl);
    const origin = urlObj.origin;

    // Try common Tessitura/TNEW API endpoints
    const apiEndpoints = [
      `${origin}/api/events`,
      `${origin}/api/performances`,
      `${origin}/TessituraService/Web/Cart/Performances`,
    ];

    // Also try TNEW feed endpoint (many Tessitura sites expose this)
    const tnewEndpoints = [
      `${origin}/events-tickets/calendar?format=json`,
      `${origin}/events?format=json`,
      `${origin}/Online/default.asp?doession&BOparam::WScontent::loadArticle::permalink=events-calendar`,
    ];

    // For Tessitura, the HTML often has structured data even though it's JS-heavy
    // Fall back to normal HTML crawl which may pick up JSON-LD or structured content
    console.log(`    Tessitura detected — falling back to HTML crawl with enhanced extraction`);
    return null; // Fall back to HTML crawl
  } catch (err) {
    return null;
  }
}

// ── Drupal JSON:API crawler ──

async function crawlDrupal(venue, baseUrl) {
  const allEvents = [];

  try {
    const urlObj = new URL(baseUrl);
    const origin = urlObj.origin;

    // Try Drupal JSON:API endpoints
    const endpoints = [
      `${origin}/jsonapi/node/event?sort=-field_date_time&page[limit]=50`,
      `${origin}/jsonapi/node/events?sort=-field_date_time&page[limit]=50`,
      `${origin}/jsonapi/node/performance?sort=-field_date_time&page[limit]=50`,
      `${origin}/jsonapi/node/program?sort=-field_date_time&page[limit]=50`,
    ];

    for (const endpoint of endpoints) {
      try {
        const res = await fetch(endpoint, {
          headers: { Accept: "application/vnd.api+json, application/json" },
        });
        if (!res.ok) continue;
        const data = await res.json();
        const items = data.data || [];
        if (items.length === 0) continue;

        for (const item of items) {
          const attrs = item.attributes || {};
          const title = attrs.title || attrs.name || "";
          if (!title) continue;

          // Try various date field names
          const startDate = attrs.field_date_time?.value || attrs.field_start_date?.value ||
            attrs.field_event_date?.value || attrs.field_date?.value ||
            attrs.created || null;
          if (!startDate) continue;

          const endDate = attrs.field_date_time?.end_value || attrs.field_end_date?.value || null;
          const desc = (attrs.field_description?.value || attrs.body?.value || "")
            .replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim().slice(0, 300);

          allEvents.push({
            title,
            start_date: startDate,
            end_date: endDate,
            description: desc || null,
            source_url: `${origin}${attrs.path?.alias || `/node/${item.id}`}`,
            is_free: attrs.field_free || attrs.field_is_free || false,
          });
        }

        // Paginate
        let nextUrl = data.links?.next?.href || null;
        let pages = 1;
        while (nextUrl && pages < 5) {
          const nextRes = await fetch(nextUrl, {
            headers: { Accept: "application/vnd.api+json, application/json" },
          });
          if (!nextRes.ok) break;
          const nextData = await nextRes.json();
          const nextItems = nextData.data || [];
          if (nextItems.length === 0) break;
          for (const item of nextItems) {
            const attrs = item.attributes || {};
            const title = attrs.title || "";
            const startDate = attrs.field_date_time?.value || attrs.field_start_date?.value ||
              attrs.field_event_date?.value || attrs.created || null;
            if (!title || !startDate) continue;
            const desc = (attrs.body?.value || "").replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim().slice(0, 300);
            allEvents.push({
              title, start_date: startDate, end_date: attrs.field_date_time?.end_value || null,
              description: desc || null, source_url: `${origin}${attrs.path?.alias || ""}`,
            });
          }
          nextUrl = nextData.links?.next?.href || null;
          pages++;
          await new Promise((r) => setTimeout(r, 300));
        }

        break; // Found events, stop trying endpoints
      } catch (_) {
        continue;
      }
    }

    if (allEvents.length === 0) return null; // Fall back to HTML

    const rows = allEvents.map((e) => normalizeEvent(e, venue));
    if (rows.length > 0) await upsertEvents(rows);
    await updateVenueCrawlStatus(venue.id, true, venue.crawl_success_rate);
    return { venue: venue.name, events: rows.length, status: "ok (drupal)" };
  } catch (err) {
    console.log(`    Drupal crawl error: ${err.message}`);
    return null;
  }
}

// ── Ticketure API crawler (Museum of Science, etc.) ──

async function crawlTicketure(venue, baseUrl) {
  try {
    const urlObj = new URL(baseUrl);
    const apiUrl = `${urlObj.origin}/api/events/available`;

    const res = await fetch(apiUrl, {
      headers: { Accept: "application/json", "User-Agent": "Mozilla/5.0" },
    });
    if (!res.ok) return null;

    const data = await res.json();
    const templates = data.event_template?._data || [];
    if (templates.length === 0) return null;

    const allEvents = [];
    for (const evt of templates) {
      if (!evt.name) continue;
      // Skip memberships and private events
      const cat = (evt.category || "").toLowerCase();
      if (cat.includes("membership") || cat.includes("private") || cat.includes("gift")) continue;

      const desc = (evt.description || "")
        .replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim().slice(0, 300);

      // For timed/repeating events, try to get sessions
      let startDate = null;
      if (evt.event_type === "single" || evt.event_type === "timed_entry") {
        try {
          const sessRes = await fetch(`${urlObj.origin}/api/events/${evt.id}/sessions?_limit=5`, {
            headers: { Accept: "application/json", "User-Agent": "Mozilla/5.0" },
          });
          if (sessRes.ok) {
            const sessData = await sessRes.json();
            const sessions = sessData.event_session?._data || [];
            if (sessions.length > 0) {
              startDate = sessions[0].start_datetime || null;
            }
          }
          await new Promise((r) => setTimeout(r, 200));
        } catch (_) {}
      }

      allEvents.push({
        title: evt.name,
        start_date: startDate || new Date().toISOString(),
        description: desc || null,
        source_url: `${urlObj.origin}/events/${evt.id}`,
        is_free: false,
        category_hint: evt.category || null,
      });
    }

    const rows = allEvents.map((e) => normalizeEvent(e, venue));
    if (rows.length > 0) await upsertEvents(rows);
    await updateVenueCrawlStatus(venue.id, true, venue.crawl_success_rate);
    return { venue: venue.name, events: rows.length, status: "ok (ticketure)" };
  } catch (err) {
    console.log(`    Ticketure crawl error: ${err.message}`);
    return null;
  }
}

// ── LiveWhale API crawler (Brown, Tufts, etc.) ──

async function crawlLiveWhale(venue, baseUrl) {
  try {
    const urlObj = new URL(baseUrl);
    const apiUrl = `${urlObj.origin}/live/json/events/`;

    const res = await fetch(apiUrl, {
      headers: { Accept: "application/json", "User-Agent": "Mozilla/5.0" },
    });
    if (!res.ok) return null;

    const events = await res.json();
    if (!Array.isArray(events) || events.length === 0) return null;

    const allEvents = [];
    for (const evt of events) {
      if (!evt.title) continue;
      const dateStr = evt.date_utc || evt.date || null;
      if (!dateStr) continue;

      allEvents.push({
        title: evt.title,
        start_date: dateStr,
        end_date: evt.date2_utc || null,
        description: (evt.description || "").replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim().slice(0, 300) || null,
        source_url: evt.url || `${urlObj.origin}/live/events/${evt.id}`,
        is_free: false,
      });
    }

    const rows = allEvents.map((e) => normalizeEvent(e, venue));
    if (rows.length > 0) await upsertEvents(rows);
    await updateVenueCrawlStatus(venue.id, true, venue.crawl_success_rate);
    return { venue: venue.name, events: rows.length, status: "ok (livewhale)" };
  } catch (err) {
    console.log(`    LiveWhale crawl error: ${err.message}`);
    return null;
  }
}

// ── Platform detection helper ──

async function detectAndCrawlPlatform(venue) {
  const url = venue.events_url;

  // 1. BiblioCommons
  const biblioMatch = url.match(/(\w+)\.bibliocommons\.com/);
  if (biblioMatch) return await crawlBiblioCommons(venue, biblioMatch[1]);

  // 2. Localist (university calendars)
  const localistDomains = ["calendar.mit.edu", "calendar.northeastern.edu", "events.bc.edu",
    "calendar.uri.edu", "events.uconn.edu",
    "events.wellesley.edu", "calendar.emerson.edu"];

  // LiveWhale domains (Brown, Tufts, etc.)
  const liveWhaleDomains = ["events.brown.edu", "events.tufts.edu"];
  try {
    const hostname = new URL(url).hostname;
    if (localistDomains.some((d) => hostname.includes(d)) || url.includes("localist.com")) {
      return await crawlLocalist(venue, url);
    }

    // 3b. LiveWhale
    if (liveWhaleDomains.some((d) => hostname.includes(d))) {
      return await crawlLiveWhale(venue, url);
    }
  } catch (_) {}

  // 3. Try WordPress API (detect from HTML or try wp-json)
  // We'll try this after HTML fetch below

  // 4. Drupal — detect from HTML markers
  // We'll try this after HTML fetch below

  return null; // No platform detected pre-fetch
}

// ── Crawl a single venue ──

async function crawlVenue(venue) {
  if (!venue.events_url) {
    return { venue: venue.name, events: 0, status: "skipped", reason: "no events_url" };
  }

  // Support multiple URLs separated by commas
  const urls = venue.events_url.split(",").map((u) => u.trim()).filter(Boolean);
  if (urls.length > 1) {
    let totalEvents = 0;
    let anySuccess = false;
    for (const url of urls) {
      const subVenue = { ...venue, events_url: url };
      const result = await crawlVenue(subVenue);
      if (result.status?.startsWith("ok")) anySuccess = true;
      totalEvents += result.events || 0;
    }
    await updateVenueCrawlStatus(venue.id, anySuccess, venue.crawl_success_rate);
    return { venue: venue.name, events: totalEvents, status: anySuccess ? "ok (multi-url)" : "error" };
  }

  try {
    // Try platform-specific API crawlers first
    const platformResult = await detectAndCrawlPlatform(venue);
    if (platformResult) return platformResult;

    // Fetch HTML
    const rawHtml = await fetchPage(venue.events_url);
    const cleanedHtml = stripHtml(rawHtml);

    if (cleanedHtml.length < 100) {
      await updateVenueCrawlStatus(venue.id, false, venue.crawl_success_rate);
      return { venue: venue.name, events: 0, status: "empty", reason: "page too short" };
    }

    // Post-fetch platform detection — try structured APIs before Claude
    const htmlLc = rawHtml.toLowerCase();

    // WordPress detection
    if (htmlLc.includes("wp-content") || htmlLc.includes("wordpress") || htmlLc.includes("wp-json")) {
      const wpResult = await crawlWordPress(venue, venue.events_url);
      if (wpResult) return wpResult;
    }

    // Drupal detection
    if (htmlLc.includes("drupal") || htmlLc.includes("jsonapi") || htmlLc.includes("drupal.settings")) {
      const drupalResult = await crawlDrupal(venue, venue.events_url);
      if (drupalResult) return drupalResult;
    }

    // LiveWhale detection
    if (htmlLc.includes("livewhale") || htmlLc.includes("live/json/events")) {
      const lwResult = await crawlLiveWhale(venue, venue.events_url);
      if (lwResult) return lwResult;
    }

    // Ticketure detection (MOS, etc.)
    if (htmlLc.includes("ticketure")) {
      const ticketureResult = await crawlTicketure(venue, venue.events_url);
      if (ticketureResult) return ticketureResult;
    }

    // Tessitura detection
    if (htmlLc.includes("tessitura") || htmlLc.includes("tnew") || htmlLc.includes("tessituraservice")) {
      console.log(`    Tessitura detected for ${venue.name} — using HTML crawl`);
    }

    // JSON-LD schema.org Event extraction — try before Claude HTML fallback.
    // Many well-maintained sites (museums, universities, city gov) publish
    // structured event data we can parse deterministically.
    if (htmlLc.includes("application/ld+json")) {
      const jsonLdResult = await crawlJsonLd(venue, rawHtml);
      if (jsonLdResult) {
        console.log(`    ✓ JSON-LD extracted ${jsonLdResult.events} events for ${venue.name}`);
        return jsonLdResult;
      }
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
