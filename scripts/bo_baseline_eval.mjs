#!/usr/bin/env node
/**
 * Bo Reasoning Layer — INLINE-ROUTING BASELINE harness (spec §13.5).
 *
 * The number the dedicated classifier (bo_classifier_eval.mjs) must BEAT. It
 * approximates today's routing: a single model call that decides the user-facing
 * surface using the REAL prod routing rules (ABSOLUTE RULES #1/#5, INFORMATION-VS-
 * EVENTS, RESPONSE SHAPING — copied from ChatViewModel.buildSystemPrompt) and the
 * REAL terminal tools, then we map the chosen tool → intent.
 *
 * APPROXIMATION (documented, honest): the real flow is a multi-round tool loop
 * where browse surfaces are chosen AFTER a live search_events round against the
 * prod DB. Reproducing that needs the backend, so here we collapse the loop —
 * the model is told to assume the search succeeded and to pick the final surface
 * (or reply in prose / ask, as its rules direct). This isolates the ROUTING
 * judgment, which is what §13.5 compares. It omits the dynamic prompt context
 * (calendar, taste, web budget) that doesn't affect which surface is chosen.
 *
 * Mapping (tool → intent):
 *   present_suggestions  -> events_search   (today has NO events-modify tool; modify rows will mismatch — that's the real gap)
 *   present_event_brief  -> event_single
 *   present_venue_brief  -> venue_single
 *   present_quick_fact   -> quick_fact
 *   present_scope_redirect -> out_of_scope
 *   present_plan         -> plan_build
 *   update_plan          -> plan_modify
 *   (no tool / plain text / a clarifying question) -> info   (today's prose/ask bucket; also where clarify lands)
 *
 * Usage: ANTHROPIC_API_KEY=sk-... node scripts/bo_baseline_eval.mjs --concurrency 4
 *        node scripts/bo_baseline_eval.mjs --dry
 */

import { readFileSync } from "node:fs";

const MODEL = argValue("--model") ?? "claude-haiku-4-5-20251001";
const FILE = argValue("--file") ?? "docs/specs/bo-reasoning-eval-seed.jsonl";
const CONCURRENCY = Number(argValue("--concurrency") ?? "4");
const DRY = process.argv.includes("--dry");

const TOOL_TO_INTENT = {
  present_suggestions: "events_search",
  present_event_brief: "event_single",
  present_venue_brief: "venue_single",
  present_quick_fact: "quick_fact",
  present_scope_redirect: "out_of_scope",
  present_plan: "plan_build",
  update_plan: "plan_modify",
};
const PROSE_INTENT = "info"; // no terminal tool emitted → prose/ask bucket

// Real routing rules, condensed from ChatViewModel.buildSystemPrompt (lines ~5949-6011),
// trimmed to the parts that drive the single-turn SURFACE decision.
const SYSTEM = `You are Bo, an AI assistant inside a local events and dining app. Warm, opinionated, decisive.

ABSOLUTE SCOPE RULE: Your scope is LOCAL — cities/states, venues/places, and events/things to do. You help discover events/venues, build plans, AND answer factual questions about a place/venue (hours, best spots, what a venue is, neighborhood info). You do NOT engage with anything outside those buckets (pure trivia, general history/science, math, code, recipes, news, sports SCORES, jokes). For anything outside scope, call present_scope_redirect — do not free-text it. A ticketed game at a local venue is an EVENT (in scope), not a "sports score".

AFTER A SUCCESSFUL SEARCH, PRESENT — NEVER ASK (for concrete event intent). If the user named an activity/venue/category/date, your response is a structured tool: present_suggestions for browse, present_event_brief / present_venue_brief / present_quick_fact for narrow factual asks. Do not ask a vibe/category clarifier after a search returned results.

INFORMATION VS EVENTS carve-out: if the user asks about a place/venue but names NO event/activity word (signals: "hours"/"open"/"best places"/"top spots"/bare venue name/"what's good in {city}"/"what's new"), it is ambiguous — reply with ONE short plain-text question offering both paths (events vs. just the info), then stop. Do NOT call a tool yet in that case.

RESPONSE SHAPING — which tool when:
- BROWSING / open-ended discovery → present_suggestions.
- SINGLE-EVENT factual question → present_event_brief.
- SINGLE-VENUE factual question → present_venue_brief.
- QUICK FACT without a card-worthy follow-up → present_quick_fact.
- OUT-OF-SCOPE → present_scope_redirect.
- Full itinerary → present_plan; edit an existing plan → update_plan.
- Never describe events as paragraph text or numbered lists; either a structured tool, or plain prose with no event list.

DECIDE NOW (test harness): assume any search you'd run already returned plenty of results. For THIS single turn, either call exactly ONE tool above (the surface you'd show), OR, if your rules say to ask a clarifying question or answer in prose, reply in plain text. Do NOT call search_events/search_venues.`;

