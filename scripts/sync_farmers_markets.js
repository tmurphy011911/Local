/**
 * sync_farmers_markets.js — Ingest farmers markets as recurring (always_on)
 * events from multiple structured sources.
 *
 * Each source module (scripts/lib/farmers_markets/*.js) knows how to fetch one
 * directory and emit NORMALIZED market records; this orchestrator turns those
 * into events and upserts them. One always_on event per market, no `venues`
 * row (the market lives on the event with its real coords). Weekly re-runs
 * OVERWRITE each market's single row (date-free dedupe_hash), so schedules +
 * next-occurrence stay fresh and seasonal markets self-hide off-season.
 *
 * Normalized market record (returned by a source's fetchMarkets()):
 *   { dedupePrefix, source, sourceId, name, lat, lng, address, city, state,
 *     zip, website, bio, imageUrl, programTags:[...],
 *     occurrences:[{ weekday (Mon=0..Sun=6), startLocal:"HH:MM", endLocal:"HH:MM",
 *                    seasonStart:"YYYY-MM-DD"|null, seasonEnd:"YYYY-MM-DD"|null }] }
 * All times are Eastern-LOCAL wall clock; this builder stamps the correct ET
 * offset when writing the timestamptz columns.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/sync_farmers_markets.js
 *   DRY_RUN=1 ... node scripts/sync_farmers_markets.js
 *   SOURCE=phl_arcgis ... node scripts/sync_farmers_markets.js   # one source only
 *   SUPABASE_URL=https://<dev-ref>.supabase.co ...               # target dev
 */

const { SUPABASE_URL } = require("./lib/secrets");

const SOURCES = [
  require("./lib/farmers_markets/marketspread"),
  require("./lib/farmers_markets/philly_arcgis"),
];

const SUPABASE_SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").trim();
if (!SUPABASE_SERVICE_KEY) {
  console.error("Missing SUPABASE_SERVICE_KEY env var");
  process.exit(1);
}
const DRY_RUN = process.env.DRY_RUN === "1";
const SOURCE_FILTER = (process.env.SOURCE || "").trim().toLowerCase();

const DAY_NAMES = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

// ── Date / time helpers ──

function parseYMD(s) {
  if (!s) return null;
  const [y, m, d] = String(s).slice(0, 10).split("-").map(Number);
  if (!y || !m || !d) return null;
  return new Date(Date.UTC(y, m - 1, d, 12, 0, 0));
}
const ymd = (d) => d.toISOString().slice(0, 10);

// ET offset ("-04:00"/"-05:00") for a given calendar date — so an ET-local
// wall-clock time becomes the right absolute instant in the timestamptz column.
function etOffset(dateStr) {
  const d = new Date(`${dateStr}T12:00:00Z`);
  const s = d.toLocaleString("en-US", { timeZone: "America/New_York", timeZoneName: "longOffset" });
  const m = s.match(/GMT([+-]\d{2}):?(\d{2})/);
  return m ? `${m[1]}:${m[2]}` : "-05:00";
}
const etLocalToIso = (dateStr, hhmm) => `${dateStr}T${hhmm}:00${etOffset(dateStr)}`;

function fmtTimeLabel(hhmm) {
  const [h, m] = hhmm.split(":").map(Number);
  const ampm = h >= 12 ? "PM" : "AM";
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return m ? `${h12}:${String(m).padStart(2, "0")} ${ampm}` : `${h12} ${ampm}`;
}

function fmtSeason(occ) {
  if (!occ || !occ.seasonStart || !occ.seasonEnd) return "";
  const ds = parseYMD(occ.seasonStart), de = parseYMD(occ.seasonEnd);
  if (!ds || !de) return "";
  const mon = (d) => d.toLocaleString("en-US", { month: "long", timeZone: "UTC" });
  return ` (${mon(ds)}–${mon(de)})`;
}

const pyToJsDay = (py) => (py + 1) % 7;

// Next calendar date (UTC noon) on/after `now` (or season_start) matching this
// occurrence's weekday, within its season window. Null = dormant.
function nextDateFor(occ, now) {
  const seasonStart = parseYMD(occ.seasonStart);
  const seasonEnd = parseYMD(occ.seasonEnd);
  if (seasonEnd && now > seasonEnd) return null;
  let from = new Date(now);
  if (seasonStart && from < seasonStart) from = new Date(seasonStart);
  const d = new Date(from);
  d.setUTCDate(d.getUTCDate() + ((pyToJsDay(occ.weekday) - d.getUTCDay() + 7) % 7));
  if (seasonEnd && d > seasonEnd) return null;
  return d;
}

