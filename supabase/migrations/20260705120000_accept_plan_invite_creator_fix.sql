-- Fix the creator-side collaborative-plan accept flow.
--
-- Verified against prod (2026-07-05): when a guest accepts a shared plan, the
-- creator's Plans page still showed "1/2 confirmed" / "1 person unconfirmed",
-- "View Attendees" showed "Guest" instead of the accepter's username, and a
-- creator's *second* plan earned no FOMO. Root causes:
--
--   1. NO CREATOR SELF-ROW. The client inserts the creator's plan_attendees row
--      (PlanRepositoryImpl.insertCreatorAttendee) under the creator's JWT, but
--      plan_attendees has no per-user INSERT policy, so RLS silently denied it
--      (error swallowed). Result: only the guest row exists, so the confirmed
--      count is 1, not 2. (Only 2 is_creator rows existed in all of prod, both
--      pre-2026-06-11.) Fixed two ways, additively:
--        (a) this RPC (SECURITY DEFINER) now ensures the creator self-row on
--            accept, backfilling plans that never got one; and
--        (b) a new plan_attendees INSERT-self RLS policy so the client insert
--            succeeds at plan-creation time going forward.
--
--   2. GUEST NAME NULL. The prior RPC inserted the guest row without a name, so
--      the mapper fell back to "Guest". Now we copy the accepter's username
--      (→ first_name → 'Guest') into the row.
--
--   3. FOMO AWARD COLLISION. Badges are keyed (user_id, action_type, milestone)
--      with no plan reference, and the prior RPC used milestone = per-plan
--      accept_count. So a creator's 2nd plan's 1st accept re-hit milestone=1 and
--      was swallowed by ON CONFLICT DO NOTHING — the creator earned nothing.
--      Now the base "+2500 per accept" uses milestone = the creator's LIFETIME
--      accept count (strictly increasing across all their plans → never
--      collides). Group-size bonuses become their own action types with a
--      per-user qualify-index milestone, so they too never collide and repeat
--      on every new qualifying plan.
--
-- Award model (product decision 2026-07-05 — per-plan, repeatable):
--   * +2500  for every accepted invite (action_type 'invite_accepted')
--   * +5000  the first time a plan reaches 5 accepted guests   ('group_5_confirmed')
--   * +10000 the first time a plan reaches 10 accepted guests  ('group_10_confirmed')
--   Group bonuses fire once per plan (keyed off this plan's accept_count crossing
--   the threshold, which is monotonic +1 per accept) and again on each new plan.
--   The sync_fomo_total trigger (leaderboard migration) credits every insert to
--   user_profiles.fomo_total, so points land instantly without the creator
--   opening the app; the client only surfaces the toast.
--
-- Additive / backward-compatible (RELEASE.md): CREATE OR REPLACE on the existing
-- function, one new RLS policy, new action_type string values. No column, type,
-- or response-shape change — the live client's badge decode and the RPC's JSON
-- response are untouched.

-- ---------------------------------------------------------------------------
-- 1. Let the client insert its own creator attendee row at plan-creation time.
--    Restricted to the caller inserting THEMSELVES on a plan THEY own, so this
--    opens no ability to attach to other users' plans.
-- ---------------------------------------------------------------------------
do $$
begin
  if not exists (
    select 1 from pg_policies
    where schemaname = 'public'
      and tablename = 'plan_attendees'
      and policyname = 'plan_attendees_insert_self'
  ) then
    create policy plan_attendees_insert_self
      on public.plan_attendees
      for insert
      to authenticated
      with check (
        user_id = auth.uid()
        -- Only the creator's own self-row. Prevents a user padding their own
        -- confirmed count with fake non-creator rows on a plan they own.
        and is_creator = true
        and exists (
          select 1 from public.plans p
          where p.id = plan_attendees.plan_id
            and p.user_id = auth.uid()
        )
      );
  end if;
end$$;

-- ---------------------------------------------------------------------------
-- 2. Rewritten accept RPC.
-- ---------------------------------------------------------------------------
create or replace function public.accept_plan_invite(p_share_token text, p_user_id uuid)
  returns json
  language plpgsql
  security definer
