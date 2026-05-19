-- Latency sprint, Day 4 (CTO hybrid #1 — pre-aggregated rollup).
--
-- mv_carousel_payload materializes the heavy join + rank computation that
-- search_events_stratified currently does at request time:
--
--   events e LEFT JOIN venues v ON lower(v.name) = lower(e.venue_name)
--   WHERE e.is_active AND e.start_at >= now() AND start_at < now()+90d
--     AND COALESCE(v.public_interest_rank, 11) > 10
--   ORDER BY rank_score DESC, start_at ASC
--   PARTITION BY category, ROW_NUMBER() AS rn
--
-- At ~13.8k active events × 30k venues this join + window is the dominant
-- cost in the RPC and the reason the 10s subcategory bug exists when
-- planner stats are stale. By pre-computing it into an MV refreshed every
-- 5 min via pg_cron, the edge function's RPC fallback path becomes a
-- single bounded SELECT against the MV instead of a full re-scan.
--
-- The MV deliberately omits user geo. Distance is computed at the edge
-- function as today, applied as a final WHERE on the MV read. This keeps
-- the MV non-parametric (one MV serves all users) at the cost of a
-- post-filter — which the venues geo index keeps cheap.
--
-- Card fields stored exactly match the CX-locked card field set:
--   For You: title, start_at, venue_name, venue_city, distanceMiles,
--            imageUrl, category, isFree
--   Explore Events: + priceMin/Max, time_known
-- Plus the join-side venue fields needed for the Explore Places card.
-- No field is added or removed vs. what the edge function returns today;
-- the MV is a denormalized projection, not a content change.

-- ──────────────────────────────────────────────────────────────────────
-- 1) Materialized view
-- ──────────────────────────────────────────────────────────────────────
DROP MATERIALIZED VIEW IF EXISTS public.mv_carousel_payload;

-- LATERAL pick of ONE venue per event. Multiple venues can share a
-- lowercased name (data condition documented in migration
-- 20260430000001_event_duplicate_of.sql), so a naive LEFT JOIN
-- multiplies rows and breaks the MV's "one row per event id"
-- guarantee. We pick the highest-ranked active venue match — same
-- result the search_events_stratified RPC would produce after its
-- combined-CTE de-dupe step, but cheaper because the dedupe happens
-- once at refresh time instead of on every query.
CREATE MATERIALIZED VIEW public.mv_carousel_payload AS
SELECT
  -- partition keys
  e.category,
  e.subcategory,
  -- ranking
  COALESCE(v.public_interest_rank, 0) AS rank_score,
  ROW_NUMBER() OVER (
    PARTITION BY e.category
    ORDER BY COALESCE(v.public_interest_rank, 0) DESC, e.start_at ASC
  ) AS rn_category,
  ROW_NUMBER() OVER (
    PARTITION BY e.subcategory
    ORDER BY COALESCE(v.public_interest_rank, 0) DESC, e.start_at ASC
  ) AS rn_subcategory,
  -- card fields — for-you + explore events
  e.id,
  e.title,
  e.description,
  e.sub_subcategory,
  e.team_name,
  e.start_at,
  e.end_at,
  e.is_free,
  e.price_min,
  e.price_max,
  e.ticket_url,
  e.image_url,
  e.venue_name,
  e.venue_address,
  e.venue_city,
  e.venue_state,
  e.venue_lat,
  e.venue_lng,
  e.source,
  e.source_url,
  e.city,
  e.time_known,
  -- denormalized venue fields for the geo filter + Explore Places card
  v.lat            AS canonical_venue_lat,
  v.lng            AS canonical_venue_lng,
  v.photo_url      AS venue_photo_url,
  v.google_rating  AS venue_google_rating,
  v.description    AS venue_description
FROM public.events e
LEFT JOIN LATERAL (
  SELECT id, name, lat, lng, public_interest_rank, photo_url,
         google_rating, description
    FROM public.venues
   WHERE lower(name) = lower(e.venue_name)
     AND is_active = true
   ORDER BY public_interest_rank DESC NULLS LAST, id ASC
   LIMIT 1
) v ON true
WHERE e.is_active = true
  AND e.start_at >= now()
  AND e.start_at <  now() + interval '90 days'
  -- Mirrors search_events_stratified's exclusion of low-rank venues.
  AND COALESCE(v.public_interest_rank, 11) > 10;

-- Unique index is required for REFRESH MATERIALIZED VIEW CONCURRENTLY.
CREATE UNIQUE INDEX mv_carousel_payload_unique_idx
  ON public.mv_carousel_payload (id);

-- Read indexes for the lane queries (mirrors covering indexes on events).
CREATE INDEX mv_carousel_payload_category_rn_idx
  ON public.mv_carousel_payload (category, rn_category)
  WHERE rn_category <= 50;

CREATE INDEX mv_carousel_payload_subcategory_rn_idx
  ON public.mv_carousel_payload (subcategory, rn_subcategory)
  WHERE subcategory IS NOT NULL AND rn_subcategory <= 5;

-- Geo filter on the canonical venue coords (preferred) with fallback to
-- event coords. Two indexes — planner picks based on selectivity.
CREATE INDEX mv_carousel_payload_geo_canonical_idx
  ON public.mv_carousel_payload (canonical_venue_lat, canonical_venue_lng)
  WHERE canonical_venue_lat IS NOT NULL;

CREATE INDEX mv_carousel_payload_geo_event_idx
  ON public.mv_carousel_payload (venue_lat, venue_lng)
  WHERE venue_lat IS NOT NULL;

