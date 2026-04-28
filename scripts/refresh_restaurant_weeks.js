/**
 * refresh_restaurant_weeks.js — Keep restaurant_weeks topped up to TARGET_COUNT
 *
 * Same structure as refresh_curated_events.js but targets the restaurant_weeks
 * table, uses a dining-focused prompt, and asks for a narrower set (fewer
 * restaurant weeks exist in a region than iconic events).
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/refresh_restaurant_weeks.js
 *
 * Env:
 *   TARGET_COUNT=8     — how many active items to keep (default: 8)
 *   REGION=MA          — region to curate (default: MA)
 *   DRY_RUN=1          — preview without writing
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

const MODEL = "claude-sonnet-4-6";
const API_BASE = "https://api.anthropic.com/v1";
const TARGET_COUNT = parseInt(process.env.TARGET_COUNT || "8", 10);
const REGION_OVERRIDE = process.env.REGION || null;
const DRY_RUN = process.env.DRY_RUN === "1";

let ANTHROPIC_API_KEY, SUPABASE_SERVICE_KEY;
const BROWSER_UA = "LocalApp/1.0 (+https://localapp.live; restaurant weeks refresh)";

// ── Supabase helpers ──────────────────────────────────────────────────

async function sbFetch(path, init = {}) {
  const url = `${SUPABASE_URL}/rest/v1/${path}`;
  const headers = {
    apikey: SUPABASE_SERVICE_KEY,
    Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
    "Content-Type": "application/json",
    ...(init.headers || {}),
  };
  const res = await fetch(url, { ...init, headers });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Supabase ${res.status} on ${path}: ${body}`);
  }
  return res;
}

async function fetchActive(region) {
  const nowIso = new Date().toISOString();
  const filter = `region=eq.${region}&is_active=eq.true&or=(end_at.gt.${nowIso},end_at.is.null)`;
  const res = await sbFetch(`restaurant_weeks?${filter}&select=id,title,start_at,end_at`);
  return res.json();
}

async function deactivateExpired(region) {
  const nowIso = new Date().toISOString();
  const res = await sbFetch(
    `restaurant_weeks?region=eq.${region}&is_active=eq.true&end_at=lt.${nowIso}`,
    {
      method: "PATCH",
      headers: { Prefer: "return=representation" },
      body: JSON.stringify({
        is_active: false,
        excluded_at: nowIso,
        excluded_reason: "expired",
      }),
    },
  );
  const updated = await res.json();
  return updated.length;
}

async function insert(rows) {
  if (rows.length === 0) return 0;
  const res = await sbFetch(`restaurant_weeks`, {
    method: "POST",
    headers: { Prefer: "return=representation,resolution=ignore-duplicates" },
    body: JSON.stringify(rows),
  });
  const inserted = await res.json();
  return inserted.length;
}

// ── Image resolution (mirrors curated script) ────────────────────────

async function validateImageUrl(url) {
  if (!url || typeof url !== "string") return null;
  if (!/^https?:\/\//.test(url)) return null;
  const headers = { "User-Agent": BROWSER_UA, "Accept": "image/*,*/*;q=0.8" };
  const check = async (method) => {
    try {
      const res = await fetch(url, {
        method, redirect: "follow", signal: AbortSignal.timeout(8000),
        headers: method === "GET" ? { ...headers, Range: "bytes=0-1023" } : headers,
      });
      if (!res.ok && res.status !== 206) return null;
      const type = (res.headers.get("content-type") || "").toLowerCase();
      if (type && !type.startsWith("image/") && !type.includes("octet-stream")) return null;
      return url;
    } catch (_) { return null; }
  };
  return (await check("HEAD")) || (await check("GET"));
}

