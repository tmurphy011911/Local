// Event selection for each post type.
//
// Pulls the right time window of active Boston-metro events from PostgREST,
// dedupes repeat rows, drops unclassified / no-public-interest / virtual junk,
// ranks the rest by appeal (shows and one-off nights over classes and weekly
// repeats), and spreads categories so a carousel isn't eight concerts in a
// row. All date math is
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
  // SOCIAL_DATE=YYYY-MM-DD previews another day's post (manual runs only).
  const o = /^(\d{4})-(\d{2})-(\d{2})$/.exec(process.env.SOCIAL_DATE || "");
  if (o) {
    const y = +o[1], m = +o[2], d = +o[3];
    return { y, m, d, hour: 11, dow: new Date(Date.UTC(y, m - 1, d)).getUTCDay() };
  }
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

function whenLabel(postType, iso, timeKnown = true) {
  if (!iso) return "Ongoing";
  const at = timeKnown ? " · " + timeLabel(iso) : ""; // skip bogus midnight when time unknown
  if (postType === "daily") {
    if (!timeKnown) return "Today";
    return (etHour(iso) < 16 ? "Today" : "Tonight") + " · " + timeLabel(iso);
  }
  if (postType === "weekend") return fmt(iso, { weekday: "short" }) + at;
  return fmt(iso, { month: "short", day: "numeric" }) + at; // monthly
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

// Plain-text, length-capped event blurb for the card. Strips HTML/entities,
// collapses whitespace, and trims to a word boundary so it fits the 3-line
// clamp. Returns "" when the row has no usable description.
function cleanDesc(s) {
  if (!s) return "";
  let t = String(s)
    .replace(/<[^>]*>/g, " ")
    .replace(/&nbsp;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&#3[49];|&rsquo;|&lsquo;|&apos;/gi, "'")
    .replace(/&quot;|&ldquo;|&rdquo;/gi, '"')
    .replace(/&[a-z#0-9]+;/gi, " ")
    .replace(/\s+/g, " ")
    .trim();
  // Ticketing house-policy text isn't a description; show no blurb instead.
  if (/seated at the discretion|house manager|latecomers|all sales are final|no refunds|bag policy|clear bag/i.test(t)) return "";
  const MAX = 160;
  if (t.length > MAX) t = t.slice(0, MAX).replace(/\s+\S*$/, "").trim() + "…";
  return t;
}

const MUSIC_SUBS = new Set([
  "music", "rock", "pop", "indie", "alternative", "jazz", "hip-hop", "rap", "r-b", "rnb", "soul", "blues",
  "country", "folk", "americana", "bluegrass", "latin", "reggae", "punk", "metal", "electronic", "edm",
  "classical", "singer-songwriter", "k-pop", "world", "gospel",
]);

// Coarse category for the per-post cap, so genre splits (rock, pop, latin…)
// all count as one "music" bucket.
function family(r) {
  const sub = (r.subcategory || "").toLowerCase(), ss = (r.sub_subcategory || "").toLowerCase();
  const tags = Array.isArray(r.tags) ? r.tags.map((t) => String(t).toLowerCase()) : [];
  if (["theatre", "theater", "musical", "opera"].includes(sub) || ss === "musical" || tags.includes("musical-theater")) return "theatre";
  if (MUSIC_SUBS.has(sub) || tags.includes("concert")) return "music";
  if (ss === "film" || tags.includes("screening")) return "film";
  return sub || (r.category || "other").toLowerCase();
}

const CATEGORY_CAP = 3; // hard limit per category in one post

function pickDiverse(sorted, limit) {
  const perCat = {}, picked = [];
  for (const row of sorted) {
    if (picked.length >= limit) break;
    const c = family(row);
    if ((perCat[c] || 0) >= CATEGORY_CAP) continue;
    picked.push(row); perCat[c] = (perCat[c] || 0) + 1;
  }
  return picked;
}

function tsOf(iso) { return iso ? new Date(iso).getTime() : Infinity; }

// Quality gate for a highlights post, using the classifier's own fields —
// venue rank is unusable here (public_interest_rank is null for nearly every
// event venue). Drops:
//  - "no-public-interest" (school sports, junk listings) — the app's own floor
//  - rows with no real classification (unclassified restaurant "specials", etc.)
//  - virtual / online events — this is a "go out in Boston" feed
function isQuality(r) {
  const sub = (r.subcategory || "").toLowerCase();
  if (sub === "no-public-interest") return false;
  if (sub === "" && !(r.sub_subcategory || "")) return false;
  const tags = Array.isArray(r.tags) ? r.tags.map((t) => String(t).toLowerCase()) : [];
  if (tags.includes("virtual") || tags.includes("online")) return false;
  return true;
}

// Strip a trailing date/time that some crawlers append to titles, e.g.
// "Laura - 10/27/26 @ 8:45 pm" or "Show @ 8pm". Never returns empty.
function cleanTitle(s) {
  const cleaned = String(s || "")
    .replace(/\s*[-–—]\s*\d{1,2}\/\d{1,2}\/\d{2,4}\b.*$/i, "")
    .replace(/\s*[-–—@]\s*\d{1,2}(:\d{2})?\s*(a\.?m\.?|p\.?m\.?)\b.*$/i, "")
    .replace(/\s*[-–—]\s*available\s+\d.*$/i, "") // "… - Available 6:00 PM or 7:30 PM"
    .trim();
  return cleaned || String(s || "").trim();
}

// Collapse same-day rows where one title is a prefix of the other, at any venue
// (e.g. "1972" vs "1972: A Rock Opera", "Yebba" vs "Yebba Jean Tour" listed
// under two venue spellings). Keeps the richer row — one with a description,
// else the longer (more descriptive) title.
function etDay(iso) { return iso ? fmt(iso, { year: "numeric", month: "2-digit", day: "2-digit" }) : ""; }
function dedupeByPrefix(rows) {
  const out = [];
  for (const r of rows) {
    const rt = normTitle(cleanTitle(r.title)), rd = etDay(r.start_at);
    const i = out.findIndex((k) => {
      if (etDay(k.start_at) !== rd) return false;
      const kt = normTitle(cleanTitle(k.title));
      return kt === rt || kt.startsWith(rt + " ") || rt.startsWith(kt + " ");
    });
    if (i === -1) { out.push(r); continue; }
    const score = (x) => (x.description ? 1000 : 0) + String(x.title || "").length;
    if (score(r) > score(out[i])) out[i] = r;
  }
  return out;
}

// How post-worthy an event is, from the classifier's tags: shows, concerts and
// one-off nights up; classes, campus talks, meetups and weekly repeats down.
// Anything under 0 is left out of the post entirely.
const TAG_APPEAL = {
  marquee: 6, "touring-act": 4, festival: 3,
  "live-performance": 3, concert: 3, "musical-theater": 3, comedy: 2,
  "theater-venue": 2, screening: 2, immersive: 2, spooky: 2, halloween: 2, "date-night": 2,
  film: 1, upscale: 1, splurge: 1, "local-artist": 1, ticketed: 1, exhibit: 1, gallery: 1, "one-time": 1, rooftop: 1,
  "class-workshop": -5, networking: -4, professional: -4, "business-focused": -3, campus: -3,
  meetup: -3, recurring: -3, fitness: -3, yoga: -3, wellness: -2, seniors: -2, kids: -2,
  educational: -2, trivia: -2, game: -2, "college-sports": -2, "farmers-market": -2,
  "drop-in": -1, "open-mic": -1, "beginner-friendly": -1, studio: -1,
};
const SUB_APPEAL = { comedy: 3, theatre: 2, music: 1, rock: 1, "for-kids": -2, libraries: -1, civic: -1, sports: -1 };

function appeal(r) {
  const tags = new Set(Array.isArray(r.tags) ? r.tags.map((t) => String(t).toLowerCase()) : []);
  let s = SUB_APPEAL[(r.subcategory || "").toLowerCase()] || 0;
  for (const t of tags) s += TAG_APPEAL[t] || 0;
  if (r.description) s += 1; // cards read better with a blurb
  if (r.time_known !== false && r.start_at) {
    const h = etHour(r.start_at);
    if (h >= 17) s += 2; else if (h < 12) s -= 1;
  }
  return s;
}

// One card per show: collapse same-title rows across venues and dates (venue
// aliases like "The Middle East" / "Middle East Restaurant and Club", repeat
// screenings, nightly runs). Keeps the more appealing row, then the earlier one.
function dedupeByTitle(rows) {
  const best = new Map();
  for (const r of rows) {
    const k = normTitle(cleanTitle(r.title));
    const cur = best.get(k);
    if (!cur) { best.set(k, r); continue; }
    const d = appeal(r) - appeal(cur);
    if (d > 0 || (d === 0 && tsOf(r.start_at) < tsOf(cur.start_at))) best.set(k, r);
  }
  return [...best.values()];
}

export async function getPost(postType) {
  const { from, to } = windowFor(postType);
  const lat = BOSTON.lat, lng = BOSTON.lng;
  const latD = RADIUS_MILES / 69.0, lngD = RADIUS_MILES / 50.0;

  // Fetch the whole window (paged) — a single capped page ordered by start time
  // dropped every evening event. Only the columns the post uses, to keep egress low.
  const filters = [
    ["select", "id,title,description,category,subcategory,sub_subcategory,tags,start_at,time_known,is_free,price_min,image_url,venue_name,venue_city,city,source"],
    ["is_active", "eq.true"],
    ["image_url", "not.is.null"],
    ["start_at", `gte.${from.toISOString()}`],
    ["start_at", `lte.${to.toISOString()}`],
    ["venue_lat", `gte.${lat - latD}`],
    ["venue_lat", `lte.${lat + latD}`],
    ["venue_lng", `gte.${lng - lngD}`],
    ["venue_lng", `lte.${lng + lngD}`],
    ["order", "start_at.asc,id.asc"],
  ];
  const PAGE = 1000, MAX_PAGES = 5;
  const rows = [];
  for (let page = 0; page < MAX_PAGES; page++) {
    const batch = await selectEvents([...filters, ["limit", String(PAGE)], ["offset", String(page * PAGE)]]);
    rows.push(...batch);
    if (batch.length < PAGE) break;
  }

  const deduped = dedupeByPrefix(dedupeByTitle(rows.filter((r) => r.title && r.image_url)));

  // Quality gate, drop the unappealing, then lead with the most attractive.
  const qualityRows = deduped
    .filter((r) => isQuality(r) && appeal(r) >= 0)
    .sort((a, b) => (appeal(b) - appeal(a)) || (tsOf(a.start_at) - tsOf(b.start_at)));

  const picked = pickDiverse(qualityRows, HEADLINE_CAP[postType]);
  console.log(`[social] ${rows.length} rows → ${qualityRows.length} candidates. top: ${qualityRows.slice(0, 15).map((r) => `${appeal(r)} ${cleanTitle(r.title)}`).join(" | ")}`);

  const events = picked.map((r) => ({
    title: cleanTitle(r.title),
    venueName: (r.venue_name || "Boston").trim(),
    hood: (r.neighborhood || r.venue_neighborhood || r.venue_city || r.city || "Boston").trim(),
    when: whenLabel(postType, r.start_at, r.time_known !== false),
    category: categoryLabel(r),
    accent: accentKey(r),
    free: isFree(r),
    photo: thumbUrl(r.image_url, 1080, 80),
    desc: cleanDesc(r.description || r.summary || r.short_description || r.excerpt),
  }));

  return { postType, window: { from: from.toISOString(), to: to.toISOString() }, events, total: qualityRows.length };
}
