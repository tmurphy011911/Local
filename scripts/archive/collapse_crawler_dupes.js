/**
 * collapse_crawler_dupes.js — One-shot cleanup for crawler-source duplicates
 *
 * The crawler's pre-fix dedupe_hash used the raw decoded title. Week-over-week
 * LLM extraction variation ("Farm to Form: A Garden Dinner" vs
 * "Farm to Form — Garden Dinner" vs "Farm-to-Form Garden Dinner") hashed
 * differently and produced 3-4 rows per real event. After the run_crawls.js
 * fix (normalizes title to lowercase + alphanumeric + first 30 chars before
 * hashing), new rows dedupe correctly, but the existing dupes need a one-time
 * pass to collapse.
 *
 * Sibling-aware: marks the older crawler row as the winner, points younger
 * crawler rows at it via `duplicate_of`, and sets is_active=false. Leaves
 * cross-source dupes (crawler vs Ticketmaster vs RSS) for dedup_events.js
 * to handle separately — those need fuzzy Jaccard, not the exact title-key
 * match this script uses.
 *
 * Prereq: 20260430000001_event_duplicate_of.sql (already applied).
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/collapse_crawler_dupes.js               # dry run
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 node scripts/collapse_crawler_dupes.js       # apply
 *   SUPABASE_SERVICE_KEY=ey... STATE=MA node scripts/collapse_crawler_dupes.js      # scope by state
 */

const { SUPABASE_URL } = require("./lib/secrets");
const SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").replace(/\s/g, "");
const WRITE = process.env.WRITE === "1";
const STATE = process.env.STATE || null;

if (!SERVICE_KEY) { console.error("Missing SUPABASE_SERVICE_KEY"); process.exit(1); }

const headers = {
  apikey: SERVICE_KEY,
  Authorization: `Bearer ${SERVICE_KEY}`,
  "Content-Type": "application/json",
};

// Must match the run_crawls.js normalization or new + old won't collide.
function titleKey(title) {
  return (title || "").toLowerCase().replace(/[^a-z0-9]/g, "").slice(0, 30);
}

// Group by (titleKey, calendar-date-of-start_at, venue_name). Date alone is
// enough to discriminate same-title repeating series ("Open Studio" every
// Saturday). Time isn't included — if the crawler captured two times for
// the same event, those genuinely are the same event with normalize jitter.
function dateKey(iso) {
  if (!iso) return "no-date";
  return iso.slice(0, 10);
}

async function fetchAllCrawlerEvents() {
  // Keyset pagination on id — uses the events_pkey index, no sort, no
  // OFFSET row-skipping. Order-by-created_at + OFFSET tripped the 8s
  // statement timeout on first run because there's no supporting index
  // and OFFSET re-scans from row 0 each batch.
  const all = [];
  const limit = 1000;
  let cursor = null;
  while (true) {
    let url =
      `${SUPABASE_URL}/rest/v1/events` +
      `?select=id,title,start_at,venue_name,venue_state,source,is_active,image_url,description,ticket_url,price_min,confidence_score,time_known,created_at` +
      `&source=eq.crawl` +
      `&is_active=eq.true` +
      `&order=id.asc` +
      `&limit=${limit}`;
    if (cursor) url += `&id=gt.${cursor}`;
    if (STATE) url += `&venue_state=eq.${STATE}`;
    const res = await fetch(url, { headers });
    if (!res.ok) throw new Error(`fetch: ${res.status} ${await res.text()}`);
    const rows = await res.json();
    all.push(...rows);
    process.stdout.write(`  fetched ${all.length}\r`);
    if (rows.length < limit) break;
    cursor = rows[rows.length - 1].id;
  }
  console.log("");
  return all;
}

// Winner = the oldest row (created first). Rationale: it carries the
// canonical source_id history, and the newer rows are LLM-noise rewrites
// of the same underlying event.
function pickWinner(rows) {
  return rows.slice().sort((a, b) =>
    (a.created_at || "").localeCompare(b.created_at || "")
  )[0];
}

async function main() {
  console.log(`Mode: ${WRITE ? "WRITE" : "DRY RUN"}${STATE ? ` (STATE=${STATE})` : ""}`);

  const events = await fetchAllCrawlerEvents();
  console.log(`Loaded ${events.length} active crawler events`);

  const groups = new Map();
  for (const e of events) {
    if (!e.venue_name || !e.start_at) continue;
    const k = `${e.venue_name}::${dateKey(e.start_at)}::${titleKey(e.title)}`;
    if (!k.endsWith("::")) {
      if (!groups.has(k)) groups.set(k, []);
      groups.get(k).push(e);
    }
  }

  const pairs = []; // { winner, loser }
  for (const [, list] of groups) {
    if (list.length < 2) continue;
    const winner = pickWinner(list);
    for (const e of list) {
      if (e.id !== winner.id) pairs.push({ winner, loser: e });
    }
  }

  console.log(`Duplicate groups: ${[...groups.values()].filter(g => g.length > 1).length}`);
  console.log(`Rows to collapse: ${pairs.length}`);
  console.log("");

  const sample = pairs.slice(0, 30);
  for (const p of sample) {
    console.log(`${p.winner.venue_name} · ${dateKey(p.winner.start_at)}`);
    console.log(`  KEEP  [${p.winner.id.slice(0, 8)}] ${p.winner.title}`);
    console.log(`  DROP  [${p.loser.id.slice(0, 8)}] ${p.loser.title}`);
    console.log("");
  }
  if (pairs.length > sample.length) console.log(`  ... and ${pairs.length - sample.length} more`);

  if (!WRITE) {
    console.log("\nDRY RUN — pass WRITE=1 to apply (sets is_active=false + duplicate_of on losers).");
    return;
  }

  if (pairs.length === 0) return;

  const byWinner = new Map();
  for (const p of pairs) {
    if (!byWinner.has(p.winner.id)) byWinner.set(p.winner.id, []);
    byWinner.get(p.winner.id).push(p.loser.id);
  }

  let updated = 0;
  for (const [winnerId, loserIds] of byWinner) {
    for (let i = 0; i < loserIds.length; i += 100) {
      const slice = loserIds.slice(i, i + 100);
      const url = `${SUPABASE_URL}/rest/v1/events?id=in.(${slice.join(",")})`;
      const res = await fetch(url, {
        method: "PATCH",
        headers: { ...headers, Prefer: "return=minimal" },
        body: JSON.stringify({
          is_active: false,
          duplicate_of: winnerId,
          updated_at: new Date().toISOString(),
        }),
      });
      if (!res.ok) throw new Error(`patch: ${res.status} ${await res.text()}`);
      updated += slice.length;
      process.stdout.write(`  patched ${updated}/${pairs.length}\r`);
    }
  }
  console.log("");
  console.log(`Done. Collapsed ${updated} crawler duplicates.`);
}

main().catch((e) => { console.error(e); process.exit(1); });
