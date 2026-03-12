/**
 * deactivate_past_events.js — Mark past events as inactive
 *
 * Updates all events where start_at < NOW() and is_active = true,
 * setting is_active = false.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/deactivate_past_events.js
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

let SUPABASE_SERVICE_KEY;

async function main() {
  const secrets = await getSecrets([]);
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  const now = new Date().toISOString();

  const res = await fetch(
    `${SUPABASE_URL}/rest/v1/events?start_at=lt.${now}&is_active=eq.true`,
    {
      method: "PATCH",
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
        "Content-Type": "application/json",
        Prefer: "return=representation",
      },
      body: JSON.stringify({ is_active: false }),
    }
  );

  if (!res.ok) {
    const body = await res.text();
    console.error(`Supabase error ${res.status}: ${body}`);
    process.exit(1);
  }

  const updated = await res.json();
  console.log(`Deactivated ${updated.length} past event(s).`);
}

main();
