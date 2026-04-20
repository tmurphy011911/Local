/**
 * discover_venues_google.js — Discover real venues via Google Places API, upsert to Supabase
 *
 * Usage:
 *   GOOGLE_PLACES_API_KEY=xxx SUPABASE_SERVICE_KEY=ey... node scripts/discover_venues_google.js
 *
 * Optional:
 *   CENTER_LAT=42.3601  CENTER_LNG=-71.0589  (override single center point)
 *   STATE=MA             (used with CENTER_LAT/LNG for category text queries)
 *   RADIUS_METERS=50000 (default 50km ≈ 31 miles, max 50000)
 *   DRY_RUN=1           (preview without writing to Supabase)
 *   CATEGORY=bar,brewery (comma-separated filter — only run specific categories)
 */

const { getSecrets, SUPABASE_URL } = require("./lib/secrets");

let GOOGLE_PLACES_API_KEY, SUPABASE_SERVICE_KEY;

const RADIUS_METERS = parseInt(process.env.RADIUS_METERS || "50000", 10);
const DRY_RUN = process.env.DRY_RUN === "1";
const CATEGORY_FILTER = process.env.CATEGORY
  ? process.env.CATEGORY.split(",").map((s) => s.trim())
  : null;

// Use custom center if provided — for manual one-off runs
const USE_CUSTOM_CENTER = process.env.CENTER_LAT && process.env.CENTER_LNG;
const GRID_CENTERS = USE_CUSTOM_CENTER
  ? [{ label: "Custom", lat: parseFloat(process.env.CENTER_LAT), lng: parseFloat(process.env.CENTER_LNG) }]
  : null;

// ── Google Places API (New) — Nearby Search ──

const PLACES_FIELDS = [
  "places.id",
  "places.displayName",
  "places.formattedAddress",
  "places.location",
  "places.websiteUri",
  "places.googleMapsUri",
  "places.types",
  "places.businessStatus",
  "places.photos",
  "places.editorialSummary",
  "places.rating",
  "places.priceLevel",
].join(",");

async function searchNearby(includedTypes, centerLat, centerLng) {
  const allPlaces = [];

  const body = {
    includedTypes,
    locationRestriction: {
      circle: {
        center: { latitude: centerLat, longitude: centerLng },
        radius: RADIUS_METERS,
      },
    },
    maxResultCount: 20,
    languageCode: "en",
  };

  let pageToken = null;
  let page = 0;

  do {
    const requestBody = { ...body };
    if (pageToken) requestBody.pageToken = pageToken;

    const res = await fetch(
      "https://places.googleapis.com/v1/places:searchNearby",
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Goog-Api-Key": GOOGLE_PLACES_API_KEY,
          "X-Goog-FieldMask": PLACES_FIELDS,
        },
        body: JSON.stringify(requestBody),
      }
    );

    if (!res.ok) {
      const errBody = await res.text();
      console.error(
        `  Google Places API error ${res.status}: ${errBody.substring(0, 200)}`
      );
      break;
    }

    const data = await res.json();
    const places = data.places || [];
    allPlaces.push(...places);
    page++;

    pageToken = data.nextPageToken || null;
    if (pageToken) await sleep(200);
  } while (pageToken && page < 5);

  return allPlaces;
}

// ── Google Places API (New) — Text Search (for broader coverage) ──

const TEXT_FIELDS = [
  "places.id",
  "places.displayName",
  "places.formattedAddress",
  "places.location",
  "places.websiteUri",
  "places.googleMapsUri",
  "places.types",
  "places.businessStatus",
  "places.photos",
  "places.editorialSummary",
  "places.rating",
  "places.priceLevel",
].join(",");

