-- Leaderboard consistency fix: home_state must be a pure function of the user's
-- HOMETOWN (the same anchor the city-scope board groups on), never of their
-- current GPS location.
--
-- Bug it fixes: the first cut derived home_state from coalesce(hometown, user,
-- manual) coords + current city. A user whose hometown is Waltham but who is
-- currently in Columbia SC (hometown_lat null, user_lat = SC) resolved to
-- home_state = null — so they showed in the WALTHAM city board (keyed on
-- hometown_name) but NOT the MASSACHUSETTS state board (keyed on home_state).
-- City ⊄ State. Wrong.
--
-- Structural fix: resolve home_state from hometown_name via the canonical
-- city→state map (geo_city_cache), then metros, then hometown coords — and from
-- NOTHING else. Because it's now a pure function of hometown_name, every user
-- sharing a hometown shares a home_state, so city ⊆ state ⊆ country holds by
-- construction. No per-user special-casing.

-- resolve_state_code: now SECURITY DEFINER (reads reference tables regardless of
-- the writing user's RLS) and consults geo_city_cache first. Same signature, so
-- this is an in-place replace — no drop.
create or replace function public.resolve_state_code(
  p_lat  double precision,
  p_lng  double precision,
  p_city text
)
returns text
language sql
stable
security definer
set search_path = public
as $$
  select coalesce(
    -- 1. canonical city -> state (geo_city_cache), preferring a live state
    (select gc.state
       from public.geo_city_cache gc
       join public.states s on s.code = gc.state
      where p_city is not null and lower(gc.city) = lower(p_city)
      order by s.is_live desc
      limit 1),
    -- 2. metro name -> state_code
    (select m.state_code
       from public.metros m
      where p_city is not null and m.name ilike p_city
      limit 1),
    -- 3. hometown coordinate bounding box (prefer live, then nearest center)
    (select s.code
       from public.states s
      where p_lat is not null and p_lng is not null
        and p_lat between s.lat_min and s.lat_max
        and p_lng between s.lng_min and s.lng_max
      order by s.is_live desc,
               ((p_lat - (s.lat_min + s.lat_max) / 2) ^ 2
              + (p_lng - (s.lng_min + s.lng_max) / 2) ^ 2) asc
      limit 1)
  );
$$;

-- set_home_state: derive from the HOMETOWN anchor only (name + hometown coords).
-- No user_lat / manual_lat / current city — those are "where I am now", not
-- "where I'm from", and mixing them is exactly what broke the invariant.
create or replace function public.set_home_state()
returns trigger
language plpgsql
as $$
begin
  if new.home_state is null then
    new.home_state := public.resolve_state_code(
      new.hometown_lat, new.hometown_lng, new.hometown_name);
  end if;
  return new;
end;
$$;

-- Re-derive home_state for every row that has a hometown, overwriting any value
-- the old coords-based logic produced. Bounded WHERE (rows with a hometown to
-- resolve from); rows without a hometown keep their existing (null) value.
update public.user_profiles
   set home_state = public.resolve_state_code(hometown_lat, hometown_lng, hometown_name)
 where hometown_name is not null
    or hometown_lat is not null;
