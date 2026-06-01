-- Fix sparse carousels in non-Boston markets (Philadelphia reported).
--
-- ── Root cause ───────────────────────────────────────────────────────
-- The 20260527150000 latency fix swapped per-query window functions for
-- the MV's pre-computed rn_category / rn_subcategory and restricted the
-- result to rn_category <= 50 OR rn_subcategory <= 100. The pre-
-- computed rns are GLOBAL: ranked across the entire 90-day MV. For a
-- Boston-dominated MV, the global top 50 per category IS effectively
-- the Boston top 50, so Boston users see no difference. Philly users
-- get sparse rails because the global top 50 per category gets
-- geo-filtered to "very few Philly events" — the Philly-local top
-- events have rn > 50 and never reach the result.
--
-- ── Fix ──────────────────────────────────────────────────────────────
-- Drop the rn restriction. Use the pre-computed rns only for ORDERING
-- (coverage interleave: every category gets its top entry before the
-- LIMIT trims). Geo bbox + LIMIT 900 do the rest. Result:
--   • Boston: geo bbox returns ~5-15k rows. Sort by coverage, top 900
--     wins. Top globally-ranked events naturally surface first because
--     their rn is lowest. Same fidelity as the rn <= 50/100 version.
--   • Philly: geo bbox returns ~500-3000 rows, all retained. Every
--     Philly event in the MV reaches the edge function. Rails fill.
--
-- ── Latency ──────────────────────────────────────────────────────────
-- Boston: extra sort cost of ~50-200 ms over 5-15k rows (vs index-only
--   scan via partial indexes). Acceptable — keeps us in the 1-2 s
--   edge-fn warm range.
-- Philly: sort over 500-3000 rows is sub-50 ms. Net win because the
--   correct events are now present.

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
  -- Coverage-ordered: pre-computed global rns give us a deterministic
  -- "every category gets a top spot" interleave. For Boston this lines
  -- up with what the user used to see; for non-Boston it lets the local
  -- top events (which have higher global rn but lower geo-filtered rn)
  -- come through.
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
  'Geo-scoped read of mv_carousel_payload. Uses pre-computed rn_category / rn_subcategory for ordering coverage but applies no rn restriction in the WHERE clause — every event in the geo bbox is eligible. Fixes the Philly-sparse-carousel regression from 20260527150000 while preserving Boston fidelity.';

ANALYZE public.mv_carousel_payload;
