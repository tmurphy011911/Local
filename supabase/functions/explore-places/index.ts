import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { jsonCompressed, corsPreflight } from "../_shared/compress.ts";

// ════════════════════════════════════════════════════════════════
// Explore > Places feed
//
// Replaces VenueRepository.getVenues + client-side dedup/sort. Returns a
// ranked list of venues with upcomingEventCount computed server-side from
// a single aggregated query (instead of N round-trips on device).
//
// GET /explore-places
//   ?lat=42.3601&lng=-71.0589
//   &user_id=<uuid>            (optional — personalizes)
//   &query=<search text>       (optional — name, city)
//   &category=<slug>           (optional)
//   &radius=<miles>            (optional — defaults 100)
//   &cursor=<base64>           (optional)
//   &limit=<int>               (defaults 50, caps 100)
// ════════════════════════════════════════════════════════════════

interface VenueRow {
  id: string;
  name: string;
  address: string | null;
  city: string;
  state: string | null;
  lat: number | null;
  lng: number | null;
  photo_url: string | null;
  description: string | null;
  category: string | null;
  google_rating: number | null;
  website: string | null;
  reservation_url: string | null;
  public_interest_rank: number | null;
  is_active: boolean;
}

interface UserPrefs {
  selected_categories: string[];
  selected_venues: string[];
}

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
};

// In-memory cache of the FULLY-SCORED sorted list. Per-request only slices
// for pagination — no re-scoring → refresh returns identical order inside TTL.
interface ScoredVenue {
  v: VenueRow;
  dist: number;
  eventCount: number;
  // Capped at MAX_EVENTS_PER_VENUE; same source as eventCount so the venue
  // detail screen never sees a non-zero count with a zero events list.
  events: InlineEventRow[];
  score: number;
}
interface CacheEntry {
  scored: ScoredVenue[];
  timestamp: number;
}
const CACHE_TTL_MS = 10 * 60 * 1000;
const cache = new Map<string, CacheEntry>();

function getCached(key: string): CacheEntry | null {
  const hit = cache.get(key);
  if (!hit) return null;
  if (Date.now() - hit.timestamp > CACHE_TTL_MS) {
    cache.delete(key);
    return null;
  }
  return hit;
}

// ── Geo ──

