-- Add missing Greater Boston event venues (Suffolk/Middlesex/Norfolk/Essex/Plymouth
-- county gaps + creative/experiential list), deduped across both source lists.
--
-- Conventions (match PA county onboarding):
--   * events_url = NULL  -> let onboard fix_venue_urls.js discover + VERIFY real URLs
--                           (provided URLs in the source lists were partly hallucinated)
--   * website   = NULL   -> same reason
--   * ON CONFLICT (name, city) DO NOTHING -> safe re-run; never overwrites existing rows
--
-- Excluded (flagged already-in-DB / verify / skip in the audit):
--   The Cabot Cinema, Methuen Memorial Music Hall, Fuller Craft Museum,
--   Kingston Farmers Market, Diablo Glass School, 450 Harrison Ave Studios,
--   Artisan's Asylum, Brookline Booksmith, Porter Square Books, Hanson's Farm,
--   Belkin Lookout Farm, Ward's Berry Farm, Pottery Studio 1, Main Point Books.
-- Excluded (ambiguous multi-location / out-of-region):
--   Painting with a Twist, Stumpy's Hatchet House, Craft Beer Cellar (no single city);
--   Watershed Center (ME); Obadiah/Flying Pig (Western MA, per audit "skip").
-- Held back pending your call (NOT in this file): "Oberon" (ART 2 Arrow St — the
--   club space closed in 2020; confirm before adding); see chat.
-- Deduped to one row each: GrandTen, Remnant Brewing, Brickbottom, Hingham
--   Farmers Market, Ames Street Studios (= former "East End Clay").
--
-- metro_area left to table default ('boston'); geo queries use lat/lng so a few
-- Western-MA / Cape rows being tagged 'boston' is cosmetic. Photos + coords come
-- from backfill_venue_photos.js after insert.

-- 1) DRY-RUN: how many of these are genuinely new? (run before the INSERT)
--    Expect ~120 new; any already present will be skipped by ON CONFLICT.

BEGIN;

