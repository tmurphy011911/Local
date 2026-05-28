-- Add event-level location columns to the events table.
--
-- Existing venue_city / venue_lat / venue_lng describe the host venue, which
-- is often an organization (Mass Audubon, First Congregational Church) whose
-- HQ is in a different town than the event itself. That made distance and
-- the For You city chip render the host org's location rather than where
-- the event actually takes place — e.g. a Williamstown event by Mass Audubon
-- showed "Boston / 9.9 mi" because Mass Audubon's HQ is near Boston.
--
-- These columns let ingest record the physical event location separately.
-- When set, they take precedence over the venue_* fields in distance and
-- city-chip rendering (see mv_events_for_geo + carousels edge function).
-- When NULL, behavior is unchanged.

ALTER TABLE public.events
  ADD COLUMN IF NOT EXISTS event_lat   double precision,
  ADD COLUMN IF NOT EXISTS event_lng   double precision,
  ADD COLUMN IF NOT EXISTS event_city  text,
  ADD COLUMN IF NOT EXISTS event_state text;

-- Geo index for the bbox prefilter in mv_events_for_geo. Partial so the
-- index stays small until backfill populates event_lat for the rows
-- that actually need it (events whose host venue is not at the event site).
CREATE INDEX IF NOT EXISTS events_event_geo_idx
  ON public.events (event_lat, event_lng)
  WHERE event_lat IS NOT NULL;

COMMENT ON COLUMN public.events.event_lat IS
  'Real event coords. Overrides venue_lat for distance when set.';
COMMENT ON COLUMN public.events.event_lng IS
  'Real event coords. Overrides venue_lng for distance when set.';
COMMENT ON COLUMN public.events.event_city IS
  'Real event city. Overrides venue_city for the For You chip when set.';
COMMENT ON COLUMN public.events.event_state IS
  'Real event state. Pairs with event_city.';
