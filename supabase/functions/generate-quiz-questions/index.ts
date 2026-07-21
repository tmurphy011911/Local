/// <reference types="https://esm.sh/@supabase/functions-js/src/edge-runtime.d.ts" />
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

// ════════════════════════════════════════════════════════════════════════════
// generate-quiz-questions: top up the Daily Local trivia bank with fresh,
// Claude-generated questions — never repeating what's already asked or queued.
//
// For each (live state × difficulty) it counts how many UNUSED questions remain
// (used_on IS NULL) and generates only the deficit below `targetUnused`, so the
// daily picker (ensure_daily_quiz_set, which prefers unused) never has to reuse a
// stale question, while spend + table growth stay bounded.
//
// Dedup guarantees:
//   • Hard: unique index (state, difficulty, normalized_question) + upsert
//     ignoreDuplicates → a text-dupe of ANY existing row (asked or queued) is
//     rejected, plus within-batch dupes.
//   • Soft: the existing question texts are handed to Claude with "don't repeat
//     these", catching same-fact/different-words near-dupes the index misses.
//
// Auth: header `x-quiz-gen-secret` must equal env QUIZ_GEN_SECRET. Deploy with
// --no-verify-jwt (cron / admin function, no user token).
//
// Input (POST JSON, all optional):
//   { states?: string[], difficulties?: ("easy"|"medium"|"hard")[],
//     targetUnused?: number (default 40), perCellCap?: number (default 30),
//     dryRun?: boolean }
// Output: { ok, dryRun, results: [{ state, difficulty, unusedBefore, requested,
//           generated, inserted, skippedDuplicate, invalid }], totalInserted }
// ════════════════════════════════════════════════════════════════════════════

const CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
const CLAUDE_MODEL = "claude-haiku-4-5-20251001";
// Fact-check/critic pass runs on a stronger model than generation — factuality
// and subjectivity judgment is exactly where the capability gap pays for itself,
// and it's only ~1 call per (state × difficulty) cell per run.
const VERIFY_MODEL = "claude-opus-4-8";
const DIFFICULTIES = ["easy", "medium", "hard"] as const;
type Difficulty = (typeof DIFFICULTIES)[number];

// State code -> full name for the prompt.
const STATE_NAMES: Record<string, string> = {
  MA: "Massachusetts", PA: "Pennsylvania", RI: "Rhode Island",
  NY: "New York", NJ: "New Jersey", CA: "California",
};

const DIFFICULTY_GUIDANCE: Record<Difficulty, string> = {
  easy: "well-known facts most residents would know (state capital, nickname, famous foods, sports teams, landmarks).",
  medium: "facts a proud local would know but a casual visitor might not (neighborhoods, regional history, notable people, local traditions).",
  hard: "deep-cut trivia that stumps most people (specific dates, lesser-known history, records, obscure geography).",
};

function normalize(q: string): string {
  return q.toLowerCase().replace(/[^a-z0-9]+/g, "");
}

function cors(): Record<string, string> {
  return {
    "Content-Type": "application/json",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "authorization, content-type, x-quiz-gen-secret",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
  };
}

interface GenQuestion { question: string; answers: string[]; correct_index: number; }

// Extract the first JSON array from Claude's reply (tolerates prose wrappers).
function parseQuestions(raw: string): GenQuestion[] {
  const start = raw.indexOf("[");
  const end = raw.lastIndexOf("]");
  if (start < 0 || end <= start) return [];
  try {
    const arr = JSON.parse(raw.slice(start, end + 1));
    return Array.isArray(arr) ? arr : [];
  } catch {
    return [];
  }
}

