-- restaurant_weeks: upcoming restaurant-week promotions per region, refreshed
-- daily by scripts/refresh_restaurant_weeks.js. Same pipeline shape as
-- curated_events but a separate table so the two carousels can evolve
-- independently (cardinality, card style, cadence, admin overrides).
--
-- Distinct from curated_events because restaurant weeks are dining promos
-- (Dine Out Boston, Cambridge Restaurant Week, etc.), not iconic civic events.
-- Mixing them would force one carousel to represent both with a single voice.

CREATE TABLE IF NOT EXISTS restaurant_weeks (
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
  participating_count INTEGER,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  excluded_at TIMESTAMPTZ,
  excluded_reason TEXT,
  curation_batch_id UUID,
  curated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_restaurant_weeks_region_active_end
  ON restaurant_weeks (region, is_active, end_at);

CREATE UNIQUE INDEX IF NOT EXISTS idx_restaurant_weeks_region_title_unique
  ON restaurant_weeks (region, lower(trim(title)))
  WHERE is_active = TRUE;

ALTER TABLE restaurant_weeks ENABLE ROW LEVEL SECURITY;

CREATE POLICY "restaurant_weeks_read" ON restaurant_weeks
  FOR SELECT TO anon, authenticated USING (TRUE);
