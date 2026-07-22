-- ===========================================================================
-- Monthly cumulative retention (mirror of the weekly views). "Retained to
-- month N" = returned in month N or any later month. Same return signal
-- (auth.sessions + engagement), same Jun-1 signup base.
-- ===========================================================================

create or replace view public.bi_retention_cohort_monthly as
with signup as (
  select id as user_id, date_trunc('month', created_at)::date as cohort_month
  from public.user_profiles
  where created_at >= date '2026-06-01'
),
activity as (
  select user_id, date_trunc('month', ts)::date as active_month
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
  select s.user_id, s.cohort_month,
         max( ((extract(year from a.active_month) - extract(year from s.cohort_month)) * 12
             + (extract(month from a.active_month) - extract(month from s.cohort_month)))::int ) as max_month
  from signup s
  left join activity a
    on a.user_id = s.user_id
   and a.active_month > s.cohort_month
  group by s.user_id, s.cohort_month
),
cohort_sizes as (
  select cohort_month, count(*)::int as cohort_size from signup group by cohort_month
),
grid as (
  select c.cohort_month, m.month_number, c.cohort_size
  from cohort_sizes c
  cross join generate_series(1, 12) as m(month_number)
)
select g.cohort_month,
       g.month_number,
       g.cohort_size,
       case when (g.cohort_month + make_interval(months => g.month_number))::date <= date_trunc('month', now())::date
            then count(ur.user_id) filter (where ur.max_month >= g.month_number)::int
       end as retained_users,
       case when (g.cohort_month + make_interval(months => g.month_number))::date <= date_trunc('month', now())::date
            then round(100.0 * count(ur.user_id) filter (where ur.max_month >= g.month_number)
                       / nullif(g.cohort_size, 0), 1)
       end as retention_pct
from grid g
join user_reach ur on ur.cohort_month = g.cohort_month
group by g.cohort_month, g.month_number, g.cohort_size
order by g.cohort_month, g.month_number;

create or replace view public.bi_retention_monthly_avg as
select month_number,
       count(*)::int            as cohorts,
       sum(cohort_size)::int     as total_users,
       sum(retained_users)::int  as total_retained,
       round(100.0 * sum(retained_users) / nullif(sum(cohort_size), 0), 1) as avg_retention_pct
from public.bi_retention_cohort_monthly
where retained_users is not null
group by month_number
order by month_number;

grant select on public.bi_retention_cohort_monthly to bi_readonly;
grant select on public.bi_retention_monthly_avg    to bi_readonly;
