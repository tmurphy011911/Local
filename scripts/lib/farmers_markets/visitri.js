/**
 * Source module: Rhode Island farmers markets (visitri.com curated list).
 *
 * visitri's /state/farmers-markets/ page is plain server-rendered HTML (no
 * Cloudflare, unlike guide.farmfreshri.org) with ~11 curated markets. Each card
 * has a name, a schema.org PostalAddress, and a Google Maps link; the schedule
 * lives in the description prose, so we best-effort regex a day + time range and
 * leave it blank when the prose is vague (the row still surfaces by location).
 * Addresses are geocoded via Google (GOOGLE_PLACES_API_KEY in the secrets table)
 * — only ~11 calls, cheap enough to do at runtime.
 *
 * RI is Eastern (tz America/New_York).
 */

const { getSecrets } = require("../secrets");

const URL = "https://www.visitri.com/state/farmers-markets/";
const DAY = { sun: 6, mon: 0, tue: 1, wed: 2, thu: 3, fri: 4, sat: 5 };

const slug = (s) => (s || "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 60);

function pick(re, s) { const m = (s || "").match(re); return m ? m[1] : null; }

function to24(t) {
  const m = t.trim().toLowerCase().match(/(\d{1,2})(?::(\d{2}))?\s*(am|pm)/);
  if (!m) return null;
  let h = +m[1]; const mn = m[2] || "00";
  if (m[3] === "pm" && h !== 12) h += 12;
  if (m[3] === "am" && h === 12) h = 0;
  return `${String(h).padStart(2, "0")}:${mn}`;
}

// Best-effort schedule from prose: first weekday mention + first time range.
function scheduleFromProse(text, name) {
  const dayM = (text.match(/\b(sun|mon|tue|wed|thu|fri|sat)[a-z]*/i) || [])[0];
  const weekday = dayM ? DAY[dayM.slice(0, 3).toLowerCase()] : null;
  const times = (text.match(/\b\d{1,2}(?::\d{2})?\s?(?:am|pm)\b/gi) || []).map(to24).filter(Boolean);
  if (weekday == null || times.length < 2) return [];
  // Season hint from the market name (Summer/Winter); else none.
  const n = (name || "").toLowerCase();
  const yr = new Date().getUTCFullYear();
  let seasonStart = null, seasonEnd = null;
  if (n.includes("summer")) { seasonStart = `${yr}-05-01`; seasonEnd = `${yr}-10-31`; }
  else if (n.includes("winter")) { seasonStart = `${yr}-11-01`; seasonEnd = `${yr}-04-30`; }
  return [{ weekday, startLocal: times[0], endLocal: times[1], seasonStart, seasonEnd }];
}

async function geocode(address, key) {
  if (!key || !address) return null;
  const u = `https://maps.googleapis.com/maps/api/geocode/json?address=${encodeURIComponent(address)}&key=${key}`;
  try {
    const r = await fetch(u);
    const d = await r.json();
    if (d.status === "OK" && d.results[0]) {
      const { lat, lng } = d.results[0].geometry.location;
      return { lat, lng };
    }
  } catch (_) { /* ignore */ }
  return null;
}

async function fetchMarkets() {
  const { GOOGLE_PLACES_API_KEY } = await getSecrets(["GOOGLE_PLACES_API_KEY"]);
  const res = await fetch(URL, { headers: { "User-Agent": "Mozilla/5.0" } });
  if (!res.ok) throw new Error(`visitri fetch ${res.status}`);
  const html = await res.text();
  const cards = html.split(/<div class="card mb-4/).slice(1);

  const out = [];
  for (const c of cards) {
    const name = (pick(/card-title">\s*<a[^>]*>([^<]+)<\/a>/, c) || pick(/card-title">\s*([^<]+)/, c) || "").trim();
    if (!name || !/market/i.test(name)) continue;
    const street = (pick(/streetAddress">([^<]*)</, c) || "").trim();
    const region = (pick(/addressRegion">([^<]*)</, c) || "").trim(); // "City, RI, 02892"
    const body = (pick(/<div class="card-text">([\s\S]*?)<\/div>/, c) || "")
      .replace(/<[^>]+>/g, " ").replace(/&[a-z#0-9]+;/g, " ").replace(/\s+/g, " ").trim();
    const website = pick(/card-title">\s*<a href="(https?:\/\/[^"]+)"/, c);

    // City = first component of region; geocode the most specific address we have.
    const city = (region.split(",")[0] || "").trim() || null;
    const hasRealStreet = street && !/two|three|locations|see below/i.test(street);
    const geoQuery = [hasRealStreet ? street : null, region].filter(Boolean).join(", ");
    const coords = await geocode(geoQuery, GOOGLE_PLACES_API_KEY);
    if (!coords) continue; // can't place it → skip

    out.push({
      dedupePrefix: "visitri_ri",
      source: "visitri_ri_farmers_market",
      sourceId: slug(name),
      tz: "America/New_York",
      name,
      lat: coords.lat,
      lng: coords.lng,
      address: hasRealStreet ? street : null,
      city,
      state: "RI",
      zip: pick(/\b(\d{5})\b/, region),
      website: website || null,
      bio: body ? body.slice(0, 400) : null,
      imageUrl: null,
      programTags: [],
      occurrences: scheduleFromProse(body, name),
    });
  }
  return out;
}

module.exports = { fetchMarkets, label: "visitri (RI)" };
