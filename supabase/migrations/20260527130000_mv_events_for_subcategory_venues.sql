-- Surface venue diversity on subcategory tabs.
--
-- ── Problem ──────────────────────────────────────────────────────────
-- mv_events_for_geo() returns up to 900 events stratified by category
-- and subcategory and ordered by rank_score DESC. For dense subcategories
-- like for-kids in metro Boston, the top-ranked venue (Boston Public
-- Library, rank ~95) crowds the top of the for-kids ordering — its 14
-- events sit at rn_sub_local 1-14 and consume most of the 100-event
-- per-subcategory cap.
--
-- Lower-rank branch libraries that ALSO host kids events (Brighton
-- Branch, Hummingbird Books, Coolidge Corner, etc., all rank 30-60)
-- get rn_sub_local 15+ and frequently trim off entirely by the time
-- the LIMIT 900 + interleave-ordered output lands.
--
-- Net effect: the carousels edge function only sees for-kids events
-- from 2-3 venues, so buildDynamicSubcarousels can only render 2-3
-- "At <venue>" rails — no matter how generous the top-N venue cap is
-- inside the edge function.
--
-- ── Fix ──────────────────────────────────────────────────────────────
-- This RPC returns, for a specific subcategory, the top N venues' top M
-- events each, geo-scoped. It's run in PARALLEL with mv_events_for_geo
-- on subcategory-tab requests; the edge function unions the results,
-- dedupes by id, and passes them to buildDynamicSubcarousels.
--
-- Latency: parallel fetch, no added round-trip. Query plan uses the
-- existing geo + subcategory partial indexes on mv_carousel_payload.
--
-- Output cap: top 12 venues × top 6 events each = max 72 rows. Well
-- under PostgREST's 1000-row wire cap.

DROP FUNCTION IF EXISTS public.mv_events_for_subcategory_venues(double precision, double precision, double precision, text, int, int);

CREATE FUNCTION public.mv_events_for_subcategory_venues(
  p_lat double precision,
  p_lng double precision,
  p_radius_miles double precision,
  p_subcategory text,
  p_top_venues int DEFAULT 12,
  p_events_per_venue int DEFAULT 6
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
  -- All future events in the geo bbox for the requested subcategory.
  -- Includes recurring + always_on (those are exactly the events that
  -- make per-venue rails feel rich — Story Time, Open Mic Night, etc.).
  -- Excludes curated since those belong on the Experiences tab.
  scoped AS (
    SELECT
      m.*,
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
    WHERE m.subcategory = p_subcategory
      AND m.source IS DISTINCT FROM 'curated'
      AND m.start_at >= now()
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
  -- Rank venues by event count (we want the venues with the most
  -- subcategory programming first — those produce the densest rails).
  venue_counts AS (
    SELECT
      lower(venue_name) AS venue_key,
      count(*) AS n_events,
      max(rank_score) AS top_rank
    FROM scoped
    WHERE venue_name IS NOT NULL
    GROUP BY lower(venue_name)
    HAVING count(*) >= 4    -- matches the addCarousel render threshold in the edge fn
  ),
  top_venues AS (
    SELECT venue_key
    FROM venue_counts
    ORDER BY n_events DESC, top_rank DESC
    LIMIT p_top_venues
  ),
  -- For each top venue, take its top events by rank + soonest start.
  ranked_events AS (
    SELECT
      s.*,
      ROW_NUMBER() OVER (
        PARTITION BY lower(s.venue_name)
        ORDER BY s.rank_score DESC, s.start_at ASC
      ) AS rn_in_venue
    FROM scoped s
    WHERE lower(s.venue_name) IN (SELECT venue_key FROM top_venues)
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
  FROM ranked_events
  WHERE rn_in_venue <= p_events_per_venue
  ORDER BY venue_name, rn_in_venue;
$$;

GRANT EXECUTE ON FUNCTION public.mv_events_for_subcategory_venues TO anon, authenticated, service_role;

COMMENT ON FUNCTION public.mv_events_for_subcategory_venues IS
  'Geo-scoped, venue-diverse fetch for a single subcategory. Returns top N venues'' top M events each (default 12 venues × 6 events = 72 rows max). Used by the carousels edge function on subcategory-tab requests in parallel with mv_events_for_geo to surface "At <venue>" rails that the rank-stratified main RPC starves out for high-density subcategories like for-kids.';
