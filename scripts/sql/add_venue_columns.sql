-- Add google_rating, price_level, place_type, and address to venues table
-- Run this in the Supabase SQL Editor before running discover_venues_google.js

-- place_type groups categories for plan building:
--   eat_drink:          restaurant, bar, brewery, cafe, bakery, winery
--   public_attraction:  park, beach, garden, trail, waterfront, plaza
--   ticketed:           museum, aquarium, zoo, historic_site, tour, observation, escape_room
--   entertainment:      bowling, arcade, mini_golf, movie_theater, spa, shopping, bookstore, market

ALTER TABLE venues ADD COLUMN IF NOT EXISTS google_rating REAL;
ALTER TABLE venues ADD COLUMN IF NOT EXISTS price_level TEXT;
ALTER TABLE venues ADD COLUMN IF NOT EXISTS place_type TEXT;
ALTER TABLE venues ADD COLUMN IF NOT EXISTS address TEXT;

-- Index for querying by place_type within a metro area
CREATE INDEX IF NOT EXISTS idx_venues_metro_place_type ON venues(metro_area, place_type);

-- Index for sorting by rating
CREATE INDEX IF NOT EXISTS idx_venues_rating ON venues(google_rating DESC NULLS LAST);

-- Backfill place_type for existing venues based on category
UPDATE venues SET place_type = 'eat_drink'
  WHERE category IN ('restaurant', 'bar', 'brewery', 'cafe', 'bakery', 'winery')
  AND place_type IS NULL;

UPDATE venues SET place_type = 'public_attraction'
  WHERE category IN ('park', 'public_space')
  AND place_type IS NULL;

UPDATE venues SET place_type = 'ticketed'
  WHERE category IN ('museum', 'tour', 'theater', 'arts_center', 'library')
  AND place_type IS NULL;

UPDATE venues SET place_type = 'entertainment'
  WHERE category IN ('comedy_club', 'sports_venue', 'stadium')
  AND place_type IS NULL;

UPDATE venues SET place_type = 'event_venue'
  WHERE category IN ('music_venue', 'outdoor_amphitheater', 'college', 'bar_events')
  AND place_type IS NULL;
