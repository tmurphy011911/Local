/**
 * fix_ticketmaster_midnight.js
 *
 * One-shot remediation for Ticketmaster events stored at midnight UTC. The old
 * sync fell back to bare `localDate` when `dateTime` was missing (TBD-time
 * playoff games etc.), and Postgres parsed those as midnight UTC, which
 * renders as 8 PM the previous calendar day in Eastern.
 *
 * Fix forward: ticketmaster_sync.js now stores `${localDate}T12:00:00Z` plus
 * time_known=false. This script patches existing rows the same way:
 *   - shift start_at +12h so it stays on the right calendar day in any US tz
 *   - set time_known=false so the client renders "Time TBD"
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/fix_ticketmaster_midnight.js          # dry run
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 node scripts/fix_ticketmaster_midnight.js  # apply
 */

const { SUPABASE_URL } = require("./lib/secrets");
const SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").replace(/\s/g, "");
const WRITE = process.env.WRITE === "1";

if (!SERVICE_KEY) {
  console.error("Missing SUPABASE_SERVICE_KEY");
  process.exit(1);
}

const headers = {
  apikey: SERVICE_KEY,
  Authorization: `Bearer ${SERVICE_KEY}`,
  "Content-Type": "application/json",
};

async function fetchPage(offset, limit) {
  const url =
    `${SUPABASE_URL}/rest/v1/events` +
    `?source=eq.ticketmaster` +
    `&select=id,start_at,time_known` +
    `&order=start_at.asc` +
    `&offset=${offset}&limit=${limit}`;
  const res = await fetch(url, { headers });
  if (!res.ok) {
    throw new Error(`fetch ${offset}: ${res.status} ${await res.text()}`);
  }
  return res.json();
}

function isMidnightUtc(iso) {
  // matches "2026-03-08T00:00:00+00:00" / "2026-03-08 00:00:00+00" / etc.
  return /T?\s?00:00:00(\.0+)?(\+00:?00|Z)?$/.test(iso) ||
         iso.endsWith(" 00:00:00+00") ||
         iso.endsWith("T00:00:00+00:00");
}

async function patchBatch(ids, newStartAt) {
  const url =
    `${SUPABASE_URL}/rest/v1/events` +
    `?id=in.(${ids.join(",")})`;
  const res = await fetch(url, {
    method: "PATCH",
    headers: { ...headers, Prefer: "return=minimal" },
    body: JSON.stringify({
      start_at: newStartAt,
      time_known: false,
      updated_at: new Date().toISOString(),
    }),
  });
  if (!res.ok) {
    throw new Error(`patch: ${res.status} ${await res.text()}`);
  }
}

async function main() {
  console.log(`Mode: ${WRITE ? "WRITE" : "DRY RUN"}`);

  // Page through all ticketmaster rows, collect candidates client-side.
  const candidates = []; // { id, start_at }
  let offset = 0;
  const limit = 1000;
  while (true) {
    const rows = await fetchPage(offset, limit);
    for (const r of rows) {
      if (r.time_known === false) continue;
      if (isMidnightUtc(r.start_at)) {
        candidates.push(r);
      }
    }
    process.stdout.write(`  scanned ${offset + rows.length}, candidates ${candidates.length}\r`);
    if (rows.length < limit) break;
    offset += limit;
  }
  console.log("");
  console.log(`Total candidates: ${candidates.length}`);

  if (candidates.length === 0 || !WRITE) {
    console.log("Sample (first 10):");
    for (const c of candidates.slice(0, 10)) console.log(`  ${c.id}  ${c.start_at}`);
    if (!WRITE) console.log("\nDRY RUN — pass WRITE=1 to apply.");
    return;
  }

  // Group by start_at so each date becomes one batched PATCH.
  const byDate = new Map();
  for (const c of candidates) {
    const k = c.start_at;
    if (!byDate.has(k)) byDate.set(k, []);
    byDate.get(k).push(c.id);
  }

  let updated = 0;
  for (const [oldStartAt, ids] of byDate) {
    // oldStartAt is "YYYY-MM-DD 00:00:00+00" or ISO with T. Build the +12h ISO.
    const isoBase = oldStartAt.includes("T")
      ? oldStartAt.replace("T00:00:00", "T12:00:00")
      : oldStartAt.replace(" 00:00:00", " 12:00:00");
    // Patch in chunks of 200 IDs to keep URL length sane.
    for (let i = 0; i < ids.length; i += 200) {
      const slice = ids.slice(i, i + 200);
      await patchBatch(slice, isoBase);
      updated += slice.length;
      process.stdout.write(`  patched ${updated}/${candidates.length}\r`);
    }
  }
  console.log("");
  console.log(`Done. Updated ${updated} rows.`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
