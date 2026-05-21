-- Rename the "Top 10 This Week" carousel to "Top Events This Week".
-- Drops the "10" from both the visible title and the internal slug /
-- filter_type. The edge function now switches on filter_type='top_weekly';
-- this migration keeps existing DB rows in sync.

UPDATE carousels
SET
  slug = 'top_weekly',
  title = 'Top Events This Week',
  filter_type = 'top_weekly'
WHERE slug = 'top10_weekly'
   OR filter_type = 'top10_weekly';
