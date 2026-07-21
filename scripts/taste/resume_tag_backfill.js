#!/usr/bin/env node
/**
 * resume_tag_backfill.js — THROTTLED resume of the TAGS_ONLY tag backfill.
 *
 * WHY THIS EXISTS: the original run (scripts/classify_events.js TAGS_ONLY) wrote
 * ~72k of 129k tags, then its concurrency-10 / no-delay write loop saturated the
 * Nano prod DB's IO and took the whole app down (PostgREST 522 on everything).
 * The Anthropic Batch job SUCCEEDED though — every result is retained ~29 days —
 * so finishing is just: re-download the batch results and write the REMAINING
 * untagged rows, gently.
 *
 * SAFETY (the whole point):
 *   - Reuses the existing batch — NO new LLM spend, NO new batch submitted.
 *   - Writes only rows still missing tags (tag_vocab_version != current).
 *   - Small serial batches + delay between them + low concurrency.
 *   - Live DB-health probe before each batch: if the DB is slow, it BACKS OFF
 *     (grows the delay) instead of piling on. If it's unreachable, it PAUSES.
 *   - Ctrl-C safe: progress is driven off the DB's own tag_vocab_version, so a
 *     re-run resumes exactly where it stopped.
 *
 * ENV:
 *   SUPABASE_SERVICE_KEY  (required)  prod service role key
 *   ANTHROPIC_API_KEY     (required)  to download the batch results
 *   RESUME_BATCH_ID       (default msgbatch_016q6srzpNuQeWiUpjAMpJkr)
 *   SUPABASE_URL          (default prod)
 *   WRITE_BATCH           (default 25)   rows per serial batch
 *   WRITE_CONCURRENCY     (default 4)    concurrent PATCHes within a batch
 *   BATCH_DELAY_MS        (default 600)  pause between batches
 *   DRY_RUN=1                            report counts, write nothing
 */

const { TAG_VOCAB_VERSION, TAG_MAX, ALL_TAGS } = require("./tag_vocabulary");
const { getSecrets } = require("../lib/secrets");

const SUPABASE_URL = process.env.SUPABASE_URL || "https://blifubxslaucshmlphdc.supabase.co";
const SUPABASE_SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").replace(/\s/g, "");
// ANTHROPIC_API_KEY is resolved in main() — from env, else the prod `secrets`
// table via getSecrets (same bootstrap the classifier uses).
let ANTHROPIC_API_KEY = process.env.ANTHROPIC_API_KEY;
const BATCH_ID = process.env.RESUME_BATCH_ID || "msgbatch_016q6srzpNuQeWiUpjAMpJkr";
const API_BASE = "https://api.anthropic.com/v1";

const WRITE_BATCH = parseInt(process.env.WRITE_BATCH || "25", 10);
const WRITE_CONCURRENCY = parseInt(process.env.WRITE_CONCURRENCY || "4", 10);
let BATCH_DELAY_MS = parseInt(process.env.BATCH_DELAY_MS || "600", 10);
const DRY_RUN = process.env.DRY_RUN === "1";

// Health-probe thresholds. If a light read takes longer than SLOW_MS we grow the
// delay; if it times out HARD_MS we pause and wait for recovery.
const PROBE_SLOW_MS = 1500;
const PROBE_HARD_MS = 8000;
const MAX_DELAY_MS = 5000;

const VALID_TAGS_SET = new Set(ALL_TAGS);

