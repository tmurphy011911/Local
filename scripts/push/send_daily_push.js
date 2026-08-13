#!/usr/bin/env node
/**
 * send_daily_push.js — the daily push evaluator.
 *
 * Runs once a day (GitHub Actions cron, `push.yml`). For today's weekday it
 * walks a PRIORITY DECISION TREE per user and sends at most ONE push each: the
 * first matching leaf wins, otherwise the user gets nothing. See
 * project_plan_push_strategy for the full trees. Notification legend:
 *   A plan reminder (ungated, every day)   E Daily Local reminder (Sun)
 *   B discovery/FOMO (Tue)                  F taste weekend events (Thu)
 *   C Daily Local social-proof (Tue/fallback)  G saved-venue weekend events (Thu)
 *   D saved event soon (Sun/Fri)
 *
 * GATE: the login-recency check EXCLUDES currently-active users (push is a
 * re-engagement channel) — EXCEPT A (utility) and EXCEPT Thursday (weekend
 * preview is for everyone). B/F need >=3 events or they fall back to C (unless
 * Daily Local already played today -> silent).
 *
 * TIMEZONE: all live states are US Eastern, so v1 fires on one Eastern-timed
 * cron with NO per-user tz math. Revisit (store tz / derive from lat/lng) when
 * a non-Eastern state launches.
 *
 * Usage:
 *   SUPABASE_URL=<dev|prod> SUPABASE_SERVICE_KEY=ey... node scripts/push/send_daily_push.js         # DRY RUN (logs who'd get what)
 *   SUPABASE_URL=... SUPABASE_SERVICE_KEY=... SEND=1 node scripts/push/send_daily_push.js            # actually send
 *   ... DAY=thu ...    # override weekday for testing (sun|mon|tue|wed|thu|fri|sat)
 */

const { getSecrets, SUPABASE_URL } = require("../lib/secrets");
const { sendPush } = require("./lib/onesignal");

const SEND = process.env.SEND === "1";
const DAY_OVERRIDE = (process.env.DAY || "").toLowerCase();
const WEEKDAYS = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"];

// --- tiny Supabase REST helpers (raw fetch, same style as lib/secrets) --------

let SERVICE_KEY = "";

async function sbGet(pathAndQuery) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/${pathAndQuery}`, {
    headers: { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` },
  });
  if (!res.ok) throw new Error(`GET ${pathAndQuery} -> ${res.status}: ${(await res.text()).slice(0, 200)}`);
  return res.json();
}

