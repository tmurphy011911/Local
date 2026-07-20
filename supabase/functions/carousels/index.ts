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
  tags: string[] | null;
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
  event_city: string | null;
  event_state: string | null;
  event_lat: number | null;
  event_lng: number | null;
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
// Scoring reach is density-adaptive (see adaptiveRadiusMiles / weightsForUser),
// derived from the actual local event supply rather than distance to any
// hardcoded metro center — so it scales to every current and future market.

// Absolute distance ceiling. No event may appear in any carousel if it is
// more than this many miles from the user's location (manual or GPS). This is
// a hard invariant enforced in three layers: the requested radius is clamped
// to it, weightsForUser caps maxEventRadius at it, and buildCarousels makes a
// final pass dropping any event beyond it regardless of which rail produced it.
const MAX_EVENT_DISTANCE_MILES = 100;

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

// Genres the generic title-caser can't render correctly (acronyms, "&", etc.).
const genreDisplayOverrides: Record<string, string> = {
  "r&b": "R&B",
};

function subSubcategoryDisplayName(key: string): string {
  const override = genreDisplayOverrides[key.toLowerCase()];
  if (override) return override;
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
    // Restaurant weeks legitimately recur within a year (Wildwoods runs Jun /
    // Aug / Nov) and each edition is its own dated card, so disambiguate them by
    // start month instead of collapsing every same-titled edition to one. Every
    // other event type keeps the plain base-title collapse (no 8 "Hamilton"s).
    const key = (event.subcategory ?? "").toLowerCase() === "restaurant-week"
      ? `${baseTitle(event.title)}|${(event.start_at ?? "").slice(0, 7)}`
      : baseTitle(event.title);
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
  cityPhotoCache?: Map<string, string>,
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
    // Last-resort city-photo fallback for events that don't resolve to a
    // venue (municipal at-large events: venue_name IS NULL → no venue photo).
    // Without this they render as a blank category gradient. Keyed by
    // venue_city. ONLY serve stable Supabase-Storage URLs: the city_photos
    // table historically held rotted Google Places URLs that embed an API
    // key — never surface those to clients.
    if (!result.image_url && cityPhotoCache) {
      const cityKey = (result.venue_city ?? "").trim().toLowerCase();
      const cityPhoto = cityKey ? cityPhotoCache.get(cityKey) : undefined;
      if (cityPhoto && cityPhoto.includes("/storage/v1/object/public/city-photos/")) {
        result = { ...result, image_url: cityPhoto };
      }
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
  // Prefer event_lat (real event location) over venue_lat (host org HQ).
  // Mirrors the priority in mv_events_for_geo so card distance is consistent
  // across the fast (MV) path and the always_on slow path.
  if (event.event_lat != null && event.event_lng != null) {
    return {
      ...event,
      distance_miles: distanceMiles(userLat, userLng, event.event_lat, event.event_lng),
    };
  }
  if (event.venue_lat == null || event.venue_lng == null) return event;
  return {
    ...event,
    distance_miles: distanceMiles(userLat, userLng, event.venue_lat, event.venue_lng),
  };
}

// ════════════════════════════════════════════════════════════════
// Scoring
// ════════════════════════════════════════════════════════════════

// The scoring weights key off `effectiveRadius` — the density-adaptive reach
// computed in adaptiveRadiusMiles(), NOT a hardcoded metro distance. A tight
// reach means the user sits in a dense supply of nearby events, so proximity
// carries most of the weight and decays fast; a wide reach means events are
// sparse, so we lean less on proximity (there isn't much nearby to prefer) and
// let relevance / venue-rank / date matter more. This replaced a metro-center
// heuristic (distance to a hardcoded Boston-then-list of points) that didn't
// scale: it mis-classified every non-listed metro as "far" and inflated the
// radius to the 100mi ceiling, flooding dense metros with 40–75mi events.
function weightsForUser(effectiveRadius: number): ScoreWeights {
  let proxWeight: number;
  let decay: number;
  if (effectiveRadius <= 20.0) { proxWeight = 0.42; decay = 4.0; }
  else if (effectiveRadius <= 40.0) { proxWeight = 0.52; decay = 3.0; }
  else if (effectiveRadius <= 60.0) { proxWeight = 0.64; decay = 1.8; }
  else { proxWeight = 0.72; decay = 1.0; }

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

// ── Density-adaptive reach ────────────────────────────────────────────────
// Reach only as far as needed to gather enough events for a full, diverse
// feed. Dense metros (Philadelphia: ~130 events within 10mi) settle at the
// floor and never surface the Jersey Shore / NYC; genuinely sparse areas keep
// widening — up to whatever was fetched — so they still see something. Purely
// supply-driven: no metro list, no per-city tuning, scales to any new market.
const ADAPTIVE_RADIUS_FLOOR_MILES = 15;  // always show a full-city view
const ADAPTIVE_FEED_TARGET = 120;        // events wanted for a diverse feed
function adaptiveRadiusMiles(events: ProcessedEvent[], fetchRadiusMiles: number): number {
  const ds = events
    .map(e => e.distance_miles)
    .filter((d): d is number => d != null)
    .sort((a, b) => a - b);
  // Fewer events available than we want → reach as wide as we fetched.
  if (ds.length <= ADAPTIVE_FEED_TARGET) return fetchRadiusMiles;
  // Otherwise reach exactly far enough to include the target-th nearest event,
  // clamped so we never go tighter than a full-city view nor wider than fetched.
  const needed = ds[ADAPTIVE_FEED_TARGET - 1];
  return Math.min(Math.max(needed, ADAPTIVE_RADIUS_FLOOR_MILES), fetchRadiusMiles);
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
  const cityLc = (event.venue_city ?? "").toLowerCase();

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

// Diversify a single-category event list (e.g. all "for-kids") by
// sub_subcategory + venue, with a relaxed backfill pass so we never
// return fewer events than the pool allows. Used by rankForColdStart's
// single-category fast path.
function diversifyWithinCategory(
  events: ProcessedEvent[],
  maxResults: number,
): ProcessedEvent[] {
  const result: ProcessedEvent[] = [];
  const subSubCounts = new Map<string, number>();
  const venueCounts = new Map<string, number>();
  const deferred: ProcessedEvent[] = [];
  const SUBSUB_CAP = 3;
  const VENUE_CAP = 3;

  for (const e of events) {
    if (result.length >= maxResults) break;
    const subSub = (e.sub_subcategory ?? "").toLowerCase() || "_none";
    const venue = (e.venue_name ?? "").toLowerCase() || "_none";
    const subSubCount = subSubCounts.get(subSub) ?? 0;
    const venueCount = venueCounts.get(venue) ?? 0;
    if (subSubCount >= SUBSUB_CAP) { deferred.push(e); continue; }
    if (venueCount >= VENUE_CAP) { deferred.push(e); continue; }
    if (result.length > 0 && isSimilarEvent(e, result[result.length - 1])) {
      deferred.push(e);
      continue;
    }
    result.push(e);
    subSubCounts.set(subSub, subSubCount + 1);
    venueCounts.set(venue, venueCount + 1);
  }

  // Backfill: drop the dimension caps but keep the adjacent-similarity
  // guard. Better to ship 12 cards with one near-duplicate pair than 4
  // cards with strict caps.
  for (const e of deferred) {
    if (result.length >= maxResults) break;
    if (result.length > 0 && isSimilarEvent(e, result[result.length - 1])) continue;
    result.push(e);
  }

  return result;
}

function rankForColdStart(
  events: ProcessedEvent[],
  prefs: UserPreferences | null,
  maxResults: number,
  affinityMap: Map<string, number>,
): ProcessedEvent[] {
  // Proximity is a first-class ranking signal here, not just an eligibility
  // gate. coldStartScore alone (venue prestige + category prefs + affinity) let
  // far, high-rank venues — Citi Field, Barclays, Prudential Center — outrank a
  // nearby modest venue in "Picked for You," so a Philadelphia user saw 55–89 mi
  // events above the 1.4 mi Penn Museum. The topical rails already fold in
  // proximity via scoreForSort; the hero rail did not. Add a decaying proximity
  // boost so nearby events lead while a genuinely great far event can still land.
  // decay=8 mi: 1.4 mi → +0.51, 15 mi → +0.26, 55 mi → +0.08, 89 mi → +0.05.
  const HERO_PROX_WEIGHT = 0.6;
  const HERO_PROX_DECAY = 8.0;
  const proximityBoost = (e: ProcessedEvent): number =>
    HERO_PROX_WEIGHT * (1.0 / (1.0 + (e.distance_miles ?? 50.0) / HERO_PROX_DECAY));
  const scored = events.map(e => ({
    event: e,
    score: coldStartScore(e, prefs, affinityMap) + proximityBoost(e),
  })).sort((a, b) => b.score - a.score);

  // ── Single-category fast path ─────────────────────────────────────
  // On subcategory tabs ("For Kids", "Music", etc.) every event in the
  // pool shares the same top-level category. The cross-category rules
  // below (first-3-distinct + 25% top-level cap) collapse the output
  // to a single event in that case: slot 0 gets placed, every later
  // event gets deferred on first-3-distinct, and the deferred-insert
  // loop's earliestAllowed=3 with result.length=1 means the for-loop
  // body never runs. Detect that case and diversify on sub_subcategory
  // + venue_name instead, which are the meaningful axes inside a
  // single subcategory.
  const distinctCategories = new Set(scored.map(p => p.event.category ?? "other"));
  if (distinctCategories.size <= 1 && scored.length > 0) {
    return diversifyWithinCategory(scored.map(p => p.event), maxResults);
  }

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

// Events that belong in the general-purpose feed — i.e. NOT curated
// experiences and NOT recurring. Curated rows belong only in
// "Things to Do Anytime" / Experiences tab; recurring rows belong only
// in Happens Every Day / Weekly / Monthly. Venue- and neighborhood-
// scoped rails are intentionally exempt from this rule (a user who
// favorited the MFA wants its exhibits to show up under "Coming Up at
// MFA").
function isGeneralFeedEvent(event: ProcessedEvent): boolean {
  return event.source !== "curated"
    && event.category !== "always_on"
    // Restaurant weeks are dated dining promotions that belong only in the
    // dedicated "Restaurant Weeks Near You" rail (For You) / the Food-tab P1
    // rail — never scattered through the generic topical rails. Same intent as
    // the curated/recurring exclusions above.
    && (event.subcategory ?? "").toLowerCase() !== "restaurant-week"
    && !isWeeklyEvent(event)
    && !isMonthlyEvent(event);
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
  // "Experiences" tab: combined pool of curated experiences and tour
  // events. Reached via "experiences" OR "tours" so existing deep links
  // / bookmarks to the old Tours tab redirect into this single tab.
  | { type: "experiences_combined" }
  | { type: "exclude_known" };

function resolveFilter(category: string | null): EventFilterType {
  if (!category || category.toLowerCase() === "foryou") return { type: "none" };
  const lc = category.toLowerCase();
  if (lc === "experiences" || lc === "tours") return { type: "experiences_combined" };
  if (lc === "nightlife") return { type: "category", value: "nightlife" };
  if (knownSubcategories.includes(lc)) return { type: "category", value: lc };
  if (parentCategories.has(lc)) return { type: "category", value: lc };
  if (lc === "locations") return { type: "none" };
  if (lc === "other") return { type: "exclude_known" };
  return { type: "category", value: lc };
}

function applyFilter(events: ProcessedEvent[], filter: EventFilterType): ProcessedEvent[] {
  switch (filter.type) {
    case "none": return events.filter(isGeneralFeedEvent);
    case "empty": return [];
    case "category": {
      const filterValue = filter.value;
      // If filtering by a parent category, include events whose subcategory maps to it
      if (parentCategories.has(filterValue)) {
        return events.filter(e => {
          if (!isGeneralFeedEvent(e)) return false;
          const subcat = (e.subcategory ?? "").toLowerCase();
          if (subcat === filterValue) return true;
          return (subcategoryToParent[subcat] ?? "") === filterValue;
        });
      }
      return events.filter(e =>
        isGeneralFeedEvent(e) && (e.subcategory ?? "").toLowerCase() === filterValue
      );
    }
    case "source":
      // Experiences tab — must NOT apply isGeneralFeedEvent (it would
      // strip the very rows the tab exists to show).
      return events.filter(e => e.source === filter.value);
    case "experiences_combined":
      // Combined Experiences + Tours tab. Curated rows are intentionally
      // exempt from isGeneralFeedEvent (recurrence semantics don't apply
      // to them). Tour events also bypass the recurrence filter because
      // their cadence is the point — a Freedom Trail walking tour
      // running weekly belongs on the Experiences tab, not stripped to
      // a "Weekly Happenings" rail.
      return events.filter(e =>
        e.source === "curated" || (e.subcategory ?? "").toLowerCase() === "tours"
      );
    case "exclude_known":
      return events.filter(e =>
        isGeneralFeedEvent(e) && !knownSubcategories.includes((e.subcategory ?? "other").toLowerCase())
      );
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
  // Venue must have at least 4 events to render (matches addCarousel's
  // minimum). Filtering at >= 4 here (not >= 2) avoids burning a top-N
  // slot on a venue that's going to silently fail to render anyway.
  // Show up to 12 venues so dense subcategories (For Kids in metro Boston
  // has 20+ venues meeting the bar) surface a representative spread
  // instead of clipping to a top 6.
  const sortedVenues = [...byVenue.entries()]
    .filter(([, v]) => v.length >= 4)
    .sort((a, b) => b[1].length - a[1].length)
    .slice(0, 12);
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
        // For You feed: match the carousel's category by either exact
        // subcategory OR (when the carousel category is a parent like
        // "sports", "music", "arts") any subcategory that rolls up to
        // it. Without this, the "Sports" rail on For You was returning
        // only events literally tagged subcategory='sports' (which is
        // the bucket for unclassified sports stuff — motorsports,
        // wrestling-tour misclassifications, etc.) and missing every
        // Red Sox / Bruins / Celtics / Revolution game because those
        // are tagged with the specific sport (baseball / hockey /
        // basketball / soccer). Same logic applyFilter uses for
        // parent-category tab loads.
        const isParent = parentCategories.has(carouselCat);
        return pool.filter(e => {
          const subcat = (e.subcategory ?? "").toLowerCase();
          if (subcat === carouselCat) return true;
          if (isParent && (subcategoryToParent[subcat] ?? "") === carouselCat) return true;
          return false;
        });
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
      // ── Subcategory-tab fast path ────────────────────────────────
      // On a subcategory tab the user has scoped to one niche (Music,
      // Comedy, For Kids…) and explicitly wants saturation IN that niche.
      // rankForColdStart's single-category branch caps per-venue and
      // per-sub_subcategory at 3 each, which on Music can leave the
      // "Top Events Happening Soon" rail showing 2-3 events while the
      // adjacent "Live Music" rail shows 25 from the same pool. Skip
      // the diversification here; use a simple score+date sort over a
      // 30-day window so the headline rail is always populated.
      if (userFilter.type === "category" && userFilter.value !== "sports") {
        const cutoff = daysFromNowIso(30);
        return pool
          .filter(e => (e.start_at ?? "") <= cutoff)
          .map(e => ({ e, s: coldStartScore(e, prefs, affinityMap) }))
          .sort((a, b) => b.s - a.s)
          .map(p => p.e)
          .slice(0, CAROUSEL_DISPLAY_LIMIT);
      }
      // ── For You / general path ───────────────────────────────────
      // Adaptive window: try 7 days; if the pool is thin, widen to 14,
      // then 30. Title is "Top Events Happening Soon" regardless of
      // which window wins, so users never see a sparse rail on slow
      // weekdays. rankForColdStart's cross-category diversification IS
      // wanted here — keeps For You from being all-Fenway.
      const windows = [7, 14, 30];
      const minPoolSizes = [12, 8, 0];
      let chosen: ProcessedEvent[] = [];
      for (let i = 0; i < windows.length; i++) {
        const cutoff = daysFromNowIso(windows[i]);
        const windowPool = pool.filter(e => (e.start_at ?? "") <= cutoff);
        chosen = windowPool;
        if (windowPool.length >= minPoolSizes[i]) break;
      }
      return rankForColdStart(chosen, prefs, CAROUSEL_DISPLAY_LIMIT, affinityMap);
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
  // City-scoped events fetched directly (bypassing the metro-wide MV sample)
  // so neighborhood rails reflect their named city. Empty on non-For-You paths.
  cityEvents: ProcessedEvent[] = [],
  // Venue-scoped events fetched directly (same rationale) so fav-venue rails
  // show a venue's full upcoming slate. Empty on non-For-You paths.
  venueEvents: ProcessedEvent[] = [],
  // Subcategory-scoped events fetched directly (same rationale) so the topical
  // favorite-category and affinity rails reflect their subcategory's full
  // slate instead of the rank-stratified MV sample. Empty on non-For-You paths.
  subcatEvents: ProcessedEvent[] = [],
  // Full in-radius farmers-market set, fetched directly so the guaranteed
  // "Farmers Markets Near You" rail isn't starved by the always_on title cap
  // (the bug where Waltham et al. fell past the 3000-row cutoff). Empty on
  // non-For-You paths. See fetchFarmersMarketEvents.
  farmersMarketEvents: ProcessedEvent[] = [],
  // Full in-radius restaurant-week set, fetched directly so the guaranteed
  // For-You rail and the Food-tab P1 rail aren't starved by the rank-stratified
  // MV sample. Empty on paths that build neither rail.
  restaurantWeekEvents: ProcessedEvent[] = [],
  // The user's REQUESTED radius (not the metro-inflated maxEventRadius) — used to
  // cap the Restaurant Weeks rail so an in-state-but-far week (Pittsburgh, 258 mi
  // from Philly) doesn't show even when the feed's effective radius is widened by
  // distance-from-metro.
  rwRadiusMiles: number = MAX_EVENT_DISTANCE_MILES,
  // Curated "25 Must-Know" editorial picks (from the curated_events table) for
  // the user's state — drives the state-scoped Must-Know rail. Empty off For-You.
  curatedEvents: ProcessedEvent[] = [],
  // Preference-matched events 50-200 mi out (the "Get Away a Little Bit" rail).
  // Fetched directly since this band reaches beyond the metro-wide MV sample.
  // Empty off the For-You path.
  getawayEvents: ProcessedEvent[] = [],
  // Per-entity event groups for the "If You Like <X>" taste rails (performers/
  // brands from the taste graph). Empty off For-You / until profiles are built.
  tasteEntityGroups: { entity: string; events: ProcessedEvent[] }[] = [],
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

  // Restaurant Weeks rail — built once, used on both For You (guaranteed slot)
  // and the Food tab (position 1 when ≥3 nearby, else dropped lower). Restaurant
  // weeks are dated dining promotions stripped from the generic rails by
  // isGeneralFeedEvent, so this dedicated rail is the only place they surface.
  // In-state AND within the user's radius (default 75 mi): a Philly user gets
  // every UPCOMING week in PA that's reasonably close (Manayunk, Lansdale, …) but
  // NOT a 258-mi Pittsburgh week, and NOT a nearby-but-out-of-state week (a
  // Wilmington DE / South Jersey one 20 mi away is excluded by the state gate).
  // No date horizon — a far-future in-state week still shows. Pool = allEvents
  // UNIONED with the dedicated by-state fetch (the complete in-state source).
  // Sort: nearest-first, then soonest. Empty when the user is outside the live
  // footprint.
  const nowIsoRw = new Date().toISOString();
  const rwUserState = userStateCode(userLat, userLng);
  const restaurantWeekSection: CarouselSection = (() => {
    const events = rwUserState == null ? [] : (() => {
      const seen = new Set(allEvents.map(e => e.id));
      const pool = [...allEvents, ...restaurantWeekEvents.filter(e => !seen.has(e.id))]
        .filter(e => (e.subcategory ?? "").toLowerCase() === "restaurant-week")
        .filter(e => (e.venue_state ?? "").toUpperCase() === rwUserState)
        .map(e => withDistance(e, userLat, userLng))
        .filter(e => (e.distance_miles ?? 0) <= rwRadiusMiles);

      // "Live" = has a confirmed upcoming-or-current window (end_at in the
      // future). Anything else (a window that ended, or an undated "Check back"
      // promo with no start) is shown but labelled "Dates TBD" by the client.
      const isLive = (e: ProcessedEvent) => !!e.end_at && e.end_at >= nowIsoRw;

      // One card per promo (keyed by title): prefer the SOONEST live edition;
      // if a promo has no live edition, keep a single TBD representative
      // (undated row if present, else the most recent past one). This stops a
      // twice-a-year promo from showing both its upcoming and its past edition.
      const byPromo = new Map<string, ProcessedEvent>();
      for (const e of pool) {
        const key = (e.title ?? "").toLowerCase().trim();
        const cur = byPromo.get(key);
        if (!cur) { byPromo.set(key, e); continue; }
        const eLive = isLive(e), curLive = isLive(cur);
        if (eLive && !curLive) byPromo.set(key, e);
        else if (eLive && curLive) {
          if ((e.start_at ?? "") < (cur.start_at ?? "")) byPromo.set(key, e);
        } else if (!eLive && !curLive) {
          // Both TBD: prefer the undated row, else the most recent past start.
          if (!e.start_at && cur.start_at) byPromo.set(key, e);
          else if (!e.start_at === !cur.start_at && (e.start_at ?? "") > (cur.start_at ?? "")) byPromo.set(key, e);
        }
      }
      const byDistThenDate = (a: ProcessedEvent, b: ProcessedEvent) => {
        const da = a.distance_miles ?? 999999, db = b.distance_miles ?? 999999;
        if (da !== db) return da - db;
        return (a.start_at ?? "").localeCompare(b.start_at ?? "");
      };
      const deduped = [...byPromo.values()];
      // Live weeks first (nearest, then soonest); TBD/past weeks after (nearest).
      const live = deduped.filter(isLive).sort(byDistThenDate);
      const tbd = deduped.filter(e => !isLive(e))
        .sort((a, b) => (a.distance_miles ?? 999999) - (b.distance_miles ?? 999999));
      return [...live, ...tbd].slice(0, 50);
    })();
    return {
      slug: "restaurant-weeks",
      title: "Restaurant Week",
      subtitle: null,
      filter_type: "restaurant_weeks",
      events,
    };
  })();

  // "25 Must-Know" rail — dated, regionally-iconic editorial picks for the user's
  // state (curated_events table). The refresh keeps ~50 per state; here we pick
  // the 25 CLOSEST to the user (so a Philly user gets the Philly-area icons, not
  // 25 spread to Pittsburgh), then display those 25 chronologically. Rows with no
  // coords sort last in the proximity pass. Empty outside the footprint.
  const todayDateIso = nowIsoRw.slice(0, 10);
  const mustKnowSection: CarouselSection = (() => {
    const events = rwUserState == null ? [] : curatedEvents
      .filter(e => (e.venue_state ?? "").toUpperCase() === rwUserState)
      // Drop anything that's over. With an end_at, it must not have passed; for
      // open-ended rows (no end_at) keep only the still-upcoming ones, so a
      // past one-day pick with a null end_at (Lexington Reenactment, the
      // Marathon) doesn't lead the rail, while ongoing/season rows (Tanglewood,
      // America250) — which carry a real future end_at — stay.
      .filter(e => e.end_at ? e.end_at >= nowIsoRw : (e.start_at ?? "") >= todayDateIso)
      .map(e => withDistance(e, userLat, userLng))
      // Select the 25 nearest…
      .sort((a, b) => (a.distance_miles ?? 999999) - (b.distance_miles ?? 999999))
      .slice(0, 25)
      // …then present them chronologically.
      .sort((a, b) => (a.start_at ?? "").localeCompare(b.start_at ?? ""));
    const stateName = (rwUserState && STATE_CODE_NAMES[rwUserState]) || "Your State";
    return {
      slug: "must-know",
      title: `Must Know in ${stateName}`,
      subtitle: null,
      filter_type: "must_know",
      events,
    };
  })();

  // ── Personalized carousels (only on "For You" tab) ──
  if (filter.type === "none") {
    // Build carousel pools by type, then interleave
    const monthCutoff = daysFromNowIso(31);

    // Pool: Personalized — the hero carousel. Users actually swipe deep here,
    // so we use the bigger HERO_CAROUSEL_DISPLAY_LIMIT instead of the niche
    // default. Diversity caps inside rankForColdStart scale with maxResults,
    // so a 40-slot pool naturally surfaces 5+ distinct categories.
    const weekCutoff = daysFromNowIso(7);
    // Belt-and-suspenders: require a real start_at. (e.start_at ?? "")
    // is "" for null, which sorts < any ISO string, so without this any
    // re-classification slip-up upstream would put curated/always_on
    // rows straight back into the hero. applyFilter already excludes
    // them on the For You feed, but the hero pool is high-value enough
    // to double-gate.
    const weekPool = filtered.filter(e => e.start_at != null && e.start_at <= weekCutoff);
    const ranked = rankForColdStart(weekPool, prefs, HERO_CAROUSEL_DISPLAY_LIMIT, affinityMap);
    const personalizedEvents = backfillToMinimum(ranked, seenEventIds, filtered, HERO_CAROUSEL_DISPLAY_LIMIT);

    // Pool: Weekend
    // Topical rails are independent of "Picked for You" — we used to dedupe
    // against the hero pool, which left "Your Weekend Lineup" with ~5 events
    // when the hero ate the rest. Now each rail just shows its top matches.
    const weekendStart = startOfNextFridayIso();
    const weekendEnd = endOfNextSundayIso();
    // OVERLAP test (mirrors the Must-Know rail above), not start_at-in-window.
    // The old test kept only events whose start_at landed inside Fri-Sun, which
    // silently dropped every multi-day / all-day / festival event that STARTS
    // before Friday but RUNS THROUGH the weekend (its start_at is < Friday) —
    // exactly the richest "this weekend" content, and the same start_at-vs-end_at
    // bug class as the in-progress-festival incident. Lookback bound stops a
    // long-running exhibit (started weeks ago) from leaking in.
    const weekendLookback = daysFromNowIso(-14);
    const weekendEvents = filtered.filter(e => {
      const s = e.start_at ?? "";
      if (!s || s >= weekendEnd) return false;            // no start, or starts after the window
      const end = e.end_at ?? s;                          // point event ends at its start
      return end >= weekendStart && s >= weekendLookback; // overlaps the window, started recently
    });

    // Source pool for the topical favorite-category + affinity rails. Same
    // rationale as favVenuePool / neighborhoodPool below: the rank-stratified
    // MV starves niche subcategories, so union the subcategory-scoped direct
    // fetch on top of allEvents (deduped by id). Empty subcatEvents (non-
    // For-You paths) degrades to plain allEvents, unchanged. Applies to every
    // rail category, so museums/for-kids/comedy/etc. all get their full slate.
    const topicalPool = (() => {
      if (subcatEvents.length === 0) return allEvents;
      const seen = new Set(allEvents.map(e => e.id));
      return [...allEvents, ...subcatEvents.filter(e => !seen.has(e.id))];
    })();

    // Pool: Favorite categories
    const favCatCarousels: CarouselSection[] = [];
    if (prefs) {
      let favCatIdx = 0;
      for (const cat of prefs.selected_categories) {
        const catEvents = topicalPool
          .filter(isGeneralFeedEvent)
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
    // Source pool: general pool plus the venue-scoped direct fetch (deduped by
    // id). venueEvents guarantees a favorited venue's full slate is present
    // even when the rank-stratified MV omitted most of it (e.g. a library with
    // 47 upcoming events that only surfaced 1 in the metro-wide sample).
    const favVenuePool = (() => {
      if (venueEvents.length === 0) return allEvents;
      const seen = new Set(allEvents.map(e => e.id));
      return [...allEvents, ...venueEvents.filter(e => !seen.has(e.id))];
    })();
    const favVenueCarousels: CarouselSection[] = [];
    if (prefs) {
      for (const venue of prefs.selected_venues) {
        const venueRailEvents = favVenuePool
          .filter(e => (e.venue_name ?? "").toLowerCase().includes(venue.toLowerCase()))
          .map(e => withDistance(e, userLat, userLng))
          .sort(sortFn)
          .slice(0, CAROUSEL_DISPLAY_LIMIT);
        if (venueRailEvents.length === 0) continue;
        favVenueCarousels.push({
          slug: `fav-venue-${venue.toLowerCase().replace(/ /g, "-")}`,
          title: `At ${venue}`,
          subtitle: null,
          filter_type: "favorite_venue",
          events: venueRailEvents,
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
    // Source pool for neighborhood rails: the general pool plus the
    // city-scoped fetch (deduped by id). cityEvents guarantees the named
    // city's events are present even when the rank-stratified MV omitted them.
    const neighborhoodPool = (() => {
      if (cityEvents.length === 0) return allEvents;
      const seen = new Set(allEvents.map(e => e.id));
      return [...allEvents, ...cityEvents.filter(e => !seen.has(e.id))];
    })();
    for (const neighborhood of neighborhoods) {
      const neighborhoodEvents = neighborhoodPool
        .filter(e => (e.venue_city ?? "").toLowerCase().includes(neighborhood.toLowerCase()))
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
    // Taste-graph era: give engaged users a rail per genuine interest. The old
    // gate (score >= 0.4 of top, cap 6) throttled heavy users to ~2 rails when
    // one category dominated (arts=1.0 => museums/comedy/baseball all < 0.4).
    // Lower the relative floor to surface the long tail and raise the cap; the
    // empty-events skip below is the natural quality floor (no rail without a
    // populated slate). Numbers are tunable on real data.
    for (const [cat, score] of sortedAffinity) {
      if (score < 0.15) break;
      if (affinityCount >= 20) break;
      if (skipCategories.has(cat.toLowerCase())) continue;
      if (favCatSlugs.has(`fav-cat-${cat.toLowerCase()}`)) continue;
      const affinityEvents = topicalPool
        .filter(isGeneralFeedEvent)
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

    // Pool: Get Away for the Weekend — preference-matched events 50-200 mi out
    // that START on a Fri/Sat/Sun. A weekend-trip discovery rail. The fetch
    // already matched the user's preferred subcategories.
    const getawayRail: CarouselSection | null = (() => {
      if (getawayEvents.length === 0) return null;
      // Weekend = the event's calendar date lands on Fri/Sat/Sun. Use the date
      // part at noon UTC so an evening event near midnight isn't mis-dayed by a
      // timezone shift. getUTCDay: 0=Sun … 5=Fri, 6=Sat.
      const startsOnWeekend = (s: string): boolean => {
        if (!s) return false;
        const dow = new Date(s.slice(0, 10) + "T12:00:00Z").getUTCDay();
        return dow === 5 || dow === 6 || dow === 0;
      };
      const events = getawayEvents
        .filter(isGeneralFeedEvent)
        .filter(e => startsOnWeekend(e.start_at ?? ""))
        .map(e => withDistance(e, userLat, userLng))
        .filter(e => {
          const d = e.distance_miles ?? 0;
          return d > 50 && d <= 200;
        })
        .sort(sortFn)
        .slice(0, CAROUSEL_DISPLAY_LIMIT);
      if (events.length < 3) return null;
      return {
        slug: "get-away",
        title: "Get Away for the Weekend",
        subtitle: null,
        filter_type: "getaway",
        events,
      };
    })();

    // Taste entity rails — "If You Like Shane Gillis". One rail per top entity
    // (performer/brand) the user gravitates to; the fetch already scoped events
    // to that entity, so here we just apply radius + rank + a min-count gate.
    const entityRails: CarouselSection[] = tasteEntityGroups
      .map((group) => {
        const events = group.events
          .map(e => withDistance(e, userLat, userLng))
          .filter(e => maxRadius === Infinity || (e.distance_miles ?? 999) <= maxRadius)
          .sort(sortFn)
          .slice(0, CAROUSEL_DISPLAY_LIMIT);
        if (events.length < 3) return null;
        const label = group.entity
          .split(/[_-]+/)
          .filter(Boolean)
          .map(w => w.charAt(0).toUpperCase() + w.slice(1))
          .join(" ");
        return {
          slug: `taste-entity-${group.entity}`,
          title: `If You Like ${label}`,
          subtitle: null,
          filter_type: "taste_entity",
          events,
        } as CarouselSection;
      })
      .filter((c): c is CarouselSection => c !== null);

    // Pool: Popular
    const popularEvents = filtered
      .filter(e => totalSaves(e.id, communityScores) > 10)
      .sort((a, b) => totalSaves(b.id, communityScores) - totalSaves(a.id, communityScores))
      .slice(0, CAROUSEL_DISPLAY_LIMIT);

    // Pool: Free
    const freeEvents = filtered.filter(e => e.is_free).slice(0, CAROUSEL_DISPLAY_LIMIT);

    // Pool: Experiences (curated + tours — matches the combined Experiences tab)
    const experiences = allEvents
      .filter(e => e.source === "curated" || (e.subcategory ?? "").toLowerCase() === "tours")
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

    // Taste entity rails ("If You Like Shane Gillis") — placed right after
    // Picked for You; they're the strongest personal hook the feed has.
    for (const rail of entityRails) addCarousel(rail);

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

    // 2.5 Farmers Markets — a guaranteed rail shown to EVERY user regardless
    // of taste profile (product requirement). Farmers markets are recurring
    // `always_on` events, so isGeneralFeedEvent strips them from `filtered`
    // and they never reach the topical/interest-gated rails above — we source
    // straight from allEvents (which includes the always_on geo fetch) and
    // bypass the For-You interest gate entirely. Nearest-first: a market's
    // value is proximity. Marked seen so buildRecurringCarousels' "Weekly
    // Happenings" rail at the bottom doesn't repeat them.
    // Pool: allEvents (capped always_on fetch) UNIONED with the dedicated
    // farmers-market fetch (deduped by id), so markets truncated by the
    // always_on title cap are still present. The direct fetch is the
    // load-bearing source; allEvents just avoids a re-fetch for the ones it
    // already had.
    const farmersMarketPool = (() => {
      const seen = new Set(allEvents.map(e => e.id));
      return [...allEvents, ...farmersMarketEvents.filter(e => !seen.has(e.id))];
    })();
    const farmersMarketRail = farmersMarketPool
      .filter(e =>
        (e.subcategory ?? "").toLowerCase() === "farmers-market" ||
        (Array.isArray(e.tags) && e.tags.some(t => (t ?? "").toLowerCase() === "farmers-market")))
      .map(e => withDistance(e, userLat, userLng))
      .filter(e => maxRadius === Infinity || (e.distance_miles ?? 999) <= maxRadius)
      .sort((a, b) => (a.distance_miles ?? 999) - (b.distance_miles ?? 999))
      .slice(0, CAROUSEL_DISPLAY_LIMIT);
    if (farmersMarketRail.length > 0) {
      addCarousel({
        slug: "farmers-markets",
        title: "Farmers Markets Near You",
        subtitle: null,
        filter_type: "farmers_markets",
        events: farmersMarketRail,
      });
      farmersMarketRail.forEach(e => seenEventIds.add(e.id));
    }

    // 2.6 Restaurant Weeks — a guaranteed rail shown to EVERY user regardless of
    // taste profile (same rationale as farmers markets). Mark its events seen now
    // so the recurring rails below don't repeat them, but DON'T pin it to the top
    // — it's spliced in a bit lower (after the first wave of personalized rails)
    // at the end of this block.
    if (restaurantWeekSection.events.length > 0) {
      restaurantWeekSection.events.forEach(e => seenEventIds.add(e.id));
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
            title: "For Free",
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

    // Get Away — preference-matched roadtrip rail (50-200 mi), placed after the
    // primary personalized rails.
    if (getawayRail) addCarousel(getawayRail);

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

    // "25 Must-Know" — guaranteed editorial rail placed just ABOVE the Farmers
    // Markets rail (falls back to ~3rd slot if there's no farmers rail).
    if (mustKnowSection.events.length > 0) {
      const fmIdx = result.findIndex(s => s.slug === "farmers-markets");
      result.splice(fmIdx >= 0 ? fmIdx : Math.min(2, result.length), 0, mustKnowSection);
    }

    // Place Restaurant Weeks a bit lower than the hero region — below the first
    // wave of personalized/interest rails, not pinned under "Picked for You".
    // Clamped so a sparse feed still gets it near the end.
    if (restaurantWeekSection.events.length > 0) {
      result.splice(Math.min(6, result.length), 0, restaurantWeekSection);
    }
  }

  // Sports-specific carousels (raw uncleaned events)
  if (filter.type === "category" && filter.value === "sports") {
    console.log(`Sports filter: rawSportsEvents=${rawSportsEvents.length}, seenIds=${seenEventIds.size}`);
    const sportsCarousels = buildSportsCarousels(rawSportsEvents, seenEventIds, venueCache);
    console.log(`Sports carousels built: ${sportsCarousels.length} carousels, ${sportsCarousels.map(c => `${c.title}(${c.events.length})`).join(", ")}`);
    result.push(...sportsCarousels);
  }

  // Standard DB-driven carousels
  //
  // For-You interest gating: the static `carousels` config includes fixed
  // category rails (Live Music, Sports, Nightlife, Outdoors, …) that would
  // otherwise render for EVERY user regardless of stated interest. On the For
  // You feed we instead surface only the category rails the user actually cares
  // about — onboarding `selected_categories` ∪ affinity (taps/saves), each
  // mapped up to its parent category. FALLBACK: if the user has no interest
  // signal at all (cold start, nothing to personalize from), we show the full
  // category catalog so the feed isn't sparse. Category tabs are unaffected —
  // the explicit tab IS the interest signal there.
  const toParentCat = (c: string): string => {
    const lc = c.toLowerCase();
    return parentCategories.has(lc) ? lc : (subcategoryToParent[lc] ?? lc);
  };
  const interestParents = new Set<string>();
  for (const c of prefs?.selected_categories ?? []) interestParents.add(toParentCat(c));
  for (const [c] of affinityMap) interestParents.add(toParentCat(c));
  const hasInterestSignal = interestParents.size > 0;

  const standardCarousels: CarouselSection[] = [];
  for (const carousel of carousels) {
    if (carousel.filter_type === "category" && filter.type !== "none" && filter.type !== "category") {
      continue;
    }
    // For You + has interest signal: skip category rails outside the user's
    // interests. No signal → fall through (show all, discovery fallback).
    if (filter.type === "none"
        && carousel.filter_type === "category"
        && hasInterestSignal
        && !interestParents.has((carousel.filter_value ?? "").toLowerCase())) {
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
    // Skip the DB "Free Events" tag carousel on For You — it duplicates the
    // dynamic "For Free" rail (slug=free-events) built above in the
    // interleave. Subcategory tabs still get the DB version (scoped to the
    // tab's category, e.g. "Free Music Events").
    if (filter.type === "none"
        && carousel.filter_type === "tag"
        && (carousel.filter_value ?? "").toLowerCase() === "free") {
      continue;
    }
    const events = resolveCarouselFromPool(filtered, carousel, filter, prefs, affinityMap)
      .slice(0, CAROUSEL_DISPLAY_LIMIT);
    if (events.length === 0) continue;

    let title = carousel.title;
    if (filter.type === "category") {
      const catName = categoryDisplayName(filter.value);
      // The category is implied by the page the user is on, so titles on
      // category tabs render bare ("Top Events Happening Soon", not "Kids:
      // Top Events Happening Soon"). Kept "This Weekend" / "Trending Now"
      // qualified because they originally were — flag if those should strip.
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

  // Dynamic sub-carousels for subcategory tab.
  //
  // PRODUCT RULE EXCEPTION: `filtered` strips recurring events via
  // isGeneralFeedEvent (the right call for Top Events / This Month / For You).
  // But the themed sub_subcategory rails — "Story Time", "Open Mic Nights",
  // "Minecraft Club" — are specifically scoped to one subcategory AND one
  // sub_subcategory. That's exactly where recurring events belong, because
  // the rail title IS the recurrence pattern. Routing them only into the
  // generic "Weekly Happenings" rail loses the specificity that made these
  // rails useful (regression introduced 2026-05-27).
  //
  // So: build a parallel pool for buildDynamicSubcarousels that matches the
  // subcategory + geo filter but keeps recurring events. Curated events
  // (`source === "curated"`) stay excluded — those belong on Experiences.
  if (filter.type === "category" && filter.value !== "sports") {
    const filterValue = filter.value;
    const dynamicSubPool = allEvents
      .filter(e => e.source !== "curated")
      .filter(e => {
        if (parentCategories.has(filterValue)) {
          const subcat = (e.subcategory ?? "").toLowerCase();
          if (subcat === filterValue) return true;
          return (subcategoryToParent[subcat] ?? "") === filterValue;
        }
        return (e.subcategory ?? "").toLowerCase() === filterValue;
      })
      .map(e => withDistance(e, userLat, userLng))
      .filter(e => maxRadius === Infinity || (e.distance_miles ?? 999) <= maxRadius)
      .sort(sortFn);

    const dynamicSections = buildDynamicSubcarousels(
      dynamicSubPool, filter.value, categoryDisplayName(filter.value),
    );
    // Mark events surfaced by themed sub-rails as seen so the downstream
    // Daily / Weekly / Monthly rails don't show the same event twice. The
    // themed rail wins because it's more specific ("Story Time" beats
    // "Weekly Happenings" for the same row).
    for (const section of dynamicSections) {
      for (const e of section.events) seenEventIds.add(e.id);
    }
    result.push(...dynamicSections);
  }

  // Experiences tab dynamic sub-carousels (also covers the new combined
  // Experiences+Tours tab — the `experiences_combined` filter already
  // narrows the pool to curated + tours, so buildDynamicSubcarousels'
  // group-by-sub_subcategory and group-by-venue passes produce themed
  // rails like "Walking Tours", "At Freedom Trail", etc.).
  if (filter.type === "source" || filter.type === "experiences_combined") {
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

  // Food tab: headline the Restaurant Weeks rail at position 1 when there are
  // enough nearby to lead with (>2), otherwise drop it lower in the order so a
  // lone week doesn't dominate the tab. Restaurant weeks are excluded from the
  // tab's other rails by isGeneralFeedEvent, so this never double-shows.
  if (filter.type === "category" && filter.value === "food" && restaurantWeekSection.events.length > 0) {
    if (restaurantWeekSection.events.length > 2) {
      result.unshift(restaurantWeekSection);
    } else {
      result.push(restaurantWeekSection);
    }
  }

  // Hard distance ceiling — final guarantee. Some rails (neighborhood,
  // favorite-venue, city-scoped fetch) intentionally skip the per-rail radius
  // filter, and bounding-box fetches can include corner events past the
  // requested radius. This pass drops anything beyond MAX_EVENT_DISTANCE_MILES
  // from the user regardless of which rail produced it, then removes any rail
  // left empty. Events with unknown coords (null distance) are kept, matching
  // the existing radius-filter convention elsewhere.
  for (const section of result) {
    // Must-Know is statewide and many rows lack coords — exempt it from the
    // proximity ceiling so an iconic in-state pick isn't dropped for being far.
    if (section.slug === "must-know") continue;
    section.events = section.events.filter(
      e => e.distance_miles == null || e.distance_miles <= MAX_EVENT_DISTANCE_MILES,
    );
  }
  return result.filter(section => section.events.length > 0);
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
  restaurant: "food", cafe: "food", bakery: "food", "restaurant-week": "food",
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
  "food", "outdoors", "museums", "libraries", "for-kids",
  // "tours" intentionally omitted — tour events are now surfaced via
  // the "Experiences" tab alongside curated experiences (single
  // consolidated entry point with the tours icon).
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
    // Tours are surfaced via the combined Experiences tab below — skip
    // them here so they don't get bucketed into "other" (the fallback
    // when a subcategory has no parent mapping after Tours was removed
    // from parentCategories).
    if (rawSubcat === "tours") continue;
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

  // Combined Experiences tab = curated experiences + tour events.
  // Uses the Tours icon (product decision: tours icon best represents
  // the consolidated "things to go do" feel). Tab id stays "experiences"
  // so the existing client-side routing & analytics keep working.
  const experiencesCount = allEvents.filter(e =>
    e.source === "curated" || (e.subcategory ?? "").toLowerCase() === "tours"
  ).length;
  if (experiencesCount > 0) {
    const insertIndex = Math.min(3, categories.length);
    categories.splice(insertIndex, 0, {
      id: "experiences",
      label: "Experiences",
      icon_url: iconUrlFor("tours"),
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
  subcategoryFocus: string | null = null,
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

  // When the request is for a subcategory tab, fire a venue-diverse fetch
  // in parallel. mv_events_for_geo's rank-stratified output starves the
  // venue diversity that the "At <venue>" rails need (BPL's high venue
  // rank crowds out Brighton Branch, Hummingbird Books, etc., for the
  // for-kids subcategory). This parallel call returns top 12 venues × top
  // 6 events for the focused subcategory only — bounded payload, zero
  // added latency because it runs in the same Promise.all.
  const venueDiverseRpc = subcategoryFocus
    ? supabase.rpc("mv_events_for_subcategory_venues", {
        p_lat: lat,
        p_lng: lng,
        p_radius_miles: radiusMiles,
        p_subcategory: subcategoryFocus,
        p_top_venues: 12,
        p_events_per_venue: 6,
      })
    : Promise.resolve({ data: [], error: null });

  const [mvResp, venueDiverseResp, ...alwaysOnPages] = await Promise.all([
    supabase.rpc("mv_events_for_geo", {
      p_lat: lat,
      p_lng: lng,
      p_radius_miles: radiusMiles,
      p_max_results: 7000,
    }),
    venueDiverseRpc,
    ...Array.from({ length: 3 }, (_, i) => fetchAlwaysOnPage(i)),
  ]);
  if (mvResp.error) throw mvResp.error;
  // Tolerate venue-diverse RPC errors silently — venue rails are
  // additive; degraded mode is "main RPC's venue diversity only", not
  // a broken page.
  if (venueDiverseResp.error) {
    console.error("[carousels] mv_events_for_subcategory_venues failed:", venueDiverseResp.error);
  }

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

  // Union venue-diverse events on top of the main MV result. Dedup by id
  // so events that appeared in both queries don't double-count when
  // buildDynamicSubcarousels groups by venue.
  const venueDiverseRows = (venueDiverseResp.data ?? []) as Array<EventRow & {
    rank_score: number | null;
    distance_miles: number | null;
    venue_description: string | null;
    venue_photo_url: string | null;
  }>;
  const seenIds = new Set(mvProcessed.map(e => e.id));
  const venueDiverseProcessed: ProcessedEvent[] = venueDiverseRows
    .filter(row => !seenIds.has(row.id))
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
      distance_miles: (row.event_lat != null && row.event_lng != null)
        ? distanceMiles(lat, lng, row.event_lat, row.event_lng)
        : (row.venue_lat != null && row.venue_lng != null)
          ? distanceMiles(lat, lng, row.venue_lat, row.venue_lng)
          : null,
      venue_description: null,
      venue_photo_url: null,
    }))
    .filter(e => e.distance_miles == null || e.distance_miles <= radiusMiles);

  return [...mvProcessed, ...venueDiverseProcessed, ...alwaysOnProcessed];
}

// City-scoped fetch for the neighborhood rails ("The <City> Scene",
// "In <City>", etc.). mv_events_for_geo is a rank-stratified metro-wide
// SAMPLE — for a dense metro it returns only ~900 rows, so a single city's
// lower-interest (often recurring) events get crowded out. Measured: only 9
// of ~120 upcoming Brookline events survive into the MV. A city-named rail
// must reflect that city, so we pull its events directly from `events`.
//
// Matches the rail's own filter (venue_city CONTAINS the name), bounded by
// the same geo box + a 90-day horizon (mirrors the MV window) and capped per
// city to keep the payload small. Ordering is by start_at (soonest first) to
// pick a sensible candidate set; final display rank is re-applied in-memory by
// the rail's sortFn, so this is "top N by rank" at the display layer.
async function fetchCityEvents(
  supabase: ReturnType<typeof createClient>,
  lat: number,
  lng: number,
  radiusMiles: number,
  cities: string[],
): Promise<EventRow[]> {
  if (cities.length === 0) return [];
  const latDelta = radiusMiles / 69.0;
  const lngDelta = radiusMiles / 50.0;
  const minLat = lat - latDelta, maxLat = lat + latDelta;
  const minLng = lng - lngDelta, maxLng = lng + lngDelta;
  const startFrom = nowIso();
  const startTo = daysFromNowIso(90);
  const PER_CITY = 80;

  const pages = await Promise.all(cities.map(async (city) => {
    const { data, error } = await supabase
      .from("events")
      .select("*")
      .eq("is_active", true)
      .ilike("venue_city", `%${city}%`)
      .gte("start_at", startFrom)
      .lte("start_at", startTo)
      .gte("venue_lat", minLat)
      .lte("venue_lat", maxLat)
      .gte("venue_lng", minLng)
      .lte("venue_lng", maxLng)
      .order("start_at", { ascending: true })
      .limit(PER_CITY);
    if (error) {
      console.error(`[carousels] fetchCityEvents(${city}) failed:`, error);
      return [];
    }
    return (data ?? []) as EventRow[];
  }));
  // Dedup across cities by id (overlapping substring matches are possible).
  const byId = new Map<string, EventRow>();
  for (const row of pages.flat()) byId.set(row.id, row);
  return [...byId.values()];
}

// Venue-scoped direct fetch — sibling of fetchCityEvents, for the
// "Coming Up at <venue>" fav-venue rails. The shared rank-stratified MV
// (mv_events_for_geo) returns a metro-wide sample, so a single favorited
// venue's events get crowded out (e.g. Public Library of Brookline had 47
// upcoming but only ~1 survived the sample). This queries the events table
// directly per favorited venue so the full slate is available to the rail.
async function fetchVenueEvents(
  supabase: ReturnType<typeof createClient>,
  lat: number,
  lng: number,
  radiusMiles: number,
  venues: string[],
): Promise<EventRow[]> {
  if (venues.length === 0) return [];
  const latDelta = radiusMiles / 69.0;
  const lngDelta = radiusMiles / 50.0;
  const minLat = lat - latDelta, maxLat = lat + latDelta;
  const minLng = lng - lngDelta, maxLng = lng + lngDelta;
  const startFrom = nowIso();
  const startTo = daysFromNowIso(90);
  const PER_VENUE = 80;

  const pages = await Promise.all(venues.map(async (venue) => {
    const { data, error } = await supabase
      .from("events")
      .select("*")
      .eq("is_active", true)
      .ilike("venue_name", `%${venue}%`)
      .gte("start_at", startFrom)
      .lte("start_at", startTo)
      .gte("venue_lat", minLat)
      .lte("venue_lat", maxLat)
      .gte("venue_lng", minLng)
      .lte("venue_lng", maxLng)
      .order("start_at", { ascending: true })
      .limit(PER_VENUE);
    if (error) {
      console.error(`[carousels] fetchVenueEvents(${venue}) failed:`, error);
      return [];
    }
    return (data ?? []) as EventRow[];
  }));
  const byId = new Map<string, EventRow>();
  for (const row of pages.flat()) byId.set(row.id, row);
  return [...byId.values()];
}

// Subcategory-scoped direct fetch — sibling of fetchCityEvents /
// fetchVenueEvents, for the topical favorite-category and affinity rails
// ("Museum Picks For You", "Kid-Approved Events", "Your Music Radar", …).
// On the For-You feed those rails read from the shared rank-stratified MV
// (mv_events_for_geo), which is a metro-wide SAMPLE: a niche subcategory's
// events (museums, for-kids, comedy, …) get crowded out by higher-ranked
// venues, so the rail renders 1-2 cards even when hundreds qualify in radius.
// This pulls each rail subcategory's full near-term slate directly from
// `events` so the pool is complete before the rail's own isGeneralFeedEvent +
// exact-subcategory filters run. One query per subcategory (not a single
// `.in()` with a global cap) so no single category starves the others — and
// it covers EVERY category the user has a rail for, not just museums/for-kids.
async function fetchSubcategoryEvents(
  supabase: ReturnType<typeof createClient>,
  lat: number,
  lng: number,
  radiusMiles: number,
  subcategories: string[],
): Promise<EventRow[]> {
  if (subcategories.length === 0) return [];
  const latDelta = radiusMiles / 69.0;
  const lngDelta = radiusMiles / 50.0;
  const minLat = lat - latDelta, maxLat = lat + latDelta;
  const minLng = lng - lngDelta, maxLng = lng + lngDelta;
  const startFrom = nowIso();
  const startTo = daysFromNowIso(90);
  const PER_SUBCAT = 80;

  const pages = await Promise.all(subcategories.map(async (subcat) => {
    // ilike with no wildcards = case-insensitive exact match, mirroring the
    // rails' own `(e.subcategory).toLowerCase() === cat` comparison.
    const { data, error } = await supabase
      .from("events")
      .select("*")
      .eq("is_active", true)
      .ilike("subcategory", subcat)
      .gte("start_at", startFrom)
      .lte("start_at", startTo)
      .gte("venue_lat", minLat)
      .lte("venue_lat", maxLat)
      .gte("venue_lng", minLng)
      .lte("venue_lng", maxLng)
      .order("start_at", { ascending: true })
      .limit(PER_SUBCAT);
    if (error) {
      console.error(`[carousels] fetchSubcategoryEvents(${subcat}) failed:`, error);
      return [];
    }
    return (data ?? []) as EventRow[];
  }));
  const byId = new Map<string, EventRow>();
  for (const row of pages.flat()) byId.set(row.id, row);
  return [...byId.values()];
}

// Get Away — preference-matched events out to 200 mi so buildCarousels can show
// a "little roadtrip" rail. The 50-mi LOWER bound (excluding local events) is
// applied in buildCarousels, since a bbox can't express a min-radius. One query
// per preferred subcategory so no single category starves the wider band.
async function fetchGetawayEvents(
  supabase: ReturnType<typeof createClient>,
  lat: number,
  lng: number,
  subcategories: string[],
): Promise<EventRow[]> {
  if (subcategories.length === 0) return [];
  const radiusMiles = 200;
  const latDelta = radiusMiles / 69.0;
  const lngDelta = radiusMiles / 50.0;
  const minLat = lat - latDelta, maxLat = lat + latDelta;
  const minLng = lng - lngDelta, maxLng = lng + lngDelta;
  const startFrom = nowIso();
  const startTo = daysFromNowIso(90);
  const PER_SUBCAT = 60;

  const pages = await Promise.all(subcategories.map(async (subcat) => {
    const { data, error } = await supabase
      .from("events")
      .select("*")
      .eq("is_active", true)
      .ilike("subcategory", subcat)
      .gte("start_at", startFrom)
      .lte("start_at", startTo)
      .gte("venue_lat", minLat)
      .lte("venue_lat", maxLat)
      .gte("venue_lng", minLng)
      .lte("venue_lng", maxLng)
      .order("start_at", { ascending: true })
      .limit(PER_SUBCAT);
    if (error) {
      console.error(`[carousels] fetchGetawayEvents(${subcat}) failed:`, error);
      return [];
    }
    return (data ?? []) as EventRow[];
  }));
  const byId = new Map<string, EventRow>();
  for (const row of pages.flat()) byId.set(row.id, row);
  return [...byId.values()];
}

// Top entities (performers/brands) the user gravitates to, from the taste graph
// (user_taste_summary). Drives the "If You Like <X>" rails. Empty until the
// nightly profile build has run.
async function fetchTasteEntities(
  supabase: ReturnType<typeof createClient>,
  userId: string | null,
): Promise<string[]> {
  if (!userId) return [];
  try {
    const { data } = await (supabase as any)
      .from("user_taste_summary")
      .select("top_entities,interaction_count")
      .eq("user_id", userId)
      .maybeSingle();
    if (!data || (data.interaction_count ?? 0) < 3) return [];
    return ((data.top_entities ?? []) as string[]).slice(0, 3);
  } catch {
    return [];
  }
}

// Collaborative adjacency (feature_affinity): for each seed entity, the top
// related ENTITIES the user base also engages ("Shane Gillis fans also like
// Bill Burr"). Enriches the "If You Like <X>" rail with adjacent acts without
// flooding it (entity-scoped, unlike a broad subcategory). Empty until the
// matrix job has run — then the rail falls back to exact matches.
async function fetchFeatureAffinity(
  supabase: ReturnType<typeof createClient>,
  seeds: string[],
): Promise<Map<string, string[]>> {
  const out = new Map<string, string[]>();
  if (seeds.length === 0) return out;
  try {
    const { data } = await (supabase as any)
      .from("feature_affinity")
      .select("seed,related,weight")
      .in("seed", seeds)
      .eq("related_type", "entity")
      .order("weight", { ascending: false });
    for (const row of ((data ?? []) as { seed: string; related: string }[])) {
      const arr = out.get(row.seed) ?? [];
      if (arr.length < 6) { arr.push(row.related); out.set(row.seed, arr); }
    }
  } catch {
    // feature_affinity absent / not built yet — rails fall back to exact matches.
  }
  return out;
}

// Per-entity direct fetch for the "If You Like <X>" rails: events whose
// performers OR brands array contains the entity, within radius. GIN-indexed
// (idx_events_performers_gin / _brands_gin). Empty per-entity if the columns
// aren't present yet — graceful pre-backfill.
async function fetchEntityEvents(
  supabase: ReturnType<typeof createClient>,
  lat: number,
  lng: number,
  radiusMiles: number,
  entities: string[],
  relatedBySeed: Map<string, string[]>,
): Promise<{ entity: string; rows: EventRow[] }[]> {
  if (entities.length === 0) return [];
  const latDelta = radiusMiles / 69.0;
  const lngDelta = radiusMiles / 50.0;
  const minLat = lat - latDelta, maxLat = lat + latDelta;
  const minLng = lng - lngDelta, maxLng = lng + lngDelta;
  const startFrom = nowIso();
  const startTo = daysFromNowIso(90);
  return await Promise.all(entities.map(async (entity) => {
    // Exact seed + collaborative related acts — all entity-scoped, so adjacency
    // enriches the rail without flooding it with generic same-subcategory events.
    const conds = [`performers.cs.{${entity}}`, `brands.cs.{${entity}}`];
    for (const rel of relatedBySeed.get(entity) ?? []) {
      conds.push(`performers.cs.{${rel}}`, `brands.cs.{${rel}}`);
    }
    const { data, error } = await supabase
      .from("events")
      .select("*")
      .eq("is_active", true)
      .or(conds.join(","))
      .gte("start_at", startFrom)
      .lte("start_at", startTo)
      .gte("venue_lat", minLat)
      .lte("venue_lat", maxLat)
      .gte("venue_lng", minLng)
      .lte("venue_lng", maxLng)
      .order("start_at", { ascending: true })
      .limit(60);
    if (error) {
      console.error(`[carousels] fetchEntityEvents(${entity}) failed:`, error);
      return { entity, rows: [] as EventRow[] };
    }
    return { entity, rows: (data ?? []) as EventRow[] };
  }));
}

// Farmers markets are always_on, and the always_on slow-path fetch is capped
// at 3 pages × 1000 ordered by title — in a dense metro there are ~5k always_on
// rows, so every market alphabetically past the cutoff (e.g. "Waltham") is
// truncated before it reaches the rail. This dedicated fetch pulls the full
// in-radius farmers-market set (a tiny pool — ~100 statewide) unbounded by that
// cap, so the rail always has every nearby market to sort by proximity.
// No start_at gate: always_on rows carry a recurring next-occurrence; dormant
// (off-season) markets are is_active=false and excluded here.
async function fetchFarmersMarketEvents(
  supabase: ReturnType<typeof createClient>,
  lat: number,
  lng: number,
  radiusMiles: number,
): Promise<EventRow[]> {
  const latDelta = radiusMiles / 69.0;
  const lngDelta = radiusMiles / 50.0;
  const { data, error } = await supabase
    .from("events")
    .select("*")
    .eq("is_active", true)
    .eq("subcategory", "farmers-market")
    .gte("venue_lat", lat - latDelta)
    .lte("venue_lat", lat + latDelta)
    .gte("venue_lng", lng - lngDelta)
    .lte("venue_lng", lng + lngDelta)
    .limit(500);
  if (error) {
    console.error("[carousels] fetchFarmersMarketEvents failed:", error);
    return [];
  }
  return (data ?? []) as EventRow[];
}

// The Restaurant Weeks rail is state-scoped (every in-state week regardless of
// distance), so this fetch pulls by venue_state, NOT a bbox — a bbox would clip
// the far corners of a state (e.g. Pittsburgh for a Philly user). Tiny pool, so
// no geo predicate is needed. The rail applies its own sort + end_at filter.
async function fetchRestaurantWeekEvents(
  supabase: ReturnType<typeof createClient>,
  stateCode: string,
): Promise<EventRow[]> {
  const { data, error } = await supabase
    .from("events")
    .select("*")
    .eq("is_active", true)
    .eq("subcategory", "restaurant-week")
    .eq("venue_state", stateCode)
    .limit(200);
  if (error) {
    console.error("[carousels] fetchRestaurantWeekEvents failed:", error);
    return [];
  }
  return (data ?? []) as EventRow[];
}

// Display names for the live states (for the Must-Know rail title).
const STATE_CODE_NAMES: Record<string, string> = {
  MA: "Massachusetts", RI: "Rhode Island", PA: "Pennsylvania",
  NY: "New York", NJ: "New Jersey", CA: "California",
};

// The "25 Must-Know" rail reads the SEPARATE curated_events table — dated,
// nationally/regionally iconic editorial picks (Jacob's Pillow, Feast of the
// Madonna, …) refreshed by scripts/refresh_curated_events.js. This is NOT the
// events-pool `source="curated"` evergreen experiences that feed the Experiences
// tab; it's a distinct table the rest of the edge fn never touches. State-scoped,
// so fetched by region. Mapped to ProcessedEvent so it flows through the same
// rail/card machinery. Many rows have no coords, so the rail sorts by date.
async function fetchCuratedEvents(
  supabase: ReturnType<typeof createClient>,
  stateCode: string,
): Promise<ProcessedEvent[]> {
  const nowIso = new Date().toISOString();
  const { data, error } = await supabase
    .from("curated_events")
    .select("*")
    .eq("is_active", true)
    .eq("region", stateCode)
    .or(`end_at.gte.${nowIso},end_at.is.null`)
    .order("start_at", { ascending: true })
    .limit(50);
  if (error) {
    console.error("[carousels] fetchCuratedEvents failed:", error);
    return [];
  }
  return (data ?? []).map((r: Record<string, unknown>): ProcessedEvent => ({
    id: r.id as string,
    title: r.title as string,
    description: (r.description as string) ?? null,
    category: "curated",
    subcategory: "must-know",
    sub_subcategory: null,
    tags: null,
    team_name: null,
    start_at: (r.start_at as string) ?? null,
    end_at: (r.end_at as string) ?? null,
    is_free: false,
    price_min: null,
    price_max: null,
    ticket_url: (r.source_url as string) ?? null,
    image_url: (r.image_url as string) ?? null,
    venue_name: (r.venue_name as string) ?? null,
    venue_address: (r.venue_address as string) ?? null,
    venue_city: (r.venue_city as string) ?? "",
    venue_state: (r.venue_state as string) ?? stateCode,
    venue_lat: (r.venue_lat as number) ?? null,
    venue_lng: (r.venue_lng as number) ?? null,
    event_city: null,
    event_state: null,
    event_lat: null,
    event_lng: null,
    source: "curated",
    source_url: (r.source_url as string) ?? null,
    city: (r.venue_city as string) ?? "",
    time_known: false,
    distance_miles: null,
    venue_description: null,
    venue_photo_url: null,
  }));
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

// City → stable Storage photo URL, the last-resort image fallback for events
// that don't resolve to a venue (municipal at-large rows). Only Storage-hosted
// rows are loaded; legacy rotted Google Places URLs (which also embed an API
// key) are filtered out so they can never reach a client. Loaded once per
// slow-path build and cached alongside the venue cache.
async function fetchCityPhotoCache(
  supabase: ReturnType<typeof createClient>,
): Promise<Map<string, string>> {
  const cache = new Map<string, string>();
  const { data, error } = await supabase
    .from("city_photos")
    .select("city, photo_url")
    .like("photo_url", "%/storage/v1/object/public/city-photos/%");
  if (error || !data) return cache;
  for (const row of data as Array<{ city: string | null; photo_url: string | null }>) {
    if (row.city && row.photo_url) cache.set(row.city.toLowerCase(), row.photo_url);
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

// Anchor cities per live state for nearest-anchor (1-NN) state resolution.
// Resolving the user's state by nearest labeled CITY is far more reliable at the
// borders that matter than bbox hit-testing: Philadelphia→PA (not NJ), Jersey
// City→NJ, NYC→NY all come out right, whereas loose overlapping bboxes +
// nearest-CENTER mis-resolve those cross-river metros. Used only by the
// state-scoped Restaurant Weeks rail (every in-state week, no out-of-state).
const STATE_ANCHORS: { code: string; lat: number; lng: number }[] = [
  { code: "MA", lat: 42.3601, lng: -71.0589 }, // Boston
  { code: "MA", lat: 42.2626, lng: -71.8023 }, // Worcester
  { code: "MA", lat: 42.1015, lng: -72.5898 }, // Springfield
  { code: "RI", lat: 41.8240, lng: -71.4128 }, // Providence
  { code: "PA", lat: 39.9526, lng: -75.1652 }, // Philadelphia
  { code: "PA", lat: 40.4406, lng: -79.9959 }, // Pittsburgh
  { code: "PA", lat: 40.2732, lng: -76.8867 }, // Harrisburg
  { code: "PA", lat: 40.6084, lng: -75.4902 }, // Allentown
  { code: "PA", lat: 41.4090, lng: -75.6624 }, // Scranton
  { code: "NY", lat: 40.7128, lng: -74.0060 }, // New York City
  { code: "NY", lat: 42.6526, lng: -73.7562 }, // Albany
  { code: "NY", lat: 43.0481, lng: -76.1474 }, // Syracuse
  { code: "NY", lat: 42.8864, lng: -78.8784 }, // Buffalo
  { code: "NY", lat: 43.1610, lng: -77.6109 }, // Rochester
  { code: "NJ", lat: 40.7357, lng: -74.1724 }, // Newark
  { code: "NJ", lat: 40.7178, lng: -74.0431 }, // Jersey City
  { code: "NJ", lat: 40.2206, lng: -74.7597 }, // Trenton
  { code: "NJ", lat: 39.3643, lng: -74.4229 }, // Atlantic City
  { code: "NJ", lat: 40.9176, lng: -74.1718 }, // Paterson
  { code: "CA", lat: 34.0522, lng: -118.2437 }, // Los Angeles
  { code: "CA", lat: 37.7749, lng: -122.4194 }, // San Francisco
  { code: "CA", lat: 32.7157, lng: -117.1611 }, // San Diego
  { code: "CA", lat: 38.5816, lng: -121.4944 }, // Sacramento
  { code: "CA", lat: 37.3382, lng: -121.8863 }, // San Jose
  { code: "CA", lat: 36.7468, lng: -119.7726 }, // Fresno
];

// User's state CODE by nearest anchor city. Returns null when the nearest anchor
// is implausibly far (user outside the live footprint) so an out-of-area user
// gets an empty Restaurant Weeks rail rather than some other state's weeks.
function userStateCode(lat: number, lng: number): string | null {
  let best: { code: string; d: number } | null = null;
  for (const a of STATE_ANCHORS) {
    const d = distanceMiles(lat, lng, a.lat, a.lng);
    if (!best || d < best.d) best = { code: a.code, d };
  }
  return best && best.d <= 200 ? best.code : null;
}

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
  // Fetch the profile AND the user's heart-saved venue ids in parallel.
  // Onboarding picks (user_profiles.selected_venues) and Places-tab heart
  // saves (community_interactions, item_type='place') write to different
  // tables; the For You feed only renders "Coming Up at <venue>" rails
  // for venues in prefs.selected_venues. Without merging here, anything
  // a user hearts after onboarding never gets a rail.
  const [profileResp, favRowsResp] = await Promise.all([
    supabase.from("user_profiles").select("*").eq("id", userId).maybeSingle(),
    supabase
      .from("community_interactions")
      .select("item_id")
      .eq("user_id", userId)
      .eq("item_type", "place")
      .eq("interaction_type", "favorite"),
  ]);
  if (profileResp.error || !profileResp.data) return null;
  const data = profileResp.data;

  // Resolve heart-saved item_ids → venue names. Single indexed lookup;
  // skipped entirely when the user has no hearts (avoids a wasted call
  // on cold-start anonymous-ish accounts).
  const itemIds = (favRowsResp.data ?? [])
    .map((r: { item_id?: string }) => r.item_id)
    .filter((id): id is string => typeof id === "string" && id.length > 0);
  let heartSavedNames: string[] = [];
  if (itemIds.length > 0) {
    const { data: venueRows } = await supabase
      .from("venues")
      .select("name")
      .in("id", itemIds);
    heartSavedNames = (venueRows ?? [])
      .map((r: { name?: string }) => r.name)
      .filter((n): n is string => typeof n === "string" && n.length > 0);
  }

  // Merge onboarding-selected venues with heart-saved venues, case-
  // insensitive dedupe so the same venue from both sources doesn't
  // produce two carousels.
  const onboardingVenues: string[] = data.selected_venues ?? [];
  const seenLc = new Set(onboardingVenues.map(v => v.toLowerCase()));
  const mergedVenues = [...onboardingVenues];
  for (const name of heartSavedNames) {
    const lc = name.toLowerCase();
    if (!seenLc.has(lc)) {
      mergedVenues.push(name);
      seenLc.add(lc);
    }
  }

  // --- REVERTIBLE BLOCK: event-favorite → category promotion (added 2026-06-09) ---
  // Mirrors the place-save → selected_venues merge above. Categories a user
  // favorites at the EVENT level (community_interactions, item_type='event')
  // get the same dedicated-rail + preference treatment that saved PLACES
  // already get, instead of only nudging sort order via category affinity.
  // TO REVERT: set MERGE_EVENT_FAVORITE_CATEGORIES = false (no-op), or delete
  // this whole block and change the return below back to
  //   selected_categories: data.selected_categories ?? [],
  const MERGE_EVENT_FAVORITE_CATEGORIES = true;
  const onboardingCategories: string[] = data.selected_categories ?? [];
  let mergedCategories: string[] = onboardingCategories;
  if (MERGE_EVENT_FAVORITE_CATEGORIES) {
    const { data: evFavRows } = await supabase
      .from("community_interactions")
      .select("category")
      .eq("user_id", userId)
      .eq("item_type", "event")
      .eq("interaction_type", "favorite");
    const seenCat = new Set(onboardingCategories.map((c) => c.toLowerCase()));
    mergedCategories = [...onboardingCategories];
    for (const row of (evFavRows ?? []) as { category?: string }[]) {
      const cat = (row.category ?? "").trim();
      if (!cat) continue;
      const lc = cat.toLowerCase();
      if (lc === "other") continue; // catch-all bucket, not a real preference
      if (!seenCat.has(lc)) {
        mergedCategories.push(cat);
        seenCat.add(lc);
      }
    }
  }
  // --- END REVERTIBLE BLOCK ---

  return {
    id: data.id,
    first_name: data.first_name ?? null,
    last_name: data.last_name ?? null,
    phone: data.phone ?? null,
    city: data.city ?? "boston",
    user_lat: data.user_lat ?? null,
    user_lng: data.user_lng ?? null,
    selected_neighborhoods: data.selected_neighborhoods ?? [],
    selected_venues: mergedVenues,
    selected_categories: mergedCategories,
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
  const map = new Map<string, number>();

  const { data, error } = await supabase
    .rpc("get_category_affinity", { p_user_id: userId });
  if (!error && data) {
    // Normalize to 0-1 by dividing by max
    const entries = (data as CategoryAffinity[]);
    const maxWeight = Math.max(...entries.map(e => e.weight), 1);
    for (const entry of entries) {
      map.set(entry.category.toLowerCase(), Math.min(entry.weight / maxWeight, 1.0));
    }
  }

  // Taste graph (spec §6): blend the richer per-user subcategory taste from
  // user_taste_summary into the affinity map, so the For-You feed reflects the
  // taste vector (coldStartScore already reads this map — no threading needed).
  // Graceful: absent table / no row / cold-start (<3 interactions) leaves the
  // map untouched, so behavior is unchanged until profiles are built.
  try {
    const { data: t } = await (supabase as any)
      .from("user_taste_summary")
      .select("subcategory_scores,interaction_count")
      .eq("user_id", userId)
      .maybeSingle();
    if (t && (t.interaction_count ?? 0) >= 3 && t.subcategory_scores) {
      const sc = t.subcategory_scores as Record<string, number>;
      const vals = Object.values(sc).filter((v) => v > 0);
      if (vals.length > 0) {
        const max = Math.max(...vals);
        for (const [k, v] of Object.entries(sc)) {
          if (v > 0) {
            const key = k.toLowerCase();
            map.set(key, Math.max(map.get(key) ?? 0, v / max));
          }
        }
      }
    }
  } catch {
    // leave map as-is — taste blend is best-effort
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
  cityPhotoCache: Map<string, string>;
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
    // `user_id` from the query string is the legacy (spoofable) identifier.
    // Phase 1 of the IDOR fix: when a client sends a real user access token
    // (X-User-Token), we verify it server-side and trust its subject over the
    // param (see the per-user fetch below). Old clients that only send the
    // param keep working until phase 2 drops the fallback.
    let userId = url.searchParams.get("user_id") ?? null;
    const category = url.searchParams.get("category") ?? "foryou";
    const locationName = url.searchParams.get("location_name") ?? "";
    // Clamp the requested radius to the hard ceiling so a wide client value
    // can't widen the fetch box or any rail past MAX_EVENT_DISTANCE_MILES.
    const requestedRadius = parseInt(url.searchParams.get("radius") ?? "75", 10);
    const radiusMiles = Math.min(
      Number.isFinite(requestedRadius) ? requestedRadius : 75,
      MAX_EVENT_DISTANCE_MILES,
    );

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
    // The precomputed payload is ANONYMOUS — it carries none of the per-user
    // rails (fav-venue, neighborhood, fav-category, affinity, "Picked for
    // You"). Those are only built on the personalized feeds (foryou /
    // locations → filter.type "none"). Serving the cache to a logged-in user
    // on those feeds silently drops their personalized rails (the bug where
    // "Coming Up at Fenway" / "In Newton" never appeared post-onboarding).
    // For personalized requests, fall through to the slow path, which still
    // amortizes the heavy event/venue fetch via the in-memory dataCache and
    // only rebuilds the cheap per-user rails fresh.
    const isPersonalized = userId != null && resolveFilter(category).type === "none";
    if (!isRefreshing && !isPersonalized) {
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
          // This branch only runs for non-personalized requests now
          // (anonymous callers, or subcategory tabs that are identical for
          // every user). Personalized foryou/locations requests skip the
          // cache above and take the slow path so their per-user rails build.
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
    let cityPhotoCache: Map<string, string>;
    let carouselsConfig: any[];

    const cachedGeoKey = (dataCache as any)?.geoKey as string | undefined;
    if (isCacheValid() && cachedGeoKey === cacheGeoKey) {
      allEvents = dataCache!.allEvents;
      rawSportsEvents = dataCache!.rawSportsEvents;
      venueCache = dataCache!.venueCache;
      cityPhotoCache = dataCache!.cityPhotoCache;
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
      // Compute the subcategoryFocus to drive the parallel venue-diverse
      // fetch inside fetchAllEvents. We pass the raw filter.value when
      // it's a category-typed filter and not sports (sports has its own
      // dedicated rail logic). For-You / Experiences / Locations tabs
      // pass null and skip the extra call.
      const _filterForFetch = resolveFilter(category);
      const subcategoryFocus =
        _filterForFetch.type === "category" && _filterForFetch.value !== "sports"
          ? _filterForFetch.value
          : null;
      const [probeEvents, venues, cityPhotos, config] = await Promise.all([
        fetchAllEvents(supabase, lat, lng, radiusMiles, subcategoryFocus),
        fetchVenueCacheLite(supabase),
        fetchCityPhotoCache(supabase),
        fetchCarouselsConfig(supabase, lat, lng),
      ]);
      // Densify the near pool. mv_events_for_geo returns a rank-stratified
      // sample capped at ~900 rows, so a wide fetch (the client's "Any" radius
      // arrives as 200 → clamped to 100) spends most of that budget on far
      // events — a Manayunk request got only ~130 of 900 rows within 15mi, the
      // rest on NYC/DC venues, starving the topical rails. Measure reach from
      // the wide probe, then, for dense metros that tighten, re-fetch
      // concentrated on that reach so the whole 900-row budget lands nearby
      // (~715 rows within 15mi for Manayunk). Sparse areas don't tighten, so
      // they skip the second call and keep the wide probe.
      const probeRadius = adaptiveRadiusMiles(probeEvents, radiusMiles);
      const rawEvents = probeRadius < radiusMiles
        ? await fetchAllEvents(supabase, lat, lng, probeRadius, subcategoryFocus)
        : probeEvents;
      const deduped = deduplicateCrossSource(rawEvents);
      allEvents = cleanEvents(deduped, venues, cityPhotos);
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
      cityPhotoCache = cityPhotos;
      carouselsConfig = config;
      dataCache = { allEvents, rawSportsEvents, venueCache, cityPhotoCache, carouselsConfig, timestamp: Date.now(), geoKey: cacheGeoKey } as any;
    }

    // IDOR fix (phase 1): if the caller presented a user access token, verify
    // it and trust its subject over the spoofable `user_id` query param. We do
    // this on the slow path only — warm-cache requests for anonymous content
    // never need a precise user id, so they skip the auth round-trip. A
    // missing/expired/invalid token falls back to the query param for now.
    const userToken = req.headers.get("X-User-Token");
    if (userToken) {
      try {
        const { data: { user } } = await supabase.auth.getUser(userToken);
        if (user?.id) userId = user.id;
      } catch (_) { /* invalid/expired token — keep param fallback (phase 1) */ }
    }

    // Per-user data — always fetched fresh (fast, single-row queries)
    const [userPreferences, communityScores, affinityMap] = await Promise.all([
      userId ? fetchUserPreferences(supabase, userId) : Promise.resolve(null),
      fetchCommunityScores(supabase, userId),
      fetchCategoryAffinity(supabase, userId),
    ]);

    // Reach adapts to how many events are actually near this user: dense metros
    // tighten to the floor (excluding far-metro spillover), sparse areas keep
    // the fetched width. Computed from the full fetched pool before any rail
    // filtering so every rail shares one consistent, supply-driven radius.
    const effectiveRadius = adaptiveRadiusMiles(allEvents, radiusMiles);
    const weights = weightsForUser(effectiveRadius);
    const nearestCity = locationName || nearestCityName(lat, lng);

    // City-scoped pool for the neighborhood rails. Fetched per-request (NOT in
    // the shared geo cache — selected_neighborhoods are per-user) and only on
    // the For-You / Locations path, the only place neighborhood rails build.
    // The current-location rail sources `nearestCity`; each onboarding rail
    // sources its own selected neighborhood city.
    let cityEvents: ProcessedEvent[] = [];
    let venueEvents: ProcessedEvent[] = [];
    let subcatEvents: ProcessedEvent[] = [];
    let farmersMarketEvents: ProcessedEvent[] = [];
    let restaurantWeekEvents: ProcessedEvent[] = [];
    let curatedEvents: ProcessedEvent[] = [];
    let getawayEvents: ProcessedEvent[] = [];
    let tasteEntityGroups: { entity: string; events: ProcessedEvent[] }[] = [];
    // Restaurant Weeks rail builds on For You AND the Food tab; the Must-Know rail
    // is For-You only. Both are state-scoped, so resolve the state once.
    {
      const rwFilter = resolveFilter(category);
      const rwState = userStateCode(lat, lng);
      if (rwState && (rwFilter.type === "none" || (rwFilter.type === "category" && rwFilter.value === "food"))) {
        const rwRows = await fetchRestaurantWeekEvents(supabase, rwState);
        restaurantWeekEvents = cleanEvents(deduplicateCrossSource(rwRows), venueCache, cityPhotoCache);
      }
      if (rwState && rwFilter.type === "none") {
        // curated_events are already editorial (hand-curated titles) — skip the
        // title-cleaning / cross-source dedup the events pool needs.
        curatedEvents = await fetchCuratedEvents(supabase, rwState);
      }
    }
    if (resolveFilter(category).type === "none") {
      const targetCities = [nearestCity];
      for (const n of userPreferences?.selected_neighborhoods ?? []) {
        if (!targetCities.some(c => c.toLowerCase() === n.toLowerCase())) {
          targetCities.push(n);
        }
      }
      // Subcategories every topical rail will need: onboarding favorites ∪ the
      // affinity categories that clear the rail threshold (score ≥ 0.4, same
      // gate buildCarousels uses). Fetching this union directly means the
      // favorite-category and affinity rails reflect their full in-radius slate
      // for EVERY category, not just whatever survived the MV sample.
      const railSubcats = new Set<string>();
      for (const c of userPreferences?.selected_categories ?? []) railSubcats.add(c.toLowerCase());
      for (const [c, score] of affinityMap) {
        if (score >= 0.4) railSubcats.add(c.toLowerCase());
      }
      // Top entities (taste graph) drive the "If You Like <X>" rails — read
      // first so the entity-events fetch can run in the parallel batch below.
      const tasteEntities = await fetchTasteEntities(supabase, userId);
      // Collaborative adjacency for those seeds ("SG fans also like Bill Burr").
      const relatedBySeed = await fetchFeatureAffinity(supabase, tasteEntities);
      // Parallel: city-scoped pool (neighborhood rails) + venue-scoped pool
      // (fav-venue rails) + subcategory-scoped pool (topical fav-cat/affinity
      // rails). All three bypass the rank-stratified MV's metro-wide sampling so
      // the named city/venue/subcategory's full slate is available per-request.
      const [cityRows, venueRows, subcatRows, farmersMarketRows, getawayRows, entityGroupRows] = await Promise.all([
        fetchCityEvents(supabase, lat, lng, radiusMiles, targetCities),
        fetchVenueEvents(supabase, lat, lng, radiusMiles, userPreferences?.selected_venues ?? []),
        fetchSubcategoryEvents(supabase, lat, lng, radiusMiles, [...railSubcats]),
        fetchFarmersMarketEvents(supabase, lat, lng, radiusMiles),
        fetchGetawayEvents(supabase, lat, lng, [...railSubcats]),
        fetchEntityEvents(supabase, lat, lng, radiusMiles, tasteEntities, relatedBySeed),
      ]);
      cityEvents = cleanEvents(deduplicateCrossSource(cityRows), venueCache, cityPhotoCache);
      venueEvents = cleanEvents(deduplicateCrossSource(venueRows), venueCache, cityPhotoCache);
      subcatEvents = cleanEvents(deduplicateCrossSource(subcatRows), venueCache, cityPhotoCache);
      farmersMarketEvents = cleanEvents(deduplicateCrossSource(farmersMarketRows), venueCache, cityPhotoCache);
      getawayEvents = cleanEvents(deduplicateCrossSource(getawayRows), venueCache, cityPhotoCache);
      tasteEntityGroups = entityGroupRows
        .map(g => ({ entity: g.entity, events: cleanEvents(deduplicateCrossSource(g.rows), venueCache, cityPhotoCache) }))
        .filter(g => g.events.length > 0);
    }

    const carouselSections = buildCarousels(
      allEvents, rawSportsEvents, carouselsConfig, category,
      lat, lng, userPreferences, nearestCity,
      weights, venueCache, communityScores, affinityMap, cityEvents, venueEvents, subcatEvents,
      farmersMarketEvents, restaurantWeekEvents, radiusMiles, curatedEvents, getawayEvents,
      tasteEntityGroups,
    );

    const categories = buildCategories(allEvents, userPreferences);

    // Trim heavy fields from events to reduce response size
    const trimmedCarousels = carouselSections.map(section => ({
      ...section,
      // Cap description at 2000 chars. The detail screen renders this text
      // directly (it does NOT reliably refetch the full row), so a 200-char
      // cap was cutting ~32% of descriptions mid-sentence on the detail view.
      // 2000 covers every real description (p99 ~720, max seen ~1321) while
      // still bounding a pathological row. Strip venue_description entirely —
      // it's a fallback only used when description is null, and the detail
      // screen refetches the venue independently.
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
        // Never emit null for venue_city/city: the mobile client's EventDto
        // types these as non-nullable String (default "" only covers a MISSING
        // key, not an explicit null), so a null here throws in kotlinx
        // deserialization and blanks the ENTIRE feed. Municipal-sourced rows
        // can carry null venue_city — coalesce at the serialization boundary.
        venue_city: e.venue_city ?? "",
        venue_state: e.venue_state,
        venue_lat: e.venue_lat,
        venue_lng: e.venue_lng,
        venue_address: e.venue_address,
        event_city: e.event_city,
        event_state: e.event_state,
        event_lat: e.event_lat,
        event_lng: e.event_lng,
        city: e.city ?? "",
        source: e.source,
        source_url: e.source_url,
        distance_miles: e.distance_miles,
        description: e.description ? e.description.slice(0, 2000) : null,
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
