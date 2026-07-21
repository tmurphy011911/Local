-- ===========================================================================
-- Grandfather users who ALREADY selected onboarding preferences.
-- ---------------------------------------------------------------------------
-- preferences_completed was just added defaulting to false, so every existing
-- user — including those who went through the full onboarding preference flow
-- before it was shortened — currently reads false and would see the new lobby
-- CTA and be able to earn its FOMO. Mark anyone with an existing preference
-- selection (categories / neighborhoods / venues) as completed so the CTA never
-- shows for them.
--
-- These pre-existing selections must NOT pay out FOMO (the award is only for
-- NEW completions), so the flip is wrapped in DISABLE/ENABLE of the award
-- trigger — only that one trigger is suppressed, the username/home-state guards
-- still run. The award trigger fires on the false->true transition, which this
-- backfill would otherwise trigger.
--
-- Runs after 20260714130000 (which creates the column + trigger). One-time,
-- idempotent (guarded on preferences_completed = false).
-- ===========================================================================
alter table public.user_profiles disable trigger trg_award_profile_milestone_fomo;

update public.user_profiles
   set preferences_completed = true
 where preferences_completed = false
   and (coalesce(array_length(selected_categories, 1), 0) > 0
     or coalesce(array_length(selected_neighborhoods, 1), 0) > 0
     or coalesce(array_length(selected_venues, 1), 0) > 0);

alter table public.user_profiles enable trigger trg_award_profile_milestone_fomo;