async function sbInsert(table, rows) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/${table}`, {
    method: "POST",
    headers: {
      apikey: SERVICE_KEY,
      Authorization: `Bearer ${SERVICE_KEY}`,
      "Content-Type": "application/json",
      Prefer: "return=minimal,resolution=ignore-duplicates", // dedupe index makes reruns idempotent
    },
    body: JSON.stringify(rows),
  });
  if (!res.ok && res.status !== 409) {
    throw new Error(`INSERT ${table} -> ${res.status}: ${(await res.text()).slice(0, 200)}`);
  }
}

// --- date helpers (Eastern-anchored; see TIMEZONE note) -----------------------

/** YYYY-MM-DD for `now` shifted by `days`, in US Eastern. */
function isoDateEastern(days = 0) {
  // Anchor to Eastern by formatting in that tz, then re-parsing the date parts.
  const d = new Date(Date.now() + days * 86400000);
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "America/New_York", year: "numeric", month: "2-digit", day: "2-digit",
  }).formatToParts(d);
  const get = (t) => parts.find((p) => p.type === t).value;
  return `${get("year")}-${get("month")}-${get("day")}`;
}

function easternWeekday() {
  const wd = new Intl.DateTimeFormat("en-US", { timeZone: "America/New_York", weekday: "short" })
    .format(new Date()).toLowerCase().slice(0, 3);
  return wd;
}

/** Date boundary (UTC midnight) of the ET calendar date today+days, for start_at
 * comparisons. UTC (not ET) offset on purpose: date-only events are stamped at
 * 00:00 UTC, so a UTC boundary includes them; timed events shift at most a few
 * hours. Fine while all live states are Eastern; see TIMEZONE note. */
function dateBoundaryISO(days = 0) {
  return `${isoDateEastern(days)}T00:00:00Z`; // Z, not +00:00 — the + decodes to a space in a query string
}

/** "Thursday at 7pm" for a timed event, or just "Thursday" for a date-only one
 * (time_known=false, stamped at 00:00 UTC — rendering a time would be wrong, and
 * the day must come from the UTC date, not ET, or it lands a day early). */
function formatDayTime(startAt, timeKnown) {
  const d = new Date(startAt);
  if (!timeKnown) {
    return new Intl.DateTimeFormat("en-US", { timeZone: "UTC", weekday: "long" }).format(d);
  }
  const day = new Intl.DateTimeFormat("en-US", { timeZone: "America/New_York", weekday: "long" }).format(d);
  const time = new Intl.DateTimeFormat("en-US", { timeZone: "America/New_York", hour: "numeric", minute: "2-digit", hour12: true })
    .format(d).toLowerCase().replace(":00", "").replace(" ", "");
  return `${day} at ${time}`;
}

// --- notification builders ----------------------------------------------------
// Each returns Map<uid, { title, body, path, dedupeKey, type }>. Priority is the
// ARRAY ORDER in the per-day tree below; first match per uid wins.

/**
 * A — plan reminder. Ungated, every day. "{Plan} starts tomorrow."
 * plans.date = tomorrow -> notify plans.user_id (the owner).
 */
async function buildPlanReminders() {
  const tomorrow = isoDateEastern(1);
  const plans = await sbGet(`plans?date=eq.${tomorrow}&select=id,user_id,name`);
  const out = new Map();
  for (const p of plans) {
    if (!p.user_id) continue;
    out.set(p.user_id, {
      type: "A",
      title: `"${p.name}" starts tomorrow`,
      body: "Bo is here if you need any last minute changes or recommendations",
      path: `/p/${p.id}`,
      dedupeKey: `A:plan:${p.id}:${tomorrow}`,
    });
  }
  return out;
}

/**
 * D — saved event happening soon. Sun window = next 7 days; Fri window =
 * tonight..Sunday. Saved events are community_interactions favorite/event
 * (item_id = events.id). Copy: "{Event} is almost here / {Day} at {Time}. Bringing Bo?"
 */
async function buildSavedEventSoon(window) {
  const start = dateBoundaryISO(0);
  const end = window === "weekend_edge" ? dateBoundaryISO(3) : dateBoundaryISO(8); // Fri: Fri–Sun; Sun: next 7d
  const favs = await sbGet(`community_interactions?interaction_type=eq.favorite&item_type=eq.event&select=user_id,item_id`);
  if (favs.length === 0) return new Map();
  const ids = [...new Set(favs.map((f) => f.item_id))];
  const events = await sbGet(`events?id=in.(${ids.join(",")})&is_active=eq.true&start_at=gte.${start}&start_at=lt.${end}&select=id,title,start_at,time_known`);
  const evById = new Map(events.map((e) => [e.id, e]));
  const soonest = new Map(); // uid -> soonest saved event in window
  for (const f of favs) {
    const e = evById.get(f.item_id);
    if (!e) continue;
    const cur = soonest.get(f.user_id);
    if (!cur || e.start_at < cur.start_at) soonest.set(f.user_id, e);
  }
  const out = new Map();
  for (const [uid, e] of soonest) {
    out.set(uid, {
      type: "D",
      title: `${e.title} is almost here`,
      body: `${formatDayTime(e.start_at, e.time_known)}. Bringing Bo?`,
      path: `/e/${e.id}`,
      dedupeKey: `D:event:${e.id}:${isoDateEastern(0)}`,
    });
  }
  return out;
}

/**
 * G — a favorited venue has events this weekend (Thu–Sun). Favorites are
 * community_interactions favorite/place (item_id = venues.id); events join by
 * venue_name (events carry no venue_id). Copy: "Bo has something at {Venue}
 * this weekend / {Event}{ + N more}".
 */
async function buildSavedVenueWeekend() {
  const start = dateBoundaryISO(0), end = dateBoundaryISO(4); // Thu..Sun
  const favs = await sbGet(`community_interactions?interaction_type=eq.favorite&item_type=eq.place&select=user_id,item_id`);
  if (favs.length === 0) return new Map();
  const vIds = [...new Set(favs.map((f) => f.item_id))];
  const venues = await sbGet(`venues?id=in.(${vIds.join(",")})&select=id,name`);
  const vById = new Map(venues.map((v) => [v.id, v]));
  const byUser = new Map(); // uid -> [venueId...]
  for (const f of favs) {
    if (!byUser.has(f.user_id)) byUser.set(f.user_id, []);
    byUser.get(f.user_id).push(f.item_id);
  }
  const out = new Map();
  for (const [uid, vids] of byUser) {
    for (const vid of vids) {
      const v = vById.get(vid);
      if (!v) continue;
      const evs = await sbGet(`events?venue_name=eq.${encodeURIComponent(v.name)}&is_active=eq.true&start_at=gte.${start}&start_at=lt.${end}&select=id,title&order=start_at.asc&limit=6`);
      if (evs.length > 0) {
        const more = evs.length > 1 ? ` + ${evs.length - 1} more` : "";
        out.set(uid, {
          type: "G",
          title: `Bo has something at ${v.name} this weekend`,
          body: `${evs[0].title}${more}`,
          path: `/v/${v.id}`,
          dedupeKey: `G:venue:${v.id}:${isoDateEastern(0)}`,
        });
        break; // one G per user
      }
    }
  }
  return out;
}

// Shared context (loaded once in main, passed to the builders that need it).
async function loadProfiles() {
  const rows = await sbGet(`user_profiles?onboarding_complete=eq.true&select=id,selected_categories,user_lat,user_lng,manual_lat,manual_lng,location_mode,home_state`);
  return new Map(rows.map((r) => [r.id, r]));
}
async function loadActivity() {
  // last_sign_in_at per user via the auth admin API (paged). Powers the gate.
  const map = new Map();
  for (let page = 1; ; page++) {
    const res = await fetch(`${SUPABASE_URL}/auth/v1/admin/users?page=${page}&per_page=1000`, {
      headers: { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` },
    });
    const users = (await res.json()).users || [];
    for (const u of users) if (u.last_sign_in_at) map.set(u.id, new Date(u.last_sign_in_at));
    if (users.length < 1000) break;
  }
  return map;
}
async function loadDailyLocal() {
  const today = isoDateEastern(0);
  const rows = await sbGet(`quiz_completions?quiz_date=eq.${today}&select=user_id,state,score`);
  const playedToday = new Set(rows.map((r) => r.user_id));
  const topScoreByState = new Map();
  for (const r of rows) {
    if (r.state == null) continue;
    const cur = topScoreByState.get(r.state);
    if (cur == null || r.score > cur) topScoreByState.set(r.state, r.score);
  }
  // per user: latest streak, whether they've ever played, and whether their most
  // recent game (within 2 days) was 2/3 — powers the streak/"so close" E copy.
  const recent = await sbGet(`quiz_completions?select=user_id,streak,correct_count,quiz_date&order=quiz_date.desc&limit=5000`);
  const twoDaysAgo = isoDateEastern(-2);
  const streaks = new Map(), everPlayed = new Set(), lastClose = new Set();
  for (const r of recent) {
    everPlayed.add(r.user_id);
    if (!streaks.has(r.user_id)) { // first row per user = most recent game
      streaks.set(r.user_id, r.streak || 0);
      if (r.correct_count === 2 && r.quiz_date >= twoDaysAgo) lastClose.add(r.user_id);
    }
  }
  return { playedToday, topScoreByState, streaks, everPlayed, lastClose };
}
function userLatLng(p) {
  if (p.location_mode === "manual" && p.manual_lat != null) return { lat: p.manual_lat, lng: p.manual_lng };
  if (p.user_lat != null) return { lat: p.user_lat, lng: p.user_lng };
  return null;
}
// Interaction-inferred taste. community_interactions.category uses a finer
// taxonomy than events.category, so normalize it (and drop unmappable ones).
const CAT_MAP = { rock: "music", "restaurant-week": "food", "for-kids": "family", "farmers-market": "food" };
const EVENT_CATS = new Set(["arts", "music", "sports", "community", "food", "outdoors", "family"]);
function normalizeCat(c) { const m = CAT_MAP[c] || c; return EVENT_CATS.has(m) ? m : null; }

