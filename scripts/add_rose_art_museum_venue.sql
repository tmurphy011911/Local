-- Add Rose Art Museum as its own venue row.
-- Run in Supabase SQL Editor (Dashboard → SQL Editor → New Query).
--
-- Context: the MA crawl found Brandeis University's Trumba feed
-- (https://www.trumba.com/calendars/brandeis-university.rss) and extracted
-- 51 events under venue_name="Brandeis University". Rose Art Museum
-- exhibitions live inside that feed but get tagged with the parent's name,
-- so search for "Rose Art Museum" returns nothing. Yocal's regression suite
-- catches this — when asked to plan around Rose Art Museum it falls back to
-- recommend_and_save_venue (creates a stub with no event_id), and the plan
-- renders an unactionable slot.
--
-- Adding the venue here:
--   - lets search/carousels surface Rose-specific events once classify
--     post-processing splits aggregator events on sub-venue mentions (or
--     once we add a Rose-specific events_url that scrapes its own page)
--   - gives Yocal a real venue row to attach plan slots to
--   - is additive only — won't disturb the existing Brandeis row

-- ─────────────────────────────────────────────────────
-- 1. Does Rose Art Museum already exist?
-- ─────────────────────────────────────────────────────
SELECT
  'Rose Art Museum — existing venue row' AS section,
  id, name, city, state, category, place_type,
  events_url, source, is_active, last_crawled_at,
  google_rating, photo_url IS NOT NULL AS has_photo
FROM venues
WHERE name ILIKE '%rose art%'
   OR (name ILIKE '%rose%' AND city = 'Waltham');

-- ─────────────────────────────────────────────────────
-- 2. What events currently mention Rose Art Museum?
--    (likely under venue_name="Brandeis University" from the Trumba feed)
-- ─────────────────────────────────────────────────────
SELECT
  'Events mentioning Rose Art' AS section,
  id, title, venue_name, start_at, is_active, source
FROM events
WHERE (title ILIKE '%rose art%'
       OR description ILIKE '%rose art museum%')
  AND start_at >= now()
ORDER BY start_at ASC
LIMIT 20;

-- ─────────────────────────────────────────────────────
-- 3. UPSERT Rose Art Museum.
--    Idempotent on (name, city) — re-running won't create duplicates.
--    Verify events_url before running — if Brandeis doesn't publish a
--    Rose-specific Trumba calendar, point this at the exhibitions page
--    and the crawler's claude_html fallback will scrape it.
-- ─────────────────────────────────────────────────────
INSERT INTO venues (
  name,
  website,
  events_url,
  category,
  city,
  state,
  metro_area,
  lat,
  lng,
  is_active
) VALUES (
  'Rose Art Museum',
  'https://www.brandeis.edu/rose/',
  'https://www.brandeis.edu/rose/exhibitions/',
  'museum',
  'Waltham',
  'MA',
  'boston',
  42.3658,
  -71.2602,
  true
)
ON CONFLICT (name, city) DO UPDATE SET
  website    = EXCLUDED.website,
  events_url = EXCLUDED.events_url,
  category   = EXCLUDED.category,
  state      = EXCLUDED.state,
  metro_area = EXCLUDED.metro_area,
  lat        = COALESCE(venues.lat, EXCLUDED.lat),
  lng        = COALESCE(venues.lng, EXCLUDED.lng),
  is_active  = true;

-- ─────────────────────────────────────────────────────
-- 4. Confirm it landed.
-- ─────────────────────────────────────────────────────
SELECT
  'Rose Art Museum — post-insert' AS section,
  id, name, city, state, category, events_url, is_active
FROM venues
WHERE name = 'Rose Art Museum' AND city = 'Waltham';

-- ─────────────────────────────────────────────────────
-- OPTIONAL: backfill venue_name on existing Rose-tagged events.
-- Only run after confirming step 2 returns the events you expect.
-- This rewrites events whose title or description mentions Rose Art Museum
-- to use the new venue_name so search/carousels group them correctly.
-- ─────────────────────────────────────────────────────
-- UPDATE events
-- SET venue_name = 'Rose Art Museum'
-- WHERE venue_name = 'Brandeis University'
--   AND (title ILIKE '%rose art%' OR description ILIKE '%rose art museum%')
--   AND start_at >= now();
