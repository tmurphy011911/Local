/**
 * secrets.js — Fetch API keys from Supabase secrets table
 *
 * Usage:
 *   const { getSecrets } = require("./lib/secrets");
 *   const secrets = await getSecrets(["ANTHROPIC_API_KEY", "GOOGLE_PLACES_API_KEY"]);
 *
 * Keys are fetched from the `secrets` table in Supabase.
 * SUPABASE_SERVICE_KEY must be available as an env var (the one key that bootstraps everything).
 * All other keys are pulled from the DB.
 *
 * Falls back to environment variables if the DB fetch fails.
 */

const SUPABASE_URL = "https://blifubxslaucshmlphdc.supabase.co";

async function getSecrets(keyNames) {
  const serviceKey = (process.env.SUPABASE_SERVICE_KEY || "").replace(/\s/g, "");

  if (!serviceKey) {
    console.error("Missing SUPABASE_SERVICE_KEY env var (needed to bootstrap secrets)");
    process.exit(1);
  }

  const result = { SUPABASE_SERVICE_KEY: serviceKey };

  try {
    const filter = keyNames.map((k) => `"${k}"`).join(",");
    const url = `${SUPABASE_URL}/rest/v1/secrets?key=in.(${filter})&select=key,value`;

    const res = await fetch(url, {
      headers: {
        apikey: serviceKey,
        Authorization: `Bearer ${serviceKey}`,
      },
    });

    if (!res.ok) {
      const body = await res.text();
      console.warn(`Warning: Could not fetch secrets from DB (${res.status}). Falling back to env vars.`);
      console.warn(`  ${body.substring(0, 200)}`);
      for (const k of keyNames) {
        result[k] = (process.env[k] || "").replace(/\s/g, "");
      }
      return result;
    }

    const rows = await res.json();
    const dbSecrets = {};
    for (const row of rows) {
      dbSecrets[row.key] = row.value;
    }

    // Use DB value if available, fall back to env var
    for (const k of keyNames) {
      result[k] = dbSecrets[k] || (process.env[k] || "").replace(/\s/g, "");
      if (!result[k]) {
        console.warn(`Warning: Secret "${k}" not found in DB or env vars`);
      }
    }
  } catch (err) {
    console.warn(`Warning: Failed to fetch secrets (${err.message}). Falling back to env vars.`);
    for (const k of keyNames) {
      result[k] = (process.env[k] || "").replace(/\s/g, "");
    }
  }

  return result;
}

module.exports = { getSecrets, SUPABASE_URL };
