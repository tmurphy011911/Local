#!/usr/bin/env node
// Two-device collab E2E helper. Scoped ENTIRELY to the testbypass@ account.
//
//   reset-seed  : wipe A's invite-award state + prior test plans, seed one fresh
//                 plan (creator=A, group_size=2). Prints PLAN_ID=<uuid>.
//   verify      : assert A earned the INVITE_ACCEPTED milestone (1 / 2500 pts).
//
// Requires SUPABASE_SERVICE_KEY in env (the service_role key). That key is
// flagged for rotation — do NOT hardcode it here.
//
// Why a seed step at all: A can't build a shareable plan through the UI in the
// emulator (Places autocomplete is unreachable under Maestro), and the 2,500
// award is one-time/monotonic, so each run must reset A's counters or it never
// re-fires. See PhoneAuthViewModel/BadgeService for the bypass + award path.

const URL = "https://blifubxslaucshmlphdc.supabase.co";
const KEY = process.env.SUPABASE_SERVICE_KEY;
const A = "42df5c2c-6cff-4846-94a0-d03f4b06e569"; // testbypass@localapp.live
const B = "01f3cd4f-ee44-48a4-b03f-d63369efcfd8"; // testbypass+b@localapp.live
const PLAN_NAME = "Collab E2E Plan";
const AWARD_ACTIONS = "(invite_accepted,group_5_confirmed,group_10_confirmed)";

if (!KEY) {
  console.error("ERROR: SUPABASE_SERVICE_KEY not set in env.");
  process.exit(2);
}

const h = (extra = {}) => ({
  apikey: KEY,
  Authorization: `Bearer ${KEY}`,
  "Content-Type": "application/json",
  ...extra,
});

async function rest(method, path, body, prefer) {
  const headers = h(prefer ? { Prefer: prefer } : {});
  const res = await fetch(`${URL}/rest/v1/${path}`, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) {
    throw new Error(`${method} ${path} -> ${res.status}: ${text}`);
  }
  return text ? JSON.parse(text) : null;
}

async function ownedPlanIds() {
  const rows = await rest("GET", `plans?user_id=eq.${A}&select=id`);
  return rows.map((r) => r.id);
}

async function resetSeed() {
  // 1. Clear A's award counters + earned badges for the collab actions so the
  //    monotonic ratchet re-fires from zero.
  await rest("DELETE", `user_action_counts?user_id=eq.${A}&action_type=in.${AWARD_ACTIONS}`);
  await rest("DELETE", `user_badges?user_id=eq.${A}&action_type=in.${AWARD_ACTIONS}`);

  // 2. Remove prior test plans + their attendees (B's accept rows included).
  //    Accepting an invite gives B their OWN copy of the plan (same title/city),
  //    so we must delete test plans owned by BOTH A and B — otherwise B's
  //    leftover copy makes userAlreadyHasPlan() (matches on title+city) route B
  //    to their own copy instead of the accept preview, and "Accept Invite"
  //    never appears. We also clear B's plan_accepts so the RPC's
  //    already_accepted guard can't trip on a stale token.
  const priorTest = await rest(
    "GET",
    `plans?user_id=in.(${A},${B})&name=like.${encodeURIComponent(PLAN_NAME + "%")}&select=id`,
  );
  const priorIds = priorTest.map((r) => r.id);
  for (const id of priorIds) {
    await rest("DELETE", `plan_attendees?plan_id=eq.${id}`);
    await rest("DELETE", `plans?id=eq.${id}`);
  }
  // B is a dedicated test account used only for this E2E — clear all its
  // accept records (old plan ids are now deleted/orphaned anyway).
  await rest("DELETE", `plan_accepts?user_id=eq.${B}`);

  // 3. Guard: the award snapshot sums non-creator confirmed accepts across ALL
  //    of A's owned plans. Stray accepts on non-test plans would inflate the
  //    milestone (e.g. 0->3 = "3 Invites Accepted! +7,500"), breaking the
  //    deterministic 2,500 assertion. Abort loudly rather than mis-toast.
  const remaining = await ownedPlanIds();
  if (remaining.length) {
    const inList = `(${remaining.join(",")})`;
    const strays = await rest(
      "GET",
      `plan_attendees?plan_id=in.${inList}&is_creator=eq.false&confirmed=eq.true&select=plan_id`,
    );
    if (strays.length) {
      console.error(
        `ERROR: ${strays.length} stray confirmed accept(s) on A's other plans; ` +
          `the 2,500 assertion would be wrong. Clean these first.`,
      );
      process.exit(3);
    }
  }

  // 4. Seed one fresh plan (group_size=2 -> "1 person unconfirmed" pre-accept).
  const [plan] = await rest(
    "POST",
    "plans",
    { user_id: A, name: PLAN_NAME, city: "Boston", days: [], group_size: 2 },
    "return=representation",
  );
  await rest("POST", "plan_attendees", {
    plan_id: plan.id,
    user_id: A,
    name: "You",
    is_creator: true,
    confirmed: true,
  });

  console.log(`PLAN_ID=${plan.id}`);
}

async function verify() {
  const badges = await rest(
    "GET",
    `user_badges?user_id=eq.${A}&action_type=eq.invite_accepted&select=milestone,points`,
  );
  const counts = await rest(
    "GET",
    `user_action_counts?user_id=eq.${A}&action_type=eq.invite_accepted&select=count`,
  );
  const m1 = badges.find((b) => b.milestone === 1);
  const okBadge = m1 && m1.points === 2500;
  const okCount = counts.length && counts[0].count >= 1;

  // Specifically prove the server-side plan_attendees insert (the RPC fix in
  // migration 20260610170000). The badge above is written by the RPC even if
  // the attendee insert were missing, so it alone wouldn't catch a regression
  // here — assert B's confirmed, non-creator row exists on A's test plan.
  const [testPlan] = await rest(
    "GET",
    `plans?user_id=eq.${A}&name=eq.${encodeURIComponent(PLAN_NAME)}&select=id&order=created_at.desc&limit=1`,
  );
  let okGuestRow = false;
  if (testPlan) {
    const guest = await rest(
      "GET",
      `plan_attendees?plan_id=eq.${testPlan.id}&user_id=eq.${B}&is_creator=eq.false&confirmed=eq.true&select=user_id`,
    );
    okGuestRow = guest.length >= 1;
  }

  console.log(
    `badges=${JSON.stringify(badges)} counts=${JSON.stringify(counts)} guestRow=${okGuestRow}`,
  );
  if (okBadge && okCount && okGuestRow) {
    console.log("VERIFY_OK: A earned INVITE_ACCEPTED milestone 1 (+2500); B's attendee row present.");
  } else {
    console.error(
      "VERIFY_FAIL: expected milestone 1 / 2500 pts, count>=1, and B's confirmed attendee row.",
    );
    process.exit(4);
  }
}

const cmd = process.argv[2];
if (cmd === "reset-seed") await resetSeed();
else if (cmd === "verify") await verify();
else {
  console.error("usage: collab_reset_seed.mjs reset-seed|verify");
  process.exit(1);
}
