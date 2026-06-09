/**
 * sync_prod_to_dev.js — Copy active/future data from the prod DB to the dev DB.
 *
 * Why this exists: the ingest pipeline (crawl, classify, rank, photo/geo
 * backfills) is dominated by PAID external APIs (Claude, Google Places,
 * Scrapfly, Ticketmaster, Eventbrite). Running it twice — once per DB — would
 * double those bills. Instead the pipeline runs ONCE against prod and this
 * script copies the resulting rows into dev. Dev doesn't care that the data was
 * computed against prod; it just needs the data to test 1.x.0 builds against.
 *
 * Scope: active + future only (is_active=true, events also start_at>=now) plus
 * the small derived/reference tables. Keeps prod READ load bounded — important
 * given the prod Nano's tight IO budget. Dev intentionally does NOT get the full
 * historical/inactive corpus.
 *
 * Schema note: dev's schema may LEAD prod during a dev cycle (you're testing new
 * migrations there). This copy is additive (upsert by PK), so extra dev-only
 * columns are simply left at their defaults — it never drops or alters anything.
 *
 * Direction is one-way (prod -> dev) and guarded: it refuses to run unless the
 * dev URL is set, distinct from prod, and a dev service key is present.
 *
 * Usage (locally):
 *   SUPABASE_SERVICE_KEY=<prod_key> \
 *   DEV_SUPABASE_URL=https://<dev-ref>.supabase.co \
 *   DEV_SUPABASE_SERVICE_KEY=<dev_key> \
 *   node scripts/sync_prod_to_dev.js
 */

const { SUPABASE_URL } = require("./lib/secrets");

const PROD_URL = SUPABASE_URL;
const PROD_KEY = (process.env.SUPABASE_SERVICE_KEY || "").replace(/\s/g, "");
const DEV_URL = (process.env.DEV_SUPABASE_URL || "").replace(/\s/g, "");
const DEV_KEY = (process.env.DEV_SUPABASE_SERVICE_KEY || "").replace(/\s/g, "");

const READ_PAGE = parseInt(process.env.READ_PAGE || "1000", 10);
const WRITE_BATCH = parseInt(process.env.WRITE_BATCH || "500", 10);

// Tables to mirror, in FK-safe order (parents before children).
// `filter` is a PostgREST query string appended to the read; "" copies all rows.
// `{now}` is replaced with the current ISO timestamp at runtime.
// `transform` (optional) rewrites each row before upsert — used to strip
// self-referential FKs that would dangle under a scoped (active/future) copy.
// `orderBy` is the PostgREST `order` clause used for stable pagination. Most
// tables have an `id` PK, but some don't (states keys on `code`, carousel_cache
// has a composite (lat_bucket,lng_bucket,category) key) — paginating those by a
// nonexistent `id` column 400s, so each table declares its own. Defaults to id.
const TABLES = [
  { name: "venues",         filter: "is_active=eq.true" },
  // events.duplicate_of -> events(id) is a self-FK; the target row is often an
  // older/inactive canonical that's outside this scoped copy, so null it.
  { name: "events",         filter: "is_active=eq.true&start_at=gte.{now}", transform: (r) => ({ ...r, duplicate_of: null }) },
  // states must precede metros (metros.state_code -> states.code FK).
  { name: "states",         filter: "", orderBy: "code.asc" },
  { name: "metros",         filter: "" },
  { name: "curated_events", filter: "" },
  { name: "carousel_cache", filter: "", orderBy: "lat_bucket.asc,lng_bucket.asc,category.asc" },
];

function assertSafeTarget() {
  if (!PROD_KEY) {
    console.error("Missing SUPABASE_SERVICE_KEY (prod source key).");
    process.exit(1);
  }
  if (!DEV_URL || !DEV_KEY) {
    console.error("Missing DEV_SUPABASE_URL / DEV_SUPABASE_SERVICE_KEY — nothing to sync into.");
    process.exit(1);
  }
  if (DEV_URL === PROD_URL) {
    console.error(`Refusing to run: DEV_SUPABASE_URL equals prod (${PROD_URL}). This would overwrite prod with itself.`);
    process.exit(1);
  }
}

function headers(key, extra = {}) {
  return { apikey: key, Authorization: `Bearer ${key}`, ...extra };
}

async function readPage(table, filter, offset) {
  const sep = filter ? "&" : "";
  const url = `${PROD_URL}/rest/v1/${table}?select=*${sep}${filter}&order=id.asc&limit=${READ_PAGE}&offset=${offset}`;
  const res = await fetch(url, { headers: headers(PROD_KEY) });
  if (!res.ok) {
    throw new Error(`read ${table} @${offset} -> ${res.status} ${(await res.text()).slice(0, 200)}`);
  }
  return res.json();
}

async function upsertBatch(table, rows) {
  const url = `${DEV_URL}/rest/v1/${table}`;
  const res = await fetch(url, {
    method: "POST",
    headers: headers(DEV_KEY, {
      "Content-Type": "application/json",
      Prefer: "resolution=merge-duplicates,return=minimal",
    }),
    body: JSON.stringify(rows),
  });
  if (!res.ok) {
    throw new Error(`upsert ${table} (${rows.length}) -> ${res.status} ${(await res.text()).slice(0, 300)}`);
  }
}

async function copyTable({ name, filter, transform }, now) {
  const resolvedFilter = filter.replace("{now}", encodeURIComponent(now));
  let offset = 0;
  let copied = 0;

  for (;;) {
    let page;
    try {
      page = await readPage(name, resolvedFilter, offset);
    } catch (err) {
      console.warn(`  [${name}] read failed, skipping table: ${err.message}`);
      return;
    }
    if (!page.length) break;
    if (transform) page = page.map(transform);

    for (let i = 0; i < page.length; i += WRITE_BATCH) {
      const batch = page.slice(i, i + WRITE_BATCH);
      try {
        await upsertBatch(name, batch);
        copied += batch.length;
      } catch (err) {
        // Best-effort: a single bad batch (e.g. a dev column not yet migrated)
        // shouldn't abort the whole sync.
        console.warn(`  [${name}] write batch failed: ${err.message}`);
      }
    }

    offset += page.length;
    if (page.length < READ_PAGE) break;
  }

  console.log(`  [${name}] synced ${copied} rows`);
}

async function main() {
  assertSafeTarget();
  const now = new Date().toISOString();
  console.log(`Sync prod -> dev`);
  console.log(`  source: ${PROD_URL}`);
  console.log(`  target: ${DEV_URL}`);
  console.log(`  cutoff (events start_at >=): ${now}\n`);

  for (const table of TABLES) {
    await copyTable(table, now);
  }

  console.log("\nDone.");
}

main().catch((err) => {
  console.error(`Fatal: ${err.message}`);
  process.exit(1);
});
