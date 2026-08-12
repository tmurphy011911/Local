-- ===========================================================================
-- BI: signup population split into three mutually exclusive groups for the
-- investor "Conversion to Paid" tab (dashboard 37 / tab 11):
--   1. subscribed to Pro at some point (RC webhook touched them:
--      pro_updated_at set, or is_pro for manually granted rows)
--        └─ pro_cancelled = ever subscribed but entitlement lapsed
--   2. in trial, never subscribed
--   3. trial expired, never subscribed
-- Everyone gets an app-side trial (trial_ends_at stamped at signup), so RC
-- events imply a real purchase, not a trial start.
-- Same SECURITY DEFINER-style view chain as the other bi_* views.
-- ===========================================================================

create or replace view public.bi_signup_groups as
with u as (
  select is_pro,
         trial_ends_at,
         (pro_updated_at is not null or is_pro) as ever_pro
  from public.user_profiles
)
select
  count(*)::int                                                            as total_signups,
  count(*) filter (where ever_pro)::int                                    as subscribed_pro,
  count(*) filter (where ever_pro and is_pro)::int                         as pro_active,
  count(*) filter (where ever_pro and not is_pro)::int                     as pro_cancelled,
  round(100.0 * count(*) filter (where ever_pro and not is_pro)
        / nullif(count(*) filter (where ever_pro), 0), 1)                  as pro_cancelled_pct,
  count(*) filter (where not ever_pro and trial_ends_at > now())::int      as in_trial_not_subscribed,
  count(*) filter (where not ever_pro
                     and (trial_ends_at is null or trial_ends_at <= now()))::int
                                                                           as trial_expired_not_subscribed,
  round(100.0 * count(*) filter (where ever_pro)
        / nullif(count(*), 0), 1)                                          as subscribed_pct_of_all,
  round(100.0 * count(*) filter (where not ever_pro and trial_ends_at > now())
        / nullif(count(*), 0), 1)                                          as in_trial_pct_of_all,
  round(100.0 * count(*) filter (where not ever_pro
                                   and (trial_ends_at is null or trial_ends_at <= now()))
        / nullif(count(*), 0), 1)                                          as trial_expired_pct_of_all
from u;

grant select on public.bi_signup_groups to bi_readonly;
