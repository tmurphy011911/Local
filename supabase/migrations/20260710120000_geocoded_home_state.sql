-- ===========================================================================
-- home_state from the geocoder (authoritative), not from coordinate guessing.
-- ---------------------------------------------------------------------------
-- Deriving a US state from lat/lng is unreliable at borders (Philadelphia's
-- point sits inside BOTH the PA and NJ bounding boxes; nearest-metro is wrong
-- for a resident just across a state line). The geocoder already returns the
-- correct state for a typed address ("Philadelphia, PA, USA" -> PA), so the
-- client now stores it in geocoded_state and the trigger trusts that first,
-- falling back to resolve_state_code only when it's absent (e.g. raw GPS).
--
-- geocoded_state is preserved once set (a later write that omits it keeps the
-- existing value), so it can't get clobbered by a Home-screen profile write.
-- Additive only.
-- ===========================================================================
alter table public.user_profiles add column if not exists geocoded_state text;

create or replace function public.set_home_state()
returns trigger
language plpgsql
as $$
begin
  -- Keep an already-set geocoded_state if this write doesn't supply one.
  if tg_op = 'UPDATE' then
    new.geocoded_state := coalesce(nullif(new.geocoded_state, ''), nullif(old.geocoded_state, ''));
  end if;

  new.home_state := coalesce(
    nullif(new.geocoded_state, ''),                                   -- authoritative: the geocoder's state
    public.resolve_state_code(                                        -- fallback: derive from coords/city
      coalesce(new.hometown_lat, new.user_lat, new.manual_lat),
      coalesce(new.hometown_lng, new.user_lng, new.manual_lng),
      coalesce(new.hometown_name, new.city)
    ),
    new.home_state
  );
  return new;
end;
$$;