async function searchText(query, centerLat, centerLng) {
  const allPlaces = [];

  const body = {
    textQuery: query,
    locationBias: {
      circle: {
        center: { latitude: centerLat, longitude: centerLng },
        radius: RADIUS_METERS,
      },
    },
    maxResultCount: 20,
    languageCode: "en",
  };

  let pageToken = null;
  let page = 0;

  do {
    const requestBody = { ...body };
    if (pageToken) requestBody.pageToken = pageToken;

    const res = await fetch(
      "https://places.googleapis.com/v1/places:searchText",
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Goog-Api-Key": GOOGLE_PLACES_API_KEY,
          "X-Goog-FieldMask": TEXT_FIELDS,
        },
        body: JSON.stringify(requestBody),
      }
    );

    if (!res.ok) {
      const errBody = await res.text();
      console.error(
        `  Google Text Search API error ${res.status}: ${errBody.substring(0, 200)}`
      );
      break;
    }

    const data = await res.json();
    const places = data.places || [];
    allPlaces.push(...places);
    page++;

    pageToken = data.nextPageToken || null;
    if (pageToken) await sleep(200);
  } while (pageToken && page < 5);

  return allPlaces;
}

// ── Chain / non-local venue filter ──

const EXCLUDED_NAMES = new Set([
  // Fast food
  "mcdonald's", "burger king", "wendy's", "taco bell", "kfc",
  "popeyes", "chick-fil-a", "five guys", "shake shack", "chipotle",
  "subway", "arby's", "sonic drive-in", "jack in the box", "carl's jr.",
  "hardee's", "whataburger", "panda express", "wingstop", "raising cane's",
  "jersey mike's", "jimmy john's", "firehouse subs", "potbelly",
  "el pollo loco", "del taco", "church's chicken", "zaxby's",
  "culver's", "in-n-out burger", "white castle", "checkers", "rally's",
  "little caesars", "papa john's", "domino's", "pizza hut",
  "papa gino's", "d'angelo", "qdoba", "moe's southwest grill",
  "boston market", "quiznos", "jason's deli", "mcalister's deli",
  "tropical smoothie cafe", "jamba juice", "smoothie king",
  "auntie anne's", "cinnabon", "krispy kreme",
  // Coffee/donut chains
  "dunkin'", "dunkin' donuts", "starbucks", "tim hortons",
  "peet's coffee", "caribou coffee", "dutch bros",
  // Casual dining chains
  "applebee's", "chili's", "olive garden", "red lobster", "outback steakhouse",
  "longhorn steakhouse", "texas roadhouse", "buffalo wild wings",
  "t.g.i. friday's", "tgi fridays", "cracker barrel", "denny's", "ihop",
  "bob evans", "golden corral", "red robin", "ruby tuesday", "perkins",
  "friendly's", "the cheesecake factory", "cheesecake factory",
  "p.f. chang's", "pf chang's", "benihana", "hooters", "dave & buster's",
  "dave and buster's", "noodles & company", "panera bread", "panera",
  "au bon pain", "cosi", "sweetgreen", "cava", "chipotle mexican grill",
  "99 restaurants", "the 99", "bertucci's", "uno pizzeria & grill",
  "not your average joe's", "the capital grille", "legal sea foods",
  "wahlburgers", "b.good", "boloco",
  // Big box / retail
  "walmart", "target", "costco", "sam's club", "bj's wholesale",
  "home depot", "lowe's", "best buy", "staples", "office depot",
  "walgreens", "cvs", "rite aid", "dollar tree", "dollar general",
  "family dollar", "marshalls", "t.j. maxx", "tjmaxx", "tj maxx",
  "ross dress for less", "burlington", "old navy", "gap", "h&m",
  "bath & body works", "bed bath & beyond", "pier 1", "michaels",
  "joann fabrics", "hobby lobby", "ace hardware", "true value",
  "autozone", "o'reilly auto parts", "advance auto parts", "napa auto parts",
  "pep boys", "jiffy lube", "valvoline", "midas", "maaco",
  // Hotel chains
  "marriott", "hilton", "holiday inn", "hampton inn", "courtyard by marriott",
  "residence inn", "fairfield inn", "comfort inn", "quality inn",
  "best western", "days inn", "super 8", "motel 6", "la quinta",
  "ramada", "wyndham", "hyatt", "sheraton", "westin", "radisson",
  "four points", "aloft", "element", "w hotel",
  // Gym chains
  "planet fitness", "anytime fitness", "la fitness", "orangetheory fitness",
  "gold's gym", "equinox", "crunch fitness", "snap fitness", "lifetime fitness",
  "24 hour fitness", "crossfit", "f45 training", "barry's bootcamp",
  "blink fitness", "retro fitness",
  // Gas stations / convenience
  "shell", "exxon", "mobil", "chevron", "bp", "sunoco", "citgo",
  "7-eleven", "cumberland farms", "irving", "speedway", "wawa",
  "circle k", "getty",
  // Movie theater chains
  "amc theatres", "amc", "regal cinemas", "regal", "cinemark",
  "showcase cinemas", "showcase cinema",
  // Grocery / pharmacy
  "whole foods", "whole foods market", "trader joe's", "stop & shop",
  "shaw's", "market basket", "aldi", "wegmans", "hannaford", "big y",
  "price chopper", "food lion", "lidl", "save-a-lot",
  // Banks
  "bank of america", "chase", "wells fargo", "td bank", "citizens bank",
  "santander", "rockland trust", "webster bank", "peoples united bank",
  "eastern bank", "bankinter",
  // Phone / telecom
  "verizon", "at&t", "t-mobile", "sprint", "xfinity", "spectrum",
  // Car dealerships / rental
  "toyota", "honda", "ford", "chevrolet", "nissan", "hyundai", "kia",
  "subaru", "volkswagen", "bmw", "mercedes-benz", "audi", "lexus",
  "enterprise rent-a-car", "hertz", "avis", "budget",
  // Medical / dental / veterinary
  "urgent care", "minuteclinic", "quest diagnostics", "labcorp",
  "aspen dental", "heartland dental", "gentle dental", "banfield pet hospital",
  "petco", "petsmart",
  // Shipping / logistics
  "ups store", "fedex office", "usps",
  // Real estate / insurance
  "state farm", "allstate", "geico", "liberty mutual", "progressive",
  "re/max", "coldwell banker", "keller williams", "century 21",
  // Storage
  "public storage", "extra space storage", "cubesmart", "life storage",
  // Funeral
  "funeral home", "funeral service",
  // Dry cleaning / laundry
  "dry cleaners", "laundromat",
  // Spa / massage chains
  "massage envy", "hand & stone", "hand and stone", "elements massage",
  "european wax center", "ideal image", "laser away", "milan laser",
  // Bowling / entertainment chains
  "bowlero", "bowlmor", "amf bowling", "lucky strike",
  "dave & buster's", "dave and buster's", "main event", "topgolf",
  "k1 speed", "urban air", "sky zone", "launch trampoline",
  // Shopping / retail chains (beyond big box already listed)
  "primark", "zara", "h&m", "forever 21", "uniqlo",
  "nordstrom rack", "saks off 5th", "ross", "five below",
  "dollar tree", "dollar general", "family dollar",
  "gamestop", "spencer's", "hot topic", "bath & body works",
  // Bookstore chains
  "barnes & noble", "barnes and noble", "books-a-million",
  // Market / grocery chains (beyond already listed)
  "sprouts", "fresh market", "the fresh market",
  // Private / non-public
  "elks lodge", "vfw post", "american legion", "moose lodge",
  "knights of columbus", "masonic lodge", "rotary club",
  "country club", "yacht club", "tennis club", "swim club",
]);

