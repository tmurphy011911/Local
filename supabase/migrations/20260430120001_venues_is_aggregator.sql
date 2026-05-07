-- Mark venues that list events happening at OTHER venues (Harvard Square,
-- Creative Legion, Visit Boston, etc.). Events tagged to these get
-- re-attributed to their actual venues by scripts/split_aggregator_events.js.
-- Carousels exclude these from the Places/venues feed (events still flow).
ALTER TABLE venues
  ADD COLUMN IF NOT EXISTS is_aggregator BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS venues_is_aggregator_idx
  ON venues(is_aggregator) WHERE is_aggregator = TRUE;

COMMENT ON COLUMN venues.is_aggregator IS
  'When true, this venue lists events occurring at other venues. Excluded from Places carousels; its events get reattributed by scripts/split_aggregator_events.js.';
