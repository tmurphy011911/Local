/**
 * backfill_entity_decoding.js — Decode HTML entities in existing event
 * titles and descriptions.
 *
 * Earlier crawls (before run_crawls.js was updated to decode entities in
 * normalizeEvent) persisted things like "George Martin: The Scores &#8211;
 * A Live Immersive Experience". Future crawls are clean; this script cleans
 * the historical rows.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/backfill_entity_decoding.js          # dry
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 node scripts/backfill_entity_decoding.js  # apply
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");
const WRITE = process.env.WRITE === "1";
const TABLES = [
  { table: "events", cols: ["title", "description", "venue_name"] },
  { table: "venues", cols: ["name", "description"] },
];

function decodeHtmlEntities(s) {
  if (!s) return s;
  return String(s)
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&nbsp;/g, " ")
    // Named entities seen in the wild
    .replace(/&mdash;/g, "—")
    .replace(/&ndash;/g, "–")
    .replace(/&hellip;/g, "…")
    .replace(/&lsquo;/g, "\u2018")
    .replace(/&rsquo;/g, "\u2019")
    .replace(/&ldquo;/g, "\u201C")
    .replace(/&rdquo;/g, "\u201D")
    // Decimal numeric: &#8211; etc.
    .replace(/&#(\d+);/g, (_, n) => {
      const code = parseInt(n, 10);
      return Number.isFinite(code) ? String.fromCodePoint(code) : "";
    })
    // Hex numeric: &#x2019; etc.
    .replace(/&#x([0-9a-fA-F]+);/g, (_, n) => {
      const code = parseInt(n, 16);
      return Number.isFinite(code) ? String.fromCodePoint(code) : "";
    });
}

function needsDecoding(s) {
  return typeof s === "string" && /&(amp|lt|gt|quot|apos|nbsp|mdash|ndash|hellip|lsquo|rsquo|ldquo|rdquo|#\d+|#x[0-9a-f]+);/i.test(s);
}

async function fetchAll(serviceKey, table, cols) {
  // Keyset pagination on id + retry on 5xx. The original offset+limit
  // approach hit `canceling statement due to statement timeout` on page 2
  // of events because OFFSET re-scans from row 0 each call AND there's no
  // supporting index for the entity-marker filter. id-based keyset uses
  // the primary key index and survives the load.
  const all = [];
  const page = 1000;
  const selCols = ["id", ...cols].join(",");
  const filter = cols.map((c) => `${c}.ilike.*%26%23*,${c}.ilike.*%26amp%3B*,${c}.ilike.*%26quot%3B*`).join(",");
  let cursor = null;
  while (true) {
    let url = `${SUPABASE_URL}/rest/v1/${table}?select=${selCols}&or=(${filter})&order=id.asc&limit=${page}`;
    if (cursor) url += `&id=gt.${cursor}`;
    // Retry on 5xx — the entity-filter scan can race with autovacuum /
    // other load. Three tries with backoff before giving up on this page.
    let rows = null;
    for (let attempt = 1; attempt <= 3; attempt++) {
      const res = await fetch(url, {
        headers: { apikey: serviceKey, Authorization: `Bearer ${serviceKey}` },
      });
      if (res.ok) { rows = await res.json(); break; }
      const txt = await res.text();
      console.error(`fetch ${table} attempt ${attempt}/3: ${res.status} ${txt.slice(0, 200)}`);
      if (res.status < 500 || attempt === 3) { rows = null; break; }
      await new Promise((r) => setTimeout(r, 1000 * attempt));
    }
    if (rows === null) {
      console.error(`fetch ${table} gave up after 3 attempts — moving on with ${all.length} rows so far`);
      break;
    }
    all.push(...rows);
    if (rows.length < page) break;
    cursor = rows[rows.length - 1].id;
  }
  return all;
}

async function main() {
  const secrets = await getSecrets([]);
  const SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;
  if (!SUPABASE_SERVICE_KEY) {
    console.error("Missing SUPABASE_SERVICE_KEY");
    process.exit(1);
  }
  console.log(`Mode: ${WRITE ? "WRITE" : "DRY RUN"}\n`);

  let totalChanged = 0;

  for (const { table, cols } of TABLES) {
    console.log(`== ${table} ==`);
    const rows = await fetchAll(SUPABASE_SERVICE_KEY, table, cols);
    console.log(`  candidates fetched: ${rows.length}`);

    const updates = [];
    for (const row of rows) {
      const patch = {};
      for (const col of cols) {
        const v = row[col];
        if (needsDecoding(v)) {
          const decoded = decodeHtmlEntities(v);
          if (decoded !== v) patch[col] = decoded;
        }
      }
      if (Object.keys(patch).length > 0) updates.push({ id: row.id, patch, original: row });
    }

    console.log(`  rows to update: ${updates.length}`);
    if (updates.length > 0) {
      console.log("  sample:");
      for (const u of updates.slice(0, 5)) {
        for (const [col, val] of Object.entries(u.patch)) {
          const before = String(u.original[col]).slice(0, 70);
          const after = String(val).slice(0, 70);
          console.log(`    ${table}.${col}: "${before}" -> "${after}"`);
        }
      }
    }

    if (!WRITE) {
      totalChanged += updates.length;
      continue;
    }

    let ok = 0;
    let index = 0;
    async function worker() {
      while (index < updates.length) {
        const i = index++;
        const u = updates[i];
        const res = await fetch(`${SUPABASE_URL}/rest/v1/${table}?id=eq.${u.id}`, {
          method: "PATCH",
          headers: {
            apikey: SUPABASE_SERVICE_KEY,
            Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify(u.patch),
        });
        if (res.ok) ok++;
      }
    }
    await Promise.all(Array.from({ length: 8 }, worker));
    console.log(`  updated: ${ok}/${updates.length}`);
    totalChanged += ok;
  }

  console.log(`\nTotal rows ${WRITE ? "updated" : "would update"}: ${totalChanged}`);
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
