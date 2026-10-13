-- Soft-paywall nudge cadence knob.
--
-- Opening (and sharing) an event is now FREE for everyone. Instead of hard-gating
-- the open, non-premium users see the dismissible paywall every Nth app open. The
-- client (VersionCheckService) reads this cadence from app_config so it's tunable
-- without shipping a release. Default 3.
--
-- Two additive / backward-compatible changes:
--   1. Seed the `paywall_nudge_interval` row (guarded so it won't clobber a value
--      already set directly in prod).
--   2. Widen the public read policy so the anon/authenticated client can read this
--      key alongside the version keys — the policy is a strict key allowlist, so a
--      new key is invisible to the client until it's listed here.
-- Older clients that don't know the key simply ignore it, so this is safe for the
-- live apps.

insert into public.app_config (key, value)
select 'paywall_nudge_interval', '3'
where not exists (
  select 1 from public.app_config where key = 'paywall_nudge_interval'
);

drop policy if exists app_config_version_read on public.app_config;
create policy app_config_version_read
  on public.app_config
  for select
  to anon, authenticated
  using (key in ('min_version', 'latest_version', 'force_message', 'paywall_nudge_interval'));
