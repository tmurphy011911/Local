import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

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

async function fetchEventCountsByVenue(
  supabase: any,
  lat: number,
  lng: number,
  radiusMiles: number,
): Promise<Record<string, number>> {
  const latDelta = radiusMiles / 55;
  const lngDelta = radiusMiles / 45;
  const nowIso = new Date().toISOString();

  // Page through ALL future active events in the bbox (not just a limit-2000
  // unordered slice — that was producing jittery counts because Postgres
  // returned a different 2000-row subset per call). Deterministic id-sorted
  // pagination keeps counts stable across refreshes.
  const pageSize = 1000;
  let offset = 0;
  const counts: Record<string, number> = {};
  for (let page = 0; page < 20; page++) {
    const { data } = await supabase
      .from("events")
      .select("venue_name")
      .eq("is_active", true)
      .neq("subcategory", "no-public-interest") // skip auto-excluded events from counts
      .gte("start_at", nowIso)
      .gte("venue_lat", lat - latDelta)
      .lte("venue_lat", lat + latDelta)
      .gte("venue_lng", lng - lngDelta)
      .lte("venue_lng", lng + lngDelta)
      .order("id", { ascending: true })
      .range(offset, offset + pageSize - 1);
    const rows = data ?? [];
    for (const row of rows) {
      // Match normalized key — "The Brattle Theatre" and "Brattle Theatre"
      // collapse to the same bucket so venue.name and event.venue_name
      // reliably join.
      const key = normalizeVenueName(row.venue_name ?? "");
      if (!key) continue;
      counts[key] = (counts[key] ?? 0) + 1;
    }
    if (rows.length < pageSize) break;
    offset += pageSize;
  }
  return counts;
}

async function fetchUserPrefs(supabase: any, userId: string): Promise<UserPrefs | null> {
  const { data } = await supabase
    .from("user_preferences")
    .select("selected_categories,selected_venues")
    .eq("id", userId)
    .maybeSingle();
  return data ?? null;
}

// ── Scoring ──

function scoreVenue(
  v: VenueRow,
  distance: number,
  eventCount: number,
  prefs: UserPrefs | null,
  maxRank: number,
): number {
  const proxScore = 1 / (1 + distance / 8); // falls off ~8mi — places lookups tolerate more radius than events
  const rankScore = maxRank > 0 ? (v.public_interest_rank ?? 0) / maxRank : 0;
  const eventDensity = Math.min(1, eventCount / 10); // 10+ events/month = full credit
  const hasPhoto = v.photo_url ? 1 : 0;

  const tasteCats = new Set((prefs?.selected_categories ?? []).map((c) => c.toLowerCase().trim()));
  const tasteVenues = new Set((prefs?.selected_venues ?? []).map((v) => v.toLowerCase().trim()));
  const taste = tasteCats.size > 0 && v.category && tasteCats.has(v.category.toLowerCase()) ? 1 : 0;
  const savedVenueBoost = tasteVenues.has(v.name.toLowerCase().trim()) ? 1 : 0;

  // Weights: proximity 30 · rank 25 · event density 20 · photo 10 · taste 10 · saved 5
  return (
    proxScore * 0.3 +
    rankScore * 0.25 +
    eventDensity * 0.2 +
    hasPhoto * 0.1 +
    taste * 0.1 +
    savedVenueBoost * 0.05
  );
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
    return new Response(null, { headers: CORS_HEADERS });
  }
  if (req.method !== "GET") {
    return new Response(JSON.stringify({ error: "Method not allowed" }), {
      status: 405,
      headers: { "Content-Type": "application/json", ...CORS_HEADERS },
    });
  }

  try {
    const url = new URL(req.url);
    const lat = parseFloat(url.searchParams.get("lat") ?? "42.3601");
    const lng = parseFloat(url.searchParams.get("lng") ?? "-71.0589");
    const userId = url.searchParams.get("user_id");
    const query = url.searchParams.get("query");
    const category = url.searchParams.get("category");
    const radiusMiles = Math.min(parseFloat(url.searchParams.get("radius") ?? "100"), 150);
    const hasEventsOnly = url.searchParams.get("has_events") === "true";
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
      category ?? "",
      radiusMiles,
      userId ?? "anon",
    ].join("|");

    let scored: ScoredVenue[];
    const cached = getCached(cacheKey);
    if (cached) {
      scored = cached.scored;
    } else {
      const [rawVenues, counts] = await Promise.all([
        fetchVenues(supabase, lat, lng, query, category, radiusMiles),
        fetchEventCountsByVenue(supabase, lat, lng, radiusMiles),
      ]);
      const venues = dedupVenues(rawVenues, counts);
      const prefs = userId ? await fetchUserPrefs(supabase, userId) : null;
      const maxRank = venues.reduce((m, v) => Math.max(m, v.public_interest_rank ?? 0), 0);

      scored = venues
        .filter((v) => v.lat != null && v.lng != null)
        .map((v) => {
          const dist = distanceMiles(lat, lng, v.lat!, v.lng!);
          const eventCount = counts[normalizeVenueName(v.name)] ?? 0;
          return { v, dist, eventCount, score: scoreVenue(v, dist, eventCount, prefs, maxRank) };
        })
        .filter((x) => x.dist <= radiusMiles)
        // Stable tiebreak on venue id — guarantees identical order across
        // calls even when two venues score identically.
        .sort((a, b) => b.score - a.score || a.v.id.localeCompare(b.v.id));

      cache.set(cacheKey, { scored, timestamp: Date.now() });
    }

    const filtered = hasEventsOnly ? scored.filter((x) => x.eventCount > 0) : scored;
    const page = filtered.slice(offset, offset + limit);
    const hasMore = filtered.length > offset + limit;

    const items = page.map(({ v, dist, eventCount }) => ({
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
    }));

    return new Response(
      JSON.stringify({
        items,
        cursor: hasMore ? encodeCursor(offset + limit) : null,
        hasMore,
        total: filtered.length,
      }),
      {
        headers: {
          "Content-Type": "application/json",
          "Cache-Control": "public, max-age=60",
          ...CORS_HEADERS,
        },
      },
    );
  } catch (err) {
    console.error("explore-places error:", err);
    return new Response(
      JSON.stringify({ error: err instanceof Error ? err.message : "Unknown error" }),
      {
        status: 500,
        headers: { "Content-Type": "application/json", ...CORS_HEADERS },
      },
    );
  }
});

function toTitleCase(s: string): string {
  return s
    .split(/[\s_]+/)
    .map((w) => (w ? w[0].toUpperCase() + w.slice(1).toLowerCase() : ""))
    .join(" ");
}
