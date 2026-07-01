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

// Safety gate for the unattended (daily/weekly CI) job: if a phase wants to
// soft-delete more than this fraction of its population, abort instead of
// writing — a sudden spike usually means a signature bug, not real dups.
// Override with FORCE=1 for an intentional large collapse (e.g. the first run
// after the title-suffix cleanup). ~26% event dups is normal here, so default
// to 0.45.
const MAX_DUP_FRACTION = parseFloat(process.env.MAX_DUP_FRACTION || "0.45");
const FORCE = process.env.FORCE === "1";

function assertUnderGate(label, dupRows, population) {
  const frac = population > 0 ? dupRows / population : 0;
  const pct = (frac * 100).toFixed(1);
  if (frac > MAX_DUP_FRACTION && !FORCE) {
    throw new Error(
      `ABORT (${label}): ${dupRows}/${population} rows (${pct}%) exceed the ` +
        `${(MAX_DUP_FRACTION * 100).toFixed(0)}% safety gate. Re-run with FORCE=1 ` +
        `if this is intentional.`
    );
  }
  console.log(`  Safety gate OK (${label}): ${pct}% ≤ ${(MAX_DUP_FRACTION * 100).toFixed(0)}%`);
}

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

// The `venues` table has no `source` column, so we can't rank a canonical by
// provenance. Instead score by how complete the row is — a venue with a photo,
// website, address and description is the better one to keep. Each present
// field is worth a point.
function venueCompleteness(v) {
  return (
    (v.photo_url ? 1 : 0) +
    (v.website ? 1 : 0) +
    (v.address ? 1 : 0) +
    (v.description ? 1 : 0) +
    (v.events_url ? 1 : 0)
  );
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

// Keyset-paginate on the primary key. Offset/Range paging over 100k+ rows with
// an `order=created_at` sort intermittently trips Supabase's 8s statement
// timeout (deep offsets re-scan from the top each page). Paging by `id=gt.<last>`
// rides the PK index, so every page is cheap and constant-time. Callers MUST
// include `id` in their select; any caller-supplied `order=` is ignored.
async function pagedSelect(table, query, pageSize = 1000) {
  const baseQuery = query.replace(/&?order=[^&]*/g, "");
  const out = [];
  let after = "00000000-0000-0000-0000-000000000000";
  while (true) {
    const url =
      `${SUPABASE_URL}/rest/v1/${table}?${baseQuery}` +
      `&id=gt.${after}&order=id.asc&limit=${pageSize}`;
    const res = await fetch(url, { headers: headers() });
    if (!res.ok) throw new Error(`Select ${table}: ${res.status} ${await res.text()}`);
    const rows = await res.json();
    out.push(...rows);
    if (rows.length < pageSize) break;
    after = rows[rows.length - 1].id;
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

// Trailing "- <Month> <Day>" suffix that crawlers used to bake into recurring
// titles (e.g. "Trivia Night - March 31"). The date lives in start_at now, so
// strip it here defensively: otherwise a source that still ships date-in-title
// gets a different signature than the clean-title version of the same event and
// the two never dedupe against each other.
const TITLE_DATE_SUFFIX =
  /\s*[-–—]\s*(jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|jun(e)?|jul(y)?|aug(ust)?|sep(t(ember)?)?|oct(ober)?|nov(ember)?|dec(ember)?)\s+\d{1,2}(st|nd|rd|th)?(,?\s*\d{4})?\s*$/i;

function normalizeTitle(s) {
  return (s || "")
    .toLowerCase()
    .replace(TITLE_DATE_SUFFIX, "")
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

// Generic recurring-event names that are NOT unique acts — must never collapse
// across venues on headliner alone ("Trivia Night" at 5 bars is 5 events).
const GENERIC_HEADLINERS = new Set([
  "trivia night", "live music", "open mic", "dj night", "karaoke night",
  "happy hour", "bingo night", "comedy night", "game night", "ladies night",
  "open studios", "farmers market", "story time", "paint night", "date night",
  "first friday", "final friday", "third thursday", "sunday service",
  "live jazz", "jazz brunch", "drag brunch", "line dancing", "salsa night",
]);

// The headliner (leading act) of a concert title: everything before the first
// subtitle / opener / ticket-product delimiter. "Noah Kahan: The Great Divide
// Tour", "Noah Kahan w/ Gigi Perez", "Noah Kahan: ... - Make a Difference
// Tickets" all → "noah kahan".
const HEADLINER_DELIM = /\s(?:-|–|—|w\/|with|feat\.?|ft\.?|featuring|presents|·)\s|:/i;
function headliner(title) {
  let t = (title || "").toLowerCase().replace(/\(.*?\)/g, " ");
  const m = t.match(HEADLINER_DELIM);
  if (m) t = t.slice(0, m.index);
  return t.replace(/[^a-z0-9 ]/g, " ").replace(/^(the|a|an)\s+/, "").replace(/\s+/g, " ").trim();
}

function eventSignature(e) {
  // Ticketmaster exposes a single show as several sellable rows whose titles
  // share only the ARTIST (main tour / "w/ opener" / charity tickets), so
  // exact-title matching misses them. Group TICKETMASTER rows by headliner +
  // hour + venue-geo. Restricted to source=ticketmaster (that's the only source
  // with this multi-listing pattern — crawl/eventbrite workshops like "Resume
  // Builder" run at many venues and must NOT collapse on a shared title).
  // Guards: distinctive multi-word headliner (not a generic recurring name),
  // an hour bucket (a genuine matinee+evening double won't merge), and a
  // ~1km geo bucket (belt-and-suspenders against any cross-venue merge).
  if (e.source === "ticketmaster") {
    const h = headliner(e.title);
    const hour = (e.start_at || "").slice(0, 13);
    if (h && hour && h.includes(" ") && h.length >= 6 && !GENERIC_HEADLINERS.has(h)) {
      return `hl|${hour}|${h}|${geoBucket(e.venue_lat, e.venue_lng, 2)}`;
    }
  }
  const title = normalizeTitle(e.title);
  if (!title) return null;
  // Hour bucket so 7:00pm and 7:05pm collapse, but 7pm and 9pm don't.
  const hour = (e.start_at || "").slice(0, 13);
  if (!hour) return null;
  return `${hour}|${title}|${geoBucket(e.venue_lat, e.venue_lng, 3)}`;
}

// ── Same-day title-variant clustering (recurring / crawl dups) ─────────────
// The signature above needs an EXACT title, so it misses same-day variants of
// one event: "First Friday" / "Open Studios: First Friday" / "Open Studios:
// First Fridays" at SoWa Artists Guild on the same Friday. These match on a
// SUBSET of significant title tokens, same venue-geo, same LOCAL DATE (never
// across dates — a weekly event's Jul 3 / Jul 10 rows are separate occurrences,
// NOT dups).
const TITLE_STOP = new Set([
  "the", "a", "an", "of", "at", "and", "for", "to", "in", "on", "with", "w",
  "presents", "presented", "by", "from", "this", "your", "our", "you", "amp",
  "featuring", "feat", "ft",
]);
function sigTokens(title) {
  const t = (title || "").toLowerCase().replace(/\(.*?\)/g, " ").replace(/[^a-z0-9 ]/g, " ");
  const out = new Set();
  for (let w of t.split(/\s+/)) {
    if (!w || TITLE_STOP.has(w) || /^\d+$/.test(w)) continue;
    if (w.length > 3 && w.endsWith("s")) w = w.slice(0, -1); // crude plural stem
    out.add(w);
  }
  return out;
}
function knownHour(e) {
  const st = e.start_at || "";
  if (!e.time_known || st.slice(11, 16) === "00:00") return null; // date-only / all-day
  const h = parseInt(st.slice(11, 13), 10);
  return isNaN(h) ? null : h;
}
function isSubset(a, b) { for (const x of a) if (!b.has(x)) return false; return true; }
// Two same-venue same-date rows are the same event when one title's significant
// tokens are a SUBSET of the other's (variant with extra descriptor) — NOT just
// sharing a generic phrase ("Hoyt Lake Walking Tour" vs "Parkside Walking Tour"
// aren't subsets, so they stay separate). Guards: >=2 significant tokens, and a
// >3h known-time gap means genuine separate showings (matinee vs evening).
function canMergeSameDay(a, b) {
  const sa = sigTokens(a.title), sb = sigTokens(b.title);
  if (sa.size < 2 || sb.size < 2) return false;
  if (!(isSubset(sa, sb) || isSubset(sb, sa))) return false;
  const ha = knownHour(a), hb = knownHour(b);
  if (ha != null && hb != null && Math.abs(ha - hb) > 3) return false;
  return true;
}

// How complete/useful an event row is — the merge KEEPS the richest sibling
// within a source so we never drop the row with the real image/time for a
// sparse duplicate. time_known is weighted heavily: a row with a real start
// time must beat a date-only sibling (else the UI shows "12 AM").
function eventCompleteness(e) {
  return (
    (e.image_url ? 2 : 0) +
    (e.description ? 2 : 0) +
    (e.ticket_url ? 1 : 0) +
    (e.price_min != null ? 1 : 0) +
    (e.time_known ? 3 : 0) +
    (e.confidence_score ?? 0.5)
  );
}

// Canonical selection shared by both event passes:
//   1. highest source rank (ticketmaster > eventbrite > crawl > municipal) —
//      the authoritative feed has the correct venue/time/ticket link;
//   2. then most complete row (image, real time, description, tickets);
//   3. then newest created_at (fewest schema gaps).
function eventCanonicalCmp(a, b) {
  const ar = eventSourceRank(a.source), br = eventSourceRank(b.source);
  if (ar !== br) return br - ar;
  const ac = eventCompleteness(a), bc = eventCompleteness(b);
  if (ac !== bc) return bc - ac;
  return (b.created_at || "").localeCompare(a.created_at || "");
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
    `select=id,title,start_at,venue_lat,venue_lng,source,time_known,image_url,description,ticket_url,price_min,confidence_score,created_at&is_active=eq.true&start_at=gte.${nowIso}&order=created_at.asc`
  );
  console.log(`Loaded ${events.length} active future events`);

  // Plan first (collect canonical + dup ids), gate-check the total, THEN write.
  let dupGroups = 0, dupRows = 0, repointed = 0;
  const plan = [];
  const claimed = new Set();

  // ── Pass 1: same venue-geo + same LOCAL DATE, subset-of-title clustering.
  const byVenueDate = new Map();
  for (const e of events) {
    const g = geoBucket(e.venue_lat, e.venue_lng, 3);
    if (g === "no_geo") continue;
    const date = (e.start_at || "").slice(0, 10);
    if (!date) continue;
    const k = `${g}|${date}`;
    if (!byVenueDate.has(k)) byVenueDate.set(k, []);
    byVenueDate.get(k).push(e);
  }
  for (const rows of byVenueDate.values()) {
    if (rows.length < 2) continue;
    const usedLocal = new Array(rows.length).fill(false);
    for (let i = 0; i < rows.length; i++) {
      if (usedLocal[i]) continue;
      const cluster = [rows[i]];
      for (let j = i + 1; j < rows.length; j++) {
        if (usedLocal[j]) continue;
        if (cluster.some(c => canMergeSameDay(c, rows[j]))) { cluster.push(rows[j]); usedLocal[j] = true; }
      }
      usedLocal[i] = true;
      if (cluster.length < 2) continue;
      cluster.sort(eventCanonicalCmp);
      const canonical = cluster[0], dups = cluster.slice(1);
      dupGroups++; dupRows += dups.length;
      canonical && claimed.add(canonical.id);
      dups.forEach(d => claimed.add(d.id));
      if (process.env.VERBOSE === "1") {
        console.log(`  [sameday ${cluster.length}] "${canonical.title.slice(0, 50)}"; dropping ${dups.map(d => `"${d.title.slice(0,30)}"`).join(", ")}`);
      }
      plan.push({ canonicalId: canonical.id, dupIds: dups.map(d => d.id) });
    }
  }

  // ── Pass 2: content signature (exact title+geo+hour, and Ticketmaster
  // headliner) on rows Pass 1 didn't already claim.
  const groups = new Map();
  for (const e of events) {
    if (claimed.has(e.id)) continue;
    const sig = eventSignature(e);
    if (!sig) continue;
    if (!groups.has(sig)) groups.set(sig, []);
    groups.get(sig).push(e);
  }
  for (const [, rows] of groups) {
    if (rows.length < 2) continue;
    dupGroups++;
    rows.sort(eventCanonicalCmp);
    const canonical = rows[0], dups = rows.slice(1);
    dupRows += dups.length;
    if (process.env.VERBOSE === "1") {
      console.log(`  [sig ${rows.length}] "${canonical.title.slice(0, 50)}" canonical=${canonical.id.slice(0,8)} (${canonical.source}); dropping ${dups.map(d => d.source).join(",")}`);
    }
    plan.push({ canonicalId: canonical.id, dupIds: dups.map(d => d.id) });
  }

  assertUnderGate("events", dupRows, events.length);

  for (const { canonicalId, dupIds } of plan) {
    const moved = await repointInteractions("event", dupIds, canonicalId);
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
    `select=id,name,lat,lng,public_interest_rank,city,created_at,photo_url,website,address,description,events_url&order=created_at.asc`
  );
  console.log(`Loaded ${venues.length} venues`);

  // Pre-compute event counts per venue lat/lng (within the geo bucket) so we
  // can use it as a tiebreaker.
  const eventCountByGeo = new Map();
  const eventGeoCounts = await pagedSelect(
    "events",
    `select=id,venue_lat,venue_lng&is_active=eq.true&venue_lat=not.is.null`
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

  // Plan first, gate-check the total, THEN write.
  let dupGroups = 0, dupRows = 0, repointed = 0;
  const plan = [];
  for (const [sig, rows] of groups) {
    if (rows.length < 2) continue;
    dupGroups++;
    rows.sort((a, b) => {
      const ap = a.public_interest_rank ?? 0, bp = b.public_interest_rank ?? 0;
      if (ap !== bp) return bp - ap;                       // higher rank wins
      const ac = venueCompleteness(a), bc = venueCompleteness(b);
      if (ac !== bc) return bc - ac;                       // more complete wins
      const ae = eventCountByGeo.get(geoBucket(a.lat, a.lng, 4)) || 0;
      const be = eventCountByGeo.get(geoBucket(b.lat, b.lng, 4)) || 0;
      if (ae !== be) return be - ae;                       // more events wins
      return (a.created_at || "").localeCompare(b.created_at || ""); // oldest wins
    });
    const canonical = rows[0];
    const dups = rows.slice(1);
    dupRows += dups.length;
    if (process.env.VERBOSE === "1") {
      console.log(`  [${rows.length}] "${canonical.name.slice(0, 50)}" canonical=${canonical.id.slice(0,8)}; dropping ${dups.map(d => d.id.slice(0,8)).join(",")}`);
    }
    plan.push({ canonicalId: canonical.id, dupIds: dups.map(d => d.id) });
  }

  assertUnderGate("venues", dupRows, venues.length);

  for (const { canonicalId, dupIds } of plan) {
    const moved = await repointInteractions("venue", dupIds, canonicalId);
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
