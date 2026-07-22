-- ===========================================================================
-- Retention, full rebuild: survival + activation, D0-D6 / W0-W3 / M0-M2, with
-- period 0 = 100% (everyone is present at signup). Fixed bases (constant
-- denominator): daily >=7d (base~33), weekly >=4wk (base~16), monthly >=3mo.
--
--   SURVIVAL   "still active at/after N" — retained[N] = max return offset >= N
--              (or N=0). DECREASES with N. day-7 >= week-4 on one base.
--   ACTIVATION "returned within their first N" — retained[N] = first return
--              offset <= N (or N=0). INCREASES with N. week-4 >= day-7.
--
-- Per-user first/last return offset lives in the bi_reach_* views (shared by
-- both metrics). Return signal = auth.sessions + engagement. Jun-1 base.
-- SECURITY DEFINER chain (owner reads auth); granted to bi_readonly.
-- ===========================================================================

-- ── Per-user reach (first + last post-signup return offset) ────────────────
create or replace view public.bi_reach_daily as
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
)
select b.user_id,
       min((a.active - b.anchor)::int) as first_off,
       max((a.active - b.anchor)::int) as max_off
from base b
left join activity a on a.user_id = b.user_id and a.active > b.anchor
group by b.user_id;

create or replace view public.bi_reach_weekly as
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
)
select b.user_id,
       min(((a.active - b.anchor) / 7)::int) as first_off,
       max(((a.active - b.anchor) / 7)::int) as max_off
from base b
left join activity a on a.user_id = b.user_id and a.active > b.anchor
group by b.user_id;

create or replace view public.bi_reach_monthly as
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
)
select b.user_id,
       min(((extract(year from a.active) - extract(year from b.anchor)) * 12
          + (extract(month from a.active) - extract(month from b.anchor)))::int) as first_off,
       max(((extract(year from a.active) - extract(year from b.anchor)) * 12
          + (extract(month from a.active) - extract(month from b.anchor)))::int) as max_off
from base b
left join activity a on a.user_id = b.user_id and a.active > b.anchor
group by b.user_id;

-- ── SURVIVAL (still active at/after N; DECREASES) ──────────────────────────
create or replace view public.bi_survival_daily as
with r as (select first_off, max_off from public.bi_reach_daily), tot as (select count(*)::int n from r)
select g.n as day_number, (select n from tot) as base_users,
  count(*) filter (where g.n = 0 or r.max_off >= g.n)::int as retained,
  round(100.0 * count(*) filter (where g.n = 0 or r.max_off >= g.n) / nullif((select n from tot),0),1) as retention_pct
from r cross join generate_series(0, 6) as g(n) group by g.n order by g.n;

create or replace view public.bi_survival_weekly as
with r as (select first_off, max_off from public.bi_reach_weekly), tot as (select count(*)::int n from r)
select g.n as week_number, (select n from tot) as base_users,
  count(*) filter (where g.n = 0 or r.max_off >= g.n)::int as retained,
  round(100.0 * count(*) filter (where g.n = 0 or r.max_off >= g.n) / nullif((select n from tot),0),1) as retention_pct
from r cross join generate_series(0, 3) as g(n) group by g.n order by g.n;

create or replace view public.bi_survival_monthly as
with r as (select first_off, max_off from public.bi_reach_monthly), tot as (select count(*)::int n from r)
select g.n as month_number, (select n from tot) as base_users,
  count(*) filter (where g.n = 0 or r.max_off >= g.n)::int as retained,
  round(100.0 * count(*) filter (where g.n = 0 or r.max_off >= g.n) / nullif((select n from tot),0),1) as retention_pct
from r cross join generate_series(0, 2) as g(n) group by g.n order by g.n;

-- ── ACTIVATION (returned within first N; INCREASES) ────────────────────────
create or replace view public.bi_activation_daily as
with r as (select first_off, max_off from public.bi_reach_daily), tot as (select count(*)::int n from r)
select g.n as day_number, (select n from tot) as base_users,
  count(*) filter (where g.n = 0 or (r.first_off is not null and r.first_off <= g.n))::int as returned_within,
  round(100.0 * count(*) filter (where g.n = 0 or (r.first_off is not null and r.first_off <= g.n)) / nullif((select n from tot),0),1) as retention_pct
from r cross join generate_series(0, 6) as g(n) group by g.n order by g.n;

create or replace view public.bi_activation_weekly as
with r as (select first_off, max_off from public.bi_reach_weekly), tot as (select count(*)::int n from r)
select g.n as week_number, (select n from tot) as base_users,
  count(*) filter (where g.n = 0 or (r.first_off is not null and r.first_off <= g.n))::int as returned_within,
  round(100.0 * count(*) filter (where g.n = 0 or (r.first_off is not null and r.first_off <= g.n)) / nullif((select n from tot),0),1) as retention_pct
from r cross join generate_series(0, 3) as g(n) group by g.n order by g.n;

create or replace view public.bi_activation_monthly as
with r as (select first_off, max_off from public.bi_reach_monthly), tot as (select count(*)::int n from r)
select g.n as month_number, (select n from tot) as base_users,
  count(*) filter (where g.n = 0 or (r.first_off is not null and r.first_off <= g.n))::int as returned_within,
  round(100.0 * count(*) filter (where g.n = 0 or (r.first_off is not null and r.first_off <= g.n)) / nullif((select n from tot),0),1) as retention_pct
from r cross join generate_series(0, 2) as g(n) group by g.n order by g.n;

grant select on public.bi_reach_daily, public.bi_reach_weekly, public.bi_reach_monthly to bi_readonly;
grant select on public.bi_survival_daily, public.bi_survival_weekly, public.bi_survival_monthly to bi_readonly;
grant select on public.bi_activation_daily, public.bi_activation_weekly, public.bi_activation_monthly to bi_readonly;
