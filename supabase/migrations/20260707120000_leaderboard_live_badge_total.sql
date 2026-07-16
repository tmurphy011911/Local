-- Leaderboard FOMO now computed live from user_badges instead of reading the
-- denormalized user_profiles.fomo_total column.
--
-- Why: fomo_total is maintained by a delta trigger (sync_fomo_total: on each
-- badge insert, `update user_profiles set fomo_total = fomo_total + new.points
-- where id = new.user_id`). Any badge inserted while the profile row didn't yet
-- exist, or bulk-loaded with triggers disabled (e.g. a data sync), silently
-- updated 0 rows — so those points are lost from fomo_total forever. The result
-- drifted BELOW the true total for some users. The Progress screen sums the
-- badge rows directly, so a user's own board number disagreed with their
-- profile (fscanlon: 1,100 on profile, 250 on the board, and stable across app
-- restarts — a server-data issue, not a cache).
--
-- Fix: rank + display off a live sum of user_badges, deduped by
-- (action_type, milestone) to exactly mirror the profile's own dedup (a
-- milestone earned in two states counts once). This is drift-proof and
-- self-correcting for every user at once — no backfill needed.
--
-- Additive / backward-compatible: CREATE OR REPLACE, identical signature and
-- JSON response shape (still returns a `fomo_total` field per row, just sourced
-- correctly). The fomo_total column + its trigger are left in place (now
-- vestigial for the board) rather than dropped, so nothing destructive lands
-- and older clients are unaffected.
--
-- Perf note: this aggregates user_badges per call. Fine at current scale; if the
-- board becomes a hot path at volume, reintroduce a correctly-maintained
-- denormalized total or a materialized view (cf. mv_carousel_payload).

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
  with badge_totals as (
    -- Deduped by (user, action_type, milestone) to match the profile total.
    select user_id, sum(points) as fomo_total
      from (
        select distinct on (user_id, action_type, milestone)
               user_id, action_type, milestone, points
          from public.user_badges
      ) d
     group by user_id
  ),
  me_profile as (
    select up.home_state, up.hometown_name
      from public.user_profiles up
     where up.id = p_user_id
  ),
  scoped as (
    select up.id,
           up.username,
           up.created_at,
           coalesce(bt.fomo_total, 0) as fomo_total,
           up.hometown_name,
           row_number() over (order by coalesce(bt.fomo_total, 0) desc, up.created_at asc) as rank
      from public.user_profiles up
      cross join me_profile mp
      left join badge_totals bt on bt.user_id = up.id
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
                 'username',   s.username,
                 'joined_at',  s.created_at,
                 'fomo_total', s.fomo_total,
                 'city',       s.hometown_name,
                 'is_me',      s.id = p_user_id)
               order by s.rank)
        from scoped s
       where s.rank <= 100), '[]'::jsonb),
    'me', (
      select jsonb_build_object(
               'rank',       s.rank,
               'user_id',    s.id,
               'username',   s.username,
               'joined_at',  s.created_at,
               'fomo_total', s.fomo_total,
               'city',       s.hometown_name,
               'in_top_100', s.rank <= 100,
               'is_me',      true)
        from scoped s
       where s.id = p_user_id)
  );
$$;
