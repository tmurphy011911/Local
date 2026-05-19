-- Latency sprint, slow-path completion: single-call venue cache RPC.
--
-- The carousels edge function's fetchVenueCache() pages through ~9k
-- active venues in 1000-row chunks. PostgREST's max_rows=1000 cap forces
-- the pagination, and the 9 sequential round-trips are the new long pole
-- of the slow path (~13 s of the 15 s total).
--
-- RPCs aren't subject to PostgREST's max_rows cap. This function returns
-- only the four columns the venue cache actually uses (name + rank +
-- photo_url + description), so the payload stays ~1 MB and the network
-- round-trip is single-digit ms instead of seconds.

CREATE OR REPLACE FUNCTION public.get_active_venues_lite()
RETURNS TABLE (
  name text,
  public_interest_rank int,
  photo_url text,
  description text
) LANGUAGE sql STABLE AS $$
  SELECT name, public_interest_rank, photo_url, description
    FROM public.venues
   WHERE is_active = true;
$$;

GRANT EXECUTE ON FUNCTION public.get_active_venues_lite TO anon, authenticated, service_role;

COMMENT ON FUNCTION public.get_active_venues_lite IS
  'Single-call venue lookup for the carousels edge function. Bypasses PostgREST max_rows=1000 cap that forced fetchVenueCache to do 9 sequential paginated queries.';