const TOOLS = Object.keys(TOOL_TO_INTENT).map((name) => ({
  name,
  description: `Present the ${name.replace("present_", "").replace("_", " ")} surface.`,
  input_schema: { type: "object", properties: {} },
}));

async function route(row) {
  const body = {
    model: MODEL,
    max_tokens: 512,
    system: SYSTEM,
    tools: TOOLS,
    tool_choice: { type: "auto" },
    messages: [{ role: "user", content: `Current surface on screen: ${row.context || "none"}\n\nUser: ${row.utterance}` }],
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
  const tool = (json.content || []).find((b) => b.type === "tool_use" && TOOL_TO_INTENT[b.name]);
  return tool ? TOOL_TO_INTENT[tool.name] : PROSE_INTENT; // prose/ask → info bucket
}

async function main() {
  const rows = readFileSync(FILE, "utf8").split("\n").map((l) => l.trim()).filter(Boolean).map((l) => JSON.parse(l));

  if (DRY) { console.log(`Parsed ${rows.length} rows.\n\n--- baseline system prompt ---\n${SYSTEM}`); return; }
  if (!process.env.ANTHROPIC_API_KEY) { console.error("ANTHROPIC_API_KEY not set. Use --dry."); process.exit(1); }

  const results = [];
  for (let i = 0; i < rows.length; i += CONCURRENCY) {
    const batch = rows.slice(i, i + CONCURRENCY);
    const settled = await Promise.allSettled(batch.map(route));
    settled.forEach((s, j) => results.push({ row: batch[j], pred: s.status === "fulfilled" ? s.value : null,
      err: s.status === "rejected" ? s.reason.message : null }));
    process.stderr.write(`  …${Math.min(i + CONCURRENCY, rows.length)}/${rows.length}\r`);
  }

  let ok = 0, scored = 0;
  const perIntent = {};
  const misses = [];
  for (const { row, pred, err } of results) {
    if (err) { console.error(`  ! ${row.id} failed: ${err}`); continue; }
    scored++;
    perIntent[row.intent] ??= { n: 0, ok: 0 };
    perIntent[row.intent].n++;
    if (pred === row.intent) { ok++; perIntent[row.intent].ok++; }
    else misses.push({ id: row.id, utterance: row.utterance, context: row.context, exp: row.intent, got: pred });
  }
  const pct = (a, b) => b ? `${((100 * a) / b).toFixed(1)}%` : "n/a";

  console.log(`\n\n=== Bo INLINE-ROUTING baseline — ${MODEL} — ${scored}/${rows.length} scored ===`);
  console.log(`Routing accuracy: ${ok}/${scored}  ${pct(ok, scored)}`);
  console.log(`(events_modify rows are expected misses — today has no events-modify surface; that gap is the classifier's win.)`);
  console.log(`\nPer-intent:`);
  for (const k of Object.keys(perIntent)) console.log(`  ${k.padEnd(14)} ${perIntent[k].ok}/${perIntent[k].n}  ${pct(perIntent[k].ok, perIntent[k].n)}`);
  if (misses.length) {
    console.log(`\nMisses (${misses.length}):`);
    for (const m of misses) console.log(`  [${m.id}] "${m.utterance}" (ctx:${m.context})  exp ${m.exp}  got ${m.got}`);
  }
}

function argValue(flag) { const i = process.argv.indexOf(flag); return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : undefined; }
main().catch((e) => { console.error(e); process.exit(1); });
