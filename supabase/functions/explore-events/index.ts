import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

// ════════════════════════════════════════════════════════════════
// Explore > Events feed
//
// Replaces the client-side scoring/filter logic in PlacesViewModel. Takes
// user geo + optional filters, returns a single pre-ranked list of events
// with images already resolved (event.image_url → venue.photo_url fallback).
//
// GET /explore-events
//   ?lat=42.3601&lng=-71.0589
//   &user_id=<uuid>            (optional — personalizes by taste + saved venues)
//   &query=<search text>       (optional — matches title + venue_name)
//   &category=<slug>           (optional — subcategory/category filter)
//   &radius=<miles>            (optional — defaults 50)
//   &cursor=<base64>           (optional — from prior response.cursor)
//   &limit=<int>               (optional — defaults 50, capped 100)
// ════════════════════════════════════════════════════════════════

interface EventRow {
  id: string;
  title: string;
  description: string | null;
  category: string;
  subcategory: string | null;
  start_at: string | null;
  end_at: string | null;
  is_free: boolean;
  price_min: number | null;
  price_max: number | null;
  ticket_url: string | null;
  image_url: string | null;
  venue_name: string | null;
  venue_city: string;
  venue_lat: number | null;
  venue_lng: number | null;
  source_url: string | null;
  city: string;
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

// In-memory cache — persists across requests in same Deno isolate.
// We cache the ALREADY-SORTED output so per-request just slices for
// pagination. Scoring inside the cache means order stays stable for the
// TTL window; without this, `nowMs`-dependent freshness shifts events
// across every refresh.
interface ScoredItem {
  e: EventRow;
  resolvedImage: string | null;
  dist: number;
  venueRank: number | null;
  score: number;
}
interface CacheEntry {
  scored: ScoredItem[];
  timestamp: number;
}
const CACHE_TTL_MS = 10 * 60 * 1000; // 10 min
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

/**
 * Canonical venue-name key. Shared by the photo-fallback lookup here AND
 * by the event-count/venue-detail paths elsewhere — so a venue called
 * "The Brattle Theatre" matches events whose venue_name is "Brattle
 * Theatre" (and vice versa). Without this, image fallback and event
 * counts silently miss ~20% of venues.
 */
/**
 * Round-robin through venue buckets so consecutive events come from
 * different venues. Takes the already-score-sorted list and regroups it.
 *
 * Round 0: top event from venue A, top event from venue B, top event from
 *          venue C, ... (where A, B, C are ordered by each venue's best
 *          score so high-quality venues still appear first)
 * Round 1: second-best event from each venue (if any)
 * ...
 *
 * Result: user sees variety across venues in the visible window without
 * any randomness — ordering stays deterministic under refresh.
 */
function interleaveByVenue(scored: ScoredItem[]): ScoredItem[] {
  const byVenue = new Map<string, ScoredItem[]>();
  for (const item of scored) {
    const key = normalizeVenueKey(item.e.venue_name) || `__anon_${item.e.id}`;
    const bucket = byVenue.get(key);
    if (bucket) bucket.push(item);
    else byVenue.set(key, [item]);
  }
  // Order buckets by the top item's score — best-venue-first in round 0.
  const groups = Array.from(byVenue.values()).sort(
    (a, b) => b[0].score - a[0].score || a[0].e.id.localeCompare(b[0].e.id),
  );

  const out: ScoredItem[] = [];
  let round = 0;
  while (true) {
    let pulled = false;
    for (const g of groups) {
      if (round < g.length) {
        out.push(g[round]);
        pulled = true;
      }
    }
    if (!pulled) break;
    round++;
  }
  return out;
}

function normalizeVenueKey(name: string | null | undefined): string {
  if (!name) return "";
  return name
    .toLowerCase()
    .trim()
    .replace(/^the\s+/, "")
    .replace(/[^\w\s]/g, "")
    .replace(/\s+/g, " ")
    .trim();
}

// ── Geo helpers ──

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

// ── Fetch helpers ──

// Maps the date_filter slug to a {startIso, endIso} window. Uses UTC for
// simplicity — good enough for chunked date filters; we aren't doing local-
// midnight precision here.
function resolveDateWindow(dateFilter: string | null): { startIso: string; endIso: string } {
  const now = Date.now();
  const startOfToday = new Date(now);
  startOfToday.setUTCHours(0, 0, 0, 0);
  const DAY = 24 * 60 * 60 * 1000;
  switch (dateFilter) {
    case "today": {
      const end = startOfToday.getTime() + DAY;
      return { startIso: new Date(Math.max(now, startOfToday.getTime())).toISOString(), endIso: new Date(end).toISOString() };
    }
    case "tomorrow": {
      const start = startOfToday.getTime() + DAY;
      const end = start + DAY;
      return { startIso: new Date(start).toISOString(), endIso: new Date(end).toISOString() };
    }
    case "this_weekend": {
      // Fri 00:00 UTC through Mon 00:00 UTC. getUTCDay: Sun=0..Sat=6
      const dow = startOfToday.getUTCDay();
      let daysUntilFri = (5 - dow + 7) % 7;
      // If we're already in Fri/Sat/Sun, use the ongoing weekend.
      if (dow === 5 || dow === 6 || dow === 0) daysUntilFri = 0;
      const fri = startOfToday.getTime() + daysUntilFri * DAY;
      const mon = fri + 3 * DAY;
      return { startIso: new Date(Math.max(now, fri)).toISOString(), endIso: new Date(mon).toISOString() };
    }
    case "next_7_days":
      return { startIso: new Date(now).toISOString(), endIso: new Date(now + 7 * DAY).toISOString() };
    case "next_30_days":
      return { startIso: new Date(now).toISOString(), endIso: new Date(now + 30 * DAY).toISOString() };
    default:
      return { startIso: new Date(now).toISOString(), endIso: new Date(now + 60 * DAY).toISOString() };
  }
}

async function fetchEvents(
  supabase: any,
  lat: number,
  lng: number,
  query: string | null,
  category: string | null,
  radiusMiles: number,
  dateFilter: string | null,
): Promise<EventRow[]> {
  // Generous bbox prefilter — tighter radius applied post-fetch.
  const latDelta = radiusMiles / 55;
  const lngDelta = radiusMiles / 45;
  const { startIso: nowIso, endIso } = resolveDateWindow(dateFilter);

  // Tight candidate pool when the user is typing a search — 100 trigram-index
  // matches is plenty to find the right hit and keeps the request snappy.
  // For unfiltered browsing we keep the wider 500 window so post-fetch
  // category / interleave logic has room to pick from.
  const hasQuery = !!(query && query.trim());
  const candidateLimit = hasQuery ? 100 : 500;

  let q = supabase
    .from("events")
    .select(
      "id,title,description,category,subcategory,start_at,end_at,is_free,price_min,price_max,ticket_url,image_url,venue_name,venue_city,venue_lat,venue_lng,source_url,city",
    )
    .eq("is_active", true)
    .gte("start_at", nowIso)
    .lte("start_at", endIso)
    .gte("venue_lat", lat - latDelta)
    .lte("venue_lat", lat + latDelta)
    .gte("venue_lng", lng - lngDelta)
    .lte("venue_lng", lng + lngDelta)
    // Drop auto-excluded events (AA meetings, youth sports, civic boards, etc.)
    // These are tagged by classify_events.js and shouldn't surface on Explore.
    .or("subcategory.is.null,subcategory.neq.no-public-interest")
    .order("start_at", { ascending: true })
    .limit(candidateLimit);

  if (hasQuery) {
    const safe = query!.trim().replace(/%/g, "").replace(/,/g, " ");
    q = q.or(`title.ilike.%${safe}%,venue_name.ilike.%${safe}%`);
  }
  if (category && category !== "foryou") {
    // Prefer subcategory (event-level type) over category (often venue-level
    // umbrella). A comedy show hosted at an art gallery has
    // category="art", subcategory="comedy" — the old or(category, subcategory)
    // filter incorrectly surfaced it under the Art filter. Now Art only
    // returns events whose subcategory is "art", OR (as a fallback) events
    // whose subcategory is unset and whose top-level category is "art".
    q = q.or(
      `subcategory.eq.${category},and(subcategory.is.null,category.eq.${category})`,
    );
  }

  const { data, error } = await q;
  if (error) throw error;
  return (data ?? []) as EventRow[];
}

interface VenueMeta {
  photoUrl: string | null;
  rank: number | null;
}

async function fetchVenueMeta(supabase: any): Promise<Record<string, VenueMeta>> {
  // Pull every active venue's name + photo + rank in one call. We don't
  // bbox-filter or .in() because event venue_names drift from venues.name
  // (punctuation, "The " prefix) and we'd silently miss ~20% of joins.
  const result: Record<string, VenueMeta> = {};
  const { data } = await supabase
    .from("venues")
    .select("name,photo_url,public_interest_rank")
    .eq("is_active", true)
    .limit(20000);
  for (const row of data ?? []) {
    const k = normalizeVenueKey(row.name);
    if (!k) continue;
    // If duplicates normalize to the same key, keep the higher-ranked row.
    const existing = result[k];
    if (!existing || (row.public_interest_rank ?? 0) > (existing.rank ?? 0)) {
      result[k] = { photoUrl: row.photo_url ?? null, rank: row.public_interest_rank ?? null };
    }
  }
  return result;
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
 * Learned category weights from the user's own click/favorite/plan-add
 * history. Ported from the carousels edge function — same `get_category_affinity`
 * Postgres RPC, same normalization to 0-1. Anonymous → empty map → no boost.
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
 * Community engagement signal: total favorites + plan-adds per event from
 * everyone (personal + community columns summed). Used to surface what
 * other users are actually saving, a passive crowd signal with no privacy
 * concerns. Falls back to empty map if the RPC doesn't exist or errors.
 */
async function fetchCommunityScores(supabase: any, userId: string | null): Promise<Record<string, number>> {
  try {
    const { data } = await supabase.rpc("get_community_scores", { p_user_id: userId });
    if (!data || !Array.isArray(data)) return {};
    const out: Record<string, number> = {};
    for (const row of data) {
      const favs = (row.personal_favorites ?? 0) + (row.community_favorites ?? 0);
      const plans = (row.personal_plan_adds ?? 0) + (row.community_plan_adds ?? 0);
      const score = favs + plans * 2; // plan-adds are stronger intent than favorites
      if (score > 0) out[row.item_id] = score;
    }
    return out;
  } catch {
    return {};
  }
}

// ── Scoring ──

function scoreEvent(
  e: EventRow,
  resolvedImage: string | null,
  venueRank: number | null,
  userLat: number,
  userLng: number,
  prefs: UserPrefs | null,
  affinityMap: Record<string, number>,
  communityScores: Record<string, number>,
  maxCommunityScore: number,
  nowMs: number,
): number {
  const eLat = e.venue_lat;
  const eLng = e.venue_lng;
  const distance = eLat != null && eLng != null ? distanceMiles(userLat, userLng, eLat, eLng) : 30;
  const proxScore = 1 / (1 + distance / 4);

  let freshness = 0.3;
  if (e.start_at) {
    const startMs = new Date(e.start_at).getTime();
    if (startMs >= nowMs) {
      const daysOut = (startMs - nowMs) / (24 * 60 * 60 * 1000);
      freshness = Math.max(0, Math.min(1, 1 - daysOut / 14));
    } else {
      freshness = 0;
    }
  }

  const rankScore = (venueRank != null ? Math.min(100, Math.max(0, venueRank)) : 0) / 100;

  const tasteCats = new Set((prefs?.selected_categories ?? []).map((c) => c.toLowerCase().trim()));
  const tasteVenues = new Set((prefs?.selected_venues ?? []).map((v) => v.toLowerCase().trim()));
  const taste =
    tasteCats.size > 0 &&
    (tasteCats.has(e.category.toLowerCase()) ||
      (e.subcategory && tasteCats.has(e.subcategory.toLowerCase())))
      ? 1
      : 0;
  const savedVenueBoost =
    e.venue_name && tasteVenues.has(e.venue_name.toLowerCase().trim()) ? 1 : 0;

  const hasImage = resolvedImage ? 1 : 0;

  // Affinity: learned preference from this user's own history (categories
  // they've clicked / favorited / added to plans most). 0-1 normalized.
  const affinity =
    (e.subcategory && affinityMap[e.subcategory.toLowerCase()]) ||
    affinityMap[e.category.toLowerCase()] ||
    0;

  // Community: log-scaled crowd save count. Log so one wildly popular event
  // doesn't dwarf everyone — diminishing returns above ~50 saves.
  const rawSaves = communityScores[e.id] ?? 0;
  const community = maxCommunityScore > 0 ? Math.log1p(rawSaves) / Math.log1p(maxCommunityScore) : 0;

  // proximity 20 · rank 26 · freshness 13 · taste 15 · saved 8 · image 4
  //   + affinity 8 · community 6 = 100
  return (
    proxScore * 0.20 +
    rankScore * 0.26 +
    freshness * 0.13 +
    taste * 0.15 +
    savedVenueBoost * 0.08 +
    hasImage * 0.04 +
    affinity * 0.08 +
    community * 0.06
  );
}

// ── Cursor pagination (opaque offset) ──

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
    const radiusMiles = Math.min(parseFloat(url.searchParams.get("radius") ?? "50"), 100);
    const dateFilter = url.searchParams.get("date_filter");
    const limit = Math.min(parseInt(url.searchParams.get("limit") ?? "50", 10), 100);
    const offset = decodeCursor(url.searchParams.get("cursor"));

    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

    // Cache key includes user_id so per-user taste ordering is stable.
    // Anonymous requests share one entry. Score is frozen for the TTL window
    // so refreshes within 10 min return the IDENTICAL order.
    const cacheKey = [
      Math.round(lat * 10) / 10,
      Math.round(lng * 10) / 10,
      (query ?? "").trim().toLowerCase(),
      category ?? "",
      radiusMiles,
      dateFilter ?? "",
      userId ?? "anon",
    ].join("|");

    let scored: ScoredItem[];
    const cached = getCached(cacheKey);
    if (cached) {
      scored = cached.scored;
    } else {
      const events = await fetchEvents(supabase, lat, lng, query, category, radiusMiles, dateFilter);
      const [venueMeta, prefs, affinityMap, communityScores] = await Promise.all([
        fetchVenueMeta(supabase),
        userId ? fetchUserPrefs(supabase, userId) : Promise.resolve(null),
        fetchCategoryAffinity(supabase, userId),
        fetchCommunityScores(supabase, userId),
      ]);
      const maxCommunityScore = Math.max(0, ...Object.values(communityScores));
      const nowMs = Math.floor(Date.now() / CACHE_TTL_MS) * CACHE_TTL_MS;

      const raw = events
        .map((e) => {
          const venueKey = normalizeVenueKey(e.venue_name);
          const meta = venueKey ? venueMeta[venueKey] : undefined;
          const resolvedImage = e.image_url || meta?.photoUrl || null;
          const venueRank = meta?.rank ?? null;
          const dist = e.venue_lat != null && e.venue_lng != null
            ? distanceMiles(lat, lng, e.venue_lat, e.venue_lng)
            : 999;
          return {
            e,
            resolvedImage,
            dist,
            venueRank,
            score: scoreEvent(e, resolvedImage, venueRank, lat, lng, prefs, affinityMap, communityScores, maxCommunityScore, nowMs),
          };
        })
        .filter((x) => x.dist <= radiusMiles)
        .filter((x) => x.venueRank == null || x.venueRank > 10)
        .sort((a, b) => b.score - a.score || a.e.id.localeCompare(b.e.id));

      // Venue-diverse ordering: round-robin through venue groups so no single
      // venue (Harvard Square, BPL) takes the entire top-10 slots. Within a
      // round we take the top-scoring event from each venue, sorted by the
      // venue's best score. Deterministic → stable under refresh.
      scored = interleaveByVenue(raw);

      cache.set(cacheKey, { scored, timestamp: Date.now() });
    }

    const page = scored.slice(offset, offset + limit);
    const hasMore = scored.length > offset + limit;

    const items = page.map(({ e, resolvedImage, dist }) => ({
      id: e.id,
      title: e.title,
      startAt: e.start_at,
      endAt: e.end_at,
      venueName: e.venue_name,
      venueCity: e.venue_city,
      venueLat: e.venue_lat,
      venueLng: e.venue_lng,
      imageUrl: resolvedImage,
      category: e.category,
      subcategory: e.subcategory,
      priceMin: e.price_min,
      priceMax: e.price_max,
      isFree: e.is_free,
      distanceMiles: dist,
      ticketUrl: e.ticket_url,
      sourceUrl: e.source_url,
      city: e.city,
      description: e.description,
    }));

    return new Response(
      JSON.stringify({
        items,
        cursor: hasMore ? encodeCursor(offset + limit) : null,
        hasMore,
        total: scored.length,
      }),
      {
        headers: {
          "Content-Type": "application/json",
          "Cache-Control": "public, max-age=60", // CDN may cache anonymous requests briefly
          ...CORS_HEADERS,
        },
      },
    );
  } catch (err) {
    console.error("explore-events error:", err);
    return new Response(
      JSON.stringify({ error: err instanceof Error ? err.message : "Unknown error" }),
      {
        status: 500,
        headers: { "Content-Type": "application/json", ...CORS_HEADERS },
      },
    );
  }
});
