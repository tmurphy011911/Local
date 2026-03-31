/**
 * Match event venue_names to existing venues and update events
 * with the canonical venue name so they get venue photos/descriptions.
 *
 * Usage:
 *   SUPABASE_SERVICE_KEY=ey... node scripts/match_event_venues.js
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

let SUPABASE_SERVICE_KEY;

// Aliases: event venue_name → canonical venue name
const VENUE_ALIASES = {
  // Middle East variations
  "middle east - corner/bakery": "Middle East Restaurant and Club",
  "middle east - zuzu": "Middle East Restaurant and Club",
  "middle east - upstairs": "Middle East Restaurant and Club",
  "middle east - downstairs": "Middle East Restaurant and Club",
  "middle east nightclub": "Middle East Restaurant and Club",
  "the middle east": "Middle East Restaurant and Club",
  // Fenway
  "fenway park": "Fenway Park",
  // TD Garden
  "td garden": "TD Garden",
  // ICA
  "ica boston": "Institute of Contemporary Art Boston",
  "ica": "Institute of Contemporary Art Boston",
  // Royale
  "royale boston": "Royale",
  "royale nightclub": "Royale",
  // Providence Performing Arts
  "providence performing arts": "Providence Performing Arts Center",
  "ppac": "Providence Performing Arts Center",
};

async function main() {
  const secrets = await getSecrets([]);
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  // Fetch all venue names
  const venueRes = await fetch(
    `${SUPABASE_URL}/rest/v1/venues?is_active=eq.true&select=name&limit=5000`,
    { headers: { apikey: SUPABASE_SERVICE_KEY, Authorization: `Bearer ${SUPABASE_SERVICE_KEY}` } }
  );
  const venues = await venueRes.json();
  const venueNames = new Set(venues.map((v) => v.name));
  const venueNamesLower = new Map(venues.map((v) => [v.name.toLowerCase(), v.name]));

  console.log(`Loaded ${venueNames.size} venues\n`);

  // Fetch events with unmatched venue names
  let offset = 0;
  let totalMatched = 0;
  let totalUnmatched = 0;
  const unmatchedCounts = {};

  while (true) {
    const res = await fetch(
      `${SUPABASE_URL}/rest/v1/events?is_active=eq.true&start_at=gte.2026-03-31T00:00:00Z&select=id,venue_name&limit=1000&offset=${offset}`,
      { headers: { apikey: SUPABASE_SERVICE_KEY, Authorization: `Bearer ${SUPABASE_SERVICE_KEY}` } }
    );
    const events = await res.json();
    if (events.length === 0) break;

    const updates = [];

    for (const event of events) {
      const vn = (event.venue_name || "").trim();
      if (!vn || venueNames.has(vn)) continue;

      // Try exact alias
      const aliasKey = vn.toLowerCase();
      if (VENUE_ALIASES[aliasKey]) {
        updates.push({ id: event.id, newName: VENUE_ALIASES[aliasKey] });
        continue;
      }

      // Try case-insensitive match
      if (venueNamesLower.has(aliasKey)) {
        updates.push({ id: event.id, newName: venueNamesLower.get(aliasKey) });
        continue;
      }

      // Try fuzzy: strip "The ", trailing location suffixes
      const stripped = aliasKey
        .replace(/^the\s+/, "")
        .replace(/\s*[-–]\s.*$/, "") // "Middle East - Corner" → "Middle East"
        .replace(/\s*\(.*\)$/, "")   // "Venue (Downtown)" → "Venue"
        .trim();

      let matched = false;
      for (const [lowerName, canonName] of venueNamesLower.entries()) {
        const strippedCanon = lowerName
          .replace(/^the\s+/, "")
          .replace(/\s*[-–]\s.*$/, "")
          .replace(/\s*\(.*\)$/, "")
          .trim();

        if (stripped === strippedCanon || lowerName.includes(stripped) || stripped.includes(strippedCanon)) {
          updates.push({ id: event.id, newName: canonName });
          matched = true;
          break;
        }
      }

      if (!matched) {
        unmatchedCounts[vn] = (unmatchedCounts[vn] || 0) + 1;
        totalUnmatched++;
      }
    }

    // Batch update matched events
    for (const update of updates) {
      await fetch(
        `${SUPABASE_URL}/rest/v1/events?id=eq.${update.id}`,
        {
          method: "PATCH",
          headers: {
            apikey: SUPABASE_SERVICE_KEY,
            Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
            "Content-Type": "application/json",
            Prefer: "return=minimal",
          },
          body: JSON.stringify({ venue_name: update.newName }),
        }
      );
    }

    if (updates.length > 0) {
      console.log(`  Batch ${offset / 1000 + 1}: matched ${updates.length} events`);
    }
    totalMatched += updates.length;
    offset += 1000;
  }

  console.log(`\nTotal matched: ${totalMatched}`);
  console.log(`Total unmatched: ${totalUnmatched}`);

  if (Object.keys(unmatchedCounts).length > 0) {
    console.log(`\nTop unmatched venue names (consider adding to VENUE_ALIASES):`);
    const sorted = Object.entries(unmatchedCounts).sort((a, b) => b[1] - a[1]);
    for (const [name, count] of sorted.slice(0, 30)) {
      console.log(`  ${count.toString().padStart(4)}  ${name}`);
    }
  }
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
