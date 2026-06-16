-- Leaderboard privacy hardening (review: SEC must-fix).
--
-- The first cut of get_leaderboard returned every other user's raw auth
-- user_id inside `rows` to all clients — an unnecessary cross-user UUID
-- exposure / enumeration surface. Replace the function so the ranked rows carry
-- only coarse public fields plus an `is_me` boolean the client uses to highlight
-- the caller's own row. The raw id is kept ONLY on the caller's own `me` object
-- (their own id, returned to themselves).
--
-- CREATE OR REPLACE only — same signature (uuid, text) → jsonb. Backward-compatible:
-- the client is updated in lockstep and tolerates the missing user_id on rows.

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
                 'username',   s.username,
                 'joined_at',  s.created_at,
                 'fomo_total', s.fomo_total,
                 'is_me',      s.id = p_user_id)   -- no raw user_id cross-user
               order by s.rank)
        from scoped s
       where s.rank <= 10), '[]'::jsonb),
    'me', (
      select jsonb_build_object(
               'rank',       s.rank,
               'user_id',    s.id,                  -- caller's own id, to themselves
               'username',   s.username,
               'joined_at',  s.created_at,
               'fomo_total', s.fomo_total,
               'in_top_100', s.rank <= 100,
               'is_me',      true)
        from scoped s
       where s.id = p_user_id)
  );
$$;

comment on function public.get_leaderboard is
  'Local Leaderboard: ranks users in a home-location scope (city|state|country) by global fomo_total. Returns top 10 + caller row + in_top_100. SECURITY DEFINER; rows expose only coarse public fields + is_me (no cross-user user_id, no hometown coords).';
