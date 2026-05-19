-- Remove PLAN_SHARED from the points/badge system.
--
-- "Sharing a plan" is trivially repeatable (system share sheet, copy-link to
-- self, etc.) so awarding milestone points for it is farmable. The action
-- was never actually wired up to recordAction(), so this DELETE should
-- normally be a no-op — kept as a defensive guard against any rows that
-- might have been inserted manually or via an older build.

DELETE FROM user_badges
WHERE action_type = 'plan_shared';
