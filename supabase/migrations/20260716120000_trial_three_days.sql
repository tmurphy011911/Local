-- Shorten the app-granted Local Pro free trial from 2 weeks to 3 days — for
-- NEW signups only. Existing users are intentionally left untouched.
--
-- Repoints both places the trial window is stamped server-side:
--   1. the column DEFAULT (new signups via paths that don't hit the trigger)
--   2. guard_entitlement_columns() (forced value for client INSERTs)
--
-- DELIBERATELY OMITS the `update public.user_profiles set trial_ends_at = ...`
-- that the 6→2 week migration (20260608130000) ran. Per product: existing users
-- must NOT be impacted, so we do not rewrite any existing row's trial_ends_at.
-- Only rows inserted AFTER this migration land on the new 3-day window.
--
-- Additive / backward-compatible: changes a column default and replaces a
-- function body — no DROP/RENAME/type change/NOT NULL on a live column — so it
-- is safe to promote to prod while older clients are live.

alter table public.user_profiles
  alter column trial_ends_at set default (now() + interval '3 days');

create or replace function public.guard_entitlement_columns()
returns trigger
language plpgsql
as $$
begin
  if current_user in ('authenticated', 'anon') then
    if tg_op = 'INSERT' then
      new.is_pro         := false;
      new.pro_expires_at := null;
      new.pro_product_id := null;
      new.pro_store      := null;
      new.pro_updated_at := null;
      new.trial_ends_at  := now() + interval '3 days';
    elsif tg_op = 'UPDATE' then
      new.is_pro         := old.is_pro;
      new.pro_expires_at := old.pro_expires_at;
      new.pro_product_id := old.pro_product_id;
      new.pro_store      := old.pro_store;
      new.pro_updated_at := old.pro_updated_at;
      new.trial_ends_at  := old.trial_ends_at;
    end if;
  end if;
  return new;
end;
$$;
