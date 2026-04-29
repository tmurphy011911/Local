-- Adds a time_known flag to events for distinguishing real start times
-- from date-only ingestions that defaulted to midnight Eastern.
--
-- Backstory: many crawler adapters call atStartOfDay() when the source page
-- gives a date with no time. That writes 00:00 local time to start_at, which
-- shows up as "12 AM" in the UI and breaks Bo's plan-builder logic (events
-- get assigned to the wrong day, no usable time slot, etc.).
--
-- This column lets the app distinguish:
--   time_known = true  → the start_at is a real, source-provided time
--   time_known = false → start_at was inferred from a date-only source;
--                        treat as "Time TBD" / "All day" in the UI and
--                        let Bo pick a contextually reasonable hour.
--
-- Backfill heuristic: any existing row whose local-time start_at is
-- exactly 00:00:00 (UTC offset 04:00 in EDT, 05:00 in EST). This catches
-- the bulk of bad data without false-positive on real midnight events
-- (which are virtually nonexistent for our use case — late-night events
-- are typically 11 PM, 11:30 PM, etc.).

ALTER TABLE events ADD COLUMN IF NOT EXISTS time_known BOOLEAN NOT NULL DEFAULT TRUE;

-- Backfill: mark existing midnight-Eastern rows as time_known=false
UPDATE events
SET time_known = FALSE
WHERE start_at IS NOT NULL
  AND EXTRACT(hour   FROM (start_at AT TIME ZONE 'America/New_York')) = 0
  AND EXTRACT(minute FROM (start_at AT TIME ZONE 'America/New_York')) = 0;

CREATE INDEX IF NOT EXISTS idx_events_time_known ON events(time_known) WHERE time_known = FALSE;

COMMENT ON COLUMN events.time_known IS
  'TRUE when start_at is a real source-provided time; FALSE when start_at was inferred from a date-only crawl source (defaulted to midnight). UI should show "Time TBD" for FALSE rows.';
