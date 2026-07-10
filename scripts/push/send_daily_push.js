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

// --- notification builders ----------------------------------------------------
// Each returns Map<uid, { title, body, path, dedupeKey, type }>. Priority is the
// ARRAY ORDER in the per-day tree below; first match per uid wins.

/**
 * A — plan reminder. Ungated, every day. "{Plan} starts tomorrow."
 * plans.date = tomorrow -> notify plans.user_id (the owner).
 */
async function buildPlanReminders() {
  const tomorrow = isoDateEastern(1);
  const plans = await sbGet(`plans?date=eq.${tomorrow}&select=id,user_id,title`);
  const out = new Map();
  for (const p of plans) {
    if (!p.user_id) continue;
    out.set(p.user_id, {
      type: "A",
      title: `"${p.title}" starts tomorrow`,
      body: "Bo is here if you need any last minute changes or recommendations",
      path: `/p/${p.id}`,
      dedupeKey: `A:plan:${p.id}:${tomorrow}`,
    });
  }
  return out;
}

// --- TODO: builders below need their targeting queries verified against the
// live dev schema (events start_at/category/is_active/coords; community_interactions
// entity columns; user_taste_profiles.category_scores; quiz_completions; auth
// last_sign_in_at for the activity gate; user_profiles home_state + lat/lng +
// 10-mi radius). Stubbed to return no recipients so the engine runs end-to-end
// with A today; filled in next, notification by notification.
async function buildSavedEventSoon(_window) { return new Map(); }        // D
async function buildDiscoveryFomo() { return new Map(); }                // B
async function buildDailyLocalSocialProof() { return new Map(); }        // C  (needs Daily Local shipped)
async function buildDailyLocalReminder() { return new Map(); }           // E  (needs Daily Local shipped)
async function buildTasteWeekend() { return new Map(); }                 // F
async function buildSavedVenueWeekend() { return new Map(); }            // G

// --- per-weekday priority trees (first non-empty match per user wins) ---------
// A is prepended to every day (ungated utility). Activity-gate + B/F->C fallback
// live inside the individual builders once implemented.

async function treeForDay(day) {
  const A = await buildPlanReminders();
  switch (day) {
    case "sun": return [A, await buildSavedEventSoon("this_week"), await buildDailyLocalReminder()];
    case "tue": return [A, await buildDiscoveryFomo(), await buildDailyLocalSocialProof()];
    case "thu": return [A, await buildSavedVenueWeekend(), await buildTasteWeekend(), await buildDailyLocalReminder()];
    case "fri": return [A, await buildSavedEventSoon("weekend_edge")];
    // mon/wed/sat: silent except A (utility).
    default:   return [A];
  }
}

// --- resolve priority + dedupe + send ----------------------------------------

async function main() {
  const secrets = await getSecrets(["ONESIGNAL_REST_API_KEY"]);
  SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;
  const restKey = secrets.ONESIGNAL_REST_API_KEY;

  const day = WEEKDAYS.includes(DAY_OVERRIDE) ? DAY_OVERRIDE : easternWeekday();
  console.log(`[push] day=${day}  env=${SUPABASE_URL.includes("xbyqwe") ? "DEV" : "PROD"}  mode=${SEND ? "SEND" : "DRY-RUN"}`);

  const layers = await treeForDay(day);

  // First-match-per-user wins (layers are in priority order).
  const chosen = new Map();
  for (const layer of layers) {
    for (const [uid, notif] of layer) {
      if (!chosen.has(uid)) chosen.set(uid, notif);
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
      if (resp.errors) { console.warn(`    OneSignal errors: ${JSON.stringify(resp.errors)}`); continue; }
      await sbInsert("push_sends", [{ user_id: uid, push_type: n.type, dedupe_key: n.dedupeKey, meta: { title: n.title } }]);
    }
    sent++;
  }
  console.log(`[push] ${SEND ? "sent" : "would send"} ${sent}, skipped ${skipped} (already sent).`);
}

main().catch((e) => { console.error(e); process.exit(1); });
