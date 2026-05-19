-- One-shot revival for events the buggy `deactivate_past_events.js` killed.
-- The original script flipped is_active=false on every event where
-- start_at < NOW(), ignoring end_at. That meant a 2-month museum exhibition
-- got deactivated the morning after it opened. ~992 rows currently sit in
-- this wrongly-killed state as of 2026-05-17 (Pattern & Place at Gore
-- Place, MASS MoCA wall drawings, seasonal venue programs, etc).
--
-- The deactivation bug is patched in scripts/deactivate_past_events.js
-- (sibling migration / commit). This migration just unwinds the damage.
--
-- Targeting filter — exactly the set the buggy script could have touched
-- that is also still in-window:
--   is_active = false           — currently hidden
--   start_at  < NOW()           — already started (script's only trigger)
--   end_at IS NOT NULL          — multi-day / ranged event
--   end_at    > NOW()           — run window has NOT ended
--
-- Caveat: if a moderator manually deactivated a future-end-at event for
-- legitimate reasons (e.g. content takedown), this query will undo that.
-- Run the SELECT-only preview below first; if any row looks wrong, add
-- an explicit `AND id NOT IN (...)` exclusion before running the UPDATE.

-- ── Preview (run this on its own first) ──
-- SELECT id, title, venue_name, start_at, end_at, source
-- FROM events
-- WHERE is_active = false
--   AND start_at < NOW()
--   AND end_at IS NOT NULL
--   AND end_at > NOW()
-- ORDER BY end_at ASC;

UPDATE events
SET is_active = true
WHERE is_active = false
  AND start_at < NOW()
  AND end_at IS NOT NULL
  AND end_at > NOW();

-- Sanity-check the revived population.
SELECT id, title, venue_name, start_at, end_at, source
FROM events
WHERE is_active = true
  AND start_at < NOW()
  AND end_at > NOW()
ORDER BY end_at ASC;
