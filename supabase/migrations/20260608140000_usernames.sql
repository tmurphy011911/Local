-- Usernames.
--
-- Every user gets an auto-assigned handle drawn from four prefixed pools
-- (local####, mo####, lo####, fo####, where #### ∈ 1..10000 → 40,000 names).
-- They can change it on the account page to any unused name.
--
-- Design notes:
--  • Uniqueness is CASE-INSENSITIVE and enforced by the DB (unique index on
--    lower(username)). The client's "is this taken?" check is advisory only —
--    the index is the source of truth and is what makes concurrent edits safe.
--  • username_is_custom distinguishes an auto-assigned handle ("local4821")
--    from one the user actually chose. The homepage greets ONLY when this is
--    true, so we never greet someone with a handle they didn't pick.
--  • The client never manages username_is_custom: the update guard flips it to
--    true whenever the handle actually changes, and a general profile save
--    (which sends the loaded handle back unchanged, or null) leaves it alone.

alter table public.user_profiles
  add column if not exists username text,
  add column if not exists username_is_custom boolean not null default false;

-- Case-insensitive uniqueness. Partial so the brief window where a row exists
-- before assignment (null username) doesn't collide.
create unique index if not exists user_profiles_username_lower_key
  on public.user_profiles (lower(username))
  where username is not null;

-- Pick a random unused handle from the prefixed pools.
--
-- Concurrency: the exists() check + the unique index together are the safety
-- net. Two simultaneous signups could in theory pick the same candidate between
-- the check and the insert; the unique index then rejects the loser with 23505.
-- At current scale (single-digit users, low signup rate) that's a non-event;
-- if signup volume grows enough to make collisions common, switch this to a
-- sequence-backed assignment. The 200-attempt cap turns pool exhaustion into a
-- loud error instead of an infinite loop.
create or replace function public.gen_default_username()
returns text
language plpgsql
as $$
declare
  prefixes text[] := array['local', 'mo', 'lo', 'fo'];
  candidate text;
  attempts  int := 0;
begin
  loop
    candidate := prefixes[1 + floor(random() * array_length(prefixes, 1))::int]
                 || (1 + floor(random() * 10000)::int)::text;
    exit when not exists (
      select 1 from public.user_profiles where lower(username) = lower(candidate)
    );
    attempts := attempts + 1;
    if attempts > 200 then
      raise exception
        'gen_default_username: no free handle after % attempts (pools may be exhausted)',
        attempts;
    end if;
  end loop;
  return candidate;
end;
$$;

-- New signups with no handle get one assigned (and it's not "custom").
create or replace function public.set_default_username()
returns trigger
language plpgsql
as $$
begin
  if new.username is null then
    new.username := public.gen_default_username();
    new.username_is_custom := false;
  end if;
  return new;
end;
$$;

drop trigger if exists trg_set_default_username on public.user_profiles;
create trigger trg_set_default_username
  before insert on public.user_profiles
  for each row execute function public.set_default_username();

-- Guard the handle on UPDATE so general profile saves can't damage it:
--  • a write sending NULL username must not blank an assigned handle
--  • any genuine change to the handle marks it user-chosen (is_custom = true),
--    so the client never has to manage that flag
--  • an unchanged handle preserves the existing flag
create or replace function public.guard_username_update()
returns trigger
language plpgsql
as $$
begin
  if new.username is null then
    new.username := old.username;
  end if;
  if new.username is distinct from old.username then
    new.username_is_custom := true;
  else
    new.username_is_custom := old.username_is_custom;
  end if;
  return new;
end;
$$;

drop trigger if exists trg_guard_username_update on public.user_profiles;
create trigger trg_guard_username_update
  before update on public.user_profiles
  for each row execute function public.guard_username_update();

-- NOTE: existing rows are left with username = null here on purpose. They are
-- filled by scripts/backfill_usernames.js (dry-run + rollback + registry
-- entry), keeping the schema migration free of data writes.
