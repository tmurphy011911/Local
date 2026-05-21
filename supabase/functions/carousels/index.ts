import "@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { jsonCompressed, corsPreflight } from "../_shared/compress.ts";
import { withObservability } from "../_shared/observability.ts";

// ════════════════════════════════════════════════════════════════
// Types
// ════════════════════════════════════════════════════════════════

interface EventRow {
  id: string;
  title: string;
  description: string | null;
  category: string;
  subcategory: string | null;
  sub_subcategory: string | null;
  team_name: string | null;
  start_at: string | null;
  end_at: string | null;
  is_free: boolean;
  price_min: number | null;
  price_max: number | null;
  ticket_url: string | null;
  image_url: string | null;
  venue_name: string | null;
  venue_address: string | null;
  venue_city: string;
  venue_state: string | null;
  venue_lat: number | null;
  venue_lng: number | null;
  source: string;
  source_url: string | null;
  city: string;
  time_known: boolean;
}

interface ProcessedEvent extends EventRow {
  distance_miles: number | null;
  venue_description: string | null;
  venue_photo_url: string | null;
}

interface VenueInfo {
  rank: number | null;
  photo_url: string | null;
  description: string | null;
}

interface CarouselConfig {
  id: string;
  city: string;
  slug: string;
  title: string;
  subtitle: string | null;
  filter_type: string;
  filter_value: string | null;
  sort_order: string | null;
  position: number;
  is_active: boolean;
}

interface UserPreferences {
  id: string;
  first_name: string | null;
  last_name: string | null;
  phone: string | null;
  city: string;
  user_lat: number | null;
  user_lng: number | null;
  selected_neighborhoods: string[];
  selected_venues: string[];
  selected_categories: string[];
  notif_tickets: boolean;
  notif_nearby: boolean;
  notif_venues: boolean;
  onboarding_complete: boolean;
}

interface CommunityScore {
  item_id: string;
  personal_clicks: number;
  personal_favorites: number;
  personal_shares: number;
  personal_plan_adds: number;
  community_clicks: number;
  community_favorites: number;
  community_shares: number;
  community_plan_adds: number;
}

interface CategoryAffinity {
  category: string;
  weight: number;
}

interface CarouselSection {
  slug: string;
  title: string;
  subtitle: string | null;
  filter_type: string;
  events: ProcessedEvent[];
  show_saves?: boolean;
}

interface CategoryInfo {
  id: string;
  label: string;
  icon_url: string;
  event_count: number;
}

interface ScoreWeights {
  relevance: number;
  venueRank: number;
  proximity: number;
  date: number;
  decayDivisor: number;
  maxEventRadius: number;
}

// ════════════════════════════════════════════════════════════════
// Constants
// ════════════════════════════════════════════════════════════════

// Default per-carousel event cap. Raised from 12 → 25 because the
// previous cap, combined with cross-rail deduplication, made topical
// rails look starved when the underlying DB had plenty of matches. 25
// covers several horizontal swipes while still leaving the "See all"
// sheet for the long tail.
const CAROUSEL_DISPLAY_LIMIT = 25;

// Hero carousels (Picked For You, Buzzing Right Now) get a much larger
// budget. These are the primary discovery surface — users actually swipe
// deep into them, so capping at 12 felt sparse. The Picked For You cap
// drives the diversity logic in rankForColdStart, so increasing this also
// gives the diversity gate room to surface 5+ distinct categories.
const HERO_CAROUSEL_DISPLAY_LIMIT = 40;
const MIN_CAROUSEL_SIZE = 15;
const METRO_CENTER_LAT = 42.3601;
const METRO_CENTER_LNG = -71.0589;

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
};

// ── Hardcoded lists (ported from Kotlin) ──

const proTeams = ["celtics", "bruins", "red sox", "patriots", "revolution"];

const premiumVenues = [
  "td garden", "fenway park", "gillette stadium",
  "house of blues", "boch center", "wang theatre", "shubert theatre",
  "orpheum theatre", "emerson colonial", "wilbur",
  "big night live", "leader bank pavilion", "mgm music hall",
];

const bostonArea = new Set([
  "boston", "cambridge", "somerville", "brookline", "medford",
  "everett", "chelsea", "allston", "brighton", "jamaica plain",
  "charlestown", "dorchester", "roxbury", "south boston",
  "chestnut hill", "newton",
]);

const knownSubcategories = [
  "music", "sports", "arts", "theatre", "comedy", "food",
  "outdoors", "museums", "tours", "libraries", "for-kids",
];

const miscategorizedVenues: Record<string, string> = {
  "the center for arts in natick": "comedy",
  "cabot theatre": "music",
};

const filmTitlePatterns = [
  "screening", "matinee", "oscar nominated", "film screening",
  "movie night", "movie club", "cinematics",
];

const knownFilmTitles = [
  "sinners", "marty supreme", "life of pi", "blue velvet",
  "annie laurie", "the shining", "pink panther", "happyend",
  "all we imagine as light", "the river", "the mission",
  "the last waltz",
];

const musicTitlePatterns = [
  "guitar", "trio", "quartet", "quintet", "symphony", "orchestra",
  "band", "performs", "unplugged", "concert", "tenors", "singers",
  "jazz", "blues", "country", "folk", "rock", "punk", "hip hop",
  "dj ", "live music",
];

const knownMusicActs = [
  "california guitar trio", "jake shimabukuro", "kathy mattea",
  "quinn sullivan", "chuck prophet", "tinsley ellis", "davy knowles",
  "mary fahl", "altan", "stick men", "antje duvekot", "david berkeley",
  "international guitar night", "celtic tenors", "masters of the telecaster",
  "shane guerrette", "chambers deslauriers",
];

const governmentPatterns = [
  "abutters meeting", "community meeting", "office hours",
  "berdo", "virtual legal clinic", "mock class",
  "mejora tu credito", "restore boston",
];

const weeklyKeywords = [
  "weekly", "every week", "every monday", "every tuesday", "every wednesday",
  "every thursday", "every friday", "every saturday", "every sunday",
  "monday night", "tuesday night", "wednesday night", "thursday night",
  "friday night", "saturday night", "sunday night",
  "trivia night", "open mic", "karaoke night", "game night",
  "happy hour", "ladies night", "wing night", "quiz night",
];

const monthlyKeywords = [
  "monthly", "every month", "first friday", "first saturday", "first sunday",
  "last friday", "last saturday", "second saturday", "third thursday",
  "once a month",
];

const minorSportsPatterns = [
  "river hawks", "wildcats", "minutemen", "eagles hockey",
  "mountain kings", "nordiques", "mariners vs", "thunder vs",
  "big east", "ncaa", "college",
];

interface SportsCarouselDef {
  slug: string;
  title: string;
  subSubcategory: string;
}

const sportTypes: SportsCarouselDef[] = [
  { slug: "sports-hockey", title: "Hockey", subSubcategory: "hockey" },
  { slug: "sports-basketball", title: "Basketball", subSubcategory: "basketball" },
  { slug: "sports-baseball", title: "Baseball", subSubcategory: "baseball" },
  { slug: "sports-football", title: "Football", subSubcategory: "football" },
  { slug: "sports-soccer", title: "Soccer", subSubcategory: "soccer" },
  { slug: "sports-college", title: "College Sports", subSubcategory: "college-sports" },
  { slug: "sports-minor-league", title: "Minor League", subSubcategory: "minor-league" },
];

interface CityCoord {
  name: string;
  lat: number;
  lng: number;
}

const knownCities: CityCoord[] = [
  { name: "Boston", lat: 42.3601, lng: -71.0589 },
  { name: "Cambridge", lat: 42.3736, lng: -71.1097 },
  { name: "Somerville", lat: 42.3876, lng: -71.0995 },
  { name: "Brookline", lat: 42.3318, lng: -71.1212 },
  { name: "Waltham", lat: 42.3765, lng: -71.2356 },
  { name: "Worcester", lat: 42.2626, lng: -71.8023 },
  { name: "Springfield", lat: 42.1015, lng: -72.5898 },
  { name: "Lowell", lat: 42.6334, lng: -71.3162 },
  { name: "New Bedford", lat: 41.6362, lng: -70.9342 },
  { name: "Pittsfield", lat: 42.4501, lng: -73.2454 },
  { name: "Northampton", lat: 42.3251, lng: -72.6412 },
  { name: "Salem", lat: 42.5195, lng: -70.8967 },
  { name: "Cape Cod", lat: 41.6688, lng: -70.2962 },
  { name: "Providence", lat: 41.8240, lng: -71.4128 },
  { name: "New York", lat: 40.7128, lng: -74.0060 },
  { name: "Philadelphia", lat: 39.9526, lng: -75.1652 },
  { name: "Pittsburgh", lat: 40.4406, lng: -79.9959 },
  { name: "Allentown", lat: 40.6084, lng: -75.4902 },
  { name: "Harrisburg", lat: 40.2732, lng: -76.8839 },
  { name: "Natick", lat: 42.2833, lng: -71.3490 },
  { name: "Framingham", lat: 42.2793, lng: -71.4162 },
  { name: "Newton", lat: 42.3370, lng: -71.2092 },
  { name: "Quincy", lat: 42.2529, lng: -71.0023 },
];

const categoryDisplayNames: Record<string, string> = {
  music: "Music",
  arts: "Arts",
  theatre: "Theatre",
  food: "Food & Drink",
  comedy: "Comedy",
  sports: "Sports",
  museums: "Museum",
  tours: "Tour",
  libraries: "Library",
  outdoors: "Outdoors",
  "for-kids": "Kids",
  experiences: "Experiences",
  other: "Other",
};

const categoryTabDisplayNames: Record<string, string> = {
  music: "Music",
  arts: "Arts",
  theatre: "Theatre",
  food: "Food",
  comedy: "Comedy",
  sports: "Sports",
  museums: "Museums",
  tours: "Tours",
  libraries: "Libraries",
  outdoors: "Outdoors",
  nightlife: "Nightlife",
  "for-kids": "For Kids",
  experiences: "Experiences",
  other: "Other",
};

// ════════════════════════════════════════════════════════════════
// Utility functions
// ════════════════════════════════════════════════════════════════

function distanceMiles(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const earthRadiusMiles = 3958.8;
  const dLat = (lat2 - lat1) * Math.PI / 180.0;
  const dLng = (lng2 - lng1) * Math.PI / 180.0;
  const rLat1 = lat1 * Math.PI / 180.0;
  const rLat2 = lat2 * Math.PI / 180.0;
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(rLat1) * Math.cos(rLat2) * Math.sin(dLng / 2) ** 2;
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return earthRadiusMiles * c;
}

function nearestCityName(lat: number, lng: number): string {
  let best = "Boston";
  let bestDist = Infinity;
  for (const city of knownCities) {
    const dLat = city.lat - lat;
    const dLng = city.lng - lng;
    const dist = dLat * dLat + dLng * dLng;
    if (dist < bestDist) {
      bestDist = dist;
      best = city.name;
    }
  }
  return best;
}

