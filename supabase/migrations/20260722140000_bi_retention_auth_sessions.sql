-- ===========================================================================
-- Retention fix: auth.audit_log_entries is empty on prod, so switch the
-- "returned" signal to auth.sessions (login create + last refresh = app
-- activity) UNION the permanent engagement rows (quiz/plan/interaction, never
-- pruned). Together = the broadest, most complete "opened the app" signal here.
-- Cumulative retention only needs each user's furthest activity week, so
-- session endpoints are sufficient even though sessions aren't a full timeline.
-- CREATE OR REPLACE — same output columns, so dependents (weekly_avg) still work.
-- ===========================================================================

create or replace view public.bi_retention_cohort_weekly as
with signup as (
  select id as user_id,
         date_trunc('week', created_at)::date as cohort_week
  from public.user_profiles
  where created_at >= date '2026-06-01'
),
activity as (
  select user_id, date_trunc('week', ts)::date as active_week
  from (
    select user_id::uuid as user_id, created_at   as ts from auth.sessions
    union all
    select user_id::uuid,             updated_at   as ts from auth.sessions
    union all
    select user_id::uuid,             completed_at as ts from public.quiz_completions
    union all
    select user_id::uuid,             created_at   as ts from public.community_interactions
    union all
    select user_id::uuid,             created_at   as ts from public.plans
  ) e
  where user_id is not null and ts is not null
),
user_reach as (
  select s.user_id, s.cohort_week,
         max(((a.active_week - s.cohort_week) / 7)::int) as max_week
  from signup s
  left join activity a
    on a.user_id = s.user_id
   and a.active_week > s.cohort_week
  group by s.user_id, s.cohort_week
),
cohort_sizes as (
  select cohort_week, count(*)::int as cohort_size
  from signup group by cohort_week
),
grid as (
  select c.cohort_week, w.week_number, c.cohort_size
  from cohort_sizes c
  cross join generate_series(1, 12) as w(week_number)
)
select g.cohort_week,
       g.week_number,
       g.cohort_size,
       case when g.cohort_week + (g.week_number * 7) <= date_trunc('week', now())::date
            then count(ur.user_id) filter (where ur.max_week >= g.week_number)::int
       end as retained_users,
       case when g.cohort_week + (g.week_number * 7) <= date_trunc('week', now())::date
            then round(100.0 * count(ur.user_id) filter (where ur.max_week >= g.week_number)
                       / nullif(g.cohort_size, 0), 1)
       end as retention_pct
from grid g
join user_reach ur on ur.cohort_week = g.cohort_week
group by g.cohort_week, g.week_number, g.cohort_size
order by g.cohort_week, g.week_number;

-- Session-based activity span (audit log is empty), so we know the trust window.
create or replace view public.bi_auth_activity_span as
select min(created_at) as earliest_event,
       max(coalesce(updated_at, created_at)) as latest_event,
       count(*) as total_events
from auth.sessions;

grant select on public.bi_retention_cohort_weekly to bi_readonly;
grant select on public.bi_auth_activity_span      to bi_readonly;
