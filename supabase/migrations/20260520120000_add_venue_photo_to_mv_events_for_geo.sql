-- ──────────────────────────────────────────────────────────────────────
-- Add venue_photo_url to mv_events_for_geo() return shape.
--
-- The MV (mv_carousel_payload) already stores v.photo_url AS venue_photo_url
-- (see 20260519120200_mv_carousel_payload.sql:85) but the geo RPC's
-- RETURNS TABLE never exposed it. Without it the carousels edge function
-- (and its precomputed cache payloads) can only collapse the value into
-- image_url via cleanEvents(); if that collapse misses an event (venue not
-- in cache, name mismatch, photo_url null), the client has no second-tier
-- fallback and renders a blank category gradient.
--
-- This change makes venue_photo_url a first-class column so the client can
-- use it as a last-resort fallback before the gradient on both the
-- carousel card and the event detail hero.
--
-- Backwards-compat: supabase-js callers (carousels/index.ts) key by name,
-- so adding a column is safe. No other caller exists.
--
-- DROP+CREATE rather than CREATE OR REPLACE because Postgres rejects
-- return-type changes to existing functions (SQLSTATE 42P13: "cannot
-- change return type of existing function"). Within a migration
-- transaction the drop+recreate is atomic — no window where callers
-- see a missing function.
-- ──────────────────────────────────────────────────────────────────────
DROP FUNCTION IF EXISTS public.mv_events_for_geo(double precision, double precision, double precision, int);

CREATE FUNCTION public.mv_events_for_geo(
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
    m.venue_description,
    m.venue_photo_url
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

GRANT EXECUTE ON FUNCTION public.mv_events_for_geo TO anon, authenticated, service_role;