function nowIso(): string {
  return new Date().toISOString();
}

function daysFromNowIso(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() + n);
  return d.toISOString();
}

function startOfNextFridayIso(): string {
  const now = new Date();
  const day = now.getDay(); // 0=Sun, 1=Mon, ..., 5=Fri, 6=Sat
  let daysUntilFriday: number;
  if (day === 5 || day === 6 || day === 0) {
    daysUntilFriday = 0;
  } else {
    daysUntilFriday = 5 - day;
  }
  const friday = new Date(now);
  friday.setDate(friday.getDate() + daysUntilFriday);
  friday.setHours(0, 0, 0, 0);
  return friday.toISOString();
}

function endOfNextSundayIso(): string {
  const now = new Date();
  const day = now.getDay(); // 0=Sun
  let daysUntilSunday: number;
  if (day === 0) {
    daysUntilSunday = 0;
  } else {
    daysUntilSunday = 7 - day;
  }
  // Monday midnight = day after Sunday
  const monday = new Date(now);
  monday.setDate(monday.getDate() + daysUntilSunday + 1);
  monday.setHours(0, 0, 0, 0);
  return monday.toISOString();
}

function categoryDisplayName(key: string): string {
  return categoryDisplayNames[key.toLowerCase()] || key.charAt(0).toUpperCase() + key.slice(1);
}

function subSubcategoryDisplayName(key: string): string {
  return key.split("-").map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
}

function iconUrlFor(subcategory: string): string {
  return `${SUPABASE_URL}/storage/v1/object/public/category-icons/${subcategory}.png`;
}

// ════════════════════════════════════════════════════════════════
// Event cleaning and reclassification
// ════════════════════════════════════════════════════════════════

function reclassifyEvent(event: ProcessedEvent): ProcessedEvent {
  const titleLc = event.title.toLowerCase();
  const venueLc = (event.venue_name ?? "").toLowerCase();

  // Filter out government/admin events
  if (governmentPatterns.some(p => titleLc.includes(p))) {
    return { ...event, subcategory: "other", sub_subcategory: "administrative" };
  }

  // Check if venue is known to be miscategorized
  const venueWrongCat = Object.entries(miscategorizedVenues).find(([v]) => venueLc.includes(v));

  // Film detection
  const isFilm = filmTitlePatterns.some(p => titleLc.includes(p)) ||
    knownFilmTitles.some(t => titleLc.includes(t)) ||
    /\(\d{4}\)/.test(event.title);
  if (isFilm) return { ...event, subcategory: "arts", sub_subcategory: "film" };

  // Only reclassify further if this is a known-bad venue
  if (venueWrongCat && event.subcategory === venueWrongCat[1]) {
    if (musicTitlePatterns.some(p => titleLc.includes(p)) ||
      knownMusicActs.some(a => titleLc.includes(a))) {
      return { ...event, subcategory: "music" };
    }
    if (titleLc.includes("open mic")) {
      return { ...event, subcategory: "music", sub_subcategory: "open-mic" };
    }
    if (titleLc.includes("science") || titleLc.includes("wacky")) {
      return { ...event, subcategory: "for-kids", sub_subcategory: "science-kids" };
    }
    if (titleLc.includes("comics") || titleLc.includes("comedy") ||
      titleLc.includes("improv") || titleLc.includes("stand-up") ||
      titleLc.includes("standup")) {
      return event;
    }
    if (venueLc.includes("arts") || venueLc.includes("theatre") || venueLc.includes("theater")) {
      return { ...event, subcategory: "arts" };
    }
  }

  return event;
}

function cleanTitle(event: ProcessedEvent): ProcessedEvent {
  // Don't clean curated event titles — they're hand-crafted
  if (event.source === "curated") return event;

  let cleaned = event.title;
  const pipeIdx = cleaned.indexOf(" | ");
  if (pipeIdx > 10) cleaned = cleaned.substring(0, pipeIdx);

  cleaned = cleaned.replace(/Livestream Event from /gi, "");
  cleaned = cleaned.replace(/Livestream Event /gi, "");
  cleaned = cleaned.replace(/ Livestream Event/gi, "");

  if (event.venue_name && event.venue_name.length >= 4) {
    const re = new RegExp(event.venue_name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "gi");
    const withoutVenue = cleaned.replace(re, "")
      .replace(/  +/g, " ")
      .replace(/^\s*-\s*/, "")       // leading dash from venue removal
      .replace(/\s*-\s*$/, "")       // trailing dash from venue removal
      .replace(/^\s*at\s+/i, "")     // leading "at " from venue removal
      .replace(/^\s*from\s+/i, "")   // leading "from " from venue removal
      .trim();
    if (withoutVenue.length >= 8) {
      cleaned = withoutVenue;
    }
  }

  cleaned = cleaned.trim();
  return cleaned !== event.title ? { ...event, title: cleaned } : event;
}

function cleanVenue(event: ProcessedEvent): ProcessedEvent {
  const vn = event.venue_name;
  if (!vn) return event;
  let cleaned = vn;
  cleaned = cleaned.replace(/ presented by .*/i, "");
  cleaned = cleaned.replace(/ Resort Casino.*/i, "");
  cleaned = cleaned.replace(/-NH/g, "").replace(/-CT/g, "").trim();
  return cleaned !== vn ? { ...event, venue_name: cleaned } : event;
}

function isLivestreamDuplicate(event: ProcessedEvent, allEvents: ProcessedEvent[]): boolean {
  if (!event.title.toLowerCase().includes("livestream")) return false;
  const baseName = event.title
    .replace(/Livestream Event from /gi, "")
    .replace(/Livestream Event /gi, "")
    .replace(/ Livestream Event/gi, "")
    .replace(/Livestream/gi, "")
    .trim();
  return allEvents.some(other =>
    other.id !== event.id &&
    !other.title.toLowerCase().includes("livestream") &&
    other.title.toLowerCase().includes(baseName.toLowerCase())
  );
}

function baseTitle(title: string): string {
  return title.toLowerCase()
    .replace(/\(.*?\)/g, "")
    .replace(/\s+/g, " ")
    .trim();
}

