/**
 * dedup_events.js — Cross-source event deduplication
 *
 * Same event ingested from multiple sources (Ticketmaster + venue crawl + RSS)
 * with different titles and times. The per-source dedupe_hash never collides
 * across sources, so the carousel feed shows duplicate cards. This script
 * finds (venue, date, fuzzy-title) groups, picks a winner, and marks losers
 * is_active=false + duplicate_of=<winner_id>.
 *
 * Prereq: run supabase/migrations/20260430_event_duplicate_of.sql first.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/dedup_events.js                # dry run
 *   SUPABASE_SERVICE_KEY=ey... WRITE=1 node scripts/dedup_events.js        # apply
 *   SUPABASE_SERVICE_KEY=ey... STATE=MA node scripts/dedup_events.js       # scope by state
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

// ── Helpers ─────────────────────────────────────────────────────────────

const STOPWORDS = new Set([
  "the","a","an","of","at","in","on","and","or","to","for","with","vs","vs.",
  "v","v.","at","versus","presents","presented","by","feat","ft","ft.","feat.",
  "live","tour","2026","2025","2027","show","concert","performance","tickets",
  "playoffs","playoff","round","game","gm","conf","conference","championship","championships",
  "first","second","third","fourth","quarter","quarterfinals","semis","semifinals","finals","final",
  "east","west","north","south","division","series","bowl","cup","series",
  "presented","sponsored","official","tba","tbd",
  "rd","hm","gm","at","sat","sun","mon","tue","wed","thu","fri","saturday","sunday",
  "monday","tuesday","wednesday","thursday","friday",
]);

// Extract significant tokens from a title — used for fuzzy comparison.
function tokenize(title) {
  return (title || "")
    .toLowerCase()
    .replace(/[^\w\s]/g, " ")
    .split(/\s+/)
    .filter((t) => t.length >= 3 && !STOPWORDS.has(t) && !/^\d+$/.test(t));
}

// Normalize venue names so "TD Garden" / "Td Garden" / "TD GARDEN" collide.
function normalizeVenue(name) {
  return (name || "").toLowerCase().replace(/[^\w]/g, "").trim();
}

// Convert a UTC ISO timestamp into a "YYYY-MM-DD" date in ET (the metro
// timezone for the current launch market). Coarse but good enough — events
// at the same venue are nearly always in the venue's TZ, so ET groups
// correctly for MA/RI/NH/CT/NY/PA. Re-evaluate when expanding to PT/CT.
function localDateET(iso) {
  if (!iso) return null;
  const d = new Date(iso);
  if (isNaN(d.getTime())) return null;
  // ET is UTC-5 (EST) or UTC-4 (EDT). Use a rough boundary: Mar-Oct = EDT.
  const month = d.getUTCMonth();
  const offsetHours = (month >= 2 && month <= 10) ? 4 : 5;
  const local = new Date(d.getTime() - offsetHours * 3600 * 1000);
  return local.toISOString().slice(0, 10);
}

// Source priority — higher wins. Ticketmaster has cleaner team-name metadata
// and ticket URLs for ticketed events; venue crawl is preferred for community
// events that TM doesn't list. Tiebreak below covers the rest.
const SOURCE_RANK = { ticketmaster: 100, eventbrite: 80, crawl: 60, rss: 50, manual: 200 };

function pickWinner(a, b) {
  const ra = SOURCE_RANK[a.source] ?? 0;
  const rb = SOURCE_RANK[b.source] ?? 0;
  if (ra !== rb) return ra > rb ? a : b;
  // Same source rank → prefer the one with more populated fields
  const score = (e) =>
    (e.image_url ? 2 : 0) +
    (e.description ? 2 : 0) +
    (e.ticket_url ? 1 : 0) +
    (e.price_min != null ? 1 : 0) +
    (e.time_known ? 1 : 0) +
    (e.confidence_score ?? 0.5);
  return score(a) >= score(b) ? a : b;
}

// Pro sports team names — used to fast-path cross-source playoff/regular-season
// duplicates where titles diverge in everything but the team names. Lowercase,
// last word of each franchise (the part most likely to survive title variants).
const SPORTS_TEAMS = new Set([
  // NBA
  "celtics","76ers","sixers","lakers","warriors","nets","knicks","heat","bulls",
  "raptors","bucks","cavaliers","cavs","pacers","hawks","wizards","hornets",
  "magic","pistons","timberwolves","wolves","thunder","mavericks","mavs","spurs",
  "rockets","grizzlies","pelicans","jazz","nuggets","suns","clippers","kings",
  "trail","blazers",
  // NHL
  "bruins","rangers","penguins","capitals","flyers","devils","islanders","sabres",
  "senators","canadiens","habs","leafs","lightning","panthers","hurricanes",
  "blue","jackets","red","wings","predators","stars","blues","wild","jets",
  "avalanche","kings","ducks","sharks","kraken","oilers","flames","canucks",
  "knights","coyotes","utah","mammoth",
  // MLB
  "redsox","yankees","blue","rays","jays","orioles","tigers","twins","royals",
  "guardians","whitesox","white","sox","astros","rangers","angels","athletics","mariners",
  "mets","phillies","nationals","braves","marlins","brewers","cubs","cardinals",
  "reds","pirates","dodgers","giants","padres","rockies","diamondbacks","backs",
  // NFL
  "patriots","jets","bills","dolphins","steelers","ravens","browns","bengals",
  "colts","texans","jaguars","titans","chiefs","broncos","raiders","chargers",
  "cowboys","eagles","giants","commanders","packers","bears","vikings","lions",
  "saints","falcons","panthers","buccaneers","49ers","seahawks","rams","cardinals",
  // NCAA / regional common names that show up in our crawl
  "huskies","eagles","crimson","terriers","minutemen","wildcats","river","hawks",
]);

function extractTeamSet(title) {
  const tokens = (title || "").toLowerCase()
    .replace(/[^\w\s]/g, " ")
    .split(/\s+/);
  const teams = new Set();
  for (const t of tokens) if (SPORTS_TEAMS.has(t)) teams.add(t);
  return teams;
}

// Should two events at the same venue+date be treated as the same event?
// Returns confidence 0..1 plus reasoning for the dry-run preview.
//
// Two paths:
//   (1) sports: extract team names from each title; match if both events
//       reference the same pair of teams. Cross-source playoff case
//       ("2026 NBA Playoffs: Celtics vs. 76ers" ≡ "East Conf Qtrs:
//       Philadelphia 76ers at Boston Celtics Rd 1 Hm Gm 4").
//   (2) general: strict Jaccard on title tokens. Tight threshold to avoid
//       false positives like "Girls JV Tennis" vs "Girls Varsity Tennis"
//       (shared boilerplate, different events).
function dupConfidence(a, b) {
  const reasons = [];

  // Time skew gate: if both have known times AND start_at differs by >2h,
  // these are separate showings (matinee/evening, doubleheader). Bail.
  let skewMin = null;
  if (a.time_known && b.time_known && a.start_at && b.start_at) {
    skewMin = Math.abs(new Date(a.start_at) - new Date(b.start_at)) / 60000;
    if (skewMin > 120) {
      return { confidence: 0, reasons: [`time skew ${skewMin.toFixed(0)}min — separate showings`] };
    }
  }

  // Sports fast-path
  const teamsA = extractTeamSet(a.title);
  const teamsB = extractTeamSet(b.title);
  if (teamsA.size >= 2 && teamsB.size >= 2) {
    const sharedTeams = [...teamsA].filter((t) => teamsB.has(t));
    if (sharedTeams.length >= 2) {
      reasons.push(`teams: ${sharedTeams.join(",")}`);
      if (skewMin != null) reasons.push(`time skew ${skewMin.toFixed(0)}min`);
      return { confidence: 0.95, reasons };
    }
  }

  // General path: strict Jaccard
  const ta = new Set(tokenize(a.title));
  const tb = new Set(tokenize(b.title));
  if (ta.size < 2 || tb.size < 2) {
    return { confidence: 0, reasons: ["too few significant tokens"] };
  }
  const intersection = [...ta].filter((t) => tb.has(t));
  const union = new Set([...ta, ...tb]);
  const jaccard = intersection.length / union.size;
  reasons.push(`jaccard ${(jaccard * 100).toFixed(0)}% (${intersection.length}/${union.size})`);
  if (intersection.length) reasons.push(`shared: ${intersection.slice(0, 5).join(",")}`);

  // Require Jaccard ≥ 0.85 AND ≥ 3 shared significant tokens. Anything looser
  // catches "Mother's Day Brunch at Davio's" vs "Mother's Day Brunch at Bianca"
  // where 3 of 4 tokens overlap but the events are clearly different.
  let confidence = 0;
  if (jaccard >= 0.95 && intersection.length >= 2) confidence = 0.95;
  else if (jaccard >= 0.85 && intersection.length >= 3) confidence = 0.85;

  // Time skew penalty: if known and ≥30min, downgrade. Two ballet classes at
  // 7:30 / 8:30 with the same name + ages-only diff would fail this gate.
  if (skewMin != null && skewMin >= 30 && confidence > 0) {
    reasons.push(`time skew ${skewMin.toFixed(0)}min — penalty`);
    confidence = Math.max(0, confidence - 0.4);
  }

  return { confidence, reasons };
}

// ── Fetch ───────────────────────────────────────────────────────────────

async function fetchAllUpcoming() {
  const today = new Date().toISOString().slice(0, 10);
  const all = [];
  let offset = 0;
  const limit = 1000;
  while (true) {
    let url =
      `${SUPABASE_URL}/rest/v1/events` +
      `?select=id,title,category,start_at,time_known,venue_name,venue_state,source,image_url,description,ticket_url,price_min,confidence_score,is_active` +
      `&is_active=eq.true` +
      `&start_at=gte.${today}` +
      `&order=id.asc` +
      `&offset=${offset}&limit=${limit}`;
    if (STATE) url += `&venue_state=eq.${STATE}`;
    const res = await fetch(url, { headers });
    if (!res.ok) throw new Error(`fetch: ${res.status} ${await res.text()}`);
    const rows = await res.json();
    all.push(...rows);
    process.stdout.write(`  fetched ${all.length}\r`);
    if (rows.length < limit) break;
    offset += limit;
  }
  console.log("");
  return all;
}

// ── Main ────────────────────────────────────────────────────────────────

async function main() {
  console.log(`Mode: ${WRITE ? "WRITE" : "DRY RUN"}${STATE ? ` (STATE=${STATE})` : ""}`);

  const events = await fetchAllUpcoming();
  console.log(`Loaded ${events.length} active upcoming events`);

  // Group by (normalized venue, local ET date)
  const groups = new Map();
  for (const e of events) {
    if (!e.venue_name || !e.start_at) continue;
    const key = `${normalizeVenue(e.venue_name)}::${localDateET(e.start_at)}`;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(e);
  }

  // Find candidate dup pairs in each group
  const pairs = []; // { winner, loser, confidence, reasons }
  let groupsWithDups = 0;
  for (const [, list] of groups) {
    if (list.length < 2) continue;
    // Compare all pairs
    const claimed = new Set(); // ids already chosen as a loser
    let groupHadDup = false;
    for (let i = 0; i < list.length; i++) {
      if (claimed.has(list[i].id)) continue;
      for (let j = i + 1; j < list.length; j++) {
        if (claimed.has(list[j].id)) continue;
        const { confidence, reasons } = dupConfidence(list[i], list[j]);
        if (confidence < 0.75) continue;
        const winner = pickWinner(list[i], list[j]);
        const loser = winner === list[i] ? list[j] : list[i];
        pairs.push({ winner, loser, confidence, reasons });
        claimed.add(loser.id);
        groupHadDup = true;
      }
    }
    if (groupHadDup) groupsWithDups++;
  }

  console.log(`Groups with duplicates: ${groupsWithDups}`);
  console.log(`Duplicate pairs found: ${pairs.length}`);
  console.log("");

  // Sort by confidence desc for the dry-run preview
  pairs.sort((a, b) => b.confidence - a.confidence);

  const sample = pairs.slice(0, 30);
  for (const p of sample) {
    console.log(`[${(p.confidence * 100).toFixed(0)}%] ${p.winner.venue_name} · ${localDateET(p.winner.start_at)}`);
    console.log(`  KEEP   [${p.winner.source.padEnd(13)}] ${p.winner.title}`);
    console.log(`         ${p.winner.start_at} time_known=${p.winner.time_known}`);
    console.log(`  DROP   [${p.loser.source.padEnd(13)}] ${p.loser.title}`);
    console.log(`         ${p.loser.start_at} time_known=${p.loser.time_known}`);
    console.log(`         ${p.reasons.join(" · ")}`);
    console.log("");
  }
  if (pairs.length > sample.length) console.log(`  ... and ${pairs.length - sample.length} more`);

  if (!WRITE) {
    console.log("\nDRY RUN — pass WRITE=1 to apply (sets is_active=false + duplicate_of on losers).");
    return;
  }

  if (pairs.length === 0) return;

  // Apply: PATCH each loser. Group by winner_id so we can batch in.()
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
  console.log(`Done. Marked ${updated} duplicates.`);
}

main().catch((e) => { console.error(e); process.exit(1); });
