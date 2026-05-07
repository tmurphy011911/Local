-- Cross-source event dedup. The same game / show / concert can arrive from
-- multiple ingest paths (Ticketmaster, venue crawl, RSS, etc.) with slightly
-- different titles and times — source-scoped dedupe_hash never collides them.
-- The dedup_events.js script finds these pairs and points the loser at the
-- winner via this column. Losers also get is_active=false so they drop out of
-- the feed; the row is preserved for audit and reversal.
ALTER TABLE events
  ADD COLUMN IF NOT EXISTS duplicate_of UUID NULL REFERENCES events(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS events_duplicate_of_idx
  ON events(duplicate_of) WHERE duplicate_of IS NOT NULL;

COMMENT ON COLUMN events.duplicate_of IS
  'When set, this row is a duplicate of the referenced event. Maintained by scripts/dedup_events.js. Losers also have is_active=false.';
