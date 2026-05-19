-- Latency sprint, Day 1 (CTO hybrid #1 — emergency stabilization).
--
-- Fixes the documented 10s subcategory-tab bug. Root cause per
-- project_local_session_2026_05_17_handoff.md: planner stats on `events`
-- and `venues` are stale from the 2,517-row collapse + ~150k image-backfill
-- writes, so search_events_stratified picks a bad plan and trips the
-- Postgres 8s statement_timeout. That timeout in turn poisons the
-- carousel_cache (the edge fn's RPC fallback returns nothing, the cron
-- writes empty rails). Refreshing stats unblocks both.
--
-- This migration is idempotent. Safe to re-run.

SET statement_timeout = '120s';

-- 1) Force a fresh ANALYZE across the read-hot tables. VACUUM cannot run
-- inside a migration transaction (SQLSTATE 25001), so we use plain
-- ANALYZE here — which is the actual planner-stats fix we need. Dead
-- tuples will be reclaimed by autovacuum with the tightened thresholds
-- set in step 2 below.
ANALYZE public.events;
ANALYZE public.venues;
ANALYZE public.community_interactions;
ANALYZE public.plans;
ANALYZE public.plan_attendees;

-- 2) Tighten autovacuum on the highest-churn tables. Defaults trigger an
-- analyze only after 10% of the table changes; events/community_interactions
-- churn faster than that and the planner was stale by the time the next
-- autovacuum ran.
ALTER TABLE public.events  SET (
  autovacuum_analyze_scale_factor = 0.02,
  autovacuum_vacuum_scale_factor  = 0.05,
  autovacuum_analyze_threshold    = 500,
  autovacuum_vacuum_threshold     = 500
);
ALTER TABLE public.venues SET (
  autovacuum_analyze_scale_factor = 0.05
);
ALTER TABLE public.community_interactions SET (
  autovacuum_vacuum_scale_factor  = 0.10,
  autovacuum_analyze_scale_factor = 0.05
);

-- 3) Raise per-column statistics where the planner is choosing badly. These
-- are the columns search_events_stratified partitions on (PARTITION BY
-- category / subcategory) and the rank_score join key on venues.
ALTER TABLE public.events ALTER COLUMN category    SET STATISTICS 1000;
ALTER TABLE public.events ALTER COLUMN subcategory SET STATISTICS 1000;
ALTER TABLE public.events ALTER COLUMN start_at    SET STATISTICS 1000;
ALTER TABLE public.events ALTER COLUMN venue_name  SET STATISTICS 1000;
ALTER TABLE public.venues ALTER COLUMN public_interest_rank SET STATISTICS 1000;

-- Re-analyze so the higher-stat columns actually populate.
ANALYZE public.events;
ANALYZE public.venues;

-- 4) Per-role statement_timeout. The current 8s default is inherited by
-- every PostgREST call and every edge-fn RPC call. Bound the read path
-- aggressively so a slow RPC fails fast and the edge fn falls through to
-- carousel_cache rather than wedging.
--
-- - authenticator: PostgREST executes RPCs and table reads as this role
--   (default Supabase config). 1500ms is comfortably above expected p95
--   post-index work but well under the 8s cliff.
-- - anon: same path for unauthenticated callers; tighter to discourage
--   pathological queries.
-- - service_role: used by edge functions, cron, and crawl scripts that
--   legitimately need long-running work (ANALYZE, REFRESH MATERIALIZED
--   VIEW, bulk upserts). Keep generous.
ALTER ROLE authenticator SET statement_timeout = '1500ms';
ALTER ROLE anon          SET statement_timeout = '1000ms';
ALTER ROLE service_role  SET statement_timeout = '30s';

-- Crawl scripts and refresh_carousel_cache.js connect as service_role and
-- need to override even the 30s when running ANALYZE / heavy backfills.
-- Those scripts can SET LOCAL statement_timeout = '0' at the top of their
-- transaction. Document this in scripts/README.md if not already.

COMMENT ON ROLE authenticator IS
  'Latency sprint 2026-05-19: statement_timeout=1500ms. Bounds RPC + REST reads under the 8s default cliff so the edge fn can fall back to carousel_cache instead of wedging.';
