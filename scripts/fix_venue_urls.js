/**
 * fix_venue_urls.js — Discover real events page URLs by reading venue homepages with Claude
 *
 * For each venue with a broken/missing events_url:
 *   1. Fetch the venue's main website homepage
 *   2. Pass HTML to Claude to find the real events/calendar/shows page link
 *   3. Verify the discovered URL returns 200
 *   4. Update venues.events_url in Supabase (or mark is_active=false if no events page found)
 *
 * Usage:
 *   ANTHROPIC_API_KEY=sk-... SUPABASE_SERVICE_KEY=ey... node scripts/fix_venue_urls.js
 *
 * Optional:
 *   METRO=boston            — metro area (default: boston)
 *   DRY_RUN=1              — log changes without writing to Supabase
 *   CONCURRENCY=3          — parallel operations (default: 3)
 *   RECHECK_ALL=1          — recheck all venues, not just broken ones
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

const MODEL = "claude-haiku-4-5-20251001";

let ANTHROPIC_API_KEY, SUPABASE_SERVICE_KEY;

const METRO = process.env.METRO || "boston";
const DRY_RUN = process.env.DRY_RUN === "1";
const CONCURRENCY = parseInt(process.env.CONCURRENCY || "3", 10);
const RECHECK_ALL = process.env.RECHECK_ALL === "1";
// Skip the serial HTTP pre-check of venues that already have an events_url and
// only process the ones missing it. See the candidate-selection block below.
const SKIP_URL_CHECK = process.env.SKIP_URL_CHECK === "1";
// Optional state scope (e.g. STATE=NJ). Limits the venue fetch to one state so a
// new-state run can't touch — or deactivate — venues in already-live states.
const STATE = (process.env.STATE || "").trim().toUpperCase() || null;
// Comma-separated city list to scope to a subset within STATE (or globally if
// no STATE set). Empty = all cities. Useful for onboarding runs that should
// only touch the venues we just inserted, not the full long-tail of an entire
// state's Google-discovered rows.
const CITIES = (process.env.CITIES || "").split(",").map((c) => c.trim()).filter(Boolean);
// ISO timestamp (e.g. "2026-06-25") — only process venues created on/after.
// Use for onboarding the most-recently-added venues without touching anything
// older.
const CREATED_AFTER = (process.env.CREATED_AFTER || "").trim() || null;
// When set, never flip is_active=false. For freshly Google-discovered states the
// default deactivate-on-no-events-page behavior nukes legit venues (bowling,
// wineries, diners) that simply lack an events calendar. NO_DEACTIVATE keeps any
// events_url we DO discover while leaving every venue active.
const NO_DEACTIVATE = process.env.NO_DEACTIVATE === "1";

const FETCH_HEADERS = {
  "User-Agent":
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
  Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
  "Accept-Language": "en-US,en;q=0.5",
};

// ── Fetch page with timeout, with Scrapfly fallback for Cloudflare-blocked sites ──
//
// Direct fetch was deactivating thousands of legit venues every monthly run
// because Cloudflare returns 403 / blocks the connection on default UA. Now
// when the direct fetch fails (network error, 403, 503, 1020), retry through
// Scrapfly — same trick the municipal claude_html parser uses.

let SCRAPFLY_API_KEY = null;
async function loadScrapflyKey() {
  if (SCRAPFLY_API_KEY !== null) return;
  try {
    const { getSecrets } = require("./lib/secrets");
    const s = await getSecrets(["SCRAPFLY_API_KEY"]);
    SCRAPFLY_API_KEY = s.SCRAPFLY_API_KEY || "";
  } catch { SCRAPFLY_API_KEY = ""; }
}

async function scrapflyFetch(url, timeoutMs = 30_000) {
  if (!SCRAPFLY_API_KEY) return null;
  const params = new URLSearchParams({
    key: SCRAPFLY_API_KEY,
    url,
    country: "us",
    asp: "true",
    render_js: "false",
  });
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(`https://api.scrapfly.io/scrape?${params}`, {
      signal: controller.signal,
    });
    clearTimeout(timeout);
    if (!res.ok) return null;
    const json = await res.json();
    const html = json.result?.content;
    const status = json.result?.status_code ?? (html ? 200 : 0);
    if (!html || status >= 400) return null;
    return { status, ok: true, html };
  } catch {
    clearTimeout(timeout);
    return null;
  }
}

async function fetchPage(url, timeoutMs = 15_000) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      headers: FETCH_HEADERS,
      signal: controller.signal,
      redirect: "follow",
    });
    clearTimeout(timeout);
    if (res.ok) return { status: res.status, ok: true, html: await res.text() };
    // Cloudflare typically returns 403 / 503 / 1020 on bot detection. Retry via Scrapfly.
    if ([403, 429, 503, 520, 1020].includes(res.status)) {
      await loadScrapflyKey();
      const sf = await scrapflyFetch(url, timeoutMs * 2);
      if (sf) return sf;
    }
    return { status: res.status, ok: false, html: null };
  } catch (err) {
    clearTimeout(timeout);
    // Network-level failure (timeout, ECONNRESET, EAI_AGAIN). Often Cloudflare
    // killing the connection. Try Scrapfly before giving up.
    await loadScrapflyKey();
    const sf = await scrapflyFetch(url, timeoutMs * 2);
    if (sf) return sf;
    return { status: 0, ok: false, html: null, error: err.message };
  }
}

// ── Check if a URL returns 200 ──

async function urlReturns200(url) {
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 10_000);
    const res = await fetch(url, {
      method: "HEAD",
      headers: FETCH_HEADERS,
      signal: controller.signal,
      redirect: "follow",
    });
    clearTimeout(timeout);
    // Some servers don't support HEAD, try GET if we get 405
    if (res.status === 405) {
      const getRes = await fetchPage(url, 10_000);
      return getRes.ok;
    }
    return res.ok;
  } catch {
    return false;
  }
}

// ── Strip HTML to preserve links for Claude ──

function stripHtmlKeepLinks(html) {
  let cleaned = html
    .replace(/<script[\s\S]*?<\/script>/gi, "")
    .replace(/<style[\s\S]*?<\/style>/gi, "")
    .replace(/<svg[\s\S]*?<\/svg>/gi, "");

  // Keep anchor tags with href visible
  cleaned = cleaned
    .replace(/<a[^>]*href="([^"]*)"[^>]*>([\s\S]*?)<\/a>/gi, '[LINK: $1 TEXT: $2] ')
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&#\d+;/g, " ")
    .replace(/&[a-z]+;/gi, " ")
    .replace(/\s{2,}/g, " ")
    .trim();

  // Limit size — Claude only needs navigation links, not full page content
  if (cleaned.length > 50_000) {
    cleaned = cleaned.slice(0, 50_000) + "\n\n[TRUNCATED]";
  }
  return cleaned;
}

// ── Ask Claude to find the events URL from homepage HTML ──

async function askClaudeForEventsUrl(venue, homepageHtml) {
  const userPrompt = `Here is the homepage HTML (with links preserved) for the venue "${venue.name}" (${venue.website}).

Find the URL of the events, calendar, or shows page on this website. Look for navigation links, footer links, or any link containing words like: events, calendar, shows, tickets, whats-on, taproom-events, schedule, upcoming, performances, concerts, programming, exhibitions, on-tap, lineup, gigs.

If the URL is a relative path (e.g. /events), combine it with the base URL ${venue.website} to return a full absolute URL.

If you cannot find any events/calendar page link, respond with exactly: NONE

Otherwise respond with just the URL, nothing else.

Homepage content:

${homepageHtml}`;

  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "x-api-key": ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
      "content-type": "application/json",
    },
    body: JSON.stringify({
      model: MODEL,
      max_tokens: 256,
      messages: [{ role: "user", content: userPrompt }],
    }),
  });

  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Claude API ${res.status}: ${body.slice(0, 200)}`);
  }

  const data = await res.json();
  const text = (data.content?.[0]?.text || "").trim();

  if (text === "NONE" || text.length === 0) return null;

  // Extract URL from response (Claude might add extra text despite instructions)
  const urlMatch = text.match(/https?:\/\/[^\s"'<>]+/);
  return urlMatch ? urlMatch[0] : null;
}

// ── Fetch venues that need URL fixing ──

async function fetchVenuesNeedingFix() {
  let baseUrl = `${SUPABASE_URL}/rest/v1/venues?select=*`;
  if (!RECHECK_ALL) {
    baseUrl += "&is_active=eq.true";
  }
  if (STATE) {
    baseUrl += `&state=eq.${encodeURIComponent(STATE)}`;
  }
  if (CITIES.length) {
    const quoted = CITIES.map((c) => `"${c.replace(/"/g, '\\"')}"`).join(",");
    baseUrl += `&city=in.(${encodeURIComponent(quoted)})`;
  }
  if (CREATED_AFTER) baseUrl += `&created_at=gte.${encodeURIComponent(CREATED_AFTER)}`;
  // Highest-public-interest venues first so a bounded budget never starves
  // flagship venues. Newly-discovered rows (rank=NULL) sort last.
  baseUrl += "&order=public_interest_rank.desc.nullslast,name.asc";

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

// ── Update venue in Supabase ──

async function updateVenue(venueId, updates) {
  if (DRY_RUN) return;

  const url = `${SUPABASE_URL}/rest/v1/venues?id=eq.${venueId}`;
  const res = await fetch(url, {
    method: "PATCH",
    headers: {
      apikey: SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(updates),
  });

  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Supabase PATCH error: ${res.status} ${body}`);
  }
}

// ── Fix a single venue ──

async function fixVenue(venue) {
  // Skip venues without a website — we can't discover anything
  if (!venue.website) {
    return { name: venue.name, status: "skip", reason: "no website" };
  }

  // Step 1: Check if current events_url already works
  if (venue.events_url) {
    const works = await urlReturns200(venue.events_url);
    if (works && !RECHECK_ALL) {
      return { name: venue.name, status: "ok", reason: "existing URL works", url: venue.events_url };
    }
    if (works) {
      return { name: venue.name, status: "ok", reason: "existing URL verified", url: venue.events_url };
    }
    // Current events_url is broken — proceed to fix
  }

  // Step 2: Fetch venue's main website homepage
  const homepage = await fetchPage(venue.website);
  if (!homepage.ok) {
    // Website itself is down — mark inactive (unless NO_DEACTIVATE).
    if (NO_DEACTIVATE) {
      return { name: venue.name, status: "kept", reason: `website returned ${homepage.status || homepage.error} (kept active)` };
    }
    await updateVenue(venue.id, { is_active: false });
    return { name: venue.name, status: "deactivated", reason: `website returned ${homepage.status || homepage.error}` };
  }

  const cleanedHtml = stripHtmlKeepLinks(homepage.html);
  if (cleanedHtml.length < 50) {
    if (NO_DEACTIVATE) {
      return { name: venue.name, status: "kept", reason: "empty page (kept active)" };
    }
    await updateVenue(venue.id, { is_active: false });
    return { name: venue.name, status: "deactivated", reason: "website returned empty page" };
  }

  // Step 3: Ask Claude to find the events URL
  let discoveredUrl;
  try {
    discoveredUrl = await askClaudeForEventsUrl(venue, cleanedHtml);
  } catch (err) {
    return { name: venue.name, status: "error", reason: `Claude error: ${err.message}` };
  }

  if (!discoveredUrl) {
    // Claude couldn't find an events page
    if (NO_DEACTIVATE) {
      return { name: venue.name, status: "kept", reason: "no events page found (kept active)" };
    }
    await updateVenue(venue.id, { is_active: false, events_url: null });
    return { name: venue.name, status: "deactivated", reason: "no events page found by Claude" };
  }

  // Step 4: Verify the discovered URL returns 200
  const valid = await urlReturns200(discoveredUrl);
  if (!valid) {
    // Try once more with GET instead of HEAD
    const getCheck = await fetchPage(discoveredUrl, 10_000);
    if (!getCheck.ok) {
      if (NO_DEACTIVATE) {
        return { name: venue.name, status: "kept", reason: `discovered URL 404, kept active: ${discoveredUrl}` };
      }
      await updateVenue(venue.id, { is_active: false, events_url: null });
      return { name: venue.name, status: "deactivated", reason: `discovered URL 404: ${discoveredUrl}` };
    }
  }

  // Step 5: Save the working URL
  await updateVenue(venue.id, { events_url: discoveredUrl, is_active: true });
  return { name: venue.name, status: "fixed", url: discoveredUrl };
}

// ── Concurrency control ──

async function processWithConcurrency(venues, concurrency) {
  const results = [];
  let index = 0;

  async function worker() {
    while (index < venues.length) {
      const i = index++;
      const venue = venues[i];
      console.log(`  [${i + 1}/${venues.length}] ${venue.name}...`);

      const result = await fixVenue(venue);
      const icon = result.status === "fixed" ? "+" :
                   result.status === "ok" ? "=" :
                   result.status === "deactivated" ? "x" :
                   result.status === "skip" ? "-" : "!";
      console.log(`    [${icon}] ${result.status}: ${result.reason || result.url || ""}`);

      results.push(result);

      // Brief pause for rate limiting
      await new Promise((r) => setTimeout(r, 500));
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

  console.log("=== Venue URL Discovery ===\n");
  console.log(`Metro: ${METRO}`);
  console.log(`Model: ${MODEL}`);
  console.log(`Concurrency: ${CONCURRENCY}`);
  console.log(`Dry run: ${DRY_RUN}`);
  console.log(`Recheck all: ${RECHECK_ALL}\n`);

  // Fetch all venues
  console.log("Fetching venues from Supabase...");
  const allVenues = await fetchVenuesNeedingFix();
  console.log(`  Total venues: ${allVenues.length}`);

  // Filter to venues that need fixing (broken or missing events_url)
  let candidates;
  if (RECHECK_ALL) {
    candidates = allVenues.filter((v) => v.website);
  } else if (SKIP_URL_CHECK) {
    // Greenfield/resume mode: skip the serial HTTP pre-check of venues that
    // ALREADY have an events_url — just fill the ones missing it. That pre-check
    // takes ~1h on a 27k-venue state and, because long runs here keep getting
    // killed by environment blips, the hour is re-paid on every restart for zero
    // forward progress. SKIP_URL_CHECK=1 jumps straight to the events_url=NULL
    // venues. (A periodic RECHECK_ALL run can re-validate existing URLs.)
    candidates = allVenues.filter((v) => v.website && !v.events_url);
    console.log(`\nSKIP_URL_CHECK: filling ${candidates.length} venues with no events_url (existing URLs untouched).`);
  } else {
    // Check which events_urls are broken by testing them
    console.log("\nChecking existing events_urls...");
    const needsFix = [];
    const working = [];

    for (const v of allVenues) {
      if (!v.website) continue;
      if (!v.events_url) {
        needsFix.push(v);
        continue;
      }
      const ok = await urlReturns200(v.events_url);
      if (ok) {
        working.push(v);
      } else {
        needsFix.push(v);
      }
    }

    console.log(`  Working events_url: ${working.length}`);
    console.log(`  Broken/missing events_url: ${needsFix.length}`);
    candidates = needsFix;
  }

  const noWebsite = allVenues.filter((v) => !v.website).length;
  console.log(`  No website at all: ${noWebsite}`);

  if (candidates.length === 0) {
    console.log("\nAll venue URLs are working. Nothing to fix.");
    return;
  }

  console.log(`\nProcessing ${candidates.length} venues...\n`);
  const results = await processWithConcurrency(candidates, CONCURRENCY);

  // Summary
  const fixed = results.filter((r) => r.status === "fixed");
  const ok = results.filter((r) => r.status === "ok");
  const deactivated = results.filter((r) => r.status === "deactivated");
  const skipped = results.filter((r) => r.status === "skip");
  const errors = results.filter((r) => r.status === "error");

  console.log("\n=== Summary ===");
  console.log(`  Fixed (new URL found):  ${fixed.length}`);
  console.log(`  Already working:        ${ok.length}`);
  console.log(`  Deactivated:            ${deactivated.length}`);
  console.log(`  Skipped (no website):   ${skipped.length}`);
  console.log(`  Errors:                 ${errors.length}`);

  if (fixed.length > 0) {
    console.log("\n  Newly discovered URLs:");
    fixed.forEach((r) => console.log(`    ${r.name}: ${r.url}`));
  }

  if (deactivated.length > 0) {
    console.log("\n  Deactivated venues:");
    deactivated.forEach((r) => console.log(`    ${r.name}: ${r.reason}`));
  }

  if (errors.length > 0) {
    console.log("\n  Errors:");
    errors.forEach((r) => console.log(`    ${r.name}: ${r.reason}`));
  }

  if (DRY_RUN) {
    console.log("\n  (DRY RUN — no changes written to Supabase)");
  }
}

main().catch((err) => {
  console.error("Fatal error:", err);
  process.exit(1);
});
