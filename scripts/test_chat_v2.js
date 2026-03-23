/**
 * test_chat_v2.js — Hard-mode chat testing and grading for the Local concierge
 *
 * Runs 500 simulated conversations (50 scenarios x 10 locations) against the
 * Claude API using the same system prompt and tools as the mobile app, then
 * grades each conversation on a strict 11-criterion rubric.
 *
 * Scenario types per location (50 total):
 *   12 multi-turn plan builders with back-and-forth
 *    8 sequential amendments
 *    8 conflict and edge cases
 *    8 ambiguous/messy requests
 *    6 requirement gathering tests
 *    4 restaurant/venue recommendations
 *    4 quick filter/chip quality
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/test_chat_v2.js
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");
const fs = require("fs");
const path = require("path");

// ─── Constants ───────────────────────────────────────────────────────────────

const CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
const MODEL = "claude-sonnet-4-20250514";
const MAX_TOKENS = 4096;
const CONCURRENCY = 3;
const CONVERSATION_TIMEOUT_MS = 120_000;
const SAVE_INTERVAL = 50;
const RESULTS_PATH = path.join(__dirname, "chat_test_results_v2.json");

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

// ─── Scenario Definitions (50 per location) ────────────────────────────────

function buildScenarios(loc) {
  const city = loc.city;
  const name = loc.name;

  // ── 12 Multi-turn plan builders with back-and-forth ──

  const planBuildersComplex = [
    {
      type: "plan_builder_complex",
      label: `Date night with changes in ${city}`,
      turns: [
        `Plan a date night for me and my girlfriend this Saturday in ${city}`,
        "Actually can we do something more active? Like not just dinner",
        "Perfect, but add a nice cocktail bar after the last event",
      ],
      expect_satisfaction: "amendments_after_plan",
    },
    {
      type: "plan_builder_complex",
      label: `Full day for 6 with kids pivot in ${city}`,
      turns: [
        `I need a full day plan for 6 people in ${city}`,
        "We like music and food",
        "Actually 2 of us have kids under 5, can you make it more family-friendly?",
        "Perfect but move the last event an hour earlier",
      ],
      expect_satisfaction: "major_restructuring",
    },
    {
      type: "plan_builder_complex",
      label: `Multi-day trip with per-day themes in ${city}`,
      turns: [
        `Planning a 3-day trip to ${city} for me and my partner`,
        "Day 1 should be museums and culture, day 2 outdoors and nature, day 3 food and nightlife",
        "Day 2 is too packed, can you remove one event from it?",
      ],
      expect_satisfaction: "amendments_after_plan",
    },
    {
      type: "plan_builder_complex",
      label: `Plan something fun then refine in ${city}`,
      turns: [
        `Plan something fun in ${city}`,
        "Saturday, just me and my girlfriend, nothing too fancy",
        "Actually can we do something more active instead?",
        "And add dinner somewhere nice after",
      ],
      expect_satisfaction: "major_restructuring",
    },
    {
      type: "plan_builder_complex",
      label: `Weekend getaway with shifting preferences in ${city}`,
      turns: [
        `Weekend getaway ideas near ${city}?`,
        "2 nights, couple trip, we want to explore the area",
        "Hmm actually we're more into food and drinks than sightseeing",
        "Can you also add a brunch spot for Sunday morning?",
      ],
      expect_satisfaction: "amendments_after_plan",
    },
    {
      type: "plan_builder_complex",
      label: `Group outing with constraints in ${city}`,
      turns: [
        `Planning a group outing in ${city} for about 10 people`,
        "Mix of ages, some are 21+ and some aren't. Need something everyone can do",
        "One person is vegetarian, make sure the restaurant works for them",
        "Actually let's skip the bar since half the group can't drink",
      ],
      expect_satisfaction: "amendments_after_plan",
    },
    {
      type: "plan_builder_complex",
      label: `Anniversary surprise in ${city}`,
      turns: [
        `I want to plan a special anniversary evening in ${city}`,
        "It's our 5th anniversary, she loves art and Italian food",
        "Can you make the dinner reservation earlier? Like 6pm instead",
        "And is there anything with live jazz that night?",
      ],
      expect_satisfaction: "amendments_after_plan",
    },
    {
      type: "plan_builder_complex",
      label: `Rainy day backup plan in ${city}`,
      turns: [
        `What can I do in ${city} on a rainy Saturday?`,
        "Group of 4 friends, we're in our 20s",
        "Those are good but can you swap the second thing for something more unique? We've done all the touristy stuff",
        "Add a dinner spot nearby",
      ],
      expect_satisfaction: "amendments_after_plan",
    },
    {
      type: "plan_builder_complex",
      label: `Tourist first visit to ${city}`,
      turns: [
        `First time visiting ${city}, what should I do?`,
        "I have a full day, traveling solo, I like history and good coffee",
        "That looks great but I want to end the day with something lively, like a bar or live music",
      ],
      expect_satisfaction: "amendments_after_plan",
    },
    {
      type: "plan_builder_complex",
      label: `Birthday party planning in ${city}`,
      turns: [
        `Planning my friend's 30th birthday in ${city}`,
        "About 12 people, we want something fun and memorable, not just dinner",
        "She loves karaoke and dancing, can you work that in?",
        "Can we start later? Like nothing before 5pm",
      ],
      expect_satisfaction: "major_restructuring",
    },
    {
      type: "plan_builder_complex",
      label: `Slow morning to active afternoon in ${city}`,
      turns: [
        `I want a chill morning that turns into an active afternoon in ${city}`,
        "Couple, mid-30s, we like brunch and hiking",
        "That works but the gap between brunch and the hike is too big, can you fill it?",
      ],
      expect_satisfaction: "amendments_after_plan",
    },
    {
      type: "plan_builder_complex",
      label: `Budget-conscious day out in ${city}`,
      turns: [
        `Plan a day in ${city} but keep it cheap`,
        "Just me and a friend, we like being outside",
        "What about food? We need lunch and dinner but nothing over $15 per person",
        "Actually skip dinner, we'll eat at home. Just add an afternoon coffee stop",
      ],
      expect_satisfaction: "amendments_after_plan",
    },
  ];

  // ── 8 Sequential amendments ──

  const amendments = [
    {
      type: "amendment",
      label: `Chain of time changes in ${city}`,
      turns: [
        `Plan an afternoon in ${city} for 2`,
        "Add something fun at 1pm",
        "Actually make it 2pm instead",
        "And add lunch before that",
        "Remove the first event",
      ],
      expect_amendment_count: 4,
    },
    {
      type: "amendment",
      label: `Swap and shift everything in ${city}`,
      turns: [
        `Plan a day in ${city}`,
        "Swap the second event for something outdoors",
        "Now add a coffee stop at 3pm",
        "Move everything 1 hour later",
      ],
      expect_amendment_count: 3,
    },
    {
      type: "amendment",
      label: `Rebuild keeping one piece in ${city}`,
      turns: [
        `Plan an evening in ${city}`,
        "I don't like any of these except the dinner, start over but keep the dinner",
      ],
      expect_amendment_count: 1,
    },
    {
      type: "amendment",
      label: `Add then immediately remove in ${city}`,
      turns: [
        `Plan a morning in ${city}`,
        "Add a museum visit at 11am",
        "Actually remove the museum, add a brewery tour at 11am instead",
      ],
      expect_amendment_count: 2,
    },
    {
      type: "amendment",
      label: `Reorder entire plan in ${city}`,
      turns: [
        `Plan a full day in ${city}`,
        "Can you flip the order? I want to do the last thing first and work backwards",
      ],
      expect_amendment_count: 1,
    },
    {
      type: "amendment",
      label: `Add between every event in ${city}`,
      turns: [
        `Plan a day in ${city} with 3 activities`,
        "Add a coffee break between the first and second event",
        "And add a quick snack between the second and third event",
      ],
      expect_amendment_count: 2,
    },
    {
      type: "amendment",
      label: `Replace with specific venue in ${city}`,
      turns: [
        `Plan a date night in ${city}`,
        "Replace the dinner with somewhere that has outdoor seating",
        "And move that dinner to 7:30pm exactly",
      ],
      expect_amendment_count: 2,
    },
    {
      type: "amendment",
      label: `Progressive plan building in ${city}`,
      turns: [
        `I'm free all day Saturday in ${city}`,
        "OK add a morning hike or walk around 9am",
        "Now add lunch at noon",
        "Add an afternoon activity around 2pm",
        "And finish with dinner at 6pm",
      ],
      expect_amendment_count: 4,
    },
  ];

  // ── 8 Conflict and edge cases ──

  const conflicts = [
    {
      type: "conflict",
      label: `Time overlap detection in ${city}`,
      turns: [
        `Plan an afternoon in ${city}`,
        "Add a museum visit at the exact same time as the first event",
      ],
      expect_conflict: "time_overlap",
    },
    {
      type: "conflict",
      label: `Geographic impossibility near ${city}`,
      turns: [
        `Plan a day with events in both ${city} and Cape Cod in the same afternoon`,
      ],
      expect_conflict: "geographic",
    },
    {
      type: "conflict",
      label: `No events tonight in ${city}`,
      turns: [
        `Find me a concert happening tonight in ${city}`,
      ],
      expect_conflict: "no_results_honest",
    },
    {
      type: "conflict",
      label: `Specific impossible date in ${city}`,
      turns: [
        `What events are happening on Christmas Day in ${city}?`,
      ],
      expect_conflict: "no_results_honest",
    },
    {
      type: "conflict",
      label: `Generic dinner should use venue tool in ${city}`,
      turns: [
        `Plan an evening in ${city}`,
        "Add dinner at 6pm",
      ],
      expect_conflict: "must_use_venue_tool",
    },
    {
      type: "conflict",
      label: `Nonexistent thing in ${city}`,
      turns: [
        `Are there any underwater basket weaving classes in ${city}?`,
      ],
      expect_conflict: "no_results_honest",
    },
    {
      type: "conflict",
      label: `Vague reference after plan in ${city}`,
      turns: [
        `Plan a day in ${city}`,
        "No not that one, the other one",
      ],
      expect_conflict: "clarification_needed",
    },
    {
      type: "conflict",
      label: `Cancel everything in ${city}`,
      turns: [
        `Plan an afternoon in ${city}`,
        "Cancel everything, start completely over with a different vibe",
        "Make it a chill evening instead of an active afternoon",
      ],
      expect_conflict: "full_restart",
    },
  ];

  // ── 8 Ambiguous/messy requests ──

  const ambiguous = [
    {
      type: "ambiguous",
      label: `Totally vague in ${city}`,
      turns: [
        `idk something fun maybe outdoors or not near ${city}`,
      ],
    },
    {
      type: "ambiguous",
      label: `Hearsay reference in ${city}`,
      turns: [
        `my friend said there's a thing at that place downtown in ${city}, do you know what it is?`,
      ],
    },
    {
      type: "ambiguous",
      label: `Slang and abbreviations in ${city}`,
      turns: [
        `plan smth for tmrw night w/ my gf in ${city}, nothing too $$$`,
      ],
    },
    {
      type: "ambiguous",
      label: `Reference to past activity in ${city}`,
      turns: [
        `we did that brewery tour last time in ${city}, looking for something different this time`,
        "Like maybe a wine tasting or cocktail bar instead?",
      ],
    },
    {
      type: "ambiguous",
      label: `Typos and misspellings in ${city}`,
      turns: [
        `wats hapening this wknd in ${city}? any gud evnts?`,
      ],
    },
    {
      type: "ambiguous",
      label: `Incomplete sentence in ${city}`,
      turns: [
        `I was thinking about maybe going to ${city} and doing some`,
        "Sorry, some outdoor stuff. Like hiking or maybe a park",
      ],
    },
    {
      type: "ambiguous",
      label: `Contradictory requirements in ${city}`,
      turns: [
        `I want something quiet but also exciting and social in ${city}. Like a calm party?`,
      ],
    },
    {
      type: "ambiguous",
      label: `Long rambling with buried intent in ${city}`,
      turns: [
        `So me and my boyfriend were talking about how we never go out anymore and he mentioned that his coworker went to some cool place in ${city} last weekend, I think it was a restaurant or maybe a bar, and they said the food was amazing, and we thought maybe we should try something like that but we also want to do something fun before dinner like maybe see some live music or go to a museum or whatever really, we're free Saturday after 4pm`,
      ],
    },
  ];

  // ── 6 Requirement gathering tests ──

  const requirementGathering = [
    {
      type: "requirement_gathering",
      label: `Zero info given in ${city}`,
      turns: [
        `Plan something in ${city}`,
      ],
      expect_questions_about: ["when", "who", "vibe", "duration"],
    },
    {
      type: "requirement_gathering",
      label: `Vibe only in ${city}`,
      turns: [
        `Date night in ${city}`,
      ],
      expect_questions_about: ["when", "duration"],
    },
    {
      type: "requirement_gathering",
      label: `When and where only in ${city}`,
      turns: [
        `Saturday in ${city}`,
        "Just looking for things to do",
      ],
      expect_questions_about: ["who", "vibe"],
    },
    {
      type: "requirement_gathering",
      label: `Who and duration only in ${city}`,
      turns: [
        `Full day for the family near ${city}`,
      ],
      expect_questions_about: ["when", "vibe"],
    },
    {
      type: "requirement_gathering",
      label: `All requirements upfront in ${city}`,
      turns: [
        `Romantic evening for 2 in ${city} this Saturday, 3-4 hours, under $100 total. We like live music and good food.`,
      ],
      expect_no_questions: true,
    },
    {
      type: "requirement_gathering",
      label: `Surprise me in ${city}`,
      turns: [
        `Surprise me with something great in ${city}`,
      ],
      expect_no_questions: true,
    },
  ];

  // ── 4 Restaurant/venue recommendations ──

  const venueRecommendations = [
    {
      type: "venue_recommendation",
      label: `Italian near first event in ${city}`,
      turns: [
        `Plan an afternoon in ${city}`,
        "Add a nice Italian restaurant near the first event for dinner",
      ],
    },
    {
      type: "venue_recommendation",
      label: `Dinner reservations near landmark in ${city}`,
      turns: [
        `I need dinner reservations somewhere nice in ${city}`,
      ],
    },
    {
      type: "venue_recommendation",
      label: `Cocktail bar in ${city}`,
      turns: [
        `Suggest a good cocktail bar in ${city}`,
      ],
    },
    {
      type: "venue_recommendation",
      label: `Add brunch to plan in ${city}`,
      turns: [
        `Plan a Sunday morning in ${city}`,
        "What about brunch? Add that to the plan",
      ],
    },
  ];

  // ── 4 Quick filter/chip quality ──

  const chipQuality = [
    {
      type: "chip_quality",
      label: `Chips after plan in ${city}`,
      turns: [
        `Plan a date night in ${city} this Saturday`,
      ],
      expect_chips: "plan_response",
    },
    {
      type: "chip_quality",
      label: `No redundant chips in ${city}`,
      turns: [
        `Show me comedy events in ${city}`,
      ],
      expect_chips: "no_redundant",
    },
    {
      type: "chip_quality",
      label: `Chips after question in ${city}`,
      turns: [
        `Plan something in ${city}`,
      ],
      expect_chips: "question_response",
    },
    {
      type: "chip_quality",
      label: `Chips after amendment in ${city}`,
      turns: [
        `Plan an afternoon in ${city}`,
        "Add a museum at 2pm",
      ],
      expect_chips: "amendment_response",
    },
  ];

  return [
    ...planBuildersComplex,
    ...amendments,
    ...conflicts,
    ...ambiguous,
    ...requirementGathering,
    ...venueRecommendations,
    ...chipQuality,
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
  // Rebuild the updated day's slots into context
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

// ─── Grading (Strict V2 Rubric) ─────────────────────────────────────────────

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

  // Collect all slots from the FIRST plan (for satisfaction comparison)
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

  // ── 1. Plan makes sense (15%) ──
  {
    let score = 100;
    let notes = "";

    if (allSlots.length > 1) {
      const times = allSlots.map((s) => parseTimeToMinutes(s.time));
      const validTimes = times.filter((t) => t !== null);

      // Check time gaps — should be 30min to 5hrs between events
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

      // Check slot count vs request type
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
      if (userTexts.includes("multi-day") || userTexts.includes("3-day") || userTexts.includes("2-day")) {
        if (lastPlanEnvelope && lastPlanEnvelope.days && lastPlanEnvelope.days.length < 2) {
          score = Math.min(score, 50);
          notes += "Multi-day request but only 1 day in plan. ";
        }
      }
    } else if (allSlots.length === 0) {
      // No plan — might be okay for chat-only scenarios
      const expectsPlan = ["plan_builder_complex", "amendment", "venue_recommendation"].includes(scenario.type);
      if (expectsPlan) {
        score = 0;
        notes = "Expected a plan but none was delivered. ";
      }
    }

    if (!notes) notes = "Plan structure looks reasonable";
    grades.plan_makes_sense = { score, notes };
    if (score < 100) issues.push(`Plan sense: ${notes.trim()}`);
  }

  // ── 2. Did what the user asked (15%) ──
  {
    let score = 100;
    let notes = "";

    if (scenario.type === "amendment") {
      // Must deliver plan_updates for each amendment turn
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
          // Find the plan_update envelope after this turn
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

    if (scenario.type === "plan_builder_complex") {
      // Should have at least one plan response
      if (!firstPlanEnvelope) {
        score = 0;
        notes = "Complex plan builder scenario but no plan delivered";
      }
    }

    if (scenario.type === "conflict") {
      if (scenario.expect_conflict === "must_use_venue_tool") {
        // "Add dinner at 6pm" — should use recommend_and_save_venue or search_venues
        const usedVenueTool = toolCalls.some(
          (tc) => tc.name === "search_venues" || tc.name === "recommend_and_save_venue"
        );
        if (!usedVenueTool) {
          score = 0;
          notes = "Should have used venue tool for dinner request but didn't";
        }
        // Check no "Custom dinner" or filler slots
        const fillerSlots = allSlots.filter(
          (s) =>
            !s.is_custom &&
            !s.event_id &&
            /dinner|lunch|brunch|drinks|coffee/i.test(s.name || "")
        );
        if (fillerSlots.length > 0) {
          score = 0;
          notes = `Created filler slot(s): ${fillerSlots.map((s) => s.name).join(", ")}`;
        }
      }
    }

    if (!notes) notes = "Followed instructions correctly";
    grades.did_what_asked = { score, notes };
    if (score < 100) issues.push(`Did what asked: ${notes.trim()}`);
  }

  // ── 3. Quick filters make sense (10%) ──
  {
    let score = 100;
    let notes = "";

    const lastResponse = envelopes[envelopes.length - 1];
    if (lastResponse) {
      const chips = lastResponse.chips || [];

      // Check chip count: 2-4 is ideal
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

      // After plan: should have approve, modify, add options
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

      // Check for redundancy: if scenario asks "comedy", chips shouldn't say "Comedy shows"
      if (scenario.type === "chip_quality" && scenario.expect_chips === "no_redundant") {
        const userText = scenario.turns[0].toLowerCase();
        const chipLabels = chips.map((c) => (c.label || "").toLowerCase());
        // Extract the main keyword from user request
        const keywords = ["comedy", "music", "sports", "art", "food", "outdoor"];
        for (const kw of keywords) {
          if (userText.includes(kw)) {
            const redundant = chipLabels.filter((l) => l.includes(kw));
            if (redundant.length > 0) {
              score = Math.min(score, 50);
              notes += `Redundant chip "${redundant[0]}" — user just asked for ${kw}. `;
            }
          }
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

  // ── 4. User satisfaction — needed changes? (15%) ──
  {
    let score = 100;
    let notes = "";

    if (scenario.type === "plan_builder_complex") {
      // Count user turns AFTER the first plan was delivered
      const firstPlanIdx = transcript.findIndex(
        (t) =>
          t.role === "assistant" &&
          t.envelope &&
          (t.envelope.type === "plan" || t.envelope.type === "plan_update")
      );

      if (firstPlanIdx === -1) {
        score = 0;
        notes = "No plan ever delivered";
      } else {
        // Count subsequent user messages that are corrections/changes
        const userTurnsAfterPlan = transcript
          .slice(firstPlanIdx + 1)
          .filter((t) => t.role === "user" && typeof t.content === "string");

        const correctionPhrases = [
          "actually", "instead", "change", "swap", "replace", "remove",
          "different", "start over", "no ", "nah", "not ", "don't like",
          "too ", "move", "earlier", "later", "add", "skip",
        ];

        const corrections = userTurnsAfterPlan.filter((t) => {
          const text = (t.content || "").toLowerCase();
          return correctionPhrases.some((p) => text.includes(p));
        });

        if (scenario.expect_satisfaction === "major_restructuring") {
          // These scenarios deliberately test restructuring — score based on how well Claude adapted
          if (corrections.length === 0) {
            score = 100;
            notes = "Plan needed no corrections (surprisingly good first attempt)";
          } else {
            // Check if the final plan addressed all concerns
            score = 75;
            notes = `${corrections.length} correction(s) needed — expected for this scenario type`;
          }
        } else if (scenario.expect_satisfaction === "amendments_after_plan") {
          // Amendments are expected, grade on whether they were applied correctly
          if (corrections.length <= 1) {
            score = 100;
            notes = "Minor amendments applied successfully";
          } else {
            score = 75;
            notes = `${corrections.length} rounds of changes needed`;
          }
        } else {
          if (corrections.length === 0) {
            score = 100;
            notes = "First plan was accepted";
          } else if (corrections.length === 1) {
            score = 75;
            notes = "One minor correction needed";
          } else if (corrections.length === 2) {
            score = 50;
            notes = "Multiple corrections needed";
          } else {
            score = 0;
            notes = `${corrections.length} corrections — plan missed the mark`;
          }
        }
      }
    } else if (scenario.type === "amendment") {
      // For amendment scenarios, satisfaction is about whether amendments were applied
      const updateCount = envelopes.filter((e) => e.type === "plan_update").length;
      if (updateCount > 0) {
        score = 100;
        notes = "Amendments applied";
      } else {
        score = 0;
        notes = "No amendments were applied";
      }
    } else {
      // Non-plan scenarios — check if response was helpful
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

  // ── 5. Used tools correctly (8%) ──
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
      // No tools used at all
      if (firstPlanIdx !== -1) {
        score = 0;
        notes = "Plan delivered without any tool calls";
      } else {
        // Chat only — might be ok for some scenarios
        const chatOnlyOk = ["conflict", "ambiguous"].includes(scenario.type);
        if (!chatOnlyOk && scenario.type !== "requirement_gathering") {
          score = 0;
          notes = "No tools called";
        } else {
          notes = "Chat-only response, no tools needed";
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

    // Check if venue tools were used for dining requests
    if (scenario.type === "venue_recommendation") {
      const usedVenueTool = toolCalls.some(
        (tc) => tc.name === "search_venues" || tc.name === "recommend_and_save_venue"
      );
      if (!usedVenueTool) {
        score = 0;
        notes = "Venue recommendation scenario but no venue tools used";
      }
    }

    grades.used_tools = { score, notes };
    if (score === 0 && notes) issues.push(`Tools: ${notes}`);
  }

  // ── 6. All events have real IDs (8%) ──
  {
    const nonCustomSlots = allSlots.filter((s) => !s.is_custom);
    const slotsWithIds = nonCustomSlots.filter((s) => s.event_id);
    const slotsWithoutIds = nonCustomSlots.filter((s) => !s.event_id);

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

    // ── 6b. Event times match DB (8%) ──
    {
      let timeScore = 100;
      let timeNotes = "";
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
  }

  // ── 7. Gathered requirements properly (8%) ──
  {
    let score = 100;
    let notes = "";

    if (scenario.type === "requirement_gathering") {
      const userTexts = scenario.turns.join(" ").toLowerCase();

      const hasWhen = /tonight|weekend|today|saturday|sunday|friday|tomorrow|this week|next week|morning|afternoon|evening/i.test(userTexts);
      const hasWhere = LOCATIONS.some((l) => userTexts.toLowerCase().includes(l.city.toLowerCase()));
      const hasGroupSize = /solo|alone|couple|two|group|friends|family|people|person|us|we|girlfriend|boyfriend|partner/i.test(userTexts);
      const hasVibe = /chill|active|romantic|fun|adventure|laid.?back|energetic|relaxed|fancy|casual|family|date|special/i.test(userTexts);
      const hasDuration = /full day|half day|evening|afternoon|morning|couple hours|all day|multi.?day|quick|whole day|3-4 hours/i.test(userTexts);

      const provided = [hasWhen, hasWhere, hasGroupSize, hasVibe, hasDuration].filter(Boolean).length;

      if (scenario.expect_no_questions) {
        // User provided everything — Claude should NOT ask questions, should just build
        const firstResponse = envelopes[0];
        if (firstResponse && firstResponse.type === "chat") {
          const asksQuestion = (firstResponse.text || "").includes("?");
          if (asksQuestion) {
            // Check if it's asking about something genuinely missing vs redundant
            const text = (firstResponse.text || "").toLowerCase();
            const asksRedundant =
              (hasWhen && /when|what day|what time/i.test(text)) ||
              (hasWhere && /where|which area|what city/i.test(text)) ||
              (hasGroupSize && /how many|group|who/i.test(text)) ||
              (hasVibe && /what kind|vibe|type of/i.test(text)) ||
              (hasDuration && /how long|duration/i.test(text));

            if (asksRedundant) {
              score = 0;
              notes = "Asked redundant questions when all info was provided";
            } else {
              score = 50;
              notes = "Asked follow-up questions but may have been unnecessary";
            }
          }
        }
      } else if (scenario.expect_questions_about) {
        // Should ask about specific missing info
        const firstResponse = envelopes[0];
        if (firstResponse && firstResponse.type === "chat") {
          const asksQuestion = (firstResponse.text || "").includes("?");
          if (!asksQuestion) {
            // Went straight to building without asking — check if it was reasonable
            const wentToPlan = firstResponse.type === "plan";
            if (wentToPlan && provided < 3) {
              score = 50;
              notes = `Built plan without asking about missing info (${scenario.expect_questions_about.join(", ")})`;
            }
          }
        } else if (firstResponse && (firstResponse.type === "plan" || firstResponse.type === "plan_update")) {
          if (provided < 3) {
            score = 50;
            notes = `Jumped to plan without gathering: ${scenario.expect_questions_about.join(", ")}`;
          }
        }
      }
    } else if (scenario.type === "plan_builder_complex" || scenario.type === "ambiguous") {
      // General requirement gathering check
      const userTexts = transcript
        .filter((t) => t.role === "user" && typeof t.content === "string")
        .map((t) => t.content || "")
        .join(" ")
        .toLowerCase();

      const hasWhen = /tonight|weekend|today|saturday|sunday|friday|tomorrow|this week|next week|morning|afternoon|evening/i.test(userTexts);
      const hasWhere = LOCATIONS.some((l) => userTexts.toLowerCase().includes(l.city.toLowerCase()));
      const provided = [hasWhen, hasWhere].filter(Boolean).length;

      // Check if Claude asked too many questions (>2 follow-ups before plan)
      const chatResponses = envelopes.filter((e) => e.type === "chat");
      const planResponse = envelopes.find((e) => e.type === "plan" || e.type === "plan_update");
      const chatBeforePlan = planResponse
        ? chatResponses.filter((c) => envelopes.indexOf(c) < envelopes.indexOf(planResponse))
        : chatResponses;

      if (chatBeforePlan.length > 2) {
        score = 50;
        notes = `Too many questions before building plan (${chatBeforePlan.length} chat responses)`;
      }
    }

    if (!notes) notes = "Requirements gathered appropriately";
    grades.gathered_requirements = { score, notes };
    if (score < 100) issues.push(`Requirements: ${notes}`);
  }

  // ── 8. Conflict detection (8%) ──
  {
    let score = 100;
    let notes = "";

    if (scenario.type === "conflict") {
      if (scenario.expect_conflict === "time_overlap") {
        const mentionedConflict = envelopes.some(
          (e) =>
            e.text &&
            (e.text.toLowerCase().includes("already") ||
              e.text.toLowerCase().includes("same time") ||
              e.text.toLowerCase().includes("overlap") ||
              e.text.toLowerCase().includes("conflict") ||
              e.text.toLowerCase().includes("replace") ||
              e.text.toLowerCase().includes("move"))
        );
        if (!mentionedConflict) {
          score = 0;
          notes = "Time conflict not detected or mentioned";
        } else {
          // Check if it offered solutions
          const offeredSolution = envelopes.some(
            (e) =>
              (e.chips && e.chips.length > 0) ||
              (e.text && /would you like|I can|how about|want me to/i.test(e.text))
          );
          if (!offeredSolution) {
            score = 50;
            notes = "Detected conflict but didn't offer solutions";
          } else {
            notes = "Conflict detected and solutions offered";
          }
        }
      } else if (scenario.expect_conflict === "geographic") {
        const mentionedDistance = envelopes.some(
          (e) =>
            e.text &&
            (e.text.toLowerCase().includes("far") ||
              e.text.toLowerCase().includes("distance") ||
              e.text.toLowerCase().includes("travel") ||
              e.text.toLowerCase().includes("tight") ||
              e.text.toLowerCase().includes("challenging") ||
              e.text.toLowerCase().includes("separate") ||
              e.text.toLowerCase().includes("different area") ||
              e.text.toLowerCase().includes("apart"))
        );
        if (!mentionedDistance) {
          score = 0;
          notes = "Geographic impossibility not flagged";
        } else {
          notes = "Geographic issue addressed";
        }
      } else if (scenario.expect_conflict === "no_results_honest") {
        const wasHonest = envelopes.some(
          (e) =>
            e.text &&
            (e.text.toLowerCase().includes("no ") ||
              e.text.toLowerCase().includes("couldn't find") ||
              e.text.toLowerCase().includes("don't have") ||
              e.text.toLowerCase().includes("not finding") ||
              e.text.toLowerCase().includes("unfortunately") ||
              e.text.toLowerCase().includes("no results") ||
              e.text.toLowerCase().includes("nothing") ||
              e.text.toLowerCase().includes("not available"))
        );
        if (!wasHonest && allSlots.length > 0) {
          // It built a plan anyway — check if slots have real IDs
          const fakeSlots = allSlots.filter((s) => !s.is_custom && !s.event_id);
          if (fakeSlots.length > 0) {
            score = 0;
            notes = "Made up results instead of being honest about no matches";
          } else {
            score = 100;
            notes = "Found alternative real results";
          }
        } else if (wasHonest) {
          notes = "Honestly reported no matching results";
        } else {
          score = 50;
          notes = "Unclear if response was honest about lack of results";
        }
      } else if (scenario.expect_conflict === "clarification_needed") {
        const askedForClarification = envelopes.some(
          (e) =>
            e.text &&
            (e.text.includes("?") ||
              e.text.toLowerCase().includes("which") ||
              e.text.toLowerCase().includes("could you") ||
              e.text.toLowerCase().includes("can you clarify") ||
              e.text.toLowerCase().includes("do you mean") ||
              e.text.toLowerCase().includes("referring to"))
        );
        if (!askedForClarification) {
          score = 0;
          notes = "Did not ask for clarification on vague reference";
        } else {
          notes = "Asked for clarification appropriately";
        }
      } else if (scenario.expect_conflict === "full_restart") {
        // Should have rebuilt the plan from scratch
        if (planEnvelopes.length < 2) {
          score = 50;
          notes = "Expected a rebuilt plan after cancel request";
        } else {
          notes = "Rebuilt plan after cancellation";
        }
      }
    } else {
      // Non-conflict scenarios — check if there are time overlaps that weren't flagged
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
    }

    if (!notes) notes = "No conflicts to report";
    grades.conflict_detection = { score, notes };
    if (score < 100) issues.push(`Conflict: ${notes}`);
  }

  // ── 9. Time ordering correct (5%) ──
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

  // ── 10. No hallucination (5%) ──
  {
    let score = 100;
    let notes = "";

    const nonCustomSlots = allSlots.filter((s) => !s.is_custom);

    // Slots created without tool calls
    if (nonCustomSlots.length > 0 && toolCalls.length === 0) {
      score = 0;
      notes = "Slots created without any tool calls (hallucinated)";
    }

    // Slots without event_id
    const hallucinated = nonCustomSlots.filter((s) => !s.event_id);
    if (hallucinated.length > 0) {
      score = 0;
      notes = `${hallucinated.length} slot(s) without event_id: ${hallucinated.map((s) => s.name).join(", ")}`;
    }

    // Check for generic filler names
    const fillerNames = /^(dinner|lunch|brunch|drinks|coffee|dessert|custom dinner|breakfast|snack)$/i;
    const fillers = nonCustomSlots.filter((s) => fillerNames.test((s.name || "").trim()));
    if (fillers.length > 0) {
      score = 0;
      notes = `Filler slot(s): ${fillers.map((s) => s.name).join(", ")}`;
    }

    // Check for "No events found" in tool results followed by plan with those events
    const noResultTools = transcript.filter(
      (t) =>
        t.role === "tool_result" &&
        t.result &&
        (t.result.includes("No events found") ||
          t.result.includes("No venues found"))
    );
    if (noResultTools.length > 0 && nonCustomSlots.length > 0) {
      // Check if there were ALSO successful tool results
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

  // ── 11. Response format valid (3%) ──
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
      // Also check slot emoji fields
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

  // ── Calculate weighted score ──

  const weights = {
    plan_makes_sense: 0.14,
    did_what_asked: 0.14,
    quick_filters: 0.08,
    user_satisfaction: 0.14,
    event_times_match_db: 0.08,
    used_tools: 0.07,
    real_event_ids: 0.07,
    gathered_requirements: 0.07,
    conflict_detection: 0.07,
    time_ordering: 0.05,
    no_hallucination: 0.05,
    format_valid: 0.04,
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

  // Build all 500 scenarios
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
    "quick_filters",
    "user_satisfaction",
    "used_tools",
    "real_event_ids",
    "gathered_requirements",
    "conflict_detection",
    "time_ordering",
    "no_hallucination",
    "format_valid",
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
    "plan_builder_complex",
    "amendment",
    "conflict",
    "ambiguous",
    "requirement_gathering",
    "venue_recommendation",
    "chip_quality",
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
      version: "v2",
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
