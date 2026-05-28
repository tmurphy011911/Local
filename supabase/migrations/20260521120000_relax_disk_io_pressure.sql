-- Disk-IO relief after the 2026-05-19 latency sprint pushed Nano-tier
-- IO usage from ~5%/day to ~65%/day. Two surgical changes:
--
-- 1. mv_carousel_payload refresh cadence: 5 min → 30 min. The MV is the
--    fallback for the carousels edge fn's slow path; carousel_cache
--    (refreshed every 10 min by GitHub Actions) is the hot path. 30 min
--    staleness on the fallback is invisible to users because
--    mv_events_for_geo applies WHERE start_at >= now() at query time,
--    so past events are culled even if the MV row is stale.
--
-- 2. Autovacuum thresholds: restore Postgres defaults (10% analyze /
--    20% vacuum). Day 1's 2%/5% tuning was an emergency response to
--    6-months-stale planner stats; now that stats are healthy and the
--    crawler runs at a steady cadence, defaults are sufficient.
--
-- Per-column statistics (1000) stay — those don't drive IO, just
-- planner accuracy on the partition keys we care about.
--
-- KEEP IN MIND: if you run a big backfill (image backfill resume,
-- bulk crawler ingest, etc.), append `ANALYZE events; ANALYZE venues;`
-- to the backfill script. With default autovacuum, stats can lag
-- ~10% behind row changes, which is enough to bring back the 10s
-- subcategory bug on very heavy ingest days.

-- ──────────────────────────────────────────────────────────────────────
-- 1) Reschedule mv_carousel_payload_refresh from 5 min → 30 min
-- ──────────────────────────────────────────────────────────────────────
DO $$
BEGIN
  PERFORM cron.unschedule(jobid)
    FROM cron.job
    WHERE jobname = 'mv_carousel_payload_refresh';
EXCEPTION WHEN OTHERS THEN
  NULL;  -- not yet scheduled; harmless
END $$;

SELECT cron.schedule(
  'mv_carousel_payload_refresh',
  '*/30 * * * *',
  $$REFRESH MATERIALIZED VIEW CONCURRENTLY public.mv_carousel_payload;$$
);

-- ──────────────────────────────────────────────────────────────────────
-- 2) Reset autovacuum thresholds to Postgres defaults
-- ──────────────────────────────────────────────────────────────────────
ALTER TABLE public.events RESET (
  autovacuum_analyze_scale_factor,
  autovacuum_vacuum_scale_factor,
  autovacuum_analyze_threshold,
  autovacuum_vacuum_threshold
);

ALTER TABLE public.venues RESET (
  autovacuum_analyze_scale_factor
);

ALTER TABLE public.community_interactions RESET (
  autovacuum_vacuum_scale_factor,
  autovacuum_analyze_scale_factor
);

COMMENT ON ROLE authenticator IS
  'Latency sprint 2026-05-19: statement_timeout=1500ms. Bounds RPC + REST reads. Autovacuum tuning relaxed 2026-05-21 after Nano-tier disk IO alerts; re-tighten if heavy backfill returns the 10s subcategory bug.';
