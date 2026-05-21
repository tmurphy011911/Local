-- Lookup for Brookline Booksmith, Gore Place, Sellersville Theatre.
-- Run in Supabase SQL Editor (Dashboard → SQL Editor → New Query).
--
-- Each block returns:
--   (1) the venue row (or empty if missing entirely)
--   (2) the event count linked to that venue (name+city match — events
--       don't have a venue_id FK, so the join is string-based)
--   (3) a sample of the most recent events so you can sanity-check the
--       data shape before deciding what to do
--
-- If the venue exists but the event count is low compared to the
-- venue's actual public calendar, the likely causes are:
--   a) events_url is null / wrong / unreachable → crawler skipped the venue
--   b) events_url loads but the parser failed (JSON-LD missing,
--      Localist not detected, Drupal jsonapi 404, etc.) → check
--      venue.last_crawled_at / crawl_success_rate
--   c) source returns date-only data → check time_known distribution on
--      the events that DID come through

-- ─────────────────────────────────────────────────────
-- 1. BROOKLINE BOOKSMITH (Brookline, MA — independent bookstore with
--    a frequent author-events series)
-- ─────────────────────────────────────────────────────
SELECT
  'Brookline Booksmith — venue row' AS section,
  id, name, city, state, category, place_type,
  public_interest_rank, events_url, source,
  is_active, last_crawled_at, crawl_success_rate,
  google_rating, photo_url IS NOT NULL AS has_photo
FROM venues
WHERE name ILIKE '%brookline%booksmith%'
   OR (name ILIKE '%booksmith%' AND city ILIKE '%brookline%');

SELECT
  'Brookline Booksmith — event counts' AS section,
  COUNT(*) AS total_events,
  COUNT(*) FILTER (WHERE is_active = true) AS active_events,
  COUNT(*) FILTER (WHERE start_at >= now()) AS upcoming_events,
  COUNT(*) FILTER (WHERE start_at >= now() AND time_known = true) AS upcoming_with_known_time,
  MIN(start_at) FILTER (WHERE start_at >= now()) AS soonest_upcoming,
  MAX(start_at) AS latest_event
FROM events
WHERE venue_name ILIKE '%brookline%booksmith%'
   OR venue_name ILIKE '%booksmith%';

SELECT
  'Brookline Booksmith — sample upcoming events' AS section,
  id, title, start_at, time_known, is_active, source
FROM events
WHERE venue_name ILIKE '%booksmith%' AND start_at >= now()
ORDER BY start_at ASC
LIMIT 10;

-- ─────────────────────────────────────────────────────
-- 2. GORE PLACE (Waltham, MA — historic estate, events: sheepshearing
--    festival, garden tours, twilight tours)
-- ─────────────────────────────────────────────────────
SELECT
  'Gore Place — venue row' AS section,
  id, name, city, state, category, place_type,
  public_interest_rank, events_url, source,
  is_active, last_crawled_at, crawl_success_rate,
  google_rating, photo_url IS NOT NULL AS has_photo
FROM venues
WHERE name ILIKE '%gore place%'
   OR (name ILIKE '%gore%' AND city ILIKE '%waltham%');

SELECT
  'Gore Place — event counts' AS section,
  COUNT(*) AS total_events,
  COUNT(*) FILTER (WHERE is_active = true) AS active_events,
  COUNT(*) FILTER (WHERE start_at >= now()) AS upcoming_events,
  COUNT(*) FILTER (WHERE start_at >= now() AND time_known = true) AS upcoming_with_known_time,
  MIN(start_at) FILTER (WHERE start_at >= now()) AS soonest_upcoming,
  MAX(start_at) AS latest_event
FROM events
WHERE venue_name ILIKE '%gore place%';

SELECT
  'Gore Place — sample upcoming events' AS section,
  id, title, start_at, time_known, is_active, source
FROM events
WHERE venue_name ILIKE '%gore place%' AND start_at >= now()
ORDER BY start_at ASC
LIMIT 10;

-- ─────────────────────────────────────────────────────
-- 3. SELLERSVILLE THEATRE (Sellersville, PA — 300-seat music + theatre
--    venue with a dense national-touring schedule)
-- ─────────────────────────────────────────────────────
SELECT
  'Sellersville Theatre — venue row' AS section,
  id, name, city, state, category, place_type,
  public_interest_rank, events_url, source,
  is_active, last_crawled_at, crawl_success_rate,
  google_rating, photo_url IS NOT NULL AS has_photo
FROM venues
WHERE name ILIKE '%sellersville%theatre%'
   OR name ILIKE '%sellersville%theater%'
   OR (name ILIKE '%sellersville%' AND state = 'PA');

SELECT
  'Sellersville Theatre — event counts' AS section,
  COUNT(*) AS total_events,
  COUNT(*) FILTER (WHERE is_active = true) AS active_events,
  COUNT(*) FILTER (WHERE start_at >= now()) AS upcoming_events,
  COUNT(*) FILTER (WHERE start_at >= now() AND time_known = true) AS upcoming_with_known_time,
  MIN(start_at) FILTER (WHERE start_at >= now()) AS soonest_upcoming,
  MAX(start_at) AS latest_event
FROM events
WHERE venue_name ILIKE '%sellersville%';

SELECT
  'Sellersville Theatre — sample upcoming events' AS section,
  id, title, start_at, time_known, is_active, source
FROM events
WHERE venue_name ILIKE '%sellersville%' AND start_at >= now()
ORDER BY start_at ASC
LIMIT 10;
