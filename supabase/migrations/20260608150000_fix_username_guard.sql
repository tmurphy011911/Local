-- Fix for the username guard shipped in 20260608140000_usernames.sql.
--
-- Bug: the BEFORE UPDATE guard treated ANY change of the handle as a user
-- edit (is_custom := true) — including the null → handle transition the
-- backfill performs on its first assignment. That mislabeled every
-- auto-assigned handle as user-chosen, which would make the homepage greet
-- users with a handle they never picked (the exact thing the feature avoids).
--
-- Correct behavior: a first assignment (old.username IS NULL) must trust the
-- flag the writer provides (false for the backfill). Only a change to an
-- ALREADY-assigned handle counts as a user edit.
create or replace function public.guard_username_update()
returns trigger
language plpgsql
as $$
begin
  if new.username is null then
    -- never blank an assigned handle; preserve its flag
    new.username := old.username;
    new.username_is_custom := old.username_is_custom;
  elsif old.username is null then
    -- first assignment (backfill / late fill): trust the provided flag
    null;
  elsif new.username is distinct from old.username then
    -- genuine user edit of an existing handle
    new.username_is_custom := true;
  else
    -- handle unchanged
    new.username_is_custom := old.username_is_custom;
  end if;
  return new;
end;
$$;

-- Repair the rows the old guard mislabeled. The account-page editor isn't
-- shipped yet, so no handle is genuinely user-chosen — every existing handle is
-- auto-assigned and must read is_custom = false. The guard is disabled for the
-- repair so its "unchanged handle preserves old flag" branch doesn't re-stamp
-- the wrong value back. (On a fresh DB this matches 0 rows.)
alter table public.user_profiles disable trigger trg_guard_username_update;
update public.user_profiles set username_is_custom = false where username_is_custom = true;
alter table public.user_profiles enable trigger trg_guard_username_update;
