-- Three-lane stratified event search.
--
-- Replaces the old single-WHERE search with a CTE that gives Bo:
--   1. Breadth lane — top N per primary `category`, so dense markets
--      (Boston Saturday) don't let one popular axis drown the others.
--   2. Niche lane   — top 1 per `subcategory`, so long-tail intents
--      (sound bath, drag brunch, swing dance, zine fair) always surface.
--   3. Keyword lane — anything matching the user's literal hint
--      (title / venue / description / category / subcategory) bypasses
--      both quotas; if you name a venue, you get its events even if
--      they would have been culled by rank.
--
-- All three lanes are scoped inside the same geo + date window so a
-- "Dennis" search never leaks Boston events.

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
  WITH scoped AS (
    SELECT
      e.*,
      COALESCE(v.public_interest_rank, 0) AS rank_score,
      -- Crude planar distance in miles. 69 mi per deg lat, ~50 per deg
      -- lng at mid-Atlantic latitudes; close enough at 20-mile scope.
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
    WHERE e.is_active = true
      AND e.start_at >= p_start_at
      AND e.start_at < p_end_at
      -- Global quality floor: drop events at venues with rank <= 10
      -- (community centers, K-12 fields, junk). Same gate the carousel
      -- + Explore surfaces use.
      AND COALESCE(v.public_interest_rank, 11) > 10
  ),
  win AS (
    SELECT * FROM scoped
    WHERE dist_mi IS NULL OR dist_mi <= p_radius_miles
  ),
  breadth AS (
    SELECT id, ROW_NUMBER() OVER (
      PARTITION BY category
      ORDER BY rank_score DESC, start_at ASC
    ) AS rn
    FROM win
  ),
  niche AS (
    SELECT id, ROW_NUMBER() OVER (
      PARTITION BY subcategory
      ORDER BY rank_score DESC, start_at ASC
    ) AS rn
    FROM win
    WHERE subcategory IS NOT NULL
  ),
  keyword AS (
    SELECT id FROM win
    WHERE p_keyword IS NOT NULL AND p_keyword <> ''
      AND (
        title       ILIKE '%' || p_keyword || '%'
        OR venue_name  ILIKE '%' || p_keyword || '%'
        OR description ILIKE '%' || p_keyword || '%'
        OR subcategory ILIKE '%' || p_keyword || '%'
        OR category    ILIKE '%' || p_keyword || '%'
      )
  ),
  combined AS (
    SELECT id FROM breadth WHERE rn <= p_breadth_per_category
    UNION
    SELECT id FROM niche   WHERE rn = 1
    UNION
    SELECT id FROM keyword
  )
  SELECT e.*
  FROM events e
  JOIN combined c ON c.id = e.id
  ORDER BY
    COALESCE((SELECT public_interest_rank FROM venues
              WHERE lower(name) = lower(e.venue_name) LIMIT 1), 0) DESC,
    e.start_at ASC
  LIMIT p_max_results;
$$;

COMMENT ON FUNCTION search_events_stratified IS
  'Three-lane (breadth + niche + keyword) event search inside a geo + date window. Used by the Yocal/Bo chat to give the model a diverse cross-section of upcoming events without forcing Bo to fan out across multiple narrow searches.';

-- Allow the anon + authenticated roles to call it (RLS on events still applies).
GRANT EXECUTE ON FUNCTION search_events_stratified TO anon, authenticated;
