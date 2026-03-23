/**
 * test_chat_v3.js — Focused chat testing for amendments, custom events,
 * competing plans, and response directness.
 *
 * Runs 200 simulated conversations (20 scenarios x 10 locations) against the
 * Claude API using the same system prompt and tools as the mobile app, then
 * grades each conversation on a strict 13-criterion rubric.
 *
 * Scenario types per location (20 total):
 *    5 "Add your own" custom event flows
 *    5 Amendments and edits
 *    5 Competing/alternative plans
 *    5 Directness in responses
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/test_chat_v3.js
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");
const fs = require("fs");
const path = require("path");

// ─── Constants ───────────────────────────────────────────────────────────────

const CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
const MODEL = "claude-sonnet-4-20250514";
const MAX_TOKENS = 4096;
const CONCURRENCY = 10;
const CONVERSATION_TIMEOUT_MS = 120_000;
const SAVE_INTERVAL = 50;
const RESULTS_PATH = path.join(__dirname, "chat_test_results_v3.json");

// ─── Locations ───────────────────────────────────────────────────────────────

const LOCATIONS = [
  { name: "Waltham MA", lat: 42.3765, lng: -71.2356, city: "Waltham" },
  { name: "Boston MA", lat: 42.3601, lng: -71.0589, city: "Boston" },
  { name: "Cambridge MA", lat: 42.3736, lng: -71.1097, city: "Cambridge" },
  { name: "Providence RI", lat: 41.824, lng: -71.4128, city: "Providence" },
  { name: "Cape Cod MA", lat: 41.6688, lng: -70.2962, city: "Cape Cod" },
  { name: "Worcester MA", lat: 42.2626, lng: -71.8023, city: "Worcester" },
  { name: "Springfield MA", lat: 42.1015, lng: -72.5898, city: "Springfield" },
  { name: "Concord MA", lat: 42.4572, lng: -71.3509, city: "Concord" },
  { name: "Salem MA", lat: 42.5195, lng: -70.8967, city: "Salem" },
  { name: "Manchester NH", lat: 42.9956, lng: -71.4548, city: "Manchester" },
];

// ─── Scenario Definitions (20 per location) ────────────────────────────────

function buildScenarios(loc) {
  const city = loc.city;

  // ── 5 "Add your own" custom event flows ──

  const customEvents = [
    {
      type: "custom_event",
      label: `Add my own flow in ${city}`,
      turns: [
        `Plan a fun evening in ${city} this Saturday for 2`,
        "Add my own",
        "Dinner at my friend's place at 7:30pm",
      ],
      expect_custom_slot: { time: "7:30", name_contains: "friend" },
    },
    {
      type: "custom_event",
      label: `Add specific custom dinner in ${city}`,
      turns: [
        `Plan a Saturday afternoon in ${city}`,
        "I want to add dinner at Olive Garden at 7pm",
      ],
      expect_custom_slot: { time: "7:00", name_contains: "olive garden" },
    },
    {
      type: "custom_event",
      label: `Add birthday party in ${city}`,
      turns: [
        `Plan a day in ${city} this Saturday`,
        "Add my friend's birthday party at 3pm at their house",
      ],
      expect_custom_slot: { time: "3:00", name_contains: "birthday" },
    },
    {
      type: "custom_event",
      label: `Add own with incomplete info in ${city}`,
      turns: [
        `Plan an afternoon in ${city} for 2`,
        "Add my own",
        "yoga",
      ],
      expect_ask_for_time: true,
    },
    {
      type: "custom_event",
      label: `Add own then cancel in ${city}`,
      turns: [
        `Plan a date night in ${city} this Saturday`,
        "Add my own",
        "actually never mind, keep the plan as is",
      ],
      expect_no_custom_added: true,
    },
  ];

  // ── 5 Amendments and edits ──

  const amendments = [
    {
      type: "amendment",
      label: `Move event time in ${city}`,
      turns: [
        `Plan an afternoon in ${city} for 2 this Saturday`,
        "Move the 3pm event to 5pm",
      ],
      expect_amendment_count: 1,
      expect_time_change: { from: "3:00", to: "5:00" },
    },
    {
      type: "amendment",
      label: `Remove specific event in ${city}`,
      turns: [
        `Plan a full day in ${city} this Saturday`,
        "Remove the second event",
      ],
      expect_amendment_count: 1,
      expect_slot_removed: true,
    },
    {
      type: "amendment",
      label: `Replace with outdoors in ${city}`,
      turns: [
        `Plan an afternoon in ${city} this Saturday`,
        "Replace the first event with something outdoors",
      ],
      expect_amendment_count: 1,
      expect_search_before_update: true,
    },
    {
      type: "amendment",
      label: `Insert restaurant between events in ${city}`,
      turns: [
        `Plan a day in ${city} this Saturday with 3 activities`,
        "Add a restaurant between events 1 and 2",
      ],
      expect_amendment_count: 1,
      expect_slot_added: true,
    },
    {
      type: "amendment",
      label: `Chain of 3 amendments in ${city}`,
      turns: [
        `Plan an evening in ${city} this Saturday`,
        "Move the first event to 5pm",
        "Actually make it 6pm",
        "And add dinner after the last event",
      ],
      expect_amendment_count: 3,
    },
  ];

  // ── 5 Competing/alternative plans ──

  const competingPlans = [
    {
      type: "competing_plan",
      label: `Completely different plan in ${city}`,
      turns: [
        `Plan a full day in ${city} this Saturday`,
        "I don't like this, give me something completely different",
      ],
      expect_different_plan: true,
    },
    {
      type: "competing_plan",
      label: `Chill version in ${city}`,
      turns: [
        `Plan an active day in ${city} this Saturday for 2`,
        "This is too active, give me a chill version",
      ],
      expect_different_vibe: "chill",
    },
    {
      type: "competing_plan",
      label: `Cheaper alternative in ${city}`,
      turns: [
        `Plan a full day in ${city} this Saturday`,
        "Can you find cheaper options?",
      ],
      expect_cheaper: true,
    },
    {
      type: "competing_plan",
      label: `Outdoors instead of indoors in ${city}`,
      turns: [
        `Plan an afternoon in ${city} this Saturday`,
        "What if we did outdoors instead of indoors?",
      ],
      expect_different_plan: true,
    },
    {
      type: "competing_plan",
      label: `Show me option B in ${city}`,
      turns: [
        `Plan a date night in ${city} this Saturday`,
        "Show me option B",
      ],
      expect_different_plan: true,
    },
  ];

  // ── 5 Directness in responses ──

  const directness = [
    {
      type: "directness",
      label: `Direct date night question in ${city}`,
      turns: [
        `Plan a date night in ${city}`,
      ],
      expect_direct_response: true,
    },
    {
      type: "directness",
      label: `Direct fun weekend question in ${city}`,
      turns: [
        `Something fun this weekend in ${city}`,
      ],
      expect_direct_response: true,
    },
    {
      type: "directness",
      label: `Direct multi-day question in ${city}`,
      turns: [
        `Multi-day trip to ${city}`,
      ],
      expect_direct_question: "when",
    },
    {
      type: "directness",
      label: `Direct comedy search in ${city}`,
      turns: [
        `I want comedy in ${city}`,
      ],
      expect_immediate_search: true,
    },
    {
      type: "directness",
      label: `Surprise me in ${city}`,
      turns: [
        `Surprise me in ${city}`,
      ],
      expect_immediate_search: true,
    },
  ];

  return [
    ...customEvents,
    ...amendments,
    ...competingPlans,
    ...directness,
  ];
}

// ─── System Prompt (mirrors ChatViewModel.buildSystemPrompt) ─────────────────

function buildSystemPrompt(currentPlanContext = "") {
  const today = new Date().toISOString().slice(0, 10);
  const dayOfWeek = new Date().toLocaleDateString("en-US", { weekday: "long" });

  return `You are Local, an events concierge in a mobile app.
Today: ${today} (${dayOfWeek})

TOOLS: Always search before recommending. Use search_events for events, search_venues for places, recommend_and_save_venue for restaurants/bars not in the DB, get_event_details for full info. Search broadly first, then narrow down. Pass the user's location and category as tool parameters.

RULES:
- Events: only from search_events results (must have event_id).
- Restaurants/bars: search_venues first, then recommend_and_save_venue if needed. Restaurant/venue slots use event_id: null, is_custom: false. NEVER use filler like "Dinner" or "Custom dinner" — always a real restaurant name.
- Search results tagged [FIXED-TIME] or [FLEXIBLE]. Only use FIXED-TIME events if their date matches the plan day. FLEXIBLE events can go on any day. Use exact times from search results for FIXED-TIME events.
- No emoji. 1-3 sentences. Warm and concise.
- NEVER set is_custom: true — that is only for user-added items via "Add my own".
- 2-3 real results beat 5 slots with filler.

AMENDMENTS — THIS IS THE #1 RULE:
When user asks to add/remove/move/replace/swap, you MUST respond with type "plan_update" (NEVER "chat"). Include ALL slots for the day with the modification applied. Use exact times the user specifies. Sort chronologically.

CONFLICTS: If adding at a time already taken, flag it and offer options via chips.

CUSTOM EVENTS ("Add my own"):
When the user says "Add my own" or wants to add their own event/activity:
1. Ask: what is it, what time, and (if multi-day) which day? One question.
2. Return plan_update with ALL existing slots PLUS the custom slot
3. Custom slot MUST have: is_custom: true, event_id: null
4. Use the user's exact name and time for the custom slot

ALTERNATIVE PLANS:
When user asks for "something different", "cheaper", "more active", "option B", etc:
- Respond with type "plan" (a complete new plan), NOT "chat"
- Search for different events than the current plan
- Make it genuinely different — different venues, different vibe

GATHERING INFO: For plan requests, quickly gather: When, Where, Group size, Vibe, Duration — but only ask what's genuinely missing. Max 2 questions before building. If user says "surprise me", search broadly and build immediately.
TONE FOR QUESTIONS: Be direct. Don't say "Great, I can help with that! First, let me ask..." — just ask the question. Example: "When are you thinking — this weekend or a specific date?" not "That sounds fun! I'd love to help plan that. To get started, could you tell me when you're thinking?"

${currentPlanContext}

RESPONSE FORMAT (JSON only, no markdown fences):
chat: {"type":"chat","text":"...","chips":[{"label":"..."}]}
plan: {"type":"plan","text":"...","plan_title":"...","days":[{"label":"...","short_name":"...","slots":[{"time":"...","emoji":"","name":"...","detail":"...","price":"...","is_free":false,"image_url":"...","event_id":"..."}],"travel":[{"mode":"...","mins":N}]}],"chips":[...]}
plan_update: {"type":"plan_update","text":"...","day_index":N,"day":{same day structure},"chips":[...]}
approve: {"type":"approve","text":"..."}

CHIPS: 2-4 contextual suggestions. After plans: approve + modify options + "Add my own". After questions: common answers.
SLOTS: Match count to request. Every slot needs event_id from search, EXCEPT venue slots from recommend_and_save_venue (event_id: null, is_custom: false) and user custom items (is_custom: true, event_id: null). Sort chronologically. Copy image URLs exactly.`;
}

// ─── Tool Definitions (mirrors ClaudeApiService.claudeTools) ─────────────────

const CLAUDE_TOOLS = [
  {
    name: "search_events",
    description:
      "Search for upcoming events and activities. Use this when the user asks about things to do, events, activities, concerts, shows, etc.",
    input_schema: {
      type: "object",
      properties: {
        location: {
          type: "string",
          description:
            "City or neighborhood name (e.g. 'Concord', 'Boston', 'Cambridge')",
        },
        category: {
          type: "string",
          description:
            "Event category: music, sports, arts, theatre, comedy, food, outdoors, museums, tours, libraries, for-kids",
        },
        date_range: {
          type: "string",
          description:
            "Time filter: today, this_weekend, this_week, this_month",
        },
        keyword: {
          type: "string",
          description: "Search keyword for event title or venue name",
        },
        limit: {
          type: "integer",
          description: "Max results to return (default 20)",
        },
      },
    },
  },
  {
    name: "search_venues",
    description:
      "Search for venues, restaurants, bars, and places. Use this when the user asks about places to go, venues, restaurants, etc.",
    input_schema: {
      type: "object",
      properties: {
        location: {
          type: "string",
          description: "City or neighborhood name",
        },
        category: {
          type: "string",
          description:
            "Venue category: music_venue, theater, museum, library, comedy_club, sports_venue, bar, brewery, restaurant, arts_center",
        },
        keyword: {
          type: "string",
          description: "Search keyword for venue name",
        },
        limit: {
          type: "integer",
          description: "Max results to return (default 20)",
        },
      },
    },
  },
  {
    name: "get_event_details",
    description:
      "Get full details of a specific event including description, pricing, and ticket URL",
    input_schema: {
      type: "object",
      properties: {
        event_id: {
          type: "string",
          description: "The event ID",
        },
      },
      required: ["event_id"],
    },
  },
  {
    name: "recommend_and_save_venue",
    description:
      "Recommend a specific restaurant, bar, cafe, or venue that you know about. Use this when the user needs a dining/drinking spot and search_venues doesn't have a good match. You provide the venue name and location, and the system will validate it via Google Places, save it to the database, and return the details for use in the plan. Only use this for real, existing establishments — not made-up ones.",
    input_schema: {
      type: "object",
      properties: {
        name: {
          type: "string",
          description:
            "The venue name (e.g. 'Myers + Chang', 'The Salty Pig')",
        },
        city: {
          type: "string",
          description: "The city (e.g. 'Boston', 'Cambridge')",
        },
        cuisine_or_type: {
          type: "string",
          description:
            "Type of venue (e.g. 'Thai restaurant', 'cocktail bar', 'brewery', 'Italian')",
        },
      },
      required: ["name", "city"],
    },
  },
];

// ─── Supabase Helpers ────────────────────────────────────────────────────────

let SECRETS = {};

async function supabaseQuery(table, queryParams) {
  const qs = new URLSearchParams(queryParams).toString();
  const url = `${SUPABASE_URL}/rest/v1/${table}?${qs}`;
  const res = await fetch(url, {
    headers: {
      apikey: SECRETS.SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${SECRETS.SUPABASE_SERVICE_KEY}`,
    },
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Supabase query failed (${res.status}): ${body}`);
  }
  return res.json();
}

// ─── Tool Execution (against Supabase) ───────────────────────────────────────

async function executeSearchEvents(input) {
  const params = {
    is_active: "eq.true",
    order: "start_at.asc",
    select: "id,title,venue_name,venue_city,venue_address,start_at,price_display,category,subcategory,is_free,image_url,date_display",
  };

  if (input.location) {
    params["venue_city"] = `ilike.%${input.location}%`;
  }
  if (input.category) {
    params["subcategory"] = `eq.${input.category}`;
  }
  if (input.keyword) {
    params["or"] = `(title.ilike.%${input.keyword}%,venue_name.ilike.%${input.keyword}%)`;
  }
  if (input.date_range) {
    const now = new Date();
    let endDate;
    switch (input.date_range) {
      case "today":
        endDate = new Date(now);
        endDate.setHours(23, 59, 59);
        params["start_at"] = `gte.${now.toISOString()}`;
        params["start_at"] = `lte.${endDate.toISOString()}`;
        break;
      case "this_weekend": {
        const dayOfWeek = now.getDay();
        const daysToFri = dayOfWeek <= 5 ? 5 - dayOfWeek : 6;
        const fri = new Date(now);
        fri.setDate(fri.getDate() + daysToFri);
        fri.setHours(0, 0, 0);
        const sun = new Date(fri);
        sun.setDate(sun.getDate() + 2);
        sun.setHours(23, 59, 59);
        params["start_at"] = `gte.${fri.toISOString()}`;
        break;
      }
      case "this_week": {
        endDate = new Date(now);
        endDate.setDate(endDate.getDate() + 7);
        params["start_at"] = `gte.${now.toISOString()}`;
        break;
      }
      case "this_month": {
        endDate = new Date(now);
        endDate.setMonth(endDate.getMonth() + 1);
        params["start_at"] = `gte.${now.toISOString()}`;
        break;
      }
    }
  } else {
    params["start_at"] = `gte.${new Date().toISOString()}`;
  }

  params["limit"] = String(input.limit || 20);

  try {
    const events = await supabaseQuery("events", params);
    if (!events.length) return "No events found matching your search.";
    return events
      .map((e) => {
        const price = e.price_display || "Price TBD";
        const date = e.date_display || (e.start_at ? e.start_at.substring(0, 10) : "");
        const venue = e.venue_name || "TBD";
        const img = e.image_url || "";
        return `[${e.id}] ${e.title} | ${venue} (${e.venue_city}) | STARTS: ${date} | ${price} | ${e.category}${e.is_free ? " | FREE" : ""}${img ? ` | img:${img}` : ""}`;
      })
      .join("\n");
  } catch (err) {
    return `Error searching events: ${err.message}`;
  }
}

async function executeSearchVenues(input) {
  const params = {
    is_active: "eq.true",
    select: "id,name,city,address,category,place_type,google_rating",
  };

  if (input.location) {
    params["city"] = `ilike.%${input.location}%`;
  }
  if (input.category) {
    params["category"] = `ilike.%${input.category}%`;
  }
  if (input.keyword) {
    params["name"] = `ilike.%${input.keyword}%`;
  }
  params["limit"] = String(input.limit || 20);

  try {
    const venues = await supabaseQuery("venues", params);
    if (!venues.length) return "No venues found matching your search.";
    return venues
      .map((v) => {
        const cat = v.category || v.place_type || "";
        const rating = v.google_rating ? ` | Rating: ${v.google_rating}` : "";
        return `[${v.id}] ${v.name} | ${v.city}${v.address ? ` (${v.address})` : ""} | ${cat}${rating}`;
      })
      .join("\n");
  } catch (err) {
    return `Error searching venues: ${err.message}`;
  }
}

async function executeGetEventDetails(input) {
  if (!input.event_id) return '{"error": "event_id is required"}';
  try {
    const params = { id: `eq.${input.event_id}` };
    const events = await supabaseQuery("events", params);
    if (!events.length) return `{"error": "Event not found with id: ${input.event_id}"}`;
    const e = events[0];
    return [
      `ID: ${e.id}`,
      `Title: ${e.title}`,
      `Venue: ${e.venue_name || "TBD"} (${e.venue_city})`,
      e.venue_address ? `Address: ${e.venue_address}` : null,
      `Date: ${e.date_display || ""}`,
      `Price: ${e.price_display || "TBD"}`,
      e.description ? `Description: ${e.description}` : null,
      e.ticket_url ? `Tickets: ${e.ticket_url}` : null,
      e.image_url ? `Image: ${e.image_url}` : null,
      `Category: ${e.category}${e.subcategory ? ` / ${e.subcategory}` : ""}`,
      `Free: ${e.is_free}`,
    ]
      .filter(Boolean)
      .join("\n");
  } catch (err) {
    return `{"error": "Failed to get event details: ${err.message}"}`;
  }
}

async function executeRecommendAndSaveVenue(input) {
  if (!input.name || !input.city) return '{"error": "name and city are required"}';
  try {
    const res = await fetch(
      `${SUPABASE_URL}/functions/v1/validate-venue`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${SECRETS.SUPABASE_SERVICE_KEY}`,
        },
        body: JSON.stringify({
          name: input.name,
          city: input.city,
          cuisine_or_type: input.cuisine_or_type || null,
        }),
      },
    );
    const body = await res.text();
    return body;
  } catch (err) {
    return `{"error": "Failed to validate venue: ${err.message}"}`;
  }
}

async function executeTool(name, input) {
  switch (name) {
    case "search_events":
      return executeSearchEvents(input);
    case "search_venues":
      return executeSearchVenues(input);
    case "get_event_details":
      return executeGetEventDetails(input);
    case "recommend_and_save_venue":
      return executeRecommendAndSaveVenue(input);
    default:
      return `{"error": "Unknown tool: ${name}"}`;
  }
}

// ─── Claude API ──────────────────────────────────────────────────────────────

async function callClaude(systemPrompt, messages) {
  const body = {
    model: MODEL,
    max_tokens: MAX_TOKENS,
    system: systemPrompt,
    messages,
    tools: CLAUDE_TOOLS,
  };

  const res = await fetch(CLAUDE_API_URL, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "x-api-key": SECRETS.ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
    },
    body: JSON.stringify(body),
  });

  if (!res.ok) {
    const text = await res.text();
    throw new Error(`Claude API error ${res.status}: ${text}`);
  }

  return res.json();
}

function extractToolCalls(response) {
  return (response.content || [])
    .filter((b) => b.type === "tool_use")
    .map((b) => ({ id: b.id, name: b.name, input: b.input || {} }));
}

function extractText(response) {
  const textBlock = (response.content || []).find((b) => b.type === "text");
  return textBlock ? textBlock.text : null;
}

function buildAssistantBlocks(response) {
  return (response.content || []).map((block) => {
    if (block.type === "text") return { type: "text", text: block.text || "" };
    if (block.type === "tool_use")
      return {
        type: "tool_use",
        id: block.id || "",
        name: block.name || "",
        input: block.input || {},
      };
    return block;
  });
}

function cleanJson(raw) {
  const fenceMatch = raw.match(/```(?:json)?\s*\n?(\{[\s\S]*\})\s*\n?```/);
  if (fenceMatch) return fenceMatch[1].trim();
  const start = raw.indexOf("{");
  const end = raw.lastIndexOf("}");
  if (start >= 0 && end > start) return raw.substring(start, end + 1).trim();
  return raw.trim();
}

function parseStructured(rawText) {
  try {
    return JSON.parse(cleanJson(rawText));
  } catch {
    return { type: "chat", text: rawText, _parse_error: true };
  }
}

// ─── Conversation Runner ────────────────────────────────────────────────────

async function runConversation(scenario, location) {
  const transcript = [];
  const messages = [];
  const toolCalls = [];
  let currentPlanContext = "";
  let lastEnvelope = null;

  const deadline = Date.now() + CONVERSATION_TIMEOUT_MS;

  for (const userText of scenario.turns) {
    if (Date.now() > deadline) {
      transcript.push({ role: "system", content: "TIMEOUT" });
      break;
    }

    transcript.push({ role: "user", content: userText });
    messages.push({ role: "user", content: userText });

    const systemPrompt = buildSystemPrompt(currentPlanContext);

    // Tool loop
    let maxIter = 10;
    while (maxIter-- > 0) {
      if (Date.now() > deadline) break;

      let response;
      try {
        response = await callClaude(systemPrompt, messages);
      } catch (err) {
        transcript.push({
          role: "error",
          content: `Claude API error: ${err.message}`,
        });
        break;
      }

      const calls = extractToolCalls(response);

      if (calls.length === 0 || response.stop_reason === "end_turn") {
        // Final response
        const text = extractText(response);
        if (text) {
          messages.push({ role: "assistant", content: text });
          const envelope = parseStructured(text);
          lastEnvelope = envelope;
          transcript.push({ role: "assistant", content: text, envelope });

          // Build plan context for subsequent turns
          if (
            envelope.type === "plan" &&
            envelope.days &&
            envelope.days.length > 0
          ) {
            currentPlanContext = buildPlanContext(envelope);
          } else if (envelope.type === "plan_update" && envelope.day) {
            currentPlanContext = updatePlanContext(
              currentPlanContext,
              envelope
            );
          }
        }
        break;
      }

      // Tool use
      const assistantBlocks = buildAssistantBlocks(response);
      messages.push({ role: "assistant", content: assistantBlocks });

      const toolResults = [];
      for (const call of calls) {
        toolCalls.push(call);
        transcript.push({
          role: "tool_use",
          tool: call.name,
          input: call.input,
        });

        const result = await executeTool(call.name, call.input);
        toolResults.push({
          type: "tool_result",
          tool_use_id: call.id,
          content: result,
        });
        transcript.push({
          role: "tool_result",
          tool: call.name,
          result: result.substring(0, 500),
        });
      }

      messages.push({ role: "user", content: toolResults });
    }
  }

  return { transcript, toolCalls, lastEnvelope };
}

function buildPlanContext(envelope) {
  if (!envelope.days || !envelope.days.length) return "";
  const dayDescriptions = envelope.days
    .map((day, i) => {
      const slots =
        !day.slots || !day.slots.length
          ? "  (no slots yet)"
          : day.slots
              .map(
                (s) =>
                  `  ${s.time} ${s.name} -- ${s.detail} (${s.price})${s.is_custom ? " [CUSTOM]" : ""}`
              )
              .join("\n");
      return `Day ${i + 1}: ${day.label}\n${slots}`;
    })
    .join("\n\n");

  return `CURRENT PLAN (title: "${envelope.plan_title || "Your Plan"}"):\n${dayDescriptions}\n\nCurrent day being worked on: Day 1\nApproved days: 0 of ${envelope.days.length}`;
}

function updatePlanContext(existing, envelope) {
  if (!existing || !envelope.day) return existing;
  const dayIdx = envelope.day_index || 0;
  const daySlots = (envelope.day.slots || [])
    .map(
      (s) =>
        `  ${s.time} ${s.name} -- ${s.detail} (${s.price})${s.is_custom ? " [CUSTOM]" : ""}`
    )
    .join("\n");
  return existing + `\n\n[UPDATED Day ${dayIdx + 1}: ${envelope.day.label || ""}]\n${daySlots}`;
}

// ─── Time Parsing ────────────────────────────────────────────────────────────

function parseTimeToMinutes(time) {
  if (!time || typeof time !== "string") return null;
  const clean = time.trim().toUpperCase();

  const ampmMatch = clean.match(/(\d{1,2})(?::(\d{2}))?\s*(AM|PM)/);
  if (ampmMatch) {
    let hour = parseInt(ampmMatch[1]);
    const min = parseInt(ampmMatch[2] || "0");
    if (ampmMatch[3] === "PM" && hour !== 12) hour += 12;
    if (ampmMatch[3] === "AM" && hour === 12) hour = 0;
    return hour * 60 + min;
  }

  const h24Match = clean.match(/^(\d{1,2}):(\d{2})$/);
  if (h24Match) {
    return parseInt(h24Match[1]) * 60 + parseInt(h24Match[2]);
  }

  return null;
}

function _toMinutes(timeStr) {
  if (!timeStr) return -1;
  const match = timeStr.match(/(\d{1,2})(?::(\d{2}))?\s*(AM|PM)/i);
  if (!match) return -1;
  let hours = parseInt(match[1], 10);
  const mins = parseInt(match[2] || "0", 10);
  const period = match[3].toUpperCase();
  if (period === "PM" && hours !== 12) hours += 12;
  if (period === "AM" && hours === 12) hours = 0;
  return hours * 60 + mins;
}

// ─── Directness Detection Helpers ────────────────────────────────────────────

const FILLER_PHRASES = [
  /^great\b/i,
  /^i'd love to/i,
  /^i would love to/i,
  /^that sounds/i,
  /^absolutely/i,
  /^perfect\b/i,
  /^awesome/i,
  /^sure thing/i,
  /^of course/i,
  /^happy to/i,
  /^wonderful/i,
];

function measureDirectness(text) {
  if (!text) return { score: 0, reason: "No text" };
  const trimmed = text.trim();

  // Check if starts with filler
  const startsWithFiller = FILLER_PHRASES.some((p) => p.test(trimmed));

  // Check if starts with a question word (very direct)
  const startsWithQuestion = /^(what|when|where|how|which|who)\b/i.test(trimmed);

  // Length of text before the first question mark
  const firstQuestion = trimmed.indexOf("?");
  const preambleLength = firstQuestion >= 0 ? firstQuestion : trimmed.length;

  // Score 100: Under 80 chars before '?' OR starts with a question word
  if (startsWithQuestion || (!startsWithFiller && preambleLength < 80)) {
    return { score: 100, reason: "Direct and concise" };
  }
  // Score 75: Under 120 chars before '?', no filler
  if (!startsWithFiller && preambleLength < 120) {
    return { score: 75, reason: `Concise without filler (preamble: ${preambleLength} chars)` };
  }
  // Score 50: Has filler but gets to the point within 150 chars
  if (preambleLength < 150) {
    return { score: 50, reason: `Gets to point within 150 chars (preamble: ${preambleLength} chars, filler: ${startsWithFiller})` };
  }
  // Score 0: Over 200 chars of preamble, or no question when one expected
  if (preambleLength > 200 || (firstQuestion < 0 && trimmed.length > 200)) {
    return { score: 0, reason: `Verbose preamble (${preambleLength} chars, filler: ${startsWithFiller})` };
  }

  return { score: 50, reason: "Moderate directness" };
}

// ─── Grading (Strict V3 Rubric — 13 Criteria) ───────────────────────────────

async function gradeConversation(scenario, location, result) {
  const { transcript, toolCalls, lastEnvelope } = result;
  const grades = {};
  const issues = [];

  // Helper: find all envelopes in transcript
  const envelopes = transcript
    .filter((t) => t.role === "assistant" && t.envelope)
    .map((t) => t.envelope);

  // Collect ALL plan envelopes (initial plan + updates)
  const planEnvelopes = envelopes.filter(
    (e) => e.type === "plan" || e.type === "plan_update"
  );
  const firstPlanEnvelope = planEnvelopes[0] || null;
  const lastPlanEnvelope = planEnvelopes[planEnvelopes.length - 1] || null;

  // Collect all slots from the LAST plan state
  const allSlots = [];
  if (lastPlanEnvelope) {
    if (lastPlanEnvelope.days) {
      for (const day of lastPlanEnvelope.days) {
        if (day.slots) allSlots.push(...day.slots);
      }
    }
    if (lastPlanEnvelope.day && lastPlanEnvelope.day.slots) {
      allSlots.push(...lastPlanEnvelope.day.slots);
    }
  }

  // Collect all slots from the FIRST plan (for competing plan comparison)
  const firstPlanSlots = [];
  if (firstPlanEnvelope) {
    if (firstPlanEnvelope.days) {
      for (const day of firstPlanEnvelope.days) {
        if (day.slots) firstPlanSlots.push(...day.slots);
      }
    }
    if (firstPlanEnvelope.day && firstPlanEnvelope.day.slots) {
      firstPlanSlots.push(...firstPlanEnvelope.day.slots);
    }
  }

  // ── 1. Plan makes sense (13%) ──
  {
    let score = 100;
    let notes = "";

    if (allSlots.length > 1) {
      const times = allSlots.map((s) => parseTimeToMinutes(s.time));
      const validTimes = times.filter((t) => t !== null);

      for (let i = 1; i < validTimes.length; i++) {
        const gap = validTimes[i] - validTimes[i - 1];
        if (gap < 30) {
          score = Math.min(score, 50);
          notes += `Too-tight gap (${gap}min) between slots ${i - 1} and ${i}. `;
        }
        if (gap > 480) {
          score = Math.min(score, 50);
          notes += `Huge gap (${Math.round(gap / 60)}hrs) between slots ${i - 1} and ${i}. `;
        }
      }

      const userTexts = scenario.turns.join(" ").toLowerCase();
      if (userTexts.includes("full day") || userTexts.includes("all day")) {
        if (allSlots.length < 3) {
          score = Math.min(score, 50);
          notes += `Only ${allSlots.length} slots for a full day request. `;
        }
      }
      if (userTexts.includes("evening") || userTexts.includes("date night")) {
        if (allSlots.length > 4) {
          score = Math.min(score, 50);
          notes += `${allSlots.length} slots for an evening — too many. `;
        }
      }
    } else if (allSlots.length === 0) {
      const expectsPlan = ["custom_event", "amendment", "competing_plan"].includes(scenario.type);
      // directness scenarios may or may not produce plans
      if (expectsPlan && !scenario.expect_ask_for_time && !scenario.expect_no_custom_added) {
        score = 0;
        notes = "Expected a plan but none was delivered. ";
      }
    }

    if (!notes) notes = "Plan structure looks reasonable";
    grades.plan_makes_sense = { score, notes };
    if (score < 100) issues.push(`Plan sense: ${notes.trim()}`);
  }

  // ── 2. Did what the user asked (14%) ──
  {
    let score = 100;
    let notes = "";

    if (scenario.type === "custom_event") {
      if (scenario.expect_custom_slot) {
        // Check that a custom slot was added with is_custom: true and event_id: null
        const customSlots = allSlots.filter((s) => s.is_custom === true);
        if (customSlots.length === 0) {
          score = 0;
          notes = "No custom slot (is_custom: true) found in plan";
        } else {
          // Check event_id is null
          const hasNullId = customSlots.some((s) => s.event_id === null || s.event_id === undefined || s.event_id === "");
          if (!hasNullId) {
            score = Math.min(score, 50);
            notes += "Custom slot should have event_id: null. ";
          }

          // Check name matches
          if (scenario.expect_custom_slot.name_contains) {
            const nameMatch = customSlots.some((s) =>
              (s.name || "").toLowerCase().includes(scenario.expect_custom_slot.name_contains)
            );
            if (!nameMatch) {
              score = Math.min(score, 50);
              notes += `Custom slot name doesn't contain "${scenario.expect_custom_slot.name_contains}". `;
            }
          }

          // Check time matches
          if (scenario.expect_custom_slot.time) {
            const expectedMins = parseTimeToMinutes(scenario.expect_custom_slot.time + "PM");
            const timeMatch = customSlots.some((s) => {
              const slotMins = parseTimeToMinutes(s.time || "");
              return slotMins !== null && expectedMins !== null && Math.abs(slotMins - expectedMins) <= 30;
            });
            if (!timeMatch) {
              score = Math.min(score, 50);
              notes += `Custom slot time doesn't match expected ~${scenario.expect_custom_slot.time}. `;
            }
          }
        }
      }

      if (scenario.expect_ask_for_time) {
        // User said "Add my own" then gave incomplete info — Claude should ask for time
        const chatResponses = envelopes.filter((e) => e.type === "chat");
        const askedForTime = chatResponses.some((e) =>
          e.text && (
            /what time/i.test(e.text) ||
            /when/i.test(e.text) ||
            /time.*\?/i.test(e.text)
          )
        );
        if (!askedForTime) {
          score = 0;
          notes = "Should have asked for time when user gave incomplete custom event info";
        } else {
          notes = "Correctly asked for missing time info";
        }
      }

      if (scenario.expect_no_custom_added) {
        // User said "never mind" — no custom slot should appear
        const customSlots = allSlots.filter((s) => s.is_custom === true);
        if (customSlots.length > 0) {
          score = 0;
          notes = "Added custom slot despite user saying never mind";
        } else {
          notes = "Correctly kept plan as-is after user changed mind";
        }
      }
    }

    if (scenario.type === "amendment") {
      const updateCount = envelopes.filter((e) => e.type === "plan_update").length;
      const expectedAmendments = scenario.expect_amendment_count || (scenario.turns.length - 1);

      if (updateCount === 0) {
        score = 0;
        notes = "No plan_update delivered for amendment scenario";
      } else if (updateCount < expectedAmendments) {
        score = 50;
        notes = `Only ${updateCount}/${expectedAmendments} amendments delivered as plan_update`;
      }

      // Check time accuracy for amendment turns
      for (let t = 1; t < scenario.turns.length; t++) {
        const turnText = scenario.turns[t].toLowerCase();
        const timeMatch = turnText.match(/at (\d{1,2}(?::\d{2})?\s*(?:am|pm)?)/i);
        if (timeMatch) {
          const requestedTime = timeMatch[1].toUpperCase().replace(/\s/g, "");
          const updateEnvelopes = envelopes.filter((e) => e.type === "plan_update");
          if (updateEnvelopes.length >= t) {
            const update = updateEnvelopes[t - 1];
            if (update && update.day && update.day.slots) {
              const hasCorrectTime = update.day.slots.some((s) => {
                const slotTime = (s.time || "").toUpperCase().replace(/\s/g, "");
                return slotTime.includes(requestedTime) || requestedTime.includes(slotTime);
              });
              if (!hasCorrectTime) {
                score = Math.min(score, 50);
                notes += `Requested time "${timeMatch[1]}" not in updated slots. `;
              }
            }
          }
        }
      }
    }

    if (scenario.type === "competing_plan") {
      // Must have delivered at least 2 plan responses (original + alternative)
      if (planEnvelopes.length < 2) {
        score = 0;
        notes = "Did not deliver an alternative plan";
      } else {
        // Check that the new plan is actually different
        const firstIds = new Set(firstPlanSlots.map((s) => s.event_id).filter(Boolean));
        const lastIds = new Set(allSlots.map((s) => s.event_id).filter(Boolean));

        // Calculate overlap
        let overlap = 0;
        for (const id of lastIds) {
          if (firstIds.has(id)) overlap++;
        }
        const overlapPct = firstIds.size > 0 ? overlap / firstIds.size : 0;

        if (overlapPct > 0.7) {
          score = 50;
          notes = `Alternative plan shares ${Math.round(overlapPct * 100)}% of original events — not different enough`;
        } else {
          notes = `Alternative plan is ${Math.round((1 - overlapPct) * 100)}% different from original`;
        }
      }
    }

    if (scenario.type === "directness") {
      if (scenario.expect_immediate_search) {
        // Should search and/or build immediately, zero questions
        const firstResponse = envelopes[0];
        if (firstResponse && firstResponse.type === "chat") {
          const asksQuestion = (firstResponse.text || "").includes("?");
          if (asksQuestion) {
            score = 0;
            notes = "Asked questions instead of searching immediately for 'surprise me' / direct request";
          }
        }
        // Should have used tools
        if (toolCalls.length === 0) {
          score = 0;
          notes = "Did not search at all for direct/surprise request";
        }
      }
    }

    if (!notes) notes = "Followed instructions correctly";
    grades.did_what_asked = { score, notes };
    if (score < 100) issues.push(`Did what asked: ${notes.trim()}`);
  }

  // ── 3. User satisfaction (13%) ──
  {
    let score = 100;
    let notes = "";

    if (scenario.type === "competing_plan") {
      if (planEnvelopes.length < 2) {
        score = 0;
        notes = "No alternative plan delivered";
      } else {
        // Check if the new plan fits the user's request
        if (scenario.expect_cheaper) {
          const firstFreeCount = firstPlanSlots.filter((s) => s.is_free).length;
          const lastFreeCount = allSlots.filter((s) => s.is_free).length;
          if (lastFreeCount <= firstFreeCount) {
            score = 50;
            notes = "Alternative plan is not cheaper (same or fewer free events)";
          } else {
            notes = `Cheaper plan: ${lastFreeCount} free events vs ${firstFreeCount} in original`;
          }
        } else if (scenario.expect_different_vibe) {
          // Just check that it's actually different
          const firstIds = new Set(firstPlanSlots.map((s) => s.event_id).filter(Boolean));
          const lastIds = new Set(allSlots.map((s) => s.event_id).filter(Boolean));
          let overlap = 0;
          for (const id of lastIds) {
            if (firstIds.has(id)) overlap++;
          }
          if (overlap === firstIds.size && firstIds.size > 0) {
            score = 0;
            notes = "Plan wasn't changed at all despite vibe request";
          } else {
            notes = "Plan changed to match requested vibe";
          }
        } else {
          notes = "Alternative plan delivered";
        }
      }
    } else if (scenario.type === "amendment") {
      const updateCount = envelopes.filter((e) => e.type === "plan_update").length;
      if (updateCount > 0) {
        score = 100;
        notes = "Amendments applied";
      } else {
        score = 0;
        notes = "No amendments were applied";
      }
    } else if (scenario.type === "custom_event") {
      if (scenario.expect_custom_slot) {
        const customSlots = allSlots.filter((s) => s.is_custom === true);
        if (customSlots.length > 0) {
          score = 100;
          notes = "Custom event added as requested";
        } else {
          score = 0;
          notes = "Custom event was not added to plan";
        }
      } else if (scenario.expect_ask_for_time) {
        // Should have asked — check if it did
        const chatResponses = envelopes.filter((e) => e.type === "chat");
        const askedForTime = chatResponses.some((e) =>
          e.text && /what time|when|time.*\?/i.test(e.text)
        );
        score = askedForTime ? 100 : 0;
        notes = askedForTime ? "Correctly asked for missing info" : "Did not ask for time";
      } else if (scenario.expect_no_custom_added) {
        const customSlots = allSlots.filter((s) => s.is_custom === true);
        score = customSlots.length === 0 ? 100 : 0;
        notes = customSlots.length === 0 ? "Respected user cancellation" : "Added custom slot despite cancellation";
      } else {
        score = envelopes.length > 0 ? 100 : 50;
        notes = envelopes.length > 0 ? "Response delivered" : "No response";
      }
    } else {
      if (envelopes.length > 0 && !envelopes[0]._parse_error) {
        score = 100;
        notes = "Response delivered";
      } else {
        score = 50;
        notes = "Response may not have been helpful";
      }
    }

    grades.user_satisfaction = { score, notes };
    if (score < 75) issues.push(`Satisfaction: ${notes}`);
  }

  // ── 4. Event times match DB (7%) ──
  {
    let timeScore = 100;
    let timeNotes = "";

    const nonCustomSlots = allSlots.filter((s) => !s.is_custom);
    const slotsWithIds = nonCustomSlots.filter((s) => s.event_id);
    const fetchedEventsMap = new Map();

    if (slotsWithIds.length > 0) {
      try {
        const ids = slotsWithIds.map((s) => s.event_id);
        const idsFilter = `(${ids.join(",")})`;
        const evts = await supabaseQuery("events", {
          id: `in.${idsFilter}`,
          select: "id,start_at,category",
        });
        evts.forEach((e) => fetchedEventsMap.set(e.id, e));
      } catch (_) {}
    }

    const fixedSlots = slotsWithIds.filter((s) => {
      const ev = fetchedEventsMap.get(s.event_id);
      return ev && ev.category !== "always_on" && ev.start_at;
    });

    if (fixedSlots.length > 0) {
      let wrongCount = 0;
      for (const slot of fixedSlots) {
        const ev = fetchedEventsMap.get(slot.event_id);
        const dbDate = new Date(ev.start_at);
        const etString = dbDate.toLocaleString("en-US", {
          timeZone: "America/New_York",
          hour: "numeric",
          minute: "numeric",
          hour12: true,
        });
        const dbMins = _toMinutes(etString);
        const slotMins = _toMinutes(slot.time || "");
        if (dbMins >= 0 && slotMins >= 0 && Math.abs(dbMins - slotMins) > 30) {
          wrongCount++;
          timeNotes += `${slot.name}: slot=${slot.time} vs DB=${etString}; `;
        }
      }
      if (wrongCount === 0) timeScore = 100;
      else if (wrongCount === 1) timeScore = 50;
      else timeScore = 0;
    }

    grades.event_times_match_db = { score: timeScore, notes: timeNotes };
    if (timeScore < 100 && timeNotes) issues.push(`DB times: ${timeNotes.trim()}`);
  }

  // ── 5. Quick filters make sense (7%) ──
  {
    let score = 100;
    let notes = "";

    const lastResponse = envelopes[envelopes.length - 1];
    if (lastResponse) {
      const chips = lastResponse.chips || [];

      if (chips.length === 0) {
        score = 0;
        notes = "No chips provided";
      } else if (chips.length === 1) {
        score = 50;
        notes = "Only 1 chip — should have 2-4";
      } else if (chips.length > 5) {
        score = 50;
        notes = `Too many chips (${chips.length}) — should be 2-4`;
      }

      if (lastResponse.type === "plan" || lastResponse.type === "plan_update") {
        const chipLabels = chips.map((c) => (c.label || "").toLowerCase());
        const hasApprove = chipLabels.some((l) =>
          /looks good|approve|perfect|love it|great|lock/i.test(l)
        );
        const hasModify = chipLabels.some((l) =>
          /change|modify|swap|different|adjust|edit/i.test(l)
        );
        if (!hasApprove && !hasModify && chips.length > 0) {
          score = Math.min(score, 50);
          notes += "Plan chips missing approve/modify options. ";
        }
      }
    } else {
      score = 0;
      notes = "No response to check chips on";
    }

    if (!notes) notes = "Chips look appropriate";
    grades.quick_filters = { score, notes };
    if (score < 100) issues.push(`Chips: ${notes.trim()}`);
  }

  // ── 6. Gathered requirements properly (7%) ──
  {
    let score = 100;
    let notes = "";

    if (scenario.type === "directness") {
      // For directness scenarios, requirement gathering is about efficiency
      const chatResponses = envelopes.filter((e) => e.type === "chat");
      const planResponse = envelopes.find((e) => e.type === "plan" || e.type === "plan_update");
      const chatBeforePlan = planResponse
        ? chatResponses.filter((c) => envelopes.indexOf(c) < envelopes.indexOf(planResponse))
        : chatResponses;

      if (chatBeforePlan.length > 2) {
        score = 50;
        notes = `Too many questions before building plan (${chatBeforePlan.length} chat responses)`;
      }

      if (scenario.expect_immediate_search && chatBeforePlan.length > 0) {
        score = Math.min(score, 50);
        notes = "Asked questions when should have searched immediately";
      }
    }

    if (!notes) notes = "Requirements gathered appropriately";
    grades.gathered_requirements = { score, notes };
    if (score < 100) issues.push(`Requirements: ${notes}`);
  }

  // ── 7. Used tools correctly (7%) ──
  {
    let score = 100;
    let notes = "";

    const firstToolIdx = transcript.findIndex((t) => t.role === "tool_use");
    const firstPlanIdx = transcript.findIndex(
      (t) =>
        t.role === "assistant" &&
        t.envelope &&
        (t.envelope.type === "plan" || t.envelope.type === "plan_update")
    );

    if (firstToolIdx === -1) {
      if (firstPlanIdx !== -1) {
        // Plan delivered without tool calls — bad unless it's a custom-only or cancel scenario
        if (!scenario.expect_no_custom_added && !scenario.expect_ask_for_time) {
          score = 0;
          notes = "Plan delivered without any tool calls";
        }
      } else {
        const chatOnlyOk = ["directness"].includes(scenario.type) &&
          !scenario.expect_immediate_search;
        if (!chatOnlyOk) {
          score = 0;
          notes = "No tools called";
        } else {
          notes = "Chat-only response, acceptable for this scenario";
        }
      }
    } else if (firstPlanIdx !== -1 && firstToolIdx > firstPlanIdx) {
      score = 0;
      notes = "Tools called after plan was delivered";
    }

    // Check location accuracy in tool calls
    if (toolCalls.length > 0 && allSlots.length > 0) {
      const locationToolCalls = toolCalls.filter(
        (tc) =>
          tc.input.location &&
          tc.input.location.toLowerCase().includes(location.city.toLowerCase())
      );
      if (locationToolCalls.length === 0) {
        score = 0;
        notes = "Tool calls did not target the requested location";
      }
    }

    // For amendment "replace with outdoors", should search before updating
    if (scenario.expect_search_before_update) {
      const searchCalls = toolCalls.filter((tc) => tc.name === "search_events" || tc.name === "search_venues");
      if (searchCalls.length === 0) {
        score = Math.min(score, 50);
        notes += "Should have searched before updating plan for replacement. ";
      }
    }

    grades.used_tools = { score, notes };
    if (score === 0 && notes) issues.push(`Tools: ${notes}`);
  }

  // ── 8. All events have real IDs (7%) ──
  {
    const nonCustomSlots = allSlots.filter((s) => !s.is_custom);
    // Venue recommendations from recommend_and_save_venue intentionally have
    // event_id: null.  Detect them by checking for a real venue/restaurant name
    // (not filler like "Dinner").
    const fillerNamesRe = /^(dinner|lunch|brunch|drinks|coffee|dessert|custom dinner|breakfast|snack|food|meal)$/i;
    const isVenueRecommendation = (s) =>
      !s.event_id && s.name && s.name.trim().length > 2 && !fillerNamesRe.test(s.name.trim());
    const slotsWithIds = nonCustomSlots.filter((s) => s.event_id);
    const slotsWithoutIds = nonCustomSlots.filter((s) => !s.event_id && !isVenueRecommendation(s));

    let score = 100;
    let notes = "";

    if (nonCustomSlots.length === 0) {
      notes = "No plan slots to verify";
    } else if (slotsWithoutIds.length > 0) {
      score = 0;
      notes = `${slotsWithoutIds.length} slot(s) missing event_id: ${slotsWithoutIds.map((s) => s.name).join(", ")}`;
    }

    // Verify IDs exist in DB
    if (slotsWithIds.length > 0) {
      try {
        const ids = slotsWithIds.map((s) => s.event_id);
        const idsFilter = `(${ids.join(",")})`;
        const found = await supabaseQuery("events", {
          id: `in.${idsFilter}`,
          select: "id,start_at,category",
        });
        const foundIds = new Set(found.map((e) => e.id));
        const missing = ids.filter((id) => !foundIds.has(id));
        if (missing.length > 0) {
          score = 0;
          notes += ` | ${missing.length} event ID(s) not found in DB`;
        }
      } catch (err) {
        notes += ` | DB verification failed: ${err.message}`;
      }
    }

    grades.real_event_ids = { score, notes };
    if (score === 0 && notes) issues.push(`Real IDs: ${notes.trim()}`);
  }

  // ── 9. Conflict detection (6%) ──
  {
    let score = 100;
    let notes = "";

    // For non-conflict-focused scenarios, just check for time overlaps
    if (allSlots.length > 1) {
      const times = allSlots.map((s) => parseTimeToMinutes(s.time));
      const validTimes = times.filter((t) => t !== null);
      for (let i = 1; i < validTimes.length; i++) {
        if (validTimes[i] === validTimes[i - 1]) {
          score = 50;
          notes = "Time overlap in plan not flagged";
          break;
        }
      }
    }

    if (!notes) notes = "No conflicts to report";
    grades.conflict_detection = { score, notes };
    if (score < 100) issues.push(`Conflict: ${notes}`);
  }

  // ── 10. Time ordering correct (4%) ──
  {
    let score = 100;
    let notes = "";

    if (allSlots.length > 1) {
      const times = allSlots.map((s) => parseTimeToMinutes(s.time));
      const validTimes = times.filter((t) => t !== null);
      for (let i = 1; i < validTimes.length; i++) {
        if (validTimes[i] < validTimes[i - 1]) {
          score = 0;
          notes = `Out of order: slot ${i} (${allSlots[i]?.time}) before slot ${i - 1} (${allSlots[i - 1]?.time})`;
          break;
        }
      }
    }

    if (!notes) notes = "Times in order";
    grades.time_ordering = { score, notes };
    if (score === 0 && notes !== "Times in order") issues.push(`Time order: ${notes}`);
  }

  // ── 11. No hallucination (4%) ──
  {
    let score = 100;
    let notes = "";

    const nonCustomSlots = allSlots.filter((s) => !s.is_custom);

    if (nonCustomSlots.length > 0 && toolCalls.length === 0) {
      score = 0;
      notes = "Slots created without any tool calls (hallucinated)";
    }

    // Exclude venue recommendations (real names, no event_id) from hallucination check
    const venueFillerRe = /^(dinner|lunch|brunch|drinks|coffee|dessert|custom dinner|breakfast|snack|food|meal)$/i;
    const isVenueRec = (s) =>
      !s.event_id && s.name && s.name.trim().length > 2 && !venueFillerRe.test(s.name.trim());
    const hallucinated = nonCustomSlots.filter((s) => !s.event_id && !isVenueRec(s));
    if (hallucinated.length > 0) {
      score = 0;
      notes = `${hallucinated.length} slot(s) without event_id: ${hallucinated.map((s) => s.name).join(", ")}`;
    }

    const fillerNames = /^(dinner|lunch|brunch|drinks|coffee|dessert|custom dinner|breakfast|snack)$/i;
    const fillers = nonCustomSlots.filter((s) => fillerNames.test((s.name || "").trim()));
    if (fillers.length > 0) {
      score = 0;
      notes = `Filler slot(s): ${fillers.map((s) => s.name).join(", ")}`;
    }

    const noResultTools = transcript.filter(
      (t) =>
        t.role === "tool_result" &&
        t.result &&
        (t.result.includes("No events found") ||
          t.result.includes("No venues found"))
    );
    if (noResultTools.length > 0 && nonCustomSlots.length > 0) {
      const successfulResults = transcript.filter(
        (t) =>
          t.role === "tool_result" &&
          t.result &&
          !t.result.includes("No events found") &&
          !t.result.includes("No venues found") &&
          !t.result.includes("Error")
      );
      if (successfulResults.length === 0) {
        score = 0;
        notes = "All searches returned no results but plan has slots (hallucinated)";
      }
    }

    if (!notes) notes = "No hallucination detected";
    grades.no_hallucination = { score, notes };
    if (score === 0 && notes !== "No hallucination detected") issues.push(`Hallucination: ${notes}`);
  }

  // ── 12. Response format valid (3%) ──
  {
    let score = 100;
    let notes = "";

    for (const entry of transcript.filter((t) => t.role === "assistant")) {
      if (entry.envelope && entry.envelope._parse_error) {
        score = 0;
        notes = "Failed to parse response as JSON";
        break;
      }
      if (entry.envelope) {
        const validTypes = ["chat", "plan", "plan_update", "approve"];
        if (!validTypes.includes(entry.envelope.type)) {
          score = 0;
          notes = `Invalid response type: ${entry.envelope.type}`;
          break;
        }
      }
    }

    // Check for emoji in responses
    for (const entry of envelopes) {
      if (entry.text) {
        const emojiPattern =
          /[\u{1F600}-\u{1F64F}\u{1F300}-\u{1F5FF}\u{1F680}-\u{1F6FF}\u{1F1E0}-\u{1F1FF}\u{2600}-\u{26FF}\u{2700}-\u{27BF}]/u;
        if (emojiPattern.test(entry.text)) {
          score = 0;
          notes = "Response contains emoji (forbidden by system prompt)";
          break;
        }
      }
      if (entry.type === "plan" && entry.days) {
        for (const day of entry.days) {
          for (const slot of (day.slots || [])) {
            if (slot.emoji && slot.emoji.length > 0) {
              const emojiInSlot = /[\u{1F600}-\u{1F64F}\u{1F300}-\u{1F5FF}\u{1F680}-\u{1F6FF}\u{1F1E0}-\u{1F1FF}\u{2600}-\u{26FF}\u{2700}-\u{27BF}]/u;
              if (emojiInSlot.test(slot.emoji)) {
                score = 0;
                notes = "Emoji found in slot emoji field";
                break;
              }
            }
          }
        }
      }
    }

    if (!notes) notes = "Format valid";
    grades.format_valid = { score, notes };
    if (score === 0 && notes !== "Format valid") issues.push(`Format: ${notes}`);
  }

  // ── 13. Response directness (8%) — NEW ──
  {
    let score = 100;
    let notes = "";

    // Check the FIRST assistant response text for directness
    const firstAssistant = envelopes[0];
    if (firstAssistant && firstAssistant.text) {
      const directnessResult = measureDirectness(firstAssistant.text);
      score = directnessResult.score;
      notes = directnessResult.reason;
    } else {
      notes = "No text in first response to measure";
    }

    // For directness-type scenarios, be extra strict
    if (scenario.type === "directness") {
      if (scenario.expect_direct_response || scenario.expect_direct_question) {
        // First response must be direct
        if (firstAssistant && firstAssistant.text) {
          const text = firstAssistant.text.trim();
          const startsWithFiller = FILLER_PHRASES.some((p) => p.test(text));
          if (startsWithFiller) {
            score = Math.min(score, 0);
            notes = `Starts with filler phrase for a directness test scenario`;
          }
        }
      }
    }

    grades.response_directness = { score, notes };
    if (score < 100) issues.push(`Directness: ${notes}`);
  }

  // ── Calculate weighted score ──

  const weights = {
    plan_makes_sense: 0.13,
    did_what_asked: 0.14,
    user_satisfaction: 0.13,
    event_times_match_db: 0.07,
    quick_filters: 0.07,
    gathered_requirements: 0.07,
    used_tools: 0.07,
    real_event_ids: 0.07,
    conflict_detection: 0.06,
    time_ordering: 0.04,
    no_hallucination: 0.04,
    format_valid: 0.03,
    response_directness: 0.08,
  };

  let totalScore = 0;
  for (const [key, weight] of Object.entries(weights)) {
    totalScore += (grades[key]?.score || 0) * weight;
  }

  return { score: Math.round(totalScore * 10) / 10, grades, issues };
}

// ─── Main Runner ─────────────────────────────────────────────────────────────

async function main() {
  console.log("Fetching secrets...");
  SECRETS = await getSecrets(["ANTHROPIC_API_KEY"]);

  if (!SECRETS.ANTHROPIC_API_KEY) {
    console.error("ANTHROPIC_API_KEY not found in secrets or env vars");
    process.exit(1);
  }
  console.log("Secrets loaded. ANTHROPIC_API_KEY present.");

  // Build all 200 scenarios
  const allScenarios = [];
  for (const loc of LOCATIONS) {
    const scenarios = buildScenarios(loc);
    for (const s of scenarios) {
      allScenarios.push({ ...s, location: loc });
    }
  }

  console.log(`\nTotal scenarios: ${allScenarios.length}`);
  console.log(`Concurrency: ${CONCURRENCY}`);
  console.log(`Timeout per conversation: ${CONVERSATION_TIMEOUT_MS}ms`);
  console.log(`Results will be saved to: ${RESULTS_PATH}\n`);

  const results = [];
  let completed = 0;
  let passed = 0;
  let totalScore = 0;

  // Process in batches of CONCURRENCY
  for (let i = 0; i < allScenarios.length; i += CONCURRENCY) {
    const batch = allScenarios.slice(i, i + CONCURRENCY);

    const batchResults = await Promise.all(
      batch.map(async (s, batchIdx) => {
        const idx = i + batchIdx + 1;
        const startTime = Date.now();
        console.log(
          `[${idx}/${allScenarios.length}] Running: "${s.label}" (${s.location.name})...`
        );

        try {
          const convResult = await runConversation(s, s.location);
          const gradeResult = await gradeConversation(
            s,
            s.location,
            convResult
          );
          const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);

          const passedConv = gradeResult.score >= 70;
          console.log(
            `  [${idx}] Score: ${gradeResult.score} ${passedConv ? "PASS" : "FAIL"} (${elapsed}s) ${gradeResult.issues.length ? "| Issues: " + gradeResult.issues[0] : ""}`
          );

          return {
            id: idx,
            location: s.location.name,
            scenario_type: s.type,
            scenario: s.label,
            score: gradeResult.score,
            grades: gradeResult.grades,
            transcript: convResult.transcript,
            issues: gradeResult.issues,
          };
        } catch (err) {
          const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
          console.error(
            `  [${idx}] ERROR (${elapsed}s): ${err.message}`
          );

          return {
            id: idx,
            location: s.location.name,
            scenario_type: s.type,
            scenario: s.label,
            score: 0,
            grades: {},
            transcript: [{ role: "error", content: err.message }],
            issues: [`Fatal error: ${err.message}`],
          };
        }
      })
    );

    for (const r of batchResults) {
      results.push(r);
      completed++;
      totalScore += r.score;
      if (r.score >= 70) passed++;
    }

    // Save intermediate results
    if (completed % SAVE_INTERVAL === 0 || completed === allScenarios.length) {
      saveResults(results, completed, passed, totalScore);
    }

    // 1-second delay between batches
    if (i + CONCURRENCY < allScenarios.length) {
      await new Promise((r) => setTimeout(r, 1000));
    }
  }

  // Final save
  saveResults(results, completed, passed, totalScore);

  console.log("\n" + "=".repeat(60));
  console.log("FINAL RESULTS");
  console.log("=".repeat(60));
  console.log(`Total: ${completed}`);
  console.log(`Passed (>=70): ${passed}`);
  console.log(`Failed (<70): ${completed - passed}`);
  console.log(`Avg Score: ${(totalScore / completed).toFixed(1)}`);
  console.log(`\nResults saved to: ${RESULTS_PATH}`);
}

function saveResults(results, total, passed, totalScore) {
  // By location
  const byLocation = {};
  for (const loc of LOCATIONS) {
    const locResults = results.filter((r) => r.location === loc.name);
    if (locResults.length === 0) continue;
    const avg =
      locResults.reduce((sum, r) => sum + r.score, 0) / locResults.length;
    byLocation[loc.name] = {
      avg_score: Math.round(avg * 10) / 10,
      count: locResults.length,
    };
  }

  // By criterion
  const criterionKeys = [
    "plan_makes_sense",
    "did_what_asked",
    "user_satisfaction",
    "event_times_match_db",
    "quick_filters",
    "gathered_requirements",
    "used_tools",
    "real_event_ids",
    "conflict_detection",
    "time_ordering",
    "no_hallucination",
    "format_valid",
    "response_directness",
  ];
  const byCriterion = {};
  for (const key of criterionKeys) {
    const withGrade = results.filter((r) => r.grades[key]);
    const fullScore = withGrade.filter((r) => r.grades[key].score === 100).length;
    const partialScore = withGrade.filter((r) => r.grades[key].score > 0 && r.grades[key].score < 100).length;
    const zeroScore = withGrade.filter((r) => r.grades[key].score === 0).length;
    const avgScore = withGrade.length
      ? Math.round(withGrade.reduce((sum, r) => sum + r.grades[key].score, 0) / withGrade.length * 10) / 10
      : 0;
    byCriterion[key] = {
      avg_score: avgScore,
      full_marks: fullScore,
      partial: partialScore,
      zero: zeroScore,
      total: withGrade.length,
    };
  }

  // By scenario type
  const scenarioTypes = [
    "custom_event",
    "amendment",
    "competing_plan",
    "directness",
  ];
  const byScenarioType = {};
  for (const type of scenarioTypes) {
    const typeResults = results.filter((r) => r.scenario_type === type);
    if (typeResults.length === 0) continue;
    const avg =
      typeResults.reduce((sum, r) => sum + r.score, 0) / typeResults.length;
    const passCount = typeResults.filter((r) => r.score >= 70).length;
    byScenarioType[type] = {
      avg_score: Math.round(avg * 10) / 10,
      count: typeResults.length,
      pass_rate: Math.round((passCount / typeResults.length) * 100 * 10) / 10,
    };
  }

  // Top issues
  const issueCounts = {};
  for (const r of results) {
    for (const issue of r.issues) {
      const category = issue.split(":")[0].trim();
      if (!issueCounts[category]) {
        issueCounts[category] = { count: 0, examples: [] };
      }
      issueCounts[category].count++;
      if (issueCounts[category].examples.length < 3) {
        issueCounts[category].examples.push({
          scenario: r.scenario,
          detail: issue,
        });
      }
    }
  }
  const topIssues = Object.entries(issueCounts)
    .sort((a, b) => b[1].count - a[1].count)
    .slice(0, 15)
    .map(([issue, data]) => ({
      issue,
      count: data.count,
      examples: data.examples,
    }));

  const output = {
    summary: {
      version: "v3",
      total,
      passed,
      failed: total - passed,
      avg_score: total ? Math.round((totalScore / total) * 10) / 10 : 0,
      by_location: byLocation,
      by_criterion: byCriterion,
      by_scenario_type: byScenarioType,
    },
    conversations: results,
    top_issues: topIssues,
  };

  fs.writeFileSync(RESULTS_PATH, JSON.stringify(output, null, 2));
}

main().catch((err) => {
  console.error("Fatal error:", err);
  process.exit(1);
});