// Patterns that indicate a chain/non-local venue
const EXCLUDED_PATTERNS = [
  /^walmart\b/i, /^target\b/i, /^costco\b/i,
  /^mcdonald/i, /^burger king/i, /^dunkin/i, /^starbucks/i,
  /^marriott/i, /^hilton/i, /^holiday inn/i, /^hampton inn/i,
  /^comfort (inn|suites)/i, /^courtyard by/i, /^residence inn/i,
  /^fairfield (inn|by)/i, /^springhill suites/i, /^towneplace suites/i,
  /^homewood suites/i, /^embassy suites/i, /^doubletree/i,
  /^planet fitness/i, /^anytime fitness/i, /^orangetheory/i,
  /^la fitness/i, /^gold'?s gym/i, /^crunch fitness/i,
  /^amc\b/i, /^regal\b/i, /^cinemark/i, /^showcase cinema/i,
  /^td bank/i, /^bank of america/i, /^chase bank/i, /^citizens bank/i,
  /^wells fargo/i, /^santander/i,
  /^verizon/i, /^at&t/i, /^t-mobile/i, /^xfinity/i, /^spectrum/i,
  /^enterprise rent/i, /^hertz/i, /^avis\b/i, /^budget\b/i,
  /^ups store/i, /^fedex/i,
  /^state farm/i, /^allstate/i, /^geico/i, /^liberty mutual/i,
  /^public storage/i, /^extra space/i, /^cubesmart/i,
  /^petco\b/i, /^petsmart/i, /^banfield/i,
  /^aspen dental/i, /^gentle dental/i, /^quest diagnostics/i,
  /\bfuneral\b/i, /\bmortuary\b/i,
  /\bdry clean/i, /\blaundromat/i, /\bcar wash\b/i,
  /\bauto body\b/i, /\bauto repair\b/i, /\btire (center|shop)\b/i,
  /\bnail salon\b/i, /\bhair salon\b/i, /\bbarber\s?shop\b/i,
  /\bstorage\s+(unit|facilit)/i,
  /\bdaycare\b/i, /\bchild\s*care\b/i, /\bpreschool\b/i,
  /\bdentist/i, /\bdental\s+(office|clinic|center|care|group)/i,
  /\bchiropractic/i, /\bchiropractor/i,
  /\bphysical therapy\b/i, /\brehab center\b/i,
  /\baccounting\b/i, /\btax (prep|service)/i,
  /\blaw (office|firm|group)\b/i, /\battorney/i,
  /\breal estate\b/i, /\brealty\b/i,
  /\bself.?storage\b/i, /\bmini.?storage\b/i,
  /\bparking (lot|garage)\b/i,
  // Spa / wellness chains
  /^massage envy/i, /^hand (& |and )stone/i, /^elements massage/i,
  /^european wax/i, /^ideal image/i, /^milan laser/i,
  // Entertainment chains
  /^bowlero/i, /^bowlmor/i, /^amf\b/i, /^topgolf/i, /^k1 speed/i,
  /^urban air/i, /^sky zone/i, /^launch trampoline/i, /^main event\b/i,
  // Private clubs / lodges
  /\belks lodge\b/i, /\bvfw\b/i, /\bamerican legion\b/i,
  /\bmoose lodge\b/i, /\bknights of columbus\b/i, /\bmasonic\b/i,
  /\bcountry club\b/i, /\byacht club\b/i,
  /\bhoa\b/i, /\bhomeowners?\s*assoc/i,
  /\brotary club\b/i, /\blions club\b/i, /\bkiwanis\b/i,
  // Misc non-public
  /\bwedding venue\b/i, /\bbanquet hall\b/i, /\bfunction hall\b/i,
  /\brehearsal space\b/i, /\bcoworking\b/i, /\bco-working\b/i,
  /\bself.?serve\b/i, /\bvending\b/i,
];

