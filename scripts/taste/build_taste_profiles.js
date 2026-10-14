// Nightly taste-profile builder.
//
// Reads community_interactions (windowed) joined to event features, computes a
// per-user taste summary (math in taste_model.js — unit-tested), and upserts
// user_taste_summary. Pure aggregation: needs only the service key, no Anthropic.
//
// ⚠️ Like the classifier, scripts/lib/secrets.js defaults SUPABASE_URL to PROD.
// Set SUPABASE_URL=https://xbyqwekcanrzovytxmdv.supabase.co to target dev.
//
// Env:
//   SUPABASE_SERVICE_KEY  (required)   SUPABASE_URL (dev/prod)
//   WINDOW_DAYS=365   look-back window for interactions
//   MAX_USERS=0       cap users processed (test runs); 0 = all
//   DRY_RUN=1         compute + print a sample, do not write

const { SUPABASE_URL } = require("../lib/secrets");
const { computeUserTaste, TASTE_MODEL_VERSION } = require("./taste_model");

const WINDOW_DAYS = parseInt(process.env.WINDOW_DAYS || "365", 10);
const MAX_USERS = parseInt(process.env.MAX_USERS || "0", 10);
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
      `&select=id,user_id,item_id,interaction_type,created_at` +
      `&order=created_at.desc&limit=${pageSize}&offset=${offset}`;
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
      `&select=id,subcategory,tags,performers,brands,event_lat,event_lng,venue_lat,venue_lng`;
    const res = await fetch(url, { headers: headers() });
    if (!res.ok) throw new Error(`events ${res.status}: ${await res.text()}`);
    for (const e of await res.json()) {
      map.set(e.id, {
        subcategory: e.subcategory,
        tags: e.tags || [],
        performers: e.performers || [],
        brands: e.brands || [],
        lat: e.event_lat ?? e.venue_lat ?? null,
        lng: e.event_lng ?? e.venue_lng ?? null,
      });
    }
  }
  return map;
}

// Actor location lives in the own-RLS interaction_context table (read here via
// the service role). Map community_interaction_id -> {lat, lng}.
async function fetchContextLocations(interactionIds) {
  const map = new Map();
  const chunk = 150;
  for (let i = 0; i < interactionIds.length; i += chunk) {
    const inList = interactionIds.slice(i, i + chunk).join(",");
    const url = `${SUPABASE_URL}/rest/v1/interaction_context` +
      `?community_interaction_id=in.(${inList})&select=community_interaction_id,actor_lat,actor_lng`;
    const res = await fetch(url, { headers: headers() });
    if (!res.ok) throw new Error(`interaction_context ${res.status}: ${await res.text()}`);
    for (const r of await res.json()) {
      map.set(r.community_interaction_id, { lat: r.actor_lat, lng: r.actor_lng });
    }
  }
  return map;
}

async function upsert(rows) {
  const url = `${SUPABASE_URL}/rest/v1/user_taste_summary?on_conflict=user_id`;
  const res = await fetch(url, {
    method: "POST",
    headers: {
      ...headers(),
      "Content-Type": "application/json",
      Prefer: "resolution=merge-duplicates,return=minimal",
    },
    body: JSON.stringify(rows),
  });
  if (!res.ok) throw new Error(`upsert ${res.status}: ${await res.text()}`);
}

async function main() {
  if (!SERVICE_KEY) { console.error("Missing SUPABASE_SERVICE_KEY"); process.exit(1); }
  console.log(`Taste profiles — window ${WINDOW_DAYS}d, model ${TASTE_MODEL_VERSION}, target ${SUPABASE_URL}`);

  const interactions = await fetchInteractions();
  console.log(`  fetched ${interactions.length} event interactions`);

  const ids = [...new Set(interactions.map((r) => r.item_id))].filter((id) => UUID_RE.test(id));
  const featById = await fetchEventFeatures(ids);
  console.log(`  resolved features for ${featById.size}/${ids.length} live events`);

  const ctxByInteraction = await fetchContextLocations(
    interactions.map((r) => r.id).filter((id) => UUID_RE.test(id)),
  );
  console.log(`  actor location for ${ctxByInteraction.size} interactions`);

  const byUser = new Map();
  for (const r of interactions) {
    const loc = ctxByInteraction.get(r.id);
    const it = {
      interactionType: r.interaction_type,
      createdAt: r.created_at,
      actorLat: loc?.lat ?? null,
      actorLng: loc?.lng ?? null,
      event: featById.get(r.item_id) || {},
    };
    if (!byUser.has(r.user_id)) byUser.set(r.user_id, []);
    byUser.get(r.user_id).push(it);
  }

  let users = [...byUser.entries()];
  if (MAX_USERS && users.length > MAX_USERS) users = users.slice(0, MAX_USERS);
  console.log(`  computing taste for ${users.length} users`);

  const nowMs = Date.now();
  const refreshedAt = new Date(nowMs).toISOString();
  const rows = users.map(([userId, its]) => ({
    user_id: userId,
    ...computeUserTaste(its, nowMs),
    refreshed_at: refreshedAt,
  }));

  if (DRY_RUN) {
    console.log("\nDRY_RUN — sample of up to 5 profiles:");
    for (const row of rows.slice(0, 5)) {
      const interests = [row.interest_1, row.interest_2, row.interest_3, row.interest_4, row.interest_5].filter(Boolean);
      console.log(`  ${row.user_id.slice(0, 8)}… n=${row.interaction_count} interests=[${interests.join(", ")}] travel=${row.travel_propensity ?? "—"} entities=[${row.top_entities.slice(0, 3).join(", ")}]`);
    }
    console.log(`\n(${rows.length} profiles computed, not written)`);
    return;
  }

  const batch = 500;
  for (let i = 0; i < rows.length; i += batch) {
    await upsert(rows.slice(i, i + batch));
    console.log(`  upserted ${Math.min(i + batch, rows.length)}/${rows.length}`);
  }
  console.log("Done.");
}

main().catch((err) => { console.error(err); process.exit(1); });
