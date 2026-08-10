/**
 * classify_events.js — AI-powered event reclassification and tagging
 *
 * Uses the Anthropic Message Batches API for scalable, async classification.
 * Pulls all active events from Supabase, submits them to Claude as a batch,
 * polls for completion, then writes corrections back.
 *
 * Only classifies events that haven't been classified yet (no sub_subcategory
 * or tags), unless FORCE=1 is set.
 *
 * The original category/subcategory are preserved in
 * `original_category` and `original_subcategory` columns so you can revert.
 *
 * Usage:
 *   node scripts/classify_events.js
 *
 * Revert (undo all AI classifications):
 *   node scripts/classify_events.js --revert
 *
 * Optional:
 *   DRY_RUN=1             — preview batch submission without sending
 *   BATCH_SIZE=50         — events per Claude request within the batch (default: 50)
 *   FORCE=1               — re-classify already-classified events
 *   POLL_INTERVAL=60      — seconds between status checks (default: 60)
 *   PREFILTER_ONLY=1      — only run pattern-based auto-exclusion, skip Claude
 *   TEAM_NAME_BACKFILL=1  — only backfill team_name on sports events where it's null
 *   ONLY_CATEGORY=other   — scope the run to events with category=<value> AND
 *                           is_active=true, ignoring the default unclassified-only
 *                           filter so already-tagged rows still get reclassified.
 *                           Used to clean up legacy invalid categories.
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");
const { TAG_VOCAB_VERSION, TAG_MIN, TAG_MAX, TAG_FACETS, ALL_TAGS } = require("./taste/tag_vocabulary");
const MODEL = "claude-haiku-4-5-20251001";
const API_BASE = "https://api.anthropic.com/v1";

let ANTHROPIC_API_KEY, SUPABASE_SERVICE_KEY;
const BATCH_SIZE = parseInt(process.env.BATCH_SIZE || "50", 10);
const DRY_RUN = process.env.DRY_RUN === "1";
const FORCE = process.env.FORCE === "1";
const POLL_INTERVAL = parseInt(process.env.POLL_INTERVAL || "60", 10) * 1000;

// Write throttle — the result-write loop must NEVER saturate the Nano prod DB's
// IO again (an unthrottled concurrency-10 / no-delay write took prod down on
// 2026-07-20). Low concurrency + a pause between waves + an adaptive health
// probe that backs off when the DB warms. Safe defaults; tunable via env.
const WRITE_CONCURRENCY = parseInt(process.env.WRITE_CONCURRENCY || "5", 10);
let WRITE_WAVE_DELAY_MS = parseInt(process.env.WRITE_WAVE_DELAY_MS || "300", 10);
const WRITE_PROBE_SLOW_MS = 1500;
const WRITE_PROBE_HARD_MS = 8000;
const WRITE_MAX_DELAY_MS = 5000;
let _writeWaveCount = 0;
const _sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Cheap DB health probe; grows the inter-wave delay when the DB is warm and
// pauses when it's unreachable, so a big backfill can't repeat the outage.
async function probeDbHealth() {
  const start = Date.now();
  try {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), WRITE_PROBE_HARD_MS);
    const res = await fetch(`${SUPABASE_URL}/rest/v1/app_config?limit=1&select=key`, {
      headers: { apikey: SUPABASE_SERVICE_KEY, Authorization: `Bearer ${SUPABASE_SERVICE_KEY}` },
      signal: ctrl.signal,
    });
    clearTimeout(t);
    return { ok: res.ok, ms: Date.now() - start };
  } catch {
    return { ok: false, ms: Date.now() - start };
  }
}

const REVERT_MODE = process.argv.includes("--revert");
// When set, skip the Claude batch and only apply the pattern-based auto-exclusion
// pass. Useful for backfilling `no-public-interest` across already-classified
// events without burning API calls or disturbing prior classifications.
const PREFILTER_ONLY = process.env.PREFILTER_ONLY === "1";
// When set, only backfill `team_name` on sports events that still have it null.
// Skips auto-exclusion + reclassification; writes only team_name (not category
// / subcategory / tags). Used to hydrate the server-side dynamic team carousels.
const TEAM_NAME_BACKFILL = process.env.TEAM_NAME_BACKFILL === "1";
// When set, scope the fetch to events with category=<value> AND is_active=true,
// and override the default "skip already-classified" filter so rows that already
// have sub_subcategory/tags still go through Claude. Used to repair events
// stuck at invalid parent categories like "other".
const ONLY_CATEGORY = process.env.ONLY_CATEGORY || null;
// Scope a forced reclassification to a single existing subcategory (e.g.
// ONLY_SUBCATEGORY=other to re-home the legacy 'other' bucket after it was
// removed from the vocabulary). Implies FORCE-style "re-send already-classified".
const ONLY_SUBCATEGORY = process.env.ONLY_SUBCATEGORY || null;
// Cap the number of events sent to Claude in one run. Use for cheap eyeball
// test passes (e.g. MAX_EVENTS=30 FORCE=1) before a full backfill. 0 = no cap.
const MAX_EVENTS = parseInt(process.env.MAX_EVENTS || "0", 10);
// TAGS_ONLY: enrich rows with the new tag vocabulary + performers/brands WITHOUT
// touching category/subcategory/sub_subcategory/team_name. Safe for a prod
// backfill — the working taxonomy is preserved. Targets rows not yet on the
// current TAG_VOCAB_VERSION. The fetch already scopes to active + upcoming events.
const TAGS_ONLY = process.env.TAGS_ONLY === "1";
// Events deliberately tagged subcategory="no-public-interest" are hidden from
// every user-facing surface (carousels filter them out). They carry subcategory
// but NOT sub_subcategory/tags, so the "unclassified" filter below would
// otherwise pick them up and Claude could flip them back to visible — silently
// undoing manual suppression work. Protect them by default in EVERY mode
// (including FORCE/ONLY_CATEGORY). Set RECLASSIFY_EXCLUDED=1 only when you
// genuinely want to re-evaluate suppressed events.
const RECLASSIFY_EXCLUDED = process.env.RECLASSIFY_EXCLUDED === "1";

// ── Rule-based auto-exclusion ─────────────────────────────────────────
//
// Events matching any of these patterns get immediately tagged as
// subcategory="no-public-interest" without a Claude round-trip. Each regex
// is anchored with word boundaries to keep false-positive rates low.
// If these ever start catching legit events, loosen the regex — don't remove.

const AUTO_EXCLUDE_PATTERNS = [
  // Recovery / support-group meetings (title OR description match)
  { label: "recovery-meeting", re: /\b(alcoholics anonymous|narcotics anonymous|overeaters anonymous|gamblers anonymous|aa meeting|aa group|na meeting|a\.a\.|n\.a\.|al-anon|alateen|nar-anon|12.?step|twelve.?step|smart recovery|refuge recovery|celebrate recovery|sober support|sobriety meeting|sobriety group|recovery group|recovery meeting|codependents anonymous|co-dependents anonymous|coda\b)\b/i },
  // Bare-acronym titles: "AA - April 21", "NA: 7pm", "AL-Anon Weekly", etc.
  // Anchored at start-of-title + dash/colon/space (or inside venue text with
  // obvious meeting context) so this doesn't catch AA batteries or AAA baseball.
  // Negative lookahead `(?!a)` prevents AAA matching the bare-AA branch.
  { label: "recovery-meeting", re: /^(aa|na|oa|ga|al.?anon|alateen|nar.?anon|coda)(?!a)\b\s*[-–—:|]/i },
  // AA/NA followed WITHIN 30 chars by meeting-vocab — catches
  // "AA Men's Meeting", "NA Women's Group", "AA Big Book Study", etc.
  { label: "recovery-meeting", re: /^(aa|na)(?!a)\b[\s\S]{0,30}\b(meeting|group|open|closed|speaker|beginners|big book|step study|tradition|anniversary|share)\b/i },
  { label: "recovery-meeting", re: /\b(aa|na)(?!a)\b[\s\S]{0,30}\b(meeting|group|open|closed|speaker|beginners|big book|step study)\b[\s\S]{0,60}\b(tuesday|wednesday|monday|thursday|friday|saturday|sunday|weekly|daily|every)\b/i },
  // Weekday/cadence + bare acronym: "Tuesday AA", "Weekly NA"
  { label: "recovery-meeting", re: /\b(tuesday|wednesday|monday|thursday|friday|saturday|sunday|daily|weekly|morning|evening|noon|lunchtime)\s+(aa|na|oa|ga)\b(?!a)/i },
  // Youth / scholastic sports — catches "Varsity Baseball", "JV Lacrosse",
  // "Middle School Track", "MS Girls Tennis", "Girls HS Volleyball", etc.
  // MS/HS are the most common abbreviations in crawler data.
  { label: "youth-sports", re: /\b(jv|junior varsity|varsity|freshman|sub-varsity|middle school|high school|elementary|peewee|pee-wee|t-?ball|little league|cal ripken|pop warner|aau|\bu\s?\d{1,2}\b|\bms\b|\bhs\b)\b[\s\S]{0,40}\b(baseball|softball|basketball|soccer|football|hockey|lacrosse|volleyball|tennis|track|wrestling|golf|rugby|crew|rowing|field hockey|swim|swimming|cross.?country|cheer|dance team)\b/i },
  { label: "youth-sports", re: /\b(baseball|softball|basketball|soccer|football|hockey|lacrosse|volleyball|tennis|track|wrestling|golf|rugby|field hockey)\b[\s\S]{0,40}\b(jv|junior varsity|varsity|freshman|middle school|high school|elementary|peewee|pee-wee|little league|\bms\b|\bhs\b)\b/i },
  // "Girls MS" / "Boys HS" / "Co-Ed JV" — gender prefix immediately followed by scholastic qualifier
  { label: "youth-sports", re: /\b(girls|boys|co[-\s]?ed)\s+(ms|hs|jv|varsity|junior varsity|middle school|high school)\b/i },
  // Sport + "@ <School>" pattern — HS/MS sports schedules commonly title like "Varsity Tennis @ Tabor"
  { label: "youth-sports", re: /\b(baseball|softball|basketball|soccer|football|hockey|lacrosse|volleyball|tennis|track|wrestling|golf|rugby|field hockey)\b[\s\S]{0,15}@\s+[A-Z][a-z]+/i },
  // NCAA varsity at non-landmark schools — "Brown University Softball vs Holy
  // Cross", "Northeastern Crew at Princeton", etc. These are low-public-interest
  // college games that the user specifically asked to hide.
  { label: "youth-sports", re: /\b\w+\s+(university|college)\s+(baseball|softball|basketball|soccer|football|hockey|lacrosse|volleyball|tennis|wrestling|rugby|field hockey|rowing|crew)\b[\s\S]{0,20}\b(vs\.?|@|at)\b/i },
  // Government / civic / HOA committee meetings
  { label: "civic-meeting", re: /\b(school committee|school board|town council|city council|select board|planning board|zoning board|conservation commission|historic district commission|board of health|board of selectmen|abutters meeting|hoa meeting|homeowners? association meeting|condo association meeting)\b/i },
  // Internal / private / trainings
  { label: "private-event", re: /\b(staff training|employee training|internal meeting|members only meeting|private event|private gathering|private party)\b/i },
  // Academic-internal: thesis/dissertation defenses, qualifying exams — these
  // are closed to the public even when listed on a calendar.
  { label: "academic-internal", re: /\b(thesis defense|dissertation defense|phd defense|qualifying exam|oral defense|prospectus defense|comprehensive exam|phd committee|dissertation proposal)\b/i },
  // Student thesis presentations and capstones — student+advisor+committee audiences only.
  { label: "academic-internal", re: /\b(senior thesis|thesis presentation|thesis reading|undergraduate thesis|ma thesis|masters'?\s+thesis|honors thesis|capstone presentation|capstone showcase)\b/i },
  // Informal department lunch seminars — LingLangLunch, Lunch Bunch, Brown Bag, etc.
  // are all named patterns for "grad students + faculty only" reading/research groups.
  { label: "academic-internal", re: /\b(brown[\s-]?bag|brownbag|lunch bunch|ling\s?lang\s?lunch|lunch seminar|dept seminar|department seminar|lab meeting|research group meeting|journal club)\b/i },
  // Department seminar series — "MCBGP Seminar Series", "SEAM Seminar Presents",
  // "Theory Seminar Talk" etc. are all internal dept calendars.
  { label: "academic-internal", re: /\b\w+\s+seminar\s+(series|presents|presenting|talk)\b/i },
  // Spanish / other-language equivalents that also appear on multilingual campuses
  { label: "academic-internal", re: /\b(coloquio|\w+\s+colloquium\s+series)\b/i },
  // Student showcases, undergrad fellow symposia, student-research days
  { label: "academic-internal", re: /\b(student showcase|undergraduate research day|graduate research day|fellowship showcase|alumni showcase|student spotlight|student research symposium)\b/i },
  // Medical / clinical residency rounds — hospital-internal, closed to public
  { label: "academic-internal", re: /\b(grand rounds|morbidity\s+(&|and)\s+mortality|m&m rounds|tumor board|case conference|clinical rounds|medical rounds|resident rounds)\b/i },
  // Prospective-student / recruiting events
  { label: "recruiting", re: /\b(adoch|a day on college hill|accepted students day|admitted students day|prospective students? day|visit day for (prospective|admitted)|open house for (prospective|admitted))\b/i },
  // Info sessions with academic/recruiting context (allows intervening words
  // like "in Africa" between "Study Abroad" and "Information Session").
  { label: "recruiting", re: /\b(study abroad|phd program|master'?s program|mba|graduate school|fellowship|scholarship|admissions|recruiting)\b[\s\S]{0,30}\b(info|information)\s+session\b/i },
  { label: "recruiting", re: /\b(student panel webinar|alumnae?\s+panel|alumna panel|prospective student panel|incoming student panel)\b/i },
  // Office hours / coffee-with-X / campus outreach tables — internal community
  // events, not public programming
  { label: "private-event", re: /\b(office hours|open office hours)\b/i },
  { label: "private-event", re: /\bcoffee\s+(w\/|with)\s+the\s+\w+/i },
  { label: "private-event", re: /\b(coffee chats?|public safety outreach|outreach table|welcome week|move[-\s]in day|move[-\s]out day)\b/i },
  // Campus / workplace trainings — mandatory compliance, not for general audience
  { label: "internal-training", re: /\b(bystander intervention|safezone training|title ix training|active shooter (training|drill)|consent training|sexual harassment training|diversity training|overdose education(\s*(&|and)\s*prevention)?|first aid training|cpr training|narcan training|fentanyl training)\b/i },
  // Prayer / religious services — added midday/noon/daily/weekday variants
  { label: "worship-service", re: /\b(early morning prayer|daily prayer|morning prayer|evening prayer|weekly prayer|prayer group|bible study|worship service|sunday service|mass service|midday mass|noon mass|daily mass|weekday mass|morning mass|evening mass|noon prayer|sunday mass|church service|eucharist)\b/i },
];

// Title-only exclusion: events whose TITLE marks them sold out or cancelled are
// pulled from carousels (no-public-interest is filtered out of carousels /
// explore / yocal). Matched against the TITLE ALONE \u2014 not description or venue \u2014
// so a blurb like "last year's show sold out fast" can't suppress a live,
// on-sale event. We match past-tense "cancelled/canceled" (not the bare verb
// "cancel") so titles like "Cancel Culture: A Comedy Show" don't get caught.
const SOLD_OUT_OR_CANCELED_RE = /\b(sold[\s-]+out|cancell?ed)\b/i;

// Participant-only studio events \u2014 dance/music recitals, dress/tech rehearsals,
// studio picture days. Only the families/performers involved attend, so they're
// hidden from public surfaces. Matched against the TITLE alone (mirrors the
// one-time backfill 2026-06-09). Tuned to NOT catch marquee concert-hall
// recitals ("Yuja Wang in Recital", faculty/alumni/organ recitals, "Recital
// Series") or concert tours literally named "Picture Day Tour".
const PARTICIPANT_ONLY_TITLE = [
  /\b(dress|tech|technical|costume|stage)\s+rehearsal/i,
  /\b(dance|ballet)\s+recital\b/i,
  /\b(annual|spring|summer|winter|fall|june|july|december|holiday|studio|childrens?|children.s)\s+recital\b/i,
  /\brecital\s*(#\s*\d|day|week|weekend|matinee|evening|performance|fee|(19|20)\d{2})\b/i,
  /\b\d+(st|nd|rd|th)\s+annual\s+recital\b/i,
  /\b(19|20)\d{2}\s+(annual\s+)?recital\b/i,
  /\bpicture\s+(day|week)\b(?!\s+tour)/i,
];
// Bare "Recital"/"Rehearsal" is too generic for an all-venue rule (it would catch
// public music recitals), so it only excludes when the VENUE is a dance studio.
const DANCE_VENUE = /\b(dance|ballet)\b/i;
const BARE_RECITAL_OR_REHEARSAL = /\b(recitals?|rehearsals?)\b/i;

function autoExcludeReason(event) {
  const title = event.title || "";
  // Title-only rule runs first and is intentionally narrow (see regex above).
  if (SOLD_OUT_OR_CANCELED_RE.test(title)) return "sold-out-or-canceled";

  if (PARTICIPANT_ONLY_TITLE.some((re) => re.test(title))) return "participant-only";
  if (DANCE_VENUE.test(event.venue_name || "") && BARE_RECITAL_OR_REHEARSAL.test(title))
    return "participant-only";

  const haystack = [title, event.description || "", event.venue_name || ""].join(" \u2014 ");
  for (const pat of AUTO_EXCLUDE_PATTERNS) {
    if (pat.re.test(haystack)) return pat.label;
  }
  return null;
}

// ── Rule-based subcategory reclassification ──────────────────────────
//
// Events inherit their venue's category during crawl (e.g. an event at a
// sports_venue → category/subcategory "sports"). That's wrong when a
// hockey rink hosts a speaker series, a library hosts a yoga class, or
// a museum hosts a concert — and the Claude classifier skips
// already-classified rows, so these stay stuck.
//
// This pass runs on EVERY future event regardless of prior classification
// and overrides subcategory when the title clearly describes a different
// type of event. Deterministic, cheap, runs outside the Claude batch.

const SUBCATEGORY_RECLASSIFY_PATTERNS = [
  // NOTE: the "other" catch-all was removed (2026). Educational/talks,
  // networking, and fundraising/reception events are NO LONGER force-mapped to
  // a bucket here — they are too varied (a charity concert vs a corporate awards
  // gala) for a blunt regex. The LLM classifier routes them by actual activity
  // (talks/lectures → libraries, charity concert → music, etc.), and genuinely
  // private/corporate ones fall to no-public-interest via the auto-exclusion pass.
  // Film screenings → "arts"
  {
    subcategory: "arts",
    re: /\b(film screening|movie night|movie club|screening of|film festival|documentary screening|short film program)\b/i,
  },
  // Book clubs / reading groups → "libraries"
  {
    subcategory: "libraries",
    re: /\b(book club|book discussion|reading group|author talk|author signing|author event|poetry reading|literary salon)\b/i,
  },
  // Kids programming → "for-kids"
  {
    subcategory: "for-kids",
    re: /\b(story ?time|toddler time|kids craft|children'?s (craft|class|workshop|program)|parent\s*(&|and)\s*child|baby ?time|family craft|toddler playtime|little learners|kids yoga|kids cooking)\b/i,
  },
];

function reclassifyReason(event) {
  const hay = [event.title || "", event.description || ""].join(" — ");
  for (const p of SUBCATEGORY_RECLASSIFY_PATTERNS) {
    if (p.re.test(hay) && event.subcategory !== p.subcategory) {
      return p.subcategory;
    }
  }
  return null;
}

async function applyReclassifications(events, skipIds = new Set()) {
  const matches = [];
  for (const e of events) {
    // Don't overwrite events that auto-exclusion already flagged as
    // no-public-interest — otherwise a "Study Abroad Info Session"
    // would flip from no-public-interest back to "other" because
    // the reclassifier's "info session" rule runs after auto-exclusion.
    // `skipIds` covers rows tagged THIS run; the subcategory check below
    // also covers rows tagged on a PRIOR run that don't re-match an
    // auto-exclude pattern this time (their title alone matches a "->other"
    // rule). Without it, the daily prefilter un-hides already-excluded
    // events every run.
    if (skipIds.has(e.id)) continue;
    if (e.subcategory === "no-public-interest") continue;
    const target = reclassifyReason(e);
    if (target) matches.push({ ...e, _newSubcategory: target });
  }
  if (matches.length === 0) {
    console.log("Reclassification: 0 events matched patterns");
    return new Set();
  }

  console.log(`\nReclassification: ${matches.length} events matched patterns`);
  const bySub = {};
  for (const m of matches) {
    const key = `${m.subcategory ?? "(none)"} -> ${m._newSubcategory}`;
    bySub[key] = (bySub[key] || 0) + 1;
  }
  for (const [k, n] of Object.entries(bySub)) console.log(`  ${k}: ${n}`);

  if (DRY_RUN) {
    console.log("DRY RUN — not writing reclassifications\n");
    console.log("  Sample:");
    for (const m of matches.slice(0, 10)) {
      console.log(`    [${m.subcategory} -> ${m._newSubcategory}] "${m.title}" @ ${m.venue_name}`);
    }
    return new Set(matches.map((m) => m.id));
  }

  let index = 0;
  let updated = 0;
  async function worker() {
    while (index < matches.length) {
      const i = index++;
      const m = matches[i];
      const res = await fetch(`${SUPABASE_URL}/rest/v1/events?id=eq.${m.id}`, {
        method: "PATCH",
        headers: {
          apikey: SUPABASE_SERVICE_KEY,
          Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          subcategory: m._newSubcategory,
          // Preserve prior value once — original_subcategory is set by the
          // auto-exclusion pass too, so only write if not already set.
          ...(m.original_subcategory ? {} : { original_subcategory: m.subcategory ?? null }),
        }),
      });
      if (res.ok) updated++;
      await _sleep(WRITE_WAVE_DELAY_MS); // throttle — never saturate Nano IO
    }
  }
  await Promise.all(Array.from({ length: WRITE_CONCURRENCY }, worker));
  console.log(`  updated: ${updated}/${matches.length}\n`);
  return new Set(matches.map((m) => m.id));
}

async function applyAutoExclusions(events) {
  const matches = [];
  for (const e of events) {
    const reason = autoExcludeReason(e);
    if (reason) matches.push({ ...e, _autoExcludeReason: reason });
  }
  if (matches.length === 0) {
    console.log("Auto-exclusion: 0 events matched patterns");
    return new Set();
  }

  console.log(`\nAuto-exclusion: ${matches.length} events matched patterns`);
  const byReason = {};
  for (const m of matches) byReason[m._autoExcludeReason] = (byReason[m._autoExcludeReason] || 0) + 1;
  for (const [reason, n] of Object.entries(byReason)) console.log(`  ${reason}: ${n}`);

  // Safety bound: a sudden spike almost always means an upstream title-format
  // change or a too-greedy pattern, not a real flood of excludable events. Abort
  // the write rather than silently suppress thousands of events from carousels.
  // Inspect with DRY_RUN=1; raise AUTO_EXCLUDE_MAX if the spike is legitimate.
  const maxExclusions = parseInt(process.env.AUTO_EXCLUDE_MAX || "2000", 10);
  if (!DRY_RUN && matches.length > maxExclusions) {
    throw new Error(
      `Auto-exclusion abort: ${matches.length} matches exceeds AUTO_EXCLUDE_MAX=${maxExclusions}. ` +
        `Re-run with DRY_RUN=1 to inspect the matches, or set AUTO_EXCLUDE_MAX higher if expected.`
    );
  }

  if (DRY_RUN) {
    console.log("DRY RUN — not writing auto-exclusions\n");
    console.log("  Sample matches:");
    for (const m of matches.slice(0, 10)) console.log(`    [${m._autoExcludeReason}] "${m.title}"`);
    return new Set(matches.map((m) => m.id));
  }

  // Batch PATCH via per-row calls (PostgREST doesn't support multi-row updates
  // with different values in one request). ~5 parallel is plenty.
  let index = 0;
  let updated = 0;
  async function worker() {
    while (index < matches.length) {
      const i = index++;
      const m = matches[i];
      const res = await fetch(`${SUPABASE_URL}/rest/v1/events?id=eq.${m.id}`, {
        method: "PATCH",
        headers: {
          apikey: SUPABASE_SERVICE_KEY,
          Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          subcategory: "no-public-interest",
          // Preserve an audit trail so we can revert if a pattern is too aggressive.
          original_subcategory: m.subcategory || null,
        }),
      });
      if (res.ok) updated++;
      await _sleep(WRITE_WAVE_DELAY_MS); // throttle — never saturate Nano IO
    }
  }
  await Promise.all(Array.from({ length: WRITE_CONCURRENCY }, worker));
  console.log(`  updated: ${updated}/${matches.length}\n`);
  return new Set(matches.map((m) => m.id));
}

// ── Valid categories and tags ──

const VALID_PARENT_CATEGORIES = ["upcoming", "always_on"];

const VALID_SUBCATEGORIES = [
  "music", "arts", "theatre", "food", "comedy", "sports",
  "museums", "tours", "libraries", "outdoors", "for-kids",
  "no-public-interest",
];

const VALID_SUB_SUBCATEGORIES = [
  // music
  "rock", "pop", "hip-hop", "r&b", "jazz", "blues", "country", "folk",
  "classical", "electronic", "latin", "reggae", "alternative", "metal", "indie",
  "singer-songwriter", "cover-band", "tribute", "orchestra", "choir", "world-music",
  // arts
  "gallery", "visual-arts", "photography", "sculpture", "street-art", "craft",
  "spoken-word", "literary", "film", "dance", "opera",
  // theatre
  "musical", "drama", "improv", "sketch", "puppet-show", "dinner-theatre",
  // food
  "farmers-market", "food-tour", "tasting", "cooking-class", "pop-up",
  "brunch", "dinner", "beer", "wine", "cocktail", "coffee",
  // comedy
  "standup", "improv-comedy", "open-mic", "roast", "variety-show", "magic",
  // sports
  "basketball", "hockey", "baseball", "football", "soccer", "tennis", "golf",
  "wrestling", "boxing", "mma", "college-sports", "minor-league", "running",
  "fitness", "volleyball", "lacrosse", "rugby", "esports",
  // museums
  "art-museum", "science-museum", "history-museum", "exhibit", "planetarium",
  // tours
  "walking-tour", "boat-tour", "ghost-tour", "food-tour", "brewery-tour",
  "historical-tour", "architecture-tour",
  // libraries
  "book-club", "reading", "author-event", "workshop", "study-group",
  // outdoors
  "hiking", "cycling", "kayaking", "fishing", "camping", "climbing",
  "sailing", "skiing", "beach", "nature-walk", "bird-watching",
  "cleanup", "gardening",
  // for-kids
  "storytime", "craft-kids", "science-kids", "music-kids", "theatre-kids",
  "outdoor-play", "sports-kids", "camp",
  // other
  "workshop", "lecture", "networking", "community", "wellness",
  "charity", "religious", "holiday", "market", "fair", "convention",
  "meetup", "open-house", "volunteer", "fundraiser",
];

// Tags come from the faceted controlled vocabulary
// (scripts/taste/tag_vocabulary.js). VALID_TAGS is kept as an alias so the
// rest of this file is unchanged; VALID_TAGS_SET is O(1) for validation.
const VALID_TAGS = ALL_TAGS;
const VALID_TAGS_SET = new Set(ALL_TAGS);

// High-cardinality entities (performers, brands) are canonicalized to
// lowercase underscore slugs and capped, so "Shane Gillis" and "SHANE GILLIS!"
// collapse to the same feature. Kept OUT of the tag vocabulary on purpose.
function sanitizeEntities(arr, max = 6) {
  if (!Array.isArray(arr)) return [];
  const out = [];
  const seen = new Set();
  for (const raw of arr) {
    if (typeof raw !== "string") continue;
    const slug = raw.trim().toLowerCase()
      .replace(/&/g, " and ")
      .replace(/[^a-z0-9]+/g, "_")
      .replace(/^_+|_+$/g, "");
    if (slug.length < 2 || slug.length > 60 || seen.has(slug)) continue;
    seen.add(slug);
    out.push(slug);
    if (out.length >= max) break;
  }
  return out;
}

// ── Prompt builder ──

function buildPrompt(eventsForPrompt) {
  return `You are an expert event classifier for a local event discovery app.

For each event below, determine:
1. The correct **category** (parent type — one of: ${VALID_PARENT_CATEGORIES.join(", ")})
2. The correct **subcategory** (one of: ${VALID_SUBCATEGORIES.join(", ")})
3. An optional **sub_subcategory** for deeper personalization (one of: ${VALID_SUB_SUBCATEGORIES.join(", ")}, or null)
4. Between ${TAG_MIN} and ${TAG_MAX} **tags** describing the event, chosen ONLY from the faceted vocabulary below. Never invent tags. Pick the most specific applicable token from as many facets as fit — include at least one from audience, setting, format, vibe, and commitment whenever they apply:
${Object.entries(TAG_FACETS).map(([facet, toks]) => `   • ${facet}: ${toks.join(", ")}`).join("\n")}
5. **performers**: canonical names of any headlining people, acts, artists, or teams named in the title (e.g. "Matt Maeson", "Shane Gillis", "Boston Bruins"). Empty array if none. Do NOT include the venue.
6. **brands**: canonical names of a recurring event BRAND only — a festival, series, tour, or promoter (e.g. "Boston Calling", "Restaurant Week", "First Fridays"). Do NOT put the venue, host organization, library, museum, studio, or business name here — that is captured separately. Most events have NO brand; return [] unless there is a clear festival/series/tour name.

CATEGORY RULES (this is the most important classification):
- **upcoming**: Events with a specific scheduled date that customers would NOT expect to see repeating regularly. Concerts, one-off shows, specific game days, special exhibits, festivals, galas, etc.
- **always_on**: Events that run multiple times per day, daily, weekly, or monthly. Things customers can rely on being available regularly — tours, book clubs, weekly cleanups, volunteer shifts, recurring trivia nights, farmers markets, museum general admission, recurring fitness classes, weekly open mics, etc.

SUBCATEGORY RULES:
- "music" — concerts, live music, DJ sets, music festivals
- "arts" — galleries, visual arts, photography, film screenings, dance performances
- "theatre" — plays, musicals, improv shows, drama
- "food" — food tours, tastings, cooking classes, farmers markets, pop-ups, dining events
- "comedy" — standup, comedy shows, roasts
- "sports" — games, matches, races, fitness events
- "museums" — museum visits, exhibits, science centers, planetariums
- "tours" — walking tours, boat tours, ghost tours, historical tours
- "libraries" — book clubs, author events, library programs, readings
- "outdoors" — hiking, cycling, kayaking, beach events, nature walks, cleanups, gardening
- "for-kids" — storytime, kids camps, children's theatre, kids science, outdoor play
- "no-public-interest" — events that no member of the general public would be excited to discover and attend. If a random person browsing the app would not want to attend, classify it here. Examples:
  • Recovery / support groups — AA / NA / OA / GA / Al-Anon / Alateen / 12-step / SMART Recovery / Refuge Recovery / sober support meetings / grief groups
  • Youth / scholastic sports games, practices, or tryouts — JV, varsity, freshman, middle school, high school, AAU, little league, pee-wee, pop warner, U-league (U10/U12/U14 etc.). Even state championship HS games are too niche.
  • Private / member-only gatherings, private country-club events, private parties
  • Civic / government meetings — school committee, town/city council, select board, planning/zoning board, conservation commission, abutters meetings, HOA meetings
  • Internal corporate or staff trainings
  • Routine worship services (weekly prayer, daily prayer, Sunday service, mass). Church concerts, festivals, or special community events are NOT this — they go to music/arts/other.

IMPORTANT classification rules:
- There is NO "other" catch-all — it has been removed. EVERY event MUST be assigned one of the real subcategories above (or "no-public-interest"). Never return "other". Choose the single closest subcategory by the event's core activity:
  • workshops / classes → the subject: art/craft-making → "arts", cooking/tasting → "food", fitness/yoga/wellness → "sports", gardening/nature/hikes → "outdoors", anything kid-focused → "for-kids"
  • lectures / author talks / readings / book events / discussion groups → "libraries"
  • markets → craft/vintage/art markets → "arts"; farmers/food/maker-food markets → "food"
  • networking / business mixers / charity galas / community festivals / religious festivals / cultural celebrations → classify by the ACTUAL activity (charity concert → "music", community art fair → "arts", food-truck festival → "food", cultural dance festival → "arts")
  • Only use "no-public-interest" when the event genuinely has no consumer appeal (per the list above) — it is NOT a replacement dumping ground for "other". When in doubt between a real subcategory and "no-public-interest", prefer the real subcategory.
- Film screenings and movie showings are "arts" with sub_subcategory "film"
- A venue's default classification is often WRONG. Classify by the EVENT content, not the venue.
- ANY event whose title pits two sides against each other is almost certainly "sports" — games, matches, competitions. This includes "vs", "vs.", a standalone single-letter " v " or " v. " (Ticketmaster's format, e.g. "New England Patriots v Buffalo Bills", "2026 World Cup: Haiti v Scotland"), and "@" (e.g. "Yankees @ Red Sox"). It still applies when the matchup follows a prefix like "Preseason Game 1: Team A v Team B". Never classify these as music/arts/etc. For these matchup titles, ALWAYS populate team_name with the local/home team (the side belonging to the event's metro), using the canonical "City Nickname" form.
- Pro sports teams (MLB, NBA, NFL, NHL, MLS, and equivalents in any metro) are ALWAYS subcategory "sports" with sub_subcategory matching the sport (hockey, basketball, baseball, football, soccer, etc.).
- Pro sports teams in major leagues (MLB, NBA, NFL, NHL, MLS) should get the "pro-sports" tag. Minor-league affiliates (AAA/AA baseball, AHL hockey, USL soccer, etc.) get "minor-league" sub_subcategory, not "pro-sports".
- Free events should get the "free" tag
- Events good for families should get "family-friendly"
- Recurring events should get the "recurring" tag — this is KEY for always_on classification
- Use "hidden-gem" for unique, lesser-known events worth discovering
- Kids-focused events should be subcategory "for-kids" even if they could fit another category

Respond with a JSON array. Each element must have:
- "id": the event ID (string)
- "category": "upcoming" or "always_on" (string)
- "subcategory": one of the valid subcategories (string)
- "sub_subcategory": deeper classification or null (string or null)
- "team_name": for sports events, the team name exactly as commonly known — pro, college, or minor-league — in the metro the event belongs to (e.g. "Boston Bruins", "Philadelphia Eagles", "Northeastern Huskies", "Worcester Red Sox", "Lehigh Valley IronPigs"). Use the canonical "City Nickname" form. null for non-sports events or sports events not tied to a specific team (string or null)
- "tags": array of ${TAG_MIN}-${TAG_MAX} tag strings, each from the faceted vocabulary above
- "performers": array of performer/act/artist/team names (strings), or [] if none
- "brands": array of festival/series/promoter brand names (strings), or [] if none

ONLY output the JSON array, no other text.

Events to classify:
${JSON.stringify(eventsForPrompt, null, 2)}`;
}

// ── Main ──

async function main() {
  const secrets = await getSecrets(["ANTHROPIC_API_KEY"]);
  ANTHROPIC_API_KEY = secrets.ANTHROPIC_API_KEY;
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  if (!REVERT_MODE && !ANTHROPIC_API_KEY) { console.error("Missing ANTHROPIC_API_KEY"); process.exit(1); }
  if (!SUPABASE_SERVICE_KEY) { console.error("Missing SUPABASE_SERVICE_KEY"); process.exit(1); }

  if (REVERT_MODE) return await revertClassifications();

  if (TEAM_NAME_BACKFILL) return await runTeamNameBackfill();

  console.log(`Event Classifier (Batch API) — all metros, batch size: ${BATCH_SIZE}`);
  if (DRY_RUN) console.log("DRY RUN — no API calls\n");
  if (FORCE) console.log("FORCE — re-classifying all events\n");
  if (ONLY_CATEGORY) console.log(`ONLY_CATEGORY=${ONLY_CATEGORY} — scoping to active events with category="${ONLY_CATEGORY}", overriding unclassified-only filter\n`);
  if (ONLY_SUBCATEGORY) console.log(`ONLY_SUBCATEGORY=${ONLY_SUBCATEGORY} — scoping to active events with subcategory="${ONLY_SUBCATEGORY}", forced reclassification\n`);

  // 1. Fetch events to classify
  const events = await fetchAllEvents();
  console.log(`Fetched ${events.length} future events`);

  // 2. Pattern-based auto-exclusion — catches AA/recovery meetings, youth/JV
  //    sports, civic board meetings, etc. Deterministic, cheap, no Claude call.
  //    Runs against ALL future events (not just unclassified) so backfills
  //    already-misclassified events as well.
  // TAGS_ONLY preserves taxonomy: skip the pattern passes, which WRITE
  // subcategory/category directly. Auto-exclusion / reclassification belong to a
  // normal run, not a tags-only enrichment.
  const autoExcludedIds = TAGS_ONLY ? new Set() : await applyAutoExclusions(events);

  // 3. Pattern-based reclassification — fixes obvious category mistakes
  //    like speaker series at sports_venues inheriting "sports". Also runs
  //    on already-classified events so it backfills stale data. Skip any
  //    events that auto-exclusion just tagged as no-public-interest —
  //    otherwise reclassifier patterns like "info session" would undo it.
  const reclassifiedIds = TAGS_ONLY ? new Set() : await applyReclassifications(events, autoExcludedIds);

  if (PREFILTER_ONLY) {
    console.log("PREFILTER_ONLY=1 — skipping Claude batch\n");
    return;
  }

  // 3. Filter to unclassified events (unless FORCE / ONLY_CATEGORY), minus
  //    auto-excluded ones. ONLY_CATEGORY implies the caller knows the rows are
  //    misclassified despite already having sub_subcategory/tags, so we bypass
  //    the unclassified-only filter the same way FORCE does.
  const remaining = events.filter(
    (e) =>
      !autoExcludedIds.has(e.id) &&
      // Never re-send intentionally-suppressed events to Claude (see
      // RECLASSIFY_EXCLUDED above). Applies in FORCE/ONLY_CATEGORY too.
      (RECLASSIFY_EXCLUDED || e.subcategory !== "no-public-interest"),
  );
  let toClassify;
  if (TAGS_ONLY) {
    // Only rows not yet tagged under the current vocab — idempotent across re-runs.
    toClassify = remaining.filter((e) => e.tag_vocab_version !== TAG_VOCAB_VERSION);
  } else if (FORCE || ONLY_CATEGORY || ONLY_SUBCATEGORY) {
    toClassify = remaining;
  } else {
    toClassify = remaining.filter((e) => !e.sub_subcategory && (!e.tags || e.tags.length === 0));
  }
  if (MAX_EVENTS && toClassify.length > MAX_EVENTS) {
    console.log(`MAX_EVENTS=${MAX_EVENTS} — capping run to first ${MAX_EVENTS} of ${toClassify.length} events\n`);
    toClassify = toClassify.slice(0, MAX_EVENTS);
  }
  console.log(`${toClassify.length} events need Claude classification${TAGS_ONLY ? " (TAGS-ONLY — taxonomy preserved)" : FORCE ? " (forced)" : ONLY_CATEGORY ? ` (scoped to category=${ONLY_CATEGORY})` : ""}\n`);
  if (toClassify.length === 0) return;

  // 3. Build batch requests — one per chunk of BATCH_SIZE events
  const chunks = [];
  for (let i = 0; i < toClassify.length; i += BATCH_SIZE) {
    chunks.push(toClassify.slice(i, i + BATCH_SIZE));
  }
  console.log(`Submitting ${chunks.length} requests in one batch\n`);

  const batchRequests = chunks.map((chunk, i) => ({
    custom_id: `chunk-${i}`,
    params: {
      model: MODEL,
      max_tokens: 8192,
      messages: [{
        role: "user",
        content: buildPrompt(chunk.map(e => ({
          id: e.id,
          title: e.title,
          description: (e.description || "").slice(0, 200),
          current_category: e.category,
          current_subcategory: e.subcategory,
          venue_name: e.venue_name,
          venue_city: e.venue_city,
          is_free: e.is_free,
        }))),
      }],
    },
  }));

  if (DRY_RUN) {
    console.log(`Would submit batch with ${batchRequests.length} requests (${toClassify.length} events)`);
    console.log("DRY RUN — nothing sent");
    return;
  }

  // 4. Submit the batch
  const batchId = await submitBatch(batchRequests);
  console.log(`Batch submitted: ${batchId}`);

  // 5. Poll until complete
  const batchResult = await pollBatch(batchId);
  console.log(`\nBatch complete — succeeded: ${batchResult.request_counts.succeeded}, errored: ${batchResult.request_counts.errored}, expired: ${batchResult.request_counts.expired}`);

  // 6. Stream results and write to DB
  await processResults(batchId, chunks, events);
}

// ── Batch API helpers ──

async function submitBatch(requests) {
  const res = await fetch(`${API_BASE}/messages/batches`, {
    method: "POST",
    headers: {
      "x-api-key": ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
      "content-type": "application/json",
    },
    body: JSON.stringify({ requests }),
  });

  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Batch creation failed (${res.status}): ${body}`);
  }

  const data = await res.json();
  return data.id;
}

async function pollBatch(batchId) {
  let polls = 0;
  while (true) {
    await new Promise(r => setTimeout(r, POLL_INTERVAL));
    polls++;

    const res = await fetch(`${API_BASE}/messages/batches/${batchId}`, {
      headers: {
        "x-api-key": ANTHROPIC_API_KEY,
        "anthropic-version": "2023-06-01",
      },
    });

    if (!res.ok) {
      console.error(`  Poll error (${res.status}), retrying...`);
      continue;
    }

    const data = await res.json();
    const counts = data.request_counts;
    console.log(
      `  [${polls}] status: ${data.processing_status} — ` +
      `processing: ${counts.processing}, succeeded: ${counts.succeeded}, ` +
      `errored: ${counts.errored}, expired: ${counts.expired}`
    );

    if (data.processing_status === "ended") return data;
  }
}

async function processResults(batchId, chunks, allEvents) {
  const res = await fetch(`${API_BASE}/messages/batches/${batchId}/results`, {
    headers: {
      "x-api-key": ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
    },
  });

  if (!res.ok) {
    throw new Error(`Failed to fetch results (${res.status}): ${await res.text()}`);
  }

  const text = await res.text();
  const lines = text.split("\n").filter(Boolean);

  let totalChanged = 0;
  let totalTagged = 0;
  let totalErrors = 0;

  for (const line of lines) {
    const result = JSON.parse(line);
    const chunkIndex = parseInt(result.custom_id.replace("chunk-", ""), 10);
    const chunk = chunks[chunkIndex];

    if (result.result.type !== "succeeded") {
      totalErrors++;
      console.error(`  ${result.custom_id} failed: ${result.result.type}`);
      if (result.result.error) console.error(`    ${result.result.error.message}`);
      continue;
    }

    // Parse Claude's response
    const responseText = result.result.message.content[0].text.trim();
    let jsonStr = responseText;
    const codeBlockMatch = responseText.match(/```(?:json)?\s*([\s\S]*?)```/);
    if (codeBlockMatch) jsonStr = codeBlockMatch[1].trim();

    let classifications;
    try {
      classifications = JSON.parse(jsonStr);
    } catch (err) {
      totalErrors++;
      console.error(`  ${result.custom_id} — JSON parse error: ${err.message}`);
      continue;
    }

    // Validate and collect updates
    const updates = [];
    let batchChanges = 0;

    for (const c of classifications) {
      const validated = {
        id: c.id,
        category: VALID_PARENT_CATEGORIES.includes(c.category) ? c.category : null,
        subcategory: c.subcategory && VALID_SUBCATEGORIES.includes(c.subcategory) ? c.subcategory : null,
        sub_subcategory: c.sub_subcategory && VALID_SUB_SUBCATEGORIES.includes(c.sub_subcategory) ? c.sub_subcategory : null,
        team_name: c.team_name || null,
        tags: Array.isArray(c.tags) ? [...new Set(c.tags.filter(t => VALID_TAGS_SET.has(t)))].slice(0, TAG_MAX) : [],
        performers: sanitizeEntities(c.performers),
        brands: sanitizeEntities(c.brands),
        tag_vocab_version: TAG_VOCAB_VERSION,
      };

      if (!validated.category || !validated.subcategory) continue;

      const original = chunk.find(e => e.id === validated.id);
      if (!original) continue;

      const changed = original.category !== validated.category ||
                      original.subcategory !== validated.subcategory;

      if (changed || validated.tags.length > 0 || validated.sub_subcategory ||
          validated.performers.length > 0 || validated.brands.length > 0) {
        updates.push(validated);
        if (changed) batchChanges++;
      }
    }

    if (updates.length > 0) {
      await writeUpdates(updates, chunk);
    }

    totalChanged += batchChanges;
    totalTagged += updates.length;
    console.log(`  ${result.custom_id}: ${batchChanges} recategorized, ${updates.length} tagged`);
  }

  console.log(`\n========================================`);
  console.log(`Done. Recategorized: ${totalChanged}, Tagged: ${totalTagged}, Errors: ${totalErrors}`);
}

// ── Fetch all future events ──

async function fetchAllEvents() {
  // Keyset pagination on start_at: every page is a fresh `start_at > cursor`
  // query that hits the (start_at) index directly. OFFSET pagination was
  // tripping the 8s statement timeout once the inventory crossed ~70k
  // future events — Postgres re-scans from row 0 on every page when there's
  // no covering index for the ORDER BY + OFFSET combination.
  const allEvents = [];
  const pageSize = 500;
  // Also filter is_active=true server-side; reclassifying past/inactive rows
  // wastes Anthropic spend and never affects user-visible surfaces.
  let baseScope = ONLY_CATEGORY
    ? `&category=eq.${encodeURIComponent(ONLY_CATEGORY)}&is_active=eq.true`
    : `&is_active=eq.true`;
  if (ONLY_SUBCATEGORY) baseScope += `&subcategory=eq.${encodeURIComponent(ONLY_SUBCATEGORY)}`;

  const nowIso = new Date().toISOString();
  let cursor = nowIso;       // first page: start_at >= now
  let firstPage = true;

  while (true) {
    let selectCols = "id,title,description,category,subcategory,sub_subcategory,tags,tag_vocab_version,venue_name,venue_city,is_free,source,original_category,original_subcategory,start_at";
    // strict `gt` for subsequent pages so we don't re-fetch the cursor row;
    // first page uses `gte` so events starting exactly at the script start
    // time still get picked up.
    const cmp = firstPage ? "gte" : "gt";
    let url = `${SUPABASE_URL}/rest/v1/events?start_at=${cmp}.${encodeURIComponent(cursor)}${baseScope}&select=${selectCols}&order=start_at.asc&limit=${pageSize}`;

    let res = await fetch(url, {
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      },
    });

    // If new columns don't exist yet, retry without them
    if (!res.ok) {
      const errText = await res.text();
      if (errText.includes("original_category") || errText.includes("sub_subcategory") || errText.includes("tags") || errText.includes("tag_vocab_version") || errText.includes("performers") || errText.includes("brands")) {
        selectCols = "id,title,description,category,subcategory,venue_name,venue_city,is_free,source,start_at";
        url = `${SUPABASE_URL}/rest/v1/events?start_at=${cmp}.${encodeURIComponent(cursor)}${baseScope}&select=${selectCols}&order=start_at.asc&limit=${pageSize}`;
        res = await fetch(url, {
          headers: {
            apikey: SUPABASE_SERVICE_KEY,
            Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
          },
        });
        if (!res.ok) throw new Error(`Fetch failed: ${res.status} ${await res.text()}`);
      } else {
        throw new Error(`Fetch failed: ${res.status} ${errText}`);
      }
    }

    const rows = await res.json();
    allEvents.push(...rows);
    process.stdout.write(`  fetched ${allEvents.length}\r`);

    // MAX_EVENTS cap: stop paging once we have plenty to satisfy the cap after
    // filtering (5× buffer for no-public-interest / already-tagged rows that get
    // dropped). Avoids paging the entire corpus for a capped/test run.
    if (MAX_EVENTS > 0 && allEvents.length >= MAX_EVENTS * 5) {
      process.stdout.write(`  fetched ${allEvents.length} (capped early for MAX_EVENTS=${MAX_EVENTS})\n`);
      break;
    }

    if (rows.length < pageSize) break;
    // Advance the cursor to the last row's start_at. If a cluster of rows
    // shares the same start_at across the boundary we may skip a few; that
    // is acceptable for this script (the next nightly run will pick them up
    // and they don't drop out of the inventory).
    cursor = rows[rows.length - 1].start_at;
    firstPage = false;
  }
  console.log("");

  return allEvents;
}

// ── Write updates back to Supabase ──

async function writeUpdates(updates, originalEvents) {
  const concurrency = WRITE_CONCURRENCY;

  for (let i = 0; i < updates.length; i += concurrency) {
    const batch = updates.slice(i, i + concurrency);

    // Throttle: pause between waves, and every 25 waves probe DB health and
    // back off if it's warming (protects the Nano prod DB from IO exhaustion).
    if (_writeWaveCount > 0) await _sleep(WRITE_WAVE_DELAY_MS);
    if (++_writeWaveCount % 25 === 0) {
      const h = await probeDbHealth();
      if (!h.ok || h.ms > WRITE_PROBE_HARD_MS) {
        WRITE_WAVE_DELAY_MS = Math.min(WRITE_MAX_DELAY_MS, Math.round(WRITE_WAVE_DELAY_MS * 1.5));
        console.warn(`  ⚠ DB slow/unreachable (${h.ms}ms) — pausing 30s, delay→${WRITE_WAVE_DELAY_MS}ms`);
        await _sleep(30000);
      } else if (h.ms > WRITE_PROBE_SLOW_MS) {
        WRITE_WAVE_DELAY_MS = Math.min(WRITE_MAX_DELAY_MS, Math.round(WRITE_WAVE_DELAY_MS * 1.3));
        console.warn(`  ↑ DB warm (${h.ms}ms) — delay→${WRITE_WAVE_DELAY_MS}ms`);
      }
    }

    await Promise.all(batch.map(async (u) => {
      const original = originalEvents.find(e => e.id === u.id);
      const url = `${SUPABASE_URL}/rest/v1/events?id=eq.${u.id}`;

      // TAGS_ONLY: write only tag/entity fields, never the taxonomy — the
      // existing category/subcategory/sub_subcategory/team_name are preserved.
      const body = TAGS_ONLY ? {} : {
        category: u.category,
        subcategory: u.subcategory,
      };

      if (!TAGS_ONLY && u.sub_subcategory) {
        body.sub_subcategory = u.sub_subcategory;
      }

      if (!TAGS_ONLY && u.team_name) {
        body.team_name = u.team_name;
      }

      // Preserve original values for revert — only set if not already saved
      if (!TAGS_ONLY && original && !original.original_category) {
        body.original_category = original.category;
        body.original_subcategory = original.subcategory || null;
      }

      if (u.tags && u.tags.length > 0) {
        body.tags = u.tags;
      }

      // Taste-graph entity features (may not exist on prod until the
      // 20260716140000 migration lands — the retry below handles that).
      if (u.performers && u.performers.length > 0) {
        body.performers = u.performers;
      }
      if (u.brands && u.brands.length > 0) {
        body.brands = u.brands;
      }
      if (u.tag_vocab_version) {
        body.tag_vocab_version = u.tag_vocab_version;
      }

      const res = await fetch(url, {
        method: "PATCH",
        headers: {
          apikey: SUPABASE_SERVICE_KEY,
          Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
          "Content-Type": "application/json",
          Prefer: "return=minimal",
        },
        body: JSON.stringify(body),
      });

      if (!res.ok) {
        const errBody = await res.text();
        // If new columns don't exist yet, retry with just category/subcategory
        if (errBody.includes("original_category") || errBody.includes("tags") || errBody.includes("sub_subcategory") || errBody.includes("performers") || errBody.includes("brands") || errBody.includes("tag_vocab_version")) {
          // In TAGS_ONLY the tag columns are the whole point — if they're
          // missing there's nothing safe to write (and we must NOT touch
          // taxonomy). Skip rather than fall back to a category write.
          if (TAGS_ONLY) {
            console.error(`  Skipped ${u.id}: tag columns missing (run the migration first)`);
            return;
          }
          const fallback = {
            category: u.category,
            subcategory: u.subcategory,
          };
          const retry = await fetch(url, {
            method: "PATCH",
            headers: {
              apikey: SUPABASE_SERVICE_KEY,
              Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
              "Content-Type": "application/json",
              Prefer: "return=minimal",
            },
            body: JSON.stringify(fallback),
          });
          if (!retry.ok) {
            console.error(`  Failed to update ${u.id}: ${await retry.text()}`);
          }
        } else {
          console.error(`  Failed to update ${u.id}: ${errBody}`);
        }
      }
    }));
  }
}

// ── Revert all AI classifications ──

async function revertClassifications() {
  console.log("Reverting AI classifications for all metros");
  if (DRY_RUN) console.log("DRY RUN — no database writes\n");

  const allClassified = [];
  const pageSize = 1000;
  let offset = 0;

  while (true) {
    const url = `${SUPABASE_URL}/rest/v1/events?original_category=not.is.null&select=id,title,category,subcategory,original_category,original_subcategory&order=id.asc&limit=${pageSize}&offset=${offset}`;
    const res = await fetch(url, {
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      },
    });

    if (!res.ok) {
      const body = await res.text();
      if (body.includes("original_category")) {
        console.log("No original_category column found — nothing to revert.");
        return;
      }
      throw new Error(`Fetch failed: ${res.status} ${body}`);
    }

    const rows = await res.json();
    allClassified.push(...rows);
    if (rows.length < pageSize) break;
    offset += pageSize;
  }

  console.log(`Found ${allClassified.length} AI-classified events to revert\n`);
  if (allClassified.length === 0) return;

  let reverted = 0;
  const concurrency = 10;

  for (let i = 0; i < allClassified.length; i += concurrency) {
    const batch = allClassified.slice(i, i + concurrency);
    await Promise.all(batch.map(async (ev) => {
      if (DRY_RUN) {
        console.log(`  Would revert "${ev.title}": ${ev.category}/${ev.subcategory} → ${ev.original_category}/${ev.original_subcategory}`);
        reverted++;
        return;
      }

      const url = `${SUPABASE_URL}/rest/v1/events?id=eq.${ev.id}`;
      const res = await fetch(url, {
        method: "PATCH",
        headers: {
          apikey: SUPABASE_SERVICE_KEY,
          Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
          "Content-Type": "application/json",
          Prefer: "return=minimal",
        },
        body: JSON.stringify({
          category: ev.original_category,
          subcategory: ev.original_subcategory,
          sub_subcategory: null,
          original_category: null,
          original_subcategory: null,
          tags: null,
        }),
      });

      if (res.ok) {
        reverted++;
      } else {
        console.error(`  Failed to revert ${ev.id}: ${await res.text()}`);
      }
    }));
  }

  console.log(`\nReverted ${reverted} events back to original categories.`);
}

// ── Team name backfill ──
//
// Targets the narrow slice of sports events that were classified before
// `team_name` was added to the prompt (or whose first pass failed to extract
// it). Fetches sports events with `team_name IS NULL`, runs the standard
// classifier prompt, and writes back ONLY `team_name` — never touches
// category/subcategory/sub_subcategory/tags so prior classifications stand.

async function runTeamNameBackfill() {
  console.log("Team Name Backfill — sports events missing team_name");
  if (DRY_RUN) console.log("DRY RUN — no API calls\n");

  const events = await fetchSportsEventsMissingTeamName();
  console.log(`Fetched ${events.length} sports events with null team_name`);
  if (events.length === 0) return;

  const chunks = [];
  for (let i = 0; i < events.length; i += BATCH_SIZE) {
    chunks.push(events.slice(i, i + BATCH_SIZE));
  }
  console.log(`Submitting ${chunks.length} requests in one batch\n`);

  const batchRequests = chunks.map((chunk, i) => ({
    custom_id: `chunk-${i}`,
    params: {
      model: MODEL,
      max_tokens: 8192,
      messages: [{
        role: "user",
        content: buildPrompt(chunk.map(e => ({
          id: e.id,
          title: e.title,
          description: (e.description || "").slice(0, 200),
          current_category: "upcoming",
          current_subcategory: "sports",
          venue_name: e.venue_name,
          venue_city: e.venue_city,
          is_free: e.is_free,
        }))),
      }],
    },
  }));

  if (DRY_RUN) {
    console.log(`Would submit batch with ${batchRequests.length} requests (${events.length} events)`);
    return;
  }

  const batchId = await submitBatch(batchRequests);
  console.log(`Batch submitted: ${batchId}`);

  const batchResult = await pollBatch(batchId);
  console.log(`\nBatch complete — succeeded: ${batchResult.request_counts.succeeded}, errored: ${batchResult.request_counts.errored}, expired: ${batchResult.request_counts.expired}`);

  await processTeamNameResults(batchId);
}

async function fetchSportsEventsMissingTeamName() {
  // Many Ticketmaster-sourced events carry raw genre strings in `subcategory`
  // (hockey, baseball, basketball, football, soccer) instead of the properly
  // classified `subcategory="sports"` + `sub_subcategory="<sport>"` shape.
  // Include those so we don't miss pro-team games when backfilling.
  const sportSubcategories = [
    "sports", "hockey", "baseball", "basketball", "football", "soccer",
    "rugby", "lacrosse", "college-sports", "minor-league",
  ];
  const filter = sportSubcategories.map(s => `"${s}"`).join(",");

  const all = [];
  const pageSize = 1000;
  let offset = 0;

  while (true) {
    const now = new Date().toISOString();
    const cols = "id,title,description,venue_name,venue_city,is_free";
    const url = `${SUPABASE_URL}/rest/v1/events?start_at=gte.${now}&subcategory=in.(${filter})&team_name=is.null&select=${cols}&order=start_at.asc&limit=${pageSize}&offset=${offset}`;
    const res = await fetch(url, {
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      },
    });
    if (!res.ok) throw new Error(`Fetch failed: ${res.status} ${await res.text()}`);
    const rows = await res.json();
    all.push(...rows);
    if (rows.length < pageSize) break;
    offset += pageSize;
  }

  return all;
}

async function processTeamNameResults(batchId) {
  const res = await fetch(`${API_BASE}/messages/batches/${batchId}/results`, {
    headers: {
      "x-api-key": ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
    },
  });
  if (!res.ok) throw new Error(`Failed to fetch results (${res.status}): ${await res.text()}`);

  const lines = (await res.text()).split("\n").filter(Boolean);
  let patched = 0;
  let noTeam = 0;
  let errors = 0;

  for (const line of lines) {
    const result = JSON.parse(line);
    if (result.result.type !== "succeeded") {
      errors++;
      console.error(`  ${result.custom_id} failed: ${result.result.type}`);
      continue;
    }

    const responseText = result.result.message.content[0].text.trim();
    let jsonStr = responseText;
    const codeBlockMatch = responseText.match(/```(?:json)?\s*([\s\S]*?)```/);
    if (codeBlockMatch) jsonStr = codeBlockMatch[1].trim();

    let classifications;
    try {
      classifications = JSON.parse(jsonStr);
    } catch (err) {
      errors++;
      console.error(`  ${result.custom_id} — JSON parse error: ${err.message}`);
      continue;
    }

    const updates = [];
    for (const c of classifications) {
      if (c.id && typeof c.team_name === "string" && c.team_name.trim()) {
        updates.push({ id: c.id, team_name: c.team_name.trim() });
      } else {
        noTeam++;
      }
    }

    if (updates.length === 0) continue;
    await writeTeamNameOnly(updates);
    patched += updates.length;
    console.log(`  ${result.custom_id}: patched ${updates.length}`);
  }

  console.log(`\n========================================`);
  console.log(`Done. Patched team_name on ${patched} events. ${noTeam} had no detectable team. Errors: ${errors}`);
}

async function writeTeamNameOnly(updates) {
  const concurrency = 10;
  for (let i = 0; i < updates.length; i += concurrency) {
    const batch = updates.slice(i, i + concurrency);
    await Promise.all(batch.map(async (u) => {
      const res = await fetch(`${SUPABASE_URL}/rest/v1/events?id=eq.${u.id}`, {
        method: "PATCH",
        headers: {
          apikey: SUPABASE_SERVICE_KEY,
          Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
          "Content-Type": "application/json",
          Prefer: "return=minimal",
        },
        body: JSON.stringify({ team_name: u.team_name }),
      });
      if (!res.ok) {
        console.error(`  Failed to update ${u.id}: ${await res.text()}`);
      }
    }));
  }
}

main().catch(err => { console.error(err); process.exit(1); });
