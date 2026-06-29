/**
 * refresh_curated_events.js — Keep curated_events topped up to 25 active items
 *
 * Daily run:
 *   1. Deactivate expired rows (end_at < now).
 *   2. Count active rows for region=MA.
 *   3. If < 25, ask Claude for (25 - count) new must-know items, dedupe vs
 *      currently-active titles, HEAD-check each image URL, insert survivors.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/refresh_curated_events.js
 *
 * Env:
 *   TARGET_COUNT=25    — how many active items to keep (default: 25)
 *   REGION=MA          — region to curate (default: MA)
 *   DRY_RUN=1          — preview without writing
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

const MODEL = "claude-sonnet-4-6"; // Sonnet: better editorial judgment than Haiku for "must-know"
const API_BASE = "https://api.anthropic.com/v1";
// Keep ~50 per state; the Must-Know rail then displays the 25 closest to the user.
const TARGET_COUNT = parseInt(process.env.TARGET_COUNT || "50", 10);
// Leave REGION unset to iterate every live state in the `states` table.
// Set REGION=MA for a single-region manual run (useful for debugging).
const REGION_OVERRIDE = process.env.REGION || null;
const DRY_RUN = process.env.DRY_RUN === "1";

let ANTHROPIC_API_KEY, SUPABASE_SERVICE_KEY, GOOGLE_PLACES_API_KEY;

// Geocode a "City, ST" to a centroid so the Must-Know rail can distance-sort.
// Claude usually returns null coords; this backfills them. Country-level hits
// are rejected; a city/region centroid is good enough for "closest to me".
async function geocodeCity(city, region) {
  if (!GOOGLE_PLACES_API_KEY || !city) return null;
  try {
    const r = await fetch(
      `https://maps.googleapis.com/maps/api/geocode/json?address=${encodeURIComponent(`${city}, ${region}`)}&key=${GOOGLE_PLACES_API_KEY}`,
    );
    const d = await r.json();
    if (d.status !== "OK" || !d.results?.[0]) return null;
    if ((d.results[0].types || []).includes("country")) return null;
    return d.results[0].geometry.location; // { lat, lng }
  } catch (_) {
    return null;
  }
}

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

async function fetchActiveCurated(region) {
  const nowIso = new Date().toISOString();
  // Include rows with null end_at (always-on / date-TBA items still count).
  const filter = `region=eq.${region}&is_active=eq.true&or=(end_at.gt.${nowIso},end_at.is.null)`;
  const res = await sbFetch(
    `curated_events?${filter}&select=id,title,start_at,end_at`,
  );
  return res.json();
}

async function deactivateExpired(region) {
  const nowIso = new Date().toISOString();
  const res = await sbFetch(
    `curated_events?region=eq.${region}&is_active=eq.true&end_at=lt.${nowIso}`,
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

async function insertCurated(rows) {
  if (rows.length === 0) return 0;
  const res = await sbFetch(`curated_events`, {
    method: "POST",
    headers: { Prefer: "return=representation,resolution=ignore-duplicates" },
    body: JSON.stringify(rows),
  });
  const inserted = await res.json();
  return inserted.length;
}

// ── Image validation ──────────────────────────────────────────────────
//
// Claude returns image URLs from memory, which are frequently stale, wrong
// domain, or 404s. Validate via HEAD so we don't ship broken cards.

const BROWSER_UA = "LocalApp/1.0 (+https://localapp.live; curated events refresh)";

async function validateImageUrl(url) {
  if (!url || typeof url !== "string") return null;
  if (!/^https?:\/\//.test(url)) return null;
  const headers = {
    "User-Agent": BROWSER_UA,
    "Accept": "image/*,*/*;q=0.8",
  };
  const check = async (method) => {
    try {
      const res = await fetch(url, {
        method,
        redirect: "follow",
        signal: AbortSignal.timeout(8000),
        headers: method === "GET" ? { ...headers, Range: "bytes=0-1023" } : headers,
      });
      if (!res.ok && res.status !== 206) return null;
      const type = (res.headers.get("content-type") || "").toLowerCase();
      if (type && !type.startsWith("image/") && !type.includes("octet-stream")) return null;
      return url;
    } catch (_) {
      return null;
    }
  };
  return (await check("HEAD")) || (await check("GET"));
}

