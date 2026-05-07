-- Performance fix for search_events_stratified.
--
-- The 2026-04-30 stratified RPC was hitting the 8s statement timeout when
-- a keyword was supplied (e.g. Bo's `category=arts` collapses into the
-- keyword lane). Three costs were compounding:
--
--   1. The keyword CTE ran `ILIKE '%kw%'` on `description` — long
--      unindexed text. Sequential scan even within the geo window.
--   2. The `scoped` CTE LEFT JOINs `venues` on `lower(name) = lower(venue_name)`
--      *before* any geo filter, fanning out across every active event in
--      the 90-day window globally.
--   3. The final ORDER BY was a CORRELATED SUBQUERY against `venues`,
--      executed once per output row — re-doing work `scoped` already did.
--
-- Fix:
--   - Drop `description` from the keyword ILIKE; keep title / venue_name /
--     subcategory / category. (description is rarely the right match anyway,
--     and the breadth + niche lanes already cover thematic queries.)
--   - Apply a coarse bounding-box prefilter on `events.venue_lat/venue_lng`
--     inside `scoped` so the venues JOIN only sees geographically-relevant
--     rows. Events with null coords still get joined (we need venue lat/lng
--     to compute distance for them) but that's a small minority.
--   - Carry `rank_score` through the `combined` CTE and ORDER BY on it
--     directly — no second venues lookup.

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
    -- Coarse bounding box in degrees. 1 deg lat ≈ 69 mi, 1 deg lng ≈ 50 mi
    -- at mid-Atlantic latitudes — same constants as the distance calc.
    -- Inflate by 1.2x so the bbox never excludes a row that the precise
    -- distance check below would have kept.
    SELECT
      (p_radius_miles * 1.2) / 69.0 AS dlat,
      (p_radius_miles * 1.2) / 50.0 AS dlng
  ),
  scoped AS (
    SELECT
      e.*,
      COALESCE(v.public_interest_rank, 0) AS rank_score,
      CASE
        WHEN e.venue_lat IS NOT NULL AND e.venue_lng IS NOT NULL THEN
          sqrt(power((e.venue_lat - p_user_lat) * 69.0, 2)
             + power((e.venue_lng - p_user_lng) * 50.0, 2))
        WHEN v.lat IS NOT NULL AND v.lng IS NOT NULL THEN
          sqrt(power((v.lat - p_user_lat) * 69.0, 2)
             + power((v.lng - p_user_lng) * 50.0, 2))
        ELSE NULL
      END AS dist_mi
    FROM events e
    LEFT JOIN venues v ON lower(v.name) = lower(e.venue_name)
    CROSS JOIN bbox
    WHERE e.is_active = true
      AND e.start_at >= p_start_at
      AND e.start_at < p_end_at
      AND COALESCE(v.public_interest_rank, 11) > 10
      -- Coarse bbox prefilter: keep rows whose event-level coords fall in
      -- the box, OR rows with null event coords (we'll resolve those via
      -- venues.lat/lng below). This is the big win — without it the JOIN
      -- fans across every active event in the country.
      AND (
        e.venue_lat IS NULL
        OR (
          e.venue_lat BETWEEN p_user_lat - bbox.dlat AND p_user_lat + bbox.dlat
          AND e.venue_lng BETWEEN p_user_lng - bbox.dlng AND p_user_lng + bbox.dlng
        )
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
  'Three-lane (breadth + niche + keyword) event search inside a geo + date window. v2: bbox prefilter on events before venues JOIN, drops description from keyword ILIKE, carries rank_score through to final ORDER BY (was a correlated subquery in v1, would time out under load).';

GRANT EXECUTE ON FUNCTION search_events_stratified TO anon, authenticated;
