/**
 * dedupe_pass.js — Cross-source dedupe for events and venues.
 *
 * Per-source upserts (ticketmaster_sync, eventbrite_sync, run_crawls,
 * sync_municipal_feeds) all guard against re-inserting the same row twice via
 * dedupe_hash, but they have no notion of the SAME concert showing up under
 * different sources. This pass groups active rows by a content signature, picks
 * a canonical, and soft-deletes the duplicates.
 *
 * Two phases:
 *
 *   1. EVENTS — group by (start_hour, normalized_title, lat/lng grid ~110m).
 *      Canonical is the highest-ranked source (Ticketmaster > Eventbrite >
 *      crawler > municipal), tie-broken by newest id. Duplicates flip to
 *      is_active=false. community_interactions referring to the duplicate get
 *      repointed at the canonical so favorites/shares don't get orphaned.
 *
 *   2. VENUES — group by (normalized_name, lat/lng grid ~11m).
 *      Canonical is the highest public_interest_rank, tie-broken by event
 *      count, then oldest id. Duplicates flip to is_active=false.
 *      community_interactions repointed.
 *
 * Idempotent. Safe to re-run. Plan slots (embedded JSONB) are NOT repointed —
 * they're treated as historical snapshots, so saved plans keep their original
 * references intact even after a dup is deactivated.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/dedupe_pass.js
 *   DRY_RUN=1 SUPABASE_SERVICE_KEY=ey... node scripts/dedupe_pass.js   # report only
 */

const { SUPABASE_URL } = require("./lib/secrets");

const SUPABASE_SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").trim();
if (!SUPABASE_SERVICE_KEY) {
  console.error("Missing SUPABASE_SERVICE_KEY env var");
  process.exit(1);
}
const DRY_RUN = process.env.DRY_RUN === "1";

// ── Source-rank tables (higher = preferred canonical) ─────────────────────

const EVENT_SOURCE_RANK = {
  ticketmaster: 100,
  eventbrite: 80,
  // run_crawls.js stores source as the venue's source identifier — anything
  // not in this map gets a default of 60. Municipal_* and bo_web_promoted
  // explicitly lower so higher-fidelity commercial feeds win when both
  // report the same show.
  bo_web_promoted: 50,
};
function eventSourceRank(source) {
  if (!source) return 0;
  if (source.startsWith("municipal_")) return 40;
  return EVENT_SOURCE_RANK[source] ?? 60;
}

const VENUE_SOURCE_RANK = {
  google_places: 100,
  manual: 90,
  discover_venues: 80,
  event_extraction: 50,
};
function venueSourceRank(source) {
  return VENUE_SOURCE_RANK[source] ?? 70;
}

// ── PostgREST helpers ─────────────────────────────────────────────────────

function headers(extra = {}) {
  return {
    apikey: SUPABASE_SERVICE_KEY,
    Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
    "Content-Type": "application/json",
    ...extra,
  };
}

async function pagedSelect(table, query, pageSize = 1000) {
  const out = [];
  let from = 0;
  while (true) {
    const url = `${SUPABASE_URL}/rest/v1/${table}?${query}`;
    const res = await fetch(url, {
      headers: headers({ Range: `${from}-${from + pageSize - 1}` }),
    });
    if (!res.ok) throw new Error(`Select ${table}: ${res.status} ${await res.text()}`);
    const rows = await res.json();
    out.push(...rows);
    if (rows.length < pageSize) break;
    from += pageSize;
  }
  return out;
}

async function patch(table, filter, body) {
  if (DRY_RUN) return;
  const url = `${SUPABASE_URL}/rest/v1/${table}?${filter}`;
  const res = await fetch(url, { method: "PATCH", headers: headers(), body: JSON.stringify(body) });
  if (!res.ok) throw new Error(`Patch ${table}: ${res.status} ${await res.text()}`);
}

// ── Signatures ────────────────────────────────────────────────────────────

