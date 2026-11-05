/**
 * strip_title_date_suffix.js — Remove redundant "- <Month> <Day>" suffixes
 * from event titles.
 *
 * Crawlers used to bake the occurrence date into recurring-event titles
 * ("Trivia Night - March 31", "SOWA Sunday - June 6") so each instance was
 * distinguishable. The date lives in `start_at` and is rendered as its own
 * property everywhere the title shows, so the suffix is pure redundancy — and
 * it actively defeats cross-source dedupe (a clean-title row and a
 * date-in-title row of the same event hash differently). The crawler prompt no
 * longer adds it; this backfill cleans the rows already in the table.
 *
 * Only strips a trailing month-day suffix, anchored at end-of-string, and only
 * when non-date text remains — so "Brunch - All Day" or "Concert - 2026 Tour"
 * are left alone. Title-only update; reversible via the manifest it writes.
 *
 * Usage:
 *   # dry run (default) — prints what WOULD change, writes nothing
 *   SUPABASE_SERVICE_KEY=ey... node scripts/strip_title_date_suffix.js
 *
 *   # apply — writes new titles + a rollback manifest to scripts/.manifests/
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 node scripts/strip_title_date_suffix.js
 *
 *   # roll back a previous run
 *   SUPABASE_SERVICE_KEY=ey... REVERT=scripts/.manifests/strip_title_<ts>.json \
 *     node scripts/strip_title_date_suffix.js
 *
 * Scope: by default only is_active=true rows (what users actually see). Set
 * INCLUDE_INACTIVE=1 to clean the whole table.
 */

const fs = require("fs");
const path = require("path");
const { getSecrets, SUPABASE_URL } = require("../lib/secrets");

const WRITE = process.env.WRITE === "1";
const REVERT = process.env.REVERT || "";
const INCLUDE_INACTIVE = process.env.INCLUDE_INACTIVE === "1";
const PAGE = parseInt(process.env.PAGE || "1000", 10);

// Mirror of dedupe_pass.js TITLE_DATE_SUFFIX. Kept in sync by hand — both must
// match the same set of suffixes.
const TITLE_DATE_SUFFIX =
  /\s*[-–—]\s*(jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|jun(e)?|jul(y)?|aug(ust)?|sep(t(ember)?)?|oct(ober)?|nov(ember)?|dec(ember)?)\s+\d{1,2}(st|nd|rd|th)?(,?\s*\d{4})?\s*$/i;

let SERVICE_KEY;

function headers(extra = {}) {
  return {
    apikey: SERVICE_KEY,
    Authorization: `Bearer ${SERVICE_KEY}`,
    "Content-Type": "application/json",
    ...extra,
  };
}

function strip(title) {
  const next = (title || "").replace(TITLE_DATE_SUFFIX, "").trim();
  // Guard: never blank a title or strip down to nothing meaningful.
  if (!next || next === title) return null;
  return next;
}

async function patchTitle(id, title) {
  const url = `${SUPABASE_URL}/rest/v1/events?id=eq.${id}`;
  const res = await fetch(url, {
    method: "PATCH",
    headers: headers({ Prefer: "return=minimal" }),
    body: JSON.stringify({ title }),
  });
  if (!res.ok) throw new Error(`patch ${id}: ${res.status} ${await res.text()}`);
}

async function revert() {
  const manifest = JSON.parse(fs.readFileSync(REVERT, "utf8"));
  console.log(`Reverting ${manifest.length} title(s) from ${REVERT}`);
  let n = 0;
  for (const { id, old } of manifest) {
    await patchTitle(id, old);
    if (++n % 100 === 0) console.log(`  reverted ${n}/${manifest.length}`);
  }
  console.log(`Done. Reverted ${n} title(s).`);
}

async function run() {
  // Keyset-paginate on the PK (id) so every page rides the primary-key index —
  // a server-side `title ILIKE '%- %'` forces a full seq scan and trips
  // Supabase's 8s statement timeout. We page the (active) table by id and do
  // the " - " containment + regex match in JS instead.
  const activeClause = INCLUDE_INACTIVE ? "" : "&is_active=eq.true";
  const changes = [];
  let scanned = 0;
  let after = "00000000-0000-0000-0000-000000000000";
  for (;;) {
    const url =
      `${SUPABASE_URL}/rest/v1/events` +
      `?select=id,title${activeClause}` +
      `&id=gt.${after}` +
      `&order=id.asc&limit=${PAGE}`;
    const res = await fetch(url, { headers: headers() });
    if (!res.ok) throw new Error(`fetch: ${res.status} ${await res.text()}`);
    const rows = await res.json();
    if (rows.length === 0) break;
    scanned += rows.length;
    for (const r of rows) {
      after = r.id;
      if (!r.title || !r.title.includes("- ")) continue;
      const next = strip(r.title);
      if (next) changes.push({ id: r.id, old: r.title, next });
    }
    if (rows.length < PAGE) break;
  }

  console.log(`Scanned ${scanned} candidate row(s); ${changes.length} need stripping.`);
  for (const c of changes.slice(0, 20)) {
    console.log(`  "${c.old}"  →  "${c.next}"`);
  }
  if (changes.length > 20) console.log(`  … and ${changes.length - 20} more`);

  if (!WRITE) {
    console.log("\nDRY RUN — no writes. Re-run with WRITE=1 to apply.");
    return;
  }

  // Persist a rollback manifest BEFORE writing, so a mid-run crash is still
  // recoverable for the rows already touched.
  const dir = path.join(__dirname, ".manifests");
  fs.mkdirSync(dir, { recursive: true });
  const manifestPath = path.join(dir, `strip_title_${Date.now()}.json`);
  fs.writeFileSync(manifestPath, JSON.stringify(changes, null, 2));
  console.log(`\nRollback manifest: ${manifestPath}`);

  let n = 0;
  for (const c of changes) {
    await patchTitle(c.id, c.next);
    if (++n % 100 === 0) console.log(`  updated ${n}/${changes.length}`);
  }
  console.log(`Done. Stripped ${n} title(s). Revert with REVERT=${manifestPath}`);
}

async function main() {
  const secrets = await getSecrets([]);
  SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;
  if (REVERT) return revert();
  return run();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
