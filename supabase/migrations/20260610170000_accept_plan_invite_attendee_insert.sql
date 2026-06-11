-- Move the accepting guest's plan_attendees insert into accept_plan_invite.
--
-- Previously the client (PlanRepositoryImpl.acceptPlanInvite) did a follow-up
-- insert into plan_attendees after the RPC returned. That insert ran under the
-- guest's auth JWT and was silently denied by RLS (plan_attendees has no
-- per-user INSERT policy), so the accepting user's confirmed row never landed.
-- The plan's "X/Y confirmed" count and attendee list therefore never reflected
-- the guest, even though the RPC had already recorded the accept + creator badge.
--
-- This function is SECURITY DEFINER (it already writes another user's badge), so
-- doing the insert here needs no new RLS surface and no self-insert grant. The
-- insert is idempotent (ON CONFLICT DO NOTHING) and only reached on a first-time
-- accept, since the already_accepted guard returns earlier on repeats.
CREATE OR REPLACE FUNCTION public.accept_plan_invite(p_share_token text, p_user_id uuid)
 RETURNS json
 LANGUAGE plpgsql
 SECURITY DEFINER
AS $function$
  DECLARE
    v_plan_id UUID;
    v_creator_id UUID;
    v_accept_count INT;
    v_points_awarded INT := 0;
    v_already_accepted BOOLEAN;
  BEGIN
    -- Find the plan and creator
    SELECT id, user_id INTO v_plan_id, v_creator_id
    FROM plans WHERE share_token = p_share_token;

    IF v_plan_id IS NULL THEN
      RETURN json_build_object('error', 'plan_not_found');
    END IF;

    -- Don't let creator accept their own plan
    IF v_creator_id = p_user_id THEN
      RETURN json_build_object('error', 'own_plan');
    END IF;

    -- Check if already accepted
    SELECT EXISTS(
      SELECT 1 FROM plan_accepts WHERE share_token = p_share_token AND user_id = p_user_id
    ) INTO v_already_accepted;

    IF v_already_accepted THEN
      RETURN json_build_object('error', 'already_accepted');
    END IF;

    -- Record the accept
    INSERT INTO plan_accepts (plan_id, share_token, user_id)
    VALUES (v_plan_id, p_share_token, p_user_id);

    -- Mirror the accept into plan_attendees so the plan's confirmation count
    -- and attendee list include this guest. is_creator defaults to false.
    INSERT INTO plan_attendees (plan_id, user_id, confirmed, confirmed_at)
    VALUES (v_plan_id, p_user_id, true, now())
    ON CONFLICT DO NOTHING;

    -- Count total accepts for this plan
    SELECT COUNT(*) INTO v_accept_count
    FROM plan_accepts WHERE share_token = p_share_token;

    -- Award 2,500 points per accept to the creator
    v_points_awarded := 2500;

    -- Award milestone bonuses
    IF v_accept_count = 5 THEN
      v_points_awarded := v_points_awarded + 5000;
    END IF;
    IF v_accept_count = 10 THEN
      v_points_awarded := v_points_awarded + 10000;
    END IF;

    -- Insert badge milestone for creator (invite_accepted action)
    INSERT INTO user_badges (user_id, state, action_type, milestone, points)
    VALUES (v_creator_id, 'MA', 'invite_accepted', v_accept_count, v_points_awarded)
    ON CONFLICT DO NOTHING;

    RETURN json_build_object(
      'success', true,
      'accept_count', v_accept_count,
      'points_awarded', v_points_awarded,
      'creator_id', v_creator_id
    );
  END;
  $function$;
