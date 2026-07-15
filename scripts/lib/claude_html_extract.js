/**
 * claude_html_extract.js — extract structured event records from a calendar
 * page's raw HTML using Claude.
 *
 * Used by sync_municipal_feeds.js for `claude_html` feed type — covers MA
 * cities on CivicPlus / Granicus / proprietary calendars where there's no
 * machine-readable feed.
 */

const ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
const MODEL = "claude-sonnet-4-6";

// Cap HTML payload to keep token usage bounded. Calendar pages over ~250KB
// almost always contain navigation chrome and footer that we don't need.
const MAX_HTML_CHARS = 250_000;

function trimHtmlForExtraction(html) {
  if (!html) return "";
  // Drop common boilerplate that confuses Claude and burns tokens.
  let h = html
    .replace(/<script[\s\S]*?<\/script>/gi, "")
    .replace(/<style[\s\S]*?<\/style>/gi, "")
    .replace(/<svg[\s\S]*?<\/svg>/gi, "")
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, "");
  if (h.length > MAX_HTML_CHARS) h = h.slice(0, MAX_HTML_CHARS);
  return h;
}

async function extractEventsFromHtml({ html, sourceUrl, anthropicKey, city, state }) {
  const trimmed = trimHtmlForExtraction(html);
  const prompt =
    `You are extracting public events from a community events calendar HTML page.\n` +
    `The publisher of this calendar is based in ${city}, ${state}, but the calendar is a ROUND-UP — ` +
    `individual events may take place in surrounding towns or even a neighboring state ` +
    `(e.g. a shore day-trip, a festival one town over). Record each event's OWN location from the page. ` +
    `Do NOT assume an event is in ${city}, ${state} just because that's the publisher's home city — ` +
    `only use ${city}, ${state} for an event if the page itself gives no other location.\n\n` +
    `Source URL: ${sourceUrl}\n\n` +
    `Return ONLY a JSON array. Each event is an object with fields:\n` +
    `  - title: string (the event name)\n` +
    `  - description: string|null (1–2 sentences if shown; do NOT invent)\n` +
    `  - start_at: ISO 8601 datetime string with timezone offset (e.g. "2026-05-15T19:00:00-04:00"). If only a date is present, use "T00:00:00-04:00" (midnight Eastern) and set time_known=false. NEVER use "T12:00:00-04:00" as a placeholder for unknown time — that's a real noon Eastern timestamp, and using it for TBD events makes them indistinguishable from genuine noon events (e.g. "Fridays 12-1 PM" walks, lunch programs, midday concerts). Reserve T12:00:00-04:00 for events whose source page actually says they start at 12 PM Eastern, and emit time_known=true for those.\n` +
    `  - end_at: ISO 8601 or null\n` +
    `  - time_known: boolean (true if a specific start time is shown, false for date-only or "TBD")\n` +
    `  - venue_name: string|null (the actual venue if listed; null when the city itself is the location)\n` +
    `  - venue_address: string|null (street + city + state if listed)\n` +
    `  - venue_city: string|null (the TOWN/CITY this specific event is in — read it from the listing; null if the page genuinely doesn't say)\n` +
    `  - venue_state: string|null (2-letter state code for THIS event, e.g. "NJ"; null if not shown)\n` +
    `  - source_url: string|null (the per-event detail URL if linked, otherwise null)\n` +
    `  - is_free: boolean|null\n` +
    `  - category: one of "music"|"arts"|"food"|"sports"|"comedy"|"family"|"outdoors"|"community"|"education"|"other"\n\n` +
    `Rules:\n` +
    `- Skip recurring-event template rows that don't have a concrete date.\n` +
    `- Skip past events (anything dated before today).\n` +
    `- Skip municipal meetings (planning board, council, commission, hearing) — they're noise for an event-discovery app.\n` +
    `- Never guess venue_city/venue_state — leave them null rather than defaulting to the publisher's city.\n` +
    `- If there are no public events, return [].\n` +
    `- Output only the JSON array. No markdown fences, no prose.\n\n` +
    `HTML:\n${trimmed}`;

  const res = await fetch(ANTHROPIC_URL, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "x-api-key": anthropicKey,
      "anthropic-version": "2023-06-01",
    },
    body: JSON.stringify({
      model: MODEL,
      max_tokens: 8192,
      messages: [{ role: "user", content: prompt }],
    }),
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Claude ${res.status}: ${body.slice(0, 200)}`);
  }
  const data = await res.json();
  const text = data.content?.[0]?.text || "";
  const match = text.match(/\[[\s\S]*\]/);
  if (!match) return [];
  try { return JSON.parse(match[0]); }
  catch { return []; }
}

/**
 * findPageUrls — discover additional calendar pages from the pagination links
 * already present in a page's HTML.
 *
 * Municipal calendars (CivicPlus, The Events Calendar, etc.) render only the
 * current page's events server-side and expose the rest behind "1 2 3 4 …"
 * links. A single-page extraction therefore misses every later-dated event.
 * Rather than guessing each platform's URL scheme, we read the site's OWN
 * pagination anchors (handles ?page=N, ?paged=N, ?tribe_paged=N, /page/N/),
 * resolve them absolutely, and return pages 2..(1+maxExtra) in order.
 *
 * Returns [] when the page isn't paginated.
 */
function findPageUrls(html, baseUrl, maxExtra = 3) {
  if (!html) return [];
  let base;
  try { base = new URL(baseUrl); } catch { return []; }
  const found = new Map(); // pageNum -> absolute url
  const re =
    /href\s*=\s*["']([^"']*?(?:[?&](?:page|paged|tribe_paged|pg)=(\d+)|\/page\/(\d+))[^"']*)["']/gi;
  let m;
  while ((m = re.exec(html))) {
    const num = parseInt(m[2] || m[3], 10);
    if (!num || num < 2) continue;
    let abs;
    try { abs = new URL(m[1].replace(/&amp;/g, "&"), base).toString(); } catch { continue; }
    if (!found.has(num)) found.set(num, abs);
  }
  return [...found.entries()]
    .filter(([n]) => n >= 2 && n <= 1 + maxExtra)
    .sort((a, b) => a[0] - b[0])
    .map(([, url]) => url);
}

module.exports = { extractEventsFromHtml, findPageUrls };