async function extractOgImageFromPage(pageUrl) {
  if (!pageUrl || typeof pageUrl !== "string") return null;
  if (!/^https?:\/\//.test(pageUrl)) return null;
  try {
    const res = await fetch(pageUrl, {
      redirect: "follow", signal: AbortSignal.timeout(10000),
      headers: { "User-Agent": BROWSER_UA, "Accept": "text/html,*/*;q=0.8" },
    });
    if (!res.ok) return null;
    const html = (await res.text()).slice(0, 200_000);
    const patterns = [
      /<meta[^>]+property=["']og:image:secure_url["'][^>]+content=["']([^"']+)["']/i,
      /<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image:secure_url["']/i,
      /<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']/i,
      /<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']/i,
      /<meta[^>]+name=["']twitter:image["'][^>]+content=["']([^"']+)["']/i,
      /<meta[^>]+content=["']([^"']+)["'][^>]+name=["']twitter:image["']/i,
    ];
    for (const re of patterns) {
      const m = html.match(re);
      if (m && m[1]) {
        try { return new URL(m[1], pageUrl).toString(); } catch { return null; }
      }
    }
    return null;
  } catch (_) { return null; }
}

async function resolveImage(proposedUrl, sourceUrl) {
  const direct = await validateImageUrl(proposedUrl);
  if (direct) return { url: direct, reason: "direct" };
  const ogRaw = await extractOgImageFromPage(sourceUrl);
  if (ogRaw) {
    const ogValidated = await validateImageUrl(ogRaw);
    if (ogValidated) return { url: ogValidated, reason: "og-image" };
  }
  return { url: null, reason: "no-image-found" };
}

// ── Claude call ───────────────────────────────────────────────────────

function buildPrompt(region, needed, existingTitles) {
  const existingList = existingTitles.length > 0
    ? `\n\nALREADY IN THE LIST (do NOT repeat any of these):\n${existingTitles.map((t) => `- ${t}`).join("\n")}`
    : "";

  const now = new Date();
  const todayIso = now.toISOString().slice(0, 10);
  const horizon = new Date(now);
  horizon.setUTCMonth(horizon.getUTCMonth() + 12);
  const horizonIso = horizon.toISOString().slice(0, 10);

  return `You are curating an "Upcoming Restaurant Weeks" list for a local events app. Every item is a multi-day dining promotion where participating restaurants offer prix-fixe or discount menus.

You have the web_search tool — USE IT AGGRESSIVELY. Restaurant week schedules are announced 2-3 months ahead and are NOT in your training data. You should use all ${needed < 6 ? 8 : 8} of your allotted searches.

SEARCH STRATEGY — search exhaustively, don't stop at the first 2-3 you find:
${region === "MA" ? `  - "Dine Out Boston 2026 dates" (runs twice a year, Mar + Aug)
  - "Cambridge Restaurant Week 2026"
  - "Somerville Restaurant Week 2026"
  - "Worcester Restaurants Week 2026"
  - "Berkshire Restaurant Week 2026" (western MA — Pittsfield, Lenox, Great Barrington)
  - "Springfield Restaurant Week 2026"
  - "Salem Restaurant Week 2026"
  - "Wellfleet OysterFest restaurant week"
  - "North Shore Restaurant Week 2026" (Beverly, Gloucester, Marblehead)
  - "South Shore Restaurant Week 2026" (Quincy, Hingham, Plymouth)
  - "Newburyport Restaurant Week 2026"
  - "Lowell Restaurant Week 2026"
  - "Haverhill Restaurant Week 2026"
  - "MetroWest Restaurant Week 2026" (Framingham, Natick)
  - "Brookline Restaurant Week 2026"` :
region === "RI" ? `  - "Providence Restaurant Weeks" (runs twice, Jan + Jul usually)
  - "Newport Restaurant Week"
  - "Warren Restaurant Week"
  - "Rhode Island Restaurant Week"` :
region === "PA" ? `  - "Philadelphia Restaurant Week" (Center City District, phila.gov)
  - "Pittsburgh Restaurant Week"
  - "Lancaster Restaurant Week"
  - "Lehigh Valley Restaurant Week" (Allentown/Bethlehem)
  - "Harrisburg Restaurant Week"
  - "Reading Restaurant Week"
  - "Erie Restaurant Week"
  - "Bucks County Restaurant Week"` :
`  - "[major ${region} city] restaurant week 2026"
  - Search state tourism board + chamber-of-commerce pages for a restaurant-week roster`}

Don't stop until you've searched for every candidate on that list. For each hit, pull real dates, URLs, and participating counts from official sources (organizer sites, chamber-of-commerce sites, local news). Skip anything you can't verify — but do search for all of them before concluding a city has no restaurant week.

HARD GEOGRAPHIC CONSTRAINT: every promotion MUST be physically in the state of ${region}. Set venue_state = "${region}" on every row. Cross-state promos don't qualify.

HARD TIME CONSTRAINT: TODAY IS ${todayIso}. Every item must have start_at between ${todayIso} and ${horizonIso} (the next 12 months). DO NOT return past restaurant weeks. Annual-recurring promos whose next edition hasn't been announced yet can be included with your best estimate of this year's dates based on prior years' pattern — but say so in the description so we know.

Return exactly ${needed} NEW upcoming restaurant week promotions. Real, confirmed, scheduled events only. Examples of what qualifies:
  - Dine Out Boston (the biggest — runs twice a year, typically Mar and Aug)
  - Cambridge Restaurant Week
  - Somerville Restaurant Week
  - Worcester Restaurant Week
  - North End Restaurant Week
  - Any city-level restaurant week promotion sponsored by a chamber of commerce, BID, or tourism board
  - Neighborhood-level dining promos if they're officially organized and published

DO NOT include:
  - Single-restaurant promotions (one venue's dinner series)
  - Farmers markets, food truck festivals, food halls
  - Non-dining events (bake sales, cooking classes, chef talks)
  - Generic "food week" that isn't actually a multi-restaurant dining promo
  - Events that already ended

For each item, return a JSON object with:
  - title: string ("Dine Out Boston — Spring 2026", "Cambridge Restaurant Week Summer 2026" — include the edition/season in the title so it's unique)
  - description: string (1-2 sentences, editorial voice. Mention the prix-fixe or discount structure if known.)
  - start_at: ISO 8601 timestamp (UTC)
  - end_at: ISO 8601 timestamp (UTC)
  - venue_name: string (the promo's umbrella name or city — e.g. "Downtown Boston")
  - venue_city: string (e.g. "Boston")
  - venue_state: "${region}"
  - venue_lat: number or null (city centroid is fine)
  - venue_lng: number or null
  - image_url: null (we'll scrape og:image from source_url)
  - source_url: string — the OFFICIAL promo page (dineoutboston.com, cambridgeusa.org, etc.). MUST be event-specific, not a generic chamber-of-commerce homepage, or og:image scraping returns useless org logos.
  - participating_count: integer or null — approximate number of participating restaurants if known${existingList}

Your final text response — after ALL web searches are complete — must be a single JSON object and NOTHING else. First character must be '{'. Last character must be '}'. No prose, no markdown fences, no analysis, no citations, no "here is the data" preamble. The object must be of the form {"events": [...]}. If you find fewer than ${needed} verifiable items, return fewer in the array; do not pad with guesses. If zero verifiable items, return {"events": []}.`;
}

async function callClaude(prompt) {
  // Web search tool is essential here — restaurant week schedules for the
  // next 6 months aren't in training data until they're formally announced
  // (typically 2-3 months out). Without web search, Claude correctly refuses
  // to fabricate rather than emit speculative dates.
  const res = await fetch(`${API_BASE}/messages`, {
    method: "POST",
    headers: {
      "x-api-key": ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
      "content-type": "application/json",
    },
    body: JSON.stringify({
      model: MODEL,
      max_tokens: 8000,
      tools: [
        {
          type: "web_search_20250305",
          name: "web_search",
          // Bumped so Claude can check each city on the candidate list
          // rather than stopping after the first few hits.
          max_uses: 16,
        },
      ],
      messages: [{ role: "user", content: prompt }],
    }),
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Anthropic ${res.status}: ${body}`);
  }
  const json = await res.json();
  // With tools, response.content is a mixed array of tool_use / tool_result /
  // text blocks. Claude sometimes splits its answer across multiple text blocks
  // (interleaved analysis + search + final JSON). Scan ALL text blocks, find
  // the one containing a {"events":...} object, extract via balanced-brace
  // matching — safer than assuming the last block is pure JSON.
  const blocks = json.content || [];
  const textBlocks = blocks.filter((b) => b.type === "text");
  if (textBlocks.length === 0) {
    throw new Error("No text blocks in Claude response");
  }
  for (const block of textBlocks.reverse()) {
    const extracted = extractEventsJson(block.text || "");
    if (extracted !== null) return extracted;
  }
  const joined = textBlocks.map((b) => b.text || "").join("\n---\n");
  console.error("Could not find {events: []} JSON in Claude output:", joined.slice(0, 800));
  throw new Error("Claude response had no parseable {events: [...]} JSON");
}

/**
 * Locate a {"events": [...]} JSON object inside free text and parse it.
 * Walks the string looking for '{' followed (soon after) by "events", then
 * does balanced-brace matching to find the close. Returns the .events array
 * or null if no valid object was found.
 */
function extractEventsJson(text) {
  const trimmed = text.trim()
    .replace(/^```(?:json)?\s*/i, "")
    .replace(/\s*```$/i, "");
  // Fast path: the entire text is the JSON object (the prompt's happy case).
  if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
    try {
      const parsed = JSON.parse(trimmed);
      if (Array.isArray(parsed?.events)) return parsed.events;
    } catch (_) { /* fall through to scan path */ }
  }
  // Scan for a brace-balanced object that mentions "events".
  for (let start = 0; start < trimmed.length; start++) {
    if (trimmed[start] !== "{") continue;
    let depth = 0;
    let inString = false;
    let esc = false;
    for (let i = start; i < trimmed.length; i++) {
      const c = trimmed[i];
      if (inString) {
        if (esc) { esc = false; }
        else if (c === "\\") esc = true;
        else if (c === '"') inString = false;
        continue;
      }
      if (c === '"') inString = true;
      else if (c === "{") depth++;
      else if (c === "}") {
        depth--;
        if (depth === 0) {
          const candidate = trimmed.slice(start, i + 1);
          if (!candidate.includes('"events"')) break; // try next '{'
          try {
            const parsed = JSON.parse(candidate);
            if (Array.isArray(parsed?.events)) return parsed.events;
          } catch (_) { /* malformed — try next '{' */ }
          break;
        }
      }
    }
  }
  return null;
}

// ── Main ──────────────────────────────────────────────────────────────

async function fetchLiveStates() {
  const res = await fetch(
    `${SUPABASE_URL}/rest/v1/states?is_live=eq.true&select=code,name`,
    {
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      },
    },
  );
  if (!res.ok) throw new Error(`Failed to fetch states: ${res.status}`);
  return res.json();
}

async function refreshForRegion(region) {
  console.log(`[rweek][${region}] starting`);
  const expired = DRY_RUN ? 0 : await deactivateExpired(region);
  console.log(`[rweek][${region}] deactivated ${expired} expired`);

  const active = await fetchActive(region);
  console.log(`[rweek][${region}] active now: ${active.length}`);

  const needed = TARGET_COUNT - active.length;
  if (needed <= 0) {
    console.log(`[rweek][${region}] at target, no refresh needed`);
    return;
  }

  console.log(`[rweek][${region}] asking Claude for ${needed} new items`);
  const existingTitles = active.map((r) => r.title);
  const proposals = await callClaude(buildPrompt(region, needed, existingTitles));
  console.log(`[rweek][${region}] Claude returned ${proposals.length} proposals`);

  const batchId = crypto.randomUUID();
  const validated = await Promise.all(
    proposals.map(async (p) => {
      const { url: img, reason } = await resolveImage(p.image_url, p.source_url);
      console.log(`[rweek][${region}] image ${img ? "OK" : "NONE"} (${reason}): ${p.title}`);
      return {
        region,
        title: (p.title || "").trim(),
        description: p.description || null,
        start_at: p.start_at || null,
        end_at: p.end_at || null,
        venue_name: p.venue_name || null,
        venue_city: p.venue_city || null,
        venue_state: p.venue_state || region,
        venue_lat: typeof p.venue_lat === "number" ? p.venue_lat : null,
        venue_lng: typeof p.venue_lng === "number" ? p.venue_lng : null,
        image_url: img,
        source_url: p.source_url || null,
        participating_count: typeof p.participating_count === "number" ? p.participating_count : null,
        curation_batch_id: batchId,
      };
    }),
  );

  const nowIsoCheck = new Date().toISOString();
  const horizonDate = new Date();
  horizonDate.setUTCMonth(horizonDate.getUTCMonth() + 12);
  const horizonIsoCheck = horizonDate.toISOString();
  const keepable = validated.filter((r) => {
    if (r.title.length === 0) return false;
    if (r.end_at && r.end_at < nowIsoCheck) {
      console.log(`[rweek][${region}] dropping past-dated: ${r.title}`);
      return false;
    }
    if (!r.end_at && r.start_at && r.start_at < nowIsoCheck) {
      console.log(`[rweek][${region}] dropping past-dated (no end_at): ${r.title}`);
      return false;
    }
    if (r.start_at && r.start_at > horizonIsoCheck) {
      console.log(`[rweek][${region}] dropping out-of-window: ${r.title}`);
      return false;
    }
    if (r.venue_state && r.venue_state !== region) {
      console.log(`[rweek][${region}] dropping out-of-state: ${r.title} (venue_state=${r.venue_state})`);
      return false;
    }
    return true;
  });
  console.log(`[rweek][${region}] keepable after validation: ${keepable.length}`);

  if (DRY_RUN) {
    console.log(JSON.stringify(keepable, null, 2));
    return;
  }

  const inserted = await insert(keepable);
  console.log(`[rweek][${region}] inserted ${inserted} rows (batch=${batchId})`);
}

async function main() {
  const secrets = await getSecrets(["ANTHROPIC_API_KEY"]);
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;
  ANTHROPIC_API_KEY = secrets.ANTHROPIC_API_KEY;

  let regions;
  if (REGION_OVERRIDE) {
    regions = [REGION_OVERRIDE];
    console.log(`[rweek] single-region override: ${REGION_OVERRIDE}`);
  } else {
    const rows = await fetchLiveStates();
    regions = rows.map((r) => r.code);
    console.log(`[rweek] live states from DB: ${regions.join(", ")}`);
  }

  for (const region of regions) {
    try {
      await refreshForRegion(region);
    } catch (err) {
      console.error(`[rweek][${region}] ERROR:`, err.message);
    }
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
