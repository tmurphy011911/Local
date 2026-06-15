/**
 * Source module: New Jersey farmers markets (Edible Jersey statewide guide).
 *
 * ediblejersey.ediblecommunities.com/shop/shop-new-jersey-farmers-market-guide…
 * is (despite the /shop/ URL) a full WordPress content page listing ~130 NJ
 * markets by county. Each is a <p class="wp-block-paragraph"> with:
 *   <strong>CITY – Market Name</strong>
 *   <br>Day., time, Month dd–Month dd        (schedule + season)
 *   <br>Venue name,
 *   <br>Street address
 *   <br>…payment-program <img alt="snap-ebt|wic|food-bucks|senior|…">
 * Plain HTML (no Cloudflare). Addresses geocoded via Google (GOOGLE_PLACES_API_KEY
 * in secrets, ~130 runtime calls). NJ is Eastern.
 */

const { getSecrets } = require("../secrets");

const URL = "https://ediblejersey.ediblecommunities.com/shop/shop-new-jersey-farmers-market-guide-by-edible-jersey/";
const DAY = { sun: 6, mon: 0, tue: 1, wed: 2, thu: 3, fri: 4, sat: 5 };
const MON = { jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6, jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12 };

const slug = (s) => (s || "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 60);
const titleCase = (s) => s.toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());
const strip = (h) => h.replace(/<[^>]+>/g, " ").replace(/&amp;/g, "&").replace(/&[a-z#0-9]+;/g, " ").replace(/\s+/g, " ").trim();

function parseDays(s) {
  const out = [];
  for (const tok of s.toLowerCase().split(/[,/&]| and /)) {
    const m = tok.trim().match(/\b(sun|mon|tue|wed|thu|fri|sat)/);
    if (m) out.push(DAY[m[1]]);
  }
  return [...new Set(out)];
}

function to24(t, ampmHint) {
  let m = t.trim().toLowerCase().match(/(\d{1,2})(?::(\d{2}))?\s*(am|pm)?/);
  if (t.trim().toLowerCase() === "noon") return "12:00";
  if (!m) return null;
  let h = +m[1]; const mn = m[2] || "00"; const ap = m[3] || ampmHint;
  if (ap === "pm" && h !== 12) h += 12;
  if (ap === "am" && h === 12) h = 0;
  return `${String(h).padStart(2, "0")}:${mn}`;
}

function parseTime(s) {
  // "4-6pm", "8am–noon", "8:30–11:30am"
  const m = s.match(/(\d{1,2}(?::\d{2})?\s*(?:am|pm)?|noon)\s*[-–]\s*(\d{1,2}(?::\d{2})?\s*(?:am|pm)?|noon)/i);
  if (!m) return [null, null];
  const endAp = (m[2].toLowerCase().match(/(am|pm)/) || [])[1];
  return [to24(m[1], endAp), to24(m[2])];
}

function parseSeason(s, year) {
  if (/year[- ]?round/i.test(s)) return [null, null];
  const re = /([a-z]{3,9})\.?\s*(\d{1,2})\s*[-–]\s*([a-z]{3,9})?\.?\s*(\d{1,2})/i;
  const m = s.match(re);
  if (!m) return [null, null];
  const m1 = MON[m[1].slice(0, 3).toLowerCase()];
  const m2 = MON[(m[3] || m[1]).slice(0, 3).toLowerCase()];
  if (!m1 || !m2) return [null, null];
  return [`${year}-${String(m1).padStart(2, "0")}-${m[2].padStart(2, "0")}`,
          `${year}-${String(m2).padStart(2, "0")}-${m[4].padStart(2, "0")}`];
}

async function geocode(address, key) {
  if (!key || !address) return null;
  try {
    const r = await fetch(`https://maps.googleapis.com/maps/api/geocode/json?address=${encodeURIComponent(address)}&key=${key}`);
    const d = await r.json();
    if (d.status === "OK" && d.results[0]) return d.results[0].geometry.location;
  } catch (_) { /* ignore */ }
  return null;
}

async function fetchMarkets() {
  const { GOOGLE_PLACES_API_KEY } = await getSecrets(["GOOGLE_PLACES_API_KEY"]);
  const res = await fetch(URL, { headers: { "User-Agent": "Mozilla/5.0" } });
  if (!res.ok) throw new Error(`edible_nj fetch ${res.status}`);
  const html = await res.text();
  const year = new Date().getUTCFullYear();

  const paras = html.match(/<p class="wp-block-paragraph">\s*<strong>[\s\S]*?<\/p>/g) || [];
  const out = [];
  for (const p of paras) {
    const rawName = strip((p.match(/<strong>([\s\S]*?)<\/strong>/) || [])[1] || "");
    if (!rawName) continue;
    const after = p.split(/<\/strong>/)[1] || "";
    const lines = after.split(/<br\s*\/?>/i).map(strip).filter(Boolean);
    if (!lines.length) continue;

    // schedule may wrap onto line 2 ("Thurs., 8:30–11:30am," + "June 12–Aug. 28")
    const schedText = lines.slice(0, 2).join(" ");
    const weekdays = parseDays(lines[0]);
    if (!weekdays.length) continue; // not a market entry (intro/notes)
    const [startLocal, endLocal] = parseTime(schedText);
    const [seasonStart, seasonEnd] = parseSeason(schedText, year);

    // City = the part before "–" in the name; rest is the market label.
    const dash = rawName.split(/[–-]/);
    const city = titleCase(dash[0].trim());
    const name = dash.length > 1 ? `${titleCase(dash[0].trim())} – ${dash.slice(1).join("-").trim()}` : `${titleCase(rawName)} Farmers Market`;

    // Address: the non-schedule, non-URL lines (venue + street).
    const addrLines = lines.slice(1).filter((l) => !/^https?:|\.(org|com|net)\b/i.test(l) && !/^[•\s]*$/.test(l));
    const address = addrLines.slice(0, 2).join(", ") || null;
    const website = (after.match(/href="(https?:\/\/[^"]+)"/) || [])[1] || null;

    const programTags = [];
    if (/alt="snap-ebt/i.test(p)) programTags.push("snap-ebt");
    if (/alt="wic/i.test(p)) programTags.push("wic");
    if (/alt="food-bucks/i.test(p)) programTags.push("food-bucks");
    if (/alt="senior/i.test(p)) programTags.push("senior");

    const coords = await geocode([address, city, "NJ"].filter(Boolean).join(", "), GOOGLE_PLACES_API_KEY);
    if (!coords) continue;

    const occurrences = (startLocal && endLocal)
      ? weekdays.map((w) => ({ weekday: w, startLocal, endLocal, seasonStart, seasonEnd }))
      : [];

    out.push({
      dedupePrefix: "edible_nj",
      source: "edible_nj_farmers_market",
      sourceId: slug(rawName),
      tz: "America/New_York",
      name,
      lat: coords.lat,
      lng: coords.lng,
      address,
      city,
      state: "NJ",
      zip: null,
      website,
      bio: null,
      imageUrl: null,
      programTags,
      occurrences,
    });
  }
  return out;
}

module.exports = { fetchMarkets, label: "Edible Jersey (NJ)" };