function distanceMiles(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const R = 3958.8;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLng = ((lng2 - lng1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) *
      Math.cos((lat2 * Math.PI) / 180) *
      Math.sin(dLng / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function normalizeVenueName(name: string): string {
  return name
    .toLowerCase()
    .trim()
    .replace(/^the\s+/, "")
    .replace(/[^a-z0-9\s]/g, "")
    .trim();
}

// ── Fetch ──

async function fetchVenues(
  supabase: any,
  lat: number,
  lng: number,
  query: string | null,
  category: string | null,
  radiusMiles: number,
): Promise<VenueRow[]> {
  const latDelta = radiusMiles / 55;
  const lngDelta = radiusMiles / 45;

  let q = supabase
    .from("venues")
    .select(
      "id,name,address,city,state,lat,lng,photo_url,description,category,google_rating,website,reservation_url,public_interest_rank,is_active",
    )
    .eq("is_active", true)
    // Global minimum-quality floor — venues with public_interest_rank <= 10
    // (community centers, K-12 athletic fields, etc.) are hidden everywhere
    // in the app. Adjust MIN_PUBLIC_INTEREST_RANK if this threshold changes.
    .gt("public_interest_rank", 10)
    .gte("lat", lat - latDelta)
    .lte("lat", lat + latDelta)
    .gte("lng", lng - lngDelta)
    .lte("lng", lng + lngDelta)
    // Deterministic order so the .limit(500) cut is stable across refreshes
    // (and across cold-started edge function instances). Without this, Postgres
    // can return a different 500-row subset each call, producing a visibly
    // different scored ranking on refresh.
    .order("public_interest_rank", { ascending: false })
    .order("id", { ascending: true })
    .limit(500);

  if (query && query.trim()) {
    const safe = query.trim().replace(/%/g, "").replace(/,/g, " ");
    q = q.or(`name.ilike.%${safe}%,city.ilike.%${safe}%`);
  }
  if (category) {
    q = q.eq("category", category);
  }

  const { data, error } = await q;
  if (error) throw error;
  return (data ?? []) as VenueRow[];
}

// Per-venue event slim row — same shape as the inline events the client
// needs to render the venue detail's "Upcoming Events" list.
interface InlineEventRow {
  id: string;
  title: string;
  description: string | null;
  category: string | null;
  subcategory: string | null;
  start_at: string | null;
  is_free: boolean;
  price_min: number | null;
  price_max: number | null;
  ticket_url: string | null;
  image_url: string | null;
  venue_name: string | null;
  venue_city: string | null;
  source_url: string | null;
}

interface VenueEvents {
  count: number;
  // Sorted by start_at ASC, capped at MAX_EVENTS_PER_VENUE
  events: InlineEventRow[];
}

const MAX_EVENTS_PER_VENUE = 25;

async function fetchEventsByVenue(
  supabase: any,
  lat: number,
  lng: number,
  radiusMiles: number,
): Promise<Record<string, VenueEvents>> {
  const latDelta = radiusMiles / 55;
  const lngDelta = radiusMiles / 45;
  const nowIso = new Date().toISOString();

  // Compute per-venue upcoming-event counts + inline events by scanning the
  // future active events in the bbox. A dense metro bbox holds ~11k events, so
  // the old SEQUENTIAL 12-page loop cost 3-9s — the "Places tab never loads"
  // bug. Now: one cheap COUNT (indexed, ~0.3s) to learn the page count, then
  // fetch every page in PARALLEL (wall-clock ≈ one page, not the sum).
  const pageSize = 1000;
  const MAX_PAGES = 20;

  // Shared bbox filter. NOTE on `.or(...)`: exclude no-public-interest but KEEP
  // null-subcategory rows — a bare `.neq` drops NULLs (they evaluate to NULL,
  // not TRUE), which silently zeroed most crawler venues (Sellersville, Gore
  // Place, etc.) on their cards even though the Events tab found dozens.
  const applyBbox = (qb: any) =>
    qb.eq("is_active", true)
      .or("subcategory.is.null,subcategory.neq.no-public-interest")
      .gte("start_at", nowIso)
      .gte("venue_lat", lat - latDelta)
      .lte("venue_lat", lat + latDelta)
      .gte("venue_lng", lng - lngDelta)
      .lte("venue_lng", lng + lngDelta);

  const { count } = await applyBbox(
    supabase.from("events").select("id", { count: "exact", head: true }),
  );
  // If the count is unavailable, fall back to firing the full page budget.
  const total = count == null ? pageSize * MAX_PAGES : Math.min(count, pageSize * MAX_PAGES);
  const numPages = Math.max(1, Math.ceil(total / pageSize));

  const pageResults = await Promise.all(
    Array.from({ length: numPages }, (_, p) =>
      applyBbox(
        supabase.from("events").select(
          "id,title,description,category,subcategory,start_at,is_free,price_min,price_max,ticket_url,image_url,venue_name,venue_city,source_url",
        ),
      )
        .order("start_at", { ascending: true })
        .order("id", { ascending: true })
        .range(p * pageSize, p * pageSize + pageSize - 1),
    ),
  );

  // Merge pages IN ORDER (page 0 = soonest) so the per-venue cap keeps the
  // soonest events.
  const buckets: Record<string, VenueEvents> = {};
  for (const { data } of pageResults) {
    for (const row of ((data ?? []) as InlineEventRow[])) {
      // Match normalized key — "The Brattle Theatre" and "Brattle Theatre"
      // collapse to the same bucket so venue.name and event.venue_name join.
      const key = normalizeVenueName(row.venue_name ?? "");
      if (!key) continue;
      const bucket = buckets[key] ?? { count: 0, events: [] };
      bucket.count += 1;
      if (bucket.events.length < MAX_EVENTS_PER_VENUE) bucket.events.push(row);
      buckets[key] = bucket;
    }
  }
  return buckets;
}

// Back-compat shim — most callers below only need the count.
function countsFromBuckets(buckets: Record<string, VenueEvents>): Record<string, number> {
  const out: Record<string, number> = {};
  for (const [k, v] of Object.entries(buckets)) out[k] = v.count;
  return out;
}

async function fetchUserPrefs(supabase: any, userId: string): Promise<UserPrefs | null> {
  const { data } = await supabase
    .from("user_preferences")
    .select("selected_categories,selected_venues")
    .eq("id", userId)
    .maybeSingle();
  return data ?? null;
}

/**
 * Same `get_category_affinity` RPC the carousels + explore-events feeds use.
 * Returns 0-1 normalized weights keyed by event-style category strings
 * ("music", "sports", "outdoors", etc.). Anonymous → empty map → cold-start
 * scoring path engages.
 */
async function fetchCategoryAffinity(supabase: any, userId: string | null): Promise<Record<string, number>> {
  if (!userId) return {};
  const { data } = await supabase.rpc("get_category_affinity", { p_user_id: userId });
  if (!data || !Array.isArray(data)) return {};
  const maxWeight = Math.max(...data.map((e: any) => e.weight ?? 0), 1);
  const out: Record<string, number> = {};
  for (const row of data) {
    out[String(row.category ?? "").toLowerCase()] = Math.min((row.weight ?? 0) / maxWeight, 1);
  }
  return out;
}

/**
 * Bridge raw venue.category (Google-Places freeform: "Brewery", "Sandwich
 * shop", "Beach") to the event-style affinity keys ("music", "outdoors",
 * "food", etc.) so the same get_category_affinity map applies to places.
 * Returns null when the category doesn't fit any bucket — those venues
 * just get affinity=0, falling back to the rest of the score.
 */
function venueCategoryToAffinityKey(category: string | null): string | null {
  if (!category) return null;
  const c = category.toLowerCase();
  if (c.includes("museum") || c.includes("gallery") || c.includes("art center")) return "museums";
  if (c.includes("library")) return "libraries";
  if (c.includes("theatre") || c.includes("theater") || c.includes("performing arts")) return "theatre";
  if (c.includes("comedy")) return "comedy";
  if (c.includes("brewery") || c.includes("bar") || c.includes("pub") || c.includes("nightclub") || c.includes("lounge")) return "nightlife";
  if (c.includes("restaurant") || c.includes("cafe") || c.includes("bakery") || c.includes("sandwich") || c.includes("ice cream") || c.includes("coffee") || c.includes("food")) return "food";
  if (c.includes("beach") || c.includes("park") || c.includes("trail") || c.includes("garden") || c.includes("lighthouse") || c.includes("hike") || c.includes("outdoor")) return "outdoors";
  if (c.includes("stadium") || c.includes("arena") || c.includes("sports")) return "sports";
  if (c.includes("zoo") || c.includes("aquarium") || c.includes("kids") || c.includes("playground")) return "for-kids";
  if (c.includes("tour")) return "tours";
  if (c.includes("music") || c.includes("concert")) return "music";
  return null;
}

// ── Scoring ──

function scoreVenue(
  v: VenueRow,
  distance: number,
  eventCount: number,
  prefs: UserPrefs | null,
  affinityMap: Record<string, number>,
  maxRank: number,
): number {
  const proxScore = 1 / (1 + distance / 8); // falls off ~8mi — places lookups tolerate more radius than events
  const rankScore = maxRank > 0 ? (v.public_interest_rank ?? 0) / maxRank : 0;
  const eventDensity = Math.min(1, eventCount / 10); // 10+ events/month = full credit

  const tasteCats = new Set((prefs?.selected_categories ?? []).map((c) => c.toLowerCase().trim()));
  const tasteVenues = new Set((prefs?.selected_venues ?? []).map((v) => v.toLowerCase().trim()));
  const taste = tasteCats.size > 0 && v.category && tasteCats.has(v.category.toLowerCase()) ? 1 : 0;
  const savedVenueBoost = tasteVenues.has(v.name.toLowerCase().trim()) ? 1 : 0;

  // Translate venue.category (Google-Places freeform) to an event-affinity
  // key, then look up the user's learned weight for that category. Venues
  // whose category doesn't map cleanly get affinity=0 and fall back to the
  // rest of the score — better than wrong-bucket bias.
  const affinityKey = venueCategoryToAffinityKey(v.category);
  const affinity = affinityKey ? (affinityMap[affinityKey] ?? 0) : 0;

  // Option A weighting — personal-dominant Places feed:
  //   hot user (any affinity history): aff 30 · taste 20 · saved 10 · prox 25 · rank 10 · density 5 = 100
  //   cold start (no affinity yet):     aff  0 · taste 35 · saved 10 · prox 35 · rank 15 · density 5 = 100
  // Photo dropped from sort — was a quality tiebreaker; if you want a
  // photo floor it should be a filter (`WHERE photo_url IS NOT NULL`).
  const hasAffinity = Object.keys(affinityMap).length > 0;
  const wAff = hasAffinity ? 0.30 : 0.00;
  const wTaste = hasAffinity ? 0.20 : 0.35;
  const wSaved = 0.10;
  const wProx = hasAffinity ? 0.25 : 0.35;
  const wRank = hasAffinity ? 0.10 : 0.15;
  const wDensity = 0.05;

  return (
    affinity * wAff +
    taste * wTaste +
    savedVenueBoost * wSaved +
    proxScore * wProx +
    rankScore * wRank +
    eventDensity * wDensity
  );
}

// ── Sort ──
//
// Mirrors the client's PlaceSort enum. Cached scored list is already
// score-DESC; "popular" returns it as-is. "closest" and "most_events"
// re-sort the full list so paging picks the best venues globally, not just
// inside the popularity top-N. Stable tiebreak on venue id.
function sortScored(scored: ScoredVenue[], sort: string): ScoredVenue[] {
  switch (sort) {
    case "closest":
      return [...scored].sort((a, b) => a.dist - b.dist || a.v.id.localeCompare(b.v.id));
    case "most_events":
      return [...scored].sort((a, b) => b.eventCount - a.eventCount || a.v.id.localeCompare(b.v.id));
    case "popular":
    default:
      return scored;
  }
}

// ── Dedup ──

function dedupVenues(venues: VenueRow[], eventCounts: Record<string, number>): VenueRow[] {
  const groups = new Map<string, VenueRow[]>();
  for (const v of venues) {
    const key = normalizeVenueName(v.name);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key)!.push(v);
  }
  const out: VenueRow[] = [];
  for (const dupes of groups.values()) {
    if (dupes.length === 1) {
      out.push(dupes[0]);
      continue;
    }
    // Keep the best: higher rank > more events > has photo
    dupes.sort((a, b) => {
      const rankDiff = (b.public_interest_rank ?? 0) - (a.public_interest_rank ?? 0);
      if (rankDiff !== 0) return rankDiff;
      const aEvents = eventCounts[normalizeVenueName(a.name)] ?? 0;
      const bEvents = eventCounts[normalizeVenueName(b.name)] ?? 0;
      if (aEvents !== bEvents) return bEvents - aEvents;
      return (b.photo_url ? 1 : 0) - (a.photo_url ? 1 : 0);
    });
    out.push(dupes[0]);
  }
  return out;
}

// ── Cursor ──

function encodeCursor(offset: number): string {
  return btoa(String(offset));
}
function decodeCursor(cursor: string | null): number {
  if (!cursor) return 0;
  try {
    return parseInt(atob(cursor), 10) || 0;
  } catch {
    return 0;
  }
}

// ── Handler ──

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return corsPreflight();
  }
  if (req.method !== "GET") {
    return jsonCompressed(req, { error: "Method not allowed" }, { status: 405 });
  }

  try {
    const url = new URL(req.url);
    const lat = parseFloat(url.searchParams.get("lat") ?? "42.3601");
    const lng = parseFloat(url.searchParams.get("lng") ?? "-71.0589");
    const userId = url.searchParams.get("user_id");
    const query = url.searchParams.get("query");
    // City (top-level filter) and category (venue-type drill-down) are applied
    // POST-cache over the full scored list — so one cached list per
    // lat/lng/radius/user serves every city+category slice AND lets us compute
    // facet counts across the whole set. Neither is in the cache key.
    const city = url.searchParams.get("city");
    const category = url.searchParams.get("category");
    const radiusMiles = Math.min(parseFloat(url.searchParams.get("radius") ?? "100"), 150);
    const hasEventsOnly = url.searchParams.get("has_events") === "true";
    const sort = (url.searchParams.get("sort") ?? "popular").toLowerCase();
    const limit = Math.min(parseInt(url.searchParams.get("limit") ?? "50", 10), 100);
    const offset = decodeCursor(url.searchParams.get("cursor"));

    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

    // Cache key includes user_id so taste-influenced ordering is stable per
    // user. hasEventsOnly is applied AFTER the cache read so it doesn't split
    // the cache (the scored list holds eventCount; toggling just filters).
    const cacheKey = [
      Math.round(lat * 10) / 10,
      Math.round(lng * 10) / 10,
      (query ?? "").trim().toLowerCase(),
      radiusMiles,
      userId ?? "anon",
    ].join("|");

    let scored: ScoredVenue[];
    const cached = getCached(cacheKey);
    if (cached) {
      scored = cached.scored;
    } else {
      const [rawVenues, eventBuckets, prefs, affinityMap] = await Promise.all([
        // category is null here on purpose — the scored list must be
        // category-complete so it serves every drill-down + facet counts.
        fetchVenues(supabase, lat, lng, query, null, radiusMiles),
        fetchEventsByVenue(supabase, lat, lng, radiusMiles),
        userId ? fetchUserPrefs(supabase, userId) : Promise.resolve(null),
        fetchCategoryAffinity(supabase, userId),
      ]);
      const counts = countsFromBuckets(eventBuckets);
      const venues = dedupVenues(rawVenues, counts);
      const maxRank = venues.reduce((m, v) => Math.max(m, v.public_interest_rank ?? 0), 0);

      scored = venues
        .filter((v) => v.lat != null && v.lng != null)
        .map((v) => {
          const dist = distanceMiles(lat, lng, v.lat!, v.lng!);
          const key = normalizeVenueName(v.name);
          const bucket = eventBuckets[key];
          const eventCount = bucket?.count ?? 0;
          const events = bucket?.events ?? [];
          return {
            v,
            dist,
            eventCount,
            events,
            score: scoreVenue(v, dist, eventCount, prefs, affinityMap, maxRank),
          };
        })
        .filter((x) => x.dist <= radiusMiles)
        // Stable tiebreak on venue id — guarantees identical order across
        // calls even when two venues score identically.
        .sort((a, b) => b.score - a.score || a.v.id.localeCompare(b.v.id));

      cache.set(cacheKey, { scored, timestamp: Date.now() });
    }

    // Apply user-selected sort over the FULL scored list before paginating,
    // so "closest" / "most_events" pick the best venues across the whole
    // dataset (not just the popularity top-50 the client happens to hold).
    // Sort runs post-cache so the cache key stays sort-agnostic.
    const sortedAll = sortScored(scored, sort);

    // ── Two-tier facets ──────────────────────────────────────────────────
    // City row is computed from the FULL scored list (independent of the
    // current city/category selection) so it stays stable as the user drills.
    // Category row reflects the selected city.
    const userState = inferUserState(scored);
    const cityFacets = buildCityFacets(scored, userState);

    const cityNorm = (city ?? "").trim().toLowerCase();
    const cityScoped = cityNorm
      ? sortedAll.filter((x) => (x.v.city ?? "").trim().toLowerCase() === cityNorm)
      : sortedAll;
    const categoryFacets = buildCategoryFacets(cityScoped);

    const catNorm = (category ?? "").trim().toLowerCase();
    const catScoped = catNorm
      ? cityScoped.filter((x) => (x.v.category ?? "").trim().toLowerCase() === catNorm)
      : cityScoped;

    const filtered = hasEventsOnly ? catScoped.filter((x) => x.eventCount > 0) : catScoped;
    const page = filtered.slice(offset, offset + limit);
    const hasMore = filtered.length > offset + limit;

    const items = page.map(({ v, dist, eventCount, events }) => ({
      id: v.id,
      name: v.name,
      address: v.address,
      city: v.city,
      state: v.state,
      lat: v.lat,
      lng: v.lng,
      photoUrl: v.photo_url,
      description: v.description,
      category: v.category,
      tags: v.category ? [toTitleCase(v.category)] : [],
      distanceMiles: dist,
      googleRating: v.google_rating,
      website: v.website,
      reservationUrl: v.reservation_url,
      upcomingEventCount: eventCount,
      publicInterestRank: v.public_interest_rank,
      isFeatured: false,
      // Inline events keep the card and the detail in sync — same source,
      // capped at MAX_EVENTS_PER_VENUE so the payload stays bounded.
      upcomingEvents: events.map((e) => ({
        id: e.id,
        title: e.title,
        description: e.description,
        category: e.category ?? "",
        subcategory: e.subcategory,
        startAt: e.start_at,
        isFree: e.is_free,
        priceMin: e.price_min,
        priceMax: e.price_max,
        ticketUrl: e.ticket_url,
        imageUrl: e.image_url,
        venueName: e.venue_name,
        venueCity: e.venue_city ?? "",
        sourceUrl: e.source_url,
      })),
    }));

    return jsonCompressed(
      req,
      {
        items,
        cursor: hasMore ? encodeCursor(offset + limit) : null,
        hasMore,
        total: filtered.length,
        // Two-tier filter facets. `name` is the RAW value the client sends back
        // as ?city= / ?category= ; the client title-cases category for display.
        cityFacets,
        categoryFacets,
      },
      { extraHeaders: { "Cache-Control": "public, max-age=60" } },
    );
  } catch (err) {
    console.error("explore-places error:", err);
    return jsonCompressed(
      req,
      { error: err instanceof Error ? err.message : "Unknown error" },
      { status: 500 },
    );
  }
});