// Google Places types that don't belong on a local discovery app
const EXCLUDED_TYPES = new Set([
  "accounting", "atm", "bank", "car_dealer", "car_rental", "car_repair",
  "car_wash", "cemetery", "dentist", "doctor", "drugstore", "electrician",
  "fire_station", "funeral_home", "gas_station", "hair_care", "hair_salon",
  "hospital", "insurance_agency", "laundry", "lawyer", "locksmith",
  "lodging", "moving_company", "painter", "parking", "pharmacy",
  "physiotherapist", "plumber", "police", "post_office", "real_estate_agency",
  "roofing_contractor", "school", "shoe_store", "storage", "store",
  "supermarket", "transit_station", "travel_agency", "veterinary_care",
  // Additional non-public types for expanded categories
  "church", "mosque", "synagogue", "hindu_temple",
  "city_hall", "courthouse", "embassy", "local_government_office",
  "university", "primary_school", "secondary_school",
  "gym", "fitness_center",
  "rv_park", "campground",
  "wedding_venue", "funeral_director",
  "private_club",
]);

function isExcludedVenue(name, placeTypes) {
  const lower = name.toLowerCase().trim();
  if (EXCLUDED_NAMES.has(lower)) return true;
  if (EXCLUDED_PATTERNS.some((re) => re.test(name))) return true;
  // Exclude if the venue's PRIMARY type (first) is a non-event type
  if (placeTypes && placeTypes.length > 0) {
    if (EXCLUDED_TYPES.has(placeTypes[0])) return true;
  }
  return false;
}

// ── Transform Google Place to venue row ──

