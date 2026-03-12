-- Run this in the Supabase SQL Editor (Dashboard > SQL Editor > New Query)

CREATE TABLE IF NOT EXISTS carousels (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  city         TEXT NOT NULL DEFAULT 'boston',
  slug         TEXT NOT NULL,
  title        TEXT NOT NULL,
  subtitle     TEXT,
  filter_type  TEXT NOT NULL,  -- 'category', 'date_range', 'tag', 'top10_weekly', 'trending'
  filter_value TEXT,           -- e.g. 'music', 'this_weekend', 'free'
  sort_order   TEXT DEFAULT 'start_at',
  position     INT DEFAULT 0,
  is_active    BOOLEAN DEFAULT true,
  created_at   TIMESTAMPTZ DEFAULT now(),
  UNIQUE(city, slug)
);

INSERT INTO carousels (city, slug, title, subtitle, filter_type, filter_value, sort_order, position) VALUES
  ('boston', 'top10_weekly',  'Top 10 This Week',  'The best things to do this week', 'top10_weekly', NULL,           'start_at',   0),
  ('boston', 'this_weekend',  'This Weekend',      'Events happening soon',           'date_range',  'this_weekend', 'start_at',   1),
  ('boston', 'music',         'Live Music',        'Concerts and shows',              'category',    'music',        'start_at',   2),
  ('boston', 'arts',          'Arts & Culture',    'Museums, galleries, theatre',     'category',    'arts',         'start_at',   3),
  ('boston', 'sports',        'Sports',            'Games and matches',               'category',    'sports',       'start_at',   4),
  ('boston', 'free',          'Free Events',       'No ticket required',              'tag',         'free',         'start_at',   5),
  ('boston', 'nightlife',     'Nightlife',         'Bars, clubs, late night',         'category',    'nightlife',    'start_at',   6)
ON CONFLICT (city, slug) DO UPDATE SET
  title = EXCLUDED.title,
  subtitle = EXCLUDED.subtitle,
  filter_type = EXCLUDED.filter_type,
  filter_value = EXCLUDED.filter_value,
  sort_order = EXCLUDED.sort_order,
  position = EXCLUDED.position,
  is_active = EXCLUDED.is_active;