/**
 * Fallback: fetch the source page and extract og:image / twitter:image.
 * Claude is unreliable at quoting direct CDN URLs (Wikimedia hash paths are
 * MD5-derived and it guesses) but competent at naming the event's home page.
 * Every half-decent event page has og:image in the head, which IS a real URL.
 */
async function extractOgImageFromPage(pageUrl) {
  if (!pageUrl || typeof pageUrl !== "string") return null;
  if (!/^https?:\/\//.test(pageUrl)) return null;
  try {
    const res = await fetch(pageUrl, {
      redirect: "follow",
      signal: AbortSignal.timeout(10000),
      headers: { "User-Agent": BROWSER_UA, "Accept": "text/html,*/*;q=0.8" },
    });
    if (!res.ok) return null;
    const html = (await res.text()).slice(0, 200_000); // og tags are always near the top
    // Try og:image (secure_url variant first), then twitter:image.
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
        // Resolve relative URLs against the page URL.
        try { return new URL(m[1], pageUrl).toString(); } catch { return null; }
      }
    }
    return null;
  } catch (_) {
    return null;
  }
}

/**
 * Strips year-ish suffixes from a title so Wikipedia article lookup has a
 * chance. "Boston Marathon 2025" → "Boston Marathon". "First Night Boston
 * 2025–2026" → "First Night Boston". Wikipedia articles are evergreen; the
 * year in our titles is editorial framing, not article title.
 */
function stripYearSuffix(title) {
  return (title || "")
    .replace(/\s*\d{4}(?:\s*[–-]\s*\d{2,4})?\s*$/, "")
    .replace(/\s+(spring|summer|fall|autumn|winter)\s*$/i, "")
    .trim();
}

/**
 * Wikipedia fallback: hit the REST summary endpoint for the event title (year
 * stripped). If the article has a thumbnail/originalimage, return it. Wikipedia
 * article infobox images are the most reliable source for iconic civic events
 * (Boston Marathon, Head of the Charles, etc.) since Claude can't reliably
 * guess the direct upload.wikimedia.org hash path.
 */
async function extractWikipediaImage(rawTitle) {
  const title = stripYearSuffix(rawTitle);
  if (!title) return null;
  const endpoint = `https://en.wikipedia.org/api/rest_v1/page/summary/${encodeURIComponent(title)}`;
  try {
    const res = await fetch(endpoint, {
      redirect: "follow",
      signal: AbortSignal.timeout(8000),
      headers: { "User-Agent": BROWSER_UA, "Accept": "application/json" },
    });
    if (!res.ok) {
      // Fall back to opensearch to find the best-matching article, then retry.
      const searchUrl = `https://en.wikipedia.org/w/api.php?action=opensearch&search=${encodeURIComponent(title)}&limit=1&namespace=0&format=json`;
      const sres = await fetch(searchUrl, {
        signal: AbortSignal.timeout(8000),
        headers: { "User-Agent": BROWSER_UA },
      });
      if (!sres.ok) return null;
      const [, titles] = await sres.json();
      const picked = titles?.[0];
      if (!picked) return null;
      const retryRes = await fetch(
        `https://en.wikipedia.org/api/rest_v1/page/summary/${encodeURIComponent(picked)}`,
        { signal: AbortSignal.timeout(8000), headers: { "User-Agent": BROWSER_UA } },
      );
      if (!retryRes.ok) return null;
      const json = await retryRes.json();
      return json.originalimage?.source || json.thumbnail?.source || null;
    }
    const json = await res.json();
    // Prefer full-res over the small thumbnail for hero-card quality.
    return json.originalimage?.source || json.thumbnail?.source || null;
  } catch (_) {
    return null;
  }
}

