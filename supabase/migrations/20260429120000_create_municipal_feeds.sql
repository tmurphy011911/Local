-- Municipal feed registry. Replaces the static municipal_feeds.json so the
-- weekly discovery job can insert auto-detected feeds without a code change.
--
-- Each row represents one event feed exposed by a city/town's official site.
-- The sync job reads enabled rows; the discovery job inserts new rows when
-- it probes a candidate domain and finds a working endpoint.

CREATE TABLE IF NOT EXISTS municipal_feeds (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  metro TEXT NOT NULL,
  name TEXT NOT NULL,
  type TEXT NOT NULL,                 -- drupal_jsonapi | localist | ical | wp_tribe
  url TEXT NOT NULL,
  city TEXT NOT NULL,
  state CHAR(2) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  source TEXT NOT NULL DEFAULT 'manual',  -- manual | auto_discovered
  discovered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_synced_at TIMESTAMPTZ,
  last_event_count INTEGER,
  last_status TEXT,                  -- ok | error: <message>
  consecutive_failures INTEGER NOT NULL DEFAULT 0,
  UNIQUE (url)
);

CREATE INDEX IF NOT EXISTS idx_municipal_feeds_state ON municipal_feeds (state) WHERE enabled = TRUE;
CREATE INDEX IF NOT EXISTS idx_municipal_feeds_metro ON municipal_feeds (metro) WHERE enabled = TRUE;

ALTER TABLE municipal_feeds ENABLE ROW LEVEL SECURITY;

-- Seed: known-good entries. Backfilled from the prior municipal_feeds.json
-- plus a curated list of MA cities likely to expose Drupal JSON:API / WP Tribe
-- / iCal feeds. The discovery job will validate and prune broken ones.
-- City-specific URLs are best guesses — discovery will probe + correct.
INSERT INTO municipal_feeds (metro, name, type, url, city, state, source) VALUES
  ('boston', 'Boston.gov',         'drupal_jsonapi', 'https://www.boston.gov/jsonapi/node/event',           'Boston',     'MA', 'manual')
ON CONFLICT (url) DO NOTHING;
