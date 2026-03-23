/**
 * seed_state_grid.js — Use Claude to generate grid center points for state coverage
 *
 * Generates overlapping 50km radius circles to cover an entire state for
 * Google Places API venue discovery. Points are stored in state_grid_centers.
 *
 * Usage:
 *   STATE=CT SUPABASE_SERVICE_KEY=ey... node scripts/seed_state_grid.js
 *   SUPABASE_SERVICE_KEY=ey... node scripts/seed_state_grid.js          # auto-seeds all live states missing grids
 *   STATE=MA FORCE=1 SUPABASE_SERVICE_KEY=ey... node scripts/seed_state_grid.js  # re-generate even if exists
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

const STATE = process.env.STATE || null;
const FORCE = process.env.FORCE === "1";
const MODEL = "claude-sonnet-4-20250514";

let ANTHROPIC_API_KEY, SUPABASE_SERVICE_KEY;

async function fetchStatesNeedingGrids() {
  // If STATE is specified, just return that one state
  if (STATE) {
    const res = await fetch(
      `${SUPABASE_URL}/rest/v1/states?code=eq.${STATE}&select=code,name`,
      {
        headers: {
          apikey: SUPABASE_SERVICE_KEY,
          Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
        },
      }
    );
    if (!res.ok) {
      console.error(`Failed to query states table: ${res.status} ${await res.text()}`);
      process.exit(1);
    }
    const states = await res.json();
    if (states.length === 0) {
      console.error(`State "${STATE}" not found in states table.`);
      process.exit(1);
    }
    return states;
  }

  // No STATE specified — find all live states
  const res = await fetch(
    `${SUPABASE_URL}/rest/v1/states?is_live=eq.true&select=code,name`,
    {
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      },
    }
  );
  if (!res.ok) {
    console.error(`Failed to fetch live states: ${res.status} ${await res.text()}`);
    process.exit(1);
  }
  return await res.json();
}

async function fetchExistingGridCenters(stateCode) {
  const res = await fetch(
    `${SUPABASE_URL}/rest/v1/state_grid_centers?state_code=eq.${stateCode}&select=label,lat,lng`,
    {
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      },
    }
  );
  if (!res.ok) return [];
  return await res.json();
}

async function generateGridCenters(stateCode, stateName) {
  const prompt = `I need to cover the entire state of ${stateName} (${stateCode}) with overlapping circles of 50km (31 mile) radius for a Google Places API nearby search. Generate the minimum number of center points needed to ensure every part of the state is within at least one circle's radius. For each point, provide a descriptive label (e.g. 'Hartford/Central', 'New Haven/South'), latitude, and longitude. Smaller states may need only 2-3 points, larger states may need 5-8. Return as a JSON array: [{label, lat, lng}]`;

  const res = await fetch("https://api.anthropic.com/v1/messages", {
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

  if (!res.ok) {
    const body = await res.text();
    console.error(`Claude API error: ${res.status} ${body.slice(0, 300)}`);
    process.exit(1);
  }

  const data = await res.json();
  const responseText = data.content?.[0]?.text || "";

  let centers;
  try {
    const jsonMatch = responseText.match(/\[[\s\S]*\]/);
    if (!jsonMatch) throw new Error("No JSON array found in response");
    centers = JSON.parse(jsonMatch[0]);
  } catch (err) {
    console.error(`Failed to parse Claude response: ${err.message}`);
    console.error(`Response was: ${responseText.slice(0, 500)}`);
    process.exit(1);
  }

  return centers;
}

async function insertGridCenters(stateCode, centers) {
  const rows = centers.map((c) => ({
    state_code: stateCode,
    label: c.label,
    lat: c.lat,
    lng: c.lng,
  }));

  const res = await fetch(`${SUPABASE_URL}/rest/v1/state_grid_centers`, {
    method: "POST",
    headers: {
      apikey: SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      "Content-Type": "application/json",
      Prefer: "return=representation",
    },
    body: JSON.stringify(rows),
  });

  if (!res.ok) {
    const body = await res.text();
    console.error(`Failed to insert grid centers: ${res.status} ${body}`);
    process.exit(1);
  }

  return await res.json();
}

async function main() {
  const secrets = await getSecrets(["ANTHROPIC_API_KEY"]);
  ANTHROPIC_API_KEY = secrets.ANTHROPIC_API_KEY;
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  if (!ANTHROPIC_API_KEY) {
    console.error("Missing ANTHROPIC_API_KEY. Add it to the Supabase secrets table or pass as env var.");
    process.exit(1);
  }

  console.log("=== Seed State Grid Centers ===\n");

  const states = await fetchStatesNeedingGrids();
  console.log(`States to process: ${states.map((s) => `${s.code} (${s.name})`).join(", ")}\n`);

  let totalInserted = 0;

  for (const state of states) {
    console.log(`--- ${state.code} (${state.name}) ---`);

    // Check existing grid centers
    const existing = await fetchExistingGridCenters(state.code);

    if (existing.length > 0 && !FORCE) {
      console.log(`  Already has ${existing.length} grid center(s): ${existing.map((c) => c.label).join(", ")}`);
      console.log("  Skipping (use FORCE=1 to regenerate).\n");
      continue;
    }

    if (existing.length > 0 && FORCE) {
      console.log(`  Has ${existing.length} existing grid center(s) — FORCE=1, deleting and regenerating...`);
      const delRes = await fetch(
        `${SUPABASE_URL}/rest/v1/state_grid_centers?state_code=eq.${state.code}`,
        {
          method: "DELETE",
          headers: {
            apikey: SUPABASE_SERVICE_KEY,
            Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
          },
        }
      );
      if (!delRes.ok) {
        console.error(`  Failed to delete existing centers: ${delRes.status} ${await delRes.text()}`);
        continue;
      }
    }

    // Generate grid centers via Claude
    console.log("  Asking Claude for grid center points...");
    const centers = await generateGridCenters(state.code, state.name);

    console.log(`  Claude suggested ${centers.length} center(s):`);
    for (const c of centers) {
      console.log(`    ${c.label} (${c.lat}, ${c.lng})`);
    }

    // Insert into DB
    const inserted = await insertGridCenters(state.code, centers);
    console.log(`  Inserted ${inserted.length} grid center(s).\n`);
    totalInserted += inserted.length;
  }

  console.log(`\n=== Done. Total inserted: ${totalInserted} ===`);
}

main().catch((err) => {
  console.error("Fatal error:", err);
  process.exit(1);
});
