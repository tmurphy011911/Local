/**
 * seed_metros.js — Use Claude to identify major metro areas for a state
 *
 * Queries the states table to verify the state exists, checks what metros
 * already exist, uses Claude to identify major metro areas, and inserts new ones.
 *
 * Usage:
 *   STATE=CT SUPABASE_SERVICE_KEY=ey... node scripts/seed_metros.js
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

const STATE = process.env.STATE;
const MODEL = "claude-sonnet-4-20250514";

let ANTHROPIC_API_KEY, SUPABASE_SERVICE_KEY;

async function main() {
  if (!STATE) {
    console.error("Missing STATE env var (e.g. STATE=CT)");
    process.exit(1);
  }

  const secrets = await getSecrets(["ANTHROPIC_API_KEY"]);
  ANTHROPIC_API_KEY = secrets.ANTHROPIC_API_KEY;
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  if (!ANTHROPIC_API_KEY) {
    console.error("Missing ANTHROPIC_API_KEY. Add it to the Supabase secrets table or pass as env var.");
    process.exit(1);
  }

  console.log(`=== Seed Metros for ${STATE} ===\n`);

  // 1. Verify state exists and is live
  const stateRes = await fetch(
    `${SUPABASE_URL}/rest/v1/states?code=eq.${STATE}&select=code,name,is_live`,
    {
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      },
    }
  );

  if (!stateRes.ok) {
    console.error(`Failed to query states table: ${stateRes.status} ${await stateRes.text()}`);
    process.exit(1);
  }

  const states = await stateRes.json();
  if (states.length === 0) {
    console.error(`State "${STATE}" not found in states table.`);
    process.exit(1);
  }

  const state = states[0];
  if (!state.is_live) {
    console.warn(`Warning: State "${STATE}" (${state.name}) is not marked as is_live.`);
  }

  console.log(`State: ${state.name} (${state.code})`);

  // 2. Check existing metros for this state
  const existingRes = await fetch(
    `${SUPABASE_URL}/rest/v1/metros?state_code=eq.${STATE}&select=name,lat,lng`,
    {
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      },
    }
  );

  if (!existingRes.ok) {
    console.error(`Failed to query metros table: ${existingRes.status} ${await existingRes.text()}`);
    process.exit(1);
  }

  const existingMetros = await existingRes.json();
  const existingNames = new Set(existingMetros.map((m) => m.name.toLowerCase()));
  console.log(`Existing metros: ${existingMetros.length} (${existingMetros.map((m) => m.name).join(", ") || "none"})\n`);

  // 3. Ask Claude for major metro areas
  console.log("Asking Claude for major metro areas...");

  const prompt = `List the 2-5 major metro areas in ${state.name} that would have significant local event activity (concerts, sports, theater, arts, food festivals). For each, provide the city name, latitude, and longitude. Return as JSON array with objects containing "name" (string), "lat" (number), "lng" (number). Return ONLY the JSON array, no other text.`;

  const claudeRes = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "x-api-key": ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
      "content-type": "application/json",
    },
    body: JSON.stringify({
      model: MODEL,
      max_tokens: 1024,
      messages: [{ role: "user", content: prompt }],
    }),
  });

  if (!claudeRes.ok) {
    const body = await claudeRes.text();
    console.error(`Claude API error: ${claudeRes.status} ${body.slice(0, 300)}`);
    process.exit(1);
  }

  const claudeData = await claudeRes.json();
  const responseText = claudeData.content?.[0]?.text || "";

  let metros;
  try {
    const jsonMatch = responseText.match(/\[[\s\S]*\]/);
    if (!jsonMatch) throw new Error("No JSON array found in response");
    metros = JSON.parse(jsonMatch[0]);
  } catch (err) {
    console.error(`Failed to parse Claude response: ${err.message}`);
    console.error(`Response was: ${responseText.slice(0, 500)}`);
    process.exit(1);
  }

  console.log(`Claude suggested ${metros.length} metros:\n`);
  for (const m of metros) {
    console.log(`  ${m.name} (${m.lat}, ${m.lng})`);
  }

  // 4. Insert new metros
  const newMetros = metros.filter((m) => !existingNames.has(m.name.toLowerCase()));

  if (newMetros.length === 0) {
    console.log("\nAll suggested metros already exist. Nothing to insert.");
    return;
  }

  console.log(`\nInserting ${newMetros.length} new metro(s)...`);

  const rows = newMetros.map((m) => ({
    state_code: STATE,
    name: m.name,
    lat: m.lat,
    lng: m.lng,
  }));

  const insertRes = await fetch(`${SUPABASE_URL}/rest/v1/metros`, {
    method: "POST",
    headers: {
      apikey: SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      "Content-Type": "application/json",
      Prefer: "return=representation",
    },
    body: JSON.stringify(rows),
  });

  if (!insertRes.ok) {
    const body = await insertRes.text();
    console.error(`Failed to insert metros: ${insertRes.status} ${body}`);
    process.exit(1);
  }

  const inserted = await insertRes.json();
  console.log(`\nInserted ${inserted.length} metro(s):`);
  for (const m of inserted) {
    console.log(`  ${m.name} (${m.lat}, ${m.lng}) — id: ${m.id}`);
  }
}

main().catch((err) => {
  console.error("Fatal error:", err);
  process.exit(1);
});
