/**
 * split_aggregator_events.js — re-attribute events from aggregator venues
 * (Harvard Square, Creative Legion, etc.) to the actual venues where they
 * occur, by parsing JSON-LD on each event's source_url.
 *
 * Pipeline per event:
 *   1. Fetch source_url (Scrapfly first → falls back to Chrome-UA fetch).
 *   2. Parse JSON-LD <script type="application/ld+json"> blocks; find
 *      `@type=Event` with `location.{name, address}`.
 *   3. Title-pattern fallback ("X at Y", "X presents Y", "in the
 *      performance space" → Club Passim, etc.) when JSON-LD is absent.
 *   4. Fuzzy-match extracted venue name against the venues table
 *      (lowercase + Unicode-fold, optionally geo-filtered to ≤0.75mi).
 *   5. UPDATE event: venue_name + venue_address + venue_lat/lng.
 *      Leave events tagged to the aggregator if no extraction succeeded
 *      (covers genuine plaza events — Oktoberfest, busking festival, etc.).
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/split_aggregator_events.js                 # dry run, all is_aggregator=true
 *   SUPABASE_SERVICE_KEY=ey... node scripts/split_aggregator_events.js --venue=<id>    # single venue
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 node scripts/split_aggregator_events.js         # apply
 *   SUPABASE_SERVICE_KEY=ey... LIMIT=10 ... node scripts/split_aggregator_events.js    # cap events
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");
const SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").replace(/\s/g, "");
const WRITE = process.env.WRITE === "1";
const LIMIT = parseInt(process.env.LIMIT || "0", 10) || null;

const venueArg = process.argv.find((a) => a.startsWith("--venue="));
const TARGET_VENUE_ID = venueArg ? venueArg.split("=")[1] : null;

if (!SERVICE_KEY) { console.error("Missing SUPABASE_SERVICE_KEY"); process.exit(1); }

let SCRAPFLY_KEY = null;

const headers = {
  apikey: SERVICE_KEY,
  Authorization: `Bearer ${SERVICE_KEY}`,
  "Content-Type": "application/json",
};

const CHROME_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0 Safari/537.36";

// ── Fetching ────────────────────────────────────────────────────────────

async function fetchHtml(url) {
  // Scrapfly first if we have a key — handles Cloudflare et al.
  if (SCRAPFLY_KEY) {
    const sUrl = `https://api.scrapfly.io/scrape?key=${SCRAPFLY_KEY}` +
                 `&url=${encodeURIComponent(url)}&country=us&asp=true`;
    const res = await fetch(sUrl);
    if (res.ok) {
      const json = await res.json();
      if (json.result?.content) return json.result.content;
    } else {
      // Quietly fall through to direct fetch if Scrapfly fails on a single page
      console.warn(`  scrapfly ${res.status} on ${url} — falling back to direct`);
    }
  }
  const res = await fetch(url, { headers: { "User-Agent": CHROME_UA } });
  if (!res.ok) throw new Error(`fetch ${url}: ${res.status}`);
  return res.text();
}

// ── JSON-LD extraction ──────────────────────────────────────────────────

function extractJsonLdEvent(html) {
  const blocks = [...html.matchAll(/<script[^>]*type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/gi)];
  for (const b of blocks) {
    let raw = b[1].trim();
    // WordPress / Yoast embed multiple objects in @graph; just JSON.parse and walk.
    let parsed;
    try { parsed = JSON.parse(raw); } catch { continue; }
    const candidates = [];
    const walk = (n) => {
      if (!n) return;
      if (Array.isArray(n)) { n.forEach(walk); return; }
      if (typeof n !== "object") return;
      const t = n["@type"];
      if (t === "Event" || (Array.isArray(t) && t.includes("Event"))) candidates.push(n);
      if (n["@graph"]) walk(n["@graph"]);
    };
    walk(parsed);
    for (const ev of candidates) {
      const loc = ev.location;
      if (!loc) continue;
      const name = decodeEntities(loc.name || "").trim();
      if (!name) continue;
      const addr = loc.address || {};
      const street = (addr.streetAddress || "").trim();
      const city = (addr.addressLocality || "").trim();
      const state = (addr.addressRegion || "").trim();
      const zip = (addr.postalCode || "").trim();
      const fullAddr = [street, city, state, zip].filter(Boolean).join(", ");
      return { name, address: fullAddr || null, city: city || null, state: state || null };
    }
  }
  return null;
}

function decodeEntities(s) {
  return (s || "")
    .replace(/&amp;/g, "&").replace(/&#039;/g, "'").replace(/&apos;/g, "'")
    .replace(/&quot;/g, '"').replace(/&#8217;/g, "’")
    .replace(/&lt;/g, "<").replace(/&gt;/g, ">");
}

// ── Title-pattern fallback ──────────────────────────────────────────────

// Aggregator-specific shorthand (fill in as we discover patterns).
// Order matters — first match wins.
const TITLE_HINTS = [
  // "in the performance space" / "in the lounge" → Club Passim
  { match: /in the (performance space|lounge)\b/i, venueName: "Club Passim" },
  // Harvard Square deep-links — these venues rarely include "at <name>" in
  // their titles, so we pattern-match the artist/series instead.
  { match: /\bregattabar\b/i, venueName: "Regattabar" },
  { match: /\bthe sinclair\b/i, venueName: "The Sinclair" },
  { match: /\bsanders theatre\b/i, venueName: "Sanders Theatre" },
  { match: /\bbrattle theatre\b/i, venueName: "Brattle Theatre" },
  { match: /\bbeat brew hall\b/i, venueName: "Beat Brew Hall" },
  { match: /\bclub passim\b/i, venueName: "Club Passim" },
];

function parseTitleVenue(title) {
  if (!title) return null;
  for (const h of TITLE_HINTS) if (h.match.test(title)) return { name: h.venueName };

  // "<event> at <venue>" or "<event> at the <venue>"
  let m = title.match(/\bat\s+(?:the\s+)?([A-Z][\w'.&-]+(?:\s+[A-Z][\w'.&-]+){0,5})/);
  if (m) return { name: m[1].trim() };

  // "<venue> presents <event>"
  m = title.match(/^([A-Z][\w'.&-]+(?:\s+[A-Z][\w'.&-]+){0,4})\s+presents\b/i);
  if (m) return { name: m[1].trim() };

  return null;
}

// ── Venue matching ──────────────────────────────────────────────────────

function normalizeName(s) {
  return (s || "")
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/[^\w\s]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function haversineMi(a, b, c, d) {
  if ([a,b,c,d].some(v => v == null)) return Infinity;
  const toRad = (x) => x * Math.PI / 180;
  const R = 3958.8;
  const dLat = toRad(c - a), dLng = toRad(d - b);
  const x = Math.sin(dLat/2)**2 + Math.cos(toRad(a)) * Math.cos(toRad(c)) * Math.sin(dLng/2)**2;
  return 2 * R * Math.asin(Math.sqrt(x));
}

let venuesCache = null;
async function loadVenues() {
  if (venuesCache) return venuesCache;
  const all = [];
  let offset = 0;
  while (true) {
    const url = `${SUPABASE_URL}/rest/v1/venues?select=id,name,city,state,address,lat,lng,is_aggregator&offset=${offset}&limit=1000`;
    const res = await fetch(url, { headers });
    if (!res.ok) throw new Error(`venues: ${res.status} ${await res.text()}`);
    const rows = await res.json();
    all.push(...rows);
    if (rows.length < 1000) break;
    offset += 1000;
  }
  venuesCache = all.map(v => ({ ...v, _norm: normalizeName(v.name) }));
  return venuesCache;
}

// Match extracted name against venues table. Prefer exact normalized match
// within geo radius; otherwise a Jaccard-token match in same city.
async function matchVenue(name, anchorLat, anchorLng) {
  if (!name) return null;
  const venues = await loadVenues();
  const norm = normalizeName(name);
  if (!norm) return null;

  // Exact normalized name match — within 5mi of anchor if anchor known
  const exact = venues.filter(v => v._norm === norm && !v.is_aggregator);
  if (exact.length === 1) return exact[0];
  if (exact.length > 1 && anchorLat != null) {
    const sorted = exact
      .map(v => ({ v, d: haversineMi(anchorLat, anchorLng, v.lat, v.lng) }))
      .sort((a,b) => a.d - b.d);
    if (sorted[0].d <= 5) return sorted[0].v;
  }
  if (exact.length >= 1) return exact[0];

  // Jaccard fallback — substantial token overlap
  const wantTokens = new Set(norm.split(" ").filter(t => t.length >= 3));
  if (wantTokens.size < 2) return null;
  let best = null, bestScore = 0;
  for (const v of venues) {
    if (v.is_aggregator) continue;
    const haveTokens = new Set(v._norm.split(" ").filter(t => t.length >= 3));
    if (haveTokens.size < 2) continue;
    const inter = [...wantTokens].filter(t => haveTokens.has(t)).length;
    const union = new Set([...wantTokens, ...haveTokens]).size;
    const j = inter / union;
    if (j >= 0.7 && j > bestScore) { best = v; bestScore = j; }
  }
  return best;
}

// ── Main ────────────────────────────────────────────────────────────────

async function fetchAggregatorVenues() {
  let url = `${SUPABASE_URL}/rest/v1/venues?is_aggregator=eq.true&select=id,name,city,state,lat,lng`;
  if (TARGET_VENUE_ID) url = `${SUPABASE_URL}/rest/v1/venues?id=eq.${TARGET_VENUE_ID}&select=id,name,city,state,lat,lng`;
  const res = await fetch(url, { headers });
  if (!res.ok) throw new Error(`agg venues: ${res.status}`);
  return res.json();
}

async function fetchEventsFor(venue) {
  // Filter by start_at >= 7 days ago (uses the indexed column to avoid a
  // sequential scan that times out for high-cardinality venue_name strings).
  const since = new Date(Date.now() - 7 * 86400 * 1000).toISOString().slice(0, 10);
  let allRows = [];
  let offset = 0;
  while (true) {
    const url =
      `${SUPABASE_URL}/rest/v1/events` +
      `?select=id,title,description,source_url,venue_name,venue_address,venue_lat,venue_lng,start_at` +
      `&venue_name=eq.${encodeURIComponent(venue.name)}` +
      `&is_active=eq.true` +
      `&start_at=gte.${since}` +
      `&order=start_at.asc&offset=${offset}&limit=1000`;
    const res = await fetch(url, { headers });
    if (!res.ok) throw new Error(`events: ${res.status} ${await res.text()}`);
    const rows = await res.json();
    allRows.push(...rows);
    if (rows.length < 1000) break;
    offset += 1000;
  }
  // Dedup if multiple aggregator rows share the same name (e.g. 4 Creative Legions)
  const seen = new Set();
  allRows = allRows.filter(r => !seen.has(r.id) && seen.add(r.id));
  if (LIMIT) allRows = allRows.slice(0, LIMIT);
  return allRows;
}

async function patchEvent(id, patch) {
  const url = `${SUPABASE_URL}/rest/v1/events?id=eq.${id}`;
  const res = await fetch(url, {
    method: "PATCH",
    headers: { ...headers, Prefer: "return=minimal" },
    body: JSON.stringify({ ...patch, updated_at: new Date().toISOString() }),
  });
  if (!res.ok) throw new Error(`patch ${id}: ${res.status} ${await res.text()}`);
}

async function processEvent(ev, agg) {
  // 1. JSON-LD via source_url
  let extracted = null;
  if (ev.source_url) {
    try {
      const html = await fetchHtml(ev.source_url);
      extracted = extractJsonLdEvent(html);
    } catch (e) {
      console.warn(`    fetch failed: ${e.message}`);
    }
  }
  // 2. Title fallback
  if (!extracted) {
    const t = parseTitleVenue(ev.title);
    if (t) extracted = { name: t.name, address: null };
  }
  if (!extracted) return { ev, status: "no-extraction" };

  // Don't reattribute back to the aggregator itself
  if (normalizeName(extracted.name) === normalizeName(agg.name)) {
    return { ev, status: "self-attribution" };
  }

  // 3. Match against existing venues
  const match = await matchVenue(extracted.name, agg.lat, agg.lng);

  // Only override lat/lng when we have a real venue match. Otherwise keep
  // the aggregator's coords as a reasonable proxy — events still surface in
  // geo-bounded carousel queries for the area.
  const patch = {
    venue_name: match?.name || extracted.name,
    venue_address: extracted.address || match?.address || ev.venue_address,
  };
  if (match?.lat != null && match?.lng != null) {
    patch.venue_lat = match.lat;
    patch.venue_lng = match.lng;
  }

  return { ev, status: "ok", extracted, match, patch };
}

async function main() {
  console.log(`Mode: ${WRITE ? "WRITE" : "DRY RUN"}`);

  // Load Scrapfly key (optional but preferred for Cloudflare-protected sources)
  try {
    const secrets = await getSecrets(["SCRAPFLY_API_KEY"]);
    SCRAPFLY_KEY = secrets.SCRAPFLY_API_KEY || null;
    console.log(`Scrapfly: ${SCRAPFLY_KEY ? "available" : "not configured — will use direct fetch with Chrome UA"}`);
  } catch (e) {
    console.warn("Scrapfly key fetch failed, continuing with direct fetch:", e.message);
  }

  const aggs = await fetchAggregatorVenues();
  if (aggs.length === 0) {
    console.log("No aggregator venues found. Mark venues with is_aggregator=true first.");
    return;
  }
  console.log(`Aggregators: ${aggs.map(a => a.name).join(", ")}`);

  const tally = { ok: 0, "no-extraction": 0, "self-attribution": 0, matched: 0, unmatched: 0 };
  const samples = [];

  for (const agg of aggs) {
    const events = await fetchEventsFor(agg);
    console.log(`\n=== ${agg.name} (${events.length} events) ===`);
    let i = 0;
    for (const ev of events) {
      i++;
      process.stdout.write(`  [${i}/${events.length}] ${ev.title.slice(0, 60)}\r`);
      const result = await processEvent(ev, agg);
      tally[result.status] = (tally[result.status] || 0) + 1;
      if (result.status === "ok") {
        if (result.match) tally.matched++; else tally.unmatched++;
        if (samples.length < 30) {
          samples.push({
            title: ev.title,
            extracted: result.extracted.name,
            address: result.extracted.address,
            matchedTo: result.match ? `${result.match.name} (${result.match.id.slice(0, 8)})` : "NO MATCH — would create stub",
          });
        }
        if (WRITE) await patchEvent(ev.id, result.patch);
      }
      // Tiny pacing
      if (SCRAPFLY_KEY ? i % 5 === 0 : true) await new Promise(r => setTimeout(r, 100));
    }
    console.log("");
  }

  console.log("\n=== Summary ===");
  for (const [k, v] of Object.entries(tally)) console.log(`  ${k}: ${v}`);
  console.log("\n=== Sample extractions ===");
  for (const s of samples) {
    console.log(`  ${s.title.slice(0, 60)}`);
    console.log(`    → ${s.extracted}${s.address ? ` (${s.address})` : ""}`);
    console.log(`    matched: ${s.matchedTo}`);
  }

  if (!WRITE) console.log("\nDRY RUN — pass WRITE=1 to apply.");
}

main().catch(e => { console.error(e); process.exit(1); });
