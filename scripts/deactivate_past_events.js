/**
 * deactivate_past_events.js — Mark past events as inactive
 *
 * Deactivates events whose run window has fully ended:
 *   start_at < NOW() AND (end_at IS NULL OR end_at < NOW())
 *
 * The end_at clause matters for multi-day / long-running events (museum
 * exhibitions, seasonal programs, gallery installs). Previously only start_at
 * was checked, so a 2-month exhibition got deactivated the day after it
 * opened. ~992 such rows were wrongly hidden as of 2026-05-17.
 *
 * Paging note: previously the script issued one bulk PATCH on the entire
 * matching set with `Prefer: return=representation`. Postgres ran an UPDATE
 * across thousands of rows AND then a SELECT-back of every updated row in
 * the same transaction, which trips Supabase's 8s statement timeout.
 * Fix: select IDs in batches via the indexed start_at filter, PATCH each
 * batch with `Prefer: return=minimal` so Postgres skips the read-back.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/deactivate_past_events.js
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

const BATCH_SIZE = parseInt(process.env.BATCH_SIZE || "500", 10);
const MAX_BATCHES = parseInt(process.env.MAX_BATCHES || "200", 10);

let SUPABASE_SERVICE_KEY;

async function fetchPastIds(now) {
  // Indexed by (start_at) and filtered by is_active so the SELECT is bounded.
  // The or= clause keeps multi-day events alive while their end_at is still in
  // the future — without it, an exhibition opening today gets killed tomorrow.
  // We page implicitly: every loop returns the OLDEST batch still active, the
  // PATCH flips them, the next loop returns the next batch.
  const encodedNow = encodeURIComponent(now);
  const url =
    `${SUPABASE_URL}/rest/v1/events` +
    `?select=id` +
    `&start_at=lt.${encodedNow}` +
    `&is_active=eq.true` +
    `&or=(end_at.is.null,end_at.lt.${encodedNow})` +
    `&order=start_at.asc` +
    `&limit=${BATCH_SIZE}`;
  const res = await fetch(url, {
    headers: {
      apikey: SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
    },
  });
  if (!res.ok) throw new Error(`fetch ids: ${res.status} ${await res.text()}`);
  return res.json();
}

async function patchBatch(ids) {
  const url = `${SUPABASE_URL}/rest/v1/events?id=in.(${ids.join(",")})`;
  const res = await fetch(url, {
    method: "PATCH",
    headers: {
      apikey: SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      "Content-Type": "application/json",
      // return=minimal — skip the post-UPDATE SELECT, that's what was timing out.
      Prefer: "return=minimal",
    },
    body: JSON.stringify({ is_active: false }),
  });
  if (!res.ok) throw new Error(`patch: ${res.status} ${await res.text()}`);
}

async function main() {
  const secrets = await getSecrets([]);
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;
  const now = new Date().toISOString();

  let total = 0;
  for (let i = 0; i < MAX_BATCHES; i++) {
    const rows = await fetchPastIds(now);
    if (rows.length === 0) break;
    const ids = rows.map((r) => r.id);
    await patchBatch(ids);
    total += ids.length;
    console.log(`  batch ${i + 1}: deactivated ${ids.length} (running total ${total})`);
    if (rows.length < BATCH_SIZE) break;
  }
  console.log(`Done. Deactivated ${total} past event(s).`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
