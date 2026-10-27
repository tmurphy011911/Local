// Event selection for each post type.
//
// Pulls the right time window of active Boston-metro events from PostgREST,
// dedupes recurring rows, ranks by venue rank then start time, and spreads
// categories so a carousel isn't eight concerts in a row. All date math is
// done in America/New_York so "today" / "this weekend" / "this month" mean what
// a Bostonian means, regardless of the (UTC) runner clock.

import { selectEvents } from "./lib/supabase.mjs";
import { thumbUrl } from "./lib/images.mjs";
import { BOSTON, RADIUS_MILES, HEADLINE_CAP } from "./lib/config.mjs";

const TZ = "America/New_York";

// ── timezone helpers ────────────────────────────────────────────────────────
function tzOffsetMs(date) {
  const dtf = new Intl.DateTimeFormat("en-US", {
    timeZone: TZ, hour12: false,
    year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit",
  });
  const p = dtf.formatToParts(date).reduce((a, x) => (a[x.type] = x.value, a), {});
  const asUTC = Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute, +p.second);
  return asUTC - date.getTime();
}
// The UTC instant whose ET wall-clock is y-m-d hh:mm:ss (DST-correct).
function etInstant(y, m, d, hh = 0, mm = 0, ss = 0) {
  const naive = Date.UTC(y, m - 1, d, hh, mm, ss);
  return new Date(naive - tzOffsetMs(new Date(naive)));
}
function etNow() {
  const p = new Intl.DateTimeFormat("en-US", {
    timeZone: TZ, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", hour12: false,
  }).formatToParts(new Date()).reduce((a, x) => (a[x.type] = x.value, a), {});
  const y = +p.year, m = +p.month, d = +p.day;
  return { y, m, d, hour: +p.hour, dow: new Date(Date.UTC(y, m - 1, d)).getUTCDay() };
}
function ymdAddDays(y, m, d, n) {
  const dt = new Date(Date.UTC(y, m - 1, d + n));
  return { y: dt.getUTCFullYear(), m: dt.getUTCMonth() + 1, d: dt.getUTCDate() };
}

export function windowFor(postType, now = etNow()) {
  if (postType === "daily") {
    return { from: etInstant(now.y, now.m, now.d, 0, 0, 0), to: etInstant(now.y, now.m, now.d, 23, 59, 59) };
  }
  if (postType === "weekend") {
    const daysToFri = (5 - now.dow + 7) % 7; // Thu run -> 1; already Fri -> 0
    const fri = ymdAddDays(now.y, now.m, now.d, daysToFri);
    const sun = ymdAddDays(fri.y, fri.m, fri.d, 2);
    return { from: etInstant(fri.y, fri.m, fri.d, 0, 0, 0), to: etInstant(sun.y, sun.m, sun.d, 23, 59, 59) };
  }
  if (postType === "monthly") {
    const last = new Date(Date.UTC(now.y, now.m, 0)).getUTCDate();
    return { from: etInstant(now.y, now.m, 1, 0, 0, 0), to: etInstant(now.y, now.m, last, 23, 59, 59) };
  }
  throw new Error(`unknown post type: ${postType}`);
}

// ── labels ──────────────────────────────────────────────────────────────────
function fmt(iso, opts) {
  return new Intl.DateTimeFormat("en-US", { timeZone: TZ, ...opts }).format(new Date(iso));
}
function timeLabel(iso) { return fmt(iso, { hour: "numeric", minute: "2-digit", hour12: true }); }
function etHour(iso) { return +fmt(iso, { hour: "2-digit", hour12: false }); }

function whenLabel(postType, iso) {
  if (!iso) return "Ongoing";
  if (postType === "daily") return (etHour(iso) < 16 ? "Today · " : "Tonight · ") + timeLabel(iso);
  if (postType === "weekend") return fmt(iso, { weekday: "short" }) + " · " + timeLabel(iso);
  return fmt(iso, { month: "short", day: "numeric" }) + " · " + timeLabel(iso); // monthly
}

const ACCENT_BY_CAT = {
  music: "plum", nightlife: "plum", comedy: "coral", theatre: "coral", theater: "coral",
  art: "sky", arts: "sky", museum: "sky", museums: "sky",
  food: "sun", dining: "sun", "farmers-market": "sun", markets: "sun",
  outdoors: "sage", sports: "sage", tours: "sage", kids: "sun", libraries: "sky", experiences: "coral",
};
const GENERIC = new Set(["always_on", "miscellaneous", "other", "", null, undefined]);

function categoryLabel(row) {
  const sub = (row.subcategory || "").toLowerCase();
  const cat = (row.category || "").toLowerCase();
  const pick = GENERIC.has(sub) ? cat : sub;
  if (GENERIC.has(pick)) return "Event";
  return pick.replace(/[-_]/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
}
function accentKey(row) {
  const sub = (row.subcategory || "").toLowerCase();
  const cat = (row.category || "").toLowerCase();
  return ACCENT_BY_CAT[sub] || ACCENT_BY_CAT[cat] || "coral";
}
function isFree(row) {
  if (row.is_free === true) return true;
  const p = row.price ?? row.price_min ?? row.min_price;
  return p === 0 || p === "0" || p === "0.00";
}

// ── selection ───────────────────────────────────────────────────────────────
function normTitle(s) { return (s || "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim(); }

function pickDiverse(sorted, limit) {
  const perCat = {}, picked = [], leftover = [];
  for (const row of sorted) {
    const c = (row.subcategory || row.category || "other").toLowerCase();
    if (picked.length < limit && (perCat[c] || 0) < 3) {
      picked.push(row); perCat[c] = (perCat[c] || 0) + 1;
    } else leftover.push(row);
  }
  while (picked.length < limit && leftover.length) picked.push(leftover.shift());
  return picked;
}

export async function getPost(postType) {
  const { from, to } = windowFor(postType);
  const lat = BOSTON.lat, lng = BOSTON.lng;
  const latD = RADIUS_MILES / 69.0, lngD = RADIUS_MILES / 50.0;

  const rows = await selectEvents([
    ["select", "*"],
    ["is_active", "eq.true"],
    ["image_url", "not.is.null"],
    ["start_at", `gte.${from.toISOString()}`],
    ["start_at", `lte.${to.toISOString()}`],
    ["venue_lat", `gte.${lat - latD}`],
    ["venue_lat", `lte.${lat + latD}`],
    ["venue_lng", `gte.${lng - lngD}`],
    ["venue_lng", `lte.${lng + lngD}`],
    ["order", "rank.asc.nullslast,start_at.asc"],
    ["limit", "120"],
  ]);

  // dedupe recurring rows (same title+venue across the window) — keep earliest
  const seen = new Map();
  for (const r of rows) {
    if (!r.title || !r.image_url) continue;
    const k = normTitle(r.title) + "|" + normTitle(r.venue_name);
    if (!seen.has(k)) seen.set(k, r);
  }
  const deduped = [...seen.values()];

  const picked = pickDiverse(deduped, HEADLINE_CAP[postType]);

  const events = picked.map((r) => ({
    title: r.title.trim(),
    venueName: (r.venue_name || "Boston").trim(),
    hood: (r.neighborhood || r.venue_neighborhood || r.city || "Boston").trim(),
    when: whenLabel(postType, r.start_at),
    category: categoryLabel(r),
    accent: accentKey(r),
    free: isFree(r),
    photo: thumbUrl(r.image_url, 1080, 80),
  }));

  return { postType, window: { from: from.toISOString(), to: to.toISOString() }, events, total: deduped.length };
}
