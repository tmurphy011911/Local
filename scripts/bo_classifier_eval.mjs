#!/usr/bin/env node
/**
 * Bo Reasoning Layer — offline classifier eval harness (spec §10, §13.1, Phase 0).
 *
 * Runs the classifier prompt over the labeled seed and reports intent/target
 * accuracy + a confusion breakdown + confidence calibration. This is the
 * *classifier-accuracy* half of the go/no-go gate (§13.5). The inline-routing
 * baseline it must beat is a separate harness (TODO, see spec §13.5).
 *
 * Usage:
 *   ANTHROPIC_API_KEY=sk-... node scripts/bo_classifier_eval.mjs
 *   node scripts/bo_classifier_eval.mjs --dry        # parse + show prompt, no API calls, no key needed
 *   node scripts/bo_classifier_eval.mjs --file docs/specs/bo-reasoning-eval-seed.jsonl --concurrency 4
 *
 * NOTE: the prompt + tool schema below MIRROR
 *   composeApp/.../data/bo/BoClassifierPrompt.kt  — keep them in sync.
 * Cost: one small Haiku call per seed row (~56) — negligible, but we're in a
 * cost-cutting window (spec §7), so there's no auto-retry storm here.
 */

import { readFileSync } from "node:fs";

const MODEL = argValue("--model") ?? "claude-haiku-4-5-20251001";
const FILE = argValue("--file") ?? "docs/specs/bo-reasoning-eval-seed.jsonl";
const CONCURRENCY = Number(argValue("--concurrency") ?? "4");
const LIMIT = argValue("--limit") ? Number(argValue("--limit")) : Infinity;
const DRY = process.argv.includes("--dry");

const INTENTS = [
  "events_search", "events_modify", "venue_single", "event_single", "quick_fact",
  "info", "plan_build", "plan_modify", "out_of_scope", "clarify",
];

// ── classifier prompt (mirror of BoClassifierPrompt.systemPrompt) ──
function systemPrompt(surfaceStateJson) {
  return `You are an intent classifier for a local events & venues chat assistant ("Bo").
Your ONLY job: read the user's latest turn (with recent context) and call the
\`classify\` tool exactly once. Do not answer the user. Do not call any other tool.

INTENTS (pick the single best \`intent\`):
- events_search  — a fresh request for things to do / events / activities.
- events_modify  — a change to the event list ALREADY on screen ("only free", "cheaper", "drop the outdoor ones", "show more", "sort by soonest", "what about next weekend").
- venue_single   — about ONE named venue.
- event_single   — about ONE specific event.
- quick_fact     — a short factual question about a place/event.
- info           — general local info / opinion with no single subject.
- plan_build     — build a NEW itinerary/plan.
- plan_modify    — change the plan ALREADY on screen.
- out_of_scope   — not about local events/venues/experiences (incl. sports scores).
- clarify        — genuinely underspecified AND not answerable by presenting a sensible default.

CURRENT SURFACE (what's on screen right now):
${surfaceStateJson}

DECISION RULES:
- Fresh-vs-modify (only when a compatible surface is open): a DELTA phrase (only/cheaper/drop/sooner/more/more like/swap/move/add/less) -> *_modify on the current surface. A phrase naming a NEW subject -> fresh search, even with a surface open. ("just jazz" with a rock tray = events_modify; "live jazz" = events_search.)
- "what about Sunday" is events_modify when a tray is open, else events_search.
- Prefer PRESENTING over asking: "surprise me" / "I'm bored" / "what should I do" -> events_search (not clarify). Only use clarify when there is truly nothing to default to (e.g. bare "help").
- SCOPE vs sports: a ticketed game at a local venue ("is the Celtics game sold out?") IS a local event -> event_single / events_search, NOT out_of_scope. out_of_scope is only for topics with no local angle (weather elsewhere, taxes, poems, trivia, "who won the game").
- INFO vs events: "feel of a place" questions -- "what's new in town", "what's the vibe in {area}", "what should I know about {neighborhood}" -- are info (no single subject). Reserve events_search for a concrete ask for things to do.
- QUICK_FACT vs venue: a venue's hours/price/closing time ("what time does the MFA close", "how much is the aquarium") is quick_fact even though it names a venue. venue_single is "tell me about / what's going on at {venue}".
- plan_build precondition: if no seed picks on screen AND no day/date AND no vibe/occasion, still classify plan_build but set confidence LOW. Location is usually known from device; a missing city alone is not a blocker.
- Compound: two asks -> primary as \`intent\`, the other as \`secondary_intent\`.

CONFIDENCE: set \`confidence\` in [0,1] honestly. Use < 0.5 when ambiguous or guessing — the app falls back to default handling on low confidence, so low is safe. Never inflate.

Fill \`params\` only with slots the turn states. Put a <=120 char \`rationale\` (for logs).`;
}

