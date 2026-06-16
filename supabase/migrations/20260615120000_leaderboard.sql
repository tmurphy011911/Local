-- Local Leaderboard — backend foundation.
--
-- Ranks users by a single GLOBAL "FOMO total" (sum of all their user_badges
-- points, regardless of which state earned them). The leaderboard FILTERS users
-- by their home location (city / state / country) and ranks the filtered set by
-- that one global total. So a user who earned 100 FOMO in PA and later calls MA
-- home is ranked on the MA board with the full 100 — points travel with the user,
-- they are NOT re-segmented by where they were earned. (`user_badges.state` stays
-- pure provenance; it plays no part in ranking.)
--
-- Additive-only / backward-compatible (RELEASE.md): new nullable/defaulted
-- columns, new indexes, new functions + triggers. No DROP/RENAME/retype. Safe to
-- promote dev -> prod while older clients (which never read these) keep working.
--
-- v1 scope: home location is the IMMUTABLE onboarding hometown. The "change home
-- city" flow (1x/year, place_id, rate limit) is deliberately deferred to v2, so
-- this migration does NOT touch guard_hometown_write.

-- ---------------------------------------------------------------------------
-- 1. Denormalized columns on user_profiles.
--    fomo_total : running global point total, maintained by trigger (below).
--    home_state : state code for the user's home location, derived once from the
--                 hometown anchor. Stored (not computed per query) so the state
--                 board ranks off an index. Effectively immutable in v1 because
--                 the hometown it derives from is write-once.
-- ---------------------------------------------------------------------------
alter table public.user_profiles
  add column if not exists fomo_total int  not null default 0,
  add column if not exists home_state text;

