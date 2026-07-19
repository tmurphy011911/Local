// Offline test for feature_affinity_model.js — run: node scripts/taste/feature_affinity_model.test.js
const assert = require("assert");
const { computeFeatureAffinity } = require("./feature_affinity_model");

let passed = 0;
const ok = (c, m) => { assert.ok(c, m); passed++; };

const E = (f) => ({ feature: f, type: "entity" });
const S = (f) => ({ feature: f, type: "subcategory" });
const T = (f) => ({ feature: f, type: "tag" });

// 5 Shane Gillis fans who also like Bill Burr + baseball (+ comedy).
// 5 other users who like comedy too (so comedy is UBIQUITOUS) but nothing else shared.
const sets = [];
for (let i = 0; i < 5; i++) sets.push([E("shane_gillis"), E("bill_burr"), S("comedy"), S("baseball")]);
for (let i = 0; i < 5; i++) sets.push([S("comedy"), S("music"), T("jazz")]);

const rows = computeFeatureAffinity(sets);
const bySeed = (seed) => rows.filter((r) => r.seed === seed);
const related = (seed) => new Set(bySeed(seed).map((r) => r.related));

ok(rows.length > 0, "produced affinity rows");
// The magic: Shane Gillis fans -> Bill Burr + baseball surface.
ok(related("shane_gillis").has("bill_burr"), "shane_gillis -> bill_burr surfaced");
ok(related("shane_gillis").has("baseball"), "shane_gillis -> baseball surfaced");
// comedy is ubiquitous (P=1) -> lift 1 -> must NOT be a distinctive relation.
ok(!related("shane_gillis").has("comedy"), "ubiquitous comedy excluded (lift correction)");
// weight = P(related | seed); every SG fan also likes bill_burr -> 1.0
const bb = bySeed("shane_gillis").find((r) => r.related === "bill_burr");
ok(bb && Math.abs(bb.weight - 1.0) < 1e-6, `P(bill_burr|shane_gillis)=1.0, got ${bb && bb.weight}`);
// related_type carried through
ok(bb.related_type === "entity", "related_type preserved");
// min-seed-support: a seed seen once is dropped
const rare = computeFeatureAffinity([[E("obscure_act"), S("comedy")]]);
ok(rare.filter((r) => r.seed === "obscure_act").length === 0, "rare seed (support<3) dropped");

console.log(`shane_gillis -> ${bySeed("shane_gillis").map((r) => r.related + "(" + r.weight + ")").join(", ")}`);
console.log(`\n✓ all ${passed} feature-affinity checks passed`);