// Pick the soonest upcoming occurrence across all of a market's operating days.
function chooseNext(occurrences, now) {
  let best = null;
  for (const occ of occurrences) {
    const date = nextDateFor(occ, now);
    if (date && (!best || date < best.date)) best = { occ, date };
  }
  return best; // null = dormant (all occurrences out of season)
}

// ── Build event row ──

function buildEvent(market, now) {
  if (market.lat == null || market.lng == null) return null;
  const occs = market.occurrences || [];
  const next = chooseNext(occs, now);
  const active = !!next;

  // Human schedule line: list every operating day + time, so a multi-day
  // market reads fully. "Weekly" + "Every <day>" hit the carousel weeklyKeywords.
  const dayLine = occs.length
    ? occs
        .map((o) => `Every ${DAY_NAMES[o.weekday]}, ${fmtTimeLabel(o.startLocal)}–${fmtTimeLabel(o.endLocal)}`)
        .join("; ")
    : "";
  const seasonStr = fmtSeason(next ? next.occ : occs[0]);
  const scheduleLine = dayLine ? `Weekly farmers market. ${dayLine}${seasonStr}.` : "Weekly farmers market.";
  const description = market.bio ? `${market.bio}\n\n${scheduleLine}` : scheduleLine;

  const tags = [...new Set([
    "farmers-market", "recurring", "outdoor", "family-friendly", "free",
    ...(market.programTags || []),
  ])];

  let startAt = null, endAt = null;
  if (active) {
    const day = ymd(next.date);
    startAt = etLocalToIso(day, next.occ.startLocal);
    endAt = etLocalToIso(day, next.occ.endLocal);
  }

  return {
    title: market.name,
    description,
    category: "always_on",
    subcategory: "farmers-market",
    tags,
    start_at: startAt,
    end_at: endAt,
    time_known: true,
    is_free: true,
    price_min: null,
    price_max: null,
    ticket_url: market.website,
    image_url: market.imageUrl || null,
    status: active ? "active" : "inactive",
    is_active: active,
    venue_name: market.name,
    venue_address: market.address,
    venue_city: market.city,
    venue_state: market.state,
    venue_lat: market.lat,
    venue_lng: market.lng,
    source: market.source,
    source_id: market.sourceId,
    source_url: market.website,
    confidence_score: 0.95,
    needs_review: false,
    dedupe_hash: `${market.dedupePrefix}:${market.sourceId}`,
    city: market.city,
    last_synced_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  };
}

// ── Upsert ──

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

// ── Main ──

async function main() {
  console.log(`Farmers-market sync → ${SUPABASE_URL}${DRY_RUN ? "  [DRY RUN]" : ""}`);
  const now = new Date();

  const rows = [];
  let dormant = 0, skipped = 0;

  for (const src of SOURCES) {
    const tag = src.label || "source";
    if (SOURCE_FILTER && !tag.toLowerCase().includes(SOURCE_FILTER)) continue;
    let markets;
    try {
      markets = await src.fetchMarkets();
    } catch (e) {
      console.error(`  ${tag}: FETCH FAILED — ${e.message}`);
      continue;
    }
    let built = 0;
    for (const m of markets) {
      const ev = buildEvent(m, now);
      if (!ev) { skipped++; continue; }
      if (!ev.is_active) dormant++;
      rows.push(ev);
      built++;
    }
    console.log(`  ${tag}: ${markets.length} markets → ${built} events`);
  }

  console.log(`Total: ${rows.length} events (${dormant} dormant/off-season, ${skipped} skipped).`);

  if (DRY_RUN) {
    console.log("\nSample:");
    for (const r of rows.slice(0, 6)) {
      console.log(`  • ${r.title} [${r.source}] — ${r.is_active ? r.start_at : "DORMANT"} — ${r.venue_city},${r.venue_state} (${r.venue_lat?.toFixed(3)},${r.venue_lng?.toFixed(3)})`);
    }
    console.log("\nDRY_RUN: no writes performed.");
    return;
  }

  const { ok, errors } = await upsert(rows);
  console.log(`\nUpserted ${ok} rows, ${errors} errors.`);
  if (errors > 0) process.exitCode = 1;
}

main().catch((e) => { console.error(e); process.exit(1); });
