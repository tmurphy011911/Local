// Nightly builder for the collaborative feature-affinity matrix.
//
// Reads community_interactions (all users, windowed) joined to event features,
// builds each user's engaged-feature set, computes co-occurrence (math in
// feature_affinity_model.js — unit-tested), and rebuilds the feature_affinity
// table. Aggregate only — no per-user rows are written. Service key only.
//
// ⚠️ scripts/lib/secrets.js defaults SUPABASE_URL to PROD — set it explicitly.
// Env: SUPABASE_SERVICE_KEY (req), SUPABASE_URL, WINDOW_DAYS=365, DRY_RUN=1.

const { SUPABASE_URL } = require("../lib/secrets");
const { computeFeatureAffinity } = require("./feature_affinity_model");

const WINDOW_DAYS = parseInt(process.env.WINDOW_DAYS || "365", 10);
const DRY_RUN = process.env.DRY_RUN === "1";
const SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").replace(/\s/g, "");
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function headers() {
  return { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` };
}

async function fetchInteractions() {
  const sinceIso = new Date(Date.now() - WINDOW_DAYS * 86400000).toISOString();
  const pageSize = 1000;
  let offset = 0;
  const rows = [];
  while (true) {
    const url = `${SUPABASE_URL}/rest/v1/community_interactions` +
      `?created_at=gte.${sinceIso}&item_type=eq.event` +
      `&select=user_id,item_id&order=created_at.desc&limit=${pageSize}&offset=${offset}`;
    const res = await fetch(url, { headers: headers() });
    if (!res.ok) throw new Error(`interactions ${res.status}: ${await res.text()}`);
    const page = await res.json();
    rows.push(...page);
    if (page.length < pageSize) break;
    offset += pageSize;
  }
  return rows;
}

async function fetchEventFeatures(ids) {
  const map = new Map();
  const chunk = 150;
  for (let i = 0; i < ids.length; i += chunk) {
    const inList = ids.slice(i, i + chunk).join(",");
    const url = `${SUPABASE_URL}/rest/v1/events?id=in.(${inList})` +
      `&select=id,subcategory,tags,performers,brands`;
    const res = await fetch(url, { headers: headers() });
    if (!res.ok) throw new Error(`events ${res.status}: ${await res.text()}`);
    for (const e of await res.json()) {
      const feats = [];
      if (e.subcategory) feats.push({ feature: String(e.subcategory).toLowerCase(), type: "subcategory" });
      for (const t of e.tags || []) feats.push({ feature: String(t).toLowerCase(), type: "tag" });
      for (const p of e.performers || []) feats.push({ feature: String(p).toLowerCase(), type: "entity" });
      for (const b of e.brands || []) feats.push({ feature: String(b).toLowerCase(), type: "entity" });
      map.set(e.id, feats);
    }
  }
  return map;
}

async function rebuild(rows) {
  const runStart = new Date().toISOString();
  // Upsert current rows (stamping refreshed_at), then prune anything untouched.
  const batch = 500;
  for (let i = 0; i < rows.length; i += batch) {
    const chunk = rows.slice(i, i + batch).map((r) => ({ ...r, refreshed_at: runStart }));
    const res = await fetch(`${SUPABASE_URL}/rest/v1/feature_affinity?on_conflict=seed,related`, {
      method: "POST",
      headers: { ...headers(), "Content-Type": "application/json", Prefer: "resolution=merge-duplicates,return=minimal" },
      body: JSON.stringify(chunk),
    });
    if (!res.ok) throw new Error(`upsert ${res.status}: ${await res.text()}`);
    console.log(`  upserted ${Math.min(i + batch, rows.length)}/${rows.length}`);
  }
  // Prune stale pairs (no longer co-occurring) from earlier runs.
  const del = await fetch(`${SUPABASE_URL}/rest/v1/feature_affinity?refreshed_at=lt.${runStart}`, {
    method: "DELETE",
    headers: { ...headers(), Prefer: "return=minimal" },
  });
  if (!del.ok) console.warn(`  prune warning ${del.status}: ${await del.text()}`);
}

async function main() {
  if (!SERVICE_KEY) { console.error("Missing SUPABASE_SERVICE_KEY"); process.exit(1); }
  console.log(`Feature affinity — window ${WINDOW_DAYS}d, target ${SUPABASE_URL}`);

  const interactions = await fetchInteractions();
  console.log(`  ${interactions.length} event interactions`);
  const ids = [...new Set(interactions.map((r) => r.item_id))].filter((id) => UUID_RE.test(id));
  const featById = await fetchEventFeatures(ids);
  console.log(`  features for ${featById.size}/${ids.length} events`);

  // Per-user engaged-feature set.
  const byUser = new Map();
  for (const r of interactions) {
    const feats = featById.get(r.item_id);
    if (!feats || feats.length === 0) continue;
    if (!byUser.has(r.user_id)) byUser.set(r.user_id, []);
    byUser.get(r.user_id).push(...feats);
  }
  const userFeatureSets = [...byUser.values()];
  console.log(`  ${userFeatureSets.length} users with feature sets`);

  const affinityRows = computeFeatureAffinity(userFeatureSets);
  console.log(`  computed ${affinityRows.length} affinity pairs across ${new Set(affinityRows.map((r) => r.seed)).size} seeds`);

  if (DRY_RUN) {
    console.log("\nDRY_RUN — sample seeds:");
    const seeds = [...new Set(affinityRows.map((r) => r.seed))].slice(0, 8);
    for (const s of seeds) {
      const rel = affinityRows.filter((r) => r.seed === s).map((r) => `${r.related}(${r.weight})`).join(", ");
      console.log(`  ${s} -> ${rel}`);
    }
    return;
  }

  await rebuild(affinityRows);
  console.log("Done.");
}

main().catch((e) => { console.error(e); process.exit(1); });
