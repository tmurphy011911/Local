/**
 * seed_dev_test_users.js — Provision the reserved dev-auth "existing user" on the
 * DEV Supabase project so it always lands in the skip-onboarding flow.
 *
 * The app's dev-auth mode (debug build + dev backend) maps each phone number to
 * `dev+<digits>@localapp.live` and signs-in-or-signs-up. Any NEW number runs
 * onboarding the first time and is recognized as existing afterward. This script
 * pre-seeds Config.DEV_EXISTING_USER_PHONE (5555550100) with a COMPLETED profile
 * so it's an existing user from the very first sign-in — no onboarding needed.
 *
 * Idempotent: re-run any time (e.g. after a dev DB wipe). Safe — DEV only.
 *
 * Usage:
 *   DEV_SUPABASE_URL=https://xbyqwekcanrzovytxmdv.supabase.co \
 *   DEV_SUPABASE_SERVICE_KEY=<dev_service_key> \
 *   node scripts/seed_dev_test_users.js
 */

const URL = (process.env.DEV_SUPABASE_URL || "").replace(/\/$/, "");
const KEY = (process.env.DEV_SUPABASE_SERVICE_KEY || "").replace(/\s/g, "");
const PASSWORD = "LocalTestBypass!2026"; // mirrors Config.TEST_AUTH_PASSWORD

// (phone, profile) pairs to seed as existing users. Add more as needed.
const USERS = [
  { phone: "5555550100", firstName: "Dev", city: "Boston", userLat: 42.3601, userLng: -71.0589 },
];

function guard() {
  if (!URL || !KEY) {
    console.error("Missing DEV_SUPABASE_URL / DEV_SUPABASE_SERVICE_KEY.");
    process.exit(1);
  }
  if (URL.includes("blifubxslaucshmlphdc")) {
    console.error("Refusing to run against PROD. This is a dev-only seeder.");
    process.exit(1);
  }
}

const headers = { apikey: KEY, Authorization: `Bearer ${KEY}`, "Content-Type": "application/json" };
const emailFor = (phone) => `dev+${phone.replace(/\D/g, "")}@localapp.live`;

// Create the auth user (email pre-confirmed), or fetch its id if it already exists.
async function ensureAuthUser(email) {
  const create = await fetch(`${URL}/auth/v1/admin/users`, {
    method: "POST",
    headers,
    body: JSON.stringify({ email, password: PASSWORD, email_confirm: true }),
  });
  if (create.ok) {
    const u = await create.json();
    return u.id;
  }
  // Already exists (or other) → look it up via the admin list filter.
  const list = await fetch(`${URL}/auth/v1/admin/users?filter=${encodeURIComponent(email)}`, { headers });
  if (!list.ok) throw new Error(`lookup ${email} -> ${list.status} ${(await list.text()).slice(0, 200)}`);
  const body = await list.json();
  const found = (body.users || []).find((u) => u.email === email);
  if (!found) throw new Error(`could not create or find auth user ${email} (create ${create.status})`);
  return found.id;
}

// Upsert a COMPLETED user_profiles row keyed on the auth id.
async function upsertProfile(id, u, email) {
  const row = {
    id,
    first_name: u.firstName,
    phone: u.phone,
    email,
    city: u.city,
    user_lat: u.userLat,
    user_lng: u.userLng,
    onboarding_complete: true,
  };
  const res = await fetch(`${URL}/rest/v1/user_profiles`, {
    method: "POST",
    headers: { ...headers, Prefer: "resolution=merge-duplicates,return=minimal" },
    body: JSON.stringify(row),
  });
  if (!res.ok) throw new Error(`upsert profile ${email} -> ${res.status} ${(await res.text()).slice(0, 300)}`);
}

async function main() {
  guard();
  console.log(`Seeding dev test users into ${URL}\n`);
  for (const u of USERS) {
    const email = emailFor(u.phone);
    const id = await ensureAuthUser(email);
    await upsertProfile(id, u, email);
    console.log(`  ✓ ${u.phone} -> ${email} (id ${id}) — existing user, onboarding complete`);
  }
  console.log("\nDone.");
}

main().catch((e) => {
  console.error(`Fatal: ${e.message}`);
  process.exit(1);
});
