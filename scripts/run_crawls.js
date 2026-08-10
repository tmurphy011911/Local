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
const { fetchHtml: scrapflyFetchHtml, postForm: scrapflyPostForm } = require("./lib/scrapfly_fetch");

const MODEL = "claude-haiku-4-5-20251001";
const MAX_HTML_CHARS = 50_000;

let ANTHROPIC_API_KEY, SUPABASE_SERVICE_KEY, SCRAPFLY_API_KEY;
const CATEGORY_FILTER = process.env.CATEGORY
  ? process.env.CATEGORY.split(",").map((s) => s.trim())
  : null;
const VENUE_NAME = process.env.VENUE_NAME || null;
// STATE filter — used by the weekly workflow's matrix to shard the crawl
// across states in parallel runners (each gets <2h instead of one runner
// hitting the 6h GitHub Actions max). Comma-separated list e.g. "MA" or
// "MA,RI". Null/empty = all states.
const STATE_FILTER = (process.env.STATE || "").trim() || null;
// Comma-separated city list to scope within STATE_FILTER (or globally).
// Empty = all cities. For onboarding runs that should crawl only a freshly-
// inserted subset, not the full state's venues.
const CITIES_FILTER = (process.env.CITIES || "").split(",").map((c) => c.trim()).filter(Boolean);
// ISO timestamp — only crawl venues whose row was created on/after.
const CREATED_AFTER = (process.env.CREATED_AFTER || "").trim() || null;
const CONCURRENCY = parseInt(process.env.CONCURRENCY || "3", 10);
const MAX_VENUES = parseInt(process.env.MAX_VENUES || "0", 10);
// Wall-clock budget in minutes. When set, the crawl stops pulling NEW venues
// once the budget is hit (in-flight venues finish), then upserts what it got.
// Combined with the stalest-first ordering in fetchVenues(), this makes a
// timeout structurally impossible: each run drains the most-stale venues it
// can within the budget, and the rest are picked up next run (their
// last_crawled_at is now newer, so they sort to the back). 0 = no budget
// (crawl everything — used for manual single-venue/VENUE_NAME runs).
const MAX_MINUTES = parseInt(process.env.CRAWL_MAX_MINUTES || "0", 10);
// NPS Developer API — falls back to api.data.gov DEMO_KEY (30 req/hr, 50/day)
// when NPS_API_KEY isn't set. DEMO_KEY is fine for our 6 NPS venues weekly.
const NPS_API_KEY = process.env.NPS_API_KEY || "DEMO_KEY";

// ── Generic RSS / Atom adapter ─────────────────────────────────────────
//
// Many venues silently fail today because their /events page renders
// client-side (JS) while advertising a clean RSS/Atom feed via
// <link rel="alternate" type="application/rss+xml"> in the page head.
// This adapter finds the feed, fetches it, parses items, and hands them
// to Claude for date extraction. Feed descriptions are preserved verbatim
// (via match-back after Claude extraction) so events inherit the feed's
// rich description text instead of coming through empty.

function findFeedLink(html) {
  const linkRe = /<link\b[^>]*>/gi;
  const candidates = [];
  let m;
  while ((m = linkRe.exec(html)) !== null) {
    const tag = m[0];
    const isAlternate = /rel\s*=\s*["']?alternate["']?/i.test(tag);
    const isFeedType = /type\s*=\s*["']application\/(rss\+xml|atom\+xml)["']/i.test(tag);
    if (!isAlternate || !isFeedType) continue;
    const href = tag.match(/href\s*=\s*["']([^"']+)["']/i)?.[1];
    const title = tag.match(/title\s*=\s*["']([^"']+)["']/i)?.[1] || "";
    if (href) candidates.push({ href, title });
  }
  if (candidates.length === 0) return null;
  // Prefer feed whose title mentions events; otherwise first.
  return candidates.find((c) => /event/i.test(c.title)) || candidates[0];
}

// Decode HTML/XML entities. Looped up to 5 times so double-encoded values
// like `&amp;#8211;` unwind fully in one call (pass 1: &amp; → &, pass 2:
// &#8211; → en-dash). Without the loop, the original single-pass version
// left `&#8211;` literal in titles like "Veteran Grave Flagging &#8211;
// Melrose Cemetery". Also handles hex numeric (&#x201C;) and the common
// named entities municipal feeds emit.
function decodeXmlEntities(s) {
  let result = String(s);
  for (let i = 0; i < 5; i++) {
    const before = result;
    result = result
      .replace(/&amp;/g, "&")
      .replace(/&lt;/g, "<")
      .replace(/&gt;/g, ">")
      .replace(/&quot;/g, '"')
      .replace(/&apos;/g, "'")
      .replace(/&nbsp;/g, " ")
      .replace(/&mdash;/g, "—")
      .replace(/&ndash;/g, "–")
      .replace(/&hellip;/g, "…")
      .replace(/&lsquo;/g, "‘")
      .replace(/&rsquo;/g, "’")
      .replace(/&ldquo;/g, "“")
      .replace(/&rdquo;/g, "”")
      .replace(/&#(\d+);/g, (_, n) => {
        const code = parseInt(n, 10);
        return Number.isFinite(code) ? String.fromCodePoint(code) : "";
      })
      .replace(/&#x([0-9a-fA-F]+);/g, (_, n) => {
        const code = parseInt(n, 16);
        return Number.isFinite(code) ? String.fromCodePoint(code) : "";
      });
    if (result === before) break;
  }
  return result;
}

function extractXmlField(xml, field) {
  const cdata = new RegExp(`<${field}\\b[^>]*><!\\[CDATA\\[([\\s\\S]*?)\\]\\]></${field}>`, "i").exec(xml);
  if (cdata) return cdata[1].trim();
  const plain = new RegExp(`<${field}\\b[^>]*>([\\s\\S]*?)</${field}>`, "i").exec(xml);
  if (plain) return decodeXmlEntities(plain[1]).trim();
  return null;
}

function parseFeedItems(xml) {
  const items = [];
  const blockRe = /<(item|entry)\b[^>]*>([\s\S]*?)<\/\1>/gi;
  let m;
  while ((m = blockRe.exec(xml)) !== null) {
    const block = m[2];
    const title = extractXmlField(block, "title");
    if (!title) continue;
    // RSS uses plain <link>URL</link>. Atom uses <link href="URL"/>
    let link = extractXmlField(block, "link");
    if (!link) {
      const atomLink = block.match(/<link\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*\/?\s*>/i);
      if (atomLink) link = atomLink[1];
    }
    const description =
      extractXmlField(block, "description") ||
      extractXmlField(block, "summary") ||
      extractXmlField(block, "content:encoded") ||
      extractXmlField(block, "content") ||
      "";
    const pubDate = extractXmlField(block, "pubDate") || extractXmlField(block, "published") || extractXmlField(block, "updated") || null;
    items.push({ title, link: link || "", description, pubDate });
  }
  return items;
}

async function crawlRssFeed(venue, rawHtml) {
  const feedLink = findFeedLink(rawHtml);
  if (!feedLink) return null;

  let feedUrl;
  try {
    feedUrl = new URL(feedLink.href, venue.events_url).toString();
  } catch {
    return null;
  }

  let feedXml;
  try {
    feedXml = await fetchPage(feedUrl);
  } catch (err) {
    console.log(`    RSS fetch failed (${feedUrl}): ${err.message}`);
    return null;
  }

  return crawlFeedXml(venue, feedXml, feedUrl);
}

