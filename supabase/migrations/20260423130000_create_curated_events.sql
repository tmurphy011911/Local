-- curated_events: editorial top-N "must-know" events per region, refreshed daily
-- by scripts/refresh_curated_events.js calling Claude. The home feed surfaces
-- the For You tab's curated list as an editorial carousel. Cards auto-disappear
-- once end_at passes (deactivate step in the refresh script) or when an admin
-- manually excludes one (excluded_at set by hand).
--
-- Why a new table instead of reusing `events`:
--   - These are Claude-curated marquee items, not crawler-ingested. Mixing them
--     into `events` would pollute ranking + trigger the deactivation cascades
--     meant for live-venue listings.
--   - Smaller row count (~25 per region) makes cache-all-in-memory trivial.
--   - Dedicated is_active / excluded_reason columns let editors yank an item
--     without nulling out timing fields.

CREATE TABLE IF NOT EXISTS curated_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  region TEXT NOT NULL DEFAULT 'MA',
  title TEXT NOT NULL,
  description TEXT,
  start_at TIMESTAMPTZ,
  end_at TIMESTAMPTZ,
  venue_name TEXT,
  venue_address TEXT,
  venue_city TEXT,
  venue_state TEXT,
  venue_lat DOUBLE PRECISION,
  venue_lng DOUBLE PRECISION,
  image_url TEXT,
  source_url TEXT,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  excluded_at TIMESTAMPTZ,
  excluded_reason TEXT,
  curation_batch_id UUID,
  curated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Primary read pattern: active events in a region sorted by start_at, filtering
-- out expired ones. This composite covers the daily refresh "how many active"
-- count and the client/edge-function fetch.
CREATE INDEX IF NOT EXISTS idx_curated_events_region_active_end
  ON curated_events (region, is_active, end_at);

-- Title uniqueness within an active region prevents Claude from re-proposing
-- the same item after a restart/batch rerun. Case-insensitive on trimmed title.
CREATE UNIQUE INDEX IF NOT EXISTS idx_curated_events_region_title_unique
  ON curated_events (region, lower(trim(title)))
  WHERE is_active = TRUE;

-- Row-level security: readable by anon (client fetches directly), writable only
-- via service key (refresh script + admin exclusions).
ALTER TABLE curated_events ENABLE ROW LEVEL SECURITY;

CREATE POLICY "curated_events_read" ON curated_events
  FOR SELECT TO anon, authenticated USING (TRUE);
