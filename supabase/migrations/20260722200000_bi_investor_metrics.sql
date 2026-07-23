-- ===========================================================================
-- Investor dashboard metrics: acquisition, engagement, conversion-to-paid.
-- "Active" = any of: auth.sessions (login/refresh), quiz_completions, plans,
-- community_interactions — the same broad "used the app" signal as retention.
-- SECURITY DEFINER chain (owner reads auth); granted to bi_readonly.
-- ===========================================================================

-- ── ACQUISITION ────────────────────────────────────────────────────────────
create or replace view public.bi_signups_weekly as
with w as (
  select date_trunc('week', created_at)::date as wk, count(*)::int as new_users
  from public.user_profiles where created_at is not null group by 1
)
select wk, new_users, sum(new_users) over (order by wk)::int as cumulative_users
from w order by wk;

create or replace view public.bi_signups_daily as
select date_trunc('day', created_at)::date as day, count(*)::int as new_users
from public.user_profiles where created_at is not null group by 1 order by 1;

create or replace view public.bi_acq_summary as
select count(*)::int as total_users,
       count(*) filter (where created_at >= now() - interval '7 days')::int  as signups_last_7d,
       count(*) filter (where created_at >= now() - interval '14 days'
                          and created_at <  now() - interval '7 days')::int  as signups_prev_7d,
       count(*) filter (where preferences_completed)::int                     as activated,
       round(100.0 * count(*) filter (where preferences_completed)
             / nullif(count(*),0),1)                                          as activated_pct
from public.user_profiles;

-- ── ENGAGEMENT ─────────────────────────────────────────────────────────────
create or replace view public.bi_active_weekly as
with act as (
  select user_id::uuid as uid, date_trunc('week', ts)::date as wk
  from (
    select user_id::uuid as user_id, created_at as ts from auth.sessions
    union all select user_id::uuid, updated_at from auth.sessions
    union all select user_id::uuid, completed_at from public.quiz_completions
    union all select user_id::uuid, created_at from public.community_interactions
    union all select user_id::uuid, created_at from public.plans
  ) e where user_id is not null and ts is not null
)
select wk, count(distinct uid)::int as wau from act group by wk order by wk;

create or replace view public.bi_active_daily as
with act as (
  select user_id::uuid as uid, ts::date as d
  from (
    select user_id::uuid as user_id, created_at as ts from auth.sessions
    union all select user_id::uuid, updated_at from auth.sessions
    union all select user_id::uuid, completed_at from public.quiz_completions
    union all select user_id::uuid, created_at from public.community_interactions
    union all select user_id::uuid, created_at from public.plans
  ) e where user_id is not null and ts is not null
)
select d, count(distinct uid)::int as dau from act group by d order by d;

create or replace view public.bi_quiz_daily as
select quiz_date as day, count(distinct user_id)::int as players, count(*)::int as completions
from public.quiz_completions group by 1 order by 1;

create or replace view public.bi_eng_summary as
with act as (
  select user_id::uuid as uid, ts::date as d
  from (
    select user_id::uuid as user_id, created_at as ts from auth.sessions
    union all select user_id::uuid, updated_at from auth.sessions
    union all select user_id::uuid, completed_at from public.quiz_completions
    union all select user_id::uuid, created_at from public.community_interactions
    union all select user_id::uuid, created_at from public.plans
  ) e where user_id is not null and ts is not null
)
select count(distinct uid) filter (where d >= (now() - interval '7 days')::date)::int  as wau_7d,
       count(distinct uid) filter (where d >= (now() - interval '30 days')::date)::int as mau_30d,
       count(distinct uid) filter (where d >= (now() - interval '1 day')::date)::int   as dau_today,
       round(100.0 * count(distinct uid) filter (where d >= (now() - interval '7 days')::date)
             / nullif(count(distinct uid) filter (where d >= (now() - interval '30 days')::date),0),1) as stickiness_wau_over_mau_pct
from act;

-- ── CONVERSION TO PAID ─────────────────────────────────────────────────────
-- is_pro is synced from RevenueCat via rc-webhook. Everyone gets a trial
-- (trial_ends_at set at signup), so the funnel is: signup -> activated ->
-- (in trial | trial expired) -> paid.
create or replace view public.bi_paid_funnel as
select count(*)::int                                                              as total_users,
       count(*) filter (where preferences_completed)::int                          as activated,
       count(*) filter (where not is_pro and trial_ends_at > now())::int           as in_trial,
       count(*) filter (where not is_pro and trial_ends_at <= now())::int          as trial_expired_free,
       count(*) filter (where is_pro)::int                                         as paid,
       round(100.0 * count(*) filter (where is_pro) / nullif(count(*),0),1)         as paid_pct_of_all,
       round(100.0 * count(*) filter (where is_pro)
             / nullif(count(*) filter (where is_pro
                                        or (not is_pro and trial_ends_at <= now())),0),1) as paid_pct_of_matured
from public.user_profiles;

grant select on public.bi_signups_weekly, public.bi_signups_daily, public.bi_acq_summary to bi_readonly;
grant select on public.bi_active_weekly, public.bi_active_daily, public.bi_quiz_daily, public.bi_eng_summary to bi_readonly;
grant select on public.bi_paid_funnel to bi_readonly;
