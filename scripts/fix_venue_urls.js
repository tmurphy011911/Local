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

const MODEL = "claude-sonnet-4-20250514";

let ANTHROPIC_API_KEY, SUPABASE_SERVICE_KEY;

const METRO = process.env.METRO || "boston";
const DRY_RUN = process.env.DRY_RUN === "1";
const CONCURRENCY = parseInt(process.env.CONCURRENCY || "3", 10);
const RECHECK_ALL = process.env.RECHECK_ALL === "1";

const FETCH_HEADERS = {
  "User-Agent":
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
  Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
  "Accept-Language": "en-US,en;q=0.5",
};

// ── Fetch page with timeout ──

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
    return { status: res.status, ok: res.ok, html: res.ok ? await res.text() : null };
  } catch (err) {
    clearTimeout(timeout);
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
  baseUrl += "&order=name.asc";

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
    // Website itself is down — mark inactive
    await updateVenue(venue.id, { is_active: false });
    return { name: venue.name, status: "deactivated", reason: `website returned ${homepage.status || homepage.error}` };
  }

  const cleanedHtml = stripHtmlKeepLinks(homepage.html);
  if (cleanedHtml.length < 50) {
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
    await updateVenue(venue.id, { is_active: false, events_url: null });
    return { name: venue.name, status: "deactivated", reason: "no events page found by Claude" };
  }

  // Step 4: Verify the discovered URL returns 200
  const valid = await urlReturns200(discoveredUrl);
  if (!valid) {
    // Try once more with GET instead of HEAD
    const getCheck = await fetchPage(discoveredUrl, 10_000);
    if (!getCheck.ok) {
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