if (!SUPABASE_SERVICE_KEY) {
  console.error("Missing SUPABASE_SERVICE_KEY env var (bootstraps everything)");
  process.exit(1);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function sanitizeEntities(arr, max = 6) {
  if (!Array.isArray(arr)) return [];
  const out = [], seen = new Set();
  for (const raw of arr) {
    if (typeof raw !== "string") continue;
    const slug = raw.trim().toLowerCase()
      .replace(/&/g, " and ")
      .replace(/[^a-z0-9]+/g, "_")
      .replace(/^_+|_+$/g, "");
    if (slug.length < 2 || slug.length > 60 || seen.has(slug)) continue;
    seen.add(slug);
    out.push(slug);
    if (out.length >= max) break;
  }
  return out;
}

// ── 1. Download the completed batch results (one big read from Anthropic) ──
async function downloadResults() {
  console.log(`Downloading results for batch ${BATCH_ID} …`);
  // Confirm it ended and results are available.
  const meta = await fetch(`${API_BASE}/messages/batches/${BATCH_ID}`, {
    headers: { "x-api-key": ANTHROPIC_API_KEY, "anthropic-version": "2023-06-01" },
  });
  if (!meta.ok) throw new Error(`Batch lookup failed (${meta.status}): ${await meta.text()}`);
  const metaJson = await meta.json();
  console.log(`  status: ${metaJson.processing_status}, counts:`, metaJson.request_counts);
  if (!metaJson.results_url && metaJson.processing_status !== "ended") {
    throw new Error(`Batch not ended (${metaJson.processing_status}) — cannot resume`);
  }

  const res = await fetch(`${API_BASE}/messages/batches/${BATCH_ID}/results`, {
    headers: { "x-api-key": ANTHROPIC_API_KEY, "anthropic-version": "2023-06-01" },
  });
  if (!res.ok) throw new Error(`Results fetch failed (${res.status}): ${await res.text()}`);
  const text = await res.text();
  const lines = text.split("\n").filter(Boolean);

  // Flatten every classification into id -> {tags, performers, brands}.
  const byId = new Map();
  let parseErrors = 0, rawClassifications = 0;
  for (const line of lines) {
    let result;
    try { result = JSON.parse(line); } catch { parseErrors++; continue; }
    if (!result.result || result.result.type !== "succeeded") continue;
    let responseText;
    try { responseText = result.result.message.content[0].text.trim(); } catch { parseErrors++; continue; }
    const cb = responseText.match(/```(?:json)?\s*([\s\S]*?)```/);
    const jsonStr = cb ? cb[1].trim() : responseText;
    let classifications;
    try { classifications = JSON.parse(jsonStr); } catch { parseErrors++; continue; }
    if (!Array.isArray(classifications)) continue;
    for (const c of classifications) {
      if (!c || !c.id) continue;
      rawClassifications++;
      const tags = Array.isArray(c.tags)
        ? [...new Set(c.tags.filter((t) => VALID_TAGS_SET.has(t)))].slice(0, TAG_MAX)
        : [];
      byId.set(c.id, {
        tags,
        performers: sanitizeEntities(c.performers),
        brands: sanitizeEntities(c.brands),
      });
    }
  }
  console.log(`  parsed ${lines.length} result lines → ${byId.size} unique events (${rawClassifications} classifications, ${parseErrors} parse skips)\n`);
  return byId;
}

// ── 2. Which events still need tags (idempotent resume) ──
async function fetchUntaggedIds() {
  console.log("Fetching untagged event ids (tag_vocab_version missing/stale, active, upcoming) …");
  const ids = new Set();
  const pageSize = 1000;
  const nowIso = new Date().toISOString();
  let cursor = nowIso, firstPage = true;
  while (true) {
    const cmp = firstPage ? "gte" : "gt";
    const url = `${SUPABASE_URL}/rest/v1/events`
      + `?select=id,start_at`
      + `&is_active=eq.true`
      + `&start_at=${cmp}.${encodeURIComponent(cursor)}`
      + `&or=(tag_vocab_version.is.null,tag_vocab_version.neq.${TAG_VOCAB_VERSION})`
      + `&order=start_at.asc&limit=${pageSize}`;
    const res = await fetch(url, {
      headers: { apikey: SUPABASE_SERVICE_KEY, Authorization: `Bearer ${SUPABASE_SERVICE_KEY}` },
    });
    if (!res.ok) throw new Error(`Untagged fetch failed (${res.status}): ${await res.text()}`);
    const rows = await res.json();
    for (const r of rows) ids.add(r.id);
    process.stdout.write(`  found ${ids.size}\r`);
    if (rows.length < pageSize) break;
    cursor = rows[rows.length - 1].start_at;
    firstPage = false;
    await sleep(100); // gentle even on the read sweep
  }
  console.log(`  found ${ids.size} events still needing tags\n`);
  return ids;
}

// ── 3. DB health probe → adaptive backoff ──
async function probeHealth() {
  const start = Date.now();
  try {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), PROBE_HARD_MS);
    const res = await fetch(`${SUPABASE_URL}/rest/v1/app_config?limit=1&select=key`, {
      headers: { apikey: SUPABASE_SERVICE_KEY, Authorization: `Bearer ${SUPABASE_SERVICE_KEY}` },
      signal: ctrl.signal,
    });
    clearTimeout(t);
    const ms = Date.now() - start;
    return { ok: res.ok, ms };
  } catch {
    return { ok: false, ms: Date.now() - start };
  }
}

