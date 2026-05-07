/**
 * discover_municipal_feeds.js — Auto-find municipal event feeds for each
 * active state and insert them into the municipal_feeds table.
 *
 * Flow per active state (states.is_live = true):
 *   1. Ask Claude to enumerate up to 30 incorporated cities/towns in the state
 *      with their official .gov website + nearest metro from our metros table.
 *   2. For each candidate domain, probe a list of well-known feed endpoints:
 *        - /jsonapi/node/event           (Drupal)
 *        - /jsonapi/node/events
 *        - /api/2/events                 (Localist)
 *        - /events.ics, /calendar.ics    (iCal)
 *        - /wp-json/tribe/events/v1/events  (WordPress + The Events Calendar)
 *   3. Validate each hit (HTTP 200, parseable, looks event-shaped, returns
 *      at least one upcoming row).
 *   4. UNIQUE constraint on URL means re-runs are idempotent: known feeds
 *      are skipped, new feeds are inserted with source='auto_discovered'.
 *
 * Designed to be called weekly *before* sync_municipal_feeds.js so newly
 * discovered feeds get synced in the same run.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/discover_municipal_feeds.js
 *   STATE=MA SUPABASE_SERVICE_KEY=ey... node scripts/discover_municipal_feeds.js  # one state
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");
const { fetchHtml } = require("./lib/scrapfly_fetch");

let SUPABASE_SERVICE_KEY, ANTHROPIC_API_KEY, SCRAPFLY_API_KEY;

const ONLY_STATE = process.env.STATE || null;
const PROBE_TIMEOUT_MS = 8000;

// Curated tourism / CVB / chamber domains to seed alongside Claude's
// city enumeration. These cover MA cities where the .gov calendar is
// thin or absent but the visitor bureau / chamber publishes a calendar
// (Cambridge, Salem, Newton, etc.). Each entry is treated as a normal
// candidate and goes through the same probe → claude_html fallback.
const CURATED_TOURISM_DOMAINS = {
  MA: [
    { name: "Cambridge (visitor)", domain: "cambridgeusa.org", nearest_metro: "Boston" },
    { name: "Somerville (discover)", domain: "somervillema.gov/events", nearest_metro: "Boston" },
    { name: "Salem (visitor)", domain: "salem.org", nearest_metro: "Boston" },
    { name: "Newton (events)", domain: "newtonma.gov", nearest_metro: "Boston" },
    { name: "Boston (events)", domain: "boston.gov/events", nearest_metro: "Boston" },
    { name: "Brookline (chamber)", domain: "brooklinechamber.com", nearest_metro: "Boston" },
    { name: "Provincetown (CVB)", domain: "ptownchamber.com", nearest_metro: "Boston" },
    { name: "Plymouth (visitor)", domain: "seeplymouth.com", nearest_metro: "Boston" },
    { name: "Worcester (DCU/Discover)", domain: "discovercentralma.org", nearest_metro: "Worcester" },
    { name: "Berkshires (visitor)", domain: "berkshires.org", nearest_metro: "Boston" },
    { name: "Lowell (chamber)", domain: "merrimackvalleychamber.com", nearest_metro: "Boston" },
    { name: "Cape Cod (CVB)", domain: "capecodchamber.org", nearest_metro: "Boston" },
  ],
  RI: [
    { name: "Providence (CVB)", domain: "goprovidence.com", nearest_metro: "Providence" },
    { name: "Newport (CVB)", domain: "discovernewport.org", nearest_metro: "Providence" },
    { name: "Rhode Island (state)", domain: "visitrhodeisland.com", nearest_metro: "Providence" },
  ],
  PA: [
    { name: "Philadelphia (CVB)", domain: "visitphilly.com", nearest_metro: "Philadelphia" },
    { name: "Pittsburgh (CVB)", domain: "visitpittsburgh.com", nearest_metro: "Pittsburgh" },
    { name: "Lancaster (CVB)", domain: "discoverlancaster.com", nearest_metro: "Philadelphia" },
    { name: "Bucks County (CVB)", domain: "visitbuckscounty.com", nearest_metro: "Philadelphia" },
    { name: "Hershey/Harrisburg (CVB)", domain: "visithersheyharrisburg.org", nearest_metro: "Philadelphia" },
  ],
};
const PROBE_PATHS = [
  { path: "/jsonapi/node/event",  type: "drupal_jsonapi", validate: validateDrupal },
  { path: "/jsonapi/node/events", type: "drupal_jsonapi", validate: validateDrupal },
  { path: "/api/2/events",        type: "localist",       validate: validateLocalist },
  { path: "/wp-json/tribe/events/v1/events", type: "wp_tribe", validate: validateWpTribe },
  { path: "/events.ics",          type: "ical",           validate: validateIcal },
  { path: "/calendar.ics",        type: "ical",           validate: validateIcal },
  { path: "/events/feed.ics",     type: "ical",           validate: validateIcal },
  { path: "/feed/?post_type=tribe_events", type: "rss",   validate: validateRss },
  { path: "/events/feed/",        type: "rss",            validate: validateRss },
];

// HTML calendar pages where the actual feed URL is advertised via
// <link rel="alternate" type="application/rss+xml|text/calendar"> tags.
// We fetch these, extract the linked feed URL, and validate that.
const HTML_PROBE_PATHS = [
  "/calendar/upcoming",
  "/calendar",
  "/events",
  "/Calendar.aspx",
  "/CivicAlerts.aspx",
];

// ── Supabase I/O ──

async function sbGet(path) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/${path}`, {
    headers: { apikey: SUPABASE_SERVICE_KEY, Authorization: `Bearer ${SUPABASE_SERVICE_KEY}` },
  });
  if (!res.ok) throw new Error(`Supabase GET ${path}: ${res.status}`);
  return res.json();
}

async function sbInsert(table, row) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/${table}`, {
    method: "POST",
    headers: {
      apikey: SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      "Content-Type": "application/json",
      Prefer: "return=minimal,resolution=ignore-duplicates",
    },
    body: JSON.stringify(row),
  });
  return res.ok || res.status === 409;
}

// ── Probe + validate ──

async function fetchWithTimeout(url, opts = {}) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), PROBE_TIMEOUT_MS);
  try {
    return await fetch(url, { ...opts, signal: ctrl.signal });
  } finally { clearTimeout(t); }
}

async function validateDrupal(res) {
  const data = await res.json().catch(() => null);
  if (!data || !Array.isArray(data.data)) return false;
  // Look for at least one event-shaped item with a future date.
  return data.data.some(item => item.attributes?.title);
}

async function validateLocalist(res) {
  const data = await res.json().catch(() => null);
  return Array.isArray(data?.events);
}

async function validateWpTribe(res) {
  const data = await res.json().catch(() => null);
  return Array.isArray(data?.events);
}

async function validateIcal(res) {
  const text = await res.text();
  return text.includes("BEGIN:VCALENDAR") && text.includes("BEGIN:VEVENT");
}

async function validateRss(res) {
  const text = await res.text();
  // Loose check: any RSS/Atom feed with at least one item.
  return /<rss|<feed/i.test(text) && /<item|<entry/i.test(text);
}

// Fetch an HTML calendar page; extract any feed URLs advertised via
// <link rel="alternate" type="application/rss+xml|atom+xml|text/calendar">.
// Returns a list of { absoluteUrl, type } we can validate.
async function discoverFeedsFromHtml(baseUrl, htmlPath) {
  const url = `${baseUrl}${htmlPath}`;
  let text;
  try {
    const res = await fetchWithTimeout(url);
    if (!res.ok) return [];
    text = await res.text();
  } catch (_) { return []; }

  const found = [];
  const linkRe = /<link\b[^>]*?rel\s*=\s*["']?alternate["']?[^>]*?>/gi;
  for (const m of text.matchAll(linkRe)) {
    const tag = m[0];
    const typeMatch = /type\s*=\s*["']?([^"'\s>]+)/i.exec(tag);
    const hrefMatch = /href\s*=\s*["']?([^"'\s>]+)/i.exec(tag);
    if (!typeMatch || !hrefMatch) continue;
    const t = typeMatch[1].toLowerCase();
    const href = hrefMatch[1];
    let kind = null;
    if (t.includes("rss") || t.includes("atom")) kind = "rss";
    else if (t.includes("calendar")) kind = "ical";
    if (!kind) continue;
    const abs = href.startsWith("http") ? href : new URL(href, url).toString();
    found.push({ absoluteUrl: abs, type: kind });
  }

  // Heuristic fallback: scan anchor tags for ".ics" or "rss" hrefs that
  // sit on the calendar page (CivicPlus often has explicit "Subscribe to
  // calendar" links in the page body, not always in <link> tags).
  const anchorRe = /<a\b[^>]*?href\s*=\s*["']?([^"'\s>]+\.ics)["']?/gi;
  for (const m of text.matchAll(anchorRe)) {
    const href = m[1];
    const abs = href.startsWith("http") ? href : new URL(href, url).toString();
    found.push({ absoluteUrl: abs, type: "ical" });
  }
  return found;
}

async function probeDomain(domain) {
  // Try with and without "www." prefix for HTTPS, plus the literal domain
  // as-given (handles cases like city.waltham.ma.us where "www." would be wrong).
  const bare = domain.replace(/^www\./, "").replace(/^https?:\/\//, "");
  const bases = [
    `https://${bare}`,
    `https://www.${bare}`,
  ];
  for (const base of bases) {
    // Step 1: direct API endpoint probes.
    for (const probe of PROBE_PATHS) {
      const url = `${base}${probe.path}`;
      try {
        const res = await fetchWithTimeout(url, {
          headers: probe.type === "drupal_jsonapi"
            ? { Accept: "application/vnd.api+json" }
            : probe.type === "ical" ? { Accept: "text/calendar" }
            : probe.type === "rss" ? { Accept: "application/rss+xml" }
            : { Accept: "application/json" },
        });
        if (!res.ok) continue;
        const ok = await probe.validate(res);
        if (ok) return { url, type: probe.type };
      } catch (_) { /* timeout / dns / TLS — keep trying */ }
    }

    // Step 2: HTML calendar page → extract feed URL from <link rel="alternate">
    // or in-page .ics anchor. Catches CivicPlus, custom SPA-style calendars,
    // anything that publishes a discoverable feed even without a standard path.
    for (const htmlPath of HTML_PROBE_PATHS) {
      const candidates = await discoverFeedsFromHtml(base, htmlPath);
      for (const c of candidates) {
        try {
          const res = await fetchWithTimeout(c.absoluteUrl, {
            headers: c.type === "ical" ? { Accept: "text/calendar" } : { Accept: "application/rss+xml" },
          });
          if (!res.ok) continue;
          const ok = c.type === "ical" ? await validateIcal(res) : await validateRss(res);
          if (ok) return { url: c.absoluteUrl, type: c.type };
        } catch (_) { /* keep trying */ }
      }
    }
  }

  // Step 3: Scrapfly + Claude HTML extraction fallback. Most MA cities
  // (CivicPlus, Granicus, proprietary SPA calendars) have visible event
  // listings on a calendar page but expose no feed. Find the page that
  // actually has events and register it as a `claude_html` feed — the sync
  // job will re-fetch via Scrapfly and ask Claude to extract events weekly.
  if (SCRAPFLY_API_KEY) {
    for (const base of bases) {
      for (const htmlPath of HTML_PROBE_PATHS) {
        const candidate = `${base}${htmlPath}`;
        try {
          const { html } = await fetchHtml(candidate, {
            scrapflyKey: SCRAPFLY_API_KEY,
            renderJs: true,
            timeoutMs: PROBE_TIMEOUT_MS,
          });
          if (looksLikeEventCalendar(html)) {
            return { url: candidate, type: "claude_html" };
          }
        } catch (_) { /* try next path */ }
      }
    }
  }

  return null;
}