function normalizeTitle(s) {
  return (s || "")
    .toLowerCase()
    .replace(/[^a-z0-9 ]/g, "")
    .replace(/^(the|a|an)\s+/, "")
    .replace(/\s+/g, " ")
    .trim();
}

function normalizeVenueName(s) {
  return (s || "")
    .toLowerCase()
    .replace(/[^a-z0-9 ]/g, "")
    .replace(/^the\s+/, "")
    .replace(/\b(restaurant|club|theater|theatre|venue|hall|lounge|bar|cafe|gallery|museum|center|centre|park|stadium)\b/g, "")
    .replace(/\s+/g, " ")
    .trim();
}

function geoBucket(lat, lng, decimals) {
  if (lat == null || lng == null) return "no_geo";
  const f = Math.pow(10, decimals);
  return `${Math.round(lat * f) / f}|${Math.round(lng * f) / f}`;
}

function eventSignature(e) {
  const title = normalizeTitle(e.title);
  if (!title) return null;
  // Hour bucket so 7:00pm and 7:05pm collapse, but 7pm and 9pm don't.
  const hour = (e.start_at || "").slice(0, 13);
  if (!hour) return null;
  return `${hour}|${title}|${geoBucket(e.venue_lat, e.venue_lng, 3)}`;
}

function venueSignature(v) {
  const name = normalizeVenueName(v.name);
  if (!name) return null;
  return `${name}|${geoBucket(v.lat, v.lng, 4)}`;
}

// ── Repointing community_interactions ─────────────────────────────────────

async function repointInteractions(itemType, fromIds, toId) {
  if (fromIds.length === 0 || DRY_RUN) return 0;
  // PostgREST supports `item_id=in.(a,b,c)` with quoted UUIDs.
  const idList = fromIds.map(id => `"${id}"`).join(",");
  const filter = `item_type=eq.${itemType}&item_id=in.(${idList})`;
  const url = `${SUPABASE_URL}/rest/v1/community_interactions?${filter}`;
  const res = await fetch(url, {
    method: "PATCH",
    headers: headers({ Prefer: "return=representation" }),
    body: JSON.stringify({ item_id: toId }),
  });
  if (!res.ok) {
    // Likely a unique constraint on (user_id, item_id, item_type) — a user
    // who favorited both rows now has a conflict. Best-effort: log + continue.
    console.warn(`  (interactions repoint partially failed: ${res.status})`);
    return 0;
  }
  const updated = await res.json();
  return updated.length;
}

// ── Phase A: Event dedupe ─────────────────────────────────────────────────

async function dedupeEvents() {
  console.log("━━━ Phase A: Event dedupe ━━━");
  const nowIso = new Date().toISOString();
  const events = await pagedSelect(
    "events",
    `select=id,title,start_at,venue_lat,venue_lng,source,created_at&is_active=eq.true&start_at=gte.${nowIso}&order=created_at.asc`
  );
  console.log(`Loaded ${events.length} active future events`);

  const groups = new Map();
  for (const e of events) {
    const sig = eventSignature(e);
    if (!sig) continue;
    if (!groups.has(sig)) groups.set(sig, []);
    groups.get(sig).push(e);
  }

  let dupGroups = 0, dupRows = 0, repointed = 0;
  for (const [sig, rows] of groups) {
    if (rows.length < 2) continue;
    dupGroups++;
    rows.sort((a, b) => {
      const ar = eventSourceRank(a.source), br = eventSourceRank(b.source);
      if (ar !== br) return br - ar;
      // Tie: newest created_at (hopefully fewer schema gaps)
      return (b.created_at || "").localeCompare(a.created_at || "");
    });
    const canonical = rows[0];
    const dups = rows.slice(1);
    dupRows += dups.length;
    if (process.env.VERBOSE === "1") {
      console.log(`  [${rows.length}] "${canonical.title.slice(0, 50)}" canonical=${canonical.id.slice(0,8)} (${canonical.source}); dropping ${dups.map(d => d.source).join(",")}`);
    }
    const dupIds = dups.map(d => d.id);
    const moved = await repointInteractions("event", dupIds, canonical.id);
    repointed += moved;
    const idList = dupIds.map(id => `"${id}"`).join(",");
    await patch("events", `id=in.(${idList})`, { is_active: false, status: "duplicate" });
  }

  console.log(`Found ${dupGroups} duplicate groups covering ${dupRows} rows.`);
  console.log(`Soft-deleted ${dupRows} duplicate event(s); repointed ${repointed} community_interaction row(s).`);
  if (DRY_RUN) console.log("(DRY_RUN — no writes performed)");
}

