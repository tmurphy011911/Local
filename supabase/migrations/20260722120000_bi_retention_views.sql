-- ===========================================================================
-- BI: weekly CUMULATIVE (survival) retention for Metabase.
-- ---------------------------------------------------------------------------
-- Definition (per product spec): a user is "retained at week N" if they
-- returned — did ANY engagement action — in week N OR ANY LATER week. So a
-- user who first comes back in week 3 counts as retained for weeks 1, 2, AND 3.
-- Equivalent to: max(weeks-since-signup over all post-signup activity) >= N.
-- This yields a monotonically non-increasing survival curve per cohort.
--
-- Cohorts are weekly, anchored on signup week (user_profiles.created_at).
-- "Return / active" = a row in any user-initiated engagement table that week:
--   quiz_completions (completed_at), community_interactions (created_at),
--   plans (created_at). Signup-week activity is excluded (returns only).
--
-- Two views:
--   bi_retention_cohort_weekly  — per-cohort grid (Metabase pivots this)
--   bi_retention_weekly_avg     — size-weighted average across all cohorts
--
-- Cells/cohorts where week N hasn't fully elapsed yet are excluded/NULLed so we
-- never average in cohorts that couldn't have returned yet (a proper cohort
-- triangle). NOTE: cumulative retention is an as-of-now lower bound — a late
-- return retroactively lifts earlier weeks, so recent weeks fill in over time.
--
-- Owned by the migration role (bypasses RLS) so bi_readonly can read the
-- aggregate rows without row access to the base tables. Additive.
-- ===========================================================================

create or replace view public.bi_retention_cohort_weekly as
with signup as (
  -- Real authenticated signups (clean base) — only those on/after Jun 1 2026.
  select id as user_id,
         date_trunc('week', created_at)::date as cohort_week
  from public.user_profiles
  where created_at >= date '2026-06-01'
),
activity as (
  -- "returned to the app" = an auth session event (login or token refresh).
  -- This is the broadest DB proxy for "opened the app" — it catches users who
  -- came back and only browsed (event/plan VIEWS never hit the DB). The user is
  -- in payload.actor_id; NOTE: bounded by the auth audit-log retention window
  -- (see bi_auth_activity_span below for the usable date range).
  select (payload->>'actor_id')::uuid as user_id,
         date_trunc('week', created_at)::date as active_week
  from auth.audit_log_entries
  where payload->>'action' in ('login', 'token_refreshed')
    and payload->>'actor_id' is not null
),
user_reach as (
  -- furthest week (in weeks after signup week) each user was ever active;
  -- NULL when the user never returned after their signup week.
  select s.user_id,
         s.cohort_week,
         max(((a.active_week - s.cohort_week) / 7)::int) as max_week
  from signup s
  left join activity a
    on a.user_id = s.user_id
   and a.active_week > s.cohort_week
  group by s.user_id, s.cohort_week
),
cohort_sizes as (
  select cohort_week, count(*)::int as cohort_size
  from signup
  group by cohort_week
),
grid as (
  select c.cohort_week, w.week_number, c.cohort_size
  from cohort_sizes c
  cross join generate_series(1, 12) as w(week_number)
)
select g.cohort_week,
       g.week_number,
       g.cohort_size,
       -- only report a cell once week N has fully elapsed for this cohort
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

comment on view public.bi_retention_cohort_weekly is
  'Weekly cumulative (survival) retention per signup-week cohort. retention_pct = % of cohort that returned in week N or any later week. NULL until week N has elapsed.';

create or replace view public.bi_retention_weekly_avg as
select week_number,
       count(*)::int            as cohorts,        -- cohorts mature enough for week N
       sum(cohort_size)::int     as total_users,
       sum(retained_users)::int  as total_retained,
       round(100.0 * sum(retained_users) / nullif(sum(cohort_size), 0), 1) as avg_retention_pct
from public.bi_retention_cohort_weekly
where retained_users is not null    -- excludes not-yet-elapsed cells
group by week_number
order by week_number;

comment on view public.bi_retention_weekly_avg is
  'Size-weighted average cumulative retention across all mature cohorts, per week-since-signup.';

-- How far back the auth audit log actually goes — cohorts before earliest_event
-- can't be trusted (their early returns were pruned). Check this in Metabase.
create or replace view public.bi_auth_activity_span as
select min(created_at) as earliest_event,
       max(created_at) as latest_event,
       count(*)        as total_events
from auth.audit_log_entries
where payload->>'action' in ('login', 'token_refreshed');

comment on view public.bi_auth_activity_span is
  'Date span + count of the auth audit log used for retention. Cohorts before earliest_event are unreliable (older activity pruned).';

grant select on public.bi_retention_cohort_weekly to bi_readonly;
grant select on public.bi_retention_weekly_avg    to bi_readonly;
grant select on public.bi_auth_activity_span      to bi_readonly;