async function callClaude(
  anthropicKey: string,
  system: string,
  user: string,
  model: string = CLAUDE_MODEL,
): Promise<string> {
  const body = JSON.stringify({
    model,
    max_tokens: 4096,
    system,
    messages: [{ role: "user", content: user }],
  });
  let last: Response | null = null;
  for (let attempt = 0; attempt < 3; attempt++) {
    if (attempt > 0) await new Promise((r) => setTimeout(r, 800 * attempt));
    const res = await fetch(CLAUDE_API_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-api-key": anthropicKey,
        "anthropic-version": "2023-06-01",
      },
      body,
    });
    if (res.ok) {
      const data = await res.json();
      // Grab the first text block (robust to thinking/other block types).
      const text = (data?.content ?? []).find((b: { type?: string }) => b?.type === "text")?.text;
      return (text ?? "") as string;
    }
    last = res;
    const transient = res.status === 429 || res.status === 503 || res.status === 529 || (res.status >= 500 && res.status < 600);
    console.error(`generate-quiz-questions: Claude attempt ${attempt + 1} -> ${res.status} (transient=${transient})`);
    if (!transient) break;
  }
  throw new Error(`Claude request failed (${last?.status ?? "no response"})`);
}

// ── Fact-check / critic pass ────────────────────────────────────────────────
// Structural validation (4 answers, 1 correct) can't catch a hallucinated
// premise, a wrong marked-correct answer, an ambiguous question with multiple
// valid answers, or a subjective/opinion prompt. A stronger model reviews each
// candidate and returns keep/reject so those never reach the live bank.
interface VerifyRow { question: string; answers: string[]; correctIndex: number; }

