/**
 * dedupe_venues_fuzzy.js — Alias/acronym-aware venue de-duplication.
 *
 * The scheduled `dedupe_pass.js` Phase B is deliberately CONSERVATIVE: it only
 * merges venues whose normalized names are string-equal AND whose coordinates
 * fall in the same ~11m geo grid cell. That never false-merges, but it is blind
 * to the most common real-world duplicate: the SAME venue ingested under a
 * different name and a slightly different geocode. Examples we've seen in prod:
 *
 *   "ICA Boston"         vs  "Institute of Contemporary Art Boston"   (acronym)
 *   "The Wilbur"        vs  "Wilbur Theatre"                          (containment)
 *   "MFA"               vs  "Museum of Fine Arts"                     (acronym)
 *
 * These hash differently in the conservative pass, so they survive as two venue
 * rows — and because events link to venues by the `venue_name` STRING (there is
 * no venue_id FK; see backfill_event_images.js), every event under each spelling
 * stays attached to its own row, which is why you ALSO see the same event twice.
 *
 * This pass is fuzzier and therefore RISKIER, so it is dry-run-first and writes
 * a rollback manifest. It:
 *
 *   1. Spatially clusters venues (bucket grid + 8 neighbors) within MAX_METERS.
 *   2. Within a cluster, links two venues if their names match by ANY of:
 *        - token-set Jaccard >= JACCARD (default 0.6)
 *        - token containment (one name's significant tokens ⊆ the other's)
 *        - acronym match (residual tokens of one == initials of the other's
 *          residual, after removing tokens common to both — so "ICA Boston"
 *          links to "Institute of Contemporary Art Boston").
 *   3. Union-finds links into clusters, picks a canonical (rank → completeness
 *      → event count → oldest), and for every duplicate venue:
 *        - rewrites its events' venue_name / venue_lat / venue_lng to canonical,
 *          so the now-colocated duplicate EVENTS collapse on the next event pass
 *          (or immediately, if RUN_EVENT_DEDUPE were chained — not done here);
 *        - repoints community_interactions (venue) to canonical;
 *        - soft-deletes the duplicate venue (is_active=false).
 *
 * Everything is reversible from the manifest written to scripts/.manifests/.
 *
 * Usage:
 *   # dry run (default) — prints every cluster it WOULD merge, writes nothing
 *   SUPABASE_SERVICE_KEY=ey... node scripts/dedupe_venues_fuzzy.js
 *
 *   # apply — rewrites events, soft-deletes dup venues, writes rollback manifest
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 node scripts/dedupe_venues_fuzzy.js
 *
 *   # roll back a previous run
 *   SUPABASE_SERVICE_KEY=ey... REVERT=scripts/.manifests/fuzzy_venues_<ts>.json \
 *     node scripts/dedupe_venues_fuzzy.js
 *
 * Tunables (env): MAX_METERS=150  JACCARD=0.6  MAX_CLUSTER=6  VERBOSE=1
 */

const fs = require("fs");
const path = require("path");
const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

const WRITE = process.env.WRITE === "1";
const REVERT = process.env.REVERT || "";
const MAX_METERS = parseFloat(process.env.MAX_METERS || "150");
// 0.70 (not 0.60) so prefix-sibling venues that share most tokens but differ in
// one distinguishing word do NOT merge: "Boch Center - Wang Theatre" vs
// "... Shubert Theatre", "Penn State School of Theatre" vs "... of Music",
// "Lorikeet Exhibit & Feeding Area" vs "Giraffe ..." all sit at exactly 0.60.
const JACCARD = parseFloat(process.env.JACCARD || "0.7");
// Refuse to auto-merge an implausibly large cluster — almost always a matching
// bug (e.g. a generic name like "City Hall" colliding across a dense block).
const MAX_CLUSTER = parseInt(process.env.MAX_CLUSTER || "6", 10);
const VERBOSE = process.env.VERBOSE === "1";
const PAGE = parseInt(process.env.PAGE || "1000", 10);

let SERVICE_KEY;

function headers(extra = {}) {
  return {
    apikey: SERVICE_KEY,
    Authorization: `Bearer ${SERVICE_KEY}`,
    "Content-Type": "application/json",
    ...extra,
  };
}

// ── Name normalization & tokenization ─────────────────────────────────────

// Words that carry no disambiguating signal for a venue name. We DON'T strip
// venue-type words here (theatre, museum, …) the way the conservative pass
// does, because containment matching wants to compare full token sets; instead
// type words simply tend to be the non-shared token and get handled by the
// containment / acronym rules.
const STOPWORDS = new Set(["the", "a", "an", "of", "and", "at", "on", "for", "to", "&"]);

function tokens(name) {
  return (name || "")
    .toLowerCase()
    .replace(/[^a-z0-9 ]/g, " ")
    .split(/\s+/)
    .filter((t) => t && !STOPWORDS.has(t));
}

