CREATE TABLE venues (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name               TEXT NOT NULL,
  website            TEXT,
  events_url         TEXT,
  category           TEXT,
  city               TEXT NOT NULL,
  metro_area         TEXT DEFAULT 'boston',
  lat                NUMERIC(10,7),
  lng                NUMERIC(10,7),
  is_active          BOOLEAN DEFAULT true,
  last_crawled_at    TIMESTAMPTZ,
  crawl_success_rate NUMERIC(3,2) DEFAULT 1.0,
  created_at         TIMESTAMPTZ DEFAULT now()
);

ALTER TABLE venues ENABLE ROW LEVEL SECURITY;
CREATE POLICY "public venues readable" ON venues FOR SELECT USING (true);
