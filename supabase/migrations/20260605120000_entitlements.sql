-- Entitlement state for Local Pro.
--
-- Two independent things live here:
--   * trial_ends_at  — the app-granted 6-week free trial. NOT a RevenueCat
--                      subscription; stamped server-side at signup.
--   * is_pro / pro_*  — the real paid subscription, owned by RevenueCat and
--                      written ONLY by the rc-webhook edge function (service
--                      role). Derived from expiry vs now, never from event type.
--
-- Effective access = is_pro = true OR now() < trial_ends_at.
--
-- Security: clients (authenticated/anon) must never write any of these. The
-- guard trigger below forces them to server-owned values regardless of the
-- existing row-update RLS policy, so we don't depend on column grants we can't
-- introspect.

-- Constant default (false) = metadata-only add, no table rewrite.
alter table public.user_profiles
  add column if not exists is_pro         boolean     not null default false,
  add column if not exists pro_expires_at timestamptz,
  add column if not exists pro_product_id text,
  add column if not exists pro_store      text,
  add column if not exists pro_updated_at timestamptz;

-- trial_ends_at: a volatile default (now()) would rewrite the whole table under
-- an ACCESS EXCLUSIVE lock. Do it in three cheap steps instead:
--   1. add nullable (metadata-only)
--   2. backfill existing rows — "fresh 6 weeks from launch" (confirmed policy:
--      nobody is walled at launch; the alternative, trial from original signup,
--      would wall any user >6wk old)
--   3. attach the default for future rows, then enforce NOT NULL
alter table public.user_profiles
  add column if not exists trial_ends_at timestamptz;

update public.user_profiles
  set trial_ends_at = now() + interval '6 weeks'
  where trial_ends_at is null;

alter table public.user_profiles
  alter column trial_ends_at set default (now() + interval '6 weeks');

alter table public.user_profiles
  alter column trial_ends_at set not null;

-- Count/segment paid users cheaply: `select count(*) from user_profiles where is_pro`.
create index if not exists user_profiles_is_pro_idx
  on public.user_profiles (is_pro) where is_pro = true;

-- Filter "still in trial" users (push/upsell targeting).
create index if not exists user_profiles_trial_ends_at_idx
  on public.user_profiles (trial_ends_at);

create or replace function public.guard_entitlement_columns()
returns trigger
language plpgsql
as $$
begin
  -- Clients connect as 'authenticated' or 'anon'. They are the only callers we
  -- don't trust. service_role (rc-webhook) and admin/migration roles write freely.
  if current_user in ('authenticated', 'anon') then
    if tg_op = 'INSERT' then
      new.is_pro         := false;
      new.pro_expires_at := null;
      new.pro_product_id := null;
      new.pro_store      := null;
      new.pro_updated_at := null;
      new.trial_ends_at  := now() + interval '6 weeks';
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

drop trigger if exists guard_entitlement_columns on public.user_profiles;
create trigger guard_entitlement_columns
  before insert or update on public.user_profiles
  for each row execute function public.guard_entitlement_columns();
