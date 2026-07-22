-- ===========================================================================
-- Fixed-denominator cumulative retention (comparable survival curves).
-- Only include users old enough that EVERY shown offset has fully elapsed, so
-- the denominator is constant across offsets and the curve is monotonic and
-- directly comparable week-to-week / month-to-month / day-to-day.
--   weekly : users >= 4 weeks old,  offsets week 1..4
--   daily  : users >= 7 days old,   offsets day  1..7
--   monthly: users >= 3 months old, offsets month 1..2
-- "Retained to offset N" = returned in that period N or any later period.
-- Return signal = auth.sessions + engagement (quiz/plan/interaction). Jun-1 base.
-- ===========================================================================

-- ── WEEKLY (>=4 weeks old, weeks 1-4) ──────────────────────────────────────
create or replace view public.bi_retention_weekly_fixed as
with base as (
  select id as user_id, date_trunc('week', created_at)::date as anchor
  from public.user_profiles
  where created_at >= date '2026-06-01' and created_at <= now() - interval '4 weeks'
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
  select b.user_id, max(((a.active - b.anchor) / 7)::int) as max_off
  from base b left join activity a on a.user_id = b.user_id and a.active > b.anchor
  group by b.user_id
),
tot as (select count(*)::int as n from base)
select w.n as week_number,
       (select n from tot) as base_users,
       count(*) filter (where r.max_off >= w.n)::int as retained,
       round(100.0 * count(*) filter (where r.max_off >= w.n) / nullif((select n from tot), 0), 1) as retention_pct
from reach r cross join generate_series(1, 4) as w(n)
group by w.n order by w.n;

-- ── DAILY (>=7 days old, days 1-7) ─────────────────────────────────────────
create or replace view public.bi_retention_daily_fixed as
with base as (
  select id as user_id, created_at::date as anchor
  from public.user_profiles
  where created_at >= date '2026-06-01' and created_at <= now() - interval '7 days'
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
  select b.user_id, max((a.active - b.anchor)::int) as max_off
  from base b left join activity a on a.user_id = b.user_id and a.active > b.anchor
  group by b.user_id
),
tot as (select count(*)::int as n from base)
select d.n as day_number,
       (select n from tot) as base_users,
       count(*) filter (where r.max_off >= d.n)::int as retained,
       round(100.0 * count(*) filter (where r.max_off >= d.n) / nullif((select n from tot), 0), 1) as retention_pct
from reach r cross join generate_series(1, 7) as d(n)
group by d.n order by d.n;

-- ── MONTHLY (>=3 months old, months 1-2) ───────────────────────────────────
create or replace view public.bi_retention_monthly_fixed as
with base as (
  select id as user_id, date_trunc('month', created_at)::date as anchor
  from public.user_profiles
  where created_at >= date '2026-06-01' and created_at <= now() - interval '3 months'
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
  select b.user_id,
         max(((extract(year from a.active) - extract(year from b.anchor)) * 12
            + (extract(month from a.active) - extract(month from b.anchor)))::int) as max_off
  from base b left join activity a on a.user_id = b.user_id and a.active > b.anchor
  group by b.user_id
),
tot as (select count(*)::int as n from base)
select m.n as month_number,
       (select n from tot) as base_users,
       count(*) filter (where r.max_off >= m.n)::int as retained,
       round(100.0 * count(*) filter (where r.max_off >= m.n) / nullif((select n from tot), 0), 1) as retention_pct
from reach r cross join generate_series(1, 2) as m(n)
group by m.n order by m.n;

grant select on public.bi_retention_weekly_fixed  to bi_readonly;
grant select on public.bi_retention_daily_fixed    to bi_readonly;
grant select on public.bi_retention_monthly_fixed  to bi_readonly;
