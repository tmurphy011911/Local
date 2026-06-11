-- Mark plans that were saved by accepting someone else's invite.
--
-- Accepting an invite copies the shared plan into the accepter's account as a
-- normal plan row (user_id = accepter), so creatorId (which maps from user_id)
-- looks identical to a self-made plan. That made the attendee's copy show the
-- creator-only share button + "X/Y confirmed" tally. This column records the
-- ORIGINAL creator's id on the copy; null means the user originated the plan.
-- The client derives Plan.isAcceptedCopy = (accepted_from_creator_id != null)
-- and hides share / confirmation UI when it's set.
ALTER TABLE plans
  ADD COLUMN IF NOT EXISTS accepted_from_creator_id uuid;

COMMENT ON COLUMN plans.accepted_from_creator_id IS
  'When non-null, this plan is a copy saved by accepting an invite; holds the original creator''s user id. Null for plans the owner originated.';

-- NOTE: pre-existing accepted copies (saved before this migration) keep a null
-- value and will still show the creator UI — they can''t be identified
-- retroactively. New accepts are stamped going forward.
