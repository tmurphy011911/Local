import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

// ════════════════════════════════════════════════════════════════════════════
// bo-web-budget: gate decision for Bo's web_search / web_fetch tools.
//
// Called by ChatViewModel + PlanChatViewModel BEFORE each Bo turn. Returns
// whether to include the web tools in this turn's tools array, plus context
// the Bo system prompt uses to deliver the right user-facing message when
// blocked (live-state list, etc.).
//
// Logging side: clients POST after each turn with the count of web_search /
// web_fetch invocations Anthropic ran. We insert into bo_web_api_calls with
// estimated cost. Aggregations on that table drive the next gate decision.
//
// Endpoints:
//   GET  /?user_id=<uuid>           → check gate, return decision
//   POST / { user_id, calls: [...] } → log calls
// ════════════════════════════════════════════════════════════════════════════

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

// Caps (event-related and factual share the same per-user counters; the
// 20+10 distinction lives in the Bo system prompt — Bo is told which kind
// it's making and self-rations within a single turn).
const PER_USER_PER_DAY = 30; // 20 event + 10 factual
const PER_USER_PER_HOUR = 10;
const MONTHLY_USD_CAP = 500;

// Cost model — Anthropic doesn't return per-call $. Rough estimates:
//   web_search: ~$0.01 base + ~$0.002 per result returned
//   web_fetch:  ~$0.005 per URL
function estimateCost(tool: string, resultCount: number | null): number {
  if (tool === "web_search") return 0.01 + 0.002 * (resultCount ?? 0);
  if (tool === "web_fetch") return 0.005;
  return 0;
}

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "authorization, content-type",
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });

  const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

  if (req.method === "POST") return handleLog(req, supabase);
  return handleCheck(req, supabase);
});

async function handleCheck(req: Request, supabase: any): Promise<Response> {
  const url = new URL(req.url);
  const userId = url.searchParams.get("user_id");
  if (!userId) return json({ error: "missing user_id" }, 400);

  // Always include the live-states list — clients use it for "Local is not
  // yet live in your state. It is currently live in [list]" copy.
  const { data: liveStates } = await supabase
    .from("states")
    .select("name")
    .eq("is_live", true);
  const liveStatesList = (liveStates ?? []).map((s: any) => s.name).join(", ");

  // 1. Live-state gate — hard rule. Bo doesn't search the web for users
  //    physically in a state we haven't launched in.
  const userState = await resolveUserState(supabase, userId);
  if (!userState || !userState.is_live) {
    return json({
      allow_web_search: false,
      allow_web_fetch: false,
      reason: "not_live_state",
      user_state: userState?.name ?? null,
      live_states_list: liveStatesList,
    });
  }

  // 2. Monthly $500 cap (global). Includes verifier + enricher calls too.
  const monthStart = startOfMonthIso();
  const { data: monthCalls } = await supabase
    .from("bo_web_api_calls")
    .select("cost_usd_estimate")
    .gte("created_at", monthStart);
  const monthSpend = (monthCalls ?? []).reduce(
    (s: number, r: any) => s + Number(r.cost_usd_estimate),
    0,
  );
  if (monthSpend >= MONTHLY_USD_CAP) {
    return json({
      allow_web_search: false,
      allow_web_fetch: false,
      reason: "monthly_cap",
      user_state: userState.name,
      live_states_list: liveStatesList,
    });
  }

  // 3. Per-user 30/day (Eastern time reset). Counts BOTH chat-side and
  //    user-attributed verifier calls, but verifier currently runs as
  //    caller='verifier' with user_id=null so this filter is just chat.
  const dayStart = startOfDayEasternIso();
  const { count: dayCalls } = await supabase
    .from("bo_web_api_calls")
    .select("*", { count: "exact", head: true })
    .eq("user_id", userId)
    .gte("created_at", dayStart);
  if ((dayCalls ?? 0) >= PER_USER_PER_DAY) {
    return json({
      allow_web_search: false,
      allow_web_fetch: false,
      reason: "user_day_cap",
      user_state: userState.name,
      live_states_list: liveStatesList,
    });
  }

  // 4. Per-user 10/hour rolling window — burst guard.
  const hourAgo = new Date(Date.now() - 60 * 60 * 1000).toISOString();
  const { count: hourCalls } = await supabase
    .from("bo_web_api_calls")
    .select("*", { count: "exact", head: true })
    .eq("user_id", userId)
    .gte("created_at", hourAgo);
  if ((hourCalls ?? 0) >= PER_USER_PER_HOUR) {
    return json({
      allow_web_search: false,
      allow_web_fetch: false,
      reason: "user_hour_cap",
      user_state: userState.name,
      live_states_list: liveStatesList,
    });
  }

  return json({
    allow_web_search: true,
    allow_web_fetch: true,
    reason: "ok",
    user_state: userState.name,
    live_states_list: liveStatesList,
    remaining_day: PER_USER_PER_DAY - (dayCalls ?? 0),
    remaining_hour: PER_USER_PER_HOUR - (hourCalls ?? 0),
    monthly_spend_usd: monthSpend,
  });
}

