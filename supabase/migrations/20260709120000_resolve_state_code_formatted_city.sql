-- ===========================================================================
-- resolve_state_code: match the metros table on the city's first comma-segment
-- ---------------------------------------------------------------------------
-- The city name arriving from onboarding is often a formatted string
-- ("Philadelphia, PA, USA"), but the metros lookup did an exact ILIKE against
-- the bare metro name ("Philadelphia"), so it missed and fell through to the
-- bounding-box path. For border cities (Philadelphia sits on the PA/NJ line)
-- the bbox path mis-picks the neighbor (NJ) — it can't tell which side of the
-- river a point is on. Matching on split_part(p_city, ',', 1) makes the
-- metro path catch formatted names, so "Philadelphia, PA, USA" -> PA.
--
-- Additive: CREATE OR REPLACE of an existing STABLE SQL function, same
-- signature and return type. Safe for every live client.
-- ===========================================================================
create or replace function public.resolve_state_code(
  p_lat double precision,
  p_lng double precision,
  p_city text
)
returns text
language sql
stable
as $$
  select coalesce(
    -- Path 1: home city name -> metro -> state_code. Match on the first
    -- comma-segment so formatted names ("Philadelphia, PA, USA") still resolve.
    (select m.state_code
       from public.metros m
      where p_city is not null
        and m.name ilike trim(split_part(p_city, ',', 1))
      limit 1),
    -- Path 2: coordinate bounding box; prefer live states, then the state the
    -- point is MOST INTERIOR to (largest min-distance to any bbox edge).
    (select s.code
       from public.states s
      where p_lat is not null and p_lng is not null
        and p_lat between s.lat_min and s.lat_max
        and p_lng between s.lng_min and s.lng_max
      order by s.is_live desc,
               least(p_lat - s.lat_min, s.lat_max - p_lat,
                     p_lng - s.lng_min, s.lng_max - p_lng) desc
      limit 1)
  );
$$;

-- ---------------------------------------------------------------------------
-- Make home_state server-authoritative. The old trigger only computed
-- home_state when it was null, so a profile inserted with coords BEFORE
-- hometown_name arrived (or with a client-supplied home_state) got stuck on the
-- early border-broken bbox result (Philadelphia -> NJ) and never recomputed once
-- the city name was known. Recompute from the hometown on every write; fall back
-- to the existing value only when the resolver can't determine a state.
-- hometown_* stays write-once via trg_guard_hometown_write, so this stabilises
-- as soon as the hometown is pinned.
-- ---------------------------------------------------------------------------
create or replace function public.set_home_state()
returns trigger
language plpgsql
as $$
begin
  -- Fall back to city (and current-location coords) so a write that sets the
  -- location before hometown_name is populated still resolves via the reliable
  -- metros path instead of the border-broken bbox (Philadelphia -> NJ).
  new.home_state := coalesce(
    public.resolve_state_code(
      coalesce(new.hometown_lat, new.user_lat, new.manual_lat),
      coalesce(new.hometown_lng, new.user_lng, new.manual_lng),
      coalesce(new.hometown_name, new.city)
    ),
    new.home_state
  );
  return new;
end;
$$;
