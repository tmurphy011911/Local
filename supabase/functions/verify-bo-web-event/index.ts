import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

// ════════════════════════════════════════════════════════════════════════════
// verify-bo-web-event: corroborate a Bo-Web staging row using Anthropic
// Sonnet 4.6 + web_search and either promote it into the canonical events/
// venues tables or reject it.
//
// Flow:
//   1. Load the bo_web_events / bo_web_venues row by id.
//   2. Set state='verifying'.
//   3. Call Anthropic with web_search; system prompt has the rubric.
//   4. Sonnet returns { decision, reason, corroborating_urls }.
//      - verified  → INSERT into events/venues, set promoted_*_id and
//                    state='verified'.
//      - rejected  → state='rejected'.
//      - inconclusive → re-run with Opus 4.7 once. If still inconclusive,
//                       fail closed (state='rejected').
//   5. Log every Anthropic call to bo_web_api_calls (caller='verifier').
//
// Endpoint: POST / { id, kind: "event"|"venue" }
// ════════════════════════════════════════════════════════════════════════════

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

const ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
const SONNET_MODEL = "claude-sonnet-4-6";
const OPUS_MODEL = "claude-opus-4-7";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "authorization, content-type",
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  const body = await req.json().catch(() => null);
  if (!body?.id || (body.kind !== "event" && body.kind !== "venue")) {
    return json({ error: "id and kind=event|venue required" }, 400);
  }

  const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

  const anthropicKey = await getSecret(supabase, "ANTHROPIC_API_KEY");
  if (!anthropicKey) return json({ error: "missing ANTHROPIC_API_KEY in secrets" }, 500);

  const table = body.kind === "event" ? "bo_web_events" : "bo_web_venues";

  // Load row, claim it by setting state='verifying' (idempotency guard:
  // skip rows that aren't unverified anymore).
  const { data: row } = await supabase.from(table).select("*").eq("id", body.id).single();
  if (!row) return json({ error: "row not found" }, 404);
  if (row.state !== "unverified") return json({ skipped: true, current_state: row.state });

  await supabase.from(table)
    .update({ state: "verifying", updated_at: new Date().toISOString() })
    .eq("id", body.id);

  // Run verifier. Sonnet first; escalate to Opus only on inconclusive.
  let result = await runVerifier(SONNET_MODEL, anthropicKey, body.kind, row, supabase);
  if (result.decision === "inconclusive") {
    console.log(`[verify ${body.id}] Sonnet inconclusive — escalating to Opus`);
    result = await runVerifier(OPUS_MODEL, anthropicKey, body.kind, row, supabase);
    if (result.decision === "inconclusive") {
      // Fail closed.
      result = { decision: "rejected", reason: `Inconclusive after Sonnet+Opus. ${result.reason}`, corroborating_urls: result.corroborating_urls };
    }
  }

  if (result.decision === "verified") {
    await promote(supabase, body.kind, row, result);
    return json({ decision: "verified", id: body.id });
  } else {
    await supabase.from(table).update({
      state: "rejected",
      verifier_run_at: new Date().toISOString(),
      verifier_decision: "rejected",
      verifier_reason: result.reason.slice(0, 500),
      verifier_corroborating_urls: result.corroborating_urls,
      updated_at: new Date().toISOString(),
    }).eq("id", body.id);
    return json({ decision: "rejected", id: body.id, reason: result.reason });
  }
});

// ── Verifier core ─────────────────────────────────────────────────────────

interface VerifierResult {
  decision: "verified" | "rejected" | "inconclusive";
  reason: string;
  corroborating_urls: string[];
}

async function runVerifier(model: string, apiKey: string, kind: string, row: any, supabase: any): Promise<VerifierResult> {
  const trustedDomains = await loadTrustedDomains(supabase);
  const prompt = kind === "event" ? buildEventPrompt(row) : buildVenuePrompt(row);
  const systemPrompt = buildSystemPrompt(kind, trustedDomains);

  const requestBody = {
    model,
    max_tokens: 2048,
    system: systemPrompt,
    messages: [{ role: "user", content: prompt }],
    tools: [
      { type: "web_search_20250305", name: "web_search", max_uses: 3 },
    ],
  };

  let totalSearches = 0;
  let textOut = "";

  try {
    const res = await fetch(ANTHROPIC_URL, {
      method: "POST",
      headers: {
        "x-api-key": apiKey,
        "anthropic-version": "2023-06-01",
        "Content-Type": "application/json",
      },
      body: JSON.stringify(requestBody),
    });
    if (!res.ok) {
      const errBody = await res.text();
      console.log(`[verify] ${model} HTTP ${res.status}: ${errBody.slice(0, 300)}`);
      return { decision: "inconclusive", reason: `${model} API error ${res.status}`, corroborating_urls: [] };
    }
    const respJson = await res.json();
    for (const block of respJson.content || []) {
      if (block.type === "text") textOut += block.text;
      if (block.type === "server_tool_use" && block.name === "web_search") totalSearches++;
    }

    // Log each web_search invocation for cost tracking.
    for (let i = 0; i < totalSearches; i++) {
      await supabase.from("bo_web_api_calls").insert({
        caller: "verifier",
        user_id: null,
        tool: "web_search",
        query: null,
        result_count: null,
        cost_usd_estimate: 0.01 + 0.002 * 5,
      });
    }
  } catch (e: any) {
    console.log(`[verify] exception: ${e?.message}`);
    return { decision: "inconclusive", reason: `exception: ${e?.message}`, corroborating_urls: [] };
  }

  return parseVerifierJson(textOut);
}