as $function$
  declare
    v_plan_id          uuid;
    v_creator_id       uuid;
    v_accept_count     int;      -- accepts on THIS plan
    v_lifetime_accepts int;      -- accepts across ALL the creator's plans
    v_points_awarded   int := 0;
    v_guest_name       text;
    v_already_accepted boolean;
    v_qualify_index    int;
  begin
    -- Find the plan and creator
    select id, user_id into v_plan_id, v_creator_id
    from public.plans where share_token = p_share_token;

    if v_plan_id is null then
      return json_build_object('error', 'plan_not_found');
    end if;

    -- Don't let the creator accept their own plan
    if v_creator_id = p_user_id then
      return json_build_object('error', 'own_plan');
    end if;

    -- Idempotency: bail if this user already accepted this plan
    select exists(
      select 1 from public.plan_accepts
      where share_token = p_share_token and user_id = p_user_id
    ) into v_already_accepted;

    if v_already_accepted then
      return json_build_object('error', 'already_accepted');
    end if;

    -- Serialize all mutations for this creator so concurrent accepts can't
    -- race the creator-row NOT EXISTS check or compute a duplicate
    -- lifetime-accept milestone. Per-creator key → different creators don't
    -- block each other. Released at transaction end.
    perform pg_advisory_xact_lock(hashtext(v_creator_id::text)::bigint);

    -- Record the accept
    insert into public.plan_accepts (plan_id, share_token, user_id)
    values (v_plan_id, p_share_token, p_user_id);

    -- Ensure the creator's confirmed self-row exists (client insert is
    -- RLS-gated at creation and may never have landed). name is display-only;
    -- the client maps is_creator rows to "You" regardless.
    if not exists (
      select 1 from public.plan_attendees
      where plan_id = v_plan_id and user_id = v_creator_id
    ) then
      insert into public.plan_attendees (plan_id, user_id, name, is_creator, confirmed, confirmed_at)
      values (v_plan_id, v_creator_id, 'You', true, true, now());
    end if;

    -- Mirror the accept into plan_attendees with the accepter's real name, so
    -- the confirmation count and the "View Attendees" sheet reflect them.
    select coalesce(nullif(trim(username), ''), nullif(trim(first_name), ''), 'Guest')
      into v_guest_name
    from public.user_profiles where id = p_user_id;

    if not exists (
      select 1 from public.plan_attendees
      where plan_id = v_plan_id and user_id = p_user_id
    ) then
      insert into public.plan_attendees (plan_id, user_id, name, is_creator, confirmed, confirmed_at)
      values (v_plan_id, p_user_id, coalesce(v_guest_name, 'Guest'), false, true, now());
    end if;

    -- Accepts on this plan (for the group-size thresholds).
    select count(*) into v_accept_count
    from public.plan_accepts where share_token = p_share_token;

    -- Lifetime accepts across all of the creator's plans (for a collision-free,
    -- strictly-increasing base milestone).
    select count(*) into v_lifetime_accepts
    from public.plan_accepts pa
    join public.plans p on p.id = pa.plan_id
    where p.user_id = v_creator_id;

    -- BASE: +2500 for this accepted invite.
    if not exists (
      select 1 from public.user_badges
      where user_id = v_creator_id
        and action_type = 'invite_accepted'
        and milestone = v_lifetime_accepts
    ) then
      insert into public.user_badges (user_id, state, action_type, milestone, points)
      values (v_creator_id, 'MA', 'invite_accepted', v_lifetime_accepts, 2500);
      v_points_awarded := v_points_awarded + 2500;
    end if;

    -- GROUP BONUS: +5000 the first time this plan reaches 5 accepted guests.
    -- accept_count increments by 1 per accept, so == 5 fires exactly once per
    -- plan. milestone = per-user index of qualifying plans → never collides and
    -- repeats on each new plan.
    if v_accept_count = 5 then
      select count(*) + 1 into v_qualify_index
      from public.user_badges
      where user_id = v_creator_id and action_type = 'group_5_confirmed';

      insert into public.user_badges (user_id, state, action_type, milestone, points)
      values (v_creator_id, 'MA', 'group_5_confirmed', v_qualify_index, 5000);
      v_points_awarded := v_points_awarded + 5000;
    end if;

    -- GROUP BONUS: +10000 the first time this plan reaches 10 accepted guests.
    if v_accept_count = 10 then
      select count(*) + 1 into v_qualify_index
      from public.user_badges
      where user_id = v_creator_id and action_type = 'group_10_confirmed';

      insert into public.user_badges (user_id, state, action_type, milestone, points)
      values (v_creator_id, 'MA', 'group_10_confirmed', v_qualify_index, 10000);
      v_points_awarded := v_points_awarded + 10000;
    end if;

    -- Response shape unchanged (older clients read these fields).
    return json_build_object(
      'success', true,
      'accept_count', v_accept_count,
      'points_awarded', v_points_awarded,
      'creator_id', v_creator_id
    );
  end;
  $function$;