function extractCity(formattedAddress) {
  if (!formattedAddress) return "";
  // Typical format: "123 Main St, Cambridge, MA 02139, USA"
  const parts = formattedAddress.split(",").map((s) => s.trim());
  // City is usually the second-to-last before state+zip
  if (parts.length >= 3) {
    return parts[parts.length - 3] || parts[parts.length - 2] || "";
  }
  if (parts.length >= 2) {
    return parts[parts.length - 2] || "";
  }
  return parts[0] || "";
}

function extractState(formattedAddress, fallbackState) {
  if (!formattedAddress) return fallbackState;
  // "123 Main St, Cambridge, MA 02139, USA" → "MA"
  const parts = formattedAddress.split(",").map((s) => s.trim());
  if (parts.length >= 2) {
    const stateZip = parts[parts.length - 2]; // "MA 02139"
    const match = stateZip.match(/^([A-Z]{2})\s/);
    if (match) return match[1];
  }
  return fallbackState;
}

// ── Category → place_type mapping ──

const PLACE_TYPE_MAP = {
  // eat_drink
  restaurant: "eat_drink", bar: "eat_drink", brewery: "eat_drink",
  cafe: "eat_drink", bakery: "eat_drink", winery: "eat_drink",
  // public_attraction
  park: "public_attraction", beach: "public_attraction", garden: "public_attraction",
  trail: "public_attraction", waterfront: "public_attraction", plaza: "public_attraction",
  public_space: "public_attraction",
  // ticketed
  museum: "ticketed", aquarium: "ticketed", zoo: "ticketed",
  historic_site: "ticketed", tour: "ticketed", observation: "ticketed",
  escape_room: "ticketed", arts_center: "ticketed", library: "ticketed",
  theater: "ticketed",
  // classes
  classes: "ticketed",
  // entertainment
  bowling: "entertainment", arcade: "entertainment", mini_golf: "entertainment",
  movie_theater: "entertainment", spa: "entertainment", shopping: "entertainment",
  bookstore: "entertainment", market: "entertainment", comedy_club: "entertainment",
  // event_venue (primarily for crawling)
  music_venue: "event_venue", sports_venue: "event_venue", stadium: "event_venue",
  outdoor_amphitheater: "event_venue", college: "event_venue", bar_events: "event_venue",
};

function placeToVenue(place, category, currentStateCode) {
  const name = place.displayName?.text || place.displayName || "";
  const city = extractCity(place.formattedAddress);
  const state = extractState(place.formattedAddress, currentStateCode);
  const lat = place.location?.latitude || null;
  const lng = place.location?.longitude || null;
  const website = place.websiteUri || null;
  const placeTypes = place.types || [];
  const description = place.editorialSummary?.text || null;
  const firstPhoto = place.photos?.[0];
  const photo_url = firstPhoto
    ? `https://places.googleapis.com/v1/${firstPhoto.name}/media?maxWidthPx=800&key=${GOOGLE_PLACES_API_KEY}`
    : null;

  return {
    name,
    website,
    events_url: null, // Will be discovered by fix_venue_urls.js
    category,
    place_type: PLACE_TYPE_MAP[category] || null,
    city,
    state,
    lat,
    lng,
    is_active: true,
    crawl_success_rate: 1.0,
    photo_url,
    description,
    address: place.formattedAddress || null,
    google_rating: place.rating || null,
    price_level: place.priceLevel || null,
    _placeTypes: placeTypes, // used for filtering, stripped before upsert
  };
}

// ── Upsert to Supabase ──

function stripInternalFields(obj) {
  const clean = {};
  for (const [k, v] of Object.entries(obj)) {
    if (!k.startsWith("_")) clean[k] = v;
  }
  return clean;
}