COMMENT ON MATERIALIZED VIEW public.mv_carousel_payload IS
  'Pre-computed carousel feed. Refreshed CONCURRENTLY every 5 min by pg_cron job ''mv_carousel_payload_refresh''. Read path: edge function falls back to this MV instead of search_events_stratified RPC when carousel_cache is cold. Card fields are byte-identical to what carousels edge fn returns — denormalized projection only.';

-- ──────────────────────────────────────────────────────────────────────
-- 2) pg_cron refresh schedule
--
-- 5-minute cadence matches today's scripts/refresh_carousel_cache.js
-- cadence so freshness contract is unchanged. CONCURRENTLY means
-- readers are never blocked.
-- ──────────────────────────────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS pg_cron;

-- Unschedule any prior job with the same name so this migration is
-- idempotent across re-runs.
DO $$
BEGIN
  PERFORM cron.unschedule(jobid)
    FROM cron.job
    WHERE jobname = 'mv_carousel_payload_refresh';
EXCEPTION WHEN OTHERS THEN
  -- pg_cron not yet installed on first migration run, or no job.
  NULL;
END $$;

SELECT cron.schedule(
  'mv_carousel_payload_refresh',
  '*/5 * * * *',
  $$REFRESH MATERIALIZED VIEW CONCURRENTLY public.mv_carousel_payload;$$
);

-- Run one synchronous refresh now so the MV has data the moment the
-- migration finishes (rather than waiting up to 5 min for the first
-- cron tick). Plain REFRESH (without CONCURRENTLY) works inside the
-- migration's transaction — it takes an AccessExclusiveLock for the
-- duration, which is fine here since the MV was just created and
-- no reader can hold it.
REFRESH MATERIALIZED VIEW public.mv_carousel_payload;

-- ──────────────────────────────────────────────────────────────────────
-- 3) Helper RPC — geo-filtered read of the MV, the drop-in replacement
--    for fetchAllEvents() inside supabase/functions/carousels/index.ts.
--
-- Returns the same shape as the existing EventRow + distance_miles +
-- venue_description that the edge function uses today. The cutover is
-- a one-line change inside fetchAllEvents (and fetchRawSportsEvents):
--
--   - .from("events").select("*").eq("is_active", true).gte(...)
--   + supabase.rpc("mv_events_for_geo", { p_lat, p_lng, p_radius_miles })
--
-- Cutover steps documented in docs/latency-sprint-apply.md. Until then
-- the edge function continues reading base tables and the MV is
-- exercised only by direct callers (or A/B traffic).
-- ──────────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION public.mv_events_for_geo(
  p_lat double precision,
  p_lng double precision,
  p_radius_miles double precision DEFAULT 75,
  p_max_results int DEFAULT 7000
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
  source text,
  source_url text,
  city text,
  time_known boolean,
  rank_score int,
  distance_miles double precision,
  venue_description text
) LANGUAGE sql STABLE AS $$
  WITH bbox AS (
    SELECT
      (p_radius_miles * 1.2) / 69.0 AS dlat,
      (p_radius_miles * 1.2) / 50.0 AS dlng
  )
  SELECT
    m.id, m.title, m.description, m.category, m.subcategory,
    m.sub_subcategory, m.team_name, m.start_at, m.end_at,
    m.is_free, m.price_min, m.price_max, m.ticket_url, m.image_url,
    m.venue_name, m.venue_address, m.venue_city, m.venue_state,
    m.venue_lat, m.venue_lng, m.source, m.source_url, m.city,
    m.time_known, m.rank_score,
    CASE
      WHEN m.canonical_venue_lat IS NOT NULL THEN
        sqrt(power((m.canonical_venue_lat - p_lat) * 69.0, 2)
           + power((m.canonical_venue_lng - p_lng) * 50.0, 2))
      WHEN m.venue_lat IS NOT NULL THEN
        sqrt(power((m.venue_lat - p_lat) * 69.0, 2)
           + power((m.venue_lng - p_lng) * 50.0, 2))
      ELSE NULL
    END AS distance_miles,
    m.venue_description
  FROM public.mv_carousel_payload m
  CROSS JOIN bbox
  WHERE m.start_at >= now()
    AND (
      (m.canonical_venue_lat IS NOT NULL
        AND m.canonical_venue_lat BETWEEN p_lat - bbox.dlat AND p_lat + bbox.dlat
        AND m.canonical_venue_lng BETWEEN p_lng - bbox.dlng AND p_lng + bbox.dlng)
      OR
      (m.canonical_venue_lat IS NULL AND (
        m.venue_lat IS NULL OR (
          m.venue_lat BETWEEN p_lat - bbox.dlat AND p_lat + bbox.dlat
          AND m.venue_lng BETWEEN p_lng - bbox.dlng AND p_lng + bbox.dlng
        )
      ))
    )
  ORDER BY m.rank_score DESC, m.start_at ASC
  LIMIT p_max_results;
$$;

COMMENT ON FUNCTION public.mv_events_for_geo IS
  'Geo-filtered read of mv_carousel_payload. Drop-in replacement for fetchAllEvents() in supabase/functions/carousels/index.ts. Distance is computed in SQL using the same formula the existing TS code uses, so byte-identical card distance values. Cutover documented in docs/latency-sprint-apply.md.';

GRANT EXECUTE ON FUNCTION public.mv_events_for_geo TO anon, authenticated, service_role;

-- ──────────────────────────────────────────────────────────────────────
-- 4) Permissions on the MV itself
-- ──────────────────────────────────────────────────────────────────────
GRANT SELECT ON public.mv_carousel_payload TO anon, authenticated, service_role;
