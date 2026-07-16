// Offline sanity test for taste_model.js — no DB, run: node scripts/taste/taste_model.test.js
const assert = require("assert");
const {
  computeUserTaste, recencyDecay, haversineMiles, weightedPercentile,
} = require("./taste_model");

let passed = 0;
const ok = (cond, msg) => { assert.ok(cond, msg); passed++; };

// ── unit: recency decay ──
const NOW = Date.parse("2026-07-16T00:00:00Z");
ok(Math.abs(recencyDecay(NOW, NOW) - 1.0) < 1e-9, "no decay at age 0");
ok(Math.abs(recencyDecay(NOW, NOW - 60 * 86400000) - 0.5) < 1e-9, "half after 60d");

// ── unit: haversine (Boston→NYC ≈ 190 mi) ──
const d = haversineMiles(42.36, -71.06, 40.71, -74.01);
ok(d > 180 && d < 200, `Boston→NYC ~190mi, got ${d.toFixed(1)}`);

// ── unit: weighted percentile ──
ok(weightedPercentile([{value: 10, weight: 1}, {value: 90, weight: 9}], 0.75) === 90, "weighted p75 leans to heavy value");

// ── integration: a comedy+sports+beer fan who declined a concert ──
const iso = (daysAgo) => new Date(NOW - daysAgo * 86400000).toISOString();
const actor = { actorLat: 42.0, actorLng: -71.0 };
const interactions = [
  { interactionType: "favorite", createdAt: iso(2), ...actor,
    event: { subcategory: "comedy", tags: ["comedy", "standup-set", "21+"], performers: ["shane_gillis"], brands: [], lat: 42.0, lng: -71.0 } },
  { interactionType: "plan_add", createdAt: iso(5), ...actor,
    event: { subcategory: "sports", tags: ["pro-sports", "big-crowd"], performers: [], brands: [], lat: 42.5, lng: -71.0 } },
  { interactionType: "click", createdAt: iso(3), ...actor,
    event: { subcategory: "food", tags: ["craft-beer", "brewery-taproom"], performers: [], brands: [], lat: 43.0, lng: -71.0 } },
  { interactionType: "not_interested", createdAt: iso(1), ...actor,
    event: { subcategory: "music", tags: ["live-music", "concert"], performers: [], brands: [], lat: 42.0, lng: -71.0 } },
];

const r = computeUserTaste(interactions, NOW);

ok(r.interaction_count === 4, "counted all 4 interactions");
ok(r.top_entities.includes("shane_gillis"), "shane_gillis surfaced as entity");
ok((r.interest_scores["music"] ?? 0) <= 0, "declined concert => music not a positive interest");
const labels = [r.interest_1, r.interest_2, r.interest_3, r.interest_4, r.interest_5];
ok(!labels.includes("Music") && !labels.includes("Live Music"), "music excluded from interest labels");
ok(r.interest_1 != null, "has a top interest");
ok(r.travel_propensity != null && r.travel_propensity > 0 && r.travel_propensity <= 1, `travel_propensity in (0,1], got ${r.travel_propensity}`);
ok(r.typical_travel_miles != null && r.typical_travel_miles > 0, `typical_travel_miles > 0, got ${r.typical_travel_miles}`);
ok(r.model_version === "v1", "model version stamped");

// ── cold: too few travel samples => null travel profile ──
const sparse = computeUserTaste([interactions[0]], NOW);
ok(sparse.travel_propensity === null, "1 sample => no travel propensity claim");

console.log(`\nInterest labels: ${labels.filter(Boolean).join(", ")}`);
console.log(`Travel: p75=${r.typical_travel_miles?.toFixed(1)}mi propensity=${r.travel_propensity?.toFixed(2)}`);
console.log(`\n✓ all ${passed} taste-model checks passed`);
