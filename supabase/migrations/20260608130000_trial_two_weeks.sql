-- Shorten the app-granted Local Pro free trial from 6 weeks to 2 weeks.
--
-- Repoints both places the trial window is stamped server-side:
--   1. the column DEFAULT (new signups via paths that don't hit the trigger)
--   2. guard_entitlement_columns() (forced value for client INSERTs)
--
-- Per product: apply retroactively to EVERYONE (test accounts included), so
-- every existing row's trial is reset to 2 weeks from now. Runs as the
-- migration/admin role, so guard_entitlement_columns() passes it through
-- (the trigger only pins values for the 'authenticated'/'anon' roles).
update public.user_profiles
  set trial_ends_at = now() + interval '2 weeks';

alter table public.user_profiles
  alter column trial_ends_at set default (now() + interval '2 weeks');

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
      new.trial_ends_at  := now() + interval '2 weeks';
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
