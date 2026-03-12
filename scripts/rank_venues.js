/**
 * rank_venues.js — Use Claude to assign a public interest rank (1-100) to each venue
 *
 * Usage:
 *   ANTHROPIC_API_KEY=sk-... SUPABASE_SERVICE_KEY=ey... node scripts/rank_venues.js
 *
 * Optional:
 *   STATE=MA             (default; only rank venues in this state)
 *   BATCH_SIZE=30        (venues per Claude call, default 30)
 *   CONCURRENCY=2        (parallel Claude calls, default 2)
 *   DRY_RUN=1            (preview without writing to Supabase)
 *   FORCE=1              (re-rank venues that already have a rank)
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

let ANTHROPIC_API_KEY, SUPABASE_SERVICE_KEY;

const STATE = process.env.STATE || "MA";
const BATCH_SIZE = parseInt(process.env.BATCH_SIZE || "30", 10);
const CONCURRENCY = parseInt(process.env.CONCURRENCY || "2", 10);
const DRY_RUN = process.env.DRY_RUN === "1";
const FORCE = process.env.FORCE === "1";

// ── Fetch venues from Supabase ──

async function fetchVenues() {
  let url = `${SUPABASE_URL}/rest/v1/venues?is_active=eq.true&select=id,name,city,state,category,website`;
  if (!FORCE) {
    url += "&public_interest_rank=is.null";
  }

  const allVenues = [];
  let offset = 0;
  const limit = 1000;

  while (true) {
    const res = await fetch(`${url}&offset=${offset}&limit=${limit}&order=name.asc`, {
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      },
    });

    if (!res.ok) {
      const body = await res.text();
      console.error(`Supabase fetch error: ${res.status} ${body.substring(0, 200)}`);
      break;
    }

    const venues = await res.json();
    allVenues.push(...venues);
    if (venues.length < limit) break;
    offset += limit;
  }

  return allVenues;
}

// ── Claude ranking ──

const RANKING_PROMPT = `You are ranking venues for a local event discovery app. For each venue, assign a public_interest_rank from 1 to 100 based on:

- **Popularity** (20%): How well-known is this venue? Major landmarks and famous spots score higher.
- **Event likelihood** (30%): How likely is this venue to host public events regularly? Concert halls, theaters, and event spaces score high. A random small shop scores low.
- **Cultural significance** (25%): Is this place historically important, iconic, or culturally meaningful to the area?
- **Uniqueness** (25%): Is this a one-of-a-kind destination, or is it generic/interchangeable?

Scoring guide:
- 90-100: Iconic, nationally known, constant events (e.g., Fenway Park, Museum of Fine Arts)
- 70-89: Well-known regionally, frequent events (e.g., House of Blues, Tanglewood)
- 50-69: Solid local venue, regular events (e.g., popular local brewery with live music)
- 30-49: Smaller/niche venue, occasional events (e.g., small gallery, neighborhood cafe)
- 10-29: Unlikely to host events, low public interest (e.g., small office park, generic business)
- 1-9: Not relevant for event discovery at all

Return ONLY a JSON array of objects with "id" and "rank" fields. No explanation, no markdown fences.

Example response:
[{"id":"abc-123","rank":85},{"id":"def-456","rank":42}]`;

async function rankBatch(venues) {
  const venueList = venues
    .map((v) => `- ID: ${v.id} | Name: ${v.name} | City: ${v.city}, ${v.state} | Category: ${v.category}`)
    .join("\n");

  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "x-api-key": ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
    },
    body: JSON.stringify({
      model: "claude-sonnet-4-20250514",
      max_tokens: 4096,
      messages: [
        {
          role: "user",
          content: `${RANKING_PROMPT}\n\nVenues to rank:\n${venueList}`,
        },
      ],
    }),
  });

  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Claude API error ${res.status}: ${body.substring(0, 200)}`);
  }

  const data = await res.json();
  const text = data.content?.[0]?.text || "";

  try {
    const rankings = JSON.parse(text);
    if (!Array.isArray(rankings)) throw new Error("Not an array");
    return rankings
      .filter((r) => r.id && typeof r.rank === "number" && r.rank >= 1 && r.rank <= 100)
      .map((r) => ({ id: r.id, rank: Math.round(r.rank) }));
  } catch (err) {
    console.error(`  Failed to parse Claude response: ${err.message}`);
    console.error(`  Response: ${text.substring(0, 300)}`);
    return [];
  }
}

// ── Write rankings to Supabase ──

async function writeRankings(rankings) {
  if (DRY_RUN) {
    console.log(`  [DRY RUN] Would update ${rankings.length} venues`);
    return { updated: rankings.length, errors: 0 };
  }

  let updated = 0;
  let errors = 0;

  for (const { id, rank } of rankings) {
    const res = await fetch(
      `${SUPABASE_URL}/rest/v1/venues?id=eq.${id}`,
      {
        method: "PATCH",
        headers: {
          apikey: SUPABASE_SERVICE_KEY,
          Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
          "Content-Type": "application/json",
          Prefer: "return=minimal",
        },
        body: JSON.stringify({ public_interest_rank: rank }),
      }
    );

    if (!res.ok) {
      errors++;
    } else {
      updated++;
    }
  }

  return { updated, errors };
}

// ── Helpers ──

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

// ── Main ──

async function main() {
  const secrets = await getSecrets(["ANTHROPIC_API_KEY"]);
  ANTHROPIC_API_KEY = secrets.ANTHROPIC_API_KEY;
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  console.log("=== Venue Public Interest Ranking ===\n");
  console.log(`State: ${STATE}`);
  console.log(`Batch size: ${BATCH_SIZE}, Concurrency: ${CONCURRENCY}`);
  if (DRY_RUN) console.log("MODE: DRY RUN");
  if (FORCE) console.log("MODE: FORCE (re-ranking all)");
  console.log();

  const venues = await fetchVenues();
  console.log(`Fetched ${venues.length} venues to rank\n`);

  if (venues.length === 0) {
    console.log("No venues to rank. Done.");
    return;
  }

  // Split into batches
  const batches = [];
  for (let i = 0; i < venues.length; i += BATCH_SIZE) {
    batches.push(venues.slice(i, i + BATCH_SIZE));
  }

  let totalUpdated = 0;
  let totalErrors = 0;
  let totalRanked = 0;

  // Process batches with concurrency
  for (let i = 0; i < batches.length; i += CONCURRENCY) {
    const chunk = batches.slice(i, i + CONCURRENCY);
    const batchNums = chunk.map((_, j) => i + j + 1);
    console.log(`Processing batch(es) ${batchNums.join(", ")} of ${batches.length}...`);

    const results = await Promise.all(
      chunk.map(async (batch, j) => {
        try {
          const rankings = await rankBatch(batch);
          console.log(`  Batch ${i + j + 1}: ${rankings.length}/${batch.length} ranked`);

          // Log some examples
          const examples = rankings.slice(0, 3).map((r) => {
            const v = batch.find((v) => v.id === r.id);
            return `${v?.name || r.id}: ${r.rank}`;
          });
          if (examples.length > 0) {
            console.log(`    Examples: ${examples.join(", ")}`);
          }

          const { updated, errors } = await writeRankings(rankings);
          return { ranked: rankings.length, updated, errors };
        } catch (err) {
          console.error(`  Batch ${i + j + 1} ERROR: ${err.message}`);
          return { ranked: 0, updated: 0, errors: batch.length };
        }
      })
    );

    for (const r of results) {
      totalRanked += r.ranked;
      totalUpdated += r.updated;
      totalErrors += r.errors;
    }

    // Rate limit
    if (i + CONCURRENCY < batches.length) {
      await sleep(1000);
    }
  }

  // Summary
  console.log("\n=== Ranking Summary ===");
  console.log(`  Total venues: ${venues.length}`);
  console.log(`  Ranked: ${totalRanked}`);
  console.log(`  Updated in DB: ${totalUpdated}`);
  console.log(`  Errors: ${totalErrors}`);
}

main().catch((err) => {
  console.error("Fatal error:", err);
  process.exit(1);
});
