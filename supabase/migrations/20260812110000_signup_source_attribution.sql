-- ===========================================================================
-- Acquisition attribution: where a signup came from.
--
-- Client (v1.4.0+) stamps these once, on the initial user_profiles insert:
--   signup_source   — normalized channel: utm_source from the Play Install
--                     Referrer ("instagram", "google-play" for organic, ...)
--                     or "share-link" for deferred share-link installs (the
--                     only pre-signup signal iOS provides).
--   signup_referrer — raw referrer string, kept for debugging/re-mapping.
-- Pre-v1.4.0 users stay NULL → reported as "unknown".
--
-- bi_signups_by_source powers the "Signups by source" card on the investor
-- Acquisition tab. Same definer-view + bi_readonly grant pattern as bi_*.
-- ===========================================================================

alter table public.user_profiles
  add column if not exists signup_source text,
  add column if not exists signup_referrer text;

create or replace view public.bi_signups_by_source as
select date_trunc('week', created_at)::date                        as wk,
       coalesce(nullif(trim(signup_source), ''), 'unknown')        as source,
       count(*)::int                                               as signups
from public.user_profiles
where created_at is not null
group by 1, 2
order by 1, 2;

grant select on public.bi_signups_by_source to bi_readonly;
