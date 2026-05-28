-- Carousel sparsity fix — comedy / for-kids / for-you rails were
-- rendering with 1-3 events despite the live `events` table having
-- many more qualifying rows.
--
-- ── Root cause ────────────────────────────────────────────────────────
-- mv_events_for_geo() has `ORDER BY rank_score DESC LIMIT p_max_results`
-- with p_max_results=7000 in the carousels edge function call. PostgREST
-- enforces a hard 1000-row wire cap on RPC returns (verified — requesting
-- 2000 returns exactly 1000). The 1000 rows are all from the highest
-- rank tier (observed cliff at rank 88 for Waltham). Result: events at
-- venues with public_interest_rank in the 11-87 band — small comedy
-- clubs, branch libraries, brew-pub open mics — pass the MV's
-- `rank > 10` quality gate but then get silently chopped off by the
-- RPC's rank-sorted output + PostgREST cap.
--
-- ── Fix ──────────────────────────────────────────────────────────────
-- Replace mv_events_for_geo() with a stratified projection: top N per
-- parent category UNION top M per subcategory, geo-scoped, ordered by
-- coverage tier (LEAST(rn_cat, rn_sub)) so every category gets oxygen
-- before the LIMIT trims. LIMIT capped at 900 to stay safely under
-- PostgREST's 1000-row wire cap.
--
-- The MV (mv_carousel_payload) is NOT touched. Its existing rank > 10
-- filter (from 20260526120100) is intentional — keeps community
-- centers / K-12 fields / junk venues out of carousels entirely.
-- The threshold itself may want to tighten beyond 10 — separate
-- discussion, not this migration.
--
-- ── Latency posture ──────────────────────────────────────────────────
-- Old query: bbox index scan + sort + limit 7000 (trimmed to 1000).
-- New query: same bbox index scan + 2 ROW_NUMBER() windows over the
-- geo-filtered set (~5-15k rows partitioned by ~15 categories and ~30
-- subcategories) + filter + sort + limit 900.
-- Window functions at this scale are CPU-cheap (<10 ms). Wire payload
-- shrinks (≤900 vs 1000), partially offsetting. Net user-facing
-- latency: within noise (±10 ms).
--
-- ── Capacity / coverage trade-off ────────────────────────────────────
-- Stratification caps: 50 per parent category, 100 per subcategory.
-- Pre-dedup ceiling: 12*50 + 30*100 = 3600. After geo + dedup, dense
-- metro (Boston) actuals are ~1200-2000 → LIMIT 900 trims via the
-- coverage-ordered sort, which preserves every category's top entries
-- and trims the deep tail of the densest categories first.
-- A single subcategory tab gets ~50-100 events (vs ~3-30 today).
-- Exhaustive coverage of a focused subcategory (e.g. all 114 for-kids
-- events near Waltham) needs a follow-up p_subcategory_focus param.

DROP FUNCTION IF EXISTS public.mv_events_for_geo(double precision, double precision, double precision, int);

