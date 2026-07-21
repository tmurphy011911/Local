-- ===========================================================================
-- Remove an unintended double entendre from Bo's Daily Local personal_best pool.
-- ---------------------------------------------------------------------------
-- The line "New personal best, hot off the press. Ink's still wet, so am I."
-- reads as innuendo ("wet ... so am I") and was showing to real users on a
-- personal-best run. The quiz_bo_messages rows were originally hand-seeded via
-- the SQL editor (no INSERT for them exists in any prior migration), so this
-- content delete is captured here to apply cleanly to dev and prod via db push.
--
-- Matched by content, not id: the identity column was seeded independently in
-- each database, so ids differ. Idempotent -- deletes 0 rows if already gone.
-- Additive / back-compat: removes 1 of ~60 personal_best lines; the pool stays
-- well-populated and quiz_pick_bo_message() keeps returning a line.
--
-- Rollback (if ever needed):
--   insert into public.quiz_bo_messages (bucket, message)
--   values ('personal_best',
--     'New personal best, hot off the press. Ink''s still wet, so am I.');
-- ===========================================================================
delete from public.quiz_bo_messages
 where bucket = 'personal_best'
   and message = 'New personal best, hot off the press. Ink''s still wet, so am I.';
