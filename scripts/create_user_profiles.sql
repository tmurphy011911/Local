-- Run this in the Supabase SQL Editor (Dashboard > SQL Editor > New Query)

CREATE TABLE IF NOT EXISTS user_profiles (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  phone                 TEXT UNIQUE,
  city                  TEXT NOT NULL DEFAULT 'boston',
  user_lat              NUMERIC(10,7),
  user_lng              NUMERIC(10,7),
  selected_neighborhoods TEXT[] DEFAULT '{}',
  selected_venues       TEXT[] DEFAULT '{}',
  selected_categories   TEXT[] DEFAULT '{}',
  notif_tickets         BOOLEAN DEFAULT true,
  notif_nearby          BOOLEAN DEFAULT true,
  notif_venues          BOOLEAN DEFAULT true,
  onboarding_complete   BOOLEAN DEFAULT false,
  created_at            TIMESTAMPTZ DEFAULT now(),
  updated_at            TIMESTAMPTZ DEFAULT now()
);

ALTER TABLE user_profiles ENABLE ROW LEVEL SECURITY;

-- Public insert (new users can create their own profile)
CREATE POLICY "anyone can insert profiles" ON user_profiles
  FOR INSERT WITH CHECK (true);

-- Public read/update by id (will lock down with auth later)
CREATE POLICY "profiles readable by id" ON user_profiles
  FOR SELECT USING (true);

CREATE POLICY "profiles updatable by id" ON user_profiles
  FOR UPDATE USING (true);

-- Auto-update updated_at
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER user_profiles_updated_at
  BEFORE UPDATE ON user_profiles
  FOR EACH ROW EXECUTE FUNCTION update_updated_at();