/**
 * Wikimedia Commons image search — returns an actual photograph (from the
 * File: namespace), not an org's logo. Filters out obvious non-photo filenames
 * (logo, seal, flag, coat_of_arms, icon, logotype) so we don't regress to the
 * exact problem og:image was giving us. Prefers images wider than 800px.
 */
async function searchCommonsImage(rawTitle, region) {
  const title = stripYearSuffix(rawTitle);
  if (!title) return null;
  // Bias the query toward finding event photography by appending the region.
  const query = `${title} ${region || ""}`.trim();
  const apiUrl = `https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrsearch=${encodeURIComponent(query)}&gsrnamespace=6&gsrlimit=10&prop=imageinfo&iiprop=url%7Csize&iiurlwidth=1600&format=json&origin=*`;
  try {
    const res = await fetch(apiUrl, {
      signal: AbortSignal.timeout(10000),
      headers: { "User-Agent": BROWSER_UA, "Accept": "application/json" },
    });
    if (!res.ok) return null;
    const json = await res.json();
    const pages = Object.values(json?.query?.pages || {});
    if (pages.length === 0) return null;
    // Rank by (passes logo filter) → (width descending). Commons returns
    // lots of maps/diagrams/seals mixed with real photos; the filter is
    // load-bearing here.
    const blacklistRe = /(logo|logotype|seal|coat[_\s-]?of[_\s-]?arms|flag|icon|wordmark|emblem|crest|svg|\.pdf$|\.tif+$)/i;
    const ranked = pages
      .map((p) => {
        const info = p?.imageinfo?.[0];
        if (!info) return null;
        const filename = (p.title || "").replace(/^File:/, "");
        if (blacklistRe.test(filename)) return null;
        const url = info.thumburl || info.url;
        const width = info.thumbwidth || info.width || 0;
        if (!url || width < 600) return null;
        return { url, width };
      })
      .filter(Boolean)
      .sort((a, b) => b.width - a.width);
    return ranked[0]?.url || null;
  } catch (_) {
    return null;
  }
}

/**
 * Four-pass image resolution, ranked by expected quality of output image:
 *   1. Claude's image_url (direct) — rare, only when Claude is confident.
 *   2. Wikimedia Commons photo search — returns actual event photography
 *      filtered against logo/seal/flag filenames. Works for most iconic civic
 *      events (parades, regattas, festivals) that have Commons coverage.
 *   3. og:image from proposal's source_url — last resort before Wikipedia.
 *      Unreliable because event homepages tend to serve their LOGO as
 *      og:image, not an event photo.
 *   4. Wikipedia article infobox image — same logo risk as og:image but
 *      sometimes lands the right photo for events with a dedicated article.
 * Returns null only if all four fail.
 */