const CLASSIFY_TOOL = {
  name: "classify",
  description: "Classify the user's latest turn into a routing intent. Call exactly once.",
  input_schema: {
    type: "object",
    required: ["intent", "confidence"],
    properties: {
      intent: { type: "string", enum: INTENTS },
      confidence: { type: "number" },
      params: { type: "object" },
      modify: { type: "object" },
      secondary_intent: { type: "string" },
      rationale: { type: "string" },
    },
  },
};

async function classify(row) {
  const body = {
    model: MODEL,
    max_tokens: 512,
    system: systemPrompt(row.context || "none"),
    tools: [CLASSIFY_TOOL],
    tool_choice: { type: "tool", name: "classify" },
    messages: [{ role: "user", content: row.utterance }],
  };
  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "x-api-key": process.env.ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
      "content-type": "application/json",
    },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
  const json = await res.json();
  const toolUse = (json.content || []).find((b) => b.type === "tool_use" && b.name === "classify");
  if (!toolUse) throw new Error("no classify tool_use in response");
  return toolUse.input;
}

async function main() {
  const rows = readFileSync(FILE, "utf8")
    .split("\n").map((l) => l.trim()).filter(Boolean).map((l) => JSON.parse(l))
    .slice(0, LIMIT);

  if (DRY) {
    console.log(`Parsed ${rows.length} rows from ${FILE}.`);
    console.log(`\n--- example built prompt (row ${rows[0].id}) ---\n`);
    console.log(systemPrompt(rows[0].context));
    console.log(`\n--- user turn: "${rows[0].utterance}"`);
    return;
  }
  if (!process.env.ANTHROPIC_API_KEY) {
    console.error("ANTHROPIC_API_KEY not set. Use --dry to inspect without calling the API.");
    process.exit(1);
  }

  const results = [];
  for (let i = 0; i < rows.length; i += CONCURRENCY) {
    const batch = rows.slice(i, i + CONCURRENCY);
    const settled = await Promise.allSettled(batch.map(classify));
    settled.forEach((s, j) => {
      const row = batch[j];
      if (s.status === "fulfilled") results.push({ row, pred: s.value });
      else { console.error(`  ! ${row.id} failed: ${s.reason.message}`); results.push({ row, pred: null }); }
    });
    process.stderr.write(`  …${Math.min(i + CONCURRENCY, rows.length)}/${rows.length}\r`);
  }

  // ── scoring (intent only; NEW/CURRENT target is derived from intent) ──
  let intentOk = 0, scored = 0;
  const perIntent = Object.fromEntries(INTENTS.map((k) => [k, { n: 0, ok: 0 }]));
  const misses = [];
  const confCorrect = [], confWrong = [];

  for (const { row, pred } of results) {
    if (!pred) continue;
    scored++;
    perIntent[row.intent] && perIntent[row.intent].n++;
    const iOk = pred.intent === row.intent;
    if (iOk) { intentOk++; perIntent[row.intent].ok++; }
    (iOk ? confCorrect : confWrong).push(pred.confidence ?? 0);
    if (!iOk) {
      misses.push({ id: row.id, utterance: row.utterance, context: row.context,
        exp: row.intent, got: pred.intent, conf: pred.confidence, why: pred.rationale });
    }
  }

  const pct = (a, b) => b ? `${((100 * a) / b).toFixed(1)}%` : "n/a";
  const mean = (xs) => (xs.length ? (xs.reduce((a, b) => a + b, 0) / xs.length).toFixed(2) : "n/a");

  console.log(`\n\n=== Bo classifier eval — ${MODEL} — ${scored}/${rows.length} scored ===`);
  console.log(`Intent accuracy:  ${intentOk}/${scored}  ${pct(intentOk, scored)}`);
  console.log(`Mean confidence — correct: ${mean(confCorrect)}   wrong: ${mean(confWrong)}  (wrong should be lower)`);

  console.log(`\nPer-intent recall:`);
  for (const k of INTENTS) {
    const p = perIntent[k];
    if (p.n) console.log(`  ${k.padEnd(14)} ${p.ok}/${p.n}  ${pct(p.ok, p.n)}`);
  }

  if (misses.length) {
    console.log(`\nMisses (${misses.length}):`);
    for (const m of misses) {
      console.log(`  [${m.id}] "${m.utterance}"  (ctx: ${m.context})`);
      console.log(`        exp ${m.exp}  got ${m.got}  conf=${m.conf}  :: ${m.why ?? ""}`);
    }
  }
  console.log(`\nGo/no-go (§13.5): compare Intent accuracy against the inline-routing baseline once that harness exists.`);
}

function argValue(flag) {
  const i = process.argv.indexOf(flag);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : undefined;
}

main().catch((e) => { console.error(e); process.exit(1); });
