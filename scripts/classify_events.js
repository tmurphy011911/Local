/**
 * classify_events.js — AI-powered event reclassification and tagging
 *
 * Pulls all active events from Supabase, sends them to Claude in batches
 * for parent category (upcoming/always_on), subcategory, sub-subcategory,
 * and rich tagging, then writes corrections back.
 *
 * The original category/subcategory are preserved in
 * `original_category` and `original_subcategory` columns so you can revert.
 *
 * Usage:
 *   node scripts/classify_events.js
 *
 * Revert (undo all AI classifications):
 *   node scripts/classify_events.js --revert
 *
 * Optional:
 *   DRY_RUN=1          — preview changes without writing to DB
 *   CITY=boston         — city to classify (default: boston)
 *   BATCH_SIZE=50       — events per Claude call (default: 50)
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");
const MODEL = "claude-sonnet-4-20250514";

let ANTHROPIC_API_KEY, SUPABASE_SERVICE_KEY;
const CITY = process.env.CITY || "boston";
const BATCH_SIZE = parseInt(process.env.BATCH_SIZE || "50", 10);
const DRY_RUN = process.env.DRY_RUN === "1";

const REVERT_MODE = process.argv.includes("--revert");

// ── Valid categories and tags ──

const VALID_PARENT_CATEGORIES = ["upcoming", "always_on"];

const VALID_SUBCATEGORIES = [
  "music", "arts", "theatre", "food", "comedy", "sports",
  "museums", "tours", "libraries", "outdoors", "for-kids", "other",
  "no-public-interest",
];

const VALID_SUB_SUBCATEGORIES = [
  // music
  "rock", "pop", "hip-hop", "r&b", "jazz", "blues", "country", "folk",
  "classical", "electronic", "latin", "reggae", "alternative", "metal", "indie",
  "singer-songwriter", "cover-band", "tribute", "orchestra", "choir", "world-music",
  // arts
  "gallery", "visual-arts", "photography", "sculpture", "street-art", "craft",
  "spoken-word", "literary", "film", "dance", "opera",
  // theatre
  "musical", "drama", "improv", "sketch", "puppet-show", "dinner-theatre",
  // food
  "farmers-market", "food-tour", "tasting", "cooking-class", "pop-up",
  "brunch", "dinner", "beer", "wine", "cocktail", "coffee",
  // comedy
  "standup", "improv-comedy", "open-mic", "roast", "variety-show", "magic",
  // sports
  "basketball", "hockey", "baseball", "football", "soccer", "tennis", "golf",
  "wrestling", "boxing", "mma", "college-sports", "minor-league", "running",
  "fitness", "volleyball", "lacrosse", "rugby", "esports",
  // museums
  "art-museum", "science-museum", "history-museum", "exhibit", "planetarium",
  // tours
  "walking-tour", "boat-tour", "ghost-tour", "food-tour", "brewery-tour",
  "historical-tour", "architecture-tour",
  // libraries
  "book-club", "reading", "author-event", "workshop", "study-group",
  // outdoors
  "hiking", "cycling", "kayaking", "fishing", "camping", "climbing",
  "sailing", "skiing", "beach", "nature-walk", "bird-watching",
  "cleanup", "gardening",
  // for-kids
  "storytime", "craft-kids", "science-kids", "music-kids", "theatre-kids",
  "outdoor-play", "sports-kids", "camp",
  // other
  "workshop", "lecture", "networking", "community", "wellness",
  "charity", "religious", "holiday", "market", "fair", "convention",
  "meetup", "open-house", "volunteer", "fundraiser",
];

const VALID_TAGS = [
  // audience
  "family-friendly", "kids", "adults-only", "date-night", "solo-friendly",
  "group-activity", "seniors", "teens", "21+",
  // vibe
  "chill", "high-energy", "intimate", "epic", "quirky", "romantic",
  "educational", "competitive", "nostalgic", "spooky",
  // format
  "recurring", "one-time", "outdoor", "indoor", "virtual", "livestream",
  "free", "cheap", "splurge",
  // interest
  "live-music", "local-artist", "touring-act", "pro-sports", "college-sports",
  "film-screening", "art-exhibit", "craft-beer", "wine", "foodie",
  "literary", "history", "science", "wellness", "fitness",
  "comedy-show", "open-mic", "trivia", "drag-show", "dance-party",
  "theatre", "musical", "opera", "classical-music",
  "walking-tour", "food-tour", "boat-tour", "ghost-tour",
  "farmers-market", "holiday-event", "festival",
  "community-event", "fundraiser", "networking",
  "boston-essential", "hidden-gem",
];

// ── Main ──

async function main() {
  const secrets = await getSecrets(["ANTHROPIC_API_KEY"]);
  ANTHROPIC_API_KEY = secrets.ANTHROPIC_API_KEY;
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  if (!REVERT_MODE && !ANTHROPIC_API_KEY) { console.error("Missing ANTHROPIC_API_KEY"); process.exit(1); }
  if (!SUPABASE_SERVICE_KEY) { console.error("Missing SUPABASE_SERVICE_KEY"); process.exit(1); }

  if (REVERT_MODE) return await revertClassifications();

  console.log(`Event Classifier — city: ${CITY}, batch size: ${BATCH_SIZE}`);
  if (DRY_RUN) console.log("DRY RUN — no database writes\n");

  // 1. Fetch all future events
  const events = await fetchAllEvents();
  console.log(`Fetched ${events.length} future events\n`);
  if (events.length === 0) return;

  // 2. Process in batches
  let totalChanged = 0;
  let totalTagged = 0;
  let totalErrors = 0;

  for (let i = 0; i < events.length; i += BATCH_SIZE) {
    const batch = events.slice(i, i + BATCH_SIZE);
    const batchNum = Math.floor(i / BATCH_SIZE) + 1;
    const totalBatches = Math.ceil(events.length / BATCH_SIZE);
    console.log(`\n── Batch ${batchNum}/${totalBatches} (${batch.length} events) ──`);

    try {
      const classifications = await classifyBatch(batch);

      let batchChanges = 0;
      const updates = [];

      for (const c of classifications) {
        const original = batch.find(e => e.id === c.id);
        if (!original) continue;

        const changed = original.category !== c.category ||
                        original.subcategory !== c.subcategory;
        const hasTags = c.tags && c.tags.length > 0;

        if (changed || hasTags || c.sub_subcategory) {
          updates.push({
            id: c.id,
            category: c.category,
            subcategory: c.subcategory,
            sub_subcategory: c.sub_subcategory,
            tags: c.tags || [],
          });

          if (changed) {
            batchChanges++;
            console.log(
              `  "${original.title}" @ ${original.venue_name}` +
              `\n      ${original.category}/${original.subcategory || "null"} → ${c.category}/${c.subcategory || "null"}` +
              (c.sub_subcategory ? ` / ${c.sub_subcategory}` : "") +
              (hasTags ? `\n      tags: [${c.tags.join(", ")}]` : "")
            );
          }
        }
      }

      if (!DRY_RUN && updates.length > 0) {
        await writeUpdates(updates, batch);
      }

      totalChanged += batchChanges;
      totalTagged += updates.length;
      console.log(`  Batch: ${batchChanges} recategorized, ${updates.length} tagged`);

    } catch (err) {
      totalErrors++;
      console.error(`  Batch ${batchNum} failed:`, err.message);
    }

    // Small delay between batches to avoid rate limits
    if (i + BATCH_SIZE < events.length) {
      await new Promise(r => setTimeout(r, 1000));
    }
  }

  console.log(`\n========================================`);
  console.log(`Done. Recategorized: ${totalChanged}, Tagged: ${totalTagged}, Errors: ${totalErrors}`);
  if (DRY_RUN) console.log("(DRY RUN — nothing was written)");
}

// ── Fetch all future events ──

async function fetchAllEvents() {
  const allEvents = [];
  const pageSize = 1000;
  let offset = 0;

  while (true) {
    const now = new Date().toISOString();
    let selectCols = "id,title,description,category,subcategory,venue_name,venue_city,is_free,source,original_category,original_subcategory";
    let url = `${SUPABASE_URL}/rest/v1/events?city=eq.${CITY}&start_at=gte.${now}&select=${selectCols}&order=start_at.asc&limit=${pageSize}&offset=${offset}`;

    let res = await fetch(url, {
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      },
    });

    // If original_* columns don't exist yet, retry without them
    if (!res.ok) {
      const errText = await res.text();
      if (errText.includes("original_category")) {
        selectCols = "id,title,description,category,subcategory,venue_name,venue_city,is_free,source";
        url = `${SUPABASE_URL}/rest/v1/events?city=eq.${CITY}&start_at=gte.${now}&select=${selectCols}&order=start_at.asc&limit=${pageSize}&offset=${offset}`;
        res = await fetch(url, {
          headers: {
            apikey: SUPABASE_SERVICE_KEY,
            Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
          },
        });
        if (!res.ok) throw new Error(`Fetch failed: ${res.status} ${await res.text()}`);
      } else {
        throw new Error(`Fetch failed: ${res.status} ${errText}`);
      }
    }

    const rows = await res.json();
    allEvents.push(...rows);

    if (rows.length < pageSize) break;
    offset += pageSize;
  }

  return allEvents;
}

// ── Classify a batch with Claude ──

async function classifyBatch(events) {
  const eventsForPrompt = events.map(e => ({
    id: e.id,
    title: e.title,
    description: (e.description || "").slice(0, 200),
    current_category: e.category,
    current_subcategory: e.subcategory,
    venue_name: e.venue_name,
    venue_city: e.venue_city,
    is_free: e.is_free,
  }));

  const prompt = `You are an expert event classifier for a local event discovery app in the Boston metro area.

For each event below, determine:
1. The correct **category** (parent type — one of: ${VALID_PARENT_CATEGORIES.join(", ")})
2. The correct **subcategory** (one of: ${VALID_SUBCATEGORIES.join(", ")})
3. An optional **sub_subcategory** for deeper personalization (one of: ${VALID_SUB_SUBCATEGORIES.join(", ")}, or null)
4. Up to 5 **tags** from this list: ${VALID_TAGS.join(", ")}

CATEGORY RULES (this is the most important classification):
- **upcoming**: Events with a specific scheduled date that customers would NOT expect to see repeating regularly. Concerts, one-off shows, specific game days, special exhibits, festivals, galas, etc.
- **always_on**: Events that run multiple times per day, daily, weekly, or monthly. Things customers can rely on being available regularly — tours, book clubs, weekly cleanups, volunteer shifts, recurring trivia nights, farmers markets, museum general admission, recurring fitness classes, weekly open mics, etc.

SUBCATEGORY RULES:
- "music" — concerts, live music, DJ sets, music festivals
- "arts" — galleries, visual arts, photography, film screenings, dance performances
- "theatre" — plays, musicals, improv shows, drama
- "food" — food tours, tastings, cooking classes, farmers markets, pop-ups, dining events
- "comedy" — standup, comedy shows, roasts
- "sports" — games, matches, races, fitness events
- "museums" — museum visits, exhibits, science centers, planetariums
- "tours" — walking tours, boat tours, ghost tours, historical tours
- "libraries" — book clubs, author events, library programs, readings
- "outdoors" — hiking, cycling, kayaking, beach events, nature walks, cleanups, gardening
- "for-kids" — storytime, kids camps, children's theatre, kids science, outdoor play
- "other" — workshops, lectures, networking, community events, wellness, charity, religious, markets
- "no-public-interest" — events that no member of the general public would be excited to discover and attend. Examples: private country club events, varsity/JV sports practices, internal corporate meetings, school committee meetings, private member-only gatherings, government abutters meetings, HOA meetings, staff trainings, etc. If a random person browsing the app would not want to attend, classify it here.

IMPORTANT classification rules:
- Film screenings and movie showings are "arts" with sub_subcategory "film"
- A venue's default classification is often WRONG. Classify by the EVENT content, not the venue.
- ANY event with "vs" or "vs." in the title is almost certainly "sports" — games, matches, competitions. Never classify these as music/arts/etc.
- Pro Boston sports teams (Celtics, Bruins, Red Sox, Patriots, Revolution) are ALWAYS subcategory "sports". Bruins = hockey, Celtics = basketball, Red Sox = baseball, Patriots = football, Revolution = soccer.
- Pro Boston sports (Celtics, Bruins, Red Sox, Patriots, Revolution) should get "pro-sports" tag
- Free events should get the "free" tag
- Events good for families should get "family-friendly"
- Recurring events should get the "recurring" tag — this is KEY for always_on classification
- Use "boston-essential" sparingly for truly iconic Boston experiences
- Use "hidden-gem" for unique, lesser-known events worth discovering
- Kids-focused events should be subcategory "for-kids" even if they could fit another category

Respond with a JSON array. Each element must have:
- "id": the event ID (string)
- "category": "upcoming" or "always_on" (string)
- "subcategory": one of the valid subcategories (string)
- "sub_subcategory": deeper classification or null (string or null)
- "team_name": for sports events, the team name exactly as commonly known (e.g. "Boston Bruins", "Northeastern Huskies", "Worcester Red Sox"). null for non-sports events or sports events not tied to a specific team (string or null)
- "tags": array of tag strings (max 5)

ONLY output the JSON array, no other text.

Events to classify:
${JSON.stringify(eventsForPrompt, null, 2)}`;

  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "x-api-key": ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
      "content-type": "application/json",
    },
    body: JSON.stringify({
      model: MODEL,
      max_tokens: 8192,
      messages: [{ role: "user", content: prompt }],
    }),
  });

  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Claude API error ${res.status}: ${body}`);
  }

  const data = await res.json();
  const text = data.content[0].text.trim();

  // Extract JSON from response (handle markdown code blocks)
  let jsonStr = text;
  const codeBlockMatch = text.match(/```(?:json)?\s*([\s\S]*?)```/);
  if (codeBlockMatch) jsonStr = codeBlockMatch[1].trim();

  try {
    const results = JSON.parse(jsonStr);
    // Validate
    return results.map(r => ({
      id: r.id,
      category: VALID_PARENT_CATEGORIES.includes(r.category) ? r.category : null,
      subcategory: r.subcategory && VALID_SUBCATEGORIES.includes(r.subcategory) ? r.subcategory : null,
      sub_subcategory: r.sub_subcategory && VALID_SUB_SUBCATEGORIES.includes(r.sub_subcategory) ? r.sub_subcategory : null,
      team_name: r.team_name || null,
      tags: Array.isArray(r.tags) ? r.tags.filter(t => VALID_TAGS.includes(t)).slice(0, 5) : [],
    })).filter(r => r.category !== null && r.subcategory !== null);
  } catch (err) {
    throw new Error(`Failed to parse Claude response: ${err.message}\nResponse: ${text.slice(0, 500)}`);
  }
}

// ── Write updates back to Supabase ──

async function writeUpdates(updates, originalEvents) {
  const concurrency = 10;

  for (let i = 0; i < updates.length; i += concurrency) {
    const batch = updates.slice(i, i + concurrency);
    await Promise.all(batch.map(async (u) => {
      const original = originalEvents.find(e => e.id === u.id);
      const url = `${SUPABASE_URL}/rest/v1/events?id=eq.${u.id}`;

      const body = {
        category: u.category,
        subcategory: u.subcategory,
      };

      if (u.sub_subcategory) {
        body.sub_subcategory = u.sub_subcategory;
      }

      if (u.team_name) {
        body.team_name = u.team_name;
      }

      // Preserve original values for revert — only set if not already saved
      if (original && !original.original_category) {
        body.original_category = original.category;
        body.original_subcategory = original.subcategory || null;
      }

      if (u.tags && u.tags.length > 0) {
        body.tags = u.tags;
      }

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

      if (!res.ok) {
        const errBody = await res.text();
        // If new columns don't exist yet, retry with just category/subcategory
        if (errBody.includes("original_category") || errBody.includes("tags") || errBody.includes("sub_subcategory")) {
          const fallback = {
            category: u.category,
            subcategory: u.subcategory,
          };
          const retry = await fetch(url, {
            method: "PATCH",
            headers: {
              apikey: SUPABASE_SERVICE_KEY,
              Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
              "Content-Type": "application/json",
              Prefer: "return=minimal",
            },
            body: JSON.stringify(fallback),
          });
          if (!retry.ok) {
            console.error(`  Failed to update ${u.id}: ${await retry.text()}`);
          }
        } else {
          console.error(`  Failed to update ${u.id}: ${errBody}`);
        }
      }
    }));
  }
}

// ── Revert all AI classifications ──

async function revertClassifications() {
  console.log(`Reverting AI classifications for city: ${CITY}`);
  if (DRY_RUN) console.log("DRY RUN — no database writes\n");

  const allClassified = [];
  const pageSize = 1000;
  let offset = 0;

  while (true) {
    const url = `${SUPABASE_URL}/rest/v1/events?city=eq.${CITY}&original_category=not.is.null&select=id,title,category,subcategory,original_category,original_subcategory&order=id.asc&limit=${pageSize}&offset=${offset}`;
    const res = await fetch(url, {
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      },
    });

    if (!res.ok) {
      const body = await res.text();
      if (body.includes("original_category")) {
        console.log("No original_category column found — nothing to revert.");
        return;
      }
      throw new Error(`Fetch failed: ${res.status} ${body}`);
    }

    const rows = await res.json();
    allClassified.push(...rows);
    if (rows.length < pageSize) break;
    offset += pageSize;
  }

  console.log(`Found ${allClassified.length} AI-classified events to revert\n`);
  if (allClassified.length === 0) return;

  let reverted = 0;
  const concurrency = 10;

  for (let i = 0; i < allClassified.length; i += concurrency) {
    const batch = allClassified.slice(i, i + concurrency);
    await Promise.all(batch.map(async (ev) => {
      if (DRY_RUN) {
        console.log(`  Would revert "${ev.title}": ${ev.category}/${ev.subcategory} → ${ev.original_category}/${ev.original_subcategory}`);
        reverted++;
        return;
      }

      const url = `${SUPABASE_URL}/rest/v1/events?id=eq.${ev.id}`;
      const res = await fetch(url, {
        method: "PATCH",
        headers: {
          apikey: SUPABASE_SERVICE_KEY,
          Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
          "Content-Type": "application/json",
          Prefer: "return=minimal",
        },
        body: JSON.stringify({
          category: ev.original_category,
          subcategory: ev.original_subcategory,
          sub_subcategory: null,
          original_category: null,
          original_subcategory: null,
          tags: null,
        }),
      });

      if (res.ok) {
        reverted++;
      } else {
        console.error(`  Failed to revert ${ev.id}: ${await res.text()}`);
      }
    }));
  }

  console.log(`\nReverted ${reverted} events back to original categories.`);
}

main().catch(err => { console.error(err); process.exit(1); });