async function resolveImage(proposedUrl, sourceUrl, title, region) {
  const direct = await validateImageUrl(proposedUrl);
  if (direct) return { url: direct, reason: "direct" };
  const commons = await searchCommonsImage(title, region);
  if (commons) {
    const commonsValidated = await validateImageUrl(commons);
    if (commonsValidated) return { url: commonsValidated, reason: "commons" };
  }
  const ogRaw = await extractOgImageFromPage(sourceUrl);
  if (ogRaw) {
    const ogValidated = await validateImageUrl(ogRaw);
    if (ogValidated) return { url: ogValidated, reason: "og-image" };
  }
  const wiki = await extractWikipediaImage(title);
  if (wiki) {
    const wikiValidated = await validateImageUrl(wiki);
    if (wikiValidated) return { url: wikiValidated, reason: "wikipedia" };
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
  horizon.setUTCMonth(horizon.getUTCMonth() + 6);
  const horizonIso = horizon.toISOString().slice(0, 10);

  return `You are curating a "25 must-know things happening over the next 6 months in ${region}" list for a local events app. The bar is NATIONALLY OR REGIONALLY ICONIC — the things a local would tell a visitor "you have to know this is happening."

HARD GEOGRAPHIC CONSTRAINT: every event MUST take place physically inside the state of ${region} — US postal code for that state only. Events in neighboring states (e.g. Newport Folk Festival is in Rhode Island, not ${region}) DO NOT QUALIFY no matter how iconic. If the primary venue address isn't in ${region}, skip it. Set venue_state = "${region}" on every row.

HARD TIME CONSTRAINT: TODAY IS ${todayIso}. Every item must have start_at between ${todayIso} and ${horizonIso} (the next 6 months). Events further out — even if iconic — belong to future refreshes, not this one. DO NOT return events that end before today; your training data may be old.

Build the list ENTIRELY from ${region}'s OWN iconic events. The Massachusetts names in parentheses below are there ONLY to calibrate HOW famous/established an event must be — never put a Massachusetts event in a non-Massachusetts list; find the genuine ${region} equivalents (for PA that's the Mummers Parade, Welcome America, Pittsburgh Regatta, the Little League World Series, etc. — you supply the right ones).

Do NOT critique or comment on this prompt. If something seems off, ignore it and still return the JSON.

HARD ACCURACY CONSTRAINT: NEVER assert the outcome of a competition you cannot verify — who won or will win a championship / playoff / election / award, or who is the "defending" / "reigning" champion or title-holder. Your training data is stale and these change. If an event's hook depends on such a claim (e.g. "the defending champions' home opener"), describe it neutrally without the claim, or skip it. When unsure of a fact, leave it out rather than guess.

Return exactly ${needed} NEW editorial picks happening in ${region} between ${todayIso} and ${horizonIso}, skewing toward these CATEGORIES:
  - Signature civic traditions with decades+ of history (MA bar: North End feasts, First Night, Bunker Hill Day)
  - Major parades + festivals with national/regional recognition (MA bar: Boston Pride, Salem Haunted Happenings, Topsfield Fair, Head of the Charles Regatta, Sail Boston)
  - Headline cultural seasons + exhibitions (MA bar: Tanglewood, Jacob's Pillow, flagship museum exhibitions, a book/film festival)
  - Marquee sports moments (MA bar: the Boston Marathon, historic reenactments, World Cup matches, pro-team playoff runs when confirmed)
  - Fireworks and 4th of July anchor events (MA bar: Boston Pops Fireworks, Harborfest)
  - Anniversary / once-in-a-generation moments (MA bar: America250 / Sail250 programming)
  - Civic moments of state-level consequence (major tree lightings, inaugurations)

EXPLICITLY EXCLUDE:
  - Restaurant weeks or any food-week-style dining promotions (a separate feature covers these — DO NOT include them)
  - Generic craft fairs, generic farmers markets
  - School/college events (graduations, reunions, university-internal programming)
  - Repeating weekly/monthly events (yoga in the park, weekly markets, monthly open mics)
  - Commercial expos / trade shows / home shows
  - Professional or college sports games, home openers, or season/playoff/"championship defense" framings — the app's Sports tab covers real schedules, and you can't know how a season turns out
  - Any event < 3 years old that hasn't established national recognition
  - Small/niche food festivals

Food festivals ARE allowed IF they are regionally iconic with national name-recognition — a century-old religious feast, a signature seasonal festival a whole region travels for (the MA bar here is Wellfleet OysterFest, the North End's Saint Anthony's Feast). A generic "${region} BBQ Festival 2026" → NO; a 100+ year tradition → YES.

Spread across the 6-month window — not clustered in one month. Mix of serious (reenactments, civic) + joyful (parades, fireworks) + cultural (festivals, exhibitions). Real, confirmed events only; if you're not certain it's scheduled between ${todayIso} and ${horizonIso}, skip it.${existingList}

For each item, return a JSON object with:
  - title: string (proper noun, no "the" prefix if avoidable)
  - description: string (1-2 sentences, editorial voice)
  - start_at: ISO 8601 timestamp (UTC) or null if dates not yet announced
  - end_at: ISO 8601 timestamp (UTC) or null for single-day events (use start_at + 24h)
  - venue_name: string or null
  - venue_address: string or null (street + city, e.g. "1 Fenway Park Way, Boston")
  - venue_city: string (e.g. "Boston")
  - venue_state: "${region}"
  - venue_lat: number or null
  - venue_lng: number or null
  - source_url: string URL to the event's official page. MUST BE EVENT-SPECIFIC. A link to the event itself (bostonmarathon.org, hocr.org, fishermansfeast.com) is GOOD. A link to a generic place or organization (salem.gov, bostonsymphony.org, "Salem,_Massachusetts" Wikipedia page) is BAD — generic source_urls give us generic og:images (skyline shots, location photos) instead of event photography. If no specific official page exists, point at the event's Wikipedia article ONLY if one exists with "(event)" or the event name in the title. Otherwise return null.
  - image_url: set to null. We'll extract it from source_url's og:image. DO NOT guess upload.wikimedia.org hash paths (the /x/yz/ prefix is MD5-derived and wrong guesses 404). Set image_url only if you KNOW an event-specific, editorial PHOTOGRAPH URL that resolves — a parade shot, a crowd shot, performance photography. Not a logo. Not a skyline. Not a venue exterior. When in doubt, null.

Return ONLY a JSON object of the form {"events": [...]} — no prose, no markdown fences.`;
}

async function callClaude(prompt) {
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
      messages: [{ role: "user", content: prompt }],
    }),
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Anthropic ${res.status}: ${body}`);
  }
  const json = await res.json();
  // Scan ALL text blocks (last first) for a {"events":[...]} object. Sonnet
  // sometimes prefixes a prose preamble ("I need to verify these dates…") or
  // splits its answer, so content[0] isn't reliably the JSON — a naive
  // JSON.parse(content[0].text) was failing for whole states (RI/PA/NJ).
  const textBlocks = (json.content || []).filter((b) => b.type === "text");
  for (const block of [...textBlocks].reverse()) {
    const extracted = extractEventsJson(block.text || "");
    if (extracted !== null) return extracted;
  }
  const joined = textBlocks.map((b) => b.text || "").join("\n---\n");
  console.error("No {events:[]} JSON in Claude output:", joined.slice(0, 500));
  throw new Error("Claude response had no parseable {events:[...]} JSON");
}

// Locate a {"events": [...]} object inside free text (prose-before-JSON safe)
// via balanced-brace matching. Returns the .events array or null.
function extractEventsJson(text) {
  const trimmed = text.trim().replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/i, "");
  if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
    try {
      const parsed = JSON.parse(trimmed);
      if (Array.isArray(parsed?.events)) return parsed.events;
    } catch (_) { /* fall through to scan */ }
  }
  for (let start = 0; start < trimmed.length; start++) {
    if (trimmed[start] !== "{") continue;
    let depth = 0, inString = false, esc = false;
    for (let i = start; i < trimmed.length; i++) {
      const c = trimmed[i];
      if (inString) {
        if (esc) esc = false;
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
  console.log(`[curated][${region}] starting`);
  const expired = DRY_RUN ? 0 : await deactivateExpired(region);
  console.log(`[curated][${region}] deactivated ${expired} expired`);

  const active = await fetchActiveCurated(region);
  console.log(`[curated][${region}] active now: ${active.length}`);

  const needed = TARGET_COUNT - active.length;
  if (needed <= 0) {
    console.log(`[curated][${region}] at target, no refresh needed`);
    return;
  }

  console.log(`[curated][${region}] asking Claude for ${needed} new items`);
  const existingTitles = active.map((r) => r.title);
  const proposals = await callClaude(buildPrompt(region, needed, existingTitles));
  console.log(`[curated][${region}] Claude returned ${proposals.length} proposals`);

  const batchId = crypto.randomUUID();
  const validated = await Promise.all(
    proposals.map(async (p) => {
      const { url: img, reason } = await resolveImage(p.image_url, p.source_url, p.title, region);
      console.log(`[curated][${region}] image ${img ? "OK" : "NONE"} (${reason}): ${p.title}`);
      // Backfill coords (Claude usually returns null) so the rail can distance-sort.
      let lat = typeof p.venue_lat === "number" ? p.venue_lat : null;
      let lng = typeof p.venue_lng === "number" ? p.venue_lng : null;
      if ((lat == null || lng == null) && p.venue_city) {
        const c = await geocodeCity(p.venue_city, region);
        if (c) { lat = c.lat; lng = c.lng; }
      }
      return {
        region,
        title: (p.title || "").trim(),
        description: p.description || null,
        start_at: p.start_at || null,
        end_at: p.end_at || null,
        venue_name: p.venue_name || null,
        venue_address: p.venue_address || null,
        venue_city: p.venue_city || null,
        venue_state: p.venue_state || region,
        venue_lat: lat,
        venue_lng: lng,
        image_url: img,
        source_url: p.source_url || null,
        curation_batch_id: batchId,
      };
    }),
  );

  const nowIsoCheck = new Date().toISOString();
  const horizonDate = new Date();
  horizonDate.setUTCMonth(horizonDate.getUTCMonth() + 6);
  const horizonIsoCheck = horizonDate.toISOString();
  const keepable = validated.filter((r) => {
    if (r.title.length === 0) return false;
    if (r.end_at && r.end_at < nowIsoCheck) {
      console.log(`[curated][${region}] dropping past-dated: ${r.title} (end_at=${r.end_at})`);
      return false;
    }
    if (!r.end_at && r.start_at && r.start_at < nowIsoCheck) {
      console.log(`[curated][${region}] dropping past-dated (no end_at): ${r.title} (start_at=${r.start_at})`);
      return false;
    }
    if (r.start_at && r.start_at > horizonIsoCheck) {
      console.log(`[curated][${region}] dropping out-of-window: ${r.title} (start_at=${r.start_at})`);
      return false;
    }
    // Cross-state guard: venue_state must match the region we're curating for.
    if (r.venue_state && r.venue_state !== region) {
      console.log(`[curated][${region}] dropping out-of-state: ${r.title} (venue_state=${r.venue_state})`);
      return false;
    }
    return true;
  });
  console.log(`[curated][${region}] keepable after validation: ${keepable.length}`);

  if (DRY_RUN) {
    console.log(JSON.stringify(keepable, null, 2));
    return;
  }

  const inserted = await insertCurated(keepable);
  console.log(`[curated][${region}] inserted ${inserted} rows (batch=${batchId})`);
}

async function main() {
  const secrets = await getSecrets(["ANTHROPIC_API_KEY", "GOOGLE_PLACES_API_KEY"]);
  GOOGLE_PLACES_API_KEY = secrets.GOOGLE_PLACES_API_KEY;
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;
  ANTHROPIC_API_KEY = secrets.ANTHROPIC_API_KEY;

  // REGION=MA → single-region manual run. Otherwise iterate every live state
  // in the `states` table so adding a new state just means flipping is_live.
  let regions;
  if (REGION_OVERRIDE) {
    regions = [REGION_OVERRIDE];
    console.log(`[curated] single-region override: ${REGION_OVERRIDE}`);
  } else {
    const rows = await fetchLiveStates();
    regions = rows.map((r) => r.code);
    console.log(`[curated] live states from DB: ${regions.join(", ")}`);
  }

  for (const region of regions) {
    try {
      await refreshForRegion(region);
    } catch (err) {
      // One region failing shouldn't block the others — log and continue.
      console.error(`[curated][${region}] ERROR:`, err.message);
    }
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
