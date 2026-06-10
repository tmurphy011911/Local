-- Hometown: a durable, write-once anchor for the location the user chose during
-- onboarding (manual address typed on step 1, or the GPS fix they granted).
--
-- Why a dedicated field rather than reusing user_lat/user_lng/city:
--  • Those columns happen to retain the onboarding location today only because
--    nothing persists post-onboarding GPS ticks (setUserLocation is UI-only) and
--    saveLocationPrefs preserves them. That durability is INCIDENTAL — a future
--    change that starts persisting live coords would silently clobber it.
--  • user_lat/user_lng is semantically overloaded (storage = onboarding anchor,
--    some reads treat it as "current-ish"). Hometown makes the anchor explicit
--    so the upcoming social "hometown board" can depend on it safely.
--
-- Write-once: enforced server-side by a guard trigger (same approach as the
-- username / trial_ends_at guards) so a general profile save can never change or
-- blank an already-set hometown. A deliberate "I moved" edit, if we ever build
-- one, should go through a dedicated SECURITY DEFINER path, not the general save.
--
-- Privacy: hometown_lat/lng are PII. The social board must expose only the
-- coarse hometown_name (town/metro) cross-user and keep coords owner-scoped via
-- RLS — do NOT select hometown_lat/lng in any cross-user query.
--
-- Nullable add, no table rewrite. Existing rows are intentionally left null here
-- and filled by a separate dry-run backfill (scripts/backfill_hometown.js, run
-- via /db-op), keeping this schema migration free of data writes — same split as
-- the usernames migration.

alter table public.user_profiles
  add column if not exists hometown_name   text,
  add column if not exists hometown_lat    double precision,
  add column if not exists hometown_lng    double precision,
  add column if not exists hometown_set_at timestamptz;

-- Guard hometown on INSERT/UPDATE:
--  • once set (name or lat present), it is immutable — any incoming change is
--    ignored and the stored values are preserved. This is what makes the general
--    profile save (which sends the full row back every time) safe.
--  • on the first write that establishes a hometown, stamp hometown_set_at so we
--    can later tell a clean onboarding capture from a backfilled/inferred one.
create or replace function public.guard_hometown_write()
returns trigger
language plpgsql
as $$
begin
  if tg_op = 'UPDATE'
     and (old.hometown_name is not null or old.hometown_lat is not null) then
    -- Already set: immutable. Preserve, ignore the incoming values.
    new.hometown_name   := old.hometown_name;
    new.hometown_lat    := old.hometown_lat;
    new.hometown_lng    := old.hometown_lng;
    new.hometown_set_at := old.hometown_set_at;
    return new;
  end if;

  -- First-time set (insert, or update from an empty hometown): stamp it.
  if new.hometown_name is not null or new.hometown_lat is not null then
    new.hometown_set_at := coalesce(new.hometown_set_at, now());
  end if;
  return new;
end;
$$;

drop trigger if exists trg_guard_hometown_write on public.user_profiles;
create trigger trg_guard_hometown_write
  before insert or update on public.user_profiles
  for each row execute function public.guard_hometown_write();
