-- app_config was never client-readable: RLS was enabled with no SELECT policy
-- for the app's roles, so the anon client's version check
-- (VersionCheckService.check → select on app_config) got an EMPTY result and
-- silently defaulted min_version/latest_version to "1.0" → the force/soft update
-- sheets never fired for anyone. Verified 2026-07-07: anon REST read of
-- app_config returned [] even with min_version='1.1.0' present.
--
-- Add a read policy scoped to just the version keys (min_version /
-- latest_version / force_message) so the client can read them, without exposing
-- any other config rows to the public. Additive / backward-compatible.
--
-- Applied to prod via SQL editor 2026-07-07; committed here as source of truth.

drop policy if exists app_config_version_read on public.app_config;
create policy app_config_version_read
  on public.app_config
  for select
  to anon, authenticated
  using (key in ('min_version', 'latest_version', 'force_message'));