// Heuristic: does this HTML actually contain event-like content? We don't
// want to register every random calendar page as a claude_html feed —
// only ones with multiple dated entries. Cheap regex pass to avoid burning
// Claude tokens during weekly sync on a page that has nothing to extract.
function looksLikeEventCalendar(html) {
  if (!html || html.length < 1000) return false;
  // Multiple month/day mentions OR multiple time entries OR multiple
  // event-shaped class/id markers.
  const monthHits = (html.match(/\b(?:January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{1,2}/gi) || []).length;
  const timeHits = (html.match(/\b\d{1,2}:\d{2}\s?(?:am|pm|AM|PM)\b/g) || []).length;
  const eventClassHits = (html.match(/class=["'][^"']*\bevent[^"']*["']/gi) || []).length;
  const dateAttrHits = (html.match(/data-(?:event-)?date=/gi) || []).length;
  // Need multiple signals — single-date pages (e.g. a contact page with one
  // upcoming meeting) shouldn't trigger the fallback.
  return (monthHits >= 3) || (timeHits >= 3 && eventClassHits >= 2) || (dateAttrHits >= 3);
}

// ── Claude city enumeration ──

async function enumerateCities(stateName, stateCode, knownMetros) {
  const metroNames = knownMetros.map(m => m.name).join(", ");
  // We ask for multiple event-source domains per city because the .gov site
  // alone misses most public events — most calendar activity sits on tourism
  // bureaus, chambers of commerce, BIDs, cultural councils, and town libraries.
  // Returning these inline lets us probe ALL of them per city in one pass
  // instead of hand-maintaining a curated list per state.
  const prompt = `Enumerate up to 40 cities, towns, and culturally significant places in ${stateName} (${stateCode}). Include incorporated cities/towns over 15,000 population AND smaller places that are well-known cultural/event hubs (e.g. Provincetown, Stockbridge, Lenox).

For each, return up to FIVE candidate event-source domains in priority order:

- name (city/town name, no "City of" prefix)
- nearest_metro (one of: ${metroNames})
- domains: an array of objects { domain, kind } where kind is one of:
  - "municipal" (official .gov site, e.g. "boston.gov", "cambridgema.gov")
  - "tourism" (visitor bureau / CVB / DMO, e.g. "cambridgeusa.org", "salem.org")
  - "chamber" (chamber of commerce, e.g. "brooklinechamber.com")
  - "cultural_council" (local cultural council site, e.g. "newtonculturalalliance.org")
  - "bid" (downtown BID / business improvement district, e.g. "downtownboston.com")
  - "library" (the town/regional library system, e.g. "minlib.net")

Domains should be JUST the host (no protocol, no path). Skip a kind if you don't know a real domain — DO NOT INVENT domains; better to omit than guess. A city might have only "municipal" or have all six.

Respond with ONLY a JSON array, no prose. Example:
[{"name":"Cambridge","nearest_metro":"Boston","domains":[{"domain":"cambridgema.gov","kind":"municipal"},{"domain":"cambridgeusa.org","kind":"tourism"},{"domain":"cambridgechamber.org","kind":"chamber"},{"domain":"cambridgeartscouncil.org","kind":"cultural_council"}]}]`;

  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "x-api-key": ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
    },
    body: JSON.stringify({
      model: "claude-sonnet-4-6",
      max_tokens: 8192,
      messages: [{ role: "user", content: prompt }],
    }),
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Claude API ${res.status}: ${body.slice(0, 200)}`);
  }
  const data = await res.json();
  const text = data.content?.[0]?.text || "";
  const match = text.match(/\[[\s\S]*\]/);
  if (!match) throw new Error(`No JSON array in Claude response: ${text.slice(0, 200)}`);
  const cities = JSON.parse(match[0]);

  // Fan out: each city becomes one candidate per domain, so the existing
  // probe loop touches all of them. Backwards-compatible with the legacy
  // single-domain shape (some Claude responses may still omit `domains`).
  const expanded = [];
  for (const c of cities) {
    if (Array.isArray(c.domains) && c.domains.length) {
      for (const d of c.domains) {
        if (!d.domain) continue;
        expanded.push({
          name: `${c.name}${d.kind && d.kind !== "municipal" ? ` (${d.kind})` : ""}`,
          domain: d.domain,
          nearest_metro: c.nearest_metro,
          kind: d.kind || "municipal",
        });
      }
    } else if (c.domain) {
      expanded.push({ name: c.name, domain: c.domain, nearest_metro: c.nearest_metro, kind: "municipal" });
    }
  }
  return expanded;
}

// ── Main ──

async function main() {
  const secrets = await getSecrets(["ANTHROPIC_API_KEY", "SCRAPFLY_API_KEY"]);
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;
  ANTHROPIC_API_KEY = secrets.ANTHROPIC_API_KEY || process.env.ANTHROPIC_API_KEY;
  SCRAPFLY_API_KEY = secrets.SCRAPFLY_API_KEY || process.env.SCRAPFLY_API_KEY || null;
  if (!ANTHROPIC_API_KEY) {
    console.error("Missing ANTHROPIC_API_KEY (not in Supabase secrets table or env vars)");
    process.exit(1);
  }
  console.log(`Scrapfly: ${SCRAPFLY_API_KEY ? "available — claude_html fallback enabled" : "NOT configured — only structured-feed discovery"}`);

  // 1. Active states
  const stateFilter = ONLY_STATE ? `&code=eq.${ONLY_STATE}` : "";
  const states = await sbGet(`states?is_live=eq.true${stateFilter}&select=code,name`);
  console.log(`Active states: ${states.map(s => s.code).join(", ")}`);

  // 2. Existing feed URLs for de-dupe
  const existing = await sbGet(`municipal_feeds?select=url`);
  const knownUrls = new Set(existing.map(r => r.url));
  console.log(`Already-known feeds: ${knownUrls.size}`);

  let totalAdded = 0, totalProbed = 0, totalSkipped = 0;

  for (const state of states) {
    console.log(`\n━━━ ${state.name} (${state.code}) ━━━`);
    const metros = await sbGet(`metros?state_code=eq.${state.code}&select=name,lat,lng`);
    if (metros.length === 0) {
      console.log(`  No metros for ${state.code}, skipping.`);
      continue;
    }

    let candidates;
    try {
      candidates = await enumerateCities(state.name, state.code, metros);
    } catch (err) {
      console.error(`  Claude enumeration failed: ${err.message}`);
      continue;
    }
    console.log(`  Claude returned ${candidates.length} candidate cities`);

    // Prepend curated tourism / CVB / chamber domains for this state.
    // These cover MA cities where the .gov calendar is thin or absent
    // but the visitor bureau publishes a real one (Cambridge USA, Salem.org,
    // discovercentralma.org, etc.). Same probe pipeline as Claude's list.
    const curated = CURATED_TOURISM_DOMAINS[state.code] || [];
    if (curated.length) {
      console.log(`  + ${curated.length} curated tourism/CVB domain(s)`);
      candidates = [...curated, ...candidates];
    }

    for (const c of candidates) {
      if (!c.domain) continue;
      // Skip if any of this city's domains already has any registered feed.
      const hostKey = c.domain.replace(/^www\./, "").toLowerCase();
      const alreadyHas = [...knownUrls].some(u => u.toLowerCase().includes(hostKey));
      if (alreadyHas) { totalSkipped++; continue; }

      totalProbed++;
      process.stdout.write(`  Probing ${c.name} (${c.domain})... `);
      const hit = await probeDomain(c.domain);
      if (!hit) { console.log("none"); continue; }

      const metroName = (c.nearest_metro || metros[0].name).toLowerCase();
      const row = {
        metro: metroName,
        name: `${c.name} (${state.code})`,
        type: hit.type,
        url: hit.url,
        city: c.name,
        state: state.code,
        enabled: true,
        source: "auto_discovered",
      };
      const ok = await sbInsert("municipal_feeds", row);
      if (ok) {
        knownUrls.add(hit.url);
        totalAdded++;
        console.log(`✓ ${hit.type}: ${hit.url}`);
      } else {
        console.log("✗ insert failed");
      }
    }
  }

  console.log("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
  console.log(`Probed ${totalProbed}, skipped (already known) ${totalSkipped}, added ${totalAdded} new feed(s).`);
}

main().catch(err => { console.error("Fatal:", err); process.exit(1); });
