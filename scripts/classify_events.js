/**
 * classify_events.js — AI-powered event reclassification and tagging
 *
 * Uses the Anthropic Message Batches API for scalable, async classification.
 * Pulls all active events from Supabase, submits them to Claude as a batch,
 * polls for completion, then writes corrections back.
 *
 * Only classifies events that haven't been classified yet (no sub_subcategory
 * or tags), unless FORCE=1 is set.
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
 *   DRY_RUN=1          — preview batch submission without sending
 *   CITY=boston         — city to classify (default: boston)
 *   BATCH_SIZE=50      — events per Claude request within the batch (default: 50)
 *   FORCE=1            — re-classify already-classified events
 *   POLL_INTERVAL=60   — seconds between status checks (default: 60)
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");
const MODEL = "claude-sonnet-4-6";
const API_BASE = "https://api.anthropic.com/v1";

let ANTHROPIC_API_KEY, SUPABASE_SERVICE_KEY;
const CITY = process.env.CITY || "boston";
const BATCH_SIZE = parseInt(process.env.BATCH_SIZE || "50", 10);
const DRY_RUN = process.env.DRY_RUN === "1";
const FORCE = process.env.FORCE === "1";
const POLL_INTERVAL = parseInt(process.env.POLL_INTERVAL || "60", 10) * 1000;

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

// ── Prompt builder ──

function buildPrompt(eventsForPrompt) {
  return `You are an expert event classifier for a local event discovery app in the Boston metro area.

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
}

// ── Main ──

async function main() {
  const secrets = await getSecrets(["ANTHROPIC_API_KEY"]);
  ANTHROPIC_API_KEY = secrets.ANTHROPIC_API_KEY;
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  if (!REVERT_MODE && !ANTHROPIC_API_KEY) { console.error("Missing ANTHROPIC_API_KEY"); process.exit(1); }
  if (!SUPABASE_SERVICE_KEY) { console.error("Missing SUPABASE_SERVICE_KEY"); process.exit(1); }

  if (REVERT_MODE) return await revertClassifications();

  console.log(`Event Classifier (Batch API) — city: ${CITY}, batch size: ${BATCH_SIZE}`);
  if (DRY_RUN) console.log("DRY RUN — no API calls\n");
  if (FORCE) console.log("FORCE — re-classifying all events\n");

  // 1. Fetch events to classify
  const events = await fetchAllEvents();
  console.log(`Fetched ${events.length} future events`);

  // 2. Filter to unclassified events (unless FORCE)
  const toClassify = FORCE
    ? events
    : events.filter(e => !e.sub_subcategory && (!e.tags || e.tags.length === 0));
  console.log(`${toClassify.length} events need classification${FORCE ? " (forced)" : ""}\n`);
  if (toClassify.length === 0) return;

  // 3. Build batch requests — one per chunk of BATCH_SIZE events
  const chunks = [];
  for (let i = 0; i < toClassify.length; i += BATCH_SIZE) {
    chunks.push(toClassify.slice(i, i + BATCH_SIZE));
  }
  console.log(`Submitting ${chunks.length} requests in one batch\n`);

  const batchRequests = chunks.map((chunk, i) => ({
    custom_id: `chunk-${i}`,
    params: {
      model: MODEL,
      max_tokens: 8192,
      messages: [{
        role: "user",
        content: buildPrompt(chunk.map(e => ({
          id: e.id,
          title: e.title,
          description: (e.description || "").slice(0, 200),
          current_category: e.category,
          current_subcategory: e.subcategory,
          venue_name: e.venue_name,
          venue_city: e.venue_city,
          is_free: e.is_free,
        }))),
      }],
    },
  }));

  if (DRY_RUN) {
    console.log(`Would submit batch with ${batchRequests.length} requests (${toClassify.length} events)`);
    console.log("DRY RUN — nothing sent");
    return;
  }

  // 4. Submit the batch
  const batchId = await submitBatch(batchRequests);
  console.log(`Batch submitted: ${batchId}`);

  // 5. Poll until complete
  const batchResult = await pollBatch(batchId);
  console.log(`\nBatch complete — succeeded: ${batchResult.request_counts.succeeded}, errored: ${batchResult.request_counts.errored}, expired: ${batchResult.request_counts.expired}`);

  // 6. Stream results and write to DB
  await processResults(batchId, chunks, events);
}

// ── Batch API helpers ──

async function submitBatch(requests) {
  const res = await fetch(`${API_BASE}/messages/batches`, {
    method: "POST",
    headers: {
      "x-api-key": ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
      "content-type": "application/json",
    },
    body: JSON.stringify({ requests }),
  });

  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Batch creation failed (${res.status}): ${body}`);
  }

  const data = await res.json();
  return data.id;
}

async function pollBatch(batchId) {
  let polls = 0;
  while (true) {
    await new Promise(r => setTimeout(r, POLL_INTERVAL));
    polls++;

    const res = await fetch(`${API_BASE}/messages/batches/${batchId}`, {
      headers: {
        "x-api-key": ANTHROPIC_API_KEY,
        "anthropic-version": "2023-06-01",
      },
    });

    if (!res.ok) {
      console.error(`  Poll error (${res.status}), retrying...`);
      continue;
    }

    const data = await res.json();
    const counts = data.request_counts;
    console.log(
      `  [${polls}] status: ${data.processing_status} — ` +
      `processing: ${counts.processing}, succeeded: ${counts.succeeded}, ` +
      `errored: ${counts.errored}, expired: ${counts.expired}`
    );

    if (data.processing_status === "ended") return data;
  }
}

async function processResults(batchId, chunks, allEvents) {
  const res = await fetch(`${API_BASE}/messages/batches/${batchId}/results`, {
    headers: {
      "x-api-key": ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
    },
  });

  if (!res.ok) {
    throw new Error(`Failed to fetch results (${res.status}): ${await res.text()}`);
  }

  const text = await res.text();
  const lines = text.split("\n").filter(Boolean);

  let totalChanged = 0;
  let totalTagged = 0;
  let totalErrors = 0;

  for (const line of lines) {
    const result = JSON.parse(line);
    const chunkIndex = parseInt(result.custom_id.replace("chunk-", ""), 10);
    const chunk = chunks[chunkIndex];

    if (result.result.type !== "succeeded") {
      totalErrors++;
      console.error(`  ${result.custom_id} failed: ${result.result.type}`);
      if (result.result.error) console.error(`    ${result.result.error.message}`);
      continue;
    }

    // Parse Claude's response
    const responseText = result.result.message.content[0].text.trim();
    let jsonStr = responseText;
    const codeBlockMatch = responseText.match(/```(?:json)?\s*([\s\S]*?)```/);
    if (codeBlockMatch) jsonStr = codeBlockMatch[1].trim();

    let classifications;
    try {
      classifications = JSON.parse(jsonStr);
    } catch (err) {
      totalErrors++;
      console.error(`  ${result.custom_id} — JSON parse error: ${err.message}`);
      continue;
    }

    // Validate and collect updates
    const updates = [];
    let batchChanges = 0;

    for (const c of classifications) {
      const validated = {
        id: c.id,
        category: VALID_PARENT_CATEGORIES.includes(c.category) ? c.category : null,
        subcategory: c.subcategory && VALID_SUBCATEGORIES.includes(c.subcategory) ? c.subcategory : null,
        sub_subcategory: c.sub_subcategory && VALID_SUB_SUBCATEGORIES.includes(c.sub_subcategory) ? c.sub_subcategory : null,
        team_name: c.team_name || null,
        tags: Array.isArray(c.tags) ? c.tags.filter(t => VALID_TAGS.includes(t)).slice(0, 5) : [],
      };

      if (!validated.category || !validated.subcategory) continue;

      const original = chunk.find(e => e.id === validated.id);
      if (!original) continue;

      const changed = original.category !== validated.category ||
                      original.subcategory !== validated.subcategory;

      if (changed || validated.tags.length > 0 || validated.sub_subcategory) {
        updates.push(validated);
        if (changed) batchChanges++;
      }
    }

    if (updates.length > 0) {
      await writeUpdates(updates, chunk);
    }

    totalChanged += batchChanges;
    totalTagged += updates.length;
    console.log(`  ${result.custom_id}: ${batchChanges} recategorized, ${updates.length} tagged`);
  }

  console.log(`\n========================================`);
  console.log(`Done. Recategorized: ${totalChanged}, Tagged: ${totalTagged}, Errors: ${totalErrors}`);
}

// ── Fetch all future events ──

async function fetchAllEvents() {
  const allEvents = [];
  const pageSize = 1000;
  let offset = 0;

  while (true) {
    const now = new Date().toISOString();
    let selectCols = "id,title,description,category,subcategory,sub_subcategory,tags,venue_name,venue_city,is_free,source,original_category,original_subcategory";
    let url = `${SUPABASE_URL}/rest/v1/events?city=eq.${CITY}&start_at=gte.${now}&select=${selectCols}&order=start_at.asc&limit=${pageSize}&offset=${offset}`;

    let res = await fetch(url, {
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      },
    });

    // If new columns don't exist yet, retry without them
    if (!res.ok) {
      const errText = await res.text();
      if (errText.includes("original_category") || errText.includes("sub_subcategory") || errText.includes("tags")) {
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
