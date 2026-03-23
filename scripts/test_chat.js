/**
 * test_chat.js — Automated chat testing and grading for the Local concierge
 *
 * Runs 250 simulated conversations (25 scenarios x 10 locations) against the
 * Claude API using the same system prompt and tools as the mobile app, then
 * grades each conversation on an 11-criterion rubric.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/test_chat.js
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");
const fs = require("fs");
const path = require("path");

// ─── Constants ───────────────────────────────────────────────────────────────

const CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
const MODEL = "claude-sonnet-4-20250514";
const MAX_TOKENS = 4096;
const CONCURRENCY = 3;
const CONVERSATION_TIMEOUT_MS = 60_000;
const SAVE_INTERVAL = 25;
const RESULTS_PATH = path.join(__dirname, "chat_test_results.json");

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

// ─── Scenario Definitions ────────────────────────────────────────────────────

function buildScenarios(loc) {
  const city = loc.city;
  const name = loc.name;

  // 8 plan builders
  const planBuilders = [
    {
      type: "plan_builder",
      label: `Plan a date night in ${city}`,
      turns: [
        `Plan a date night in ${city}`,
        "Something with live music and dinner for 2",
      ],
    },
    {
      type: "plan_builder",
      label: `Weekend with kids under 10 in ${city}`,
      turns: [
        `I need a weekend plan for my family with kids under 10 near ${city}`,
        "Saturday works, maybe 3-4 activities",
      ],
    },
    {
      type: "plan_builder",
      label: `Full day trip to ${city}`,
      turns: [
        `Plan a full day trip to ${city} for me and my partner`,
        "We like arts and outdoors",
      ],
    },
    {
      type: "plan_builder",
      label: `Multi-day trip for 4 in ${name}`,
      turns: [
        `We're planning a 2-day trip to ${city} area for 4 people`,
        "Interests: food, history, and nightlife",
        "Looks good but can you add more food options?",
      ],
    },
    {
      type: "plan_builder",
      label: `Surprise me in ${city}`,
      turns: [`Surprise me with something fun near ${city}`],
    },
    {
      type: "plan_builder",
      label: `Chill afternoon in ${city}`,
      turns: [`Plan a chill afternoon for 2 in ${city}`],
    },
    {
      type: "plan_builder",
      label: `Bachelor party ideas in ${city}`,
      turns: [
        `Bachelor party ideas near ${city}`,
        "Group of 8, we like sports and bars",
      ],
    },
    {
      type: "plan_builder",
      label: `Solo day exploring ${city}`,
      turns: [
        `I have a free day to explore ${city} solo`,
        "I'm into museums and coffee shops",
      ],
    },
  ];

  // 6 direct searches
  const directSearches = [
    {
      type: "direct_search",
      label: `Comedy shows this weekend in ${city}`,
      turns: [`Any comedy shows this weekend near ${city}?`],
    },
    {
      type: "direct_search",
      label: `Free events near ${city}`,
      turns: [`What free events are happening near ${city}?`],
    },
    {
      type: "direct_search",
      label: `What's happening at venues in ${city}`,
      turns: [`What events are happening at venues in ${city} this week?`],
    },
    {
      type: "direct_search",
      label: `Live music tonight in ${city}`,
      turns: [`Live music tonight in ${city}`],
    },
    {
      type: "direct_search",
      label: `Museums to visit near ${city}`,
      turns: [`What museums can I visit near ${city}?`],
    },
    {
      type: "direct_search",
      label: `Outdoor activities near ${city}`,
      turns: [`Outdoor activities near ${city}`],
    },
  ];

  // 5 amendments (multi-turn: first build a plan, then amend it)
  const amendments = [
    {
      type: "amendment",
      label: `Add event at 1pm in ${city}`,
      turns: [
        `Plan an afternoon in ${city}`,
        "Add something fun at 1pm",
      ],
    },
    {
      type: "amendment",
      label: `Remove the 3pm event in ${city}`,
      turns: [
        `Plan a full day in ${city}`,
        "Remove the 3pm event",
      ],
    },
    {
      type: "amendment",
      label: `Move dinner to 7pm in ${city}`,
      turns: [
        `Plan a date night in ${city}`,
        "Move the last event to 7pm",
      ],
    },
    {
      type: "amendment",
      label: `Replace first event with outdoors in ${city}`,
      turns: [
        `Plan a day in ${city}`,
        "Replace the first event with something outdoors",
      ],
    },
    {
      type: "amendment",
      label: `Add a coffee stop in ${city}`,
      turns: [
        `Plan a morning in ${city}`,
        "Add a coffee stop between the first and second events",
      ],
    },
  ];

  // 3 conflict triggers
  const conflicts = [
    {
      type: "conflict",
      label: `Time conflict in ${city}`,
      turns: [
        `Plan an afternoon in ${city}`,
        "Add another event at the same time as the first one",
      ],
    },
    {
      type: "conflict",
      label: `Distance conflict near ${city}`,
      turns: [
        `Plan events in both ${city} and Boston in the same afternoon`,
      ],
    },
    {
      type: "conflict",
      label: `Double-book in ${city}`,
      turns: [
        `Plan a day in ${city}`,
        "Add a 1pm event even if there's already one at 1pm",
      ],
    },
  ];

  // 3 edge cases
  const edgeCases = [
    {
      type: "edge_case",
      label: `Vague request in ${city}`,
      turns: [`idk just help me find something near ${city}`],
    },
    {
      type: "edge_case",
      label: `Niche request in ${city}`,
      turns: [
        `Are there any underwater basket weaving classes in ${city}?`,
      ],
    },
    {
      type: "edge_case",
      label: `Non-event request in ${city}`,
      turns: [`What's the best pizza in ${city}?`],
    },
  ];

  return [
    ...planBuilders,
    ...directSearches,
    ...amendments,
    ...conflicts,
    ...edgeCases,
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
        // Can't set two params with same key in URLSearchParams, use workaround
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
  const messages = []; // Claude API messages
  const toolCalls = []; // Track all tool calls made
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
            // Update the plan context
            currentPlanContext = updatePlanContext(
              currentPlanContext,
              envelope
            );
          }
        }
        break;
      }

      // Tool use — add assistant blocks, execute tools, send results back
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
  // Simple: just rebuild from envelope.day
  if (!existing || !envelope.day) return existing;
  // For simplicity, just append the update info
  return existing + `\n\n[UPDATED Day ${(envelope.day_index || 0) + 1}]`;
}

// ─── Grading ─────────────────────────────────────────────────────────────────

async function gradeConversation(scenario, location, result) {
  const { transcript, toolCalls, lastEnvelope } = result;
  const grades = {};
  const issues = [];

  // Helper: find all envelopes in transcript
  const envelopes = transcript
    .filter((t) => t.role === "assistant" && t.envelope)
    .map((t) => t.envelope);

  const planEnvelope = envelopes.find(
    (e) => e.type === "plan" || e.type === "plan_update"
  );
  const allSlots = [];
  if (planEnvelope) {
    if (planEnvelope.days) {
      for (const day of planEnvelope.days) {
        if (day.slots) allSlots.push(...day.slots);
      }
    }
    if (planEnvelope.day && planEnvelope.day.slots) {
      allSlots.push(...planEnvelope.day.slots);
    }
  }

  // 1. Used tools before recommending (12%)
  {
    const firstToolIdx = transcript.findIndex((t) => t.role === "tool_use");
    const firstPlanIdx = transcript.findIndex(
      (t) =>
        t.role === "assistant" &&
        t.envelope &&
        (t.envelope.type === "plan" || t.envelope.type === "plan_update")
    );
    const firstChatWithRecs = transcript.findIndex(
      (t) =>
        t.role === "assistant" &&
        t.envelope &&
        t.envelope.type === "chat" &&
        t.envelope.text &&
        (t.envelope.text.toLowerCase().includes("here") ||
          t.envelope.text.toLowerCase().includes("found") ||
          t.envelope.text.toLowerCase().includes("recommend"))
    );

    let pass = true;
    let notes = "";
    if (firstToolIdx === -1) {
      // No tools used at all — could be a chat-only response (edge case)
      if (firstPlanIdx !== -1) {
        pass = false;
        notes = "Plan delivered without any tool calls";
      } else {
        // Chat only, might be ok for edge cases
        pass = scenario.type === "edge_case";
        notes = pass ? "Edge case, no tools needed" : "No tools called";
      }
    } else if (firstPlanIdx !== -1 && firstToolIdx > firstPlanIdx) {
      pass = false;
      notes = "Tools called after plan was delivered";
    }

    grades.used_tools = { score: pass ? 100 : 0, pass, notes };
    if (!pass) issues.push(`Used tools: ${notes}`);
  }

  // 2. All events have real IDs (12%)
  {
    const nonCustomSlots = allSlots.filter((s) => !s.is_custom);
    const slotsWithIds = nonCustomSlots.filter((s) => s.event_id);
    const slotsWithoutIds = nonCustomSlots.filter((s) => !s.event_id);

    let pass = true;
    let notes = "";

    if (nonCustomSlots.length === 0) {
      // No plan slots — might be a chat-only scenario
      pass = true;
      notes = "No plan slots to verify";
    } else if (slotsWithoutIds.length > 0) {
      pass = false;
      notes = `${slotsWithoutIds.length} slot(s) missing event_id: ${slotsWithoutIds.map((s) => s.name).join(", ")}`;
    }

    // Verify IDs exist in DB
    if (slotsWithIds.length > 0) {
      try {
        const ids = slotsWithIds.map((s) => s.event_id);
        const idsFilter = `(${ids.join(",")})`;
        const found = await supabaseQuery("events", {
          id: `in.${idsFilter}`,
          select: "id",
        });
        const foundIds = new Set(found.map((e) => e.id));
        const missing = ids.filter((id) => !foundIds.has(id));
        if (missing.length > 0) {
          pass = false;
          notes += ` | ${missing.length} event ID(s) not found in DB: ${missing.join(", ")}`;
        }
      } catch (err) {
        notes += ` | DB verification failed: ${err.message}`;
      }
    }

    grades.real_event_ids = { score: pass ? 100 : 0, pass, notes };
    if (!pass) issues.push(`Real event IDs: ${notes}`);
  }

  // 3. Events match the request (10%)
  {
    let pass = true;
    let notes = "";

    if (allSlots.length === 0 && scenario.type !== "edge_case") {
      // For non-edge cases with no results, check if Claude explained
      const hasExplanation = envelopes.some(
        (e) =>
          e.text &&
          (e.text.toLowerCase().includes("no") ||
            e.text.toLowerCase().includes("couldn't find") ||
            e.text.toLowerCase().includes("don't have"))
      );
      pass = hasExplanation;
      notes = hasExplanation
        ? "No results but Claude explained"
        : "No plan and no explanation";
    } else if (allSlots.length > 0) {
      // Check location match for tool calls
      const locationToolCalls = toolCalls.filter(
        (tc) =>
          tc.input.location &&
          tc.input.location.toLowerCase().includes(location.city.toLowerCase())
      );
      if (locationToolCalls.length === 0 && toolCalls.length > 0) {
        // Tools were called but not for the right location
        pass = false;
        notes = "Tool calls did not target the requested location";
      }
    }

    grades.events_match = { score: pass ? 100 : 0, pass, notes };
    if (!pass) issues.push(`Events match: ${notes}`);
  }

  // 4. Did exactly what the user said (12%)
  {
    let score = 100;
    let pass = true;
    let notes = "";

    if (scenario.type === "amendment") {
      // Must deliver a plan_update
      const hasUpdate = envelopes.some((e) => e.type === "plan_update");
      if (!hasUpdate) {
        score = 0;
        pass = false;
        notes = "Amendment requested but no plan_update delivered";
      } else {
        // Check if the specific amendment was applied
        const updateEnv = envelopes.find((e) => e.type === "plan_update");
        const userMsg = scenario.turns[scenario.turns.length - 1].toLowerCase();

        // Check time accuracy: "add X at 1pm" → slot should be at 1pm
        const timeMatch = userMsg.match(/at (\d{1,2}(?::\d{2})?\s*(?:am|pm)?)/i);
        if (timeMatch && updateEnv?.day?.slots) {
          const requestedTime = timeMatch[1].toUpperCase().replace(/\s/g, "");
          const hasCorrectTime = updateEnv.day.slots.some((s) => {
            const slotTime = (s.time || "").toUpperCase().replace(/\s/g, "");
            return slotTime.includes(requestedTime) || requestedTime.includes(slotTime);
          });
          if (!hasCorrectTime) {
            score = 50;
            notes = `Requested time "${timeMatch[1]}" not found in updated slots`;
          }
        }
      }
    }

    grades.followed_instructions = { score, pass, notes };
    if (!pass) issues.push(`Followed instructions: ${notes}`);
    if (score < 100 && score > 0) issues.push(`Partial: ${notes}`);
  }

  // 5. Gathered proper requirements (10%)
  {
    let score = 100;
    let pass = true;
    let notes = "";

    if (scenario.type === "plan_builder") {
      const userTexts = transcript
        .filter((t) => t.role === "user")
        .map((t) => t.content || "")
        .join(" ")
        .toLowerCase();

      const isSurprise = userTexts.includes("surprise") || userTexts.includes("just plan") || userTexts.includes("idk");

      // Check what info the user provided
      const hasWhen = /tonight|weekend|today|saturday|sunday|friday|tomorrow|this week|next week|morning|afternoon|evening/i.test(userTexts);
      const hasWhere = LOCATIONS.some((l) => userTexts.toLowerCase().includes(l.city.toLowerCase()));
      const hasGroupSize = /solo|alone|couple|two|group|friends|family|people|person|us|we/i.test(userTexts);
      const hasVibe = /chill|active|romantic|fun|adventure|laid.?back|energetic|relaxed|fancy|casual|family/i.test(userTexts);
      const hasDuration = /full day|half day|evening|afternoon|morning|couple hours|all day|multi.?day|quick|whole day/i.test(userTexts);

      const provided = [hasWhen, hasWhere, hasGroupSize, hasVibe, hasDuration].filter(Boolean).length;

      if (!isSurprise && provided < 3) {
        // User didn't give much — Claude should have asked
        const assistantTexts = envelopes.map((e) => (e.text || "").toLowerCase()).join(" ");
        const askedQuestions = assistantTexts.includes("?");
        const firstResponse = envelopes[0];
        const wentStraightToPlan = firstResponse && (firstResponse.type === "plan" || firstResponse.type === "plan_update");

        if (wentStraightToPlan && !askedQuestions) {
          score = 50;
          pass = false;
          const missing = [];
          if (!hasWhen) missing.push("when");
          if (!hasWhere) missing.push("where");
          if (!hasGroupSize) missing.push("group size");
          if (!hasVibe) missing.push("vibe");
          if (!hasDuration) missing.push("duration");
          notes = `Built plan without asking about: ${missing.join(", ")}`;
        }
      }
    }

    grades.gathered_requirements = { score, pass, notes };
    if (!pass) issues.push(`Requirements: ${notes}`);
  }

  // 6. Called out conflicts/concerns (10%)
  {
    let pass = true;
    let notes = "";

    if (scenario.type === "conflict") {
      // Check if Claude mentioned the conflict
      const mentionedConflict = envelopes.some(
        (e) =>
          e.text &&
          (e.text.toLowerCase().includes("conflict") ||
            e.text.toLowerCase().includes("overlap") ||
            e.text.toLowerCase().includes("same time") ||
            e.text.toLowerCase().includes("already") ||
            e.text.toLowerCase().includes("instead") ||
            e.text.toLowerCase().includes("far") ||
            e.text.toLowerCase().includes("distance") ||
            e.text.toLowerCase().includes("travel") ||
            e.text.toLowerCase().includes("tight"))
      );
      pass = mentionedConflict;
      notes = mentionedConflict
        ? "Conflict was addressed"
        : "Conflict not mentioned";
    }

    grades.conflict_detection = { score: pass ? 100 : 0, pass, notes };
    if (!pass) issues.push(`Conflict detection: ${notes}`);
  }

  // 7. Time ordering correct (8%)
  {
    let pass = true;
    let notes = "";

    if (allSlots.length > 1) {
      const times = allSlots.map((s) => parseTimeToMinutes(s.time));
      const validTimes = times.filter((t) => t !== null && t < 99999);
      for (let i = 1; i < validTimes.length; i++) {
        if (validTimes[i] < validTimes[i - 1]) {
          pass = false;
          notes = `Time ordering issue: slot ${i} (${allSlots[i]?.time}) before slot ${i - 1} (${allSlots[i - 1]?.time})`;
          break;
        }
      }
    }

    grades.time_ordering = { score: pass ? 100 : 0, pass, notes };
    if (!pass) issues.push(`Time ordering: ${notes}`);
  }

  // 8. No hallucinated venues/activities (8%)
  {
    let pass = true;
    let notes = "";

    // Check if any slots were created without tool calls
    const nonCustomSlots = allSlots.filter((s) => !s.is_custom);
    if (nonCustomSlots.length > 0 && toolCalls.length === 0) {
      pass = false;
      notes = "Slots created without any tool calls (likely hallucinated)";
    }

    // Check for slots without event_id (hallucination indicator)
    const hallucinated = nonCustomSlots.filter((s) => !s.event_id);
    if (hallucinated.length > 0) {
      pass = false;
      notes = `${hallucinated.length} slot(s) without event_id: ${hallucinated.map((s) => s.name).join(", ")}`;
    }

    grades.no_hallucination = { score: pass ? 100 : 0, pass, notes };
    if (!pass) issues.push(`Hallucination: ${notes}`);
  }

  // 9. Handles no results gracefully (6%)
  {
    let pass = true;
    let notes = "";

    // Check if any tool returned "No events found" / "No venues found"
    const noResultTools = transcript.filter(
      (t) =>
        t.role === "tool_result" &&
        t.result &&
        (t.result.includes("No events found") ||
          t.result.includes("No venues found"))
    );

    if (noResultTools.length > 0) {
      // Claude should not have made up events after getting no results
      const afterNoResult = transcript
        .slice(
          transcript.indexOf(noResultTools[noResultTools.length - 1])
        )
        .filter((t) => t.role === "assistant" && t.envelope);

      for (const entry of afterNoResult) {
        if (
          entry.envelope.type === "plan" ||
          entry.envelope.type === "plan_update"
        ) {
          // Check if it used results from a different search
          const slotsAfter = [];
          if (entry.envelope.days) {
            for (const d of entry.envelope.days) {
              if (d.slots) slotsAfter.push(...d.slots);
            }
          }
          if (entry.envelope.day && entry.envelope.day.slots) {
            slotsAfter.push(...entry.envelope.day.slots);
          }
          const hallucinated = slotsAfter.filter(
            (s) => !s.is_custom && !s.event_id
          );
          if (hallucinated.length > 0) {
            pass = false;
            notes =
              "Made up events after search returned no results";
          }
        }
      }

      if (pass) {
        notes = "Handled no-results gracefully";
      }
    }

    grades.no_results_handling = { score: pass ? 100 : 0, pass, notes };
    if (!pass) issues.push(`No results handling: ${notes}`);
  }

  // 10. Response format valid (6%)
  {
    let pass = true;
    let notes = "";

    for (const entry of transcript.filter((t) => t.role === "assistant")) {
      if (entry.envelope && entry.envelope._parse_error) {
        pass = false;
        notes = "Failed to parse response as JSON";
        break;
      }
      if (entry.envelope) {
        const validTypes = ["chat", "plan", "plan_update", "approve"];
        if (!validTypes.includes(entry.envelope.type)) {
          pass = false;
          notes = `Invalid response type: ${entry.envelope.type}`;
          break;
        }
      }
    }

    grades.format_valid = { score: pass ? 100 : 0, pass, notes };
    if (!pass) issues.push(`Format: ${notes}`);
  }

  // 11. Conversation quality (6%)
  {
    let pass = true;
    let notes = "";

    for (const entry of envelopes) {
      if (entry.text) {
        // Check for emoji
        const emojiPattern =
          /[\u{1F600}-\u{1F64F}\u{1F300}-\u{1F5FF}\u{1F680}-\u{1F6FF}\u{1F1E0}-\u{1F1FF}\u{2600}-\u{26FF}\u{2700}-\u{27BF}]/u;
        if (emojiPattern.test(entry.text)) {
          pass = false;
          notes = "Response contains emoji";
          break;
        }
        // Check length — too long?
        const sentences = entry.text
          .split(/[.!?]+/)
          .filter((s) => s.trim().length > 0);
        if (entry.type === "chat" && sentences.length > 5) {
          notes = `Chat response too long (${sentences.length} sentences)`;
          // Soft penalty, don't fail
        }
      }
    }

    grades.conversation_quality = { score: pass ? 100 : 0, pass, notes };
    if (!pass) issues.push(`Quality: ${notes}`);
  }

  // Calculate weighted score
  const weights = {
    used_tools: 0.12,
    real_event_ids: 0.12,
    events_match: 0.1,
    followed_instructions: 0.12,
    gathered_requirements: 0.1,
    conflict_detection: 0.1,
    time_ordering: 0.08,
    no_hallucination: 0.08,
    no_results_handling: 0.06,
    format_valid: 0.06,
    conversation_quality: 0.06,
  };

  let score = 0;
  for (const [key, weight] of Object.entries(weights)) {
    score += (grades[key]?.score || 0) * weight;
  }

  return { score: Math.round(score * 10) / 10, grades, issues };
}

function parseTimeToMinutes(time) {
  if (!time || typeof time !== "string") return null;
  const clean = time.trim().toUpperCase();

  // Handle "8PM", "8:00 PM", "14:00", etc.
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

// ─── Main Runner ─────────────────────────────────────────────────────────────

async function main() {
  console.log("Fetching secrets...");
  SECRETS = await getSecrets(["ANTHROPIC_API_KEY"]);

  if (!SECRETS.ANTHROPIC_API_KEY) {
    console.error("ANTHROPIC_API_KEY not found in secrets or env vars");
    process.exit(1);
  }
  console.log("Secrets loaded. ANTHROPIC_API_KEY present.");

  // Build all 250 scenarios
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
    "used_tools",
    "real_event_ids",
    "events_match",
    "followed_instructions",
    "gathered_requirements",
    "conflict_detection",
    "time_ordering",
    "no_hallucination",
    "no_results_handling",
    "format_valid",
    "conversation_quality",
  ];
  const byCriterion = {};
  for (const key of criterionKeys) {
    const withGrade = results.filter((r) => r.grades[key]);
    const passCount = withGrade.filter((r) => r.grades[key].pass).length;
    const failCount = withGrade.length - passCount;
    byCriterion[key] = {
      pass_rate: withGrade.length
        ? Math.round((passCount / withGrade.length) * 100 * 10) / 10
        : 0,
      failures: failCount,
    };
  }

  // By scenario type
  const scenarioTypes = [
    "plan_builder",
    "direct_search",
    "amendment",
    "conflict",
    "edge_case",
  ];
  const byScenarioType = {};
  for (const type of scenarioTypes) {
    const typeResults = results.filter((r) => r.scenario_type === type);
    if (typeResults.length === 0) continue;
    const avg =
      typeResults.reduce((sum, r) => sum + r.score, 0) / typeResults.length;
    byScenarioType[type] = {
      avg_score: Math.round(avg * 10) / 10,
      count: typeResults.length,
    };
  }

  // Top issues
  const issueCounts = {};
  for (const r of results) {
    for (const issue of r.issues) {
      // Normalize issue to category
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
    .slice(0, 10)
    .map(([issue, data]) => ({
      issue,
      count: data.count,
      examples: data.examples,
    }));

  const output = {
    summary: {
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
