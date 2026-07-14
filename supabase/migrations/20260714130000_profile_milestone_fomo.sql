-- ===========================================================================
-- FOMO for setting a username and completing the preferences flow.
-- ---------------------------------------------------------------------------
-- Two new one-time FOMO awards, credited server-side so every entry point is
-- covered uniformly and nothing can double-award:
--   * set_username       (1000) — the first time a user picks a real handle.
--     username_is_custom flips false->true via the BEFORE guard triggers no
--     matter where the write comes from (account screen, leaderboard flow, or
--     the new lobby CTA), so awarding off that flag catches all three.
--   * select_preferences (1000) — the first time a user completes the (now
--     lobby-launched) preferences flow, signalled by preferences_completed.
--
-- Both flags only ever move false->true once, so each award fires once; the
-- UNIQUE(user_id,state,action_type,milestone) plus ON CONFLICT DO NOTHING makes
-- it idempotent regardless. The leaderboard total sums DISTINCT
-- (user_id,action_type,milestone,points) — state is not in the dedup key — so a
-- later home_state change can never double-count these.
-- Additive: new column + new trigger. No existing behavior changes.
-- ===========================================================================
alter table public.user_profiles
  add column if not exists preferences_completed boolean not null default false;

create or replace function public.award_profile_milestone_fomo()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  v_state text := coalesce(new.home_state, '');
begin
  -- First real username -> 1000 FOMO.
  if new.username_is_custom is true
     and (tg_op = 'INSERT' or coalesce(old.username_is_custom, false) is false) then
    insert into public.user_badges (user_id, state, action_type, milestone, points)
      values (new.id, v_state, 'set_username', 0, 1000)
      on conflict (user_id, state, action_type, milestone) do nothing;
  end if;

  -- First completed preferences flow -> 1000 FOMO.
  if new.preferences_completed is true
     and (tg_op = 'INSERT' or coalesce(old.preferences_completed, false) is false) then
    insert into public.user_badges (user_id, state, action_type, milestone, points)
      values (new.id, v_state, 'select_preferences', 0, 1000)
      on conflict (user_id, state, action_type, milestone) do nothing;
  end if;

  return new;
end;
$$;

drop trigger if exists trg_award_profile_milestone_fomo on public.user_profiles;
create trigger trg_award_profile_milestone_fomo
  after insert or update on public.user_profiles
  for each row execute function public.award_profile_milestone_fomo();