-- ---------------------------------------------------------------------------
-- 2. resolve_state_code(): single source of truth for "coords/city -> state",
--    mirroring the client-side resolveUserState (city->metro->state first, then
--    a bounding-box fallback that prefers live states, then nearest bbox center).
--    Used by both the home_state trigger and the backfill so they never drift.
-- ---------------------------------------------------------------------------
create or replace function public.resolve_state_code(
  p_lat  double precision,
  p_lng  double precision,
  p_city text
)
returns text
language sql
stable
as $$
  select coalesce(
    -- Path 1: home city name -> metro -> state_code
    (select m.state_code
       from public.metros m
      where p_city is not null
        and m.name ilike p_city
      limit 1),
    -- Path 2: coordinate bounding box; prefer live states, then nearest center
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

-- ---------------------------------------------------------------------------
-- 3. home_state trigger: derive home_state from the hometown anchor on the first
--    write that establishes it, and never clobber an already-set value (matches
--    the write-once spirit of guard_hometown_write). Fires BEFORE so it can set
--    NEW.home_state inline. Trigger name sorts AFTER trg_guard_hometown_write
--    ('s' > 'g'), so the guard has already pinned NEW.hometown_* by the time we
--    read them.
-- ---------------------------------------------------------------------------
create or replace function public.set_home_state()
returns trigger
language plpgsql
as $$
begin
  if new.home_state is null then
    new.home_state := public.resolve_state_code(
      coalesce(new.hometown_lat,  new.user_lat,  new.manual_lat),
      coalesce(new.hometown_lng,  new.user_lng,  new.manual_lng),
      coalesce(new.hometown_name, new.city)
    );
  end if;
  return new;
end;
$$;

drop trigger if exists trg_set_home_state on public.user_profiles;
create trigger trg_set_home_state
  before insert or update on public.user_profiles
  for each row execute function public.set_home_state();

-- ---------------------------------------------------------------------------
-- 4. fomo_total trigger: keep the denormalized total in lockstep with
--    user_badges by applying the row delta. The badge system is insert-only in
--    practice, but UPDATE/DELETE are handled too so the total can never drift.
-- ---------------------------------------------------------------------------
create or replace function public.sync_fomo_total()
returns trigger
language plpgsql
as $$
begin
  if tg_op = 'INSERT' then
    update public.user_profiles
       set fomo_total = fomo_total + new.points
     where id = new.user_id;
  elsif tg_op = 'DELETE' then
    update public.user_profiles
       set fomo_total = greatest(0, fomo_total - old.points)
     where id = old.user_id;
  elsif tg_op = 'UPDATE' then
    update public.user_profiles
       set fomo_total = greatest(0, fomo_total - old.points + new.points)
     where id = new.user_id;
  end if;
  return null;
end;
$$;

drop trigger if exists trg_sync_fomo_total on public.user_badges;
create trigger trg_sync_fomo_total
  after insert or update or delete on public.user_badges
  for each row execute function public.sync_fomo_total();

-- ---------------------------------------------------------------------------
-- 5. One-time backfill for rows that predate the columns/triggers. Pre-launch
--    this is near-empty; written defensively for whatever exists.
-- ---------------------------------------------------------------------------
update public.user_profiles up
   set fomo_total = coalesce(
         (select sum(b.points) from public.user_badges b where b.user_id = up.id), 0);

update public.user_profiles up
   set home_state = public.resolve_state_code(
         coalesce(up.hometown_lat,  up.user_lat,  up.manual_lat),
         coalesce(up.hometown_lng,  up.user_lng,  up.manual_lng),
         coalesce(up.hometown_name, up.city))
 where up.home_state is null;

-- ---------------------------------------------------------------------------
-- 6. Indexes to make per-scope rank lookups index-only rather than full scans.
--    Ordering matches the leaderboard sort (fomo desc, then join date asc tiebreak).
-- ---------------------------------------------------------------------------
create index if not exists user_profiles_home_state_fomo_idx
  on public.user_profiles (home_state, fomo_total desc, created_at asc)
  where username is not null;

create index if not exists user_profiles_hometown_fomo_idx
  on public.user_profiles (lower(hometown_name), fomo_total desc, created_at asc)
  where username is not null;

create index if not exists user_profiles_fomo_idx
  on public.user_profiles (fomo_total desc, created_at asc)
  where username is not null;

-- ---------------------------------------------------------------------------
-- 7. get_leaderboard(): one call returns everything a scope view needs —
--    top 10 rows, the caller's own rank/row, total population, and an
--    in_top_100 flag (drives the home CTA copy variant). Returns jsonb so the
--    list + the caller's row + flags ride in a single round trip.
--
--    The caller passes only their id + the scope; the function resolves the
--    caller's OWN home city / state from their profile and filters to it. So
--    the client never needs to know its state code, and the returned `location`
--    is a display-ready label ("Massachusetts" / "Boston" / "United States")
--    for the header + CTA.
--
--    SECURITY DEFINER: a leaderboard is inherently cross-user, but user_profiles
--    carries PII (hometown_lat/lng). Definer rights let the ranking see every
--    row WHILE this function projects ONLY coarse, public fields
--    (id, username, join date, fomo_total) — never coordinates. Same
--    PII-stripping pattern as the BI SECURITY DEFINER views.
--
--    `level` is intentionally NOT computed here: the client derives it from
--    fomo_total via statusTierForPoints so the tier thresholds live in exactly
--    one place (Badge.kt) and can't drift between SQL and app.
-- ---------------------------------------------------------------------------
create or replace function public.get_leaderboard(
  p_user_id uuid,
  p_scope   text
)
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
  with me_profile as (
    select up.home_state, up.hometown_name
      from public.user_profiles up
     where up.id = p_user_id
  ),
  scoped as (
    select up.id,
           up.username,
           up.created_at,
           up.fomo_total,
           row_number() over (order by up.fomo_total desc, up.created_at asc) as rank
      from public.user_profiles up, me_profile mp
     where up.username is not null
       and ( p_scope = 'country'
          or (p_scope = 'state' and up.home_state = mp.home_state)
          or (p_scope = 'city'  and lower(up.hometown_name) = lower(mp.hometown_name)) )
  )
  select jsonb_build_object(
    'scope', p_scope,
    'location', case p_scope
        when 'state' then (select s.name from public.states s, me_profile mp where s.code = mp.home_state)
        when 'city'  then (select mp.hometown_name from me_profile mp)
        else 'United States' end,
    'location_code', case p_scope
        when 'state' then (select mp.home_state from me_profile mp)
        when 'city'  then (select mp.hometown_name from me_profile mp)
        else 'US' end,
    'total_users', (select count(*) from scoped),
    'rows', coalesce((
      select jsonb_agg(
               jsonb_build_object(
                 'rank',       s.rank,
                 'user_id',    s.id,
                 'username',   s.username,
                 'joined_at',  s.created_at,
                 'fomo_total', s.fomo_total)
               order by s.rank)
        from scoped s
       where s.rank <= 10), '[]'::jsonb),
    'me', (
      select jsonb_build_object(
               'rank',       s.rank,
               'user_id',    s.id,
               'username',   s.username,
               'joined_at',  s.created_at,
               'fomo_total', s.fomo_total,
               'in_top_100', s.rank <= 100)
        from scoped s
       where s.id = p_user_id)
  );
$$;

grant execute on function public.resolve_state_code  to anon, authenticated, service_role;
grant execute on function public.get_leaderboard      to anon, authenticated, service_role;

comment on function public.get_leaderboard is
  'Local Leaderboard: ranks users in a home-location scope (city|state|country) by global fomo_total. Returns top 10 + caller row + in_top_100. SECURITY DEFINER but projects only coarse public fields (no hometown coords).';
