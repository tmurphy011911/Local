-- Add team_name column to events so the classifier and carousels edge
-- function (which already reference it) can actually persist and query it.
-- Before this, PostgREST was silently erroring on PATCH bodies containing
-- team_name and the server-side sports-team carousels always returned
-- empty.

ALTER TABLE events ADD COLUMN IF NOT EXISTS team_name text;

CREATE INDEX IF NOT EXISTS idx_events_team_name
  ON events(team_name)
  WHERE team_name IS NOT NULL;