CREATE FUNCTION public.mv_events_for_geo(
  p_lat double precision,
  p_lng double precision,
  p_radius_miles double precision DEFAULT 75,
  p_max_results int DEFAULT 900
) RETURNS TABLE (
  id text,
  title text,
  description text,
  category text,
  subcategory text,
  sub_subcategory text,
  team_name text,
  start_at timestamptz,
  end_at timestamptz,
  is_free boolean,
  price_min numeric,
  price_max numeric,
  ticket_url text,
  image_url text,
  venue_name text,
  venue_address text,
  venue_city text,
  venue_state text,
  venue_lat double precision,
  venue_lng double precision,
  event_city text,
  event_state text,
  event_lat double precision,
  event_lng double precision,
  source text,
  source_url text,
  city text,
  time_known boolean,
  rank_score int,
  distance_miles double precision,
  venue_description text,
  venue_photo_url text
) LANGUAGE sql STABLE AS $$
  WITH bbox AS (
    SELECT
      (p_radius_miles * 1.2) / 69.0 AS dlat,
      (p_radius_miles * 1.2) / 50.0 AS dlng
  ),
  geo_filtered AS (
    SELECT
      m.*,
      -- Distance preference: event_lat → canonical_venue_lat → venue_lat → NULL.
      -- Same priority as 20260526120100.
      CASE
        WHEN m.event_lat IS NOT NULL THEN
          sqrt(power((m.event_lat - p_lat) * 69.0, 2)
             + power((m.event_lng - p_lng) * 50.0, 2))
        WHEN m.canonical_venue_lat IS NOT NULL THEN
          sqrt(power((m.canonical_venue_lat - p_lat) * 69.0, 2)
             + power((m.canonical_venue_lng - p_lng) * 50.0, 2))
        WHEN m.venue_lat IS NOT NULL THEN
          sqrt(power((m.venue_lat - p_lat) * 69.0, 2)
             + power((m.venue_lng - p_lng) * 50.0, 2))
        ELSE NULL
      END AS dist_mi
    FROM public.mv_carousel_payload m
    CROSS JOIN bbox
    WHERE m.start_at >= now()
      AND (
        (m.event_lat IS NOT NULL
          AND m.event_lat BETWEEN p_lat - bbox.dlat AND p_lat + bbox.dlat
          AND m.event_lng BETWEEN p_lng - bbox.dlng AND p_lng + bbox.dlng)
        OR
        (m.event_lat IS NULL AND m.canonical_venue_lat IS NOT NULL
          AND m.canonical_venue_lat BETWEEN p_lat - bbox.dlat AND p_lat + bbox.dlat
          AND m.canonical_venue_lng BETWEEN p_lng - bbox.dlng AND p_lng + bbox.dlng)
        OR
        (m.event_lat IS NULL AND m.canonical_venue_lat IS NULL AND (
          m.venue_lat IS NULL OR (
            m.venue_lat BETWEEN p_lat - bbox.dlat AND p_lat + bbox.dlat
            AND m.venue_lng BETWEEN p_lng - bbox.dlng AND p_lng + bbox.dlng
          )
        ))
      )
  ),
  stratified AS (
    SELECT
      *,
      ROW_NUMBER() OVER (
        PARTITION BY category
        ORDER BY rank_score DESC, start_at ASC
      ) AS rn_cat_local,
      ROW_NUMBER() OVER (
        PARTITION BY subcategory
        ORDER BY rank_score DESC, start_at ASC
      ) AS rn_sub_local
    FROM geo_filtered
  )
  SELECT
    id, title, description, category, subcategory,
    sub_subcategory, team_name, start_at, end_at,
    is_free, price_min, price_max, ticket_url, image_url,
    venue_name, venue_address, venue_city, venue_state,
    venue_lat, venue_lng,
    event_city, event_state, event_lat, event_lng,
    source, source_url, city,
    time_known, rank_score,
    dist_mi AS distance_miles,
    venue_description,
    venue_photo_url
  FROM stratified
  WHERE rn_cat_local <= 50
     OR (subcategory IS NOT NULL AND rn_sub_local <= 100)
  -- Interleave so every category & subcategory gets at least its top
  -- entries before the LIMIT trims. Coverage > completeness when capped.
  ORDER BY
    LEAST(rn_cat_local, rn_sub_local) ASC,
    rank_score DESC,
    start_at ASC
  LIMIT LEAST(p_max_results, 900);
$$;

GRANT EXECUTE ON FUNCTION public.mv_events_for_geo TO anon, authenticated, service_role;

COMMENT ON FUNCTION public.mv_events_for_geo IS
  'Geo-scoped, stratified read of mv_carousel_payload. Returns top 50 per parent category UNION top 100 per subcategory, ordered to ensure category breadth before the 900-row cap. Replaces the previous rank-sorted version, which combined with PostgREST''s 1000-row wire cap created a hard rank cliff that hid mid-rank venues entirely.';
