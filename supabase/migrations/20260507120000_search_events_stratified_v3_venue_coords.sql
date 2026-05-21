-- Fix #2: distant events leaking into geo-bounded searches.
--
-- Symptom: a search for "things to do May 15 near Waltham" returned events
-- at MASS MoCA (North Adams, MA — ~120 miles away) and the Norman Rockwell
-- Museum (Stockbridge, MA — ~110 miles away).
--
-- Root cause: those events were ingested via the `municipal_boston` feed,
-- which stores a Boston-centroid stub (42.3601, -71.0589) in
-- `events.venue_lat`/`venue_lng` regardless of the venue's real location.
-- The previous RPC computed distance from `e.venue_lat` first and only fell
-- back to `v.lat` when event coords were null — so the stub coords passed
-- both the bbox prefilter AND the precise distance check.
--
-- Fix: when a `venues` row exists for the event's `venue_name`, prefer the
-- venues-table coordinates over the events-table stub. The venues table is
-- authoritative (sourced from Google Places + manual curation); event-row
-- coords are best-effort from the ingest source. Events whose `venue_name`
-- doesn't match a venues row still fall back to event coords (no regression
-- for ticketmaster / eventbrite rows whose venue isn't in our DB).
--
-- Bbox prefilter: same priority — accept the row if the *better* of the two
-- coord sources falls in the box. We can't easily index on a coalesce, so
-- the prefilter accepts when EITHER coord source is in the box; the precise
-- distance check below culls anything where the venues coord (preferred)
-- puts it outside the radius.

CREATE OR REPLACE FUNCTION search_events_stratified(
  p_user_lat double precision,
  p_user_lng double precision,
  p_radius_miles double precision DEFAULT 20,
  p_start_at timestamptz DEFAULT now(),
  p_end_at timestamptz DEFAULT (now() + interval '90 days'),
  p_keyword text DEFAULT NULL,
  p_breadth_per_category int DEFAULT 25,
  p_max_results int DEFAULT 300
) RETURNS SETOF events
LANGUAGE sql STABLE AS $$
  WITH bbox AS (
    SELECT
      (p_radius_miles * 1.2) / 69.0 AS dlat,
      (p_radius_miles * 1.2) / 50.0 AS dlng
  ),
  scoped AS (
    SELECT
      e.*,
      COALESCE(v.public_interest_rank, 0) AS rank_score,
      -- Prefer venues-table coords when present (authoritative).
      -- Fall back to event-row coords only when no venue match.
      CASE
        WHEN v.lat IS NOT NULL AND v.lng IS NOT NULL THEN
          sqrt(power((v.lat - p_user_lat) * 69.0, 2)
             + power((v.lng - p_user_lng) * 50.0, 2))
        WHEN e.venue_lat IS NOT NULL AND e.venue_lng IS NOT NULL THEN
          sqrt(power((e.venue_lat - p_user_lat) * 69.0, 2)
             + power((e.venue_lng - p_user_lng) * 50.0, 2))
        ELSE NULL
      END AS dist_mi
    FROM events e
    LEFT JOIN venues v ON lower(v.name) = lower(e.venue_name)
    CROSS JOIN bbox
    WHERE e.is_active = true
      AND e.start_at >= p_start_at
      AND e.start_at < p_end_at
      AND COALESCE(v.public_interest_rank, 11) > 10
      -- Prefilter: keep rows whose BEST available coord (venues.lat first,
      -- else event coord) falls in the bbox. v.lat is the source of truth
      -- when present, so an event with stub event-coords inside the box but
      -- venue-coords WAY outside still passes the prefilter here — the
      -- precise distance check in `win` below culls it out.
      AND (
        (v.lat IS NOT NULL AND v.lat BETWEEN p_user_lat - bbox.dlat AND p_user_lat + bbox.dlat
                          AND v.lng BETWEEN p_user_lng - bbox.dlng AND p_user_lng + bbox.dlng)
        OR
        (v.lat IS NULL AND (
          e.venue_lat IS NULL
          OR (
            e.venue_lat BETWEEN p_user_lat - bbox.dlat AND p_user_lat + bbox.dlat
            AND e.venue_lng BETWEEN p_user_lng - bbox.dlng AND p_user_lng + bbox.dlng
          )
        ))
      )
  ),
  win AS (
    SELECT * FROM scoped
    WHERE dist_mi IS NULL OR dist_mi <= p_radius_miles
  ),
  breadth AS (
    SELECT id, rank_score, ROW_NUMBER() OVER (
      PARTITION BY category
      ORDER BY rank_score DESC, start_at ASC
    ) AS rn
    FROM win
  ),
  niche AS (
    SELECT id, rank_score, ROW_NUMBER() OVER (
      PARTITION BY subcategory
      ORDER BY rank_score DESC, start_at ASC
    ) AS rn
    FROM win
    WHERE subcategory IS NOT NULL
  ),
  keyword AS (
    SELECT id, rank_score FROM win
    WHERE p_keyword IS NOT NULL AND p_keyword <> ''
      AND (
        title       ILIKE '%' || p_keyword || '%'
        OR venue_name  ILIKE '%' || p_keyword || '%'
        OR subcategory ILIKE '%' || p_keyword || '%'
        OR category    ILIKE '%' || p_keyword || '%'
      )
  ),
  combined AS (
    SELECT id, MAX(rank_score) AS rank_score FROM (
      SELECT id, rank_score FROM breadth WHERE rn <= p_breadth_per_category
      UNION ALL
      SELECT id, rank_score FROM niche WHERE rn = 1
      UNION ALL
      SELECT id, rank_score FROM keyword
    ) u
    GROUP BY id
  )
  SELECT e.*
  FROM events e
  JOIN combined c ON c.id = e.id
  ORDER BY c.rank_score DESC, e.start_at ASC
  LIMIT p_max_results;
$$;

COMMENT ON FUNCTION search_events_stratified IS
  'Three-lane (breadth + niche + keyword) event search inside a geo + date window. v3: prefers venues.lat/lng over events.venue_lat/lng so municipal_boston-stub coords no longer leak distant events (MASS MoCA, Norman Rockwell, etc.) into geo-bounded searches.';

GRANT EXECUTE ON FUNCTION search_events_stratified TO anon, authenticated;
