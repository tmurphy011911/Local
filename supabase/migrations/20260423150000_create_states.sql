-- `states` is the single source of truth for which US states the app has
-- content for. Both the curated_events refresh and the restaurant_weeks
-- refresh iterate `is_live = TRUE` rows; the client reads this table on
-- launch to (a) derive the user's state from their lat/lng bbox and (b)
-- know which neighboring states count as "nearby" for restaurant weeks.
--
-- Adding a state as simple as inserting a row and running the daily
-- refreshes — no code change needed.

CREATE TABLE IF NOT EXISTS states (
  code TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  is_live BOOLEAN NOT NULL DEFAULT TRUE,
  lat_min DOUBLE PRECISION NOT NULL,
  lat_max DOUBLE PRECISION NOT NULL,
  lng_min DOUBLE PRECISION NOT NULL,
  lng_max DOUBLE PRECISION NOT NULL,
  adjacent_codes TEXT[] NOT NULL DEFAULT '{}',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE states ENABLE ROW LEVEL SECURITY;

CREATE POLICY "states_read" ON states
  FOR SELECT TO anon, authenticated USING (TRUE);

-- Seed: New England + NY. Bboxes are approximate — deliberately loose so
-- coastal points and the odd panhandle don't fall outside. Adjacency is
-- standard US geography (land borders only, not "close enough" — keeps
-- "nearby" a well-defined concept).
INSERT INTO states (code, name, lat_min, lat_max, lng_min, lng_max, adjacent_codes) VALUES
  ('MA', 'Massachusetts', 41.2, 42.9, -73.6, -69.8, ARRAY['RI','NH','CT','VT','NY']),
  ('RI', 'Rhode Island',  41.1, 42.1, -72.0, -71.1, ARRAY['MA','CT']),
  ('NH', 'New Hampshire', 42.6, 45.4, -72.6, -70.6, ARRAY['MA','VT','ME']),
  ('CT', 'Connecticut',   40.9, 42.1, -73.8, -71.7, ARRAY['MA','RI','NY']),
  ('VT', 'Vermont',       42.7, 45.1, -73.5, -71.4, ARRAY['MA','NH','NY']),
  ('ME', 'Maine',         42.9, 47.5, -71.1, -66.9, ARRAY['NH']),
  ('NY', 'New York',      40.4, 45.1, -79.8, -71.8, ARRAY['MA','CT','VT','NJ','PA'])
ON CONFLICT (code) DO NOTHING;