function jaccard(a, b) {
  const A = new Set(a), B = new Set(b);
  if (A.size === 0 || B.size === 0) return 0;
  let inter = 0;
  for (const t of A) if (B.has(t)) inter++;
  return inter / (A.size + B.size - inter);
}

function subset(small, big) {
  const B = new Set(big);
  return small.length > 0 && small.every((t) => B.has(t));
}

// "Institute of Contemporary Art Boston" ↔ "ICA Boston":
// remove tokens common to both, then check if one side collapsed to a single
// token equal to the initials of the other side's >=2 residual tokens.
function acronymMatch(a, b) {
  const common = new Set(a.filter((t) => b.includes(t)));
  const ra = a.filter((t) => !common.has(t));
  const rb = b.filter((t) => !common.has(t));
  const test = (short, long) => {
    if (short.length !== 1 || long.length < 2) return false;
    const acro = long.map((t) => t[0]).join("");
    return short[0] === acro;
  };
  return test(ra, rb) || test(rb, ra);
}

// A connector in the LONGER name almost always means it's a distinct sub-venue
// or a nearby spot, not an alias: "Central Rock Gym - Harvard Square" (a gym AT
// the square), "Skillcade ... Near PPL Center", "Lake Erie Arboretum at Frontier
// Park". So we refuse a containment match when the longer name carries one of
// these. We deliberately do NOT treat "&" as a connector ("Yawgoo Valley Ski
// Area & Water Park" is the same place). Bare substrings with no connector still
// merge ("the wilbur" ⊆ "wilbur theatre", "Paul Revere House" ⊆ "The Paul Revere
// House").
const CONNECTOR = /\s-\s|\s–\s|\s—\s|\s\|\s|\bat\b|\bnear\b|\binside\b|\bpresented by\b|\(/i;

function nameMatch(a, b) {
  const ta = tokens(a), tb = tokens(b);
  if (ta.length === 0 || tb.length === 0) return null;
  if (jaccard(ta, tb) >= JACCARD) return "jaccard";
  if (subset(ta, tb) || subset(tb, ta)) {
    // The longer string is the one with more tokens.
    const longer = ta.length >= tb.length ? a : b;
    if (CONNECTOR.test(longer)) return null;
    return "containment";
  }
  if (acronymMatch(ta, tb)) return "acronym";
  return null;
}

// ── Geo ───────────────────────────────────────────────────────────────────

function metersBetween(lat1, lng1, lat2, lng2) {
  const R = 6371000;
  const toRad = (d) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const s =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}

// Bucket size ~165m at mid-latitudes; we scan a bucket + its 8 neighbors so any
// pair within MAX_METERS (<=165m) is guaranteed to be co-considered.
const BUCKET = 0.0015;
function bucketKey(lat, lng) {
  return `${Math.floor(lat / BUCKET)}|${Math.floor(lng / BUCKET)}`;
}
function neighborKeys(lat, lng) {
  const bx = Math.floor(lat / BUCKET), by = Math.floor(lng / BUCKET);
  const keys = [];
  for (let dx = -1; dx <= 1; dx++)
    for (let dy = -1; dy <= 1; dy++) keys.push(`${bx + dx}|${by + dy}`);
  return keys;
}

// ── Canonical scoring (mirrors dedupe_pass.js Phase B) ─────────────────────

function venueCompleteness(v) {
  return (
    (v.photo_url ? 1 : 0) +
    (v.website ? 1 : 0) +
    (v.address ? 1 : 0) +
    (v.description ? 1 : 0) +
    (v.events_url ? 1 : 0)
  );
}

// ── PostgREST helpers ──────────────────────────────────────────────────────

async function pagedSelect(table, query) {
  const baseQuery = query.replace(/&?order=[^&]*/g, "");
  const out = [];
  let after = "00000000-0000-0000-0000-000000000000";
  for (;;) {
    const url =
      `${SUPABASE_URL}/rest/v1/${table}?${baseQuery}` +
      `&id=gt.${after}&order=id.asc&limit=${PAGE}`;
    const res = await fetch(url, { headers: headers() });
    if (!res.ok) throw new Error(`Select ${table}: ${res.status} ${await res.text()}`);
    const rows = await res.json();
    out.push(...rows);
    if (rows.length < PAGE) break;
    after = rows[rows.length - 1].id;
  }
  return out;
}

// Active events attached to a venue by its name string. Returns id + current
// venue fields so the manifest can restore them on revert.
async function eventsForVenueName(name) {
  const url =
    `${SUPABASE_URL}/rest/v1/events` +
    `?select=id,venue_name,venue_lat,venue_lng` +
    `&venue_name=eq.${encodeURIComponent(name)}&is_active=eq.true`;
  const res = await fetch(url, { headers: headers() });
  if (!res.ok) throw new Error(`events for "${name}": ${res.status} ${await res.text()}`);
  return res.json();
}

async function patchEvents(ids, body) {
  const idList = ids.map((id) => `"${id}"`).join(",");
  const url = `${SUPABASE_URL}/rest/v1/events?id=in.(${idList})`;
  const res = await fetch(url, {
    method: "PATCH",
    headers: headers({ Prefer: "return=minimal" }),
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`patch events: ${res.status} ${await res.text()}`);
}

async function patchVenue(id, body) {
  const url = `${SUPABASE_URL}/rest/v1/venues?id=eq.${id}`;
  const res = await fetch(url, {
    method: "PATCH",
    headers: headers({ Prefer: "return=minimal" }),
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`patch venue ${id}: ${res.status} ${await res.text()}`);
}

async function repointInteractions(fromIds, toId) {
  if (fromIds.length === 0) return 0;
  const idList = fromIds.map((id) => `"${id}"`).join(",");
  const url =
    `${SUPABASE_URL}/rest/v1/community_interactions` +
    `?item_type=eq.venue&item_id=in.(${idList})`;
  const res = await fetch(url, {
    method: "PATCH",
    headers: headers({ Prefer: "return=representation" }),
    body: JSON.stringify({ item_id: toId }),
  });
  if (!res.ok) {
    console.warn(`  (interactions repoint failed: ${res.status})`);
    return 0;
  }
  return (await res.json()).length;
}

// ── Union-find ──────────────────────────────────────────────────────────────

function makeUF(n) {
  const p = Array.from({ length: n }, (_, i) => i);
  const find = (x) => (p[x] === x ? x : (p[x] = find(p[x])));
  const union = (a, b) => { p[find(a)] = find(b); };
  return { find, union };
}

// ── Clustering ──────────────────────────────────────────────────────────────

function buildClusters(venues) {
  // Index venues into spatial buckets.
  const buckets = new Map();
  venues.forEach((v, i) => {
    if (v.lat == null || v.lng == null) return;
    const k = bucketKey(v.lat, v.lng);
    if (!buckets.has(k)) buckets.set(k, []);
    buckets.get(k).push(i);
  });

  const uf = makeUF(venues.length);
  const links = []; // {i, j, why, meters} for reporting
  const seen = new Set();

  venues.forEach((v, i) => {
    if (v.lat == null || v.lng == null) return;
    const candidates = new Set();
    for (const k of neighborKeys(v.lat, v.lng)) {
      for (const j of buckets.get(k) || []) if (j > i) candidates.add(j);
    }
    for (const j of candidates) {
      const pairKey = `${i}|${j}`;
      if (seen.has(pairKey)) continue;
      seen.add(pairKey);
      const w = venues[j];
      const meters = metersBetween(v.lat, v.lng, w.lat, w.lng);
      if (meters > MAX_METERS) continue;
      const why = nameMatch(v.name, w.name);
      if (!why) continue;
      uf.union(i, j);
      links.push({ i, j, why, meters });
    }
  });

  // Gather clusters of size >= 2.
  const byRoot = new Map();
  venues.forEach((_, i) => {
    const r = uf.find(i);
    if (!byRoot.has(r)) byRoot.set(r, []);
    byRoot.get(r).push(i);
  });
  const clusters = [];
  for (const idxs of byRoot.values()) {
    if (idxs.length < 2) continue;
    clusters.push(idxs.map((i) => venues[i]));
  }
  return { clusters, links };
}

function pickCanonical(cluster, eventCountByName) {
  return [...cluster].sort((a, b) => {
    const ap = a.public_interest_rank ?? 0, bp = b.public_interest_rank ?? 0;
    if (ap !== bp) return bp - ap;
    const ac = venueCompleteness(a), bc = venueCompleteness(b);
    if (ac !== bc) return bc - ac;
    const ae = eventCountByName.get(a.name) || 0, be = eventCountByName.get(b.name) || 0;
    if (ae !== be) return be - ae;
    return (a.created_at || "").localeCompare(b.created_at || "");
  })[0];
}

// ── Main passes ─────────────────────────────────────────────────────────────

async function run() {
  const venues = await pagedSelect(
    "venues",
    "select=id,name,lat,lng,public_interest_rank,city,created_at,photo_url,website,address,description,events_url"
  );
  console.log(`Loaded ${venues.length} venues`);

  const { clusters, links } = buildClusters(venues);
  console.log(`Found ${clusters.length} fuzzy cluster(s) from ${links.length} link(s).`);

  // Event counts per venue_name, for canonical tiebreak + impact reporting.
  const eventCountByName = new Map();
  const ev = await pagedSelect(
    "events",
    "select=id,venue_name&is_active=eq.true&venue_name=not.is.null"
  );
  for (const e of ev) {
    const n = e.venue_name;
    eventCountByName.set(n, (eventCountByName.get(n) || 0) + 1);
  }

  const merges = []; // {canonical, dups:[...]}
  let skippedBig = 0;
  for (const cluster of clusters) {
    if (cluster.length > MAX_CLUSTER) {
      skippedBig++;
      console.warn(
        `  SKIP oversized cluster (${cluster.length}): ${cluster.map((c) => `"${c.name}"`).join(", ")}`
      );
      continue;
    }
    const canonical = pickCanonical(cluster, eventCountByName);
    const dups = cluster.filter((v) => v.id !== canonical.id);
    merges.push({ canonical, dups });
  }

  // Report.
  let totalDupVenues = 0, totalRehome = 0;
  for (const { canonical, dups } of merges) {
    totalDupVenues += dups.length;
    const dupEventCounts = dups.map((d) => eventCountByName.get(d.name) || 0);
    const rehome = dupEventCounts.reduce((a, b) => a + b, 0);
    totalRehome += rehome;
    console.log(
      `\n  KEEP  "${canonical.name}"  (rank=${canonical.public_interest_rank ?? "—"}, ` +
        `complete=${venueCompleteness(canonical)}, events=${eventCountByName.get(canonical.name) || 0})`
    );
    for (const d of dups) {
      const m = (d.lat != null && canonical.lat != null)
        ? metersBetween(canonical.lat, canonical.lng, d.lat, d.lng).toFixed(0)
        : "?";
      const why = nameMatch(canonical.name, d.name) || "transitive";
      console.log(
        `    drop "${d.name}"  (${m}m, ${why}, events=${eventCountByName.get(d.name) || 0})`
      );
    }
  }

  console.log(
    `\nSummary: ${merges.length} merge group(s), ${totalDupVenues} duplicate venue(s) ` +
      `to soft-delete, ~${totalRehome} event(s) to rehome.` +
      (skippedBig ? ` (${skippedBig} oversized cluster(s) skipped.)` : "")
  );

  if (!WRITE) {
    console.log("\nDRY RUN — no writes. Re-run with WRITE=1 to apply.");
    return;
  }

  // Build + persist rollback manifest BEFORE writing.
  const manifest = { ts: Date.now(), events: [], venues: [], interactions: [] };
  for (const { canonical, dups } of merges) {
    for (const d of dups) {
      const evs = await eventsForVenueName(d.name);
      for (const e of evs) {
        manifest.events.push({
          id: e.id,
          old: { venue_name: e.venue_name, venue_lat: e.venue_lat, venue_lng: e.venue_lng },
          new: { venue_name: canonical.name, venue_lat: canonical.lat, venue_lng: canonical.lng },
        });
      }
      manifest.venues.push({ id: d.id, canonicalId: canonical.id, name: d.name });
    }
  }
  const dir = path.join(__dirname, ".manifests");
  fs.mkdirSync(dir, { recursive: true });
  const manifestPath = path.join(dir, `fuzzy_venues_${manifest.ts}.json`);
  fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2));
  console.log(`\nRollback manifest: ${manifestPath}`);

  // Apply.
  let rehomed = 0, repointed = 0, dropped = 0;
  for (const { canonical, dups } of merges) {
    for (const d of dups) {
      const evs = await eventsForVenueName(d.name);
      if (evs.length) {
        await patchEvents(evs.map((e) => e.id), {
          venue_name: canonical.name,
          venue_lat: canonical.lat,
          venue_lng: canonical.lng,
        });
        rehomed += evs.length;
      }
      repointed += await repointInteractions([d.id], canonical.id);
      try {
        await patchVenue(d.id, { is_active: false });
      } catch (err) {
        if (/is_active/.test(err.message)) await patchVenue(d.id, { status: "duplicate" });
        else throw err;
      }
      dropped++;
    }
  }
  console.log(
    `Done. Rehomed ${rehomed} event(s), repointed ${repointed} interaction(s), ` +
      `soft-deleted ${dropped} venue(s). Revert with REVERT=${manifestPath}`
  );
}

async function revert() {
  const manifest = JSON.parse(fs.readFileSync(REVERT, "utf8"));
  console.log(
    `Reverting ${manifest.events.length} event(s) and ${manifest.venues.length} venue(s) from ${REVERT}`
  );
  for (const e of manifest.events) {
    await patchEvents([e.id], e.old);
  }
  for (const v of manifest.venues) {
    try {
      await patchVenue(v.id, { is_active: true });
    } catch (err) {
      if (/is_active/.test(err.message)) await patchVenue(v.id, { status: "active" });
      else throw err;
    }
  }
  console.log("Revert complete. (community_interactions repoint is NOT reversed.)");
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
