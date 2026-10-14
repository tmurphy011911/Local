/**
 * backfill_usernames.js — assign a handle to existing user_profiles rows that
 * have none (username IS NULL).
 *
 * The 20260608140000_usernames.sql migration adds the column + a BEFORE INSERT
 * trigger, so NEW signups self-assign. This script fills the rows that already
 * existed when the column was added. It's kept out of the migration on purpose
 * (schema vs data separation), and gives the dry-run + rollback the Data Ops
 * backfill discipline requires.
 *
 * Handles are drawn from the same four pools the DB function uses:
 *   local####, mo####, lo####, fo####   (#### ∈ 1..10000 → 40,000 names)
 *
 * Assignment is done per-row from Node (not one big SQL UPDATE) so each pick is
 * checked against both the live table AND the names assigned earlier in THIS
 * run, avoiding the in-statement collision a set-based UPDATE could hit. The
 * DB's unique index on lower(username) is the final backstop.
 *
 * Safety:
 *   - DRY RUN by default: prints the count + a sample of proposed assignments.
 *   - WRITE=1 applies, writing a rollback manifest (id + before=null) so the
 *     run is fully reversible.
 *   - REVERT=<manifest> nulls the assigned handles back out.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/backfill_usernames.js
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 node scripts/backfill_usernames.js
 *   SUPABASE_SERVICE_KEY=ey... REVERT=scripts/.manifests/usernames_<ts>.json node scripts/backfill_usernames.js
 */

const fs = require("fs");
const path = require("path");
const { getSecrets, SUPABASE_URL } = require("../lib/secrets");

const WRITE = process.env.WRITE === "1";
const REVERT = process.env.REVERT || null;

const PREFIXES = ["bo"];
const POOL_MAX = 10000;
const POOL_SIZE = PREFIXES.length * POOL_MAX;

let SUPABASE_KEY = null;

const MANIFEST = [];
function flushManifest() {
  if (!WRITE || MANIFEST.length === 0) return null;
  const dir = path.join(__dirname, ".manifests");
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `usernames_${Date.now()}.json`);
  fs.writeFileSync(file, JSON.stringify(MANIFEST, null, 2));
  return file;
}

async function supaGet(p) {
  for (let attempt = 1; attempt <= 3; attempt++) {
    const res = await fetch(`${SUPABASE_URL}/rest/v1/${p}`, {
      headers: { apikey: SUPABASE_KEY, Authorization: `Bearer ${SUPABASE_KEY}` },
    });
    if (res.ok) return res.json();
    const body = await res.text();
    if (res.status < 500 || attempt === 3) throw new Error(`GET ${p}: ${res.status} ${body}`);
    await new Promise((r) => setTimeout(r, 1500 * attempt));
  }
}

async function supaPatch(idVal, patch) {
  const res = await fetch(
    `${SUPABASE_URL}/rest/v1/user_profiles?id=eq.${encodeURIComponent(idVal)}`,
    {
      method: "PATCH",
      headers: {
        apikey: SUPABASE_KEY,
        Authorization: `Bearer ${SUPABASE_KEY}`,
        "Content-Type": "application/json",
        Prefer: "return=minimal",
      },
      body: JSON.stringify(patch),
    },
  );
  return { ok: res.ok, status: res.status, body: res.ok ? "" : await res.text() };
}

function randomHandle() {
  const prefix = PREFIXES[Math.floor(Math.random() * PREFIXES.length)];
  const n = 1 + Math.floor(Math.random() * POOL_MAX);
  return `${prefix}${n}`;
}

// Pick a handle not already used (live table ∪ this run). Throws if the pools
// look exhausted, matching the DB function's loud-failure behavior.
function pickUnused(used) {
  for (let attempts = 0; attempts <= 500; attempts++) {
    const h = randomHandle();
    if (!used.has(h.toLowerCase())) return h;
  }
  throw new Error("pickUnused: pools exhausted (could not find a free handle in 500 tries)");
}

async function main() {
  const secrets = await getSecrets([]);
  SUPABASE_KEY = secrets.SUPABASE_SERVICE_KEY;

  // ── REVERT: null out handles from a prior run's manifest, then exit. ──
  if (REVERT) {
    const entries = JSON.parse(fs.readFileSync(REVERT, "utf8"));
    console.log(`REVERT: nulling ${entries.length} handles from ${REVERT}\n`);
    let restored = 0;
    for (const e of entries) {
      const r = await supaPatch(e.id, { username: null, username_is_custom: false });
      if (r.ok) restored++;
      else console.warn(`  ${e.id}: ${r.status} ${r.body}`);
    }
    console.log(`Done. Reverted ${restored}/${entries.length} rows.`);
    return;
  }

  console.log(`Mode: ${WRITE ? "WRITE" : "DRY RUN"}\n`);

  // All handles already taken (custom OR auto-assigned) — seed the used-set so
  // we never propose a collision.
  const taken = await supaGet("user_profiles?select=username&username=not.is.null");
  const used = new Set(taken.map((r) => String(r.username).toLowerCase()));
  console.log(`Handles already in use: ${used.size} / ${POOL_SIZE} pool`);

  const todo = await supaGet("user_profiles?select=id&username=is.null");
  console.log(`Rows needing a handle: ${todo.length}\n`);

  if (todo.length === 0) {
    console.log("Nothing to do.");
    return;
  }
  if (todo.length + used.size > POOL_SIZE * 0.9) {
    console.warn(
      `WARNING: assignment would push the pool past 90% full ` +
        `(${todo.length + used.size}/${POOL_SIZE}). Widen the pools before running.\n`,
    );
  }

  const planned = [];
  for (const row of todo) {
    const handle = pickUnused(used);
    used.add(handle.toLowerCase());
    planned.push({ id: row.id, username: handle });
  }

  const sample = planned.slice(0, 10).map((p) => `  ${p.id} → ${p.username}`).join("\n");
  console.log(`Proposed assignments (first ${Math.min(10, planned.length)}):\n${sample}\n`);

  if (!WRITE) {
    console.log(`DRY RUN — would assign ${planned.length} handles. Re-run with WRITE=1 to apply.`);
    return;
  }

  let ok = 0;
  let fail = 0;
  for (const p of planned) {
    // is_custom stays false — these are auto-assigned, not user-chosen.
    const r = await supaPatch(p.id, { username: p.username, username_is_custom: false });
    if (r.ok) {
      ok++;
      MANIFEST.push({ id: p.id, before: null, after: p.username });
    } else {
      fail++;
      // 23505 here means a concurrent writer grabbed the same handle — rare at
      // this scale; re-running the script will retry the still-null row.
      console.warn(`  ${p.id} → ${p.username}: ${r.status} ${r.body}`);
    }
  }

  const file = flushManifest();
  console.log(`\nDone. ${ok} assigned, ${fail} failed.`);
  if (file) {
    console.log(`Rollback manifest: ${file}`);
    console.log(`  Undo with: REVERT=${file} node scripts/backfill_usernames.js`);
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
