import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { jsonCompressed, corsPreflight } from "../_shared/compress.ts";

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
  event_city: string | null;
  event_state: string | null;
  event_lat: number | null;
  event_lng: number | null;
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

// Maps the date_filter slug to a {startIso, endIso} window in absolute UTC
// time, snapped to the user's LOCAL midnight via [tzOffsetMin].
//
// [tzOffsetMin] is (local − UTC) in minutes. For US Eastern Daylight Time
// (UTC−4) the client sends -240. Defaulting to 0 (UTC) preserves the old
// behavior for clients that haven't been updated yet.
//
// When dateFilter == "custom", `dateStart` / `dateEnd` are inclusive
// ISO-date strings (YYYY-MM-DD) interpreted as the user's LOCAL day. The
// end is expanded to the start of the following local day so events on
// the end date itself are included.
function resolveDateWindow(
  dateFilter: string | null,
  dateStart: string | null = null,
  dateEnd: string | null = null,
  tzOffsetMin: number = 0,
): { startIso: string; endIso: string } {
  const now = Date.now();
  const DAY = 24 * 60 * 60 * 1000;
  const offsetMs = tzOffsetMin * 60 * 1000;

  // UTC instant of "the start of today" in the user's local time.
  // Trick: shift now into a pseudo-UTC that matches local wall-clock,
  // snap to UTC midnight, then shift back.
  const localNow = new Date(now + offsetMs);
  localNow.setUTCHours(0, 0, 0, 0);
  const startOfTodayUtcMs = localNow.getTime() - offsetMs;
  const localDow = new Date(now + offsetMs).getUTCDay(); // 0=Sun..6=Sat in local

  switch (dateFilter) {
    case "today": {
      const end = startOfTodayUtcMs + DAY;
      return {
        startIso: new Date(Math.max(now, startOfTodayUtcMs)).toISOString(),
        endIso: new Date(end).toISOString(),
      };
    }
    case "tomorrow": {
      const start = startOfTodayUtcMs + DAY;
      const end = start + DAY;
      return { startIso: new Date(start).toISOString(), endIso: new Date(end).toISOString() };
    }
    case "this_week": {
      // Now through the end of the current week — i.e. up to the upcoming
      // Monday local-00:00 (covers the rest of Mon–Sun). If today is Monday
      // we still want the full week ahead, so 0 days-until rolls to 7.
      let daysUntilMon = (1 - localDow + 7) % 7;
      if (daysUntilMon === 0) daysUntilMon = 7;
      const mon = startOfTodayUtcMs + daysUntilMon * DAY;
      return {
        startIso: new Date(Math.max(now, startOfTodayUtcMs)).toISOString(),
        endIso: new Date(mon).toISOString(),
      };
    }
    case "this_weekend": {
      // Fri local-00:00 through Mon local-00:00.
      let daysUntilFri = (5 - localDow + 7) % 7;
      // Already inside Fri/Sat/Sun → use the ongoing weekend.
      if (localDow === 5 || localDow === 6 || localDow === 0) daysUntilFri = 0;
      const fri = startOfTodayUtcMs + daysUntilFri * DAY;
      const mon = fri + 3 * DAY;
      return {
        startIso: new Date(Math.max(now, fri)).toISOString(),
        endIso: new Date(mon).toISOString(),
      };
    }
    case "next_weekend": {
      // The Fri→Mon AFTER this weekend: take this weekend's Friday and add 7d.
      let daysUntilFri = (5 - localDow + 7) % 7;
      if (localDow === 5 || localDow === 6 || localDow === 0) daysUntilFri = 0;
      const fri = startOfTodayUtcMs + (daysUntilFri + 7) * DAY;
      const mon = fri + 3 * DAY;
      return {
        startIso: new Date(fri).toISOString(),
        endIso: new Date(mon).toISOString(),
      };
    }
    case "next_30_days": {
      const end = startOfTodayUtcMs + 30 * DAY;
      return {
        startIso: new Date(Math.max(now, startOfTodayUtcMs)).toISOString(),
        endIso: new Date(end).toISOString(),
      };
    }
    case "custom": {
      // YYYY-MM-DD parsed at the user's LOCAL midnight (not UTC midnight).
      // End shifts to start-of-next-local-day so the range is inclusive.
      const parseLocalDate = (s: string): number => {
        const utcMs = Date.parse(`${s}T00:00:00Z`);
        return utcMs - offsetMs;
      };
      const startMs = dateStart ? parseLocalDate(dateStart) : Number.NaN;
      const endMs = dateEnd ? parseLocalDate(dateEnd) : Number.NaN;
      if (Number.isFinite(startMs) && Number.isFinite(endMs)) {
        return {
          startIso: new Date(Math.max(now, startMs)).toISOString(),
          endIso: new Date(endMs + DAY).toISOString(),
        };
      }
      // Bad/missing range — fall through to the default window.
      return { startIso: new Date(now).toISOString(), endIso: new Date(now + 60 * DAY).toISOString() };
    }
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
  dateStart: string | null = null,
  dateEnd: string | null = null,
  tzOffsetMin: number = 0,
): Promise<EventRow[]> {
  // Generous bbox prefilter — tighter radius applied post-fetch.
  const latDelta = radiusMiles / 55;
  const lngDelta = radiusMiles / 45;
  const { startIso: nowIso, endIso } = resolveDateWindow(dateFilter, dateStart, dateEnd, tzOffsetMin);

  let q = supabase
    .from("events")
    .select(
      "id,title,description,category,subcategory,start_at,end_at,is_free,price_min,price_max,ticket_url,image_url,venue_name,venue_city,venue_lat,venue_lng,event_city,event_state,event_lat,event_lng,source_url,city",
    )
    .eq("is_active", true)
    .gte("start_at", nowIso)
    // Upper bound is EXCLUSIVE: events at exactly the next-local-midnight
    // belong to the following day's bucket. With `.lte` they were leaking
    // into both today's and tomorrow's filter.
    .lt("start_at", endIso)
    .gte("venue_lat", lat - latDelta)
    .lte("venue_lat", lat + latDelta)
    .gte("venue_lng", lng - lngDelta)
    .lte("venue_lng", lng + lngDelta)
    // Drop auto-excluded events (AA meetings, youth sports, civic boards, etc.)
    // These are tagged by classify_events.js and shouldn't surface on Explore.
    .or("subcategory.is.null,subcategory.neq.no-public-interest")
    .order("start_at", { ascending: true })
    .limit(500);

  if (query && query.trim()) {
    const safe = query.trim().replace(/%/g, "").replace(/,/g, " ");
    q = q.or(`title.ilike.%${safe}%,venue_name.ilike.%${safe}%`);
  }
  if (category && category !== "foryou") {
    q = q.or(`category.eq.${category},subcategory.eq.${category}`);
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

// City (lowercased) → stable Storage photo URL. Last-resort image for events
// that don't resolve to a venue (municipal at-large rows: venue_name is null,
// so no venue photo). Only Storage-hosted rows are loaded — legacy rotted
// Google Places URLs (which also embed an API key) are filtered out so they
// never reach a client. Mirrors carousels/index.ts fetchCityPhotoCache.
async function fetchCityPhotos(supabase: any): Promise<Record<string, string>> {
  const result: Record<string, string> = {};
  const { data } = await supabase
    .from("city_photos")
    .select("city,photo_url")
    .like("photo_url", "%/storage/v1/object/public/city-photos/%");
  for (const row of data ?? []) {
    if (row.city && row.photo_url) result[row.city.toLowerCase()] = row.photo_url;
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
  _resolvedImage: string | null,
  venueRank: number | null,
  userLat: number,
  userLng: number,
  prefs: UserPrefs | null,
  affinityMap: Record<string, number>,
  _communityScores: Record<string, number>,
  _maxCommunityScore: number,
  nowMs: number,
): number {
  // Prefer the physical event location (event_lat) over the host-venue HQ
  // (venue_lat) — matches the home carousel's distance basis.
  const eLat = e.event_lat ?? e.venue_lat;
  const eLng = e.event_lng ?? e.venue_lng;
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

  // Affinity: learned preference from this user's own history (categories
  // they've clicked / favorited / added to plans most). 0-1 normalized.
  const affinity =
    (e.subcategory && affinityMap[e.subcategory.toLowerCase()]) ||
    affinityMap[e.category.toLowerCase()] ||
    0;

  // Option A weighting — personal-dominant Explore feed:
  //   hot user (any affinity history): aff 40 · taste 20 · saved 10 · prox 20 · rank 7 · fresh 3 = 100
  //   cold start (no affinity yet):     aff  0 · taste 40 · saved 10 · prox 32 · rank 15 · fresh 3 = 100
  // Cold-start redistributes the 40 affinity points to taste/prox/rank so a
  // brand-new user still gets a sensible feed (onboarding-driven instead of
  // history-driven). Once they accumulate any affinity rows we flip back to
  // the hot weights — `hasAffinity` triggers off any non-empty affinityMap.
  // The previous community + hasImage signals are dropped from the score:
  // community is redundant under heavy personal weighting (it's basically
  // crowd-affinity), and hasImage was a quality tiebreaker that belongs in
  // a filter or as a render decision, not the sort.
  const hasAffinity = Object.keys(affinityMap).length > 0;
  const wAff = hasAffinity ? 0.40 : 0.00;
  const wTaste = hasAffinity ? 0.20 : 0.40;
  const wSaved = 0.10;
  const wProx = hasAffinity ? 0.20 : 0.32;
  const wRank = hasAffinity ? 0.07 : 0.15;
  const wFresh = 0.03;

  return (
    affinity * wAff +
    taste * wTaste +
    savedVenueBoost * wSaved +
    proxScore * wProx +
    rankScore * wRank +
    freshness * wFresh
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
    const category = url.searchParams.get("category");
    const radiusMiles = Math.min(parseFloat(url.searchParams.get("radius") ?? "50"), 100);
    const dateFilter = url.searchParams.get("date_filter");
    const dateStart = url.searchParams.get("date_start");
    const dateEnd = url.searchParams.get("date_end");
    // (local − UTC) in minutes. Defaults to 0 so older clients fall back
    // to UTC-based windows (the previous behavior) instead of erroring.
    const tzOffsetMin = parseInt(url.searchParams.get("tz_offset_min") ?? "0", 10) || 0;
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
      dateStart ?? "",
      dateEnd ?? "",
      tzOffsetMin,
      userId ?? "anon",
    ].join("|");

    let scored: ScoredItem[];
    const cached = getCached(cacheKey);
    if (cached) {
      scored = cached.scored;
    } else {
      const events = await fetchEvents(supabase, lat, lng, query, category, radiusMiles, dateFilter, dateStart, dateEnd, tzOffsetMin);
      // community-scores fetch dropped — community signal removed from
      // scoreEvent under Option A (heavy personal affinity makes it
      // redundant). Saves one Postgres RPC per request.
      const [venueMeta, cityPhotos, prefs, affinityMap] = await Promise.all([
        fetchVenueMeta(supabase),
        fetchCityPhotos(supabase),
        userId ? fetchUserPrefs(supabase, userId) : Promise.resolve(null),
        fetchCategoryAffinity(supabase, userId),
      ]);
      const communityScores: Record<string, number> = {};
      const maxCommunityScore = 0;
      const nowMs = Math.floor(Date.now() / CACHE_TTL_MS) * CACHE_TTL_MS;

      const raw = events
        .map((e) => {
          const venueKey = normalizeVenueKey(e.venue_name);
          const meta = venueKey ? venueMeta[venueKey] : undefined;
          // Last-resort city photo for venueless municipal events. Skip it for
          // farmers markets: their source carries no image and the market has
          // no venue photo, so every image-less market in a city would wear the
          // identical city skyline. Let them fall through to the client's
          // category gradient instead. Mirrors carousels/index.ts.
          const isFarmersMarket = (e.subcategory ?? "").toLowerCase() === "farmers-market";
          const cityKey = (e.venue_city ?? "").trim().toLowerCase();
          const cityPhoto = isFarmersMarket ? undefined : (cityKey ? cityPhotos[cityKey] : undefined);
          const resolvedImage = e.image_url || meta?.photoUrl || cityPhoto || null;
          const venueRank = meta?.rank ?? null;
          // Prefer event_lat/lng (real location) over venue HQ — same basis
          // as the home carousel so a given event's distance matches on both.
          const dLat = e.event_lat ?? e.venue_lat;
          const dLng = e.event_lng ?? e.venue_lng;
          const dist = dLat != null && dLng != null
            ? distanceMiles(lat, lng, dLat, dLng)
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
      // Coalesce nulls the client parses into NON-nullable String fields
      // (ExploreEventDto.venueCity/category/city). Municipal at-large rows have
      // a null venue_city (and sometimes null category/city); a single null here
      // fails kotlinx.serialization for the ENTIRE page, blanking Explore events
      // wide across a metro. Mirrors the 2026-07-16 carousels null-venue_city fix.
      venueCity: e.venue_city ?? "",
      venueLat: e.venue_lat,
      venueLng: e.venue_lng,
      eventCity: e.event_city,
      eventState: e.event_state,
      eventLat: e.event_lat,
      eventLng: e.event_lng,
      imageUrl: resolvedImage,
      category: e.category ?? "",
      subcategory: e.subcategory,
      priceMin: e.price_min,
      priceMax: e.price_max,
      isFree: e.is_free,
      distanceMiles: dist,
      ticketUrl: e.ticket_url,
      sourceUrl: e.source_url,
      city: e.city ?? "",
      description: e.description,
    }));

    return jsonCompressed(
      req,
      {
        items,
        cursor: hasMore ? encodeCursor(offset + limit) : null,
        hasMore,
        total: scored.length,
      },
      { extraHeaders: { "Cache-Control": "public, max-age=60" } },
    );
  } catch (err) {
    console.error("explore-events error:", err);
    return jsonCompressed(
      req,
      { error: err instanceof Error ? err.message : "Unknown error" },
      { status: 500 },
    );
  }
});