function distinctByBaseTitle(events: ProcessedEvent[]): ProcessedEvent[] {
  const seen = new Set<string>();
  return events.filter(event => {
    const key = baseTitle(event.title);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function isGovernmentNoise(event: ProcessedEvent): boolean {
  return event.subcategory === "other" && event.sub_subcategory === "administrative";
}

function cleanEvents(
  events: ProcessedEvent[],
  venueCache: Map<string, VenueInfo>,
): ProcessedEvent[] {
  const reclassified = events.map(e => cleanVenue(cleanTitle(reclassifyEvent(e))));
  const filtered = reclassified
    .filter(e => !isGovernmentNoise(e))
    .filter(e => e.subcategory !== "no-public-interest")
    // Global minimum-quality floor — hide events whose venue has
    // public_interest_rank <= 10 (community centers, K-12 athletic
    // fields, junk listings). Applied BEFORE carousel assembly so
    // bottom-tier venues don't pollute any surface.
    .filter(e => {
      const info = venueCache.get((e.venue_name ?? "").toLowerCase());
      return info?.rank == null || info.rank > 10;
    })
    .filter(e => !isLivestreamDuplicate(e, reclassified));
  const deduped = distinctByBaseTitle(filtered);
  // Apply venue photo fallback and venue description.
  //
  // Two things happen here:
  //  1. If event.image_url is null, collapse venue photo INTO image_url
  //     (legacy backstop — old clients without venue_photo_url support
  //     still see the venue photo on cards/detail).
  //  2. Always populate venue_photo_url on the response if we can find
  //     one in the cache. New clients use this as a last-resort fallback
  //     before the category gradient, covering the edge case where
  //     image_url is set but to a stale/broken URL that fails to load.
  return deduped.map(e => {
    const venueLc = (e.venue_name ?? "").toLowerCase();
    const info = venueCache.get(venueLc);
    let result = e;
    if (info?.photo_url && !result.venue_photo_url) {
      result = { ...result, venue_photo_url: info.photo_url };
    }
    if (!result.image_url && info?.photo_url) {
      result = { ...result, image_url: info.photo_url };
    }
    // Google Places photo URLs expire (return HTTP 400 once the embedded
    // photo-reference token is invalidated — usually within hours of
    // generation). When we have a stable Supabase-storage venue photo,
    // swap. This catches events that were backfilled with a Places URL
    // before the venues.photo_url column was migrated to Supabase storage.
    if (result.image_url?.includes("places.googleapis.com") && result.venue_photo_url) {
      result = { ...result, image_url: result.venue_photo_url };
    }
    if (info?.description) {
      result = { ...result, venue_description: info.description };
    }
    return result;
  });
}

// ════════════════════════════════════════════════════════════════
// Cross-source deduplication
// ════════════════════════════════════════════════════════════════

function deduplicateCrossSource(events: ProcessedEvent[]): ProcessedEvent[] {
  interface DedupeKey { venue: string; date: string; }

  function normalizeVenue(name: string): string {
    return name.toLowerCase().trim()
      .replace(/,.*/, "")
      .replace(/^the\s+/, "")
      .replace(/\s+(theatre|theater|hall|center|arena|club|room|lounge|stage|pavilion|amphitheater|amphitheatre)$/i, "")
      .replace(/\s+/g, " ")
      .trim();
  }

  function dedupeKey(e: ProcessedEvent): string {
    const venue = normalizeVenue(e.venue_name ?? "");
    const date = (e.start_at ?? "").substring(0, 10);
    return `${venue}||${date}`;
  }

  function titleWords(title: string): Set<string> {
    const filler = new Set(["the", "at", "in", "of", "and", "vs", "vs.", "for", "a", "an"]);
    return new Set(
      title.toLowerCase()
        .replace(/[^a-z0-9\s]/g, " ")
        .split(/\s+/)
        .filter(w => w.length >= 3 && !filler.has(w))
    );
  }

  function titlesMatch(a: string, b: string): boolean {
    const wordsA = titleWords(a);
    const wordsB = titleWords(b);
    if (wordsA.size === 0 || wordsB.size === 0) return false;
    let overlap = 0;
    for (const w of wordsA) {
      if (wordsB.has(w)) overlap++;
    }
    const smaller = Math.min(wordsA.size, wordsB.size);
    return overlap / smaller >= 0.5;
  }

  const grouped = new Map<string, ProcessedEvent[]>();
  for (const e of events) {
    const key = dedupeKey(e);
    const group = grouped.get(key);
    if (group) group.push(e);
    else grouped.set(key, [e]);
  }

  const result: ProcessedEvent[] = [];
  for (const [, group] of grouped) {
    if (group.length === 1) {
      result.push(group[0]);
      continue;
    }
    const used = new Array(group.length).fill(false);
    for (let i = 0; i < group.length; i++) {
      if (used[i]) continue;
      let best = group[i];
      for (let j = i + 1; j < group.length; j++) {
        if (used[j]) continue;
        if (titlesMatch(group[i].title, group[j].title)) {
          used[j] = true;
          if (group[j].source === "ticketmaster" && best.source !== "ticketmaster") {
            best = group[j];
          }
        }
      }
      result.push(best);
    }
  }
  return result;
}

// ════════════════════════════════════════════════════════════════
// Distance computation
// ════════════════════════════════════════════════════════════════

function withDistance(event: ProcessedEvent, userLat: number, userLng: number): ProcessedEvent {
  if (event.venue_lat == null || event.venue_lng == null) return event;
  return {
    ...event,
    distance_miles: distanceMiles(userLat, userLng, event.venue_lat, event.venue_lng),
  };
}

// ════════════════════════════════════════════════════════════════
// Scoring
// ════════════════════════════════════════════════════════════════

function weightsForUser(userLat: number, userLng: number, radiusMiles: number): ScoreWeights {
  const milesFromMetro = distanceMiles(userLat, userLng, METRO_CENTER_LAT, METRO_CENTER_LNG);
  let proxWeight: number;
  let decay: number;
  if (milesFromMetro <= 12.0) { proxWeight = 0.25; decay = 6.0; }
  else if (milesFromMetro <= 25.0) { proxWeight = 0.40; decay = 3.5; }
  else if (milesFromMetro <= 45.0) { proxWeight = 0.55; decay = 2.0; }
  else { proxWeight = 0.65; decay = 1.0; }

  const effectiveRadius = Math.max(radiusMiles, milesFromMetro + 25.0);
  const remaining = 1.0 - proxWeight;
  return {
    relevance: remaining * (40.0 / 80.0),
    venueRank: remaining * (25.0 / 80.0),
    proximity: proxWeight,
    date: remaining * (15.0 / 80.0),
    decayDivisor: decay,
    maxEventRadius: effectiveRadius,
  };
}

function startDateProximityScore(startAt: string | null): number {
  if (!startAt) return 0.3;
  try {
    const eventTime = new Date(startAt).getTime();
    const now = Date.now();
    const daysAway = Math.max(0, (eventTime - now) / (1000 * 60 * 60 * 24));
    return 1.0 / (1.0 + daysAway / 7.0);
  } catch {
    return 0.3;
  }
}

function coldStartScore(
  event: ProcessedEvent,
  prefs: UserPreferences | null,
  affinityMap: Map<string, number>,
): number {
  let score = 0.5;
  const titleLc = event.title.toLowerCase();
  const venueLc = (event.venue_name ?? "").toLowerCase();
  const cityLc = event.venue_city.toLowerCase();

  // Pro-sports nudge — used to be +0.35 (a 70 % boost over base 0.5) which
  // pushed Celtics/Bruins/Red Sox to the top of every "Picked for You"
  // carousel and crowded out arts/music/community/family events. Pro sports
  // already win on venue rank (TD Garden / Fenway are rank 100) and
  // community signals; an explicit content boost on top double-counts.
  // Keeping a small +0.10 so they still bias upward against truly low-rank
  // events but no longer dominate.
  if (proTeams.some(t => titleLc.includes(t))) score += 0.10;
  if (premiumVenues.some(v => venueLc.includes(v))) score += 0.2;

  score += bostonArea.has(cityLc) ? 0.15 : -0.15;

  if (titleLc.includes("livestream")) score -= 0.6;
  if (minorSportsPatterns.some(p => titleLc.includes(p))) score -= 0.2;
  if (event.is_free) score += 0.05;
  if (event.image_url) score += 0.05;

  if (prefs) {
    if (event.subcategory && prefs.selected_categories.includes(event.subcategory)) score += 0.25;
    if (prefs.selected_venues.some(v => venueLc.includes(v.toLowerCase()))) score += 0.30;
    if (prefs.selected_neighborhoods.some(n => cityLc.includes(n.toLowerCase()))) score += 0.10;
  }

  if (affinityMap.size > 0) {
    const eventSubcat = event.subcategory?.toLowerCase() ?? "";
    const eventCat = event.category.toLowerCase();
    const affinityScore = affinityMap.get(eventSubcat) ?? affinityMap.get(eventCat) ?? 0.0;
    score += affinityScore * 0.25;
  }

  return score;
}

function scoreForSort(
  event: ProcessedEvent,
  prefs: UserPreferences | null,
  weights: ScoreWeights,
  venueCache: Map<string, VenueInfo>,
  communityScores: Map<string, CommunityScore>,
  affinityMap: Map<string, number>,
): number {
  const relevance = coldStartScore(event, prefs, affinityMap);
  const distance = event.distance_miles ?? 50.0;
  const proximity = 1.0 / (1.0 + distance / weights.decayDivisor);

  const venueLc = (event.venue_name ?? "").toLowerCase();
  const venueRank = venueCache.get(venueLc)?.rank ?? 30;
  const rankScore = venueRank / 100.0;

  const dateScore = startDateProximityScore(event.start_at);

  const cs = communityScores.get(event.id);
  const personalRaw = cs
    ? (cs.personal_clicks * 1.0 + cs.personal_favorites * 2.0 + cs.personal_shares * 4.0 + cs.personal_plan_adds * 6.0)
    : 0;
  const communityRaw = cs
    ? (cs.community_clicks * 1.0 + cs.community_favorites * 2.0 + cs.community_shares * 4.0 + cs.community_plan_adds * 6.0)
    : 0;
  const personalBonus = Math.min(personalRaw, 1.0) * 0.20;
  const communityBonus = Math.min(communityRaw, 1.0) * 0.10;

  const w = weights;
  return -(relevance * w.relevance + rankScore * w.venueRank + proximity * w.proximity + dateScore * w.date + personalBonus + communityBonus);
}

function totalSaves(id: string, communityScores: Map<string, CommunityScore>): number {
  const cs = communityScores.get(id);
  if (!cs) return 0;
  return cs.personal_favorites + cs.personal_plan_adds + cs.community_favorites + cs.community_plan_adds;
}

// ════════════════════════════════════════════════════════════════
// Cold-start ranking (diversity-aware)
// ════════════════════════════════════════════════════════════════

function isSimilarEvent(a: ProcessedEvent, b: ProcessedEvent): boolean {
  if ((a.subcategory ?? "") !== (b.subcategory ?? "")) return false;
  const venueA = (a.venue_name ?? "").toLowerCase().trim();
  const venueB = (b.venue_name ?? "").toLowerCase().trim();
  if (venueA.length > 0 && venueA === venueB) return true;

  const filler = new Set(["the", "at", "in", "of", "and", "vs", "vs.", "for", "a", "an", "to"]);
  function words(title: string): Set<string> {
    return new Set(
      title.toLowerCase()
        .replace(/[^a-z0-9\s]/g, " ")
        .split(/\s+/)
        .filter(w => w.length >= 3 && !filler.has(w))
    );
  }
  const wordsA = words(a.title);
  const wordsB = words(b.title);
  if (wordsA.size === 0 || wordsB.size === 0) return false;
  let overlap = 0;
  for (const w of wordsA) { if (wordsB.has(w)) overlap++; }
  return overlap >= 2;
}

function rankForColdStart(
  events: ProcessedEvent[],
  prefs: UserPreferences | null,
  maxResults: number,
  affinityMap: Map<string, number>,
): ProcessedEvent[] {
  const scored = events.map(e => ({
    event: e,
    score: coldStartScore(e, prefs, affinityMap),
  })).sort((a, b) => b.score - a.score);

  const result: ProcessedEvent[] = [];
  const deferred: typeof scored = [];
  const categoryCounts = new Map<string, number>();
  const topLevelCounts = new Map<string, number>();

  // Cap any single top-level category at ~25 % of the carousel (rounded up).
  // Previously the only diversity gate was per-subcategory (basketball,
  // hockey, baseball, etc., each capped at 3) — so Celtics + Bruins + Red
  // Sox + Patriots + Revolution could collectively fill 15 of 30 slots.
  // 25 % keeps a healthy mix of music / arts / food / community alongside
  // sports without nuking the carousel for sports-heavy users.
  const topLevelCap = Math.max(2, Math.ceil(maxResults * 0.25));
  // Additional first-impression rule: the first 3 slots must each be a
  // different top-level category. Prevents the carousel opening with
  // "Celtics + Red Sox + Bruins" — the strongest reason it currently
  // feels sports-dominated even before scrolling.
  const FIRST_N_DISTINCT = 3;

  for (const pair of scored) {
    if (result.length >= maxResults) break;
    const subcat = pair.event.subcategory ?? "other";
    const cat = pair.event.category ?? "other";
    const catCount = categoryCounts.get(subcat) ?? 0;
    const topCount = topLevelCounts.get(cat) ?? 0;
    if (catCount >= 3) continue;
    if (topCount >= topLevelCap) continue;
    // First-N-distinct: defer events whose top-level category already
    // appears in result while we're still filling the leading slots.
    if (result.length < FIRST_N_DISTINCT && topCount >= 1) {
      deferred.push(pair);
      continue;
    }
    if (result.length > 0 && isSimilarEvent(pair.event, result[result.length - 1])) {
      deferred.push(pair);
      continue;
    }
    result.push(pair.event);
    categoryCounts.set(subcat, catCount + 1);
    topLevelCounts.set(cat, topCount + 1);
  }

  for (const pair of deferred) {
    if (result.length >= maxResults) break;
    const subcat = pair.event.subcategory ?? "other";
    const cat = pair.event.category ?? "other";
    const catCount = categoryCounts.get(subcat) ?? 0;
    const topCount = topLevelCounts.get(cat) ?? 0;
    if (catCount >= 3) continue;
    if (topCount >= topLevelCap) continue;
    // Find the lowest insertion position that doesn't violate the first-N
    // distinct-category rule. If this event's category already appears in
    // result[0..FIRST_N_DISTINCT-1], the earliest valid position is
    // FIRST_N_DISTINCT (so the leading slots stay diverse).
    let earliestAllowed = 1;
    if (result.length >= 1) {
      const headSlots = result.slice(0, Math.min(FIRST_N_DISTINCT, result.length));
      if (headSlots.some(e => (e.category ?? "other") === cat)) {
        earliestAllowed = FIRST_N_DISTINCT;
      }
    }
    let inserted = false;
    for (let i = Math.max(1, earliestAllowed); i <= result.length - 1; i++) {
      const prevSimilar = isSimilarEvent(pair.event, result[i - 1]);
      const nextSimilar = isSimilarEvent(pair.event, result[i]);
      if (!prevSimilar && !nextSimilar) {
        result.splice(i, 0, pair.event);
        categoryCounts.set(subcat, catCount + 1);
        topLevelCounts.set(cat, topCount + 1);
        inserted = true;
        break;
      }
    }
    if (!inserted && result.length >= earliestAllowed && (result.length === 0 || !isSimilarEvent(pair.event, result[result.length - 1]))) {
      result.push(pair.event);
      categoryCounts.set(subcat, catCount + 1);
      topLevelCounts.set(cat, topCount + 1);
    }
  }

  return result;
}

// ════════════════════════════════════════════════════════════════
// Recurring event detection
// ════════════════════════════════════════════════════════════════

function isWeeklyEvent(event: ProcessedEvent): boolean {
  const text = `${event.title} ${event.description ?? ""}`.toLowerCase();
  return weeklyKeywords.some(k => text.includes(k));
}

function isMonthlyEvent(event: ProcessedEvent): boolean {
  const text = `${event.title} ${event.description ?? ""}`.toLowerCase();
  return monthlyKeywords.some(k => text.includes(k));
}

function isDailyEvent(event: ProcessedEvent): boolean {
  return event.category === "always_on" && !isWeeklyEvent(event) && !isMonthlyEvent(event);
}

// ════════════════════════════════════════════════════════════════
// Affinity carousel titles
// ════════════════════════════════════════════════════════════════

function neighborhoodTitle(name: string, index: number): string {
  const titles = [
    `The ${name} Scene`,
    `Over in ${name}`,
    `What's On in ${name}`,
    `${name} Highlights`,
    `Out & About in ${name}`,
    `Happening in ${name}`,
    `${name} This Week`,
    `Explore ${name}`,
  ];
  return titles[index % titles.length];
}

function favCategoryTitle(cat: string, index: number): string {
  const name = categoryDisplayName(cat);
  const catLc = cat.toLowerCase();
  if (catLc === "music" || catLc === "live-music") {
    const titles = ["Your Music Radar", "On Your Wavelength", `${name} You'll Love`, `Don't Miss This ${name}`];
    return titles[index % titles.length];
  }
  if (catLc === "comedy") {
    const titles = ["Get Ready to Laugh", "Comedy Calling", "Your Comedy Picks", "LOL-Worthy Events"];
    return titles[index % titles.length];
  }
  if (catLc === "food" || catLc === "food-drink") {
    const titles = ["For Foodies Like You", "Tasty Events Nearby", "Bites & Sips", "Your Food Scene"];
    return titles[index % titles.length];
  }
  if (catLc === "arts" || catLc === "performing-arts") {
    const titles = ["Your Arts Picks", "Culture Calling", `${name} Worth Seeing`, "Artsy & Awesome"];
    return titles[index % titles.length];
  }
  if (catLc === "sports") {
    const titles = ["Game Day Picks", "Your Sports Calendar", "Don't Miss the Game", "Sports Lineup"];
    return titles[index % titles.length];
  }
  if (catLc === "for-kids" || catLc === "family") {
    const titles = ["Fun for the Whole Family", "Kid-Approved Events", "Family Day Out", "Bring the Kids"];
    return titles[index % titles.length];
  }
  if (catLc === "outdoors") {
    const titles = ["Get Outside", "Fresh Air Finds", "Outdoor Adventures", "Nature Calling"];
    return titles[index % titles.length];
  }
  const titles = [`Your ${name} Picks`, `${name} You'll Love`, `${name} This Month`, `More ${name}`];
  return titles[index % titles.length];
}

function affinityCarouselTitle(cat: string, index: number): string {
  const name = categoryDisplayName(cat);
  const catLc = cat.toLowerCase();
  if (catLc === "for-kids") {
    const titles = [
      "Events For Kids", "Family-Friendly Picks", "Fun For The Whole Family",
      "Kid-Approved Events", "Bring The Kids", "More Family Events",
    ];
    return titles[index % 6];
  }
  if (catLc === "food") {
    const titles = [
      "For Foodies Like You", "Tasty Events Nearby", `${name} Events For You`,
      "Don't Miss These Bites", "Food Events This Week", `More ${name} Events`,
    ];
    return titles[index % 6];
  }
  const titles = [
    `For ${name} Lovers`, `${name} Picks For You`, `More ${name} Events`,
    `Top ${name} This Week`, `${name} You Might Like`, `Trending in ${name}`,
  ];
  return titles[index % 6];
}

// ════════════════════════════════════════════════════════════════
// Filter resolution
// ════════════════════════════════════════════════════════════════

type EventFilterType =
  | { type: "none" }
  | { type: "empty" }
  | { type: "category"; value: string }
  | { type: "source"; value: string }
  | { type: "exclude_known" };

function resolveFilter(category: string | null): EventFilterType {
  if (!category || category.toLowerCase() === "foryou") return { type: "none" };
  const lc = category.toLowerCase();
  if (lc === "experiences") return { type: "source", value: "curated" };
  if (lc === "nightlife") return { type: "category", value: "nightlife" };
  if (knownSubcategories.includes(lc)) return { type: "category", value: lc };
  if (parentCategories.has(lc)) return { type: "category", value: lc };
  if (lc === "locations") return { type: "none" };
  if (lc === "other") return { type: "exclude_known" };
  return { type: "category", value: lc };
}

function applyFilter(events: ProcessedEvent[], filter: EventFilterType): ProcessedEvent[] {
  switch (filter.type) {
    case "none": return events;
    case "empty": return [];
    case "category": {
      const filterValue = filter.value;
      // If filtering by a parent category, include events whose subcategory maps to it
      if (parentCategories.has(filterValue)) {
        return events.filter(e => {
          const subcat = (e.subcategory ?? "").toLowerCase();
          if (subcat === filterValue) return true;
          return (subcategoryToParent[subcat] ?? "") === filterValue;
        });
      }
      return events.filter(e => (e.subcategory ?? "").toLowerCase() === filterValue);
    }
    case "source":
      return events.filter(e => e.source === filter.value);
    case "exclude_known":
      return events.filter(e => !knownSubcategories.includes((e.subcategory ?? "other").toLowerCase()));
  }
}

// ════════════════════════════════════════════════════════════════
// Carousel building helpers
// ════════════════════════════════════════════════════════════════

function backfillToMinimum(
  events: ProcessedEvent[],
  seenEventIds: Set<string>,
  fillerPool: ProcessedEvent[],
  targetSize: number = MIN_CAROUSEL_SIZE,
): ProcessedEvent[] {
  if (events.length >= targetSize) return events;
  if (events.length === 0) return [];
  const result = [...events];
  const usedIds = new Set([...seenEventIds, ...result.map(e => e.id)]);
  // Mirror the top-level category cap from rankForColdStart so backfill
  // doesn't quietly let pro sports flood the carousel after the diversity
  // pass. Cap scales with target size (~25 %).
  const topLevelCounts = new Map<string, number>();
  for (const e of result) {
    const c = e.category ?? "other";
    topLevelCounts.set(c, (topLevelCounts.get(c) ?? 0) + 1);
  }
  const topLevelCap = Math.max(2, Math.ceil(targetSize * 0.25));

  for (const filler of fillerPool) {
    if (result.length >= targetSize) break;
    if (usedIds.has(filler.id)) continue;
    const cat = filler.category ?? "other";
    const topCount = topLevelCounts.get(cat) ?? 0;
    if (topCount >= topLevelCap) continue;
    result.push(filler);
    usedIds.add(filler.id);
    topLevelCounts.set(cat, topCount + 1);
  }
  // Second pass: if we still couldn't reach the target size under the cap
  // (sparse-data case), allow uncapped fill so users don't see a
  // half-empty carousel.
  if (result.length < targetSize) {
    for (const filler of fillerPool) {
      if (result.length >= targetSize) break;
      if (!usedIds.has(filler.id)) {
        result.push(filler);
        usedIds.add(filler.id);
      }
    }
  }
  return result;
}

function buildDynamicSubcarousels(
  events: ProcessedEvent[],
  subcategory: string,
  displayName: string,
): CarouselSection[] {
  const result: CarouselSection[] = [];

  function addCarousel(slug: string, title: string, items: ProcessedEvent[]) {
    const capped = items.slice(0, CAROUSEL_DISPLAY_LIMIT);
    if (capped.length < 4) return;
    result.push({ slug, title, subtitle: null, filter_type: "dynamic_sub", events: capped });
  }

  // Time-based dimension — skip "Coming Up" and "Weekend" since DB carousels cover those

  // Sub-subcategory dimension
  const bySubSub = new Map<string, ProcessedEvent[]>();
  for (const e of events) {
    const key = (e.sub_subcategory ?? "").toLowerCase();
    if (!key) continue;
    const arr = bySubSub.get(key);
    if (arr) arr.push(e);
    else bySubSub.set(key, [e]);
  }
  const sortedSubSubs = [...bySubSub.entries()].sort((a, b) => b[1].length - a[1].length);
  for (const [subSub, subSubEvents] of sortedSubSubs) {
    addCarousel(`${subcategory}-${subSub}`, subSubcategoryDisplayName(subSub), subSubEvents);
  }

  // Venue dimension (venues with 2+ events)
  const byVenue = new Map<string, ProcessedEvent[]>();
  for (const e of events) {
    if (!e.venue_name) continue;
    const key = e.venue_name.toLowerCase();
    const arr = byVenue.get(key);
    if (arr) arr.push(e);
    else byVenue.set(key, [e]);
  }
  const sortedVenues = [...byVenue.entries()]
    .filter(([, v]) => v.length >= 2)
    .sort((a, b) => b[1].length - a[1].length)
    .slice(0, 6);
  for (const [, venueEvents] of sortedVenues) {
    const venueName = venueEvents[0].venue_name!;
    addCarousel(
      `${subcategory}-venue-${venueName.toLowerCase().replace(/ /g, "-").substring(0, 30)}`,
      `At ${venueName}`,
      venueEvents,
    );
  }

  // Price dimension
  const freeEvents = events.filter(e => e.is_free);
  addCarousel(`${subcategory}-free`, `Free ${displayName} Events`, freeEvents);

  return result;
}

function buildRecurringCarousels(
  allEvents: ProcessedEvent[],
  subcategoryFilter: string | null,
  maxRadius: number,
  userLat: number,
  userLng: number,
  prefs: UserPreferences | null,
  seenEventIds: Set<string>,
  weights: ScoreWeights,
  venueCache: Map<string, VenueInfo>,
  communityScores: Map<string, CommunityScore>,
  affinityMap: Map<string, number>,
): CarouselSection[] {
  const result: CarouselSection[] = [];

  let pool = allEvents;
  if (subcategoryFilter) {
    pool = pool.filter(e => (e.subcategory ?? "").toLowerCase() === subcategoryFilter);
  }
  pool = pool
    .map(e => withDistance(e, userLat, userLng))
    .filter(e => maxRadius === Infinity || (e.distance_miles ?? 999) <= maxRadius)
    .sort((a, b) =>
      scoreForSort(a, prefs, weights, venueCache, communityScores, affinityMap) -
      scoreForSort(b, prefs, weights, venueCache, communityScores, affinityMap)
    );

  // Daily
  const dailyEvents = pool
    .filter(e => isDailyEvent(e))
    .filter(e => !seenEventIds.has(e.id))
    .slice(0, CAROUSEL_DISPLAY_LIMIT);
  if (dailyEvents.length > 0) {
    dailyEvents.forEach(e => seenEventIds.add(e.id));
    result.push({
      slug: `daily-${subcategoryFilter ?? "all"}`,
      title: `Happens Every Day`,
      subtitle: null,
      filter_type: "daily",
      events: dailyEvents,
    });
  }

  // Weekly
  const weeklyEvents = pool
    .filter(e => isWeeklyEvent(e))
    .filter(e => !seenEventIds.has(e.id))
    .slice(0, CAROUSEL_DISPLAY_LIMIT);
  if (weeklyEvents.length > 0) {
    weeklyEvents.forEach(e => seenEventIds.add(e.id));
    result.push({
      slug: `weekly-${subcategoryFilter ?? "all"}`,
      title: `Weekly Happenings`,
      subtitle: null,
      filter_type: "weekly",
      events: weeklyEvents,
    });
  }

  // Monthly
  const monthlyEvents = pool
    .filter(e => isMonthlyEvent(e))
    .filter(e => !seenEventIds.has(e.id))
    .slice(0, CAROUSEL_DISPLAY_LIMIT);
  if (monthlyEvents.length > 0) {
    monthlyEvents.forEach(e => seenEventIds.add(e.id));
    result.push({
      slug: `monthly-${subcategoryFilter ?? "all"}`,
      title: `Once a Month`,
      subtitle: null,
      filter_type: "monthly",
      events: monthlyEvents,
    });
  }

  return result;
}

function buildSportsCarousels(
  allSports: ProcessedEvent[],
  seenEventIds: Set<string>,
  venueCache: Map<string, VenueInfo>,
): CarouselSection[] {
  const result: CarouselSection[] = [];

  // Tier by sub_subcategory: pro leagues first, then college, then minor league, else other.
  const proSubs = new Set(["hockey", "basketball", "baseball", "football", "soccer"]);
  function teamTier(subSub: string | null | undefined): number {
    const s = (subSub ?? "").toLowerCase();
    if (proSubs.has(s)) return 0;
    if (s === "college-sports") return 1;
    if (s === "minor-league") return 2;
    return 3;
  }

  function slugFor(teamName: string): string {
    return "sports-team-" + teamName.toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "");
  }

  // MAX home-venue public_interest_rank across a team's events. Used as a
  // popularity proxy so Red Sox (Fenway 98) and Celtics/Bruins (TD Garden 95)
  // bubble above lesser teams. MAX (not AVG) so a team whose best venue is
  // a landmark still ranks high even if some games are at smaller venues.
  function topVenueRank(events: ProcessedEvent[]): number {
    let max = 0;
    for (const e of events) {
      const info = venueCache.get((e.venue_name ?? "").toLowerCase());
      if (info?.rank != null && info.rank > max) max = info.rank;
    }
    return max;
  }

  // 1. Team carousels — group by team_name
  const byTeam = new Map<string, ProcessedEvent[]>();
  for (const event of allSports) {
    const name = (event.team_name ?? "").trim();
    if (!name) continue;
    const bucket = byTeam.get(name) ?? [];
    bucket.push(event);
    byTeam.set(name, bucket);
  }

  const teamEntries = Array.from(byTeam.entries()).map(([name, events]) => {
    const rank = topVenueRank(events);
    return { name, events, rank };
  });
  // Pure popularity sort: highest home-venue rank wins. Tier-based grouping
  // was unreliable (MIT's team_name covered mixed-sport events so its
  // tier collapsed to pro, putting it above Red Sox). Venue rank IS the
  // popularity signal — major stadiums = major teams.
  teamEntries.sort((a, b) => {
    if (a.rank !== b.rank) return b.rank - a.rank;
    if (a.events.length !== b.events.length) return b.events.length - a.events.length;
    return a.name.localeCompare(b.name);
  });

  console.log("[sports-teams] sort by topVenueRank DESC:");
  for (const e of teamEntries) {
    console.log(`  rank=${e.rank} events=${e.events.length} name=${e.name}`);
  }

  for (const entry of teamEntries) {
    const events = entry.events.slice(0, CAROUSEL_DISPLAY_LIMIT);
    events.forEach(e => seenEventIds.add(e.id));
    result.push({
      slug: slugFor(entry.name),
      title: entry.name,
      subtitle: null,
      filter_type: "sports_team",
      events,
    });
  }

  // 2. By sport type — picks up events without a team_name. Match the sport
  // against either sub_subcategory (classified) or subcategory (unclassified
  // Ticketmaster rows where the genre still sits in subcategory).
  for (const sport of sportTypes) {
    const events = allSports
      .filter(e => e.sub_subcategory === sport.subSubcategory || e.subcategory === sport.subSubcategory)
      .filter(e => !seenEventIds.has(e.id))
      .slice(0, CAROUSEL_DISPLAY_LIMIT);
    if (events.length === 0) continue;
    events.forEach(e => seenEventIds.add(e.id));
    result.push({
      slug: sport.slug,
      title: sport.title,
      subtitle: null,
      filter_type: "sports_type",
      events,
    });
  }

  return result;
}

// ════════════════════════════════════════════════════════════════
// DB carousel resolution
// ════════════════════════════════════════════════════════════════

function resolveCarouselFromPool(
  pool: ProcessedEvent[],
  carousel: CarouselConfig,
  userFilter: EventFilterType,
  prefs: UserPreferences | null,
  affinityMap: Map<string, number>,
): ProcessedEvent[] {
  switch (carousel.filter_type) {
    case "category": {
      const carouselCat = carousel.filter_value;
      if (!carouselCat) return [];
      if (userFilter.type === "category" && userFilter.value !== carouselCat) return [];
      if (userFilter.type === "none") {
        return pool.filter(e => (e.subcategory ?? "").toLowerCase() === carouselCat);
      }
      return pool;
    }
    case "date_range": {
      if (carousel.filter_value === "this_weekend") {
        const start = startOfNextFridayIso();
        const end = endOfNextSundayIso();
        return pool.filter(e => { const s = e.start_at ?? ""; return s >= start && s <= end; });
      }
      const days = carousel.filter_value === "this_week" ? 7
        : carousel.filter_value === "next_2_weeks" ? 14
        : carousel.filter_value === "this_month" ? 30 : 7;
      const cutoff = daysFromNowIso(days);
      return pool.filter(e => (e.start_at ?? "") <= cutoff);
    }
    case "tag":
      return carousel.filter_value === "free" ? pool.filter(e => e.is_free) : pool;
    case "top_weekly": {
      const cutoff = daysFromNowIso(7);
      const weekPool = pool.filter(e => (e.start_at ?? "") <= cutoff);
      return rankForColdStart(weekPool, prefs, CAROUSEL_DISPLAY_LIMIT, affinityMap);
    }
    case "trending":
      return pool;
    default:
      return [];
  }
}

// ════════════════════════════════════════════════════════════════
// Main carousel builder
// ════════════════════════════════════════════════════════════════

function buildCarousels(
  allEvents: ProcessedEvent[],
  rawSportsEvents: ProcessedEvent[],
  carousels: CarouselConfig[],
  category: string | null,
  userLat: number,
  userLng: number,
  prefs: UserPreferences | null,
  nearestCity: string,
  weights: ScoreWeights,
  venueCache: Map<string, VenueInfo>,
  communityScores: Map<string, CommunityScore>,
  affinityMap: Map<string, number>,
): CarouselSection[] {
  const filter = resolveFilter(category);
  if (filter.type === "empty") return [];

  const maxRadius = weights.maxEventRadius;
  const sortFn = (a: ProcessedEvent, b: ProcessedEvent) =>
    scoreForSort(a, prefs, weights, venueCache, communityScores, affinityMap) -
    scoreForSort(b, prefs, weights, venueCache, communityScores, affinityMap);

  const filtered = applyFilter(allEvents, filter)
    .map(e => withDistance(e, userLat, userLng))
    .filter(e => maxRadius === Infinity || (e.distance_miles ?? 999) <= maxRadius)
    .sort(sortFn);

  const seenEventIds = new Set<string>();
  const result: CarouselSection[] = [];

  // ── Personalized carousels (only on "For You" tab) ──
  if (filter.type === "none") {
    // Build carousel pools by type, then interleave
    const monthCutoff = daysFromNowIso(31);

    // Pool: Personalized — the hero carousel. Users actually swipe deep here,
    // so we use the bigger HERO_CAROUSEL_DISPLAY_LIMIT instead of the niche
    // default. Diversity caps inside rankForColdStart scale with maxResults,
    // so a 40-slot pool naturally surfaces 5+ distinct categories.
    const weekCutoff = daysFromNowIso(7);
    const weekPool = filtered.filter(e => (e.start_at ?? "") <= weekCutoff);
    const ranked = rankForColdStart(weekPool, prefs, HERO_CAROUSEL_DISPLAY_LIMIT, affinityMap);
    const personalizedEvents = backfillToMinimum(ranked, seenEventIds, filtered, HERO_CAROUSEL_DISPLAY_LIMIT);

    // Pool: Weekend
    // Topical rails are independent of "Picked for You" — we used to dedupe
    // against the hero pool, which left "Your Weekend Lineup" with ~5 events
    // when the hero ate the rest. Now each rail just shows its top matches.
    const weekendStart = startOfNextFridayIso();
    const weekendEnd = endOfNextSundayIso();
    const weekendEvents = filtered
      .filter(e => { const s = e.start_at ?? ""; return s >= weekendStart && s < weekendEnd; });

    // Pool: Favorite categories
    const favCatCarousels: CarouselSection[] = [];
    if (prefs) {
      let favCatIdx = 0;
      for (const cat of prefs.selected_categories) {
        const catEvents = allEvents
          .filter(e => (e.subcategory ?? "").toLowerCase() === cat.toLowerCase())
          .filter(e => !e.start_at || e.start_at <= monthCutoff)
          .map(e => withDistance(e, userLat, userLng))
          .sort(sortFn)
          .slice(0, CAROUSEL_DISPLAY_LIMIT);
        if (catEvents.length === 0) continue;
        favCatCarousels.push({
          slug: `fav-cat-${cat.toLowerCase()}`,
          title: favCategoryTitle(cat, favCatIdx),
          subtitle: null,
          filter_type: "favorite_category",
          events: catEvents,
        });
        favCatIdx++;
      }
    }

    // Pool: Favorite venues
    const favVenueCarousels: CarouselSection[] = [];
    if (prefs) {
      for (const venue of prefs.selected_venues) {
        const venueEvents = allEvents
          .filter(e => (e.venue_name ?? "").toLowerCase().includes(venue.toLowerCase()))
          .map(e => withDistance(e, userLat, userLng))
          .sort(sortFn)
          .slice(0, CAROUSEL_DISPLAY_LIMIT);
        if (venueEvents.length === 0) continue;
        favVenueCarousels.push({
          slug: `fav-venue-${venue.toLowerCase().replace(/ /g, "-")}`,
          title: `Coming Up at ${venue}`,
          subtitle: null,
          filter_type: "favorite_venue",
          events: venueEvents,
        });
      }
    }

    // Pool: Neighborhoods
    const neighborhoodCarousels: CarouselSection[] = [];
    const neighborhoods: string[] = [nearestCity];
    if (prefs) {
      for (const n of prefs.selected_neighborhoods) {
        if (n.toLowerCase() !== nearestCity.toLowerCase()) neighborhoods.push(n);
      }
    }
    for (const neighborhood of neighborhoods) {
      const neighborhoodEvents = allEvents
        .filter(e => e.venue_city.toLowerCase().includes(neighborhood.toLowerCase()))
        .map(e => withDistance(e, userLat, userLng))
        .sort(sortFn)
        .slice(0, CAROUSEL_DISPLAY_LIMIT);
      if (neighborhoodEvents.length === 0) continue;
      neighborhoodCarousels.push({
        slug: `neighborhood-${neighborhood.toLowerCase().replace(/ /g, "-")}`,
        title: neighborhoodTitle(neighborhood, neighborhoodCarousels.length),
        subtitle: null,
        filter_type: "neighborhood",
        events: neighborhoodEvents,
      });
    }

    // Pool: Affinity
    const affinityCarousels: CarouselSection[] = [];
    const skipCategories = new Set(["other", "general", "unknown"]);
    const favCatSlugs = new Set(favCatCarousels.map(c => c.slug));
    const sortedAffinity = [...affinityMap.entries()].sort((a, b) => b[1] - a[1]);
    let affinityCount = 0;
    for (const [cat, score] of sortedAffinity) {
      if (score < 0.4) break;
      if (affinityCount >= 6) break;
      if (skipCategories.has(cat.toLowerCase())) continue;
      if (favCatSlugs.has(`fav-cat-${cat.toLowerCase()}`)) continue;
      const affinityEvents = allEvents
        .filter(e => (e.subcategory ?? e.category).toLowerCase() === cat.toLowerCase())
        .map(e => withDistance(e, userLat, userLng))
        .sort(sortFn)
        .slice(0, CAROUSEL_DISPLAY_LIMIT);
      if (affinityEvents.length === 0) continue;
      affinityCarousels.push({
        slug: `affinity-${cat.toLowerCase().replace(/ /g, "-")}`,
        title: affinityCarouselTitle(cat, affinityCount),
        subtitle: null,
        filter_type: "affinity",
        events: affinityEvents,
      });
      affinityCount++;
    }

    // Pool: Popular
    const popularEvents = filtered
      .filter(e => totalSaves(e.id, communityScores) > 10)
      .sort((a, b) => totalSaves(b.id, communityScores) - totalSaves(a.id, communityScores))
      .slice(0, CAROUSEL_DISPLAY_LIMIT);

    // Pool: Free
    const freeEvents = filtered.filter(e => e.is_free).slice(0, CAROUSEL_DISPLAY_LIMIT);

    // Pool: Experiences
    const experiences = allEvents
      .filter(e => e.source === "curated")
      .map(e => withDistance(e, userLat, userLng))
      .filter(e => maxRadius === Infinity || (e.distance_miles ?? 999) <= maxRadius)
      .sort(sortFn)
      .slice(0, CAROUSEL_DISPLAY_LIMIT);

    // ── Interleave: build the final ordered feed ──
    // Topical rails (weekend, popular, free, favCat, neighborhoods, affinity,
    // DB-driven rails like "The Boston Scene" / "Live Music") are independent.
    // Cross-rail dedup was the main cause of underpopulated carousels — the
    // hero would consume the best events and downstream rails would show 2–5
    // when the DB had 20+. Some overlap across rails is desired (a discovery
    // feed should feel abundant). seenEventIds remains for buildRecurringCarousels
    // and "This Month" rails which have their own internal dedup contracts.
    function addCarousel(c: CarouselSection) {
      if (c.events.length === 0) return;
      result.push(c);
    }

    // 1. Picked for You
    if (personalizedEvents.length > 0) {
      addCarousel({
        slug: "top-events-personalized",
        title: "Picked for You",
        subtitle: null,
        filter_type: "personalized",
        events: personalizedEvents,
      });
    }

    // 2. Weekend Lineup
    if (weekendEvents.length > 0) {
      addCarousel({
        slug: "plan-your-weekend",
        title: "Your Weekend Lineup",
        subtitle: null,
        filter_type: "weekend",
        events: weekendEvents.slice(0, CAROUSEL_DISPLAY_LIMIT),
      });
    }

    // 3+. Interleave remaining pools — never two of the same type in a row
    const pools = {
      favCat: [...favCatCarousels],
      neighborhood: [...neighborhoodCarousels],
      favVenue: [...favVenueCarousels],
      affinity: [...affinityCarousels],
    };

    // Desired interleave order pattern
    const interleavePattern = [
      "favCat", "neighborhood", "popular", "favCat", "favVenue", "free",
      "affinity", "neighborhood", "affinity", "favCat", "experiences",
      "affinity", "favVenue", "affinity", "neighborhood", "affinity", "favCat",
    ];

    for (const slot of interleavePattern) {
      if (slot === "popular" && popularEvents.length > 0) {
        addCarousel({
          slug: "popular-events",
          title: "Buzzing Right Now",
          subtitle: null,
          filter_type: "popular",
          events: popularEvents,
          show_saves: true,
        });
      } else if (slot === "free" && freeEvents.length > 0) {
        const freeFiltered = freeEvents.slice(0, CAROUSEL_DISPLAY_LIMIT);
        if (freeFiltered.length > 0) {
          result.push({
            slug: "free-events",
            title: "No Cover, No Problem",
            subtitle: null,
            filter_type: "free",
            events: freeFiltered,
          });
        }
      } else if (slot === "experiences" && experiences.length > 0) {
        addCarousel({
          slug: "experiences",
          title: "Things to Do Anytime",
          subtitle: null,
          filter_type: "experiences",
          events: experiences,
        });
      } else if (slot in pools) {
        const pool = pools[slot as keyof typeof pools];
        if (pool.length > 0) {
          addCarousel(pool.shift()!);
        }
      }
    }

    // Interleave any remaining — round-robin across pools so no type clusters
    let hasMore = true;
    while (hasMore) {
      hasMore = false;
      for (const key of Object.keys(pools)) {
        const pool = pools[key as keyof typeof pools];
        if (pool.length > 0) {
          addCarousel(pool.shift()!);
          hasMore = true;
        }
      }
    }

    // Daily / Weekly / Monthly (recurring — last)
    result.push(...buildRecurringCarousels(
      allEvents, null, maxRadius, userLat, userLng, prefs, seenEventIds,
      weights, venueCache, communityScores, affinityMap,
    ));
  }

  // Sports-specific carousels (raw uncleaned events)
  if (filter.type === "category" && filter.value === "sports") {
    console.log(`Sports filter: rawSportsEvents=${rawSportsEvents.length}, seenIds=${seenEventIds.size}`);
    const sportsCarousels = buildSportsCarousels(rawSportsEvents, seenEventIds, venueCache);
    console.log(`Sports carousels built: ${sportsCarousels.length} carousels, ${sportsCarousels.map(c => `${c.title}(${c.events.length})`).join(", ")}`);
    result.push(...sportsCarousels);
  }

  // Standard DB-driven carousels
  const standardCarousels: CarouselSection[] = [];
  for (const carousel of carousels) {
    if (carousel.filter_type === "category" && filter.type !== "none" && filter.type !== "category") {
      continue;
    }
    if (carousel.filter_type === "date_range" && filter.type === "category") {
      continue;
    }
    // Skip "This Weekend" on the For You tab — it duplicates the hardcoded
    // "Your Weekend Lineup" rail. (On category tabs the title gets rewritten
    // to "<Category> This Weekend" below, which is meaningfully different.)
    if (filter.type === "none" && carousel.title.trim().toLowerCase() === "this weekend") {
      continue;
    }
    const events = resolveCarouselFromPool(filtered, carousel, filter, prefs, affinityMap)
      .slice(0, CAROUSEL_DISPLAY_LIMIT);
    if (events.length === 0) continue;

    let title = carousel.title;
    if (filter.type === "category") {
      const catName = categoryDisplayName(filter.value);
      // The category is implied by the page the user is on, so titles on
      // category tabs render bare ("Top Events This Week", not "Kids: Top
      // Events This Week"). Kept "This Weekend" / "Trending Now" qualified
      // because they originally were — flag if those should also strip.
      title = title
        .replace("This Weekend", `${catName} This Weekend`)
        .replace("Trending Now", `Trending ${catName}`);
    }

    standardCarousels.push({
      slug: carousel.slug,
      title,
      subtitle: carousel.subtitle,
      filter_type: carousel.filter_type,
      events,
    });
  }
  result.push(...standardCarousels);

  // Dynamic sub-carousels for subcategory tab
  if (filter.type === "category" && filter.value !== "sports") {
    result.push(...buildDynamicSubcarousels(
      filtered, filter.value, categoryDisplayName(filter.value),
    ));
  }

  // Experiences tab dynamic sub-carousels
  if (filter.type === "source") {
    result.push(...buildDynamicSubcarousels(filtered, "experiences", "Experiences"));
  }

  // Daily / Weekly / Monthly on subcategory tabs
  if (filter.type === "category") {
    result.push(...buildRecurringCarousels(
      allEvents, filter.value, maxRadius, userLat, userLng, prefs, seenEventIds,
      weights, venueCache, communityScores, affinityMap,
    ));
  }

  // Subcategory "This Month" carousel
  if (filter.type === "category") {
    const displayName = categoryDisplayName(filter.value);
    const cutoff = daysFromNowIso(30);
    const topEvents = filtered
      .filter(e => (e.start_at ?? "") <= cutoff && !seenEventIds.has(e.id))
      .slice(0, CAROUSEL_DISPLAY_LIMIT);
    if (topEvents.length > 0) {
      topEvents.forEach(e => seenEventIds.add(e.id));
      result.push({
        slug: `top-${filter.value}-month`,
        title: `${displayName} Events This Month`,
        subtitle: null,
        filter_type: "top_category_monthly",
        events: topEvents,
      });
    }
  }

  return result;
}

// ════════════════════════════════════════════════════════════════
// Category discovery
// ════════════════════════════════════════════════════════════════

// Map granular subcategories to their parent category
const subcategoryToParent: Record<string, string> = {
  // Sports sub-subcategories
  hockey: "sports", basketball: "sports", baseball: "sports", football: "sports",
  soccer: "sports", "college-sports": "sports", "minor-league": "sports",
  // Music sub-subcategories
  jazz: "music", rock: "music", "hip-hop": "music", classical: "music",
  folk: "music", country: "music", "open-mic": "music", electronic: "music",
  indie: "music", punk: "music", blues: "music", "r&b": "music",
  // Nightlife
  bar: "nightlife", brewery: "nightlife", winery: "nightlife", pub: "nightlife",
  nightclub: "nightlife", "wine-bar": "nightlife", "craft-beer": "nightlife",
  // Comedy sub-subcategories
  "stand-up": "comedy", improv: "comedy",
  // Arts sub-subcategories
  gallery: "arts", film: "arts", "street-art": "arts",
  // Food sub-subcategories
  restaurant: "food", cafe: "food", bakery: "food",
  // Theatre sub-subcategories
  opera: "theatre", dance: "theatre", musical: "theatre",
  // Misc → other
  miscellaneous: "other", miscellane: "other",
};

// The allowed parent categories for the tab row
// IMPORTANT: when you add/rename/remove an entry here, ALSO update the
// CATEGORIES array in scripts/refresh_carousel_cache.js. Drift between
// the two caused all 7 non-overlapping tabs to hit the 30 s slow path
// on every tap until the cron caught up to the edge fn's vocabulary
// (incident 2026-05-19).
const parentCategories = new Set([
  "music", "sports", "arts", "nightlife", "theatre", "comedy",
  "food", "outdoors", "museums", "tours", "libraries", "for-kids",
]);

function buildCategories(
  allEvents: ProcessedEvent[],
  userPreferences: UserPreferences | null,
): CategoryInfo[] {
  const userFavorites = (userPreferences?.selected_categories ?? []).map(c => c.toLowerCase());

  const counts = new Map<string, number>();
  for (const e of allEvents) {
    const rawSubcat = (e.subcategory ?? "").toLowerCase();
    if (!rawSubcat || rawSubcat === "no-public-interest" || rawSubcat === "cafe") continue;
    // Map to parent category if not already a parent
    const parent = parentCategories.has(rawSubcat) ? rawSubcat : (subcategoryToParent[rawSubcat] ?? "other");
    counts.set(parent, (counts.get(parent) ?? 0) + 1);
  }
  if (counts.size === 0) return [];

  // Remove "other" if it has very few events
  if ((counts.get("other") ?? 0) < 5) counts.delete("other");

  // Sort: user favorites first, then by count, "other" last
  const sorted = [...counts.entries()].sort((a, b) => {
    if (a[0] === "other" && b[0] !== "other") return 1;
    if (b[0] === "other" && a[0] !== "other") return -1;
    const aFav = userFavorites.includes(a[0]) ? 1 : 0;
    const bFav = userFavorites.includes(b[0]) ? 1 : 0;
    if (aFav !== bFav) return bFav - aFav;
    return b[1] - a[1];
  });

  const categories: CategoryInfo[] = sorted.map(([cat, count]) => ({
    id: cat,
    label: categoryTabDisplayNames[cat] ?? cat.charAt(0).toUpperCase() + cat.slice(1),
    icon_url: iconUrlFor(cat),
    event_count: count,
  }));

  // Count curated experiences
  const experiencesCount = allEvents.filter(e => e.source === "curated").length;
  if (experiencesCount > 0) {
    const insertIndex = Math.min(3, categories.length);
    categories.splice(insertIndex, 0, {
      id: "experiences",
      label: "Experiences",
      icon_url: iconUrlFor("experiences"),
      event_count: experiencesCount,
    });
  }

  return categories;
}

// ════════════════════════════════════════════════════════════════
// Data fetching
// ════════════════════════════════════════════════════════════════

async function fetchAllEvents(
  supabase: ReturnType<typeof createClient>,
  lat: number,
  lng: number,
  radiusMiles: number,
): Promise<ProcessedEvent[]> {
  // Latency sprint cutover: upcoming events come from mv_carousel_payload
  // via mv_events_for_geo() — single RPC call replaces the 7×1000 paginated
  // scan of `events`. The MV is refreshed every 5 min by pg_cron
  // (mv_carousel_payload_refresh) and pre-joins the best-ranked active
  // venue per event. Removes the 30 s cold-isolate slow path.
  //
  // always_on events are NOT in the MV (no future start_at), so we still
  // fetch them directly — small set (~hundreds), runs in parallel.
  const latDelta = radiusMiles / 69.0;
  const lngDelta = radiusMiles / 50.0;
  const minLat = lat - latDelta;
  const maxLat = lat + latDelta;
  const minLng = lng - lngDelta;
  const maxLng = lng + lngDelta;
  const pageSize = 1000;

  async function fetchAlwaysOnPage(page: number): Promise<EventRow[]> {
    const { data, error } = await supabase
      .from("events")
      .select("*")
      .eq("is_active", true)
      .eq("category", "always_on")
      .gte("venue_lat", minLat)
      .lte("venue_lat", maxLat)
      .gte("venue_lng", minLng)
      .lte("venue_lng", maxLng)
      .order("title", { ascending: true })
      .range(page * pageSize, (page + 1) * pageSize - 1);
    if (error) throw error;
    return data ?? [];
  }

  const [mvResp, ...alwaysOnPages] = await Promise.all([
    supabase.rpc("mv_events_for_geo", {
      p_lat: lat,
      p_lng: lng,
      p_radius_miles: radiusMiles,
      p_max_results: 7000,
    }),
    ...Array.from({ length: 3 }, (_, i) => fetchAlwaysOnPage(i)),
  ]);
  if (mvResp.error) throw mvResp.error;

  const mvRows = (mvResp.data ?? []) as Array<EventRow & {
    rank_score: number | null;
    distance_miles: number | null;
    venue_description: string | null;
    venue_photo_url: string | null;
  }>;
  const mvProcessed: ProcessedEvent[] = mvRows
    .map(row => ({
      ...row,
      distance_miles: row.distance_miles,
      venue_description: row.venue_description ?? null,
      venue_photo_url: row.venue_photo_url ?? null,
    }))
    .filter(e => e.distance_miles == null || e.distance_miles <= radiusMiles);

  const alwaysOnRows = alwaysOnPages.flat();
  const alwaysOnProcessed: ProcessedEvent[] = alwaysOnRows
    .map(row => ({
      ...row,
      distance_miles: (row.venue_lat != null && row.venue_lng != null)
        ? distanceMiles(lat, lng, row.venue_lat, row.venue_lng)
        : null,
      venue_description: null,
      venue_photo_url: null,
    }))
    .filter(e => e.distance_miles == null || e.distance_miles <= radiusMiles);

  return [...mvProcessed, ...alwaysOnProcessed];
}

// fetchRawSportsEvents removed — rawSportsEvents is now derived inline
// in the slow-path handler block by filtering the MV-backed fetchAllEvents
// result. Same row set, no second 7×1000 paginated scan.

async function fetchVenueCacheLite(
  supabase: ReturnType<typeof createClient>,
): Promise<Map<string, VenueInfo>> {
  // Single RPC call → ALL active venues. The RPC bypasses PostgREST's
  // max_rows=1000 cap so no pagination needed. ~1 MB payload at current
  // venue count (~9k), single round-trip, sub-second.
  //
  // Was 9 sequential paginated PostgREST calls (~13 s of slow-path
  // latency). Each prior page depended on the previous offset, so they
  // couldn't be parallelized.
  const { data, error } = await supabase.rpc("get_active_venues_lite");
  const cache = new Map<string, VenueInfo>();
  if (error || !data) return cache;
  for (const v of data as Array<{ name: string | null; public_interest_rank: number | null; photo_url: string | null; description: string | null }>) {
    if (!v.name) continue;
    cache.set(v.name.toLowerCase(), {
      rank: v.public_interest_rank,
      photo_url: v.photo_url,
      description: v.description,
    });
  }
  return cache;
}

// Bounding boxes for supported states — mirrors the client-side
// `SUPPORTED_STATE_BOUNDS` in HomeViewModel.kt. Used to filter carousels whose
// `city` column is a state name (e.g. "Massachusetts").
const STATE_BOUNDS: Record<string, { latMin: number; latMax: number; lngMin: number; lngMax: number }> = {
  massachusetts: { latMin: 41.2, latMax: 42.9, lngMin: -73.5, lngMax: -69.9 },
  "rhode island": { latMin: 41.1, latMax: 42.1, lngMin: -72.0, lngMax: -71.1 },
  pennsylvania: { latMin: 39.7, latMax: 42.3, lngMin: -80.6, lngMax: -74.6 },
};

// True when a carousel whose `city` column is `carouselCity` is relevant to a
// user at (lat,lng). Empty/null city → global carousel (always shown). State
// names → match against STATE_BOUNDS. City names → match against knownCities
// within a 60-mile radius.
function carouselMatchesLocation(
  carouselCity: string | null | undefined,
  lat: number,
  lng: number,
): boolean {
  if (!carouselCity) return true;
  const key = carouselCity.trim().toLowerCase();
  if (!key) return true;
  const state = STATE_BOUNDS[key];
  if (state) {
    return lat >= state.latMin && lat <= state.latMax && lng >= state.lngMin && lng <= state.lngMax;
  }
  const city = knownCities.find((c) => c.name.toLowerCase() === key);
  if (city) {
    return distanceMiles(lat, lng, city.lat, city.lng) <= 60;
  }
  // Unknown key — assume global so we don't accidentally hide carousels.
  return true;
}

async function fetchCarouselsConfig(
  supabase: ReturnType<typeof createClient>,
  lat: number,
  lng: number,
): Promise<CarouselConfig[]> {
  const { data, error } = await supabase
    .from("carousels")
    .select("*")
    .eq("is_active", true)
    .order("position", { ascending: true });
  if (error) return [];
  return (data ?? []).filter((c: CarouselConfig) => carouselMatchesLocation(c.city, lat, lng));
}

async function fetchUserPreferences(
  supabase: ReturnType<typeof createClient>,
  userId: string,
): Promise<UserPreferences | null> {
  const { data, error } = await supabase
    .from("user_profiles")
    .select("*")
    .eq("id", userId)
    .maybeSingle();
  if (error || !data) return null;
  return {
    id: data.id,
    first_name: data.first_name ?? null,
    last_name: data.last_name ?? null,
    phone: data.phone ?? null,
    city: data.city ?? "boston",
    user_lat: data.user_lat ?? null,
    user_lng: data.user_lng ?? null,
    selected_neighborhoods: data.selected_neighborhoods ?? [],
    selected_venues: data.selected_venues ?? [],
    selected_categories: data.selected_categories ?? [],
    notif_tickets: data.notif_tickets ?? true,
    notif_nearby: data.notif_nearby ?? true,
    notif_venues: data.notif_venues ?? true,
    onboarding_complete: data.onboarding_complete ?? false,
  };
}

async function fetchCommunityScores(
  supabase: ReturnType<typeof createClient>,
  userId: string | null,
): Promise<Map<string, CommunityScore>> {
  const { data, error } = await supabase
    .rpc("get_community_scores", { p_user_id: userId });
  if (error || !data) return new Map();
  const map = new Map<string, CommunityScore>();
  for (const row of data) {
    map.set(row.item_id, row as CommunityScore);
  }
  return map;
}

async function fetchCategoryAffinity(
  supabase: ReturnType<typeof createClient>,
  userId: string | null,
): Promise<Map<string, number>> {
  if (!userId) return new Map();
  const { data, error } = await supabase
    .rpc("get_category_affinity", { p_user_id: userId });
  if (error || !data) return new Map();
  // Normalize to 0-1 by dividing by max
  const entries = (data as CategoryAffinity[]);
  const maxWeight = Math.max(...entries.map(e => e.weight), 1);
  const map = new Map<string, number>();
  for (const entry of entries) {
    map.set(entry.category.toLowerCase(), Math.min(entry.weight / maxWeight, 1.0));
  }
  return map;
}

// ════════════════════════════════════════════════════════════════
// ════════════════════════════════════════════════════════════════
// In-memory cache (persists across requests in same Deno isolate)
// ════════════════════════════════════════════════════════════════

interface CachedData {
  allEvents: ProcessedEvent[];
  rawSportsEvents: ProcessedEvent[];
  venueCache: Map<string, VenueInfo>;
  carouselsConfig: any[];
  timestamp: number;
}

let dataCache: CachedData | null = null;
const CACHE_TTL_MS = 15 * 60 * 1000; // 15 minutes

function isCacheValid(): boolean {
  return dataCache !== null && (Date.now() - dataCache.timestamp) < CACHE_TTL_MS;
}

// Main handler
// ════════════════════════════════════════════════════════════════

Deno.serve(async (req) => {
  // CORS preflight
  if (req.method === "OPTIONS") {
    return corsPreflight();
  }

  if (req.method !== "GET") {
    return jsonCompressed(req, { error: "Method not allowed" }, { status: 405 });
  }

  return await withObservability(
    { functionName: "carousels", request: req },
    async () => {
  try {
    const url = new URL(req.url);
    const lat = parseFloat(url.searchParams.get("lat") ?? "42.3601");
    const lng = parseFloat(url.searchParams.get("lng") ?? "-71.0589");
    const userId = url.searchParams.get("user_id") ?? null;
    const category = url.searchParams.get("category") ?? "foryou";
    const locationName = url.searchParams.get("location_name") ?? "";
    const radiusMiles = parseInt(url.searchParams.get("radius") ?? "75", 10);

    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);
    // Cache key: rounded lat/lng so nearby requests share cache
    const cacheGeoKey = `${Math.round(lat * 10) / 10},${Math.round(lng * 10) / 10}`;

    // ── Tier 3 read-through: precomputed carousel_cache table ──────────
    // The refresh_carousel_cache.js cron job pre-computes carousels for
    // every (metro × category) combo and writes them here. On a request
    // we do ONE indexed SELECT instead of the 5-section heavy aggregation
    // — typically <100 ms server-side. Per-user personalization is still
    // fetched fresh and overlaid below.
    //
    // The refresh script bypasses this read by sending X-Refresh-Cache:1,
    // so we don't return stale data to ourselves while warming the cache.
    const isRefreshing = req.headers.get("X-Refresh-Cache") === "1";
    if (!isRefreshing) {
      try {
        const latBucket = Math.round(lat * 10) / 10;
        const lngBucket = Math.round(lng * 10) / 10;
        // Try the exact bucket first.
        let { data: cacheRows } = await supabase
          .from("carousel_cache")
          .select("payload, computed_at, lat_bucket, lng_bucket")
          .eq("lat_bucket", latBucket)
          .eq("lng_bucket", lngBucket)
          .eq("category", category)
          .limit(1);

        // Nearest-bucket fallback. The refresh script warms each active
        // metro at its exact lat/lng, but users 5-15 mi out (Waltham vs.
        // Boston, Cambridge vs. Boston, etc.) hit a different 0.1°
        // bucket and get nothing. Widen to ±0.3° (~21 mi) and pick the
        // closest fresh row — the carousel content is metro-wide anyway,
        // so a Boston cache served to a Waltham user is the right answer.
        if (!cacheRows || cacheRows.length === 0) {
          const fb = await supabase
            .from("carousel_cache")
            .select("payload, computed_at, lat_bucket, lng_bucket")
            .gte("lat_bucket", latBucket - 0.3).lte("lat_bucket", latBucket + 0.3)
            .gte("lng_bucket", lngBucket - 0.3).lte("lng_bucket", lngBucket + 0.3)
            .eq("category", category)
            .limit(20);
          if (fb.data && fb.data.length > 0) {
            const ranked = fb.data
              .map((r: any) => {
                const dLat = (r.lat_bucket as number) - latBucket;
                const dLng = (r.lng_bucket as number) - lngBucket;
                return { row: r, d: dLat * dLat + dLng * dLng };
              })
              .sort((a, b) => a.d - b.d);
            cacheRows = [ranked[0].row];
          }
        }

        const row = cacheRows?.[0];
        // Accept cache up to 30 min old. Refresh job runs every 10 min, so
        // most reads will land on a < 10 min old row. The 30 min ceiling
        // tolerates one missed cron without falling back to slow path.
        const ageMs = row?.computed_at ? Date.now() - new Date(row.computed_at as string).getTime() : Infinity;
        if (row?.payload && ageMs < 30 * 60 * 1000) {
          // TODO(personalization): when user_id is present, layer favorites/
          // affinity scoring on top here. For v1 we return the precomputed
          // anonymous payload — same fidelity as the previous foryou cache.
          const isFallback = (row as any).lat_bucket !== latBucket || (row as any).lng_bucket !== lngBucket;
          return jsonCompressed(req, row.payload, {
            extraHeaders: {
              "X-Cache": `precomputed-${Math.round(ageMs / 1000)}s${isFallback ? "-nearest" : ""}`,
            },
          });
        }
      } catch (_) { /* fall through to slow path */ }
    }

    // Use cached event/venue data if available, only fetch per-user data fresh
    // Invalidate cache if location changed significantly
    let allEvents: ProcessedEvent[];
    let rawSportsEvents: ProcessedEvent[];
    let venueCache: Map<string, VenueInfo>;
    let carouselsConfig: any[];

    const cachedGeoKey = (dataCache as any)?.geoKey as string | undefined;
    if (isCacheValid() && cachedGeoKey === cacheGeoKey) {
      allEvents = dataCache!.allEvents;
      rawSportsEvents = dataCache!.rawSportsEvents;
      venueCache = dataCache!.venueCache;
      carouselsConfig = dataCache!.carouselsConfig;
    } else {
      // Slow path (Deno isolate cold or in-memory cache expired). All
      // remaining fetches go through the MV-backed paths or single-call
      // RPCs — no more 7-page paginated scans.
      //
      // 1. fetchAllEvents → mv_events_for_geo() single call (was 7×1000)
      // 2. fetchVenueCache → get_active_venues_lite() single call (was
      //    9 sequential paginated calls — the previous long pole)
      // 3. rawSports → JS filter over fetchAllEvents result (was a
      //    second 7×1000 scan; same source data, just a different filter)
      const [rawEvents, venues, config] = await Promise.all([
        fetchAllEvents(supabase, lat, lng, radiusMiles),
        fetchVenueCacheLite(supabase),
        fetchCarouselsConfig(supabase, lat, lng),
      ]);
      const deduped = deduplicateCrossSource(rawEvents);
      allEvents = cleanEvents(deduped, venues);
      // Sports rail derived from fetchAllEvents result. Same row set the
      // old fetchRawSportsEvents() would have produced (MV's 90-day
      // window is broader than the old query's no-upper-bound, with the
      // only edge case being events 91+ days out — accepted tradeoff).
      rawSportsEvents = rawEvents.filter(e => {
        const sc = (e.subcategory ?? "").toLowerCase();
        return sc === "sports" || sc === "hockey" || sc === "baseball"
          || sc === "basketball" || sc === "football" || sc === "soccer"
          || sc === "rugby" || sc === "lacrosse" || sc === "college-sports"
          || sc === "minor-league";
      });
      venueCache = venues;
      carouselsConfig = config;
      dataCache = { allEvents, rawSportsEvents, venueCache, carouselsConfig, timestamp: Date.now(), geoKey: cacheGeoKey } as any;
    }

    // Per-user data — always fetched fresh (fast, single-row queries)
    const [userPreferences, communityScores, affinityMap] = await Promise.all([
      userId ? fetchUserPreferences(supabase, userId) : Promise.resolve(null),
      fetchCommunityScores(supabase, userId),
      fetchCategoryAffinity(supabase, userId),
    ]);

    const weights = weightsForUser(lat, lng, radiusMiles);
    const nearestCity = locationName || nearestCityName(lat, lng);

    const carouselSections = buildCarousels(
      allEvents, rawSportsEvents, carouselsConfig, category,
      lat, lng, userPreferences, nearestCity,
      weights, venueCache, communityScores, affinityMap,
    );

    const categories = buildCategories(allEvents, userPreferences);

    // Trim heavy fields from events to reduce response size
    const trimmedCarousels = carouselSections.map(section => ({
      ...section,
      // Truncate description to 200 chars (cards don't display it; iOS hero
      // transition still gets a preview snippet for the detail screen until
      // the full row is fetched). Strip venue_description entirely — it's a
      // fallback only used when description is null, and the detail screen
      // refetches the venue independently.
      events: section.events.map((e: ProcessedEvent) => ({
        id: e.id,
        title: e.title,
        category: e.category,
        subcategory: e.subcategory,
        sub_subcategory: e.sub_subcategory,
        start_at: e.start_at,
        end_at: e.end_at,
        is_free: e.is_free,
        price_min: e.price_min,
        price_max: e.price_max,
        ticket_url: e.ticket_url,
        image_url: e.image_url,
        venue_name: e.venue_name,
        venue_city: e.venue_city,
        venue_state: e.venue_state,
        venue_lat: e.venue_lat,
        venue_lng: e.venue_lng,
        venue_address: e.venue_address,
        city: e.city,
        source: e.source,
        source_url: e.source_url,
        distance_miles: e.distance_miles,
        description: e.description ? e.description.slice(0, 200) : null,
        time_known: e.time_known ?? true,
        venue_photo_url: e.venue_photo_url,
      })),
    }));

    const response = {
      carousels: trimmedCarousels,
      categories,
      cached_at: new Date().toISOString(),
    };

    return jsonCompressed(req, response, { extraHeaders: { "X-Cache": "slow-path" } });
  } catch (error) {
    // Render PostgrestError / Error / unknown objects to a useful string.
    // Without this, `String(err)` on a structured error returns
    // "[object Object]" and the actual cause is lost.
    let details: string;
    if (error && typeof error === "object") {
      const e = error as Record<string, unknown>;
      details = JSON.stringify({
        message: e.message,
        code: e.code,
        hint: e.hint,
        details: e.details,
        name: e.name,
        stack: typeof e.stack === "string" ? e.stack.split("\n").slice(0, 4).join(" | ") : undefined,
      });
    } else {
      details = String(error);
    }
    console.error("Carousel edge function error:", details);
    return jsonCompressed(
      req,
      { error: "Internal server error", details },
      { status: 500 },
    );
  }
    },
  );
});
