-- Teach mv_carousel_payload + mv_events_for_geo to prefer event-level
-- coords/city (events.event_lat / event_lng / event_city / event_state)
-- over the host-venue coords when they are set.
--
-- The host-venue columns (events.venue_*) often describe an organization's
-- HQ rather than the physical event site — e.g. a Williamstown nature
-- photography talk lists Mass Audubon (HQ near Boston) as the venue.
-- Without preferring event_lat, distance for that event is computed against
-- Mass Audubon's HQ (~9.9 mi from Waltham) and the For You chip shows
-- "Boston" even though the event is in the Berkshires.
--
-- Distance priority (and bbox prefilter) becomes:
--   event_lat → canonical_venue_lat → venue_lat → NULL
-- City returned to the client adds event_city alongside venue_city; clients
-- prefer event_city when non-null.
--
-- Backwards-compat: existing columns retained, two columns added to the RPC
-- return shape (event_city, event_state). All callers key by column name.

-- ──────────────────────────────────────────────────────────────────────
-- 1) Rebuild mv_carousel_payload to surface event_lat / event_lng /
--    event_city / event_state from the events table.
--
--    DROP + CREATE because adding columns to an MV isn't supported via
--    ALTER. The cron refresh and reader paths re-attach automatically;
--    the synchronous REFRESH at the bottom of this transaction makes
--    sure no reader sees an empty MV after the migration commits.
-- ──────────────────────────────────────────────────────────────────────
DROP MATERIALIZED VIEW IF EXISTS public.mv_carousel_payload;

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
  -- NEW: event-level location (overrides venue_* when set)
  e.event_lat,
  e.event_lng,
  e.event_city,
  e.event_state,
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
  AND COALESCE(v.public_interest_rank, 11) > 10;

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

-- NEW: geo index for event-level coords. Used by the bbox prefilter when
-- event_lat is set (which is the new priority case).
CREATE INDEX mv_carousel_payload_geo_event_loc_idx
  ON public.mv_carousel_payload (event_lat, event_lng)
  WHERE event_lat IS NOT NULL;

COMMENT ON MATERIALIZED VIEW public.mv_carousel_payload IS
  'Pre-computed carousel feed. Refreshed CONCURRENTLY every 5 min by pg_cron job ''mv_carousel_payload_refresh''. Read path: edge function falls back to this MV instead of search_events_stratified RPC when carousel_cache is cold. Card fields are byte-identical to what carousels edge fn returns, plus event_lat/lng/city/state which override venue_* when set.';

REFRESH MATERIALIZED VIEW public.mv_carousel_payload;

-- ──────────────────────────────────────────────────────────────────────
-- 2) Rebuild mv_events_for_geo with the new distance priority and add
--    event_city / event_state to the return shape.
--
--    DROP+CREATE because Postgres rejects return-type changes to an
--    existing function (SQLSTATE 42P13).
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
    -- Distance preference: event_lat (real event site) →
    --   canonical_venue_lat (joined venues table) →
    --   venue_lat (events table snapshot) → NULL.
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
    AND (
      -- Same priority as distance: if event_lat is set, the bbox check
      -- uses event_lat exclusively. Otherwise fall back to canonical
      -- venue coords, then event-row venue coords, then unconditional
      -- pass (no coords known anywhere).
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
  ORDER BY m.rank_score DESC, m.start_at ASC
  LIMIT p_max_results;
$$;

GRANT EXECUTE ON FUNCTION public.mv_events_for_geo TO anon, authenticated, service_role;

COMMENT ON FUNCTION public.mv_events_for_geo IS
  'Geo-filtered read of mv_carousel_payload. Distance + bbox prefer event_lat (the real event location) over canonical_venue_lat (joined venues row) over venue_lat (events row snapshot). Fixes the case where an event hosted by an organization headquartered far from the actual event site was scored against the org HQ instead of the event location.';

-- The mv_carousel_payload_refresh pg_cron job from 20260519120200 still
-- works against the rebuilt MV — cron looks up by jobname, the function
-- body REFRESHes whatever MV currently has that name. No cron change needed.
