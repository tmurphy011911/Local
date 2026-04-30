/**
 * seed_trusted_domains_for_state.js — Use Claude to enumerate reputable local
 * news/event domains for a state and insert into bo_web_trusted_domains.
 *
 * Run when toggling a new state to is_live=true. The verifier joins on this
 * table at runtime so adding a state's media outlets requires no code change.
 *
 * Usage:
 *   STATE=NJ SUPABASE_SERVICE_KEY=ey... node scripts/seed_trusted_domains_for_state.js
 *
 * Idempotent: UNIQUE(domain) means re-running on an already-seeded state
 * is a no-op.
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

let SUPABASE_SERVICE_KEY, ANTHROPIC_API_KEY;
const STATE = (process.env.STATE || "").trim().toUpperCase();
if (!STATE || STATE.length !== 2) {
  console.error("STATE env var required (two-letter code, e.g. STATE=NJ)");
  process.exit(1);
}

async function fetchStateName() {
  const url = `${SUPABASE_URL}/rest/v1/states?code=eq.${STATE}&select=name`;
  const res = await fetch(url, {
    headers: { apikey: SUPABASE_SERVICE_KEY, Authorization: `Bearer ${SUPABASE_SERVICE_KEY}` },
  });
  if (!res.ok) throw new Error(`states fetch: ${res.status}`);
  const rows = await res.json();
  return rows[0]?.name;
}

async function askClaude(stateName) {
  const prompt = `Enumerate 10-15 reputable LOCAL news, magazine, and major-event domains for ${stateName} (${STATE}). Focus on outlets that regularly cover local events, restaurants, arts, and community happenings — not national-only outlets, blogs, or pure aggregators.

For each, classify into:
  - tier 3 (state media — e.g. major newspapers, magazines, NPR affiliates, local NBC/CBS/ABC)
  - tier 4 (local listings — neighborhood blogs, pure event listing sites, smaller magazines)

Skip if it's already on a global tier-1 list (eventbrite, ticketmaster, axs, livenation, opentable, resy, etc.) or a generic .gov/.edu (already covered as tier 2).

Respond with ONLY a JSON array, no prose:
[{"domain":"bostonglobe.com","tier":3,"notes":"flagship newspaper"},...]`;

  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "x-api-key": ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: "claude-sonnet-4-6",
      max_tokens: 2048,
      messages: [{ role: "user", content: prompt }],
    }),
  });
  if (!res.ok) throw new Error(`Claude ${res.status}: ${await res.text()}`);
  const data = await res.json();
  const text = data.content?.[0]?.text || "";
  const match = text.match(/\[[\s\S]*\]/);
  if (!match) throw new Error(`No JSON array in response: ${text.slice(0, 200)}`);
  return JSON.parse(match[0]);
}

async function probeDomain(domain) {
  // Light reachability check — HEAD with 5s timeout. Skip if 404/dns/timeout.
  const url = `https://${domain.replace(/^https?:\/\//, "")}`;
  try {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 5000);
    const res = await fetch(url, { method: "HEAD", redirect: "follow", signal: ctrl.signal });
    clearTimeout(t);
    return res.ok || res.status === 405; // some sites 405 HEAD; treat as alive
  } catch {
    return false;
  }
}

async function insert(rows) {
  const sbHeaders = {
    apikey: SUPABASE_SERVICE_KEY,
    Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
    "Content-Type": "application/json",
    Prefer: "return=minimal,resolution=ignore-duplicates",
  };
  const res = await fetch(`${SUPABASE_URL}/rest/v1/bo_web_trusted_domains`, {
    method: "POST",
    headers: sbHeaders,
    body: JSON.stringify(rows),
  });
  return res.ok || res.status === 409;
}

async function main() {
  const secrets = await getSecrets(["ANTHROPIC_API_KEY"]);
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;
  ANTHROPIC_API_KEY = secrets.ANTHROPIC_API_KEY || process.env.ANTHROPIC_API_KEY;
  if (!ANTHROPIC_API_KEY) { console.error("Missing ANTHROPIC_API_KEY"); process.exit(1); }

  const stateName = await fetchStateName();
  if (!stateName) { console.error(`State ${STATE} not in states table`); process.exit(1); }

  console.log(`Seeding trusted domains for ${stateName} (${STATE})...`);
  const candidates = await askClaude(stateName);
  console.log(`Claude returned ${candidates.length} candidates`);

  const validated = [];
  for (const c of candidates) {
    if (!c.domain || !c.tier) continue;
    const domain = c.domain.toLowerCase().replace(/^https?:\/\//, "").replace(/\/$/, "");
    if (!/^[a-z0-9.-]+\.[a-z]{2,}$/i.test(domain)) continue;
    if (![3, 4].includes(c.tier)) continue;
    process.stdout.write(`  Probing ${domain}... `);
    const alive = await probeDomain(domain);
    if (!alive) { console.log("dead/unreachable, skipping"); continue; }
    console.log("✓");
    validated.push({
      domain,
      tier: c.tier,
      state_code: STATE,
      notes: c.notes || null,
    });
  }

  if (validated.length === 0) {
    console.log("No new domains to insert.");
    return;
  }
  const ok = await insert(validated);
  console.log(`\nInserted (or skipped duplicates) ${validated.length} domains for ${STATE}: ${ok ? "OK" : "FAIL"}`);
}

main().catch((err) => { console.error("Fatal:", err); process.exit(1); });