function parseVerifierJson(text: string): VerifierResult {
  // Sonnet sometimes wraps JSON in prose or fences. Pull out the largest
  // JSON object, default to inconclusive if we can't parse.
  const match = text.match(/\{[\s\S]*\}/);
  if (!match) return { decision: "inconclusive", reason: "No JSON in verifier output", corroborating_urls: [] };
  try {
    const o = JSON.parse(match[0]);
    const d = String(o.decision || "").toLowerCase();
    const decision = (d === "verified" || d === "rejected") ? d : "inconclusive";
    const urls = Array.isArray(o.corroborating_urls) ? o.corroborating_urls.slice(0, 10).map((u: any) => String(u)) : [];
    return { decision, reason: String(o.reason || "").slice(0, 500), corroborating_urls: urls };
  } catch {
    return { decision: "inconclusive", reason: "Verifier JSON parse failed", corroborating_urls: [] };
  }
}

// ── Prompts ───────────────────────────────────────────────────────────────

function buildSystemPrompt(kind: string, trustedDomains: TrustedDomains): string {
  const tier1 = trustedDomains.tier1.join(", ") || "(none seeded)";
  const tier2Hint = "*.gov, *.edu, *.org, the venue's own primary domain";
  const tier3 = trustedDomains.tier3.join(", ") || "(none seeded)";
  const tier4 = trustedDomains.tier4.join(", ") || "(none seeded)";
  return `You are a fact-checker for an events/venues app. Your job is to corroborate that a row submitted by a user-facing assistant is REAL, with REAL details, before it gets surfaced to other users in carousels or search.

You have one tool: web_search (max 3 uses). Use it efficiently — one or two well-formed queries usually suffice.

YOU MUST RESPOND WITH STRICT JSON IN THIS EXACT SHAPE (no prose around it):
{ "decision": "verified" | "rejected" | "inconclusive", "reason": "<one sentence>", "corroborating_urls": ["<url1>", "<url2>"] }

RUBRIC FOR ${kind === "event" ? "EVENTS" : "VENUES"}:

${kind === "event" ? `
- Date: start_at MUST be in the FUTURE relative to today. Past = reject.
- Real: 2+ independent web sources (different second-level domains) must mention this exact event at this date+venue. Hits in the search results that contradict each other (different dates, different venues) = reject.
- Source tiering: at least ONE source must be on the trusted list below; the second can be anywhere, but a stray blog or a single SEO-spam page is not enough.
- Hallucination patterns to reject:
  * "Annual" + future-year claim for venues that have no record of past instances.
  * Generic-sounding event name with no specific organizer or details.
  * Date the model 'rounded' (e.g. listed June 11-14 when something only occurs in late June).
` : `
- Real: the named place exists at the named city/state with reasonable address/coordinates. Search "[name] [city]" and look for an official website OR a third-party listing (Google Places, Yelp, Eater, OpenTable, etc.).
- Reject if no source mentions it at all.
- Reject if it's clearly closed/permanently shuttered.
`}

TRUSTED DOMAINS:
- Tier 1 (any one is enough): ${tier1}
- Tier 2 (any one): ${tier2Hint}
- Tier 3 (state media, count as supporting): ${tier3}
- Tier 4 (local listings, count only with a Tier-1/2/3 also present): ${tier4}

DECISION:
- "verified": rubric passes, you're confident.
- "rejected": rubric fails (past date, contradicting sources, no real-world references).
- "inconclusive": you couldn't tell after up to 3 searches. (Will be re-run with a more capable model.)

Always include 1-2 corroborating_urls when you say "verified". When "rejected", include any URL that contradicted the claim.`;
}

function buildEventPrompt(row: any): string {
  return `Verify this event row. The assistant claims:
- Title: ${row.title}
- Description: ${row.description ?? "(none)"}
- Start: ${row.start_at}
- End: ${row.end_at ?? "(not specified)"}
- Venue: ${row.venue_name ?? "(none)"}
- Address: ${row.venue_address ?? "(none)"}
- City/State: ${row.venue_city ?? "?"} / ${row.venue_state ?? "?"}
- Source URL: ${row.source_url ?? "(none)"}
- Source domains: ${(row.source_domains || []).join(", ") || "(none)"}
- Today is: ${new Date().toISOString().slice(0, 10)}

Decide: verified, rejected, or inconclusive. Respond with JSON only.`;
}

