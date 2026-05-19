-- Force public_interest_rank = 0 for venue categories the product owner
-- has flagged as "should never surface": adult entertainment (strip clubs,
-- cabarets, gentlemen's clubs) and golf venues (private/public courses,
-- country clubs, golf clubs). Rank ≤ 10 is the existing low-rank floor —
-- VenueRepositoryImpl.getLowRankVenueNames() returns these as a
-- blocklist, and ChatViewModel.searchEvents drops their events. So setting
-- rank=0 transitively excludes their events from Bo's search results and
-- from the carousels' rank-gated rails.
--
-- Pattern-matches both the `category` and `place_type` columns plus a
-- name-based safety net for venues whose category was set by an early
-- crawler pass that used a different taxonomy. ILIKE is case-insensitive.
--
-- Idempotent: re-running just sets rank=0 again on the same rows. The
-- WHERE includes a "rank > 0 OR is null" guard so we don't churn rows
-- that are already at 0.

UPDATE venues
SET public_interest_rank = 0
WHERE (public_interest_rank IS NULL OR public_interest_rank > 0)
  AND (
    -- ── Adult entertainment ──
    category ILIKE '%strip club%'
    OR category ILIKE '%cabaret%'
    OR category ILIKE '%gentlemen%'
    OR category ILIKE '%adult entertainment%'
    OR place_type ILIKE '%strip_club%'
    OR place_type ILIKE '%adult_entertainment%'
    OR name ILIKE '%strip club%'
    OR name ILIKE '%gentlemen''s club%'
    OR name ILIKE '%cabaret%'
    OR name ILIKE '%showgirls%'
    OR name ILIKE '%burlesque%'   -- catches strip-club-style venues; legit burlesque shows are events not venues

    -- ── Golf venues (the product owner's call: not a fit for the app) ──
    OR category ILIKE '%golf course%'
    OR category ILIKE '%country club%'
    OR category ILIKE '%golf club%'
    OR place_type ILIKE '%golf_course%'
    OR place_type ILIKE '%country_club%'
    OR name ILIKE '%golf course%'
    OR name ILIKE '%country club%'
    OR name ILIKE '%golf club%'
    OR name ILIKE '% golf %'   -- "Stonybrook Golf Park", "Brae Burn Golf", etc.
    OR name ILIKE 'golf %'
    OR name ILIKE '% golf'
  );

-- Return rows that just got zeroed so you can sanity-check via the SQL
-- Editor result panel.
SELECT id, name, city, category, place_type, public_interest_rank
FROM venues
WHERE public_interest_rank = 0
ORDER BY name;
