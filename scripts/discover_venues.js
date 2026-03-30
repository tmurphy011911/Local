/**
 * discover_venues.js — Use Claude to discover real venues in a metro area, upsert to Supabase
 *
 * Usage:
 *   ANTHROPIC_API_KEY=sk-... SUPABASE_SERVICE_KEY=ey... node scripts/discover_venues.js
 *
 * Optional:
 *   METRO=boston  (default)
 *   CENTER_LAT=42.3601  CENTER_LNG=-71.0589  RADIUS_MILES=75
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

const MODEL = "claude-sonnet-4-20250514";

let ANTHROPIC_API_KEY, SUPABASE_SERVICE_KEY;

const METRO = process.env.METRO || "boston";
const CENTER_LAT = parseFloat(process.env.CENTER_LAT || "42.3601");
const CENTER_LNG = parseFloat(process.env.CENTER_LNG || "-71.0589");
const RADIUS_MILES = parseInt(process.env.RADIUS_MILES || "75", 10);

// ── Venue categories to discover ──

const CATEGORIES = [
  {
    category: "music_venue",
    prompt: `List all notable music venues, concert halls, and live music bars within ${RADIUS_MILES} miles of ${CENTER_LAT},${CENTER_LNG}. Include large arenas (TD Garden, MGM Music Hall), mid-size clubs (Paradise Rock Club, The Sinclair, Brighton Music Hall, Roadrunner), small bars with live music (Wally's Cafe, The Plough & Stars, Atwood's Tavern, ONCE Somerville), and venues in surrounding cities like Providence RI (Columbus Theatre, The Strand), Worcester MA (The Palladium, Mechanics Hall), Portsmouth NH (The Music Hall), Lowell (Lowell Memorial Auditorium), Salem, Cape Cod venues, etc. Aim for 25-40 venues.`,
  },
  {
    category: "theater",
    prompt: `List all theaters and performing arts centers within ${RADIUS_MILES} miles of ${CENTER_LAT},${CENTER_LNG}. Include major theaters (Huntington Theatre, American Repertory Theater, Emerson Colonial, Shubert Theatre, Boch Center, Boston Opera House), community theaters (Lyric Stage, SpeakEasy Stage, New Rep Theatre, Merrimack Repertory), and those in surrounding cities like Providence (Trinity Repertory, PPAC), Worcester (Hanover Theatre), Portsmouth, Lowell, Salem, etc. Include dance companies with regular performances. Aim for 20-30 venues.`,
  },
  {
    category: "museum",
    prompt: `List all museums and galleries within ${RADIUS_MILES} miles of ${CENTER_LAT},${CENTER_LNG}. Include major museums (MFA, ICA Boston, Isabella Stewart Gardner, Harvard Art Museums, MIT List Visual Arts Center, Peabody Essex Museum in Salem, RISD Museum in Providence), science museums (Museum of Science, MIT Museum), history museums (Old State House, Paul Revere House, JFK Library), and smaller galleries/art centers. Include museums in Worcester, Providence, Portsmouth, Cape Cod, etc. Aim for 25-35 venues.`,
  },
  {
    category: "library",
    prompt: `List all major public libraries that host regular events within ${RADIUS_MILES} miles of ${CENTER_LAT},${CENTER_LNG}. Include Boston Public Library (main + major branches like Copley, Roxbury), Cambridge Public Library, Brookline Public Library, Somerville Public Library, Newton Free Library, Providence Public Library, Worcester Public Library, and main branches in surrounding towns. Only include libraries known for hosting public events, author talks, or community programs. Aim for 15-25 venues.`,
  },
  {
    category: "comedy_club",
    prompt: `List all comedy clubs and venues with regular comedy shows within ${RADIUS_MILES} miles of ${CENTER_LAT},${CENTER_LNG}. Include dedicated comedy clubs (Laugh Boston, Nick's Comedy Stop, ImprovBoston, The Comedy Studio), theaters/bars with comedy nights, and venues in Providence, Worcester, and other surrounding cities. Aim for 10-15 venues.`,
  },
  {
    category: "sports_venue",
    prompt: `List all major sports venues and arenas within ${RADIUS_MILES} miles of ${CENTER_LAT},${CENTER_LNG}. Include professional venues (TD Garden, Fenway Park, Gillette Stadium, Amica Mutual Pavilion in Providence), college venues (Harvard Stadium, Agganis Arena at BU, Matthews Arena at Northeastern), minor league (Polar Park in Worcester, McCoy Stadium area), and racing/other sports venues. Aim for 15-20 venues.`,
  },
  {
    category: "community_center",
    prompt: `List notable community centers, cultural centers, and event spaces within ${RADIUS_MILES} miles of ${CENTER_LAT},${CENTER_LNG} that host regular public events. Include cultural centers (Chinese Cultural Center, Villa Victoria Center for the Arts, BCEC), community spaces (Cambridge Community Center, Somerville Armory), coworking/event spaces (CIC, District Hall), churches/houses of worship that host concerts or events, and similar in surrounding cities. Aim for 15-25 venues.`,
  },
  {
    category: "college",
    prompt: `List all colleges and universities within ${RADIUS_MILES} miles of ${CENTER_LAT},${CENTER_LNG} that host public events (lectures, concerts, film screenings, art shows). Include Boston-area schools (Harvard, MIT, BU, Northeastern, Boston College, Berklee, Emerson, Tufts, Brandeis, Wellesley), Providence schools (Brown, RISD), Worcester schools (WPI, Clark, Holy Cross), and others. Provide the events/calendar URL for each. Aim for 15-25 venues.`,
  },
  {
    category: "bar_events",
    prompt: `List bars, breweries, and pubs within ${RADIUS_MILES} miles of ${CENTER_LAT},${CENTER_LNG} known for hosting regular events (trivia, karaoke, open mic, DJ nights, tastings). Include breweries (Aeronaut, Lamplighter, Night Shift, Trillium, Jack's Abby, Narragansett in Providence), bars with weekly events (The Burren, Brendan Behan Pub, Zuzu, River Gods), and similar in surrounding cities. Aim for 15-25 venues.`,
  },
  {
    category: "outdoor_amphitheater",
    prompt: `List outdoor amphitheaters, bandshells, parks, and outdoor event spaces within ${RADIUS_MILES} miles of ${CENTER_LAT},${CENTER_LNG}. Include the Hatch Shell, Leader Bank Pavilion, Cape Cod Melody Tent, South Shore Music Circus, Bold Point Park in Providence, DCR parks with summer concerts, town commons with concert series, and outdoor festival grounds. Aim for 10-20 venues.`,
  },
  {
    category: "arts_center",
    prompt: `List arts centers, maker spaces, and multi-discipline creative venues within ${RADIUS_MILES} miles of ${CENTER_LAT},${CENTER_LNG}. Include Boston Center for the Arts, Brookline Arts Center, Cambridge Arts Council venues, AS220 in Providence, Worcester Center for Crafts, SoWa artists guild, Fort Point arts community spaces, Artisan's Asylum, and similar. Aim for 10-20 venues.`,
  },
];

// ── Claude API call ──

async function discoverVenues(cat) {
  const systemPrompt = `You are a venue research assistant. Return ONLY valid JSON. For each venue, provide accurate information. If you are unsure about a website or events URL, set it to null rather than guessing. All venues must be real, currently operating establishments.`;

  const userPrompt = `${cat.prompt}

Return a JSON object with a "venues" array. Each venue object must have:
- name (string, required)
- website (string or null — the venue's main website, e.g. "https://www.paradiserockclub.com")
- events_url (string or null — direct link to their events/calendar page, e.g. "https://www.paradiserockclub.com/events". If a venue has multiple event pages like separate calendars, exhibitions, and programs, comma-separate them: "https://example.com/programs, https://example.com/exhibitions")
- category (string: "${cat.category}")
- city (string — the city name, e.g. "Boston", "Cambridge", "Providence")
- metro_area (string: "${METRO}")
- lat (number — latitude, be as accurate as possible)
- lng (number — longitude, be as accurate as possible)

Be thorough. Include venues across the full ${RADIUS_MILES}-mile radius.`;

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
      system: systemPrompt,
      messages: [{ role: "user", content: userPrompt }],
    }),
  });

  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Anthropic API error ${res.status}: ${body}`);
  }

  const data = await res.json();
  const text = data.content?.[0]?.text || "";

  const jsonMatch = text.match(/\{[\s\S]*\}/);
  if (!jsonMatch) {
    throw new Error(`No JSON in response for category "${cat.category}"`);
  }

  return JSON.parse(jsonMatch[0]);
}

// ── Upsert to Supabase ──

async function upsertVenues(venues) {
  // Dedupe by name+city before upserting
  const seen = new Set();
  const unique = venues.filter((v) => {
    const key = `${v.name.toLowerCase()}|${v.city.toLowerCase()}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });

  const url = `${SUPABASE_URL}/rest/v1/venues`;
  const batchSize = 50;
  let inserted = 0;
  let errors = 0;

  for (let i = 0; i < unique.length; i += batchSize) {
    const batch = unique.slice(i, i + batchSize);
    const res = await fetch(url, {
      method: "POST",
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
        "Content-Type": "application/json",
        Prefer: "resolution=merge-duplicates,return=representation",
      },
      body: JSON.stringify(batch),
    });

    if (!res.ok) {
      const body = await res.text();
      // If conflict on name, try upsert with on_conflict
      if (res.status === 409) {
        const retryRes = await fetch(`${url}?on_conflict=name,city`, {
          method: "POST",
          headers: {
            apikey: SUPABASE_SERVICE_KEY,
            Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
            "Content-Type": "application/json",
            Prefer: "resolution=merge-duplicates",
          },
          body: JSON.stringify(batch),
        });
        if (retryRes.ok) {
          inserted += batch.length;
          continue;
        }
      }
      console.error(`  Supabase error (batch ${Math.floor(i / batchSize)}): ${res.status} ${body}`);
      errors += batch.length;
    } else {
      inserted += batch.length;
    }
  }

  return { inserted, errors, dedupedCount: venues.length - unique.length };
}

// ── Main ──

async function main() {
  const secrets = await getSecrets(["ANTHROPIC_API_KEY"]);
  ANTHROPIC_API_KEY = secrets.ANTHROPIC_API_KEY;
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  console.log("=== Venue Discovery ===\n");
  console.log(`Metro: ${METRO}`);
  console.log(`Center: ${CENTER_LAT}, ${CENTER_LNG}`);
  console.log(`Radius: ${RADIUS_MILES} miles`);
  console.log(`Categories: ${CATEGORIES.length}`);
  console.log(`Model: ${MODEL}\n`);

  const allVenues = [];
  const results = [];

  for (const cat of CATEGORIES) {
    console.log(`Discovering: ${cat.category}...`);
    try {
      const response = await discoverVenues(cat);
      const venues = response.venues || [];
      console.log(`  Found ${venues.length} venues`);

      // Log cities found
      const cities = {};
      venues.forEach((v) => {
        cities[v.city] = (cities[v.city] || 0) + 1;
      });
      const cityList = Object.entries(cities)
        .sort((a, b) => b[1] - a[1])
        .map(([c, n]) => `${c}(${n})`)
        .join(", ");
      console.log(`  Cities: ${cityList}`);

      allVenues.push(...venues);
      results.push({ category: cat.category, found: venues.length, error: null });
    } catch (err) {
      console.error(`  ERROR: ${err.message}`);
      results.push({ category: cat.category, found: 0, error: err.message });
    }

    // Pause between API calls
    await new Promise((r) => setTimeout(r, 2000));
  }

  if (allVenues.length === 0) {
    console.log("\nNo venues discovered. Exiting.");
    return;
  }

  // Normalize all venues
  const rows = allVenues.map((v) => ({
    name: v.name,
    website: v.website || null,
    events_url: v.events_url || null,
    category: v.category,
    city: v.city,
    metro_area: v.metro_area || METRO,
    lat: v.lat || null,
    lng: v.lng || null,
    is_active: true,
    crawl_success_rate: 1.0,
  }));

  // Upsert
  console.log(`\nUpserting ${rows.length} venues to Supabase...`);
  const { inserted, errors, dedupedCount } = await upsertVenues(rows);

  // Summary
  console.log("\n=== Discovery Summary ===");
  for (const r of results) {
    const status = r.error ? `ERROR: ${r.error}` : `${r.found} venues`;
    console.log(`  ${r.category}: ${status}`);
  }

  console.log(`\n  Total discovered: ${allVenues.length}`);
  console.log(`  Duplicates removed: ${dedupedCount}`);
  console.log(`  Upserted: ${inserted}`);
  console.log(`  Errors: ${errors}`);

  // City breakdown
  const cities = {};
  allVenues.forEach((v) => {
    cities[v.city] = (cities[v.city] || 0) + 1;
  });
  console.log("\n  By city:");
  Object.entries(cities)
    .sort((a, b) => b[1] - a[1])
    .forEach(([city, count]) => {
      console.log(`    ${city}: ${count}`);
    });

  // Category breakdown
  const cats = {};
  allVenues.forEach((v) => {
    cats[v.category] = (cats[v.category] || 0) + 1;
  });
  console.log("\n  By category:");
  Object.entries(cats)
    .sort((a, b) => b[1] - a[1])
    .forEach(([cat, count]) => {
      console.log(`    ${cat}: ${count}`);
    });
}

main().catch((err) => {
  console.error("Fatal error:", err);
  process.exit(1);
});
