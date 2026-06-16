/**
 * Source module: MarketSpread association directory (Massachusetts).
 *
 * One endpoint embeds `window.farmData.members = [...]` with every market in the
 * association, fully structured. See crawlers.md → marketspread-farmers-markets.
 *
 * Emits normalized market records (see sync_farmers_markets.js for the shape).
 * Times: MarketSpread's operating_hours datetimes are UTC instants (Waltham
 * 13:30 UTC = 9:30 AM EDT). We normalize them to ET-local "HH:MM" here so every
 * source feeds the shared builder in the same (Eastern wall-clock) convention.
 */

// MarketSpread association directories. Each is a regional federation whose
// `?content` members endpoint embeds `window.farmData.members`. member_id is
// global across associations, so dedupe stays clean even if a market appears in
// two. `defaultState` only fills rows whose own state is null.
const ASSOCIATIONS = [
  { path: "49/16", defaultState: "MA", referer: "https://www.massfarmersmarkets.org/" }, // Mass Farmers Markets
  { path: "152/19", defaultState: "NY", referer: "https://www.nyfarmersmarket.com/" }, // Farmers Market Federation of NY
];
const membersUrl = (path) => `https://marketspread.com/widget/association/${path}/members/?content`;

const PY_DAY = { Monday: 0, Tuesday: 1, Wednesday: 2, Thursday: 3, Friday: 4, Saturday: 5, Sunday: 6 };

function parseFarmData(html) {
  const key = "window.farmData.members =";
  const at = html.indexOf(key);
  if (at < 0) throw new Error("window.farmData.members not found in MarketSpread payload");
  const start = at + key.length;
  let depth = 0, inStr = false, esc = false, begin = -1, end = -1;
  for (let p = start; p < html.length; p++) {
    const c = html[p];
    if (begin < 0) { if (c === "[") { begin = p; depth = 1; } continue; }
    if (esc) { esc = false; continue; }
    if (c === "\\") { esc = true; continue; }
    if (c === '"') { inStr = !inStr; continue; }
    if (inStr) continue;
    if (c === "[" || c === "{") depth++;
    else if (c === "]" || c === "}") { depth--; if (depth === 0) { end = p; break; } }
  }
  if (end < 0) throw new Error("could not brace-match the MarketSpread members array");
  return JSON.parse(html.slice(begin, end + 1));
}

// UTC "HH:MM:SS" → ET-local "HH:MM", converted on a representative in-season
// date so DST lands the way the market posts it (markets run in EDT).
function utcToEtLocal(utcTime, refDateStr) {
  const d = new Date(`${refDateStr}T${utcTime}Z`);
  const s = d.toLocaleString("en-US", {
    timeZone: "America/New_York", hour: "2-digit", minute: "2-digit", hour12: false,
  });
  return s.replace(/^24:/, "00:");
}

// Many NY rows have a null `city` but a full US address ("1406 Townsend Ave,
// Bronx, NY 10452, USA"). Pull the city = the component right before "ST ZIP".
function cityFromAddress(addr) {
  if (!addr) return null;
  const parts = String(addr).split(",").map((s) => s.trim());
  const idx = parts.findIndex((p) => /^[A-Z]{2}\s+\d{5}/.test(p));
  return idx > 0 ? parts[idx - 1] || null : null;
}

function programTags(programs) {
  return [...new Set((programs || []).flatMap((p) => {
    const n = (p.name || "").toUpperCase();
    if (n.includes("SNAP") || n.includes("EBT")) return ["snap-ebt"];
    if (n.includes("WIC")) return ["wic"];
    return [];
  }))];
}

async function fetchAssociation(assoc) {
  const res = await fetch(membersUrl(assoc.path), {
    headers: {
      "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36",
      Referer: assoc.referer,
      Accept: "text/html",
    },
  });
  if (!res.ok) throw new Error(`MarketSpread ${assoc.path} fetch ${res.status}`);
  const members = parseFarmData(await res.text());

  const out = [];
  for (const m of members) {
    const loc = m.location || {};
    if (loc.lat == null || loc.lng == null) continue;

    const occurrences = (m.operating_hours || [])
      .filter((h) => Number.isInteger(h.weekday) && h.start_datetime)
      .map((h) => {
        const seasonStart = h.is_seasonal ? h.season_start || null : null;
        const seasonEnd = h.is_seasonal ? h.season_end || null : null;
        // Reference date for the UTC→ET conversion: season_start (in-season →
        // EDT) or a mid-summer fallback in the current year.
        const ref = seasonStart || `${new Date().getUTCFullYear()}-07-01`;
        const t = (dt) => (dt && dt.includes("T") ? dt.split("T")[1].slice(0, 8) : "09:00:00");
        return {
          weekday: h.weekday,
          startLocal: utcToEtLocal(t(h.start_datetime), ref),
          endLocal: utcToEtLocal(t(h.end_datetime), ref),
          seasonStart,
          seasonEnd,
        };
      });

    out.push({
      dedupePrefix: "marketspread", // preserves existing live MA rows' dedupe_hash
      source: "marketspread_farmers_market",
      sourceId: String(m.member_id),
      name: m.name,
      lat: loc.lat,
      lng: loc.lng,
      address: m.address || null,
      city: m.city || cityFromAddress(m.address) || null,
      state: m.state || assoc.defaultState,
      zip: m.zip || null,
      website: (m.website_url || "").trim() || m.url || null,
      bio: (m.bio || "").trim() || null,
      imageUrl: m.logo_path || null,
      programTags: programTags(m.programs),
      occurrences,
    });
  }
  return out;
}

async function fetchMarkets() {
  const all = [];
  const seen = new Set();
  for (const assoc of ASSOCIATIONS) {
    const markets = await fetchAssociation(assoc);
    // member_id is global; if a market is listed in two federations, keep the
    // first (dedupe_hash would collapse them anyway, but avoid double work).
    for (const m of markets) {
      if (seen.has(m.sourceId)) continue;
      seen.add(m.sourceId);
      all.push(m);
    }
  }
  return all;
}

module.exports = { fetchMarkets, label: "MarketSpread (MA, NY)", PY_DAY };
