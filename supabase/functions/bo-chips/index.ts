import "@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

// ════════════════════════════════════════════════════════════════
// bo-chips: generate 4 personalized starter prompts for the Bo chat
// welcome screen. Personalized off the user's onboarding picks, taste
// profile (engagement signal), recent plan, current local hour, and
// day-of-week. Falls back gracefully on missing data.
//
// Input:  POST { user_id, lat?, lng?, local_hour, day_of_week }
//         (day_of_week is 1=Mon..7=Sun to match ISO)
// Output: { chips: ["...", "...", "...", "..."] }
//
// Caching: in-memory, keyed by user_id + day-of-year + period
// (morning/afternoon/evening/late). ~4 generations per user per day
// max; re-opens within a period return the same chips so it feels
// stable rather than slot-machine.
// ════════════════════════════════════════════════════════════════

const CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
const CLAUDE_MODEL = "claude-haiku-4-5-20251001";
const CHIP_COUNT = 4;
const CACHE_TTL_MS = 6 * 60 * 60 * 1000; // 6h soft TTL — period bucket is the real expiry

interface CachedChips {
  chips: string[];
  timestamp: number;
}

const cache = new Map<string, CachedChips>();

function periodFor(hour: number): string {
  if (hour < 5) return "late";
  if (hour < 11) return "morning";
  if (hour < 17) return "afternoon";
  if (hour < 22) return "evening";
  return "late";
}

function dayOfYear(): number {
  const now = new Date();
  const start = new Date(now.getFullYear(), 0, 0);
  return Math.floor((now.getTime() - start.getTime()) / (24 * 60 * 60 * 1000));
}

function cacheKey(userId: string, hour: number): string {
  return `${userId}|${dayOfYear()}|${periodFor(hour)}`;
}

function cors(extra: Record<string, string> = {}): HeadersInit {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization, apikey",
    "Content-Type": "application/json",
    ...extra,
  };
}

interface RequestBody {
  user_id?: string;
  lat?: number;
  lng?: number;
  local_hour?: number;
  day_of_week?: number; // 1=Mon..7=Sun (ISO)
}