async function upsertVenues(venues) {
  const seen = new Set();
  const unique = venues.filter((v) => {
    const key = `${v.name.toLowerCase()}|${(v.city || "").toLowerCase()}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });

  if (DRY_RUN) {
    console.log(`  [DRY RUN] Would upsert ${unique.length} venues`);
    return { inserted: unique.length, errors: 0, dedupedCount: venues.length - unique.length };
  }

  const url = `${SUPABASE_URL}/rest/v1/venues?on_conflict=name,city`;
  const batchSize = 50;
  let inserted = 0;
  let errors = 0;

  for (let i = 0; i < unique.length; i += batchSize) {
    const batch = unique.slice(i, i + batchSize).map(stripInternalFields);
    const res = await fetch(url, {
      method: "POST",
      headers: {
        apikey: SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
        "Content-Type": "application/json",
        Prefer: "resolution=merge-duplicates",
      },
      body: JSON.stringify(batch),
    });

    if (!res.ok) {
      const body = await res.text();
      console.error(`  Supabase error (batch ${Math.floor(i / batchSize)}): ${res.status} ${body.substring(0, 500)}`);
      errors += batch.length;
    } else {
      inserted += batch.length;
    }
  }

  return { inserted, errors, dedupedCount: venues.length - unique.length };
}

// ── Fetch grid centers from state_grid_centers table ──

async function fetchGridCentersForState(stateCode) {
  try {
    const res = await fetch(
      `${SUPABASE_URL}/rest/v1/state_grid_centers?state_code=eq.${stateCode}&select=label,lat,lng`,
      {
        headers: {
          apikey: SUPABASE_SERVICE_KEY,
          Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
        },
      }
    );
    if (!res.ok) return [];
    const centers = await res.json();
    if (!Array.isArray(centers) || centers.length === 0) return [];
    return centers;
  } catch (_) {
    return [];
  }
}

// ── Fetch live states from Supabase ──

async function fetchLiveStates() {
  try {
    const res = await fetch(
      `${SUPABASE_URL}/rest/v1/states?is_live=eq.true&select=code,name`,
      {
        headers: {
          apikey: SUPABASE_SERVICE_KEY,
          Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
        },
      }
    );
    if (!res.ok) {
      const body = await res.text();
      console.error(`  Failed to fetch live states (${res.status}): ${body.substring(0, 200)}`);
      console.log("  Falling back to default: MA (Massachusetts)");
      return [{ code: "MA", name: "Massachusetts" }];
    }
    const states = await res.json();
    if (!Array.isArray(states) || states.length === 0) {
      console.log("  No live states found, falling back to default: MA (Massachusetts)");
      return [{ code: "MA", name: "Massachusetts" }];
    }
    return states;
  } catch (err) {
    console.error(`  Error fetching live states: ${err.message}`);
    console.log("  Falling back to default: MA (Massachusetts)");
    return [{ code: "MA", name: "Massachusetts" }];
  }
}

// ── Build categories for a given state name ──

function buildCategories(stateName) {
  return [
    // ── Eat & Drink ──
    { category: "restaurant", includedTypes: ["restaurant"], textQuery: `restaurants in ${stateName}` },
    { category: "bar", includedTypes: ["bar"], textQuery: `bars in ${stateName}` },
    { category: "brewery", includedTypes: ["brewery"], textQuery: null },
    { category: "winery", includedTypes: ["winery"], textQuery: null },
    { category: "cafe", includedTypes: ["cafe", "coffee_shop"], textQuery: null },
    { category: "bakery", includedTypes: ["bakery"], textQuery: null },

    // ── Public Attractions ──
    { category: "park", includedTypes: ["park", "national_park"], textQuery: `parks in ${stateName}` },
    { category: "beach", includedTypes: ["beach"], textQuery: `beaches in ${stateName}` },
    { category: "garden", includedTypes: ["botanical_garden"], textQuery: `gardens in ${stateName}` },
    { category: "trail", includedTypes: ["hiking_area"], textQuery: `hiking trails in ${stateName}` },
    { category: "waterfront", includedTypes: ["marina"], textQuery: `waterfront harbor walk in ${stateName}` },
    { category: "plaza", includedTypes: ["plaza"], textQuery: `public plazas squares in ${stateName}` },
    { category: "public_space", includedTypes: ["community_center", "convention_center", "event_venue"], textQuery: `event spaces community centers in ${stateName}` },

    // ── Ticketed / Tours ──
    { category: "museum", includedTypes: ["museum", "art_gallery"], textQuery: null },
    { category: "aquarium", includedTypes: ["aquarium"], textQuery: null },
    { category: "zoo", includedTypes: ["zoo"], textQuery: null },
    { category: "historic_site", includedTypes: ["historical_landmark"], textQuery: `historic sites landmarks in ${stateName}` },
    { category: "tour", includedTypes: ["tourist_attraction"], textQuery: `tours walking tours in ${stateName}` },
    { category: "arts_center", includedTypes: ["cultural_center", "performing_arts_theater"], textQuery: `arts centers in ${stateName}` },
    { category: "library", includedTypes: ["library"], textQuery: null },
    { category: "theater", includedTypes: ["performing_arts_theater", "movie_theater"], textQuery: `theaters in ${stateName}` },
    { category: "escape_room", includedTypes: ["escape_room"], textQuery: `escape rooms in ${stateName}` },

    // ── Entertainment ──
    { category: "bowling", includedTypes: ["bowling_alley"], textQuery: null },
    { category: "arcade", includedTypes: ["amusement_center"], textQuery: `arcades in ${stateName}` },
    { category: "mini_golf", includedTypes: ["miniature_golf_course"], textQuery: null },
    { category: "spa", includedTypes: ["spa"], textQuery: null },
    { category: "shopping", includedTypes: ["shopping_mall"], textQuery: `shopping districts in ${stateName}` },
    { category: "bookstore", includedTypes: ["book_store"], textQuery: `independent bookstores in ${stateName}` },
    { category: "market", includedTypes: ["market"], textQuery: `farmers markets flea markets in ${stateName}` },

    // ── Classes & Workshops ──
    { category: "classes", includedTypes: ["yoga_studio"], textQuery: [
      `dance studios in ${stateName}`,
      `pottery studios in ${stateName}`,
      `cooking classes in ${stateName}`,
      `art studios in ${stateName}`,
      `art centers in ${stateName}`,
      `yoga pilates studios in ${stateName}`,
      `climbing gyms in ${stateName}`,
    ] },

    // ── Event Venues (for crawl pipeline) ──
    { category: "music_venue", includedTypes: ["live_music_venue", "night_club"], textQuery: `music venues concert halls in ${stateName}` },
    { category: "sports_venue", includedTypes: ["sports_complex", "arena", "gym"], textQuery: `sports venues arenas in ${stateName}` },
    { category: "stadium", includedTypes: ["stadium"], textQuery: null },
  ];
}

// ── Helpers ──

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

// ── Main ──

async function discoverForGrid(gridCenters, categoriesToRun, currentStateCode) {
  const allVenues = [];
  const results = [];

  for (const cat of categoriesToRun) {
    console.log(`Discovering: ${cat.category}...`);
    const placesMap = new Map(); // Google place ID → place, for deduplication

    try {
      for (const center of gridCenters) {
        console.log(`  Searching around ${center.label} (${center.lat}, ${center.lng})...`);

        // Nearby Search by type
        for (const type of cat.includedTypes) {
          const places = await searchNearby([type], center.lat, center.lng);
          for (const p of places) {
            if (p.businessStatus === "CLOSED_PERMANENTLY") continue;
            if (p.id && !placesMap.has(p.id)) placesMap.set(p.id, p);
          }
          await sleep(300);
        }

        // Text Search for broader coverage — textQuery can be string or array
        const textQueries = Array.isArray(cat.textQuery)
          ? cat.textQuery
          : cat.textQuery ? [cat.textQuery] : [];
        for (const q of textQueries) {
          const textPlaces = await searchText(q, center.lat, center.lng);
          for (const p of textPlaces) {
            if (p.businessStatus === "CLOSED_PERMANENTLY") continue;
            if (p.id && !placesMap.has(p.id)) placesMap.set(p.id, p);
          }
          await sleep(300);
        }
      }

      const allMapped = Array.from(placesMap.values())
        .map((p) => placeToVenue(p, cat.category, currentStateCode))
        .filter((v) => v.name && v.city);
      const venues = allMapped
        .filter((v) => !isExcludedVenue(v.name, v._placeTypes));
      const excluded = allMapped.length - venues.length;

      console.log(`  Found ${venues.length} venues (${placesMap.size} from API, ${excluded} chains/excluded)`);

      // Log city breakdown
      const cities = {};
      venues.forEach((v) => {
        cities[v.city] = (cities[v.city] || 0) + 1;
      });
      const cityList = Object.entries(cities)
        .sort((a, b) => b[1] - a[1])
        .slice(0, 10)
        .map(([c, n]) => `${c}(${n})`)
        .join(", ");
      console.log(`  Cities: ${cityList}`);

      allVenues.push(...venues);
      results.push({ category: cat.category, found: venues.length, error: null });
    } catch (err) {
      console.error(`  ERROR: ${err.message}`);
      results.push({ category: cat.category, found: 0, error: err.message });
    }

    await sleep(500);
  }

  return { allVenues, results };
}

async function main() {
  const secrets = await getSecrets(["GOOGLE_PLACES_API_KEY"]);
  GOOGLE_PLACES_API_KEY = secrets.GOOGLE_PLACES_API_KEY;
  SUPABASE_SERVICE_KEY = secrets.SUPABASE_SERVICE_KEY;

  if (!GOOGLE_PLACES_API_KEY) {
    console.error("Missing GOOGLE_PLACES_API_KEY");
    process.exit(1);
  }
  if (!SUPABASE_SERVICE_KEY && !DRY_RUN) {
    console.error("Missing SUPABASE_SERVICE_KEY");
    process.exit(1);
  }

  console.log("=== Venue Discovery (Google Places API) ===\n");
  console.log(`Radius per center: ${RADIUS_METERS}m (${(RADIUS_METERS / 1609.34).toFixed(1)} miles)`);
  if (DRY_RUN) console.log("MODE: DRY RUN");
  console.log();

  const allVenues = [];
  const results = [];

  if (USE_CUSTOM_CENTER) {
    // Custom center override — single run with custom coordinates
    const STATE_CODE = process.env.STATE || "MA";
    const STATE_NAME = STATE_CODE; // Best effort for custom center
    const CATEGORIES = buildCategories(STATE_NAME);

    console.log(`Custom center: ${GRID_CENTERS[0].lat}, ${GRID_CENTERS[0].lng}`);
    console.log(`State: ${STATE_CODE}`);
    console.log(`Grid centers: ${GRID_CENTERS.map((c) => c.label).join(", ")}`);
    console.log();

    const categoriesToRun = CATEGORY_FILTER
      ? CATEGORIES.filter((c) => CATEGORY_FILTER.includes(c.category))
      : CATEGORIES;

    console.log(`Categories: ${categoriesToRun.map((c) => c.category).join(", ")}\n`);

    const result = await discoverForGrid(GRID_CENTERS, categoriesToRun, STATE_CODE);
    allVenues.push(...result.allVenues);
    results.push(...result.results);
  } else {
    // Fetch live states from Supabase and loop over each
    console.log("Fetching live states from Supabase...");
    const liveStates = await fetchLiveStates();
    console.log(`Live states: ${liveStates.map((s) => `${s.code} (${s.name})`).join(", ")}\n`);

    for (const liveState of liveStates) {
      const gridCenters = await fetchGridCentersForState(liveState.code);

      if (!gridCenters || gridCenters.length === 0) {
        console.warn(`WARNING: State ${liveState.code} (${liveState.name}) has no grid centers. Run: STATE=${liveState.code} node seed_state_grid.js`);
        continue;
      }

      console.log(`\n========================================`);
      console.log(`Processing state: ${liveState.code} (${liveState.name})`);
      console.log(`Grid centers: ${gridCenters.map((c) => c.label).join(", ")}`);
      console.log(`========================================\n`);

      const categories = buildCategories(liveState.name);
      const categoriesToRun = CATEGORY_FILTER
        ? categories.filter((c) => CATEGORY_FILTER.includes(c.category))
        : categories;

      console.log(`Categories: ${categoriesToRun.map((c) => c.category).join(", ")}\n`);

      const result = await discoverForGrid(gridCenters, categoriesToRun, liveState.code);
      allVenues.push(...result.allVenues);
      results.push(...result.results.map((r) => ({ ...r, state: liveState.code })));
    }
  }

  if (allVenues.length === 0) {
    console.log("\nNo venues discovered. Exiting.");
    return;
  }

  // Upsert
  console.log(`\nUpserting ${allVenues.length} venues to Supabase...`);
  const { inserted, errors, dedupedCount } = await upsertVenues(allVenues);

  // Summary
  console.log("\n=== Discovery Summary ===");
  for (const r of results) {
    const stateLabel = r.state ? `[${r.state}] ` : "";
    const status = r.error ? `ERROR: ${r.error}` : `${r.found} venues`;
    console.log(`  ${stateLabel}${r.category}: ${status}`);
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
