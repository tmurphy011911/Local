-- Backfill event.image_url from venue.photo_url for every event whose
-- crawler write didn't capture an event-specific image. Today,
-- run_crawls.js → normalizeEvent always emits `image_url: null` because
-- none of the platform adapters surface a per-event image. The result:
-- thousands of crawler-sourced events render as flat gradient
-- placeholders (Brookline Booksmith, Gore Place, museum programs, etc.)
-- when the parent venue almost always has a usable photo on file.
--
-- This is paired with a write-time fix in run_crawls.js so future crawl
-- runs default image_url to venue.photo_url at insert time.
--
-- Match key: events.venue_name = venues.name. Loose, but it's the only
-- shared key the crawler writes denormalized onto events today. If
-- multiple venue rows share the same name (rare — Boston has e.g. two
-- "Trident" entries), we pick whichever the join orders first; both
-- will have similar photos.
--
-- Idempotent: re-running just no-ops on rows whose image_url is already
-- non-null. The WHERE guarantees we never overwrite an event-specific
-- image with a venue fallback.

UPDATE events
SET image_url = venues.photo_url
FROM venues
WHERE events.image_url IS NULL
  AND events.venue_name = venues.name
  AND venues.photo_url IS NOT NULL;

-- Reporting query — how many rows we just filled and how many remain
-- imageless (events whose venue had no photo either).
SELECT
  COUNT(*) FILTER (WHERE image_url IS NOT NULL) AS now_has_image,
  COUNT(*) FILTER (WHERE image_url IS NULL)     AS still_no_image
FROM events
WHERE is_active = true;