/** Infer each user's top category from their interactions (favorite 2 / share 4 /
 * click 1, matching the app's taste weighting), normalized to event categories. */
async function loadInteractionTaste() {
  const W = { favorite: 2, share: 4, click: 1 };
  const rows = await sbGet(`community_interactions?select=user_id,category,interaction_type&category=not.is.null&limit=20000`);
  const per = new Map();
  for (const r of rows) {
    const c = normalizeCat(r.category);
    if (!c) continue;
    let m = per.get(r.user_id);
    if (!m) { m = {}; per.set(r.user_id, m); }
    m[c] = (m[c] || 0) + (W[r.interaction_type] || 1);
  }
  const top = new Map();
  for (const [uid, m] of per) {
    const best = Object.entries(m).sort((a, b) => b[1] - a[1])[0];
    if (best) top.set(uid, best[0]);
  }
  return top;
}

/** Top category: inferred from interactions first, else a normalized onboarding
 * pick, else null (→ the taste builders send a location-only default). */
function topCategory(p, ctx) {
  const inferred = ctx.interestByUser.get(p.id);
  if (inferred) return inferred;
  const cats = Array.isArray(p.selected_categories) ? p.selected_categories : [];
  for (const c of cats) { const n = normalizeCat(c); if (n) return n; }
  return null;
}
function isActive(activity, uid, withinDays) {
  const last = activity.get(uid);
  return last ? Date.now() - last.getTime() <= withinDays * 86400000 : false;
}
/** exact count of `cat` events within ~10mi of (lat,lng) in [start,end).
 * Filters on venue_lat/venue_lng — event_lat/event_lng are null across the table;
 * venue coords are the populated geo field (matches the carousel geo queries). */
