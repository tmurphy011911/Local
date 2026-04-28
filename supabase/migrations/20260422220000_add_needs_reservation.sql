-- Add needs_reservation: whether this venue typically takes reservations.
-- Source: Google Places `reservable` field (true/false/unknown).
-- NULL = not evaluated yet; TRUE = reservable; FALSE = walk-in only.
--
-- Used by the client to decide whether to show a "Reserve a Table" CTA, and
-- by the reservation-URL backfill to prioritize venues where a URL is likely
-- to exist.

ALTER TABLE venues
  ADD COLUMN IF NOT EXISTS needs_reservation boolean;

-- Partial index: only the TRUE rows are useful for the backfill job, which
-- scans for dining venues with no reservation_url yet but where reservations
-- are expected.
CREATE INDEX IF NOT EXISTS venues_needs_reservation_idx
  ON venues (needs_reservation)
  WHERE needs_reservation = true AND reservation_url IS NULL;
