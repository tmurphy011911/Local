/**
 * sync_restaurant_weeks.js — Ingest restaurant weeks from findrestaurantweeks.com
 *
 * Replaces the old refresh_restaurant_weeks.js (Claude web_search guesswork)
 * with a deterministic scrape of findrestaurantweeks.com's per-state pages,
 * which list each promotion as `<a href="OFFICIAL_URL">Name</a> – <date range>`
 * with real, editorially-maintained dates and an official organizer link.
 *
 * Restaurant weeks are DATED events (a Feb 22–Mar 7 window), not recurring, so
 * unlike farmers markets they land in the main `events` table as normal
 * future-dated events with subcategory="restaurant-week". They flow through the
 * MV/geo pool like any event; the carousels edge function strips them from the
 * generic topical rails (isGeneralFeedEvent) and surfaces them in a dedicated
 * "Restaurant Weeks Near You" rail instead.
 *
 * One row per promotion, date-free dedupe_hash (`frw:<state>:<slug>`), so each
 * (monthly) re-run OVERWRITES each promo's row with the latest dates — when the
 * site flips Boston from its spring to its August edition, the same row updates.
 * Runs monthly (.github/workflows/monthly.yml): restaurant weeks are announced
 * months out and shift slowly. The daily deactivate-past-events job retires a
 * promo as soon as its window ends, between monthly scrapes.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/sync_restaurant_weeks.js
 *   DRY_RUN=1 ...            node scripts/sync_restaurant_weeks.js
 *   STATES=MA,RI ...         node scripts/sync_restaurant_weeks.js   # subset
 *   SUPABASE_URL=https://<dev-ref>.supabase.co ...                   # target dev
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

const DRY_RUN = process.env.DRY_RUN === "1";
const STATE_FILTER = (process.env.STATES || "").trim().toUpperCase();

// Live states only (states.is_live=true as of writing). Restaurant-week pages
// exist for all 50 states, but we only ingest where the app actually serves
// users. Full name is what we hand the geocoder.
const STATES = {
  MA: "Massachusetts",
  RI: "Rhode Island",
  PA: "Pennsylvania",
  NY: "New York",
  NJ: "New Jersey",
  CA: "California",
};

const BROWSER_UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
  "(KHTML, like Gecko) LocalApp/1.0 (+https://localapp.live; restaurant weeks)";

let SUPABASE_SERVICE_KEY, GOOGLE_PLACES_API_KEY;

// ── HTML / entity helpers ───────────────────────────────────────────

function decodeEntities(s) {
  if (!s) return "";
  return s
    .replace(/&#8211;|&ndash;/gi, "–")
    .replace(/&#8212;|&mdash;/gi, "—")
    .replace(/&#8217;|&rsquo;/gi, "’")
    .replace(/&#8216;|&lsquo;/gi, "‘")
    .replace(/&amp;/gi, "&")
    .replace(/&nbsp;/gi, " ")
    .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(parseInt(n, 10)))
    .replace(/&[a-z]+;/gi, " ");
}

const stripTags = (s) => decodeEntities((s || "").replace(/<[^>]+>/g, "")).replace(/\s+/g, " ").trim();

// ── Date parsing ────────────────────────────────────────────────────

const MONTHS = {
  january: 1, jan: 1, february: 2, feb: 2, march: 3, mar: 3, april: 4, apr: 4,
  may: 5, june: 6, jun: 6, july: 7, jul: 7, august: 8, aug: 8,
  september: 9, sept: 9, sep: 9, october: 10, oct: 10,
  november: 11, nov: 11, december: 12, dec: 12,
};

function parseMonthDay(s) {
  const m = String(s).trim().match(/([A-Za-z]+)\.?\s+(\d{1,2})/);
  if (!m) return null;
  const month = MONTHS[m[1].toLowerCase()];
  const day = parseInt(m[2], 10);
  if (!month || !day || day > 31) return null;
  return { month, day };
}

const iso = (y, m, d) =>
  `${y}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;

/**
 * Parse ONE date range into {start, end} YYYY-MM-DD (date-only). Handles
 * "February 20 – March 1, 2026", "August 21 – 30, 2026" (day-only end),
 * "February 1 – 28, 2026", single dates, and December→January wraps. Returns
 * null for placeholders ("Check back for 2026 dates", TBD, etc.) or when no
 * year is present (and no fallbackYear is supplied).
 */
