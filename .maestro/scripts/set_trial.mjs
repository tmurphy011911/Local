#!/usr/bin/env node
// Toggle the canned testbypass@ account between FREE (trial expired) and PREMIUM
// (active trial) so the paywall-gating flow can run, then restore premium so the
// rest of the suite (which assumes premium) keeps working.
//
//   set_trial.mjs free      -> trial_ends_at in the past  (isPremium=false, gates ON)
//   set_trial.mjs premium   -> trial_ends_at in the future (isPremium=true,  gates OFF)
//   set_trial.mjs seed-plan -> insert one plan owned by A so gate 6 (open plan) is testable
//
// Requires SUPABASE_SERVICE_KEY in env (service_role; flagged for rotation).

const URL = "https://blifubxslaucshmlphdc.supabase.co";
const KEY = process.env.SUPABASE_SERVICE_KEY;
const A = "42df5c2c-6cff-4846-94a0-d03f4b06e569"; // testbypass@localapp.live
const PLAN_NAME = "Paywall Gate Plan";

if (!KEY) {
  console.error("ERROR: SUPABASE_SERVICE_KEY not set.");
  process.exit(2);
}
const h = (extra = {}) => ({ apikey: KEY, Authorization: `Bearer ${KEY}`, "Content-Type": "application/json", ...extra });
async function rest(method, path, body, prefer) {
  const res = await fetch(`${URL}/rest/v1/${path}`, { method, headers: h(prefer ? { Prefer: prefer } : {}), body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status}: ${text}`);
  return text ? JSON.parse(text) : null;
}

const cmd = process.argv[2];
if (cmd === "free") {
  await rest("PATCH", `user_profiles?id=eq.${A}`, { trial_ends_at: "2020-01-01T00:00:00Z" });
  console.log("TRIAL=free (expired 2020-01-01) — isPremium=false, gates ON");
} else if (cmd === "premium") {
  // 30 days out, computed at call time (plain node, Date is allowed here).
  const future = new Date(Date.now() + 30 * 86400_000).toISOString();
  await rest("PATCH", `user_profiles?id=eq.${A}`, { trial_ends_at: future });
  console.log(`TRIAL=premium (ends ${future}) — isPremium=true, gates OFF`);
} else if (cmd === "seed-plan") {
  // Remove any prior gate plan, then insert one so gate 6 (open plan) is testable.
  const prior = await rest("GET", `plans?user_id=eq.${A}&name=eq.${encodeURIComponent(PLAN_NAME)}&select=id`);
  for (const p of prior) {
    await rest("DELETE", `plan_attendees?plan_id=eq.${p.id}`);
    await rest("DELETE", `plans?id=eq.${p.id}`);
  }
  const [plan] = await rest("POST", "plans", { user_id: A, name: PLAN_NAME, city: "Boston", days: [], group_size: 1 }, "return=representation");
  await rest("POST", "plan_attendees", { plan_id: plan.id, user_id: A, name: "You", is_creator: true, confirmed: true });
  console.log(`SEEDED plan ${plan.id} ("${PLAN_NAME}")`);
} else {
  console.error("usage: set_trial.mjs free|premium|seed-plan");
  process.exit(1);
}