async function verifyQuestions(
  anthropicKey: string,
  stateName: string,
  candidates: VerifyRow[],
): Promise<boolean[]> {
  if (candidates.length === 0) return [];

  const system =
    `You are a rigorous, skeptical trivia fact-checker for a local-knowledge quiz. ` +
    `The questions below were machine-generated and are known to contain hallucinations and quality problems. ` +
    `Apply a STRICT bar — better safe than sorry. KEEP a question ONLY if it is flawless: ` +
    `factually correct, objective, well-formed, and has exactly one unambiguous answer. ` +
    `REJECT if ANY of these is true:\n` +
    `1. The premise is false (e.g. "Which ${stateName} chemist discovered iodine" — iodine was discovered by Bernard Courtois, a Frenchman → false premise).\n` +
    `2. The answer marked correct is not actually correct, OR any detail in the question is inaccurate (even if the marked answer happens to be right).\n` +
    `3. Another listed option is also correct or defensible, so the answer is ambiguous.\n` +
    `4. The question is subjective/opinion-based ("best", "most beautiful", "trendiest", "most gentrified") with no objective answer.\n` +
    `5. A named person, place, or entity in the question or answers appears fabricated.\n` +
    `6. The wording is circular or self-answering (the answer appears inside the question).\n` +
    `7. The phrasing is awkward, confusing, or garbled, or the question is a near-duplicate of another in this batch.\n` +
    `8. You are not fully confident every factual claim is true.\n` +
    `A smaller, flawless bank beats a larger one with any doubtful questions. When in doubt, REJECT. ` +
    `Return ONLY a JSON array of {"i": <index>, "verdict": "keep"|"reject", "reason": <short string>}, one element per question, no prose, no markdown.`;
  const list = candidates
    .map((c, i) => `#${i} Q: ${c.question}\n   correct: ${c.answers[c.correctIndex]}\n   options: ${JSON.stringify(c.answers)}`)
    .join("\n");
  const user = `Fact-check these ${candidates.length} ${stateName} trivia questions:\n\n${list}`;

  const raw = await callClaude(anthropicKey, system, user, VERIFY_MODEL);
  const start = raw.indexOf("[");
  const end = raw.lastIndexOf("]");
  if (start < 0 || end <= start) throw new Error("verifier returned no JSON array");
  const verdicts = JSON.parse(raw.slice(start, end + 1)) as Array<{ i: number; verdict: string }>;

  // Default to reject: anything the verifier didn't explicitly keep is dropped.
  const keep = new Array(candidates.length).fill(false);
  for (const v of verdicts) {
    if (typeof v?.i === "number" && v.i >= 0 && v.i < candidates.length && v.verdict === "keep") {
      keep[v.i] = true;
    }
  }
  return keep;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: cors() });
  if (req.method !== "POST") return new Response(JSON.stringify({ error: "POST only" }), { status: 405, headers: cors() });

  const secret = Deno.env.get("QUIZ_GEN_SECRET");
  if (!secret || req.headers.get("x-quiz-gen-secret") !== secret) {
    return new Response(JSON.stringify({ error: "unauthorized" }), { status: 401, headers: cors() });
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const anthropicKey = Deno.env.get("ANTHROPIC_API_KEY");
  if (!supabaseUrl || !serviceKey || !anthropicKey) {
    return new Response(JSON.stringify({ error: "missing env (SUPABASE_URL / SERVICE_ROLE / ANTHROPIC_API_KEY)" }), { status: 500, headers: cors() });
  }
  const sb = createClient(supabaseUrl, serviceKey);

  const bodyIn = await req.json().catch(() => ({}));
  const targetUnused: number = Math.max(1, Math.min(200, bodyIn.targetUnused ?? 40));
  const perCellCap: number = Math.max(1, Math.min(50, bodyIn.perCellCap ?? 30));
  const dryRun: boolean = bodyIn.dryRun === true;
  // Fact-check pass is ON by default; pass verify:false to skip it (e.g. debugging).
  const verify: boolean = bodyIn.verify !== false;
  const reqDifficulties: Difficulty[] = Array.isArray(bodyIn.difficulties) && bodyIn.difficulties.length
    ? bodyIn.difficulties.filter((d: string) => (DIFFICULTIES as readonly string[]).includes(d))
    : [...DIFFICULTIES];

  // Default to the live states unless a subset is requested.
  let states: string[] = bodyIn.states;
  if (!Array.isArray(states) || states.length === 0) {
    const { data } = await sb.from("states").select("code").eq("is_live", true);
    states = (data ?? []).map((r: { code: string }) => r.code);
  }

  const today = new Date().toISOString().slice(0, 10);
  const results: Array<Record<string, unknown>> = [];
  let totalInserted = 0;

  for (const state of states) {
    for (const difficulty of reqDifficulties) {
      // Existing questions for this cell (all of them — asked + queued) for both
      // the deficit count and the "don't repeat these" prompt context.
      const { data: existingRows } = await sb
        .from("quiz_questions")
        .select("question, used_on")
        .eq("state", state)
        .eq("difficulty", difficulty);
      const existing = existingRows ?? [];
      const unusedBefore = existing.filter((r: { used_on: string | null }) => r.used_on === null).length;
      const requested = Math.min(perCellCap, Math.max(0, targetUnused - unusedBefore));
      if (requested === 0) {
        results.push({ state, difficulty, unusedBefore, requested: 0, generated: 0, inserted: 0, skippedDuplicate: 0, invalid: 0 });
        continue;
      }

      const existingNorm = new Set(existing.map((r: { question: string }) => normalize(r.question)));
      const existingList = existing.map((r: { question: string }) => `- ${r.question}`).join("\n");
      const stateName = STATE_NAMES[state] ?? state;

      const system =
        `You write trivia questions about US states for a local-knowledge quiz. ` +
        `Return ONLY a JSON array — no prose, no markdown. Each element: ` +
        `{"question": string, "answers": [string, string, string, string], "correct_index": 0-3}. ` +
        `Exactly 4 answers, exactly one correct (at correct_index). Questions must be factually ` +
        `accurate, unambiguous, single-answer, and about ${stateName}. Vary the answer position. ` +
        `HARD REQUIREMENTS — a question that violates any of these will be rejected:\n` +
        `- Every question must be OBJECTIVE. No opinion/subjective framing ("best", "most beautiful", ` +
        `"trendiest", "most gentrified", "coolest"). If the answer is a matter of taste, do not write it.\n` +
        `- The premise must be TRUE. Do not attribute a fact to ${stateName} that belongs elsewhere ` +
        `(e.g. do not call a person from another state or country a "${stateName}" figure).\n` +
        `- Exactly ONE option may be defensibly correct; the other three must be clearly, verifiably wrong. ` +
        `Never write a question where two options are both arguably correct.\n` +
        `- Use only REAL people, places, and entities. Never invent names as distractors.\n` +
        `- If you are not certain a fact is true, do not write the question.\n` +
        `Do NOT repeat, rephrase, or restate any question in the "ALREADY IN THE BANK" list.`;
      const user =
        `Generate ${requested} NEW ${difficulty} trivia questions about ${stateName}.\n` +
        `Difficulty "${difficulty}": ${DIFFICULTY_GUIDANCE[difficulty]}\n\n` +
        `ALREADY IN THE BANK (do not duplicate or rephrase any of these):\n` +
        `${existingList || "(none yet)"}\n\n` +
        `Return exactly ${requested} questions as a JSON array.`;

      let generated: GenQuestion[] = [];
      try {
        const raw = await callClaude(anthropicKey, system, user);
        generated = parseQuestions(raw);
      } catch (e) {
        results.push({ state, difficulty, unusedBefore, requested, generated: 0, inserted: 0, skippedDuplicate: 0, invalid: 0, error: String(e).slice(0, 160) });
        continue;
      }

      // Validate + dedup (against the bank and within this batch).
      const seenBatch = new Set<string>();
      let invalid = 0, skippedDuplicate = 0;
      const rows: Array<Record<string, unknown>> = [];
      const candidates: VerifyRow[] = [];  // index-aligned with `rows`, for the critic pass
      for (const g of generated) {
        const q = (g?.question ?? "").trim();
        const answers = g?.answers;
        const ci = g?.correct_index;
        if (!q || !Array.isArray(answers) || answers.length !== 4 ||
            typeof ci !== "number" || ci < 0 || ci > 3 ||
            answers.some((a) => typeof a !== "string" || a.trim().length === 0)) {
          invalid++;
          continue;
        }
        const norm = normalize(q);
        if (existingNorm.has(norm) || seenBatch.has(norm)) { skippedDuplicate++; continue; }
        seenBatch.add(norm);
        const trimmed = answers.map((a) => a.trim());
        rows.push({
          id: `gen_${crypto.randomUUID()}`,
          state,
          difficulty,
          question: q,
          answers: trimmed.map((text, i) => ({ text, correct: i === ci })),
          generated_at: today,
          used_on: null,
          times_used: 0,
        });
        candidates.push({ question: q, answers: trimmed, correctIndex: ci });
      }

      // Critic pass: drop hallucinated / subjective / ambiguous questions before
      // they reach the live bank. Fails safe — a verifier error skips this cell's
      // insert rather than shipping unverified questions.
      let rejected = 0;
      if (verify && rows.length > 0) {
        let keep: boolean[];
        try {
          keep = await verifyQuestions(anthropicKey, stateName, candidates);
        } catch (e) {
          results.push({ state, difficulty, unusedBefore, requested, generated: generated.length, inserted: 0, skippedDuplicate, invalid, rejected: 0, error: `verify: ${String(e).slice(0, 150)}` });
          continue;
        }
        const kept: Array<Record<string, unknown>> = [];
        for (let i = 0; i < rows.length; i++) {
          if (keep[i]) kept.push(rows[i]);
          else rejected++;
        }
        rows.length = 0;
        rows.push(...kept);
      }

      let inserted = 0;
      if (!dryRun && rows.length > 0) {
        // Belt-and-suspenders: the unique index rejects any residual dupe.
        const { data: ins, error } = await sb
          .from("quiz_questions")
          .upsert(rows, { onConflict: "state,difficulty,normalized_question", ignoreDuplicates: true })
          .select("id");
        if (error) {
          results.push({ state, difficulty, unusedBefore, requested, generated: generated.length, inserted: 0, skippedDuplicate, invalid, rejected, error: error.message.slice(0, 160) });
          continue;
        }
        inserted = ins?.length ?? 0;
        totalInserted += inserted;
      }

      results.push({
        state, difficulty, unusedBefore, requested,
        generated: generated.length,
        inserted: dryRun ? 0 : inserted,
        wouldInsert: dryRun ? rows.length : undefined,
        skippedDuplicate, invalid, rejected,
      });
      console.log(`generate-quiz-questions: ${state}/${difficulty} unused=${unusedBefore} req=${requested} gen=${generated.length} ins=${dryRun ? "(dry)" : inserted} dup=${skippedDuplicate} invalid=${invalid} rejected=${rejected}`);
    }
  }

  return new Response(JSON.stringify({ ok: true, dryRun, totalInserted, results }), { headers: cors() });
});