function parseDateRange(raw, fallbackYear) {
  const t = decodeEntities(raw)
    .replace(/[–—]/g, "-")
    .replace(/\s+/g, " ")
    .trim();
  if (!t || /check back|tbd|coming soon|to be announced|unavailable|dates\?/i.test(t)) {
    return null;
  }
  const yearMatch = t.match(/\b(20\d{2})\b/);
  const year = yearMatch ? parseInt(yearMatch[1], 10) : (fallbackYear ? parseInt(fallbackYear, 10) : null);
  if (!year) return null;

  const parts = t.split(/\s*-\s*/);
  const startMD = parseMonthDay(parts[0]);
  if (!startMD) return null;

  if (parts.length < 2) {
    return { start: iso(year, startMD.month, startMD.day), end: iso(year, startMD.month, startMD.day) };
  }

  const rightNoYear = parts[1].replace(/,?\s*20\d{2}/, "").trim();
  let endMonth, endDay;
  const rightMD = parseMonthDay(rightNoYear);
  if (rightMD) {
    endMonth = rightMD.month;
    endDay = rightMD.day;
  } else {
    const dnum = parseInt(rightNoYear, 10);
    if (!dnum || dnum > 31) return null;
    endMonth = startMD.month;
    endDay = dnum;
  }

  // Year-crossing window (e.g. "December 28 – January 3, 2026"): the listed
  // year belongs to the END; back the start up a year.
  const startYear = startMD.month > endMonth ? year - 1 : year;
  return { start: iso(startYear, startMD.month, startMD.day), end: iso(year, endMonth, endDay) };
}

/**
 * A single listing can carry MULTIPLE editions in one line, e.g.
 * "March 2 – 8, 2026 & September 14 – 20, 2026" (Manayunk runs twice). Split on
 * &/and/; and parse each segment independently — each becomes its own event.
 * Segments missing a year inherit the line's year (often only the last edition
 * spells it out). Returns [] for placeholders / unparseable text.
 */
function parseDateRanges(raw) {
  const decoded = decodeEntities(raw).replace(/[–—]/g, "-").replace(/\s+/g, " ").trim();
  if (!decoded || /check back|tbd|coming soon|to be announced|unavailable/i.test(decoded)) return [];
  const fallbackYear = (decoded.match(/\b(20\d{2})\b/) || [])[1] || null;
  const segments = decoded.split(/\s*(?:&|;|\band\b)\s*/i).map(s => s.trim()).filter(Boolean);
  const out = [];
  for (const seg of segments) {
    const r = parseDateRange(seg, fallbackYear);
    if (r) out.push(r);
  }
  return out;
}

// ── City derivation (the site lists only a promo name, not a city) ──

