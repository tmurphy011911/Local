-- Auto-username: single "bo" prefix.
--
-- Supersedes the four-pool generator from 20260608140000_usernames.sql
-- (local####, mo####, lo####, fo####). New signups now always get bo####,
-- #### ∈ 1..10000. Existing auto-assigned handles are intentionally left as-is
-- (no backfill) — they're never shown in greetings (username_is_custom = false),
-- so a forward-only switch is purely cosmetic for current users.
--
-- Backward-compatible: replaces a function body only, no DDL. The trigger
-- (trg_set_default_username) and the unique index on lower(username) are
-- unchanged. The 200-attempt cap still turns pool exhaustion into a loud error.
-- Note: a single prefix means 10,000 names instead of 40,000 — fine at current
-- scale; widen the random() range here if signup volume ever approaches it.

create or replace function public.gen_default_username()
returns text
language plpgsql
as $$
declare
  candidate text;
  attempts  int := 0;
begin
  loop
    candidate := 'bo' || (1 + floor(random() * 10000)::int)::text;
    exit when not exists (
      select 1 from public.user_profiles where lower(username) = lower(candidate)
    );
    attempts := attempts + 1;
    if attempts > 200 then
      raise exception
        'gen_default_username: no free handle after % attempts (pool may be exhausted)',
        attempts;
    end if;
  end loop;
  return candidate;
end;
$$;
