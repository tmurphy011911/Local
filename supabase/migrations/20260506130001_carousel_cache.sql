-- Pre-computed carousel cache. Background job (runs every ~5 min) writes
-- the heavy-aggregation portion of each (location_bucket, category) pair
-- here as JSONB. The carousels edge function then becomes a single
-- indexed SELECT — typically <50 ms server-side instead of the current
-- 3-8 s, which dominated home-tab cold-open latency.
--
-- Per-user personalization (favorites, affinity, community scores) is
-- still layered on top of this cache at request time — fast because it's
-- a few row lookups by user_id.
CREATE TABLE IF NOT EXISTS carousel_cache (
  -- Rounded lat/lng to 0.1° (~7mi) so users in the same metro share a
  -- bucket. Matches the existing in-memory cache key in the edge
  -- function (`Math.round(lat * 10) / 10`).
  lat_bucket NUMERIC(4, 1) NOT NULL,
  lng_bucket NUMERIC(5, 1) NOT NULL,
  -- 'foryou' (default) or one of the dynamic category tabs.
  category TEXT NOT NULL DEFAULT 'foryou',
  -- The trimmed JSON payload — same shape the edge function returns
  -- minus per-user fields. Roughly 100-200 KB per row at current scale.
  payload JSONB NOT NULL,
  -- Diagnostics
  computed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  event_count INTEGER NOT NULL DEFAULT 0,
  carousel_count INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (lat_bucket, lng_bucket, category)
);

-- Index for the "any cache > N minutes old" rebuild check used by the
-- cron job to avoid re-computing rows that are still warm.
CREATE INDEX IF NOT EXISTS carousel_cache_computed_at_idx
  ON carousel_cache (computed_at);

COMMENT ON TABLE carousel_cache IS
  'Precomputed home-tab carousels keyed by (lat_bucket, lng_bucket, category). Refilled every ~5 min by scripts/refresh_carousel_cache.js. Read by supabase/functions/carousels/index.ts.';
