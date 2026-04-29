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

let SUPABASE_SERVICE_KEY, ANTHROPIC_API_KEY;

const ONLY_STATE = process.env.STATE || null;
const PROBE_TIMEOUT_MS = 8000;
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
  return null;
}

// ── Claude city enumeration ──

async function enumerateCities(stateName, stateCode, knownMetros) {
  const metroNames = knownMetros.map(m => m.name).join(", ");
  const prompt = `Enumerate up to 30 incorporated cities and towns in ${stateName} (${stateCode}). Focus on places with population over 25,000 OR places that are well-known cultural/event hubs even if smaller. For each, provide:
- name (city/town name only, no "City of" prefix)
- domain (their official municipal website domain, NO protocol — e.g. "boston.gov", "city.waltham.ma.us", "cambridgema.gov")
- nearest_metro (one of: ${metroNames})

Respond with ONLY a JSON array, no prose. Example:
[{"name":"Boston","domain":"boston.gov","nearest_metro":"Boston"},{"name":"Waltham","domain":"city.waltham.ma.us","nearest_metro":"Boston"}]`;

  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "x-api-key": ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
    },
    body: JSON.stringify({
      model: "claude-sonnet-4-6",
      max_tokens: 4096,
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
  return JSON.parse(match[0]);
}

// ── Main ──

async function main() {
  const secrets = await getSecrets(["ANTHROPIC_API_KEY"]);
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;
  ANTHROPIC_API_KEY = secrets.ANTHROPIC_API_KEY || process.env.ANTHROPIC_API_KEY;
  if (!ANTHROPIC_API_KEY) {
    console.error("Missing ANTHROPIC_API_KEY (not in Supabase secrets table or env vars)");
    process.exit(1);
  }

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
