-- ===========================================================================
-- Classic cohort retention triangles: one row per signup cohort, cascading
-- retention across offsets. Cumulative survival (retained[N] = max return
-- offset >= N, or N=0 = 100%), per-cohort denominators. ALL Jun-1+ cohorts;
-- a cell is NULL when that cohort hasn't reached that offset yet (triangle).
--   bi_cohort_daily   : signup-day   cohorts x D0-D6
--   bi_cohort_weekly  : signup-week  cohorts x W0-W3
--   bi_cohort_monthly : signup-month cohorts x M0-M2
-- Return signal = auth.sessions + engagement. Pivot in Metabase (cohort x offset).
-- ===========================================================================

create or replace view public.bi_cohort_daily as
with signup as (
  select id as user_id, created_at::date as cohort
  from public.user_profiles where created_at >= date '2026-06-01'
),
activity as (
  select user_id, ts::date as active
  from (
    select user_id::uuid as user_id, created_at as ts from auth.sessions
    union all select user_id::uuid, updated_at   from auth.sessions
    union all select user_id::uuid, completed_at from public.quiz_completions
    union all select user_id::uuid, created_at   from public.community_interactions
    union all select user_id::uuid, created_at   from public.plans
  ) e where user_id is not null and ts is not null
),
reach as (
  select s.user_id, s.cohort, max((a.active - s.cohort)::int) as max_off
  from signup s left join activity a on a.user_id = s.user_id and a.active > s.cohort
  group by s.user_id, s.cohort
),
sizes as (select cohort, count(*)::int as n from signup group by cohort),
grid as (select c.cohort, d.n, c.n as cohort_size from sizes c cross join generate_series(0,6) as d(n))
select g.cohort as cohort_day, g.n as day_number, g.cohort_size,
       case when g.cohort + g.n <= current_date
            then round(100.0 * count(*) filter (where g.n = 0 or r.max_off >= g.n) / nullif(g.cohort_size,0),1)
       end as retention_pct
from grid g join reach r on r.cohort = g.cohort
group by g.cohort, g.n, g.cohort_size order by g.cohort, g.n;

create or replace view public.bi_cohort_weekly as
with signup as (
  select id as user_id, date_trunc('week', created_at)::date as cohort
  from public.user_profiles where created_at >= date '2026-06-01'
),
activity as (
  select user_id, date_trunc('week', ts)::date as active
  from (
    select user_id::uuid as user_id, created_at as ts from auth.sessions
    union all select user_id::uuid, updated_at   from auth.sessions
    union all select user_id::uuid, completed_at from public.quiz_completions
    union all select user_id::uuid, created_at   from public.community_interactions
    union all select user_id::uuid, created_at   from public.plans
  ) e where user_id is not null and ts is not null
),
reach as (
  select s.user_id, s.cohort, max(((a.active - s.cohort)/7)::int) as max_off
  from signup s left join activity a on a.user_id = s.user_id and a.active > s.cohort
  group by s.user_id, s.cohort
),
sizes as (select cohort, count(*)::int as n from signup group by cohort),
grid as (select c.cohort, w.n, c.n as cohort_size from sizes c cross join generate_series(0,3) as w(n))
select g.cohort as cohort_week, g.n as week_number, g.cohort_size,
       case when g.cohort + (g.n * 7) <= date_trunc('week', now())::date
            then round(100.0 * count(*) filter (where g.n = 0 or r.max_off >= g.n) / nullif(g.cohort_size,0),1)
       end as retention_pct
from grid g join reach r on r.cohort = g.cohort
group by g.cohort, g.n, g.cohort_size order by g.cohort, g.n;

create or replace view public.bi_cohort_monthly as
with signup as (
  select id as user_id, date_trunc('month', created_at)::date as cohort
  from public.user_profiles where created_at >= date '2026-06-01'
),
activity as (
  select user_id, date_trunc('month', ts)::date as active
  from (
    select user_id::uuid as user_id, created_at as ts from auth.sessions
    union all select user_id::uuid, updated_at   from auth.sessions
    union all select user_id::uuid, completed_at from public.quiz_completions
    union all select user_id::uuid, created_at   from public.community_interactions
    union all select user_id::uuid, created_at   from public.plans
  ) e where user_id is not null and ts is not null
),
reach as (
  select s.user_id, s.cohort,
         max(((extract(year from a.active) - extract(year from s.cohort)) * 12
            + (extract(month from a.active) - extract(month from s.cohort)))::int) as max_off
  from signup s left join activity a on a.user_id = s.user_id and a.active > s.cohort
  group by s.user_id, s.cohort
),
sizes as (select cohort, count(*)::int as n from signup group by cohort),
grid as (select c.cohort, m.n, c.n as cohort_size from sizes c cross join generate_series(0,2) as m(n))
select g.cohort as cohort_month, g.n as month_number, g.cohort_size,
       case when (g.cohort + make_interval(months => g.n))::date <= date_trunc('month', now())::date
            then round(100.0 * count(*) filter (where g.n = 0 or r.max_off >= g.n) / nullif(g.cohort_size,0),1)
       end as retention_pct
from grid g join reach r on r.cohort = g.cohort
group by g.cohort, g.n, g.cohort_size order by g.cohort, g.n;

grant select on public.bi_cohort_daily, public.bi_cohort_weekly, public.bi_cohort_monthly to bi_readonly;