async function writeOne(id, payload) {
  const body = { tag_vocab_version: TAG_VOCAB_VERSION };
  if (payload.tags.length) body.tags = payload.tags;
  if (payload.performers.length) body.performers = payload.performers;
  if (payload.brands.length) body.brands = payload.brands;
  const url = `${SUPABASE_URL}/rest/v1/events?id=eq.${id}`;
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const res = await fetch(url, {
        method: "PATCH",
        headers: {
          apikey: SUPABASE_SERVICE_KEY,
          Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
          "Content-Type": "application/json",
          Prefer: "return=minimal",
        },
        body: JSON.stringify(body),
      });
      if (res.ok) return true;
      if (attempt === 0) { await sleep(1000); continue; }
      console.error(`  PATCH ${id} failed ${res.status}: ${(await res.text()).slice(0, 120)}`);
      return false;
    } catch (e) {
      if (attempt === 0) { await sleep(1000); continue; }
      console.error(`  PATCH ${id} threw: ${e.message}`);
      return false;
    }
  }
  return false;
}

async function main() {
  console.log("=== Throttled tag-backfill resume ===");
  console.log(`target=${SUPABASE_URL}  batch=${BATCH_ID}`);
  console.log(`throttle: WRITE_BATCH=${WRITE_BATCH} CONCURRENCY=${WRITE_CONCURRENCY} DELAY=${BATCH_DELAY_MS}ms  DRY_RUN=${DRY_RUN}\n`);

  if (!ANTHROPIC_API_KEY) {
    const s = await getSecrets(["ANTHROPIC_API_KEY"]);
    ANTHROPIC_API_KEY = s.ANTHROPIC_API_KEY;
    if (!ANTHROPIC_API_KEY) throw new Error("ANTHROPIC_API_KEY not in env or prod secrets table");
    console.log("Resolved ANTHROPIC_API_KEY from prod secrets table.\n");
  }

  const results = await downloadResults();
  const untagged = await fetchUntaggedIds();

  // Only write rows that are BOTH untagged AND present in the batch results.
  const work = [];
  let missingFromBatch = 0;
  for (const id of untagged) {
    const payload = results.get(id);
    if (payload) work.push([id, payload]);
    else missingFromBatch++;
  }
  console.log(`To write: ${work.length}  (untagged ${untagged.size}, of which ${missingFromBatch} not in this batch — a later run/batch covers those)\n`);

  if (DRY_RUN) {
    console.log("DRY RUN — nothing written. Sample:");
    for (const [id, p] of work.slice(0, 3)) console.log("  ", id, JSON.stringify(p));
    return;
  }
  if (work.length === 0) { console.log("Nothing to do — all caught up."); return; }

  let written = 0, failed = 0, batchNo = 0;
  const totalBatches = Math.ceil(work.length / WRITE_BATCH);
  const startTs = Date.now();

  for (let i = 0; i < work.length; i += WRITE_BATCH) {
    batchNo++;

    // Health gate before every batch.
    const h = await probeHealth();
    if (!h.ok || h.ms > PROBE_HARD_MS) {
      console.warn(`  ⚠ DB slow/unreachable (${h.ms}ms). Pausing 30s and backing off…`);
      BATCH_DELAY_MS = Math.min(MAX_DELAY_MS, Math.round(BATCH_DELAY_MS * 1.5));
      await sleep(30000);
    } else if (h.ms > PROBE_SLOW_MS) {
      BATCH_DELAY_MS = Math.min(MAX_DELAY_MS, Math.round(BATCH_DELAY_MS * 1.3));
      console.warn(`  ↑ DB warm (${h.ms}ms) — delay raised to ${BATCH_DELAY_MS}ms`);
    }

    const slice = work.slice(i, i + WRITE_BATCH);
    // Low concurrency within the batch.
    for (let j = 0; j < slice.length; j += WRITE_CONCURRENCY) {
      const wave = slice.slice(j, j + WRITE_CONCURRENCY);
      const oks = await Promise.all(wave.map(([id, p]) => writeOne(id, p)));
      for (const ok of oks) ok ? written++ : failed++;
    }

    if (batchNo % 10 === 0 || batchNo === totalBatches) {
      const pct = ((batchNo / totalBatches) * 100).toFixed(1);
      const rate = (written / ((Date.now() - startTs) / 1000)).toFixed(1);
      console.log(`  [${batchNo}/${totalBatches} ${pct}%] written=${written} failed=${failed} ~${rate}/s delay=${BATCH_DELAY_MS}ms`);
    }

    await sleep(BATCH_DELAY_MS);
  }

  const mins = ((Date.now() - startTs) / 60000).toFixed(1);
  console.log(`\n=== Done in ${mins} min — written ${written}, failed ${failed} ===`);
  if (failed > 0) console.log("Re-run to retry failures (idempotent — skips rows already at current version).");
}

main().catch((e) => { console.error("FATAL:", e); process.exit(1); });
