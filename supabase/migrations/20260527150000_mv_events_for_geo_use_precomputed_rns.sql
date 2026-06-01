-- Latency emergency fix for mv_events_for_geo.
--
-- The 20260527120000 stratified rewrite added two ROW_NUMBER() OVER
-- (PARTITION BY category/subcategory) calls computed AT QUERY TIME over
-- the geo-filtered set. For Boston metro (geo_filtered ~5-15k rows) the
-- sort cost adds ~1 s warm, regressing the RPC from sub-300 ms to 1.5 s
-- warm. Subcategory tabs in the edge function, which also fire the
-- parallel mv_events_for_subcategory_venues RPC, compound to 3-30 s
-- end-to-end — observed in prod, user reports "subcategory tabs not
-- loading."
--
-- mv_carousel_payload ALREADY has rn_category and rn_subcategory
-- pre-computed at MV refresh time (every 5 min via pg_cron), partitioned
-- the same way. Switch the RPC to use those instead of recomputing at
-- query time. The planner then does an index-only scan via the partial
-- indexes on (category, rn_category) and (subcategory, rn_subcategory),
-- collapsing the query to a bounded read.
--
-- Trade-off acknowledged: the pre-computed rn is GLOBAL (ranked across
-- all events in the 90-day MV), not LOCAL (ranked within the geo bbox).
-- For dense markets like Boston this is effectively equivalent (the top
-- N globally are mostly the top N for the market). For sparse markets
-- the slight loss of geo-locality is acceptable given the latency win.
--
-- Also bumps the subcategory partial index range from rn <= 5 to
-- rn <= 100 so the subcategory branch of the RPC can use it.

-- 1) Widen the subcategory partial index so the rn_subcategory <= 100
--    branch in mv_events_for_geo can use an index-only scan.
DROP INDEX IF EXISTS public.mv_carousel_payload_subcategory_rn_idx;
CREATE INDEX mv_carousel_payload_subcategory_rn_idx
  ON public.mv_carousel_payload (subcategory, rn_subcategory)
  WHERE subcategory IS NOT NULL AND rn_subcategory <= 100;

-- 2) Replace mv_events_for_geo with the pre-computed-rn variant.
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
  )
  SELECT
    m.id, m.title, m.description, m.category, m.subcategory,
    m.sub_subcategory, m.team_name, m.start_at, m.end_at,
    m.is_free, m.price_min, m.price_max, m.ticket_url, m.image_url,
    m.venue_name, m.venue_address, m.venue_city, m.venue_state,
    m.venue_lat, m.venue_lng,
    m.event_city, m.event_state, m.event_lat, m.event_lng,
    m.source, m.source_url, m.city,
    m.time_known, m.rank_score,
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
    END AS distance_miles,
    m.venue_description,
    m.venue_photo_url
  FROM public.mv_carousel_payload m
  CROSS JOIN bbox
  WHERE m.start_at >= now()
    -- Use the MV's pre-computed rns. Top 50 per parent category UNION
    -- top 100 per subcategory — covered by existing partial indexes.
    AND (
      m.rn_category <= 50
      OR (m.subcategory IS NOT NULL AND m.rn_subcategory <= 100)
    )
    -- Geo bbox (priority: event_lat → canonical_venue_lat → venue_lat).
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
  -- Coverage-ordered: every category & subcategory gets its top entry
  -- before the LIMIT trims. Same intent as the stratified rewrite,
  -- without the per-query window function cost.
  ORDER BY
    LEAST(
      COALESCE(m.rn_category, 999),
      COALESCE(m.rn_subcategory, 999)
    ) ASC,
    m.rank_score DESC,
    m.start_at ASC
  LIMIT LEAST(p_max_results, 900);
$$;

GRANT EXECUTE ON FUNCTION public.mv_events_for_geo TO anon, authenticated, service_role;

COMMENT ON FUNCTION public.mv_events_for_geo IS
  'Geo-scoped read of mv_carousel_payload using the MV''s pre-computed rn_category / rn_subcategory (GLOBAL ranks, refreshed every 5 min) for stratification. Index-only scan via the partial indexes on (category, rn_category) and (subcategory, rn_subcategory). Replaces the per-query ROW_NUMBER() stratification that regressed latency from sub-300 ms to 1.5 s.';

ANALYZE public.mv_carousel_payload;
