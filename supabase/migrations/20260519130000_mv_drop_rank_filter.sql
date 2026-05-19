-- Latency sprint, post-week-1: rebuild mv_carousel_payload WITHOUT the
-- public_interest_rank > 10 filter so it can back the edge function's
-- fetchAllEvents() slow path (which has no rank filter today). The
-- original MV mirrored search_events_stratified's RPC filter, but the
-- edge fn never calls that RPC — its slow path is the 7×1000 paginated
-- scan in fetchAllEvents(). Aligning the MV with what the slow path
-- actually does lets us replace that scan with a single RPC call,
-- collapsing the cold-isolate carousel build from ~30 s to <500 ms.
--
-- The pg_cron job 'mv_carousel_payload_refresh' is keyed by name and
-- continues to refresh the rebuilt MV on its 5-minute schedule.
--
-- Row-count: removing the rank filter increases the MV from a few
-- thousand rows to ~all upcoming-90-days events. Indexes and CONCURRENT
-- refresh keep query + refresh cost bounded.

-- DROP CASCADE removes mv_events_for_geo() too; we recreate it below
-- pointing at the rebuilt MV.
DROP MATERIALIZED VIEW IF EXISTS public.mv_carousel_payload CASCADE;

CREATE MATERIALIZED VIEW public.mv_carousel_payload AS
SELECT
  -- partition keys for ranking
  e.category,
  e.subcategory,
  -- rank_score still computed (used for ORDER BY in mv_events_for_geo)
  -- but no longer used as a WHERE filter — fetchAllEvents needs the
  -- full unfiltered set so buildCarousels() can apply category-specific
  -- filters in JS.
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
  -- denormalized venue fields
  v.lat            AS canonical_venue_lat,
  v.lng            AS canonical_venue_lng,
  v.photo_url      AS venue_photo_url,
  v.google_rating  AS venue_google_rating,
  v.description    AS venue_description
FROM public.events e
LEFT JOIN LATERAL (
  -- One best-ranked active venue per event. Multiple venues can share
  -- a lowercased name; LATERAL picks deterministically so the unique
  -- index on (id) holds.
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
  AND e.start_at <  now() + interval '90 days';
  -- NOTE: no rank filter — matches fetchAllEvents() semantics so the
  -- edge function can swap in the MV without changing CX (same row set,
  -- same card fields, same relevancy because buildCarousels() does its
  -- own per-carousel rank logic in JS).

CREATE UNIQUE INDEX mv_carousel_payload_unique_idx
  ON public.mv_carousel_payload (id);

CREATE INDEX mv_carousel_payload_category_rn_idx
  ON public.mv_carousel_payload (category, rn_category)
  WHERE rn_category <= 50;

CREATE INDEX mv_carousel_payload_subcategory_rn_idx
  ON public.mv_carousel_payload (subcategory, rn_subcategory)
  WHERE subcategory IS NOT NULL AND rn_subcategory <= 5;

CREATE INDEX mv_carousel_payload_geo_canonical_idx
  ON public.mv_carousel_payload (canonical_venue_lat, canonical_venue_lng)
  WHERE canonical_venue_lat IS NOT NULL;

CREATE INDEX mv_carousel_payload_geo_event_idx
  ON public.mv_carousel_payload (venue_lat, venue_lng)
  WHERE venue_lat IS NOT NULL;

-- ──────────────────────────────────────────────────────────────────────
-- mv_events_for_geo — recreated to point at the rebuilt MV.
-- Same shape as before; consumers (carousels edge fn fetchAllEvents)
-- don't need to change their RPC call signature.
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

GRANT EXECUTE ON FUNCTION public.mv_events_for_geo TO anon, authenticated, service_role;
GRANT SELECT  ON public.mv_carousel_payload TO anon, authenticated, service_role;

-- Initial sync refresh so the MV has data the moment this migration
-- finishes (rather than waiting for the next pg_cron tick).
REFRESH MATERIALIZED VIEW public.mv_carousel_payload;

COMMENT ON MATERIALIZED VIEW public.mv_carousel_payload IS
  'Pre-computed events feed for the carousels edge function fast path. Refreshed every 5 min by pg_cron job mv_carousel_payload_refresh (CONCURRENTLY). NO rank filter — matches fetchAllEvents() semantics so the slow path can swap in the MV with byte-identical CX.';
