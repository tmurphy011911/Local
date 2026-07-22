create or replace view public.bi_weekly_signal_debug as
with base as (
  select id as user_id, date_trunc('week', created_at)::date as anchor
  from public.user_profiles
  where created_at >= date '2026-06-01' and created_at <= now() - interval '4 weeks'
),
sc as (select b.user_id, max(((date_trunc('week',s.created_at)::date - b.anchor)/7)::int) mo
       from base b join auth.sessions s on s.user_id=b.user_id and date_trunc('week',s.created_at)::date > b.anchor group by b.user_id),
su as (select b.user_id, max(((date_trunc('week',s.updated_at)::date - b.anchor)/7)::int) mo
       from base b join auth.sessions s on s.user_id=b.user_id and date_trunc('week',s.updated_at)::date > b.anchor group by b.user_id),
en as (select b.user_id, max(((date_trunc('week',e.ts)::date - b.anchor)/7)::int) mo
       from base b join (select user_id::uuid uid, completed_at ts from public.quiz_completions
                         union all select user_id::uuid, created_at from public.community_interactions
                         union all select user_id::uuid, created_at from public.plans) e(uid,ts)
                         on e.uid=b.user_id and date_trunc('week',e.ts)::date > b.anchor group by b.user_id)
select (select count(*) from base) base,
       (select count(distinct user_id) from sc) new_login_returns,
       (select count(*) from sc where mo>=3) new_login_w3,
       (select count(distinct user_id) from en) engagement_returns,
       (select count(*) from en where mo>=3) engagement_w3,
       (select count(*) from su where mo>=3) sessupdated_w3;
grant select on public.bi_weekly_signal_debug to bi_readonly;
