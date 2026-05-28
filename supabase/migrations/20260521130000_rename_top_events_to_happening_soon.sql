-- Rename "Top Events This Week" -> "Top Events Happening Soon".
-- The carousels edge function now uses an adaptive window (7d -> 14d -> 30d)
-- when the 7-day pool is thin, so "This Week" stopped being accurate.
-- Slug and filter_type stay 'top_weekly' for backwards compatibility.

UPDATE carousels
SET title = 'Top Events Happening Soon'
WHERE filter_type = 'top_weekly'
   OR slug = 'top_weekly';