function toTitleCase(s: string): string {
  return s
    .split(/[\s_]+/)
    .map((w) => (w ? w[0].toUpperCase() + w.slice(1).toLowerCase() : ""))
    .join(" ");
}

interface Facet {
  name: string;
  count: number;
}

/** User's home state ≈ the most common state among venues within 25 mi. Used
 *  to decide which FAR (>25 mi) cities are worth showing as "major in-state
 *  metros" vs. dropping (far out-of-state cities). */
function inferUserState(scored: ScoredVenue[]): string | null {
  const counts: Record<string, number> = {};
  for (const s of scored) {
    if (s.dist <= 25 && s.v.state) counts[s.v.state] = (counts[s.v.state] ?? 0) + 1;
  }
  let best: string | null = null;
  let bestN = 0;
  for (const [st, n] of Object.entries(counts)) {
    if (n > bestN) {
      best = st;
      bestN = n;
    }
  }
  return best;
}

/** Top-level city chips: every city with a venue within 25 mi, PLUS the major
 *  cities of the user's home state beyond 25 mi (density-ranked). Near cities
 *  sort first, then far in-state majors; capped so the row stays usable. */
function buildCityFacets(scored: ScoredVenue[], userState: string | null): Facet[] {
  const NEAR_MI = 25;
  const MIN_IN_STATE_COUNT = 3; // a far city needs real density to count as "major"
  const MAX_CITIES = 24;
  const g: Record<string, { count: number; minDist: number; state: string | null }> = {};
  for (const s of scored) {
    const cityName = (s.v.city ?? "").trim();
    if (!cityName) continue;
    const e = g[cityName] ?? { count: 0, minDist: Infinity, state: s.v.state ?? null };
    e.count += 1;
    if (s.dist < e.minDist) e.minDist = s.dist;
    g[cityName] = e;
  }
  return Object.entries(g)
    .filter(([, e]) =>
      e.minDist <= NEAR_MI ||
      (userState != null && e.state === userState && e.count >= MIN_IN_STATE_COUNT)
    )
    .sort((a, b) => {
      const aNear = a[1].minDist <= NEAR_MI ? 0 : 1;
      const bNear = b[1].minDist <= NEAR_MI ? 0 : 1;
      if (aNear !== bNear) return aNear - bNear; // near cities first
      return b[1].count - a[1].count; // then by density
    })
    .slice(0, MAX_CITIES)
    .map(([name, e]) => ({ name, count: e.count }));
}

/** Second-tier chips: venue categories present in the (city-scoped) set,
 *  density-ranked. `name` is the raw venue.category the client sends back. */
function buildCategoryFacets(scored: ScoredVenue[]): Facet[] {
  const g: Record<string, number> = {};
  for (const s of scored) {
    const cat = (s.v.category ?? "").trim();
    if (!cat) continue;
    g[cat] = (g[cat] ?? 0) + 1;
  }
  return Object.entries(g)
    .map(([name, count]) => ({ name, count }))
    .sort((a, b) => b.count - a.count);
}