INSERT INTO venues (name, city, state, category, public_interest_rank, events_url, website, is_active)
VALUES
  -- ── Suffolk ─────────────────────────────────────────────────────────────
  ('Boston Pops', 'Boston', 'MA', 'music_venue', 91, NULL, NULL, true),
  ('World Music / CRASHarts', 'Boston', 'MA', 'music_venue', 86, NULL, NULL, true),
  ('Celebrity Series of Boston', 'Boston', 'MA', 'music_venue', 84, NULL, NULL, true),
  ('Boston Lyric Opera', 'Boston', 'MA', 'music_venue', 83, NULL, NULL, true),
  ('Company One Theatre', 'Boston', 'MA', 'theater', 80, NULL, NULL, true),
  ('Boston Early Music Festival', 'Boston', 'MA', 'music_venue', 75, NULL, NULL, true),
  ('GrandTen Distilling', 'Boston', 'MA', 'classes', 74, NULL, NULL, true),
  ('BSA Space (Boston Society of Architects)', 'Boston', 'MA', 'arts_center', 72, NULL, NULL, true),
  ('The Cyclorama at Boston Center for the Arts', 'Boston', 'MA', 'arts_center', 71, NULL, NULL, true),
  ('National Center for Afro-American Artists', 'Boston', 'MA', 'museum', 70, NULL, NULL, true),
  ('Boston Calling Music Festival', 'Boston', 'MA', 'public_space', 68, NULL, NULL, true),
  ('Boston Harbor Now', 'Boston', 'MA', 'waterfront', 65, NULL, NULL, true),

  -- ── Middlesex ───────────────────────────────────────────────────────────
  ('The Rockwell', 'Somerville', 'MA', 'music_venue', 81, NULL, NULL, true),
  ('Somerville Arts Council', 'Somerville', 'MA', 'arts_center', 79, NULL, NULL, true),
  ('Brickbottom Artists Building', 'Somerville', 'MA', 'arts_center', 71, NULL, NULL, true),
  ('Mill No. 5', 'Lowell', 'MA', 'arts_center', 70, NULL, NULL, true),
  ('Lowell Folk Festival', 'Lowell', 'MA', 'public_space', 69, NULL, NULL, true),
  ('Remnant Brewing', 'Somerville', 'MA', 'brewery', 73, NULL, NULL, true),
  ('Crystal Ballroom', 'Somerville', 'MA', 'music_venue', 65, NULL, NULL, true),

  -- ── Norfolk ─────────────────────────────────────────────────────────────
  ('Dedham Community Theatre', 'Dedham', 'MA', 'theater', 71, NULL, NULL, true),
  ('Sharon Community Theatre', 'Sharon', 'MA', 'theater', 63, NULL, NULL, true),
  ('Quincy Center Farmers Market', 'Quincy', 'MA', 'market', 61, NULL, NULL, true),
  ('Needham History Center & Museum', 'Needham', 'MA', 'museum', 60, NULL, NULL, true),
  ('Canton Historical Museum', 'Canton', 'MA', 'museum', 57, NULL, NULL, true),
  ('Scituate Harbor Farmers Market', 'Scituate', 'MA', 'market', 54, NULL, NULL, true),
  ('Braintree Farmers Market', 'Braintree', 'MA', 'market', 52, NULL, NULL, true),
  ('Westwood Cultural Council', 'Westwood', 'MA', 'public_space', 45, NULL, NULL, true),

  -- ── Essex ───────────────────────────────────────────────────────────────
  ('Cape Ann Museum', 'Gloucester', 'MA', 'museum', 82, NULL, NULL, true),
  ('Salem Theatre Company', 'Salem', 'MA', 'theater', 76, NULL, NULL, true),
  ('Griffin Museum of Photography', 'Winchester', 'MA', 'museum', 70, NULL, NULL, true),
  ('Ipswich Museum', 'Ipswich', 'MA', 'museum', 65, NULL, NULL, true),
  ('Marblehead Little Theatre', 'Marblehead', 'MA', 'theater', 64, NULL, NULL, true),
  ('Newburyport Art Association', 'Newburyport', 'MA', 'arts_center', 63, NULL, NULL, true),
  ('Revolving Door Artists', 'Lawrence', 'MA', 'arts_center', 62, NULL, NULL, true),
  ('Haverhill Arts', 'Haverhill', 'MA', 'arts_center', 58, NULL, NULL, true),
  ('Cape Ann Farmers Market', 'Gloucester', 'MA', 'market', 57, NULL, NULL, true),
  ('Marblehead Farmers Market', 'Marblehead', 'MA', 'market', 54, NULL, NULL, true),
  ('Newburyport Farmers Market', 'Newburyport', 'MA', 'market', 54, NULL, NULL, true),

  -- ── Plymouth ────────────────────────────────────────────────────────────
  ('Plymouth Philharmonic Orchestra', 'Plymouth', 'MA', 'music_venue', 78, NULL, NULL, true),
  ('World''s End (Trustees of Reservations)', 'Hingham', 'MA', 'park', 76, NULL, NULL, true),
  ('Brockton Symphony Orchestra', 'Brockton', 'MA', 'music_venue', 70, NULL, NULL, true),
  ('South Shore Natural Science Center', 'Norwell', 'MA', 'park', 67, NULL, NULL, true),
  ('Duxbury Rural & Historical Society', 'Duxbury', 'MA', 'historic_site', 66, NULL, NULL, true),
  ('Massasoit Community College Arts', 'Brockton', 'MA', 'arts_center', 62, NULL, NULL, true),
  ('Hingham Historical Society', 'Hingham', 'MA', 'historic_site', 61, NULL, NULL, true),
  ('Plymouth Antiquarian Society', 'Plymouth', 'MA', 'historic_site', 60, NULL, NULL, true),
  ('Cordage Park Amphitheater', 'Plymouth', 'MA', 'music_venue', 59, NULL, NULL, true),
  ('South Shore Community Theatre', 'Norwell', 'MA', 'theater', 58, NULL, NULL, true),
  ('Duxbury Farmers Market', 'Duxbury', 'MA', 'market', 54, NULL, NULL, true),
  ('Hingham Farmers Market', 'Hingham', 'MA', 'market', 53, NULL, NULL, true),

  -- ── Breweries / cideries ─────────────────────────────────────────────────
  ('Exhibit A Brewing Company', 'Framingham', 'MA', 'brewery', 74, NULL, NULL, true),
  ('Winter Hill Brewing Company', 'Somerville', 'MA', 'brewery', 72, NULL, NULL, true),
  ('Turtle Swamp Brewing', 'Jamaica Plain', 'MA', 'brewery', 71, NULL, NULL, true),
  ('Backlash Beer Company', 'Roxbury', 'MA', 'brewery', 70, NULL, NULL, true),
  ('Springdale Beer Co', 'Holyoke', 'MA', 'brewery', 69, NULL, NULL, true),
  ('Downeast Cider House', 'East Boston', 'MA', 'brewery', 68, NULL, NULL, true),
  ('Redemption Rock Brewing', 'Worcester', 'MA', 'brewery', 68, NULL, NULL, true),
  ('Artifact Cider Project', 'Cambridge', 'MA', 'brewery', 67, NULL, NULL, true),
  ('Ipswich Ale Brewery', 'Ipswich', 'MA', 'brewery', 67, NULL, NULL, true),
  ('Bent Water Brewing', 'Lynn', 'MA', 'brewery', 66, NULL, NULL, true),
  ('Bone Up Brewing', 'Everett', 'MA', 'brewery', 65, NULL, NULL, true),
  ('Small Change Brewing', 'Lowell', 'MA', 'brewery', 64, NULL, NULL, true),
  ('Aeronaut Fenway', 'Boston', 'MA', 'brewery', 63, NULL, NULL, true),
  ('Enlightenment Ales', 'Roxbury', 'MA', 'brewery', 62, NULL, NULL, true),

  -- ── Distilleries ─────────────────────────────────────────────────────────
  ('Short Path Distillery', 'Everett', 'MA', 'classes', 72, NULL, NULL, true),
  ('Ryan & Wood Distilleries', 'Gloucester', 'MA', 'tour', 70, NULL, NULL, true),
  ('Privateer Rum', 'Ipswich', 'MA', 'tour', 69, NULL, NULL, true),
  ('Turkey Shore Distilleries', 'Ipswich', 'MA', 'classes', 65, NULL, NULL, true),

  -- ── Wineries ─────────────────────────────────────────────────────────────
  ('Westport Rivers Winery', 'Westport', 'MA', 'winery', 74, NULL, NULL, true),
  ('Nashoba Valley Winery', 'Bolton', 'MA', 'winery', 72, NULL, NULL, true),
  ('Truro Vineyards', 'North Truro', 'MA', 'winery', 67, NULL, NULL, true),
  ('Running Brook Vineyards & Winery', 'North Dartmouth', 'MA', 'winery', 66, NULL, NULL, true),
  ('The Urban Grape', 'Boston', 'MA', 'winery', 64, NULL, NULL, true),
  ('The Winery at Twin Hills', 'Sherborn', 'MA', 'winery', 62, NULL, NULL, true),

  -- ── Paint & sip ──────────────────────────────────────────────────────────
  ('The Paint Bar', 'Newton', 'MA', 'classes', 73, NULL, NULL, true),
  ('Muse Paintbar Boston', 'Boston', 'MA', 'classes', 72, NULL, NULL, true),
  ('Pinot''s Palette', 'Framingham', 'MA', 'classes', 68, NULL, NULL, true),
  ('Art Bar Boston', 'Jamaica Plain', 'MA', 'classes', 65, NULL, NULL, true),
  ('Wine & Design', 'Burlington', 'MA', 'classes', 63, NULL, NULL, true),
  ('Unwind Studio', 'Salem', 'MA', 'classes', 63, NULL, NULL, true),
  ('Bottle & Bottega', 'Boston', 'MA', 'classes', 62, NULL, NULL, true),

  -- ── Pottery / ceramics ───────────────────────────────────────────────────
  ('Pinch Pottery', 'Somerville', 'MA', 'classes', 70, NULL, NULL, true),
  ('Clay Path Studio', 'Newburyport', 'MA', 'classes', 68, NULL, NULL, true),
  ('Ames Street Studios', 'Cambridge', 'MA', 'classes', 68, NULL, NULL, true),
  ('Urban Pottery Boston', 'Boston', 'MA', 'classes', 66, NULL, NULL, true),
  ('New Ash Ceramics', 'Somerville', 'MA', 'classes', 63, NULL, NULL, true),
  ('North Shore Pottery', 'Beverly', 'MA', 'classes', 62, NULL, NULL, true),
  ('Duxbury Pottery', 'Duxbury', 'MA', 'classes', 58, NULL, NULL, true),

  -- ── Glass ────────────────────────────────────────────────────────────────
  ('New England Glassworks', 'Waltham', 'MA', 'classes', 67, NULL, NULL, true),
  ('Glassblowing Studio at The Studio', 'Boston', 'MA', 'classes', 64, NULL, NULL, true),

  -- ── Axe throwing ─────────────────────────────────────────────────────────
  ('Urban Axes Boston', 'Boston', 'MA', 'classes', 73, NULL, NULL, true),
  ('Backyard Axe Throwing League', 'Boston', 'MA', 'classes', 66, NULL, NULL, true),
  ('Lumber Baron Axe Throwing', 'Salem', 'MA', 'classes', 64, NULL, NULL, true),

  -- ── Cheese / food classes ────────────────────────────────────────────────
  ('Formaggio Kitchen Cambridge', 'Cambridge', 'MA', 'classes', 68, NULL, NULL, true),
  ('The Cheese Shop of Concord', 'Concord', 'MA', 'classes', 65, NULL, NULL, true),

  -- ── Artist studios / makerspaces ─────────────────────────────────────────
  ('The Studio at Midway', 'Boston', 'MA', 'arts_center', 66, NULL, NULL, true),
  ('Vernon Street Studios', 'Somerville', 'MA', 'arts_center', 65, NULL, NULL, true),
  ('Makeshift Boston', 'Boston', 'MA', 'classes', 64, NULL, NULL, true),
  ('The Distillery (artist studios)', 'Boston', 'MA', 'arts_center', 63, NULL, NULL, true),

  -- ── Galleries ────────────────────────────────────────────────────────────
  ('Gallery NAGA', 'Boston', 'MA', 'arts_center', 63, NULL, NULL, true),
  ('Bromfield Gallery', 'Boston', 'MA', 'arts_center', 63, NULL, NULL, true),
  ('Laconia Gallery', 'Boston', 'MA', 'arts_center', 62, NULL, NULL, true),
  ('Kingston Gallery', 'Boston', 'MA', 'arts_center', 62, NULL, NULL, true),
  ('Howard Yezerski Gallery', 'Boston', 'MA', 'arts_center', 61, NULL, NULL, true),
  ('Abigail Ogilvy Gallery', 'Boston', 'MA', 'arts_center', 61, NULL, NULL, true),
  ('Arden Gallery', 'Boston', 'MA', 'arts_center', 60, NULL, NULL, true),
  ('Fountain Street Fine Art', 'Boston', 'MA', 'arts_center', 60, NULL, NULL, true),
  ('Chase Young Gallery', 'Boston', 'MA', 'arts_center', 60, NULL, NULL, true),

  -- ── Craft / creative classes ─────────────────────────────────────────────
  ('Grub Street', 'Boston', 'MA', 'classes', 69, NULL, NULL, true),
  ('The Eliot School of Fine and Applied Arts', 'Jamaica Plain', 'MA', 'classes', 68, NULL, NULL, true),
  ('Boston Center for Adult Education', 'Boston', 'MA', 'classes', 67, NULL, NULL, true),
  ('The Fiber Cafe', 'Cambridge', 'MA', 'classes', 62, NULL, NULL, true),
  ('Fermentation Station', 'Westborough', 'MA', 'classes', 62, NULL, NULL, true),
  ('Wax & Wild', 'Boston', 'MA', 'classes', 61, NULL, NULL, true),
  ('Fiber to Fabric', 'Boston', 'MA', 'classes', 60, NULL, NULL, true),
  ('Bees Knees Supply', 'Cambridge', 'MA', 'classes', 60, NULL, NULL, true),
  ('Craft + Cider', 'Cambridge', 'MA', 'classes', 60, NULL, NULL, true),
  ('Soprafina Marketcaffe', 'Boston', 'MA', 'classes', 58, NULL, NULL, true),
  ('Yarn Underground', 'Somerville', 'MA', 'classes', 58, NULL, NULL, true),
  ('The Leather Soul', 'Boston', 'MA', 'classes', 58, NULL, NULL, true),

  -- ── Bookstores ───────────────────────────────────────────────────────────
  ('Trident Booksellers & Cafe', 'Boston', 'MA', 'bookstore', 72, NULL, NULL, true),
  ('An Unlikely Story Bookstore & Cafe', 'Plainville', 'MA', 'bookstore', 67, NULL, NULL, true),
  ('Copper Dog Books', 'Beverly', 'MA', 'bookstore', 64, NULL, NULL, true),
  ('Eight Cousins Books', 'Falmouth', 'MA', 'bookstore', 64, NULL, NULL, true),
  ('Buttonwood Books & Toys', 'Cohasset', 'MA', 'bookstore', 63, NULL, NULL, true),
  ('The Book Oasis', 'Stoneham', 'MA', 'bookstore', 63, NULL, NULL, true),

  -- ── Farms / misc ─────────────────────────────────────────────────────────
  ('Smolak Farms', 'North Andover', 'MA', 'park', 70, NULL, NULL, true),
  ('Tougas Family Farm', 'Northborough', 'MA', 'park', 66, NULL, NULL, true),
  ('Stow Acres', 'Stow', 'MA', 'park', 63, NULL, NULL, true),
  ('North Shore Riders', 'Hamilton', 'MA', 'classes', 62, NULL, NULL, true),
  ('Fancy''s Farm', 'Osterville', 'MA', 'park', 59, NULL, NULL, true)
ON CONFLICT (name, city) DO NOTHING;

-- 2) VERIFY before COMMIT: how many were actually inserted this transaction?
--    SELECT count(*) FROM venues WHERE created_at >= date_trunc('day', now());

COMMIT;

-- ── ROLLBACK (if needed, within the soak window) ─────────────────────────────
-- Deletes ONLY rows added by this op: never-crawled, no events_url, created today.
-- DELETE FROM venues
-- WHERE events_url IS NULL
--   AND last_crawled_at IS NULL
--   AND created_at >= date_trunc('day', now())
--   AND state = 'MA';   -- tighten with the (name,city) list if other inserts ran today
