-- ════════════════════════════════════════════════════════════════════════════
-- Reconcile states + metros to match production reality.
--
-- WHY: 20260423150000_create_states.sql was authored but never applied to prod.
-- Prod's `states` was hand-edited over time, and a `metros` table was created
-- directly in prod with NO migration at all. The result: migration history did
-- NOT match prod, so a fresh `supabase db reset` produced a DB that disagreed
-- with production (extra never-launched New England states, all is_live=TRUE,
-- and a missing `metros` table entirely).
--
-- This migration captures the TRUE current prod state as of 2026-06-05:
--   states:  MA, RI, PA, NY live; NJ live (just launched). NH/CT/VT/ME were
--            seeded by the old migration but NEVER launched — removed here so a
--            fresh DB matches prod.
--   metros:  18 rows (MA 3, RI 1, PA 4, NY 6, NJ 4). Drives the Thursday push
--            gate + resolveUserState Path-1 (city→metro).
--
-- Every value below is the literal current prod value, so applying this to prod
-- via `db push` is a no-op (ON CONFLICT upserts/DO NOTHING). Its real job is to
-- make fresh/dev environments reproduce prod.
-- ════════════════════════════════════════════════════════════════════════════

-- ── states ──────────────────────────────────────────────────────────────────
-- Old migration created the table (IF NOT EXISTS) and seeded New England + NY.
-- Reconcile the launched rows to current values, and drop the never-launched
-- placeholders so is_live iteration (curated/restaurant-week refreshes) doesn't
-- pick up states with no content.
INSERT INTO states (code, name, is_live, lat_min, lat_max, lng_min, lng_max, adjacent_codes) VALUES
  ('MA', 'Massachusetts', TRUE,  41.2, 42.9, -73.6, -69.8, ARRAY['RI','NY']),
  ('RI', 'Rhode Island',  TRUE,  41.1, 42.1, -72.0, -71.1, ARRAY['MA']),
  ('PA', 'Pennsylvania',  TRUE,  39.7, 42.3, -80.6, -74.6, ARRAY['NY','NJ']),
  ('NY', 'New York',      TRUE,  40.4, 45.1, -79.8, -71.8, ARRAY['MA','PA','NJ']),
  ('NJ', 'New Jersey',    TRUE,  38.9, 41.4, -75.6, -73.9, ARRAY['NY','PA'])
ON CONFLICT (code) DO UPDATE SET
  name           = EXCLUDED.name,
  is_live        = EXCLUDED.is_live,
  lat_min        = EXCLUDED.lat_min,
  lat_max        = EXCLUDED.lat_max,
  lng_min        = EXCLUDED.lng_min,
  lng_max        = EXCLUDED.lng_max,
  adjacent_codes = EXCLUDED.adjacent_codes;

-- Never-launched placeholders from the original seed (absent in prod).
DELETE FROM states WHERE code IN ('NH','CT','VT','ME');

-- ── metros ──────────────────────────────────────────────────────────────────
-- No prior migration existed for this table; created directly in prod. Drives
-- the Thursday "weekend in {metro}" push (radius_miles gate) and Path-1 of
-- resolveUserState (user_profiles.city ILIKE metros.name → state_code).
CREATE TABLE IF NOT EXISTS metros (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  state_code    TEXT NOT NULL REFERENCES states(code),
  name          TEXT NOT NULL,
  lat           DOUBLE PRECISION NOT NULL,
  lng           DOUBLE PRECISION NOT NULL,
  radius_miles  INTEGER NOT NULL DEFAULT 75,
  is_active     BOOLEAN NOT NULL DEFAULT TRUE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS metros_state_code_idx ON metros (state_code);

ALTER TABLE metros ENABLE ROW LEVEL SECURITY;

-- Mirrors prod: only the service role reads metros (push/gate logic runs
-- server-side; no anon/authenticated SELECT policy exists, so RLS denies them).

-- Seed the exact current prod rows. Fixed UUIDs so applying to prod is a no-op.
INSERT INTO metros (id, state_code, name, lat, lng, radius_miles, is_active) VALUES
  ('6eb9ea42-46cb-45b2-87e2-113db97c5b0e', 'MA', 'Boston',        42.3601, -71.0589, 75, TRUE),
  ('f693e7a7-ced8-452d-beda-e4486f810543', 'MA', 'Worcester',     42.2626, -71.8023, 75, TRUE),
  ('0bb99ab2-e823-429b-b15c-b7e0df694321', 'MA', 'Springfield',   42.1015, -72.5898, 75, TRUE),
  ('5b6c9bfa-63f5-42f4-9b82-9717930f57db', 'RI', 'Providence',    41.8240, -71.4128, 75, TRUE),
  ('27079b7e-2f0a-4f8f-a676-2bab512069c3', 'PA', 'Philadelphia',  39.9526, -75.1652, 75, TRUE),
  ('397d5040-7b2b-46c7-844f-a74e05484819', 'PA', 'Pittsburgh',    40.4406, -79.9959, 75, TRUE),
  ('beee7dcf-59aa-483c-ac5e-dbce8aa6a638', 'PA', 'Allentown',     40.6084, -75.4902, 75, TRUE),
  ('31a74813-a297-4a86-ab55-75c16ed74bf7', 'PA', 'Harrisburg',    40.2732, -76.8839, 75, TRUE),
  ('f54f26f4-734a-49f0-b280-da5382da7286', 'NY', 'New York City', 40.7128, -74.0060, 75, TRUE),
  ('b96fa6c0-018b-44e2-8eed-554505b6d06b', 'NY', 'Buffalo',       42.8864, -78.8784, 75, TRUE),
  ('3fbc6505-78da-42ac-98b0-31765c3b9a3a', 'NY', 'Rochester',     43.1566, -77.6088, 75, TRUE),
  ('62dc6eba-ef9b-43d6-a7e3-99185bf5bd02', 'NY', 'Syracuse',      43.0481, -76.1474, 75, TRUE),
  ('2d19a516-3110-4c21-9278-6c7c3e1580d7', 'NY', 'Albany',        42.6526, -73.7562, 75, TRUE),
  ('d6533566-8aa5-4219-8f2b-4d26e7b9d0f5', 'NJ', 'Newark',        40.7357, -74.1724, 75, TRUE),
  ('db0f1f27-70ef-4504-9932-e8b139bb43b8', 'NJ', 'Jersey City',   40.7282, -74.0776, 75, TRUE),
  ('fa29711d-0a9a-492e-95d8-ccd9a73a9f49', 'NJ', 'Trenton',       40.2206, -74.7565, 75, TRUE),
  ('ded09712-681a-448d-a2e9-1dd8f666e7bd', 'NJ', 'Atlantic City', 39.3643, -74.4229, 75, TRUE)
ON CONFLICT (id) DO NOTHING;