function buildVenuePrompt(row: any): string {
  return `Verify this venue row.
- Name: ${row.name}
- Address: ${row.address ?? "(none)"}
- City/State: ${row.city ?? "?"} / ${row.state_code ?? "?"}
- Lat/Lng: ${row.lat ?? "?"}, ${row.lng ?? "?"}
- Website: ${row.website ?? "(none)"}
- Source URL: ${row.source_url ?? "(none)"}

Decide: verified, rejected, or inconclusive. Respond with JSON only.`;
}

// ── Promotion ─────────────────────────────────────────────────────────────

async function promote(supabase: any, kind: string, row: any, result: VerifierResult) {
  const nowIso = new Date().toISOString();
  if (kind === "event") {
    const eventRow: any = {
      title: row.title,
      description: row.description,
      start_at: row.start_at,
      end_at: row.end_at,
      category: row.category || "community",
      ticket_url: row.ticket_url,
      source_url: row.source_url,
      image_url: row.image_url,
      is_free: row.is_free,
      price_min: row.price_min,
      price_max: row.price_max,
      venue_name: row.venue_name,
      venue_address: row.venue_address,
      venue_city: row.venue_city,
      venue_state: row.venue_state,
      venue_lat: row.venue_lat,
      venue_lng: row.venue_lng,
      source: "bo_web_promoted",
      source_id: row.id,
      dedupe_hash: `bo_web:${row.id}`,
      status: "active",
      is_active: true,
      city: row.venue_city || null,
      last_synced_at: nowIso,
      updated_at: nowIso,
    };
    const { data: inserted, error: insErr } = await supabase
      .from("events")
      .insert(eventRow)
      .select("id")
      .single();
    if (insErr) {
      console.log(`[promote event] insert error: ${insErr.message}`);
      // Don't crash — mark verified but un-promoted.
      await supabase.from("bo_web_events").update({
        state: "verified",
        verifier_run_at: nowIso,
        verifier_decision: "verified",
        verifier_reason: `${result.reason} (promotion failed: ${insErr.message.slice(0,100)})`,
        verifier_corroborating_urls: result.corroborating_urls,
        updated_at: nowIso,
      }).eq("id", row.id);
      return;
    }
    await supabase.from("bo_web_events").update({
      state: "verified",
      verifier_run_at: nowIso,
      verifier_decision: "verified",
      verifier_reason: result.reason,
      verifier_corroborating_urls: result.corroborating_urls,
      promoted_event_id: inserted.id,
      updated_at: nowIso,
    }).eq("id", row.id);
  } else {
    // Venue
    const venueRow: any = {
      name: row.name,
      address: row.address,
      city: row.city,
      state: row.state_code,
      lat: row.lat,
      lng: row.lng,
      category: row.category,
      website: row.website,
      photo_url: row.image_url,
      source: "bo_web_promoted",
      created_at: nowIso,
      updated_at: nowIso,
    };
    const { data: inserted, error: insErr } = await supabase
      .from("venues")
      .insert(venueRow)
      .select("id")
      .single();
    if (insErr) {
      await supabase.from("bo_web_venues").update({
        state: "verified",
        verifier_run_at: nowIso,
        verifier_decision: "verified",
        verifier_reason: `${result.reason} (promotion failed: ${insErr.message.slice(0,100)})`,
        verifier_corroborating_urls: result.corroborating_urls,
        updated_at: nowIso,
      }).eq("id", row.id);
      return;
    }
    await supabase.from("bo_web_venues").update({
      state: "verified",
      verifier_run_at: nowIso,
      verifier_decision: "verified",
      verifier_reason: result.reason,
      verifier_corroborating_urls: result.corroborating_urls,
      promoted_venue_id: inserted.id,
      updated_at: nowIso,
    }).eq("id", row.id);
  }
}

// ── Helpers ───────────────────────────────────────────────────────────────

interface TrustedDomains { tier1: string[]; tier3: string[]; tier4: string[]; }

async function loadTrustedDomains(supabase: any): Promise<TrustedDomains> {
  const { data } = await supabase.from("bo_web_trusted_domains").select("domain, tier");
  const tier1 = (data ?? []).filter((r: any) => r.tier === 1).map((r: any) => r.domain);
  const tier3 = (data ?? []).filter((r: any) => r.tier === 3).map((r: any) => r.domain);
  const tier4 = (data ?? []).filter((r: any) => r.tier === 4).map((r: any) => r.domain);
  return { tier1, tier3, tier4 };
}

async function getSecret(supabase: any, key: string): Promise<string | null> {
  const { data } = await supabase.from("secrets").select("value").eq("key", key).maybeSingle();
  return data?.value ?? null;
}

function json(body: any, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...CORS },
  });
}
