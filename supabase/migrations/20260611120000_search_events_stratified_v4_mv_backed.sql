-- search_events_stratified v4 — read from mv_carousel_payload instead of the
-- live events⨝venues recompute.
--
-- WHY
-- ---
-- v3 (20260507120000) does, on EVERY call under live concurrency:
--   FROM events e LEFT JOIN venues v ON lower(v.name) = lower(e.venue_name)
--   + live Haversine + 3 window lanes (breadth/niche/keyword) + re-join.
-- Load test (2026-06-11) showed this cliffs at ~100 concurrent VU: p95 2.3s,
-- max 7.6s — right on the 8s statement-timeout edge. The home feed already
-- moved off this pattern to the pre-computed MV `mv_carousel_payload`
-- (20260519120200, pg_cron-refreshed every 5 min) via `mv_events_for_geo()`.
-- This RPC — the path Bo chat + plan chat use (searchEventsBroad) — was never
-- migrated. v4 points it at the same MV.
--
-- WHAT CHANGES (and what deliberately does NOT)
-- ---------------------------------------------
--  * Source: `mv_carousel_payload m` replaces `events e LEFT JOIN venues v`.
--    The MV already materialized that exact join (highest-ranked active venue
--    per lowercased name, via LATERAL) so we drop the per-call join entirely.
--  * The `COALESCE(v.public_interest_rank,11) > 10` exclusion is DROPPED here
--    because the MV already applied it at materialization time — same rows.
--  * Coordinate preference (venues.lat first, event-stub coords fallback — the
--    v3 fix that stopped distant municipal_boston stubs leaking) is preserved
--    verbatim using m.canonical_venue_lat/lng (= v.lat/lng) then m.venue_lat/lng.
--  * The three lanes + combined de-dupe are RECOMPUTED per query over the
--    geo+date-filtered subset (NOT the MV's global rn_category/rn_subcategory,
--    which are metro-wide and not scoped to this search). Ranking output is
--    therefore identical to v3.
--  * Signature is UNCHANGED and return type stays `SETOF events` — the final
--    JOIN back to `events` returns full rows, so every live client version sees
--    the identical response shape. Additive/backward-compatible (CLAUDE.md rule).
--
-- VALIDATE ON DEV BEFORE PROMOTING (can't EXPLAIN from here — anon key only):
--  1. EXPLAIN ANALYZE at p_user_lat/lng = Boston, default args — confirm it hits
--     mv_carousel_payload_geo_canonical_idx and drops the seq-scan/join cost.
--  2. Re-run the rpc-search-stratified k6 scenario on dev; `diff.sh` before/after.
--     SHIP GATE: low-load p50/p95 must be >= as fast AND the 100-VU cliff gone.
--  3. Result-parity spot check: same (lat,lng,date,keyword) inputs against v3
--     vs v4 should return the same event id set. Watch two known semantic edges:
--       - MV floors start_at at now(); v3 honored p_start_at even if < now().
--         Bo always searches now..+90d so this is a no-op in practice — confirm.
--       - MV horizon is now()+90d; a caller passing p_end_at beyond that would
--         see fewer rows. Bo's 90-day calendar keeps callers inside the window.
--
-- ROLLBACK (if v4 misbehaves in prod): re-apply the v3 body from
-- 20260507120000_search_events_stratified_v3_venue_coords.sql via
-- CREATE OR REPLACE. It's a pure function swap — no schema/data change, no
-- client redeploy, effective in <1 min.
--
-- DEPENDENCY: this RPC now reads `mv_carousel_payload`, kept fresh by the
-- pg_cron job `mv_carousel_payload_refresh` (*/5 min, from 20260519120200).
-- Bo search is therefore ≤5-min-stale instead of live. Registered in
-- skills-system registries/crons.md. The MV (and the cron) MUST exist on the
-- target DB before this migration applies, or the function errors on every call.

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
      m.id,
      m.category,
      m.subcategory,
      m.title,
      m.venue_name,
      m.start_at,
      m.rank_score,
      -- Prefer canonical venues-table coords (authoritative); fall back to
      -- event-row coords only when no venue match. Identical to v3.
      CASE
        WHEN m.canonical_venue_lat IS NOT NULL AND m.canonical_venue_lng IS NOT NULL THEN
          sqrt(power((m.canonical_venue_lat - p_user_lat) * 69.0, 2)
             + power((m.canonical_venue_lng - p_user_lng) * 50.0, 2))
        WHEN m.venue_lat IS NOT NULL AND m.venue_lng IS NOT NULL THEN
          sqrt(power((m.venue_lat - p_user_lat) * 69.0, 2)
             + power((m.venue_lng - p_user_lng) * 50.0, 2))
        ELSE NULL
      END AS dist_mi
    FROM public.mv_carousel_payload m
    CROSS JOIN bbox
    WHERE m.start_at >= p_start_at
      AND m.start_at < p_end_at
      -- Prefilter: keep rows whose BEST available coord (canonical first,
      -- else event coord) falls in the bbox. Precise dist check culls in `win`.
      AND (
        (m.canonical_venue_lat IS NOT NULL
          AND m.canonical_venue_lat BETWEEN p_user_lat - bbox.dlat AND p_user_lat + bbox.dlat
          AND m.canonical_venue_lng BETWEEN p_user_lng - bbox.dlng AND p_user_lng + bbox.dlng)
        OR
        (m.canonical_venue_lat IS NULL AND (
          m.venue_lat IS NULL
          OR (
            m.venue_lat BETWEEN p_user_lat - bbox.dlat AND p_user_lat + bbox.dlat
            AND m.venue_lng BETWEEN p_user_lng - bbox.dlng AND p_user_lng + bbox.dlng
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
  'Three-lane (breadth + niche + keyword) event search inside a geo + date window. v4 (2026-06-11): reads the pre-computed mv_carousel_payload MV instead of the live events⨝venues recompute, eliminating the ~100-VU statement-timeout cliff. Same signature + SETOF events output as v3; coordinate-preference and ranking semantics unchanged. MV is pg_cron-refreshed every 5 min.';

GRANT EXECUTE ON FUNCTION search_events_stratified TO anon, authenticated;