async function handleLog(req: Request, supabase: any): Promise<Response> {
  const body = await req.json().catch(() => null);
  if (!body || !Array.isArray(body.calls)) {
    return json({ error: "bad request" }, 400);
  }
  const rows = body.calls.map((c: any) => ({
    caller: body.caller || "bo_chat",
    user_id: body.user_id || null,
    tool: c.tool,
    query: typeof c.query === "string" ? c.query.slice(0, 500) : null,
    result_count: typeof c.result_count === "number" ? c.result_count : null,
    cost_usd_estimate: estimateCost(c.tool, c.result_count ?? null),
  }));
  if (rows.length === 0) return json({ logged: 0 });
  const { error } = await supabase.from("bo_web_api_calls").insert(rows);
  if (error) return json({ error: error.message }, 500);
  return json({ logged: rows.length });
}

// Resolve the user's state via user_profiles.city → metros.state_code.
// Falls back to user_profiles.user_lat/lng → states bbox match.
async function resolveUserState(supabase: any, userId: string): Promise<{ code: string; name: string; is_live: boolean } | null> {
  const { data: profile } = await supabase
    .from("user_profiles")
    .select("city, user_lat, user_lng, manual_lat, manual_lng, location_mode")
    .eq("id", userId)
    .single();
  if (!profile) return null;

  // Path 1: city → metros.
  if (profile.city) {
    const { data: metro } = await supabase
      .from("metros")
      .select("state_code")
      .ilike("name", profile.city)
      .limit(1)
      .maybeSingle();
    if (metro?.state_code) {
      const { data: st } = await supabase
        .from("states")
        .select("code, name, is_live")
        .eq("code", metro.state_code)
        .single();
      if (st) return st;
    }
  }

  // Path 2: lat/lng bbox — picks the right state for current-location users.
  const lat = profile.location_mode === "manual" ? profile.manual_lat : profile.user_lat;
  const lng = profile.location_mode === "manual" ? profile.manual_lng : profile.user_lng;
  if (lat != null && lng != null) {
    const { data: states } = await supabase
      .from("states")
      .select("code, name, is_live, lat_min, lat_max, lng_min, lng_max")
      .gte("lat_max", lat)
      .lte("lat_min", lat)
      .gte("lng_max", lng)
      .lte("lng_min", lng);
    if (states && states.length > 0) return states[0];
  }

  return null;
}

function startOfMonthIso(): string {
  const d = new Date();
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1)).toISOString();
}

// Approximate America/New_York midnight. Eastern is UTC-5 (UTC-4 during DST);
// we use a conservative -5 so the window is always at least a full local day.
function startOfDayEasternIso(): string {
  const now = new Date();
  // Shift into Eastern, take date, build start-of-day in Eastern, shift back.
  const easternNow = new Date(now.getTime() - 5 * 60 * 60 * 1000);
  const startEastern = new Date(Date.UTC(
    easternNow.getUTCFullYear(),
    easternNow.getUTCMonth(),
    easternNow.getUTCDate(),
    0, 0, 0,
  ));
  // Push back to UTC: midnight Eastern = 05:00 UTC.
  return new Date(startEastern.getTime() + 5 * 60 * 60 * 1000).toISOString();
}

function json(body: any, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...CORS },
  });
}
