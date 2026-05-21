import "@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

// ════════════════════════════════════════════════════════════════
// yocal-prompts: generate a bunch of placeholder "search prompts" that
// the Home typewriter cycles through. Mix of venue-specific, event-
// specific, and generic intents, flavored by what's actually near the
// user's current location.
//
// Input:  GET /?lat=42.36&lng=-71.06
// Output: { prompts: ["What's on at Free Will Brewing?", ...] }
//
// Caching: 24h in-memory per rounded 0.1° lat/lng grid (~6 miles).
// Within a metro, ~1 Claude call/day for every user in that grid.
// ════════════════════════════════════════════════════════════════

const CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
const CLAUDE_MODEL = "claude-haiku-4-5-20251001";
const CACHE_TTL_MS = 24 * 60 * 60 * 1000; // 24h
const PROMPT_TARGET = 200;

interface CachedPrompts {
  prompts: string[];
  timestamp: number;
}

const cache = new Map<string, CachedPrompts>();

function geoKey(lat: number, lng: number): string {
  return `${Math.round(lat * 10) / 10},${Math.round(lng * 10) / 10}`;
}

function cors(extra: Record<string, string> = {}): HeadersInit {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization, apikey",
    "Content-Type": "application/json",
    ...extra,
  };
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response(null, { headers: cors() });
  }

  const url = new URL(req.url);
  const lat = parseFloat(url.searchParams.get("lat") ?? "");
  const lng = parseFloat(url.searchParams.get("lng") ?? "");

  if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
    return new Response(
      JSON.stringify({ error: "lat and lng query params required" }),
      { status: 400, headers: cors() },
    );
  }

  const key = geoKey(lat, lng);
  const hit = cache.get(key);
  if (hit && Date.now() - hit.timestamp < CACHE_TTL_MS) {
    return new Response(
      JSON.stringify({ prompts: hit.prompts, cached: true }),
      { status: 200, headers: cors() },
    );
  }

  // Miss — build a fresh prompt list.
  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const anthropicKey = Deno.env.get("ANTHROPIC_API_KEY");

  if (!supabaseUrl || !serviceKey || !anthropicKey) {
    console.error("yocal-prompts: missing env (SUPABASE_URL / SERVICE_ROLE / ANTHROPIC_API_KEY)");
    return new Response(
      JSON.stringify({ error: "Server not configured" }),
      { status: 500, headers: cors() },
    );
  }

  const supabase = createClient(supabaseUrl, serviceKey);

  // Collect local context — a handful of the most interesting venues + events
  // near the user. Keep the prompt small; we only need flavor, not completeness.
  const latDelta = 20 / 69.0; // ~20 mile box
  const lngDelta = 20 / 50.0;
  const nowIso = new Date().toISOString();
  const thirtyDays = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();

  const venuesQ = supabase
    .from("venues")
    .select("name, public_interest_rank, category, city")
    .eq("is_active", true)
    .gte("lat", lat - latDelta)
    .lte("lat", lat + latDelta)
    .gte("lng", lng - lngDelta)
    .lte("lng", lng + lngDelta)
    .gte("public_interest_rank", 40)
    .order("public_interest_rank", { ascending: false })
    .limit(25);

  const eventsQ = supabase
    .from("events")
    .select("title, venue_name, subcategory, start_at")
    .eq("is_active", true)
    .gte("start_at", nowIso)
    .lte("start_at", thirtyDays)
    .gte("venue_lat", lat - latDelta)
    .lte("venue_lat", lat + latDelta)
    .gte("venue_lng", lng - lngDelta)
    .lte("venue_lng", lng + lngDelta)
    // PostgREST `.neq(col, x)` translates to SQL `col <> x`, which
    // evaluates NULL for null cells and excludes those rows. Use the
    // explicit OR-with-IS-NULL idiom so events with null subcategory
    // (most crawler-sourced events) aren't silently dropped.
    .or("subcategory.is.null,subcategory.neq.no-public-interest")
    .order("start_at", { ascending: true })
    .limit(80);

  // Post-filter events whose titles suggest they're off-audience
  // (youth/scholastic, academic-internal, senior-center-only, etc.).
  // This app is for adults 25-40 — no middle school dances, no thesis
  // defenses, no varsity lacrosse games, no senior-center bingo.
  const OFF_AUDIENCE_PATTERNS = [
    /\b(middle school|high school|elementary school|preschool|kindergarten|pre-?k)\b/i,
    /\b(jv|junior varsity|varsity|freshman)\b/i,
    /\b(\bms\b|\bhs\b)\s+(dance|prom|game|match|tournament|meet|practice)/i,
    /\b(thesis defense|dissertation defense|qualifying exam|oral defense)\b/i,
    /\b(brown[\s-]?bag|lunch bunch|journal club|lab meeting|seminar series)\b/i,
    /\b(senior center|retirement community|assisted living|55\+)\b/i,
    /\b(aa meeting|na meeting|al-anon|recovery meeting)\b/i,
    /\b(storytime|story time|kids? craft|toddler|baby playgroup)\b/i,
    /\b(school committee|town council|city council|zoning board|abutters)\b/i,
    /\b(staff training|internal meeting|private event)\b/i,
    /\b(cal ripken|little league|pop warner|pee-?wee|t-?ball)\b/i,
  ];
  function isOffAudience(title: string): boolean {
    for (const re of OFF_AUDIENCE_PATTERNS) if (re.test(title)) return true;
    return false;
  }

  const [venueRes, eventRes] = await Promise.all([venuesQ, eventsQ]);
  const venues = venueRes.data ?? [];
  const rawEvents = eventRes.data ?? [];
  const events = rawEvents
    .filter(e => !!e.title && !isOffAudience(e.title))
    .slice(0, 25);

  const venueLines = venues.map(v =>
    `- ${v.name} (${v.category ?? "venue"}${v.city ? `, ${v.city}` : ""})`
  ).join("\n");

  const eventLines = events.map(e =>
    `- ${e.title}${e.venue_name ? ` @ ${e.venue_name}` : ""} (${e.subcategory ?? "event"})`
  ).join("\n");

  const systemPrompt = `You generate short, natural-feeling search prompts that a user might type into a local events assistant. Return exactly ${PROMPT_TARGET} prompts.

AUDIENCE (critical): Adults aged 25-40. City-living, date-night, brunch-with-friends, concert-going, gallery-hopping, craft-cocktail type users. NEVER write prompts for:
- Middle school, high school, elementary, or youth dances / games / practices
- Varsity / JV / freshman sports of any kind
- Academic seminars, thesis defenses, department lunches, journal clubs, lab meetings
- Senior-center / retirement-community / 55+ programming
- Storytime, toddler classes, baby playgroups, kids birthday parties
- AA / NA / recovery / support-group meetings
- School committee, town council, zoning board, HOA meetings
- Staff trainings, internal corporate events, private parties

If a nearby event in the list feels off-audience (e.g. a school dance or a youth sports game), skip it — don't write a prompt about it. Generic kid-friendly family outings ("Free events for kids this weekend") are fine because an adult parent might search for them.

Style:
- First-person or imperative, like a person dictating: "Date night Friday", "What's on at Free Will Brewing?", "Find me something fun this weekend".
- Most prompts 4–8 words. A few can be a short question.
- **Sentence case**: capitalize the first letter of the first word AND all proper nouns (venue names, event names, cities, days of the week, months). Everything else lowercase. Do NOT use title case.
- **Punctuation**: questions end with "?". Statements end with NO punctuation (no trailing periods). Apostrophes inside words are fine ("what's", "don't"). No em dashes, no semicolons, no ellipses.
- **No dates, no numerals** in the prompts themselves. Never mention specific dates like "April 21" or numbers like "2025" — leave time references generic ("tonight", "this weekend", "Friday", "next week").
- No quote marks around the prompt. No numbering, no bullets, no leading dashes.
- One prompt per line. No other commentary.

Mix target (LOCATION-FIRST — 75% must mention a real nearby venue or event):
- **~60% venue-specific** (roughly 120 of ${PROMPT_TARGET}): generate 4–6 different prompts for EACH venue in the NEARBY VENUES list. Vary phrasing:
  - "What's on at X?" / "Shows coming up at X" / "Tickets at X" / "Tell me about X" / "Upcoming events at X" / "Anything good at X this weekend?" / "Classes at X" / "Tour of X"
- **~15% event-specific** (~30): reference concrete upcoming events from the UPCOMING EVENTS list (audience-appropriate only). "Tickets for [event]?", "When's [event]?", "Anything left for [event]?"
- **~25% generic** (~50): day-plan / vibe / meal prompts. "A day in the city", "Free things tonight", "Date night Friday", "Where to grab cocktails", "Romantic night out".

**Hard requirement: at least 75% of prompts must name a specific venue or event from the lists below.** Almost every prompt should feel like "a neighbor recommending something specific." Not generic "upcoming food festivals" but "Food festivals at Faneuil Hall?" if Faneuil Hall is in the nearby list.

If the NEARBY VENUES list has 25 venues, you should be writing 4–6 prompts for at least 22 of them. Every single venue on the list should appear in at least 2 prompts.

Do NOT repeat prompts. Do NOT invent venues or events that aren't in the lists below — only reference listed ones. If the NEARBY VENUES list has 25 venues, you should be writing 3–5 prompts for at least 20 of them.`;

  const userPrompt = `NEARBY VENUES (pick from these for venue-specific prompts):
${venueLines || "(none)"}

UPCOMING EVENTS (pick from these for event-specific prompts):
${eventLines || "(none)"}

Return exactly ${PROMPT_TARGET} prompts, one per line.`;

  // Claude occasionally returns 429 (overloaded) or 5xx under load. Retry
  // twice with a short backoff before giving up — otherwise a single blip
  // leaves the client with an empty list.
  async function claudeCall(): Promise<Response> {
    const body = JSON.stringify({
      model: CLAUDE_MODEL,
      // ~15 tokens per prompt * 200 prompts = 3000. Give generous headroom
      // so Claude doesn't hit the token cap before it finishes its list
      // (which was leaving us with only ~12 usable prompts after filters).
      max_tokens: 5000,
      system: systemPrompt,
      messages: [{ role: "user", content: userPrompt }],
    });
    let lastRes: Response | null = null;
    for (let attempt = 0; attempt < 3; attempt++) {
      if (attempt > 0) await new Promise(r => setTimeout(r, 1500 * attempt));
      const res = await fetch(CLAUDE_API_URL, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "x-api-key": anthropicKey,
          "anthropic-version": "2023-06-01",
        },
        body,
      });
      if (res.ok) return res;
      lastRes = res;
      const transient = res.status === 429 || res.status === 503 || res.status === 529 || (res.status >= 500 && res.status < 600);
      console.error(`yocal-prompts: Claude attempt ${attempt + 1} returned ${res.status} (transient=${transient})`);
      if (!transient) return res; // non-retryable, fail fast
    }
    return lastRes!;
  }

  const claudeRes = await claudeCall();

  if (!claudeRes.ok) {
    const errText = await claudeRes.text();
    console.error(`yocal-prompts: Claude final error ${claudeRes.status}: ${errText.slice(0, 200)}`);
    return new Response(
      JSON.stringify({ error: "Claude request failed", status: claudeRes.status }),
      { status: 502, headers: cors() },
    );
  }

  const claudeData = await claudeRes.json();
  const rawText = claudeData?.content?.[0]?.text ?? "";

  // Parse — one per line, strip leading/trailing junk and any stray quotes/dashes.
  // Also drop anything off-audience (Claude sometimes ignores the system prompt).
  // Finally cap at PROMPT_TARGET so a runaway generation doesn't flood the client.
  const promptOffAudience = [
    /\b(middle school|high school|elementary school|preschool|kindergarten|pre-?k)\b/i,
    /\b(jv|junior varsity|varsity|freshman)\b/i,
    /\b(thesis defense|dissertation|qualifying exam|journal club|seminar series|lab meeting)\b/i,
    /\b(senior center|retirement community|assisted living|55\+)\b/i,
    /\b(aa meeting|na meeting|al-anon|recovery meeting|support group)\b/i,
    /\b(storytime|story time|toddler|baby playgroup|kids birthday)\b/i,
    /\b(school committee|town council|city council|zoning board|abutters|hoa)\b/i,
    /\b(staff training|internal meeting|private event)\b/i,
  ];

  const prompts = rawText
    .split(/\r?\n/)
    .map((l: string) => l.trim())
    .filter((l: string) => l.length > 0)
    // Drop markdown headers, horizontal rules, and commentary lines.
    .filter((l: string) => !/^#+\s/.test(l))
    .filter((l: string) => !/^[-=]{3,}$/.test(l))
    .filter((l: string) => !/^(here'?s|here are|i'?ll|i will|sure[,!]|okay[,!]|let me|note:|prompts?:)/i.test(l))
    // Strip list markers + surrounding quotes.
    .map((l: string) => l.replace(/^[-*•\d]+[.)]?\s*/, "").replace(/^["']|["']$/g, "").trim())
    .filter((l: string) => l.length >= 3 && l.length <= 120)
    .filter((l: string) => !promptOffAudience.some(re => re.test(l)))
    .slice(0, PROMPT_TARGET);

  // Accept any non-trivial batch. Five good location-specific prompts beat
  // falling back to the 15 hardcoded ones on the client.
  if (prompts.length < 5) {
    console.error(`yocal-prompts: Claude returned only ${prompts.length} usable lines`);
    return new Response(
      JSON.stringify({ error: "Claude returned too few prompts", count: prompts.length }),
      { status: 502, headers: cors() },
    );
  }
  if (prompts.length < 30) {
    console.warn(`yocal-prompts: only ${prompts.length} prompts survived filters — serving anyway`);
  }

  cache.set(key, { prompts, timestamp: Date.now() });
  console.log(`yocal-prompts: generated ${prompts.length} prompts for ${key}`);

  return new Response(
    JSON.stringify({ prompts, cached: false }),
    { status: 200, headers: cors() },
  );
});