// Parse an already-fetched RSS/Atom document into events, then upsert. Shared by
// crawlRssFeed (feed discovered via a <link rel=alternate> in the page head) and
// the direct-feed path in crawlVenue (events_url points straight at a feed that
// isn't advertised in any <head>, e.g. a WordPress /events/feed/). `sourceLabel`
// is only used for logging.
async function crawlFeedXml(venue, feedXml, sourceLabel) {
  const items = parseFeedItems(feedXml);
  if (items.length === 0) {
    console.log(`    RSS feed parsed but has 0 items (${sourceLabel})`);
    return null;
  }

  console.log(`    📡 RSS: ${items.length} items from ${sourceLabel}`);

  // Per-item strategy: fetch each detail page and try JSON-LD Event schema first
  // (reliable, fast, no Claude). RSS pubDate is WHEN THE ITEM WAS POSTED, not the
  // event date — so without the detail-page fetch we'd miss anything without dates
  // in its description (e.g. "Sheepshearing Festival" reads as seasonal text).
  // Fall back to Claude for items without JSON-LD on the detail page.
  const nowMs = Date.now();
  const jsonLdEvents = [];
  const fallbackItems = [];
  let idx = 0;
  const concurrency = 5;
  async function worker() {
    while (idx < items.length) {
      const i = idx++;
      const item = items[i];
      if (!item.link) {
        fallbackItems.push(item);
        continue;
      }
      try {
        const detailHtml = await fetchPage(item.link);
        const found = parseJsonLdEvents(detailHtml).filter((e) => {
          if (!e.title || !e.start_date) return false;
          const ms = Date.parse(e.start_date);
          return !isNaN(ms) && ms >= nowMs - 86_400_000; // allow earlier-today events
        });
        if (found.length === 0) {
          fallbackItems.push(item);
          continue;
        }
        // Richer RSS description beats JSON-LD's short summary.
        const feedDesc = stripHtml(item.description || "").slice(0, 500).trim();
        for (const e of found) {
          if (feedDesc && (!e.description || e.description.length < feedDesc.length)) {
            e.description = feedDesc;
          }
          if (!e.source_url) e.source_url = item.link;
        }
        jsonLdEvents.push(...found);
      } catch {
        fallbackItems.push(item);
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, worker));

  let claudeEvents = [];
  if (fallbackItems.length > 0) {
    const compactText = fallbackItems
      .map((item, i) => {
        const desc = stripHtml(item.description || "").slice(0, 1200);
        const pub = item.pubDate ? `Published: ${item.pubDate}` : "";
        return `### Event ${i + 1}\nTitle: ${item.title}\nURL: ${item.link}\n${pub}\n\n${desc}`;
      })
      .join("\n\n---\n\n");
    try {
      const result = await extractEventsFromHtml(venue, compactText);
      claudeEvents = (result.events || []).filter((e) => e.title && e.start_date);
      const byLink = new Map(fallbackItems.map((i) => [i.link, i]));
      const byTitle = new Map(fallbackItems.map((i) => [i.title.toLowerCase().trim(), i]));
      for (const e of claudeEvents) {
        const hit =
          (e.source_url && byLink.get(e.source_url)) ||
          byTitle.get((e.title || "").toLowerCase().trim()) ||
          fallbackItems.find((it) => {
            const t = it.title.toLowerCase();
            const et = (e.title || "").toLowerCase();
            return t.length > 8 && (et.includes(t) || t.includes(et.slice(0, Math.min(et.length, 20))));
          });
        if (hit) {
          const feedDesc = stripHtml(hit.description || "").slice(0, 500).trim();
          if (feedDesc && (!e.description || e.description.length < feedDesc.length)) {
            e.description = feedDesc;
          }
          if (!e.source_url && hit.link) e.source_url = hit.link;
        }
      }
    } catch (err) {
      console.log(`    Claude RSS fallback failed: ${err.message}`);
    }
  }

  const allEvents = [...jsonLdEvents, ...claudeEvents];
  if (allEvents.length === 0) return null;

  console.log(`    RSS breakdown: ${jsonLdEvents.length} via JSON-LD detail pages, ${claudeEvents.length} via Claude fallback`);

  const rows = allEvents.map((e) => normalizeEvent(e, venue));
  // Upsert inline (per-venue) so a cancelled/timed-out run keeps these events.
  // The RSS/Claude paths used to buffer rows for a single end-of-run batch that
  // could crash (bad date) or blow the job timeout and lose a whole state's
  // events — the reason NY read ~86% zero. rows are still returned for the run
  // summary, but they are already persisted here.
  if (rows.length > 0) await upsertEvents(rows);
  await updateVenueCrawlStatus(venue.id, true, venue.crawl_success_rate);
  return { venue: venue.name, events: rows.length, status: "ok (rss)", rows };
}

// ── Description enrichment ────────────────────────────────────────────
//
// Second-pass enrichment: for any event whose description is missing or
// short (< 40 chars) but has a source_url, fetch the detail page and
// pull the description from structured data (JSON-LD Event.description,
// og:description, <meta name="description">). No Claude call — pure
// HTML parsing, ~100-200ms per page, cheap.

function extractDescriptionFromHtml(html) {
  // 1. JSON-LD Event.description (most reliable)
  const jsonLdBlocks = html.match(/<script[^>]*type\s*=\s*["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi) || [];
  for (const block of jsonLdBlocks) {
    const body = block.replace(/<script[^>]*>/i, "").replace(/<\/script>/i, "").trim();
    try {
      const parsed = JSON.parse(body);
      const walk = (node) => {
        if (!node || typeof node !== "object") return null;
        if (Array.isArray(node)) {
          for (const item of node) {
            const d = walk(item);
            if (d) return d;
          }
          return null;
        }
        const t = node["@type"];
        const isEventType = (typeof t === "string" && /Event|Exhibit/i.test(t))
          || (Array.isArray(t) && t.some((x) => /Event|Exhibit/i.test(x)));
        if (isEventType && typeof node.description === "string" && node.description.trim()) {
          return node.description;
        }
        if (Array.isArray(node["@graph"])) {
          const d = walk(node["@graph"]);
          if (d) return d;
        }
        return null;
      };
      const desc = walk(parsed);
      if (desc) return stripHtml(desc).slice(0, 500).trim();
    } catch {
      // Malformed JSON-LD — skip.
    }
  }

  // 2. OpenGraph description
  const og =
    html.match(/<meta[^>]*property\s*=\s*["']og:description["'][^>]*content\s*=\s*["']([^"']+)["']/i)?.[1] ||
    html.match(/<meta[^>]*content\s*=\s*["']([^"']+)["'][^>]*property\s*=\s*["']og:description["']/i)?.[1];
  if (og) {
    const d = decodeXmlEntities(stripHtml(og)).slice(0, 500).trim();
    if (d.length >= 40) return d;
  }

  // 3. <meta name="description">
  const meta =
    html.match(/<meta[^>]*name\s*=\s*["']description["'][^>]*content\s*=\s*["']([^"']+)["']/i)?.[1] ||
    html.match(/<meta[^>]*content\s*=\s*["']([^"']+)["'][^>]*name\s*=\s*["']description["']/i)?.[1];
  if (meta) {
    const d = decodeXmlEntities(stripHtml(meta)).slice(0, 500).trim();
    if (d.length >= 40) return d;
  }

  return null;
}

async function enrichEventDescription(row) {
  const current = (row.description || "").trim();
  if (current.length >= 40) return row;
  if (!row.source_url) return row;
  try {
    const html = await fetchPage(row.source_url);
    const desc = extractDescriptionFromHtml(html);
    if (desc && desc.length > current.length) {
      row.description = desc;
    }
  } catch {
    // Fetch failures are silent — keep existing value.
  }
  return row;
}

async function enrichDescriptions(rows, concurrency = 5) {
  if (!rows || rows.length === 0) return rows;
  let index = 0;
  const out = new Array(rows.length);
  async function worker() {
    while (index < rows.length) {
      const i = index++;
      out[i] = await enrichEventDescription(rows[i]);
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, rows.length) }, worker));
  return out;
}

module.exports = module.exports || {};
module.exports.extractDescriptionFromHtml = extractDescriptionFromHtml;
module.exports.enrichEventDescription = enrichEventDescription;
module.exports.enrichDescriptions = enrichDescriptions;

// ── Category mapping from venue category to event category ──

// ── Fetch venues from Supabase ──

async function fetchVenues() {
  let baseUrl = `${SUPABASE_URL}/rest/v1/venues?is_active=eq.true&select=*`;
  if (VENUE_NAME) {
    const names = VENUE_NAME.split(",").map((s) => s.trim());
    if (names.length === 1) {
      baseUrl += `&name=ilike.*${encodeURIComponent(names[0])}*`;
    } else {
      // Wrap each ilike value in double quotes. PostgREST's or=() parser treats
      // unquoted parens/commas inside values as structural separators, which
      // silently dropped venues whose names contain them (e.g. "Massachusetts
      // Institute of Technology (MIT)"). Quoted values are treated as literals.
      const orFilter = names.map((n) => `name.ilike."*${n}*"`).join(",");
      baseUrl += `&or=(${encodeURIComponent(orFilter)})`;
    }
  }
  if (CATEGORY_FILTER) {
    baseUrl += `&category=in.(${CATEGORY_FILTER.join(",")})`;
  }
  if (STATE_FILTER) {
    const states = STATE_FILTER.split(",").map((s) => s.trim().toUpperCase()).filter(Boolean);
    baseUrl += states.length === 1
      ? `&state=eq.${states[0]}`
      : `&state=in.(${states.join(",")})`;
  }
  if (CITIES_FILTER.length) {
    const quoted = CITIES_FILTER.map((c) => `"${c.replace(/"/g, '\\"')}"`).join(",");
    baseUrl += `&city=in.(${encodeURIComponent(quoted)})`;
  }
  if (CREATED_AFTER) baseUrl += `&created_at=gte.${encodeURIComponent(CREATED_AFTER)}`;
  // Stalest-first: among crawlable venues (events_url present), crawl the ones
  // not touched in the longest time — never-crawled (null) first. Paired with
  // the CRAWL_MAX_MINUTES budget this gives a rolling crawl that always makes
  // progress on the oldest data and never re-starts from the same head, so no
  // venue is permanently starved when a run is time-boxed. crawl_success_rate
  // is the final tiebreak so reliably-productive venues win ties.
  baseUrl += "&order=events_url.desc.nullslast,last_crawled_at.asc.nullsfirst,crawl_success_rate.desc";

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

async function fetchPage(url, { stealth = false } = {}) {
  // Stealth path: route through Scrapfly with JS rendering. Used for venues
  // flagged needs_stealth=true (Cloudflare, Datadome, JS-rendered SPAs like
  // Squarespace event lists). Falls through to direct fetch if Scrapfly key
  // is missing or the Scrapfly request itself errors.
  if (stealth && SCRAPFLY_API_KEY) {
    try {
      const { html } = await scrapflyFetchHtml(url, {
        scrapflyKey: SCRAPFLY_API_KEY,
        renderJs: true,
        timeoutMs: 60_000,
        renderingWait: 5000,  // let late-AJAX widgets (TicketWeb tw-calendar, etc.) populate before capture
      });
      return html;
    } catch (err) {
      console.warn(`    Scrapfly failed for ${url} (${err.message}) — falling back to direct fetch`);
    }
  }

  // Direct fetch. On Cloudflare/WAF-shaped failures (403/429/503/520) or
  // network-level resets (ECONNRESET, fetch failed), retry via Scrapfly with
  // asp=true before giving up — same anti-bot bypass fix_venue_urls.js uses
  // for URL discovery. Without this, manually-added flagship venues (JFK
  // Presidential Library, Museum of Science, dozens of museums + theaters
  // behind Cloudflare) all returned HTTP 403 on the actual crawl despite the
  // URL being correct.
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 30_000);
  let directErr = null;
  let directStatus = 0;
  try {
    const res = await fetch(url, {
      headers: FETCH_HEADERS,
      signal: controller.signal,
      redirect: "follow",
    });
    clearTimeout(timeout);
    if (res.ok) return await res.text();
    directStatus = res.status;
    directErr = new Error(`HTTP ${res.status}`);
  } catch (err) {
    clearTimeout(timeout);
    directErr = err;
  }

  const isWafShape = directStatus && [403, 429, 503, 520, 1020].includes(directStatus);
  const isNetworkReset = directErr && /ECONNRESET|ETIMEDOUT|fetch failed|aborted/i.test(
    String(directErr.message || directErr.cause?.code || directErr)
  );
  if (SCRAPFLY_API_KEY && (isWafShape || isNetworkReset) && !stealth) {
    // Tier 1: asp=true alone. Bypasses common Cloudflare/Datadome rule sets
    // without paying the JS-render tax (~10–15s per venue).
    try {
      const { html } = await scrapflyFetchHtml(url, {
        scrapflyKey: SCRAPFLY_API_KEY,
        renderJs: false,
        timeoutMs: 45_000,
      });
      return html;
    } catch (_) { /* fall through to tier 2 */ }

    // Tier 2: asp=true + render_js. Some sites (JFK Library, Harvard museums,
    // boston.gov/mass.gov parks, theatre & college sites with JS-rendered
    // challenge pages) need the headless browser to actually execute the
    // Cloudflare interstitial JS before serving content. Paying the latency
    // is justified here — these are flagship venues that otherwise stay empty.
    try {
      const { html } = await scrapflyFetchHtml(url, {
        scrapflyKey: SCRAPFLY_API_KEY,
        renderJs: true,
        timeoutMs: 60_000,
        renderingWait: 4000,
      });
      return html;
    } catch (_) {
      // fall through to throw the original error so caller sees the real reason
    }
  }
  throw directErr;
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
- Dates in ISO 8601. Prefer the full \`YYYY-MM-DDTHH:MM:SS\` form. Use date-only \`YYYY-MM-DD\` ONLY when the page truly has no time of day for that event.
- TIME OF DAY IS REQUIRED WHEN VISIBLE. If the page shows any time indicator next to the event — "7 PM", "19:00", "Doors 6:30", "7:00pm", "Show starts at 8", a clock icon followed by digits, or an embedded ISO datetime in JSON-LD / meta tags — \`start_date\` MUST include that time. Returning date-only when a time is present on the page is a defect, not a fallback.
- If multiple times are shown for the same event (e.g., doors + show), use the SHOW time. If only "doors" is shown, use that.
- If year is missing, assume ${YEAR} (or ${YEAR + 1} if month already passed)
- Today is ${TODAY} — use this as the starting point for all date calculations
- For exhibitions/runs, set both start_date and end_date
- Set confidence 0.0-1.0 based on clarity of extracted data. If you used date-only because the page genuinely had no time, set confidence ≤ 0.6 so downstream can flag it.
- RECURRING EVENTS: When you detect a recurring event (e.g. "Every Sunday", "Weekly", "First Friday of every month", "Daily", "Every Monday and Wednesday"), you MUST expand it into separate individual event entries for EACH occurrence over the next 6 weeks starting from ${TODAY}. Do NOT create a single entry for a recurring event.
  - Each expanded entry must have its own specific start_date with the correct calendar date — that date is what distinguishes the occurrences, so it lives in start_date, NOT the title.
  - Do NOT append the date (or any "- March 22" style suffix) to the title. Each occurrence keeps the clean base title (e.g. "SOWA Sunday", "Trivia Night"); the date is stored separately in start_date and rendered from there.
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

STRICT — Only extract events that take place AT "${venue.name}". Some venues (independent bookstores, gallery hubs, multi-venue community calendars) cross-promote shows at other venues on their own events page. Skip those entries:
- Skip events where the listed location, room, or address is a different named venue (e.g. "at The Wilbur", "at City Winery", "Off-site:", "@ Partner Venue").
- Skip sections titled "Also happening", "Upcoming at our partners", "Around town", "Friends of ${venue.name}", "Other venues", or similar cross-promotion blocks.
- Skip rows whose title prefixes a different venue's name (e.g. "CANCELLED Venue: ...", "At The Wilbur:", "Off-site —").
- When in doubt, prefer to skip rather than include — a cross-venue event tagged to the wrong host poisons the user's distance + venue surfaces. False negatives are recoverable on the next crawl; false positives are not.

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
  // Already has timezone offset or Z — leave it alone. Accept both the
  // colon form (-04:00) and the colon-less form (-0400) that some sources
  // emit; without the colon-less check we'd append a second offset and
  // produce the Postgres-rejected "2026-08-08T19:00:00-0400-05:00".
  // Note: only match a 4-digit offset, never a bare ±HH — a date-only
  // "2026-08-08" ends in "-08" and would otherwise false-match.
  if (/[Zz]$/.test(isoDate) || /[+-]\d{2}:?\d{2}$/.test(isoDate)) return isoDate;
  // Date-only values (YYYY-MM-DD) need a time component before we can tack on
  // the offset, otherwise Postgres rejects "2026-07-10-04:00" as invalid.
  const normalized = /^\d{4}-\d{2}-\d{2}$/.test(isoDate) ? isoDate + "T00:00:00" : isoDate;
  // Determine EDT vs EST based on date
  // EDT: second Sunday in March → first Sunday in November
  try {
    const d = new Date(normalized + "Z"); // parse as UTC to get month/day
    const month = d.getUTCMonth(); // 0-indexed
    // Rough check: April-October is definitely EDT, Dec-Feb is EST
    // March and November need exact DST boundary checks but -04:00 is safe for most
    const isEDT = month >= 2 && month <= 10; // Mar-Nov → EDT (close enough)
    return normalized + (isEDT ? "-04:00" : "-05:00");
  } catch {
    return normalized + "-04:00"; // default EDT
  }
}

function normalizeEvent(event, venue) {
  const now = new Date().toISOString();
  const venueCity = (venue.city || "unknown").toLowerCase();
  // Per-event venue override (event._venue) — set by adapters when the feed
  // carries real per-event venue data (e.g. a Tribe aggregator like Waltham
  // Times whose every event names its actual venue). Attribution fields come
  // from the override; sourceId/dedupe_hash still hash the DB venue row's
  // name so a re-crawl after an attribution fix UPDATES the same rows
  // in place instead of duplicating them.
  const attr = event._venue && event._venue.name
    ? {
        name: decodeXmlEntities(event._venue.name).trim(),
        city: event._venue.city || venue.city,
        state: event._venue.state || venue.state || null,
        lat: event._venue.lat ?? venue.lat,
        lng: event._venue.lng ?? venue.lng,
      }
    : {
        name: decodeXmlEntities(venue.name),
        city: venue.city,
        state: venue.state || null,
        lat: venue.lat,
        lng: venue.lng,
      };
  // Decode HTML entities before hashing so "Pattern &amp; Place" and
  // "Pattern & Place" produce the same dedupe_hash across crawl paths.
  const title = decodeXmlEntities(event.title || "").trim();
  const description = event.description ? decodeXmlEntities(event.description).trim() : null;
  // Normalize the title for hashing only — lowercase, alphanumeric, first
  // 30 chars. Without this, week-over-week LLM extraction variation
  // ("Farm to Form: A Garden Dinner" vs "Farm to Form — Garden Dinner")
  // hashed differently and produced 3-4 duplicate rows per event.
  // Stored event.title still keeps the original casing & punctuation.
  const titleKey = title.toLowerCase().replace(/[^a-z0-9]/g, "").slice(0, 30);
  const sourceId = `crawl-${venueCity}-${Buffer.from(titleKey + event.start_date + venue.name).toString("base64url").slice(0, 40)}`;

  // True when the upstream source provided a real time component (HH:MM
  // in start_date that isn't 00:00). False when the crawler only had a
  // date or explicitly emitted T00:00:00 as a "no time" sentinel. The app
  // uses this flag to display "Time TBD" instead of "12 AM" and to let
  // Bo pick a sensible time when slotting these events.
  const startStr = typeof event.start_date === "string" ? event.start_date : "";
  const hasTimePart = /T\d{2}:\d{2}/.test(startStr);
  const isExplicitMidnight = /T00:00:00/.test(startStr);
  const startHasTime = hasTimePart && !isExplicitMidnight;

  return {
    title,
    description: description || null,
    // Don't inherit from venue.category — too coarse. Sellersville Theatre's
    // restaurant's "Wine Flights" used to land under arts because the venue
    // row is arts_center. classify_events.js owns per-event taxonomy now;
    // we just emit a neutral default.
    category: "other",
    subcategory: null,
    start_at: tagEasternTime(event.start_date),
    end_at: tagEasternTime(event.end_date),
    time_known: startHasTime,
    is_free: event.is_free || false,
    price_min: event.price_min ?? null,
    price_max: event.price_max ?? null,
    ticket_url: event.ticket_url || null,
    // No adapter extracts a per-event image today, so default to the
    // venue's photo. The migration backfill query handles existing rows.
    image_url: event.image_url || venue.photo_url || null,
    status: "active",
    venue_name: attr.name,
    venue_address: null,
    venue_city: attr.city,
    venue_state: attr.state,
    venue_lat: attr.lat ? parseFloat(attr.lat) : null,
    venue_lng: attr.lng ? parseFloat(attr.lng) : null,
    source: "crawl",
    source_id: sourceId,
    // For aggregator venues (Harvard Square, Creative Legion, etc.) padding
    // source_url with the parent listing page sabotages split_aggregator_events.js
    // — its JSON-LD path returns whichever Event happens to be first on the
    // listing, not this row's actual venue. Leave null when the adapter didn't
    // extract a per-event link so the splitter falls through to title hints.
    source_url: event.source_url || (venue.is_aggregator ? null : venue.events_url) || null,
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

// Shadow guard: a date-only crawler pass (time_known=false) must not insert
// a new row when an existing time_known=true row already represents the same
// event. Without this, a single LLM jitter run can resurrect a midnight-EDT
// duplicate alongside the correct row — exactly the Boch/Shubert symptom that
// produced 4 rows for one show. One round-trip per batch.
function shadowKey(title, startAt, venueName) {
  const titleKey = (title || "").toLowerCase().replace(/[^a-z0-9]/g, "").slice(0, 30);
  if (!startAt || !venueName || !titleKey) return null;
  const d = new Date(startAt);
  if (isNaN(d.getTime())) return null;
  const month = d.getUTCMonth();
  const offsetHours = (month >= 2 && month <= 10) ? 4 : 5;
  const dateET = new Date(d.getTime() - offsetHours * 3600 * 1000).toISOString().slice(0, 10);
  return `${venueName}::${dateET}::${titleKey}`;
}

async function filterDateOnlyShadows(batch) {
  // Guard against malformed start_at values (e.g. a bad timezone displacement
  // that Postgres rejects with 22009). An unparseable date here used to reach
  // `new Date(NaN).toISOString()` below and throw "Invalid time value", killing
  // the entire crawl upsert — which in turn skipped the whole downstream
  // classify/split/dedupe chain for the week. Exclude rows whose date prefix
  // can't be parsed; they still flow through to upsert (Postgres rejects the
  // bad row non-fatally), they just don't participate in the shadow check.
  const isParseableDate = (s) =>
    typeof s === "string" &&
    /^\d{4}-\d{2}-\d{2}/.test(s) &&
    !Number.isNaN(Date.parse(s.slice(0, 10) + "T00:00:00Z"));
  const dateOnly = batch.filter(
    (r) => r.time_known === false && r.start_at && r.venue_name && isParseableDate(r.start_at),
  );
  if (dateOnly.length === 0) return batch;

  const venues = [...new Set(dateOnly.map((r) => r.venue_name))];
  const dates = dateOnly.map((r) => r.start_at.slice(0, 10)).sort();
  const minDate = dates[0];
  // Add one day to the max so the half-open range covers the last day's events
  const maxDateMs = Date.parse(dates[dates.length - 1] + "T00:00:00Z") + 86_400_000;
  // Belt-and-suspenders: if the max somehow still can't be computed, fail open
  // rather than crash the upsert.
  if (Number.isNaN(maxDateMs)) return batch;
  const maxDate = new Date(maxDateMs).toISOString().slice(0, 10);

  const venueIn = venues.map((v) => `"${v.replace(/"/g, '\\"')}"`).join(",");
  const url = `${SUPABASE_URL}/rest/v1/events?` +
    `select=title,start_at,venue_name` +
    `&venue_name=in.(${encodeURIComponent(venueIn)})` +
    `&start_at=gte.${minDate}` +
    `&start_at=lt.${maxDate}` +
    `&time_known=eq.true` +
    `&is_active=eq.true`;
  let existing = [];
  try {
    const res = await fetch(url, {
      headers: { apikey: SUPABASE_SERVICE_KEY, Authorization: `Bearer ${SUPABASE_SERVICE_KEY}` },
    });
    if (res.ok) existing = await res.json();
  } catch { /* fail open — never block a crawl on this guard */ }

  if (existing.length === 0) return batch;

  const shadowed = new Set();
  for (const e of existing) {
    const k = shadowKey(e.title, e.start_at, e.venue_name);
    if (k) shadowed.add(k);
  }

  const kept = [];
  let dropped = 0;
  for (const r of batch) {
    if (r.time_known === false) {
      const k = shadowKey(r.title, r.start_at, r.venue_name);
      if (k && shadowed.has(k)) { dropped++; continue; }
    }
    kept.push(r);
  }
  if (dropped > 0) {
    console.log(`  Shadow guard: dropped ${dropped} date-only rows shadowed by existing time_known=true rows`);
  }
  return kept;
}

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
    const rawBatch = deduped.slice(i, i + batchSize);
    const batch = await filterDateOnlyShadows(rawBatch);
    if (batch.length === 0) continue;
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
          source_url: `https://${libraryCode}.bibliocommons.com/events/${id}`,
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
  const nowMs = Date.now();
  const events = parseJsonLdEvents(html).filter((e) => {
    if (!e.title || !e.start_date) return false;
    const ms = Date.parse(e.start_date);
    return !isNaN(ms) && ms >= nowMs - 86_400_000;  // allow today's earlier-today events
  });
  if (events.length === 0) return null;
  const rows = events.map((e) => normalizeEvent(e, venue));
  if (rows.length > 0) await upsertEvents(rows);
  await updateVenueCrawlStatus(venue.id, true, venue.crawl_success_rate);
  return { venue: venue.name, events: rows.length, status: "ok (json-ld)" };
}

// ── NPS Developer API crawler ──
// Handles any venue with events_url on nps.gov. Extracts the park code from
// the URL path (nps.gov/{code}/...) and queries developer.nps.gov/api/v1/events
// which returns one row per occurrence with date + times. Free with DEMO_KEY,
// real key via NPS_API_KEY env var for higher rate limits.

function extractNpsParkCode(url) {
  const m = url.match(/nps\.gov\/([a-z]{2,5})(?:\/|$)/i);
  return m ? m[1].toLowerCase() : null;
}

function parseNpsDateTime(dateStr, timeStr) {
  // dateStr = "2026-04-20", timeStr = "11:00 AM" → ISO string with time.
  // When `timeStr` is missing/unparseable, return the bare date (no T
  // component) so downstream normalization can flag time_known=false
  // instead of silently defaulting to midnight.
  if (!dateStr) return null;
  if (!timeStr) return dateStr;
  const m = timeStr.match(/(\d{1,2}):(\d{2})\s*(AM|PM)/i);
  if (!m) return dateStr;
  let hour = parseInt(m[1], 10);
  const min = m[2];
  const ampm = m[3].toUpperCase();
  if (ampm === "PM" && hour !== 12) hour += 12;
  if (ampm === "AM" && hour === 12) hour = 0;
  return `${dateStr}T${String(hour).padStart(2, "0")}:${min}:00`;
}

async function crawlNPS(venue, baseUrl) {
  const parkCode = extractNpsParkCode(baseUrl);
  if (!parkCode) {
    return { venue: venue.name, events: 0, status: "skipped", reason: "no park code" };
  }

  const allEvents = [];
  const nowMs = Date.now();
  try {
    // Pagination: API accepts pageSize up to 50, start (0-indexed offset).
    let start = 0;
    const pageSize = 50;
    const maxPages = 10;
    for (let p = 0; p < maxPages; p++) {
      const url = `https://developer.nps.gov/api/v1/events?parkCode=${parkCode}&pageSize=${pageSize}&start=${start}&api_key=${NPS_API_KEY}`;
      const res = await fetch(url, { headers: { Accept: "application/json" } });
      if (!res.ok) {
        console.log(`    NPS API error: ${res.status} for ${parkCode}`);
        break;
      }
      const data = await res.json();
      const events = data.data || [];
      if (events.length === 0) break;

      for (const evt of events) {
        if (!evt.title) continue;
        // Each item is one occurrence — use `date`, NOT `datestart` (which is
        // the overall range start for recurring events).
        const date = evt.date || evt.datestart || null;
        if (!date) continue;
        const times = Array.isArray(evt.times) && evt.times.length > 0 ? evt.times : [{}];
        for (const t of times) {
          const startIso = parseNpsDateTime(date, t.timestart);
          const endIso = parseNpsDateTime(date, t.timeend);
          if (!startIso) continue;
          const startMs = Date.parse(startIso);
          if (isNaN(startMs) || startMs < nowMs - 86_400_000) continue;
          allEvents.push({
            title: evt.title,
            start_date: startIso,
            end_date: endIso || null,
            description: (evt.description || "")
              .replace(/<[^>]+>/g, " ")
              .replace(/\s+/g, " ")
              .trim()
              .slice(0, 300) || null,
            source_url: evt.infourl || null,
            is_free: evt.isfree === "true",
            ticket_url: evt.regresurl || null,
            venue_override: evt.location || null,
            confidence: 0.95,
          });
        }
      }

      if (events.length < pageSize) break;
      start += pageSize;
      await new Promise((r) => setTimeout(r, 300));
    }

    const rows = allEvents.map((e) => {
      const v = e.venue_override ? { ...venue, name: venue.name } : venue;
      // For NPS, keep the parent venue name (e.g. "Independence National
      // Historical Park") rather than per-event location strings which are
      // often just meeting points like "East side of Liberty Bell Center".
      return normalizeEvent({ ...e, venue_override: null }, v);
    });
    if (rows.length > 0) await upsertEvents(rows);
    await updateVenueCrawlStatus(venue.id, true, venue.crawl_success_rate);
    return { venue: venue.name, events: rows.length, status: "ok (nps)" };
  } catch (err) {
    console.log(`    NPS crawl error for ${venue.name}: ${err.message}`);
    await updateVenueCrawlStatus(venue.id, false, venue.crawl_success_rate);
    return { venue: venue.name, events: 0, status: "error", reason: err.message };
  }
}

// ── Localist API crawler (MIT, Northeastern, BC, etc.) ──

async function crawlLocalist(venue, baseUrl) {
  const allEvents = [];
  let page = 1;
  // MIT alone publishes ~5000 events/60d (~83/day). At 50/page that's 100
  // pages. Most callers don't need more than a month window, so cap at
  // days=60 + 40 pages = 2000 events. days=N tells the API to return only
  // events whose next instance falls inside that window, which dramatically
  // improves signal-to-noise compared to the default "all events sorted by
  // first_date" behavior that surfaces ancient recurring rows first.
  const maxPages = 40;

  try {
    const urlObj = new URL(baseUrl);
    const apiBase = `${urlObj.origin}/api/2/events`;

    while (page <= maxPages) {
      const url = `${apiBase}?pp=50&page=${page}&days=60`;
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
        const nowMs = Date.now();
        let added = 0;
        for (const instWrapper of instances) {
          const inst = instWrapper.event_instance || instWrapper;
          const startIso = inst.start || null;
          if (!startIso) continue;
          // Compare as Date objects, NOT strings — ISO timestamps with different
          // timezone suffixes (e.g. "2026-04-20T10:00:00-04:00" vs "…Z") sort
          // lexicographically in ways that don't reflect wall time.
          const startMs = Date.parse(startIso);
          if (isNaN(startMs) || startMs < nowMs) continue;
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
          const startMsFallback = startIso ? Date.parse(startIso) : NaN;
          if (startIso && !isNaN(startMsFallback) && startMsFallback >= nowMs - 86_400_000) {
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

/**
 * Per-event venue from a Tribe REST event, for AGGREGATOR feeds only.
 * A Tribe aggregator (e.g. a local-news calendar like Waltham Times) names the
 * real venue on every event; attributing those rows to the aggregator hides
 * them from every per-venue surface. For normal single-venue feeds we keep the
 * DB venue row's identity — feed-side name variants ("MFA Boston" vs our
 * "Museum of Fine Arts") would fragment carousels otherwise.
 * Returns null (no override) unless the crawled venue is an aggregator and the
 * event carries a usable venue name. Skips virtual/online pseudo-venues so
 * Zoom meetings stay on the aggregator row instead of minting "Zoom" venues.
 */
function tribeEventVenue(evt, venue) {
  if (!venue.is_aggregator) return null;
  const v = evt.venue && !Array.isArray(evt.venue) ? evt.venue : (Array.isArray(evt.venue) ? evt.venue[0] : null);
  const name = (v && (v.venue || v.name) || "").trim();
  if (!name) return null;
  if (/^(zoom|online|virtual|webinar|tbd|tba)$/i.test(name)) return null;
  return {
    name,
    city: (v.city || "").trim() || null,
    state: (v.state_province || v.state || v.province || "").trim() || null,
    lat: v.geo_lat != null && v.geo_lat !== "" ? v.geo_lat : null,
    lng: v.geo_lng != null && v.geo_lng !== "" ? v.geo_lng : null,
  };
}

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
            _venue: tribeEventVenue(evt, venue),
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
                _venue: tribeEventVenue(evt, venue),
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

// ── Squarespace events crawler ──
//
// Squarespace exposes the entire collection as JSON by appending
// `?format=json` to any collection URL. For event collections, the response
// includes an `upcomingEvents` array with start/end epoch ms, full body
// HTML, and a CDN image URL — no scraping needed.
//
// This unblocks venues like Gore Place, where the Claude HTML fallback was
// only catching whatever fit in the first viewport (~3-4 events) and
// missing the rest of the calendar.

async function crawlSquarespace(venue, baseUrl) {
  try {
    const separator = baseUrl.includes("?") ? "&" : "?";
    const jsonUrl = `${baseUrl}${separator}format=json`;

    const res = await fetch(jsonUrl, {
      headers: { Accept: "application/json", "User-Agent": "Mozilla/5.0" },
    });
    if (!res.ok) {
      console.log(`    Squarespace API error: ${res.status}`);
      return null;
    }

    const data = await res.json();
    // upcomingEvents is what Squarespace returns for event-collection pages.
    // Fall back to items for non-event collections that nonetheless live at
    // the events URL.
    const items = Array.isArray(data.upcomingEvents) && data.upcomingEvents.length
      ? data.upcomingEvents
      : (Array.isArray(data.items) ? data.items : []);
    if (items.length === 0) return null;

    const events = [];
    for (const it of items) {
      const startMs = it.startDate || it.publishOn;
      if (!startMs || !it.title) continue;
      const endMs = it.endDate || null;
      const start_date = new Date(startMs).toISOString();
      const end_date = endMs ? new Date(endMs).toISOString() : null;
      const fullUrl = it.fullUrl
        ? new URL(it.fullUrl, baseUrl).href
        : baseUrl;
      const body = (it.excerpt || it.body || "")
        .replace(/<[^>]+>/g, " ")
        .replace(/\s+/g, " ")
        .trim()
        .slice(0, 300);

      events.push({
        title: it.title,
        start_date,
        end_date,
        description: body || null,
        source_url: fullUrl,
        image_url: it.assetUrl || null,
        is_free: false,
        confidence: 0.95,
      });
    }

    if (events.length === 0) return null;

    const rows = events.map((e) => normalizeEvent(e, venue));
    if (rows.length > 0) await upsertEvents(rows);
    await updateVenueCrawlStatus(venue.id, true, venue.crawl_success_rate);
    return { venue: venue.name, events: rows.length, status: "ok (squarespace)" };
  } catch (err) {
    console.log(`    Squarespace crawl error: ${err.message}`);
    return null;
  }
}

// ── Art Center Canvas crawler (class-registration SPA) ──
//
// Venues on the "Art Center Canvas" platform (e.g. New Art Center,
// canvas.newartcenter.org) render their public catalog as a Vercel-hosted
// React SPA behind an anti-bot security checkpoint — un-scrapeable directly,
// even with Scrapfly JS-render. But the catalog itself is served open (no auth)
// from the platform's storefront API on a separate domain:
//   https://storefront-api-2.artcentercanvas.com/v1/<slug>/classes/open
// The <slug> is the org key (e.g. "newartcenter"), derived from the canvas
// hostname and verified against /classes/count before we trust it — so the
// adapter works for any Art Center Canvas venue, not just this one.
//
// Each catalog item is a class/workshop. We split them by session span:
//   • single-session (one classDay, start==end date) → a discrete event that
//     flows into the normal feed like any other event.
//   • multi-session series (multi-week courses) → emitted as one dated span,
//     defaulted to category "always_on" so isGeneralFeedEvent keeps them out
//     of topical rails (recurring / Experiences lane only).
// classify_events.js still owns final taxonomy.
const ACC_STOREFRONT = "https://storefront-api-2.artcentercanvas.com/v1";

function accSlugFromHost(hostname) {
  // canvas.newartcenter.org → newartcenter
  const h = hostname.replace(/^canvas\./i, "");
  return h.split(".")[0] || null;
}

// "6/4/26" + "6:00 PM" → "2026-06-04T18:00:00" (local, no tz; tagEasternTime
// adds the offset downstream). Returns date-only when time is missing so
// normalizeEvent flags time_known=false.
function accDateTime(dateStr, timeStr) {
  if (!dateStr) return null;
  const m = String(dateStr).match(/^(\d{1,2})\/(\d{1,2})\/(\d{2,4})$/);
  if (!m) return null;
  let [, mo, day, yr] = m;
  if (yr.length === 2) yr = "20" + yr;
  const date = `${yr}-${mo.padStart(2, "0")}-${day.padStart(2, "0")}`;
  if (!timeStr) return date; // date-only → time_known=false downstream
  const t = String(timeStr).trim().match(/^(\d{1,2}):(\d{2})\s*(AM|PM)$/i);
  if (!t) return date;
  let hh = parseInt(t[1], 10);
  const mer = t[3].toUpperCase();
  if (mer === "PM" && hh !== 12) hh += 12;
  if (mer === "AM" && hh === 12) hh = 0;
  return `${date}T${String(hh).padStart(2, "0")}:${t[2]}:00`;
}

async function crawlArtCenterCanvas(venue, baseUrl) {
  try {
    const origin = new URL(baseUrl).origin;
    const slug = accSlugFromHost(new URL(baseUrl).hostname);
    if (!slug) return null;
    const api = `${ACC_STOREFRONT}/${slug}`;
    // The storefront API gates JSON on a same-site Origin/Referer (matching the
    // SPA's own requests); without them it returns a non-array error body.
    const headers = {
      Accept: "application/json",
      "User-Agent": "Mozilla/5.0",
      Origin: origin,
      Referer: `${origin}/`,
    };

    // Verify this is really an Art Center Canvas org before trusting the slug —
    // keeps the adapter from claiming any unrelated canvas.* host.
    const countRes = await fetch(`${api}/classes/count`, { headers });
    if (!countRes.ok) return null;
    const countJson = await countRes.json().catch(() => null);
    if (!countJson?.ok) return null;

    const res = await fetch(`${api}/classes/open?skipCache=1`, { headers });
    if (!res.ok) {
      console.log(`    Art Center Canvas API error: ${res.status}`);
      return null;
    }
    const items = await res.json();
    if (!Array.isArray(items) || items.length === 0) return null;

    const events = [];
    for (const it of items) {
      if (!it.title || it.cancelled) continue;
      const sessions = Array.isArray(it.classDays) ? it.classDays.length : 0;
      const isSingle = sessions <= 1 && it.startDate === it.endDate;

      const start_date = accDateTime(it.startDate, it.startTime);
      if (!start_date) continue;
      const end_date = accDateTime(it.endDate || it.startDate, it.endTime);

      const prices = [it.costMember, it.costNon].filter((p) => typeof p === "number");
      const price_min = prices.length ? Math.min(...prices) : null;
      const price_max = prices.length ? Math.max(...prices) : null;
      const is_free = prices.length > 0 && price_max === 0;

      const descr = (it.descr || "").replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();

      events.push({
        title: it.title,
        start_date,
        end_date: end_date || null,
        description: descr || null,
        source_url: `${origin}/classes/${it.catalogId}`,
        is_free,
        price_min,
        price_max,
        // Multi-week courses are recurring offerings, not one-off events —
        // default them out of the topical feed. classify_events.js may refine.
        seriesCategory: isSingle ? null : "always_on",
        confidence: 0.9,
      });
    }

    if (events.length === 0) return null;

    const rows = events.map((e) => {
      const row = normalizeEvent(e, venue);
      if (e.seriesCategory) row.category = e.seriesCategory;
      return row;
    });
    await upsertEvents(rows);
    await updateVenueCrawlStatus(venue.id, true, venue.crawl_success_rate);
    const seriesCount = events.filter((e) => e.seriesCategory).length;
    const discreteCount = rows.length - seriesCount;
    return {
      venue: venue.name,
      events: rows.length,
      status: `ok (art-center-canvas: ${discreteCount} events, ${seriesCount} series)`,
    };
  } catch (err) {
    console.log(`    Art Center Canvas crawl error: ${err.message}`);
    return null;
  }
}

// ── TicketWeb tw-calendar crawler (Middle East, etc.) ──
//
// TicketWeb's WordPress widget renders a FullCalendar shell ("Calendar
// Loading…") and fetches each month's shows via an authenticated AJAX POST to
// /wp-admin/admin-ajax.php (action=get_events_artist_calendar). A normal crawl
// — even with Scrapfly JS-render — only ever sees the *current* month, so the
// venue silently drains as shows age out. This adapter scrapes the page-level
// nonce + the widget's params template, then walks N months forward, POSTing
// the same request the browser would, to pull the full forward catalog.
//
// The POST is routed through Scrapfly (postForm) because these venues sit
// behind Cloudflare — a direct admin-ajax POST 403s.
const TICKETWEB_MONTHS_AHEAD = 4;

async function crawlTicketWeb(venue, baseUrl, preFetchedHtml = null) {
  try {
    const origin = new URL(baseUrl).origin;

    // Reuse the page the dispatcher already fetched when available; otherwise
    // pull it (stealth venues must go through Scrapfly). Either way we just
    // need the inline <script> with my_ajax_object + the params template.
    const pageHtml = preFetchedHtml || (await fetchPage(baseUrl, { stealth: !!venue.needs_stealth }));

    const ajaxMatch = pageHtml.match(/my_ajax_object\s*=\s*(\{[^}]*\})/);
    const paramsMatch = pageHtml.match(/params:\s*'(\{.*?\})'\s*,\s*\n\s*monthview/s);
    if (!ajaxMatch || !paramsMatch) {
      console.log(`    TicketWeb markers not found for ${venue.name} — falling through`);
      return null;
    }

    let ajaxObj, paramsTemplate;
    try {
      ajaxObj = JSON.parse(ajaxMatch[1]);
      paramsTemplate = JSON.parse(paramsMatch[1]);
    } catch (e) {
      console.log(`    TicketWeb parse error for ${venue.name}: ${e.message}`);
      return null;
    }
    const ajaxUrl = ajaxObj.ajax_url || `${origin}/wp-admin/admin-ajax.php`;
    const nonce = ajaxObj.nonce;
    if (!nonce) {
      console.log(`    TicketWeb nonce missing for ${venue.name} — falling through`);
      return null;
    }

    const byKey = new Map(); // dedupe across months
    const now = new Date();

    for (let i = 0; i < TICKETWEB_MONTHS_AHEAD; i++) {
      const d = new Date(now.getFullYear(), now.getMonth() + i, 1);
      const year = d.getFullYear();
      const month = d.getMonth() + 1; // 1-indexed
      const lastDay = new Date(year, month, 0).getDate();
      const mm = String(month).padStart(2, "0");
      const start = `${year}-${mm}-01 00:00:00`;
      const end = `${year}-${mm}-${String(lastDay).padStart(2, "0")} 23:59:59`;

      const monthParams = { ...paramsTemplate, month: String(month), year: String(year) };

      const formBody = new URLSearchParams({
        action: "get_events_artist_calendar",
        nonce,
        start,
        end,
        params: JSON.stringify(monthParams),
        monthview: "standard",
        calcount: "1",
        statefilter: "",
        cityfilter: "",
        locationfilter: "",
        showmobilelist: "true",
      }).toString();

      let json;
      try {
        const { body } = await scrapflyPostForm(ajaxUrl, {
          scrapflyKey: SCRAPFLY_API_KEY,
          formBody,
          headers: { Referer: baseUrl },
          timeoutMs: 45_000,
        });
        json = JSON.parse(body);
      } catch (e) {
        console.log(`    TicketWeb ${year}-${mm} fetch error for ${venue.name}: ${e.message}`);
        continue;
      }

      const events = Array.isArray(json.events) ? json.events : [];
      for (const ev of events) {
        if (!ev || !ev.title) continue;
        // FullCalendar start can be "YYYY-MM-DD HH:MM:SS" or ISO — normalize
        // the space separator so tagEasternTime parses it.
        let startStr = typeof ev.start === "string" ? ev.start.trim() : "";
        startStr = startStr.replace(/^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2})/, "$1T$2");
        if (!startStr) continue;

        const props = ev.extendedProps || {};
        const sourceUrl = ev.url || props.url || null;
        const key = (sourceUrl || `${ev.title}::${startStr}`).toLowerCase();
        if (byKey.has(key)) continue;

        byKey.set(key, {
          title: ev.title,
          start_date: startStr,
          end_date: typeof ev.end === "string" ? ev.end.replace(/^(\d{4}-\d{2}-\d{2}) /, "$1T") : null,
          description: null,
          source_url: sourceUrl,
          ticket_url: sourceUrl,
          image_url: props.imageUrl ? props.imageUrl.replace(/<[^>]+>/g, "").trim() || null : null,
          is_free: false,
          confidence: 0.9,
        });
      }

      await new Promise((r) => setTimeout(r, 300)); // be polite to admin-ajax
    }

    const rows = [...byKey.values()].map((e) => normalizeEvent(e, venue));
    if (rows.length === 0) {
      console.log(`    TicketWeb returned 0 events for ${venue.name}`);
      return null; // let the HTML fallback have a shot rather than report success
    }
    await upsertEvents(rows);
    await updateVenueCrawlStatus(venue.id, true, venue.crawl_success_rate);
    return { venue: venue.name, events: rows.length, status: "ok (ticketweb)" };
  } catch (err) {
    console.log(`    TicketWeb crawl error: ${err.message}`);
    return null;
  }
}

// ── Platform detection helper ──

async function detectAndCrawlPlatform(venue) {
  const url = venue.events_url;

  // 1. BiblioCommons
  const biblioMatch = url.match(/(\w+)\.bibliocommons\.com/);
  if (biblioMatch) return await crawlBiblioCommons(venue, biblioMatch[1]);

  // 1b. National Park Service — any nps.gov URL routes through the NPS API.
  if (/(^|\.)nps\.gov\//i.test(url)) {
    return await crawlNPS(venue, url);
  }

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

    // 3c. Art Center Canvas (class-registration SPA, e.g. New Art Center).
    // The public site is an anti-bot-walled React SPA, but the catalog is
    // served open from the storefront API. crawlArtCenterCanvas self-verifies
    // the slug, so a false-positive canvas.* host just falls through to null.
    if (hostname.startsWith("canvas.") || hostname.includes("artcentercanvas")) {
      const accResult = await crawlArtCenterCanvas(venue, url);
      if (accResult) return accResult;
    }
  } catch (_) {}

  // 3. Try WordPress API (detect from HTML or try wp-json)
  // We'll try this after HTML fetch below

  // 4. Drupal — detect from HTML markers
  // We'll try this after HTML fetch below

  return null; // No platform detected pre-fetch
}

// ── Date-token expansion for month-paginated URLs ──

const DATE_TOKEN_RE = /\{(YYYY-MM|YYYY\/MM|YYYY|YY|MMMM|MMM|mmmm|mmm|MM|M|DD|D)\}/;
const DATE_MONTHS_AHEAD = parseInt(process.env.URL_DATE_MONTHS_AHEAD || "2", 10);

function applyDateTokens(url, date) {
  const yyyy = String(date.getFullYear());
  const yy = yyyy.slice(2);
  const mm = String(date.getMonth() + 1).padStart(2, "0");
  const m = String(date.getMonth() + 1);
  const dd = String(date.getDate()).padStart(2, "0");
  const d = String(date.getDate());
  const monthShort = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"][date.getMonth()];
  const monthLong = ["January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December"][date.getMonth()];
  return url
    .replace(/\{YYYY-MM\}/g, `${yyyy}-${mm}`)
    .replace(/\{YYYY\/MM\}/g, `${yyyy}/${mm}`)
    .replace(/\{YYYY\}/g, yyyy)
    .replace(/\{YY\}/g, yy)
    .replace(/\{MMMM\}/g, monthLong)
    .replace(/\{MMM\}/g, monthShort)
    .replace(/\{mmmm\}/g, monthLong.toLowerCase())
    .replace(/\{mmm\}/g, monthShort.toLowerCase())
    .replace(/\{MM\}/g, mm)
    .replace(/\{M\}/g, m)
    .replace(/\{DD\}/g, dd)
    .replace(/\{D\}/g, d);
}

function expandDateTokens(url) {
  if (!DATE_TOKEN_RE.test(url)) return [url];
  const today = new Date();
  const out = [];
  for (let offset = 0; offset <= DATE_MONTHS_AHEAD; offset++) {
    const d = new Date(today.getFullYear(), today.getMonth() + offset, 1);
    out.push(applyDateTokens(url, d));
  }
  return [...new Set(out)];
}

// ── Crawl a single venue ──

async function crawlVenue(venue) {
  if (!venue.events_url) {
    return { venue: venue.name, events: 0, status: "skipped", reason: "no events_url" };
  }

  // Support multiple URLs separated by commas, plus date-token expansion for
  // month-paginated sites (TEC, custom calendars, etc.). Tokens supported:
  //   {YYYY}  4-digit year       {YY}    2-digit year
  //   {MM}    zero-padded month  {M}     un-padded month
  //   {MMMM}  full month name    {MMM}   abbreviated month name
  //   {mmmm}/{mmm}               lowercase variants
  //   {DD}    zero-padded day    {D}     un-padded day
  //   {YYYY-MM} / {YYYY/MM}      common combined forms
  // Each token-bearing URL expands to current month + next 2 months. So a row
  // like "https://brooklinebooksmith.com/events/{YYYY}/{MM}" stays valid
  // forever instead of rotting when the month changes.
  const rawUrls = venue.events_url.split(",").map((u) => u.trim()).filter(Boolean);
  const urls = [...new Set(rawUrls.flatMap(expandDateTokens))];
  if (urls.length > 1) {
    const mergedRows = [];
    let anySuccess = false;
    for (const url of urls) {
      const subVenue = { ...venue, events_url: url };
      const result = await crawlVenue(subVenue);
      if (result.status?.startsWith("ok")) anySuccess = true;
      // Collect the actual rows from each sub-URL — not just the count. Without
      // this the caller's `results.flatMap(r => r.rows)` gets nothing for any
      // multi-URL / date-token venue, so everything crawled here is silently
      // dropped before the upsert (Brookline Booksmith extracted 59, saved 0).
      if (result.rows?.length) mergedRows.push(...result.rows);
    }
    await updateVenueCrawlStatus(venue.id, anySuccess, venue.crawl_success_rate);
    return {
      venue: venue.name,
      events: mergedRows.length,
      rows: mergedRows,
      status: anySuccess ? "ok (multi-url)" : "error",
    };
  }

  try {
    // Try platform-specific API crawlers first
    const platformResult = await detectAndCrawlPlatform(venue);
    if (platformResult) return platformResult;

    // Fetch HTML — venues flagged needs_stealth (Squarespace SPAs, Cloudflare-
    // protected sites, etc.) go through Scrapfly with JS rendering so the event
    // list actually appears in the response.
    const rawHtml = await fetchPage(venue.events_url, { stealth: !!venue.needs_stealth });
    const cleanedHtml = stripHtml(rawHtml);

    if (cleanedHtml.length < 100) {
      await updateVenueCrawlStatus(venue.id, false, venue.crawl_success_rate);
      return { venue: venue.name, events: 0, status: "empty", reason: "page too short" };
    }

    // Direct feed URL: some venues expose a clean events feed that isn't
    // advertised via a <link rel=alternate> in any HTML <head> (e.g. a WordPress
    // /events/feed/). If events_url points straight at an RSS/Atom document,
    // parse it directly instead of hunting for a feed link in a page head.
    // (An XHTML page can also start with <?xml; harmless — parseFeedItems finds
    // no <item>/<entry>, returns null, and we fall through to normal detection.)
    const rawTrimmed = rawHtml.trimStart();
    if (rawTrimmed.startsWith("<?xml") || /^<(rss|feed)\b/i.test(rawTrimmed)) {
      const directFeed = await crawlFeedXml(venue, rawHtml, venue.events_url);
      if (directFeed) return directFeed;
    }

    // Post-fetch platform detection — try structured APIs before Claude
    const htmlLc = rawHtml.toLowerCase();

    // TicketWeb tw-calendar (Middle East, etc.) — must run BEFORE the generic
    // WordPress/Tribe path, since these are WP sites that would otherwise be
    // routed to the (nonexistent) Tribe endpoint and return nothing.
    if (htmlLc.includes("get_events_artist_calendar") || htmlLc.includes("tw-plugin-calendar")) {
      const twResult = await crawlTicketWeb(venue, venue.events_url, rawHtml);
      if (twResult) return twResult;
    }

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

    // Squarespace detection — both 7.0 and 7.1 sites embed these markers.
    // The ?format=json adapter pulls the entire upcomingEvents collection,
    // which beats the viewport-limited Claude HTML fallback.
    if (htmlLc.includes("static.squarespace.com") ||
        htmlLc.includes("static1.squarespace.com") ||
        htmlLc.includes("squarespace.com/universal") ||
        htmlLc.includes("usesquarespace")) {
      const sqsResult = await crawlSquarespace(venue, venue.events_url);
      if (sqsResult) return sqsResult;
    }

    // Tessitura detection
    if (htmlLc.includes("tessitura") || htmlLc.includes("tnew") || htmlLc.includes("tessituraservice")) {
      console.log(`    Tessitura detected for ${venue.name} — using HTML crawl`);
    }

    // Generic RSS/Atom feed — sites that advertise a feed in <head> almost always
    // have cleaner, more parseable event data than their HTML (Gore Place,
    // Common Street, etc.). Try this before JSON-LD / Claude.
    if (htmlLc.includes('type="application/rss+xml"') || htmlLc.includes('type="application/atom+xml"') ||
        htmlLc.includes("type='application/rss+xml'") || htmlLc.includes("type='application/atom+xml'")) {
      const rssResult = await crawlRssFeed(venue, rawHtml);
      if (rssResult && rssResult.events > 0) {
        console.log(`    ✓ RSS extracted ${rssResult.events} events for ${venue.name}`);
        return rssResult;
      }
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

    // Upsert inline (per-venue) — see the RSS path above. Persist as we go so a
    // cancelled/timed-out run never loses a buffered end-of-run batch.
    if (rows.length > 0) await upsertEvents(rows);
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

  // Hard wall-clock budget (see MAX_MINUTES). Once hit, workers stop claiming
  // new venues; in-flight crawls finish and we upsert what we have. Infinity
  // when no budget is set.
  const deadlineMs = MAX_MINUTES > 0 ? Date.now() + MAX_MINUTES * 60_000 : Infinity;
  let budgetHit = false;

  async function worker() {
    while (index < venues.length) {
      if (Date.now() >= deadlineMs) {
        budgetHit = true;
        break;
      }
      const i = index++;
      const venue = venues[i];
      console.log(`  [${i + 1}/${venues.length}] ${venue.name} (${venue.category})...`);
      const result = await crawlVenue(venue);

      // Second-pass description enrichment — hit each event's source_url
      // and pull description from JSON-LD / og:description / <meta name="description">.
      // Applies to every crawl path uniformly.
      if (result.rows && result.rows.length > 0) {
        const before = result.rows.filter((r) => (r.description || "").length >= 40).length;
        result.rows = await enrichDescriptions(result.rows);
        const after = result.rows.filter((r) => (r.description || "").length >= 40).length;
        if (after > before) {
          console.log(`    ✨ Enriched ${after - before}/${result.rows.length} descriptions via source_url`);
        }
      }

      if (result.status?.startsWith("ok")) {
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

  if (budgetHit) {
    const remaining = venues.length - index;
    console.log(
      `\n  ⏱  CRAWL_MAX_MINUTES (${MAX_MINUTES}m) budget reached — crawled ${results.length}, ` +
        `${remaining} venue(s) deferred to next run (they remain the stalest, so they go first then).`,
    );
  }

  return results;
}

// ── Main ──

async function main() {
  const secrets = await getSecrets(["ANTHROPIC_API_KEY", "SCRAPFLY_API_KEY"]);
  ANTHROPIC_API_KEY = secrets.ANTHROPIC_API_KEY;
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;
  SCRAPFLY_API_KEY = secrets.SCRAPFLY_API_KEY || null;
  console.log(`Scrapfly: ${SCRAPFLY_API_KEY ? "available — needs_stealth venues will JS-render" : "NOT configured — needs_stealth venues will fall back to direct fetch"}`);

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
    // Platform adapters (Tribe/Localist/BiblioCommons/…) upsert their events
    // INLINE and report only a count — they never hand rows back to this loop.
    // An adapter-only run therefore has zero allRows while having persisted
    // everything; saying "No events extracted" here was a lie that repeatedly
    // sent operators hunting for a failure that didn't exist.
    const adapterEvents = results.reduce((s, r) => s + (r?.events || 0), 0);
    if (adapterEvents > 0) {
      console.log(`\nAll ${adapterEvents} events were extracted and upserted inline by platform adapters. Done.`);
    } else {
      console.log("\nNo events extracted. Check venue URLs and try again.");
    }
    return;
  }

  // 3b. Save to disk so we can re-upsert without re-crawling
  const fs = require("fs");
  const outPath = `${__dirname}/crawl_results.json`;
  fs.writeFileSync(outPath, JSON.stringify(allRows, null, 2));
  console.log(`\nSaved ${allRows.length} events to ${outPath}`);

  // 4. All rows were already upserted inline per-venue as they were crawled
  //    (see the upsertEvents calls in each path). Nothing to flush here — that
  //    is what lets a cancelled/timed-out run keep everything it persisted
  //    instead of losing a whole buffered batch (why NY read ~86% zero).
  //    allRows is retained only for the summary/backup above.
  const inserted = allRows.length;
  const errors = 0;

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

// Only auto-run when invoked directly — allows other scripts to require()
// helper functions (enrichDescriptions, extractDescriptionFromHtml) without
// kicking off the full crawler.
if (require.main === module) {
  main().catch((err) => {
    console.error("Fatal error:", err);
    process.exit(1);
  });
}
