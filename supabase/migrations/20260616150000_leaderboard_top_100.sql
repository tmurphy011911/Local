-- Leaderboard: return up to top 100 ranked rows (was top 10). The window sort
-- already orders the whole scoped population every call (for total_users + the
-- caller's rank), so widening the returned slice from 10 → 100 only enlarges the
-- jsonb array — no new sort/scan cost. Client renders the list lazily.
--
-- CREATE OR REPLACE only — identical signature; just the rows `where` bound.

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
           up.hometown_name,
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
