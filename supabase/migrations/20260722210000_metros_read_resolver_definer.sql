-- ===========================================================================
-- Fix home_state resolution for app users: un-blind the metros name path.
-- ---------------------------------------------------------------------------
-- ROOT CAUSE (hotfix 1.2.1, bug 1 — Daily Local scored 0/0 with no FOMO):
-- `metros` has RLS enabled with a SELECT policy for `bi_readonly` ONLY, and
-- `resolve_state_code` runs SECURITY INVOKER. So inside any client-context
-- profile write (the set_home_state trigger fires as `authenticated`), Path 1
-- (city name -> metro -> state) silently returns nothing and resolution falls
-- through to the coordinate bbox guess — which mis-picks neighbouring states
-- for border/stale coords. Meanwhile get_daily_quiz/submit_quiz are SECURITY
-- DEFINER and DO see metros, so the same profile resolves to DIFFERENT states
-- depending on which context asked. A user whose profile PATCH raced the quiz
-- serve got served state A's questions and graded against state B's set —
-- every answer dropped, 0/0 recorded. 8/45 prod profiles carry a wrong
-- home_state from this. Verified on prod:
--   set role authenticated:  resolve_state_code(41.2388,-73.1288,'Springfield') -> NY
--   as definer/postgres:     same call                                          -> MA
--
-- Two-part fix, both additive:
--   1. Grant app roles SELECT on metros (public reference data: name/state/geo).
--   2. Make resolve_state_code SECURITY DEFINER so it is context-independent
--      everywhere it's called (triggers, RPCs, future callers), regardless of
--      the caller's RLS view.
-- ===========================================================================

-- 1. metros: readable by app roles. Idempotent (CREATE POLICY has no
--    IF NOT EXISTS, so guard via pg_policies).
do $$
begin
  if not exists (
    select 1 from pg_policies
     where schemaname = 'public' and tablename = 'metros'
       and policyname = 'metros_read_app'
  ) then
    create policy metros_read_app on public.metros
      for select to anon, authenticated using (true);
  end if;
end $$;

-- 2. resolve_state_code: same body/signature as deployed (geocode-formatted
--    city match + interior-preferring bbox), now SECURITY DEFINER.
create or replace function public.resolve_state_code(
  p_lat double precision,
  p_lng double precision,
  p_city text
)
returns text
language sql
stable
security definer
set search_path = public
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
