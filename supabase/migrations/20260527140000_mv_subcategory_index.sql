-- mv_events_for_subcategory_venues' WHERE clause filters by
-- (subcategory = $1 AND start_at >= now() AND geo-bbox). The MV's
-- existing partial subcategory index only covers rn_subcategory <= 5,
-- which doesn't help the per-subcategory venue-diverse fetch (it needs
-- ALL events in the subcategory, not just the top 5 ranked ones).
--
-- Without this index Postgres falls back to seq scan, costing 5-17 s
-- cold (observed in prod). Adding a composite (subcategory, start_at)
-- index lets the planner index-only-scan the future subcategory rows
-- and apply the geo filter as a recheck.
--
-- Partial-only on rows where subcategory IS NOT NULL so the index
-- stays small (most events don't have a subcategory set).

CREATE INDEX IF NOT EXISTS mv_carousel_payload_subcategory_start_idx
  ON public.mv_carousel_payload (subcategory, start_at)
  WHERE subcategory IS NOT NULL;

-- Refresh planner stats so the new index is used immediately rather
-- than waiting for autovacuum.
ANALYZE public.mv_carousel_payload;
