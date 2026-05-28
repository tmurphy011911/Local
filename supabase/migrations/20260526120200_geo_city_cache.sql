-- City→coords cache so the event-coord backfill geocodes each (city, state)
-- pair once and reuses the result across the (potentially thousands of)
-- events that share the same town. ~350 MA towns dominate the working set;
-- this brings geocoding API calls down ~40×.
--
-- Read by scripts/backfill_event_coords.js and (later) by any other backfill
-- that needs to resolve city → coords. Not used at request time.

CREATE TABLE IF NOT EXISTS public.geo_city_cache (
  city        text NOT NULL,
  state       text NOT NULL DEFAULT '',
  country     text NOT NULL DEFAULT 'US',
  lat         double precision,
  lng         double precision,
  source      text NOT NULL DEFAULT 'google_places',
  resolved_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (city, state, country)
);

COMMENT ON TABLE public.geo_city_cache IS
  'Cache of (city, state, country) → lat/lng resolutions. Populated by backfill scripts (scripts/backfill_event_coords.js). NULL lat/lng means the geocoder returned nothing — cached to avoid re-asking. Keyed on (city, state, country) so two towns with the same name in different states stay distinct.';

GRANT SELECT ON public.geo_city_cache TO anon, authenticated;
GRANT ALL ON public.geo_city_cache TO service_role;