// ── Phase B: Venue dedupe ─────────────────────────────────────────────────

async function dedupeVenues() {
  console.log("\n━━━ Phase B: Venue dedupe ━━━");
  // is_active may not exist on every deployment; coalesce.
  const venues = await pagedSelect(
    "venues",
    `select=id,name,lat,lng,source,public_interest_rank,city,created_at&order=created_at.asc`
  );
  console.log(`Loaded ${venues.length} venues`);

  // Pre-compute event counts per venue lat/lng (within the geo bucket) so we
  // can use it as a tiebreaker.
  const eventCountByGeo = new Map();
  const eventGeoCounts = await pagedSelect(
    "events",
    `select=venue_lat,venue_lng&is_active=eq.true&venue_lat=not.is.null`
  );
  for (const e of eventGeoCounts) {
    const k = geoBucket(e.venue_lat, e.venue_lng, 4);
    eventCountByGeo.set(k, (eventCountByGeo.get(k) || 0) + 1);
  }

  const groups = new Map();
  for (const v of venues) {
    const sig = venueSignature(v);
    if (!sig) continue;
    if (!groups.has(sig)) groups.set(sig, []);
    groups.get(sig).push(v);
  }

  let dupGroups = 0, dupRows = 0, repointed = 0;
  for (const [sig, rows] of groups) {
    if (rows.length < 2) continue;
    dupGroups++;
    rows.sort((a, b) => {
      const ar = venueSourceRank(a.source), br = venueSourceRank(b.source);
      if (ar !== br) return br - ar;
      const ap = a.public_interest_rank ?? 0, bp = b.public_interest_rank ?? 0;
      if (ap !== bp) return bp - ap;
      const ae = eventCountByGeo.get(geoBucket(a.lat, a.lng, 4)) || 0;
      const be = eventCountByGeo.get(geoBucket(b.lat, b.lng, 4)) || 0;
      if (ae !== be) return be - ae;
      return (a.created_at || "").localeCompare(b.created_at || ""); // oldest wins
    });
    const canonical = rows[0];
    const dups = rows.slice(1);
    dupRows += dups.length;
    if (process.env.VERBOSE === "1") {
      console.log(`  [${rows.length}] "${canonical.name.slice(0, 50)}" canonical=${canonical.id.slice(0,8)}; dropping ${dups.map(d => d.id.slice(0,8)).join(",")}`);
    }
    const dupIds = dups.map(d => d.id);
    const moved = await repointInteractions("venue", dupIds, canonical.id);
    repointed += moved;
    const idList = dupIds.map(id => `"${id}"`).join(",");
    // Try is_active first; fall back to status if column doesn't exist.
    try {
      await patch("venues", `id=in.(${idList})`, { is_active: false });
    } catch (err) {
      if (/is_active/.test(err.message)) {
        await patch("venues", `id=in.(${idList})`, { status: "duplicate" });
      } else throw err;
    }
  }

  console.log(`Found ${dupGroups} duplicate groups covering ${dupRows} rows.`);
  console.log(`Soft-deleted ${dupRows} duplicate venue(s); repointed ${repointed} community_interaction row(s).`);
  if (DRY_RUN) console.log("(DRY_RUN — no writes performed)");
}

// ── Main ──

async function main() {
  console.log(`Dedupe pass — ${DRY_RUN ? "DRY RUN" : "LIVE"}\n`);
  await dedupeEvents();
  await dedupeVenues();
  console.log("\nDone.");
}

main().catch(err => { console.error("Fatal:", err); process.exit(1); });
