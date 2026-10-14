// Core taste-model math — PURE, no I/O, so it can be unit-tested offline.
// The runner (build_taste_profiles.js) fetches interactions + event features
// and calls computeUserTaste(); this file just does the aggregation.
//
// v1 representation is SPARSE + interpretable (weighted feature scores), per the
// spec decision to start sparse and add learned embeddings in Phase 4. Distances
// use the interaction-time ACTIVE location (spec §5.4), not home.

const { TAG_FACETS } = require("./tag_vocabulary");

const TASTE_MODEL_VERSION = "v1";

// Same weights as the client (CommunityInterestRepositoryImpl) + the negative.
const INTERACTION_WEIGHTS = {
  click: 1.0,
  favorite: 2.0,
  share: 4.0,
  plan_add: 6.0,
  not_interested: -3.0,
};

const HALF_LIFE_DAYS = 60;     // recency decay: a signal is worth ½ after 60 days
const TRAVEL_CAP_MILES = 50;   // distance that maps to travel_propensity ~1.0
const MIN_TRAVEL_SAMPLES = 3;  // below this we don't claim a travel profile
const DAY_MS = 86400000;

// Tags that count as "interests" for the interest_1..5 projection. Audience /
// setting / format / commitment / scale tags describe an event but aren't
// interests, so they're excluded from the human-facing labels.
const INTEREST_TAGS = new Set(TAG_FACETS.interest);

function interactionWeight(type) {
  return INTERACTION_WEIGHTS[type] ?? 0;
}

function recencyDecay(nowMs, createdAtMs) {
  const ageDays = Math.max(0, (nowMs - createdAtMs) / DAY_MS);
  return Math.pow(0.5, ageDays / HALF_LIFE_DAYS);
}

function haversineMiles(lat1, lng1, lat2, lng2) {
  const toRad = (d) => (d * Math.PI) / 180;
  const R = 3958.8; // miles
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
}

// Weighted percentile of [{value, weight}] (weights > 0). p in [0,1].
function weightedPercentile(pairs, p) {
  const pos = pairs.filter((x) => x.weight > 0).sort((a, b) => a.value - b.value);
  if (pos.length === 0) return null;
  const total = pos.reduce((s, x) => s + x.weight, 0);
  const target = p * total;
  let cum = 0;
  for (const x of pos) {
    cum += x.weight;
    if (cum >= target) return x.value;
  }
  return pos[pos.length - 1].value;
}

function addScore(map, key, weight) {
  if (!key) return;
  map[key] = (map[key] || 0) + weight;
}

function humanizeSlug(slug) {
  return slug
    .replace(/[_-]+/g, " ")
    .trim()
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

/**
 * @param {Array} interactions each: {
 *   interactionType, createdAt (ISO string or ms), actorLat, actorLng,
 *   event: { subcategory, tags[], performers[], brands[], lat, lng }
 * }
 * @param {number} nowMs  current time (injected for testability)
 * @returns taste summary object ready to upsert into user_taste_summary
 */
function computeUserTaste(interactions, nowMs) {
  const subcategoryScores = {};
  const tagScores = {};
  const entityScores = {};
  const interestScores = {}; // merged: subcategory + interest-tags + entities
  const travelPairs = [];    // {value: miles, weight}

  let counted = 0;

  for (const it of interactions) {
    const base = interactionWeight(it.interactionType);
    if (base === 0) continue;
    const createdMs =
      typeof it.createdAt === "number" ? it.createdAt : Date.parse(it.createdAt);
    if (Number.isNaN(createdMs)) continue;
    const w = base * recencyDecay(nowMs, createdMs);
    const ev = it.event || {};
    counted++;

    addScore(subcategoryScores, ev.subcategory, w);
    addScore(interestScores, ev.subcategory, w);

    for (const t of ev.tags || []) {
      addScore(tagScores, t, w);
      if (INTEREST_TAGS.has(t)) addScore(interestScores, t, w);
    }
    for (const e of [...(ev.performers || []), ...(ev.brands || [])]) {
      addScore(entityScores, e, w);
      addScore(interestScores, e, w);
    }

    // Travel: positive intent only, needs both endpoints.
    if (
      base > 0 &&
      it.actorLat != null && it.actorLng != null &&
      ev.lat != null && ev.lng != null
    ) {
      const miles = haversineMiles(it.actorLat, it.actorLng, ev.lat, ev.lng);
      if (Number.isFinite(miles)) travelPairs.push({ value: miles, weight: w });
    }
  }

  // Interest_1..5 = top interest-bearing tokens (drop net-negative ones).
  const rankedInterests = Object.entries(interestScores)
    .filter(([, s]) => s > 0)
    .sort((a, b) => b[1] - a[1])
    .map(([k]) => k);

  const topEntities = Object.entries(entityScores)
    .filter(([, s]) => s > 0)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 10)
    .map(([k]) => k);

  // Travel propensity: p75 distance the user has shown they'll go, normalized.
  let typicalTravelMiles = null;
  let travelPropensity = null;
  if (travelPairs.length >= MIN_TRAVEL_SAMPLES) {
    typicalTravelMiles = weightedPercentile(travelPairs, 0.75);
    if (typicalTravelMiles != null) {
      travelPropensity = Math.max(0, Math.min(1, typicalTravelMiles / TRAVEL_CAP_MILES));
    }
  }

  const interestLabels = rankedInterests.slice(0, 5).map(humanizeSlug);

  return {
    subcategory_scores: subcategoryScores,
    tag_scores: tagScores,
    entity_scores: entityScores,
    interest_scores: interestScores,
    interest_1: interestLabels[0] ?? null,
    interest_2: interestLabels[1] ?? null,
    interest_3: interestLabels[2] ?? null,
    interest_4: interestLabels[3] ?? null,
    interest_5: interestLabels[4] ?? null,
    top_entities: topEntities,
    travel_propensity: travelPropensity,
    typical_travel_miles: typicalTravelMiles,
    interaction_count: counted,
    model_version: TASTE_MODEL_VERSION,
  };
}

module.exports = {
  TASTE_MODEL_VERSION,
  INTERACTION_WEIGHTS,
  HALF_LIFE_DAYS,
  TRAVEL_CAP_MILES,
  interactionWeight,
  recencyDecay,
  haversineMiles,
  weightedPercentile,
  computeUserTaste,
};
