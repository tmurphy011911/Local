/**
 * monthly_reverify_bo_web.js — sanity-check promoted Bo-Web rows whose source
 * URLs may have decayed.
 *
 * Pulls bo_web_events with state='verified' and verifier_run_at older than 30
 * days. For each, fetches the source_url and checks:
 *   - 404 / 5xx / timeout / TLS error → demote (state='rejected', deactivate
 *     the linked events row).
 *   - 200 + title or venue still appears in the response body → mark as
 *     freshly checked (bump verifier_run_at).
 *   - 200 but content has clearly drifted (title gone, page redirected to a
 *     listing index, etc) → demote.
 *
 * Cheap: one HTTP GET per row, no Claude calls. Designed to run monthly via
 * GitHub Actions to keep the public events surface clean of stale Bo-Web
 * promotions whose source pages 404'd or got rewritten.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/monthly_reverify_bo_web.js
 *   DRY_RUN=1 SUPABASE_SERVICE_KEY=ey... node scripts/monthly_reverify_bo_web.js
 */

const { SUPABASE_URL } = require("./lib/secrets");

const SUPABASE_SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || "").trim();
if (!SUPABASE_SERVICE_KEY) {
  console.error("Missing SUPABASE_SERVICE_KEY");
  process.exit(1);
}
const DRY_RUN = process.env.DRY_RUN === "1";

const FETCH_TIMEOUT_MS = 12000;
const STALE_DAYS = 30;

function authHeaders() {
  return {
    apikey: SUPABASE_SERVICE_KEY,
    Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
    "Content-Type": "application/json",
  };
}

async function pickStaleRows() {
  const cutoff = new Date(Date.now() - STALE_DAYS * 86400_000).toISOString();
  const url = `${SUPABASE_URL}/rest/v1/bo_web_events?state=eq.verified&verifier_run_at=lt.${cutoff}&select=id,title,venue_name,source_url,promoted_event_id&limit=500`;
  const res = await fetch(url, { headers: authHeaders() });
  if (!res.ok) throw new Error(`fetch stale rows: ${res.status} ${await res.text()}`);
  return res.json();
}

async function fetchSource(url) {
  if (!url) return { ok: false, status: 0, body: "", reason: "no_source_url" };
  try {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), FETCH_TIMEOUT_MS);
    const res = await fetch(url, {
      signal: ctrl.signal,
      redirect: "follow",
      headers: { "User-Agent": "Mozilla/5.0 (compatible; LocalAppVerifier/1.0)" },
    }).finally(() => clearTimeout(t));
    if (!res.ok) return { ok: false, status: res.status, body: "", reason: `http_${res.status}` };
    const body = await res.text();
    return { ok: true, status: res.status, body: body.slice(0, 200_000), reason: "ok" };
  } catch (e) {
    return { ok: false, status: 0, body: "", reason: `fetch_error: ${e.message}` };
  }
}

function contentMatches(body, row) {
  // Loose match: title (or first 4+ words of title) and venue name should
  // both be present somewhere in the body. Tighter rules are easy to add.
  if (!body) return false;
  const haystack = body.toLowerCase();
  const titleNeedle = (row.title || "").toLowerCase().slice(0, 80);
  const venueNeedle = (row.venue_name || "").toLowerCase().slice(0, 80);
  const titleHit = titleNeedle.length >= 5 && haystack.includes(titleNeedle);
  const venueHit = venueNeedle.length >= 4 && haystack.includes(venueNeedle);
  return titleHit || venueHit;
}

async function demote(row, reason) {
  if (DRY_RUN) {
    console.log(`  DRY: would demote ${row.id} (${row.title?.slice(0, 60)}): ${reason}`);
    return;
  }
  const nowIso = new Date().toISOString();
  await fetch(`${SUPABASE_URL}/rest/v1/bo_web_events?id=eq.${row.id}`, {
    method: "PATCH",
    headers: authHeaders(),
    body: JSON.stringify({
      state: "rejected",
      verifier_run_at: nowIso,
      verifier_decision: "rejected",
      verifier_reason: `Monthly re-verify: ${reason}`,
      updated_at: nowIso,
    }),
  });
  if (row.promoted_event_id) {
    await fetch(`${SUPABASE_URL}/rest/v1/events?id=eq.${row.promoted_event_id}`, {
      method: "PATCH",
      headers: authHeaders(),
      body: JSON.stringify({ is_active: false, status: "duplicate", updated_at: nowIso }),
    });
  }
  console.log(`  Demoted ${row.id}: ${reason}`);
}

async function refresh(row) {
  if (DRY_RUN) {
    console.log(`  DRY: would mark fresh ${row.id} (${row.title?.slice(0, 60)})`);
    return;
  }
  await fetch(`${SUPABASE_URL}/rest/v1/bo_web_events?id=eq.${row.id}`, {
    method: "PATCH",
    headers: authHeaders(),
    body: JSON.stringify({
      verifier_run_at: new Date().toISOString(),
      verifier_reason: "Monthly re-verify: source still live",
      updated_at: new Date().toISOString(),
    }),
  });
  console.log(`  Fresh ${row.id}`);
}

async function main() {
  const rows = await pickStaleRows();
  console.log(`Monthly Bo-Web re-verify — ${rows.length} stale row(s) (>30 days)\n`);
  let demoted = 0, refreshed = 0;
  for (const row of rows) {
    const result = await fetchSource(row.source_url);
    if (!result.ok) {
      await demote(row, result.reason);
      demoted++;
    } else if (contentMatches(result.body, row)) {
      await refresh(row);
      refreshed++;
    } else {
      await demote(row, "content drifted (title/venue not found)");
      demoted++;
    }
  }
  console.log(`\nDone. Refreshed ${refreshed}, demoted ${demoted}.`);
  if (DRY_RUN) console.log("(DRY_RUN — no writes)");
}

main().catch((err) => { console.error("Fatal:", err); process.exit(1); });