async function tasteEventCount(cat, lat, lng, start, end) {
  const dLat = 10 / 69, dLng = 10 / (69 * Math.cos((lat * Math.PI) / 180));
  const box = `venue_lat=gte.${(lat - dLat).toFixed(4)}&venue_lat=lte.${(lat + dLat).toFixed(4)}&venue_lng=gte.${(lng - dLng).toFixed(4)}&venue_lng=lte.${(lng + dLng).toFixed(4)}`;
  const catFilter = cat ? `category=eq.${encodeURIComponent(cat)}&` : ""; // null cat = all events (the default push)
  const res = await fetch(
    `${SUPABASE_URL}/rest/v1/events?${catFilter}is_active=eq.true&start_at=gte.${start}&start_at=lt.${end}&${box}&select=id`,
    { headers: { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}`, Prefer: "count=exact", Range: "0-0" } },
  );
  const cr = res.headers.get("content-range"); // "0-0/<total>" or "*/<total>"
  return cr ? parseInt(cr.split("/")[1], 10) || 0 : 0;
}
/** E copy. Title is always the state's score to beat (or a "be the first" line
 * when nobody's posted one). Body varies by the user's Daily Local state and
 * rotates by a {uid}+{date} hash so it isn't identical two sends running.
 * {N} = current streak, {N1} = streak+1. */
const E_BODIES = {
  never: [
    "Bo says a first-timer won't touch it. Prove him wrong — 3 questions.",
    "Everyone starts at zero. Go get yours — 30 seconds.",
    "You've never played, so Bo likes his odds. Ruin his afternoon.",
    "New here? Sneak up on the leaderboard. 3 questions, that's it.",
    "Bo bet you'd never open this. Beat it and make it awkward for him.",
    "Your city, 3 questions, 30 seconds. Start the streak today.",
  ],
  lapsed: [
    "You let the streak die. Bo noticed. One clean run fixes it.",
    "Comeback season? Beat it and restart the run.",
    "Bo's been holding your spot. Take today's three and reclaim it.",
    "A zero-day streak is a bad look on you. 30 seconds to fix it.",
    "You know you can top that. Prove you still can.",
    "Rusty? Bo's counting on it. Prove otherwise.",
  ],
  fragile: [
    "Day {N}'s in the bag — don't fold on day {N1}.",
    "A {N}-day streak is a fragile little thing. Guard it.",
    "Bo's waiting for the day you slip. Don't let it be today.",
    "You've got {N} in a row on the line. Keep it breathing.",
    "Two-day-old streaks break easy. Not yours, right?",
    "Momentum's real. Beat it, extend the run.",
  ],
  rolling: [
    "{N} days deep. Bo won't admit he's impressed — I will. Keep going.",
    "You're in a rhythm. Make it day {N1}.",
    "Bo's quietly rooting against the streak. Spite him.",
    "{N} in a row is no accident. Prove it again.",
    "Don't get cocky — it's a real number. Go earn day {N1}.",
    "The streak's getting serious. So's the score. Handle both.",
  ],
  fire: [
    "{N} straight days. Bo's genuinely sweating. Don't stop now.",
    "A {N}-day streak on the line. No pressure. (Pressure.)",
    "You've come way too far to fumble today. 30 seconds.",
    "Bo's been telling people about your streak. Don't make him a liar.",
    "{N} days. Legendary. Keep the myth alive.",
    "This is where lesser players choke. Not you. Go.",
  ],
  close: [
    "You were ONE away yesterday. Bo hasn't shut up about it. Rematch?",
    "2/3 yesterday. So close it stung. Today's your redemption.",
    "One question stood between you and glory. Settle it.",
    "Bo's still laughing about that miss. Wipe the smile off.",
    "Yesterday: heartbreak. Today: a number to beat. Get even.",
    "You KNOW you had that last one. Prove it wasn't a fluke.",
  ],
};
function hashStr(s) { let h = 0; for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0; return Math.abs(h); }
function eBodyFor(uid, streak, everPlayed, wasClose) {
  const state = wasClose ? "close"
    : streak >= 7 ? "fire"
    : streak >= 3 ? "rolling"
    : streak >= 1 ? "fragile"
    : everPlayed ? "lapsed"
    : "never";
  const arr = E_BODIES[state];
  return arr[hashStr(uid + isoDateEastern(0)) % arr.length]
    .replace(/\{N1\}/g, String(streak + 1)).replace(/\{N\}/g, String(streak));
}

/** B — discovery/FOMO (Tue): >=3 taste events within 10mi this week (else nothing;
 * the tree's next leaf is the fallback). */
async function buildDiscoveryFomo(ctx) {
  const start = dateBoundaryISO(0), end = dateBoundaryISO(8);
  const out = new Map();
  for (const [uid, p] of ctx.profiles) {
    const loc = userLatLng(p);
    if (!loc) continue; // no location → nothing (falls to E)
    const cat = topCategory(p, ctx);
    if (cat) {
      const n = await tasteEventCount(cat, loc.lat, loc.lng, start, end);
      if (n >= 3) { out.set(uid, { type: "B", title: "Bo's been busy", body: `${n} ${cat} events within 10 miles of you this week`, dedupeKey: `B:${uid}:${isoDateEastern(0)}` }); continue; }
    }
    // No inferable taste (or too few in-category) → location-only default.
    const nAll = await tasteEventCount(null, loc.lat, loc.lng, start, end);
    if (nAll >= 3) out.set(uid, { type: "B", title: "Bo's been busy", body: `${nAll} events within 10 miles of you this week`, dedupeKey: `B:${uid}:${isoDateEastern(0)}` });
  }
  return out;
}
/** E — Daily Local nudge. The universal catch-all: the last leaf on every day,
 * for anyone who hasn't played yet today (subject to that day's activity gate).
 * Title = the state's score to beat; body varies by streak / yesterday. Replaces
 * the old C. */
async function buildDailyLocalReminder(ctx) {
  const out = new Map();
  for (const [uid, p] of ctx.profiles) {
    if (ctx.playedToday.has(uid)) continue;
    const top = ctx.topScoreByState.get(p.home_state) || 0;
    const title = top > 0 ? `Today's score to beat: ${top} 💛` : "Be the first on today's Daily Local 💛";
    const body = eBodyFor(uid, ctx.streaks.get(uid) || 0, ctx.everPlayed.has(uid), ctx.lastClose.has(uid));
    out.set(uid, { type: "E", title, body, dedupeKey: `E:${uid}:${isoDateEastern(0)}` });
  }
  return out;
}
/** F — taste weekend events (Thu): >=3 within 10mi Thu–Sun (else nothing; the
 * tree's next leaf, E, is the fallback). */
async function buildTasteWeekend(ctx) {
  const start = dateBoundaryISO(0), end = dateBoundaryISO(4);
  const out = new Map();
  for (const [uid, p] of ctx.profiles) {
    const loc = userLatLng(p);
    if (!loc) continue; // no location → nothing (falls to E)
    const cat = topCategory(p, ctx);
    if (cat) {
      const n = await tasteEventCount(cat, loc.lat, loc.lng, start, end);
      if (n >= 3) { out.set(uid, { type: "F", title: "Bo says you're welcome", body: `${n} ${cat} events within 10 miles of you this weekend`, dedupeKey: `F:${uid}:${isoDateEastern(0)}` }); continue; }
    }
    // No inferable taste (or too few in-category) → location-only default.
    const nAll = await tasteEventCount(null, loc.lat, loc.lng, start, end);
    if (nAll >= 3) out.set(uid, { type: "F", title: "Bo says you're welcome", body: `${nAll} events within 10 miles of you this weekend`, dedupeKey: `F:${uid}:${isoDateEastern(0)}` });
  }
  return out;
}

// --- per-weekday priority trees (first non-empty match per user wins) ---------
// A is prepended to every day (ungated utility). Activity-gate + B/F->C fallback
// live inside the individual builders once implemented.

async function treeForDay(day, ctx) {
  const A = await buildPlanReminders();
  switch (day) {
    case "sun": return [A, await buildSavedEventSoon("this_week"), await buildDailyLocalReminder(ctx)];
    case "tue": return [A, await buildDiscoveryFomo(ctx), await buildDailyLocalReminder(ctx)];
    case "thu": return [A, await buildSavedVenueWeekend(), await buildTasteWeekend(ctx), await buildDailyLocalReminder(ctx)];
    case "fri": return [A, await buildSavedEventSoon("weekend_edge"), await buildDailyLocalReminder(ctx)];
    // mon/wed/sat: gated plan reminder, then the Daily Local catch-all (E) — the
    // same broad "come play" nudge as Sunday. Non-A leaves gated on "didn't open
    // the app today", so E reaches only users who were away and haven't played.
    default:   return [A, await buildDailyLocalReminder(ctx)];
  }
}

// --- resolve priority + dedupe + send ----------------------------------------

async function main() {
  const secrets = await getSecrets(["ONESIGNAL_REST_API_KEY"]);
  SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;
  const restKey = secrets.ONESIGNAL_REST_API_KEY;

  const day = WEEKDAYS.includes(DAY_OVERRIDE) ? DAY_OVERRIDE : easternWeekday();
  console.log(`[push] day=${day}  env=${SUPABASE_URL.includes("xbyqwe") ? "DEV" : "PROD"}  mode=${SEND ? "SEND" : "DRY-RUN"}`);

  // Load shared context — every day now needs it (the Daily Local catch-all E
  // runs on all days except Tue/Fri, and the gate needs the activity map).
  const [profiles, activity, dl, interestByUser] = await Promise.all([loadProfiles(), loadActivity(), loadDailyLocal(), loadInteractionTaste()]);
  const ctx = { profiles, activity, ...dl, interestByUser };

  const layers = await treeForDay(day, ctx);

  // First-match-per-user wins (layers are in priority order).
  const chosen = new Map();
  for (const layer of layers) {
    for (const [uid, notif] of layer) {
      if (!chosen.has(uid)) chosen.set(uid, notif);
    }
  }

  // Activity gate: drop leaves for users active within the day's window. A (plan
  // reminder) is EXEMPT on the active days (sun/tue/thu/fri) but IS gated on the
  // SILENT days (mon/wed/sat) — there we only nudge users who didn't open the app
  // today, and A is the only leaf. Thu has no gate (weekend preview for everyone).
  const gateDays = { sun: 1, mon: 1, tue: 7, wed: 1, fri: 2, sat: 1 };
  const silentDays = ["mon", "wed", "sat"];
  if (gateDays[day]) {
    for (const [uid, n] of [...chosen]) {
      const gated = n.type !== "A" || silentDays.includes(day);
      if (gated && isActive(ctx.activity, uid, gateDays[day])) chosen.delete(uid);
    }
  }

  if (chosen.size === 0) {
    console.log("[push] no recipients today.");
    return;
  }

  // Drop anyone already sent this exact notification (idempotent reruns).
  const keys = [...chosen.values()].map((n) => `"${n.dedupeKey}"`).join(",");
  const already = new Set(
    (await sbGet(`push_sends?dedupe_key=in.(${keys})&select=user_id,dedupe_key`)).map((r) => `${r.user_id}|${r.dedupe_key}`),
  );

  let sent = 0, skipped = 0;
  for (const [uid, n] of chosen) {
    if (already.has(`${uid}|${n.dedupeKey}`)) { skipped++; continue; }
    console.log(`  ${n.type} -> ${uid}: ${n.title} | ${n.body}`);
    if (SEND) {
      const resp = await sendPush({ restKey, externalIds: [uid], title: n.title, body: n.body, path: n.path });
      console.log(`    OneSignal: id=${resp.id || "-"} recipients=${resp.recipients ?? "?"}${resp.errors ? " errors=" + JSON.stringify(resp.errors) : ""}`);
      if (resp.errors && !resp.id) { continue; }
      await sbInsert("push_sends", [{ user_id: uid, push_type: n.type, dedupe_key: n.dedupeKey, onesignal_id: resp.id || null, meta: { title: n.title } }]);
    }
    sent++;
  }
  console.log(`[push] ${SEND ? "sent" : "would send"} ${sent}, skipped ${skipped} (already sent).`);
}

main().catch((e) => { console.error(e); process.exit(1); });