function deriveCity(name) {
  let c = name
    .replace(/['’]s\b/g, "")
    .replace(/\bDine\s*Out\b/gi, "")
    .replace(/\bSavor\b/gi, "")
    .replace(/\b(Restaurants?|Dining)\s+(Weeks?|Months?|Days?)\b/gi, "")
    .replace(/\bRestaurant\s*Weeks?\b/gi, "")
    .replace(/\b(Downtown|Greater|City of)\b/gi, "")
    .replace(/\s+/g, " ")
    .trim();
  return c;
}

// Names that don't resolve to a mappable place — drop rather than drop a pin
// in the geographic middle of the state.
const VAGUE_CITY = /^(regional|statewide|various|multiple|county|valley|area|the)?$/i;

// ── Geocode (Google, same pattern as sync_porchfests.js) ────────────

async function geocode(city, stateFull) {
  if (!GOOGLE_PLACES_API_KEY) return null;
  const q = `${city}, ${stateFull}`;
  try {
    const r = await fetch(
      `https://maps.googleapis.com/maps/api/geocode/json?address=${encodeURIComponent(q)}&key=${GOOGLE_PLACES_API_KEY}`,
    );
    const d = await r.json();
    if (d.status !== "OK" || !d.results?.[0]) return null;
    const top = d.results[0];
    // Reject whole-state / country hits — we want a city-level centroid, not
    // a pin in the middle of Massachusetts.
    const t = top.types || [];
    if (t.includes("administrative_area_level_1") || t.includes("country")) return null;
    return top.geometry.location; // { lat, lng }
  } catch (_) {
    return null;
  }
}

// ── og:image scrape from the official organizer page ────────────────

async function fetchOgImage(pageUrl) {
  if (!pageUrl || !/^https?:\/\//.test(pageUrl)) return null;
  try {
    const res = await fetch(pageUrl, {
      redirect: "follow",
      signal: AbortSignal.timeout(10000),
      headers: { "User-Agent": BROWSER_UA, Accept: "text/html,*/*;q=0.8" },
    });
    if (!res.ok) return null;
    const html = (await res.text()).slice(0, 200_000);
    const patterns = [
      /<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']/i,
      /<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']/i,
      /<meta[^>]+name=["']twitter:image["'][^>]+content=["']([^"']+)["']/i,
    ];
    for (const re of patterns) {
      const m = html.match(re);
      if (m && m[1]) {
        try { return new URL(m[1], pageUrl).toString(); } catch { return null; }
      }
    }
  } catch (_) { /* ignore */ }
  return null;
}

// ── Scrape one state page ───────────────────────────────────────────

async function scrapeState(code) {
  const url = `https://www.findrestaurantweeks.com/${code.toLowerCase()}/`;
  const res = await fetch(url, {
    redirect: "follow",
    signal: AbortSignal.timeout(20000),
    headers: { "User-Agent": BROWSER_UA, Accept: "text/html,*/*;q=0.8" },
  });
  if (!res.ok) throw new Error(`${url} → HTTP ${res.status}`);
  let html = await res.text();

  // Narrow to the event list region when the marker is present (keeps stray
  // <p><a> links in the header/footer out), else fall back to the whole doc.
  const marker = html.search(/Restaurant Week events happening in/i);
  if (marker >= 0) html = html.slice(marker);

  // Each promo is a `<p>` holding `<a href=URL>Name</a> – <date text>`. Parse it
  // in two steps rather than one rigid regex, so markup variants don't silently
  // drop rows: some links carry a `title=` attr BEFORE href (Pittsburgh) and
  // some wrap the link/dash in <span>/<strong> (Erie). So: grab each <p> block,
  // pull the FIRST <a href> regardless of attribute order, then take whatever
  // text follows the </a> as the date string (tags stripped, leading dash
  // removed) and let parseDateRanges handle one-or-more editions.
  const out = [];
  const todayYmd = new Date().toISOString().slice(0, 10);
  const blocks = html.match(/<p[^>]*>[\s\S]*?<\/p>/gi) || [];
  for (const block of blocks) {
    const a = block.match(/<a\s+[^>]*?href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/i);
    if (!a) continue;
    const sourceUrl = decodeEntities(a[1]).trim();
    const name = stripTags(a[2]);
    if (!name || !/^https?:\/\//.test(sourceUrl)) continue;
    // Everything after the link is the date text; drop the leading separator.
    const after = block.slice(block.indexOf(a[0]) + a[0].length);
    const dateText = stripTags(after.replace(/<\/p>\s*$/i, ""))
      .replace(/^\s*(?:&#8211;|&ndash;|[–—-])\s*/, "")
      .trim();
    const ranges = parseDateRanges(dateText); // 0+ editions per listing
    const upcoming = ranges.filter((r) => r.end >= todayYmd);
    if (upcoming.length > 0) {
      // Confirmed upcoming edition(s) → dated rows (multi-edition promos keep one
      // per upcoming edition). Past editions of the same promo are ignored.
      for (const dates of upcoming) out.push({ name, sourceUrl, dateText, ...dates });
    } else {
      // No upcoming edition (all past) OR undated "Check back" → one TBD card
      // with null dates. Null start survives the deactivate-past-events sweep,
      // so the week stays visible (labelled "Dates TBD") until a date is posted.
      out.push({ name, sourceUrl, dateText, start: null, end: null });
    }
  }
  return out;
}

// ── Event row builder (mirrors sync_farmers_markets.js shape) ───────

function buildEvent(promo, code, coords, imageUrl) {
  const stateFull = STATES[code];
  // Undated ("Check back") promos carry null dates → a TBD card.
  const startAt = promo.start ? `${promo.start}T12:00:00.000Z` : null;
  const endAt = promo.end ? `${promo.end}T23:59:59.000Z` : null;
  const slug = deriveCity(promo.name).toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "rw";
  // Edition discriminator (YYYY-MM of start) so a promo that runs multiple
  // times a year — Wildwoods (Jun/Aug/Nov), Manayunk (Mar/Sep), Dine Out Boston
  // (Mar/Aug) — keeps a distinct row per edition instead of collapsing to one
  // on the date-free upsert. A small date shift within the same month still
  // hits the same hash, so re-runs refresh dates as intended.
  const edition = promo.start ? promo.start.slice(0, 7) : "tbd";
  const city = deriveCity(promo.name);
  const runLine = promo.start ? ` Running ${promo.dateText}.` : " Dates to be announced.";
  const desc =
    `${promo.name}: a multi-day dining celebration where participating ${city || stateFull} ` +
    `restaurants serve special prix-fixe and discounted menus.${runLine}`;

  return {
    title: promo.name,
    description: desc,
    category: "food",
    subcategory: "restaurant-week",
    tags: ["restaurant-week", "food", "dining", "prix-fixe"],
    start_at: startAt,
    end_at: endAt,
    time_known: false,
    is_free: false,
    price_min: null,
    price_max: null,
    ticket_url: promo.sourceUrl,
    image_url: imageUrl || null,
    status: "active",
    is_active: true,
    venue_name: city ? `${city} Restaurants` : promo.name,
    venue_address: null,
    venue_city: city,
    venue_state: code,
    venue_lat: coords.lat,
    venue_lng: coords.lng,
    source: "findrestaurantweeks",
    source_id: `${code}:${slug}:${edition}`,
    source_url: promo.sourceUrl,
    confidence_score: 0.9,
    needs_review: false,
    dedupe_hash: `frw:${code}:${slug}:${edition}`,
    city,
    last_synced_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  };
}

// ── Upsert (mirrors sync_farmers_markets.js) ────────────────────────

async function upsert(rows) {
  const url = `${SUPABASE_URL}/rest/v1/events?on_conflict=dedupe_hash`;
  const batchSize = 50;
  let ok = 0, errors = 0;
  for (let i = 0; i < rows.length; i += batchSize) {
    const batch = rows.slice(i, i + batchSize);
    const res = await fetch(url, {
      method: "POST",
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
        "Content-Type": "application/json",
        Prefer: "resolution=merge-duplicates",
      },
      body: JSON.stringify(batch),
    });
    if (!res.ok) {
      const body = await res.text();
      console.error(`  Upsert batch ${Math.floor(i / batchSize)}: ${res.status} ${body.slice(0, 240)}`);
      errors += batch.length;
    } else ok += batch.length;
  }
  return { ok, errors };
}

// Deactivate prior findrestaurantweeks rows whose window has ended, so a promo
// the site dropped (or that simply passed) doesn't linger in the rail. The
// daily deactivate-past-events job also covers this via end_at, but doing it
// here keeps a single source-scoped sweep co-located with the ingest.
async function deactivateExpired() {
  const nowIso = new Date().toISOString();
  const url =
    `${SUPABASE_URL}/rest/v1/events?source=eq.findrestaurantweeks&is_active=eq.true&end_at=lt.${nowIso}`;
  const res = await fetch(url, {
    method: "PATCH",
    headers: {
      apikey: SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      "Content-Type": "application/json",
      Prefer: "return=representation",
    },
    body: JSON.stringify({ is_active: false, status: "inactive", updated_at: nowIso }),
  });
  if (!res.ok) return 0;
  return (await res.json()).length;
}

// ── concurrency pool (from sync_porchfests.js) ──────────────────────
async function pool(items, n, fn) {
  const out = []; let i = 0;
  await Promise.all(Array.from({ length: n }, async () => {
    while (i < items.length) { const idx = i++; out[idx] = await fn(items[idx], idx); }
  }));
  return out;
}

// ── Main ────────────────────────────────────────────────────────────

async function main() {
  const secrets = await getSecrets(["GOOGLE_PLACES_API_KEY"]);
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;
  GOOGLE_PLACES_API_KEY = secrets.GOOGLE_PLACES_API_KEY;

  const codes = Object.keys(STATES).filter(
    (c) => !STATE_FILTER || STATE_FILTER.split(",").map((s) => s.trim()).includes(c),
  );
  console.log(`Restaurant-week sync → ${SUPABASE_URL}${DRY_RUN ? "  [DRY RUN]" : ""}  states: ${codes.join(", ")}`);

  const todayIso = new Date().toISOString().slice(0, 10);
  const rows = [];
  let scanned = 0, droppedDate = 0, droppedCity = 0, droppedGeo = 0, droppedPast = 0;

  for (const code of codes) {
    let promos;
    try {
      promos = await scrapeState(code);
    } catch (e) {
      console.error(`  [${code}] SCRAPE FAILED — ${e.message}`);
      continue;
    }
    scanned += promos.length;

    // Resolve geo + og:image concurrently (small pool — be polite).
    // NOTE: past + undated promos are KEPT (shown as "Dates TBD" by the rail),
    // so there's no past-date drop here anymore.
    const built = await pool(promos, 5, async (p) => {
      const city = deriveCity(p.name);
      if (!city || VAGUE_CITY.test(city)) { droppedCity++; return null; }
      const coords = await geocode(city, STATES[code]);
      if (!coords) { droppedGeo++; return null; }
      const img = await fetchOgImage(p.sourceUrl);
      return buildEvent(p, code, coords, img);
    });

    let kept = 0;
    for (const ev of built) {
      if (ev) { rows.push(ev); kept++; }
    }
    console.log(`  [${code}] ${promos.length} listed → ${kept} events`);
  }

  const tbdCount = rows.filter((r) => !r.start_at).length;
  console.log(
    `Total: ${rows.length} events from ${scanned} listings ` +
    `(${tbdCount} TBD/undated, ${droppedCity} no-city, ${droppedGeo} no-geo).`,
  );

  if (DRY_RUN) {
    console.log("\nSample:");
    for (const r of rows.slice(0, 40)) {
      const when = r.start_at ? `${r.start_at.slice(0, 10)}→${r.end_at.slice(0, 10)}` : "Dates TBD";
      console.log(
        `  • ${r.title} — ${when} — ` +
        `${r.venue_city},${r.venue_state} (${r.venue_lat?.toFixed(3)},${r.venue_lng?.toFixed(3)}) ` +
        `${r.image_url ? "[img]" : "[no-img]"}`,
      );
    }
    return;
  }

  // No deactivate-expired pass here: a promo with no upcoming edition is ingested
  // as a null-date TBD row (which the global deactivate-past-events sweep leaves
  // alone, since it only touches rows with a non-null past start_at).
  const { ok, errors } = await upsert(rows);
  console.log(`Upserted ${ok} rows (${errors} errors).`);
}

if (require.main === module) {
  main().catch((err) => {
    console.error(err);
    process.exit(1);
  });
}

// Exported for offline unit tests (test_restaurant_weeks_parse.js).
module.exports = { parseDateRange, parseDateRanges, parseMonthDay, deriveCity, decodeEntities, stripTags };