const DOW_NAMES = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response(null, { headers: cors() });
  }
  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "POST required" }), { status: 405, headers: cors() });
  }

  let body: RequestBody;
  try {
    body = await req.json();
  } catch {
    return new Response(JSON.stringify({ error: "Invalid JSON body" }), { status: 400, headers: cors() });
  }

  const userId = body.user_id?.trim();
  const hour = Number.isFinite(body.local_hour) ? body.local_hour! : new Date().getUTCHours();
  const dowIso = Number.isFinite(body.day_of_week) ? body.day_of_week! : 1;

  if (!userId) {
    return new Response(JSON.stringify({ error: "user_id required" }), { status: 400, headers: cors() });
  }

  const key = cacheKey(userId, hour);
  const hit = cache.get(key);
  if (hit && Date.now() - hit.timestamp < CACHE_TTL_MS) {
    return new Response(
      JSON.stringify({ chips: hit.chips, cached: true }),
      { status: 200, headers: cors() },
    );
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const anthropicKey = Deno.env.get("ANTHROPIC_API_KEY");

  if (!supabaseUrl || !serviceKey || !anthropicKey) {
    console.error("bo-chips: missing env (SUPABASE_URL / SERVICE_ROLE / ANTHROPIC_API_KEY)");
    return new Response(JSON.stringify({ error: "Server not configured" }), { status: 500, headers: cors() });
  }

  const supabase = createClient(supabaseUrl, serviceKey);

  // Pull user data in parallel — profile + taste. Each piece is optional; a
  // brand-new user with no data still gets generic-but-time-aware chips.
  // (We no longer query the user's upcoming plan — see the note below where
  // user context is assembled: plan-edit openers don't work in the main chat.)
  const profileQ = supabase
    .from("user_profiles")
    .select("first_name, city, selected_categories, selected_neighborhoods, selected_venues")
    .eq("id", userId)
    .maybeSingle();

  const tasteQ = supabase
    .from("user_taste_profiles")
    .select("category_scores, cuisine_scores, neighborhood_scores")
    .eq("user_id", userId)
    .maybeSingle();

  const [profileRes, tasteRes] = await Promise.all([profileQ, tasteQ]);
  const profile = profileRes.data ?? null;
  const taste = tasteRes.data ?? null;

  // Surface the top 3 taste scores so Claude can prefer them over
  // raw onboarding picks (which can be stale or aspirational).
  function topKeys(scoresJson: unknown, n: number): string[] {
    if (!scoresJson || typeof scoresJson !== "object") return [];
    const entries = Object.entries(scoresJson as Record<string, number>)
      .filter(([, v]) => typeof v === "number" && v > 0)
      .sort((a, b) => b[1] - a[1])
      .slice(0, n)
      .map(([k]) => k);
    return entries;
  }

  const topCategories = topKeys(taste?.category_scores, 3);
  const topCuisines = topKeys(taste?.cuisine_scores, 2);
  const topNeighborhoods = topKeys(taste?.neighborhood_scores, 2);

  const dayName = DOW_NAMES[Math.max(0, Math.min(6, dowIso - 1))];
  const period = periodFor(hour);

  const userContextLines: string[] = [];
  if (profile?.first_name) userContextLines.push(`First name: ${profile.first_name}`);
  if (profile?.city) userContextLines.push(`City: ${profile.city}`);
  if (profile?.selected_categories?.length) {
    userContextLines.push(`Onboarding categories: ${(profile.selected_categories as string[]).join(", ")}`);
  }
  if (profile?.selected_neighborhoods?.length) {
    userContextLines.push(`Favorite neighborhoods: ${(profile.selected_neighborhoods as string[]).join(", ")}`);
  }
  if (profile?.selected_venues?.length) {
    userContextLines.push(`Favorite venues: ${(profile.selected_venues as string[]).join(", ")}`);
  }
  if (topCategories.length) userContextLines.push(`Engagement-top categories (strongest signal): ${topCategories.join(", ")}`);
  if (topCuisines.length) userContextLines.push(`Top cuisines: ${topCuisines.join(", ")}`);
  if (topNeighborhoods.length) userContextLines.push(`Top neighborhoods (engagement): ${topNeighborhoods.join(", ")}`);
  // NOTE: we intentionally do NOT surface the user's upcoming saved plan here.
  // The opener chips feed the MAIN Ask Bo chat, which builds NEW plans and has
  // no mechanism to edit a previously-saved plan. A chip like "Add to my
  // boston date night plan" therefore led nowhere when tapped. Saved-plan
  // editing lives in the plan-detail screen's own Bo chat, not here.

  const systemPrompt = `You generate exactly ${CHIP_COUNT} short starter prompts for "Bo," a local-events AI assistant. The user sees these as tappable chips when they open the chat — tapping one sends that prompt to Bo verbatim.

STYLE (hard rules):
- 2–5 words each. No prompt longer than 5 words.
- Sentence case (capitalize first word + proper nouns only). No title case. No trailing punctuation except "?".
- First-person or imperative, like a person dictating: "Date night Friday", "What's on at X?", "Free things tonight".
- No quotes, no numbering, no bullets, no emoji. One chip per line.
- No dates, no numerals in the chip text. Generic time references only ("tonight", "this weekend", "Friday").

CONTENT (in priority order):
1. ALWAYS make the FIRST chip a time-of-day anchor that fits the current local time/day-of-week. Examples:
   - Friday evening → "Plans for tonight"
   - Saturday morning → "Things to do today"
   - Sunday afternoon → "Sunday plans"
   - Tuesday morning → "Things to do this week"
   - Late night any day → "Plans for tomorrow"
2. Use strongest user signals when they exist (favorite venue → "What's on at X?", top engagement category → "Live music this week"). Engagement-top categories beat onboarding categories when both exist.
3. Fill remaining slots with prompts tuned to local time/day: weekend prompts if it's Thu–Sat, midweek prompts otherwise.
4. NEVER repeat the same intent twice. NEVER reference the user's first name in the chip text. NEVER mention "Bo" or "the assistant" in the chip. NEVER make a chip about adding to / editing an existing saved plan — these openers feed the main chat, which only builds new plans.

Return ONLY the ${CHIP_COUNT} chips, one per line. No preamble, no commentary, no trailing notes.`;

  const userPrompt = `CURRENT CONTEXT:
- Local hour: ${hour} (${period})
- Day of week: ${dayName}

USER CONTEXT:
${userContextLines.length > 0 ? userContextLines.join("\n") : "(no profile data — return generic time-aware chips)"}

Return ${CHIP_COUNT} chips, one per line.`;

  async function claudeCall(): Promise<Response> {
    const reqBody = JSON.stringify({
      model: CLAUDE_MODEL,
      max_tokens: 300,
      system: systemPrompt,
      messages: [{ role: "user", content: userPrompt }],
    });
    let lastRes: Response | null = null;
    for (let attempt = 0; attempt < 3; attempt++) {
      if (attempt > 0) await new Promise(r => setTimeout(r, 800 * attempt));
      const res = await fetch(CLAUDE_API_URL, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "x-api-key": anthropicKey,
          "anthropic-version": "2023-06-01",
        },
        body: reqBody,
      });
      if (res.ok) return res;
      lastRes = res;
      const transient = res.status === 429 || res.status === 503 || res.status === 529 || (res.status >= 500 && res.status < 600);
      console.error(`bo-chips: Claude attempt ${attempt + 1} returned ${res.status} (transient=${transient})`);
      if (!transient) return res;
    }
    return lastRes!;
  }

  const claudeRes = await claudeCall();
  if (!claudeRes.ok) {
    const errText = await claudeRes.text();
    console.error(`bo-chips: Claude final error ${claudeRes.status}: ${errText.slice(0, 200)}`);
    return new Response(
      JSON.stringify({ error: "Claude request failed", status: claudeRes.status }),
      { status: 502, headers: cors() },
    );
  }

  const claudeData = await claudeRes.json();
  const rawText = (claudeData?.content?.[0]?.text ?? "") as string;

  // Parse one per line, strip list markers / quotes, drop garbage.
  const chips = rawText
    .split(/\r?\n/)
    .map(l => l.trim())
    .filter(l => l.length > 0)
    .filter(l => !/^(here|sure|okay|note:|chips?:)/i.test(l))
    .map(l => l.replace(/^[-*•\d]+[.)]?\s*/, "").replace(/^["']|["']$/g, "").trim())
    .filter(l => l.length >= 2 && l.length <= 40)
    .slice(0, CHIP_COUNT);

  if (chips.length < CHIP_COUNT) {
    console.warn(`bo-chips: Claude returned only ${chips.length} usable chips; serving partial`);
  }
  if (chips.length === 0) {
    return new Response(
      JSON.stringify({ error: "No chips generated" }),
      { status: 502, headers: cors() },
    );
  }

  cache.set(key, { chips, timestamp: Date.now() });
  console.log(`bo-chips: generated ${chips.length} chips for ${userId} (${period}, ${dayName})`);

  return new Response(
    JSON.stringify({ chips, cached: false }),
    { status: 200, headers: cors() },
  );
});
