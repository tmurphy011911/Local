/**
 * Source module: City of Philadelphia farmers markets (ArcGIS FeatureServer).
 *
 * Backs phila.gov/farmers-markets. Structured GIS layer — per-day hours, season
 * window, payment programs, address, and point geometry. Times are already
 * ET-local ("10:00" = 10 AM ET), so no tz conversion is needed here.
 *
 * Emits the same normalized market shape as the other source modules.
 */

const QUERY_URL =
  "https://services.arcgis.com/fLeGjb7u4uXqeF9q/ArcGIS/rest/services/Farmers_Markets/FeatureServer/0/query";

// ArcGIS field prefix → Python weekday (Mon=0..Sun=6).
const DAY_FIELDS = [
  ["mon", 0], ["tues", 1], ["wed", 2], ["thurs", 3], ["fri", 4], ["sat", 5], ["sun", 6],
];

const pad = (n) => String(n).padStart(2, "0");
const isYes = (v) => typeof v === "string" && v.trim().toLowerCase() === "yes";

function normalizeTime(t) {
  // ArcGIS gives "10:00" / "9:00"; normalize to "HH:MM".
  if (!t) return null;
  const m = String(t).trim().match(/^(\d{1,2}):(\d{2})/);
  return m ? `${pad(+m[1])}:${m[2]}` : null;
}

function programTags(a) {
  const tags = [];
  if (isYes(a.payment_snap)) tags.push("snap-ebt");
  if (isYes(a.payment_fmnp)) tags.push("fmnp");
  if (isYes(a.payment_philly_food_bucks)) tags.push("food-bucks");
  return tags;
}

async function fetchMarkets() {
  const url = `${QUERY_URL}?where=1%3D1&outFields=*&returnGeometry=true&outSR=4326&f=json`;
  const res = await fetch(url, { headers: { "User-Agent": "Mozilla/5.0" } });
  if (!res.ok) throw new Error(`Philly ArcGIS fetch ${res.status}`);
  const data = await res.json();
  if (data.error) throw new Error(`Philly ArcGIS error: ${JSON.stringify(data.error).slice(0, 200)}`);

  const year = new Date().getUTCFullYear();
  const out = [];
  for (const f of data.features || []) {
    const a = f.attributes || {};
    const g = f.geometry || {};
    if (g.x == null || g.y == null) continue;

    // Season: year-round markets carry no window; seasonal ones derive a
    // current-year window from opening/closing month+day.
    let seasonStart = null, seasonEnd = null;
    if (!isYes(a.season_year_round) && a.season_opening_month && a.season_closing_month) {
      seasonStart = `${year}-${pad(a.season_opening_month)}-${pad(a.season_opening_day || 1)}`;
      seasonEnd = `${year}-${pad(a.season_closing_month)}-${pad(a.season_closing_day || 28)}`;
    }

    const occurrences = [];
    for (const [prefix, weekday] of DAY_FIELDS) {
      const startLocal = normalizeTime(a[`hours_${prefix}_start`]);
      const endLocal = normalizeTime(a[`hours_${prefix}_end`]);
      if (startLocal && endLocal) occurrences.push({ weekday, startLocal, endLocal, seasonStart, seasonEnd });
    }
    if (occurrences.length === 0) continue;

    let website = (a.contact_website || "").trim() || null;
    if (website && !/^https?:\/\//i.test(website)) website = `https://${website}`;

    out.push({
      dedupePrefix: "phl_arcgis",
      source: "phl_arcgis_farmers_market",
      sourceId: String(a.objectid),
      name: (a.name || "").trim(),
      lat: g.y,
      lng: g.x,
      address: (a.address || "").trim() || null,
      city: "Philadelphia",
      state: "PA",
      zip: (a.zip || "").trim() || null,
      website,
      bio: a.operator ? `Operated by ${a.operator}.` : null,
      imageUrl: null,
      programTags: programTags(a),
      occurrences,
    });
  }
  return out;
}

module.exports = { fetchMarkets, label: "Philadelphia ArcGIS (PA)" };
