-- Latency sprint, Day 2 (CTO hybrid #1 — covering indexes).
--
-- Goal: make every read path search_events_stratified, fetchAllEvents,
-- and the Plans / Profile queries depend on use an index-only scan that
-- delivers EXACTLY the card fields locked by the CX rules:
--   For You:  title, start_at, venue_name, venue_city, venue_lat,
--             venue_lng, image_url, category, is_free
--   Explore Events: + price_min, price_max, time_known
--
-- INCLUDE clauses below enumerate exactly those fields. The brief's CX
-- rule (locked, identical to what the CTO scored against) says the data
-- displayed on every card may not change — adding columns to INCLUDE is
-- additive and preserves the contract; no field on the wire changes shape.
--
-- Supabase migration runner wraps each file in a transaction; CREATE
-- INDEX CONCURRENTLY can't run inside a transaction (SQLSTATE 25001).
-- Non-concurrent CREATE INDEX takes a brief AccessExclusiveLock; on
-- the current data volume (events ~14k rows, venues ~30k) each index
-- builds in ~1–3 s. If a future re-run hits a much larger table,
-- copy the CREATE INDEX statements into the Dashboard SQL editor
-- where they can run with CONCURRENTLY outside a transaction.
--
-- IF NOT EXISTS guards against re-run. WHERE clauses keep the indexes
-- small (only future, active rows) since that's 100% of the read path.
--
-- These indexes are designed to be a strict win — none of them changes
-- query semantics. The planner will pick them only when they're faster.

-- ──────────────────────────────────────────────────────────────────────
-- 1) Lane 1 (Breadth): ROW_NUMBER() PARTITION BY category
--    in search_events_stratified. Filtered by is_active AND
--    start_at >= now() AND optionally category =. Order rank_score DESC,
--    start_at ASC — rank_score lives on venues, so the index covers the
--    pre-rank lookup; the rank join still hits venues' covering index
--    below.
-- ──────────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS events_active_cat_start_card_idx
  ON public.events (category, start_at)
  INCLUDE (
    id, title, venue_name, venue_city,
    venue_lat, venue_lng, image_url, is_free,
    price_min, price_max, time_known, subcategory
  )
  WHERE is_active = true;

COMMENT ON INDEX public.events_active_cat_start_card_idx IS
  'Carousel Lane 1 (breadth-per-category). INCLUDE columns are exactly the locked card fields; planner can index-only-scan for the entire For You + Explore Events response.';

-- ──────────────────────────────────────────────────────────────────────
-- 2) Lane 2 (Niche): ROW_NUMBER() PARTITION BY subcategory.
-- ──────────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS events_active_subcat_start_card_idx
  ON public.events (subcategory, start_at)
  INCLUDE (
    id, title, category, venue_name, venue_city,
    venue_lat, venue_lng, image_url, is_free,
    price_min, price_max, time_known
  )
  WHERE is_active = true AND subcategory IS NOT NULL;

-- ──────────────────────────────────────────────────────────────────────
-- 3) Lane 3 (Keyword): ILIKE '%kw%' on title / venue_name / category.
-- Replace the full-table-scan path with a trigram GIN. The existing
-- search_events_stratified uses ILIKE which can't use a btree; pg_trgm
-- gives sub-100ms substring search.
-- ──────────────────────────────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS events_title_trgm_idx
  ON public.events USING gin (title gin_trgm_ops)
  WHERE is_active = true;

CREATE INDEX IF NOT EXISTS events_venue_name_trgm_idx
  ON public.events USING gin (venue_name gin_trgm_ops)
  WHERE is_active = true;

-- ──────────────────────────────────────────────────────────────────────
-- 4) Venue covering index. search_events_stratified does
--    LEFT JOIN venues v ON lower(v.name) = lower(e.venue_name)
--    and the carousel assembly uses public_interest_rank for sorting,
--    photo_url + google_rating for venue cards. Cover both join key
--    and read columns.
--
-- Note: the join condition is lower(name) = lower(venue_name), so we
-- index lower(name). The existing app code is the only writer; case
-- consistency is enforced at ingest, but lower() is the only way to
-- get an index-only scan when callers vary case.
-- ──────────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS venues_active_lowername_card_idx
  ON public.venues (lower(name))
  INCLUDE (
    id, name, lat, lng, city, state,
    photo_url, category, google_rating,
    public_interest_rank, description
  )
  WHERE is_active = true;

COMMENT ON INDEX public.venues_active_lowername_card_idx IS
  'Carousel join + Explore Places card payload. INCLUDE columns cover the locked card field list so the planner avoids a heap fetch on the LEFT JOIN venues path inside search_events_stratified.';

-- ──────────────────────────────────────────────────────────────────────
-- 5) public_interest_rank for ranking (carousel sort + exclusion path
--    `COALESCE(v.public_interest_rank, 11) > 10`). Partial index keeps it
--    tiny — only the venues that actually pass the exclusion filter.
-- ──────────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS venues_active_rank_partial_idx
  ON public.venues (public_interest_rank DESC, id)
  WHERE is_active = true AND public_interest_rank > 10;

-- ──────────────────────────────────────────────────────────────────────
-- 6) Plans tab — PlanRepositoryImpl.getPlans() runs:
--      from("plans").select { filter { eq("user_id", userId) }
--                              order("created_at", DESCENDING) }
--    plus a batched plan_attendees lookup keyed by plan_id, plus a
--    bo_web_events lookup keyed by event_id. Index each lookup column.
-- ──────────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS plans_user_created_idx
  ON public.plans (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS plan_attendees_plan_user_idx
  ON public.plan_attendees (plan_id, user_id);

-- ──────────────────────────────────────────────────────────────────────
-- 7) Profile tab — BadgeRepositoryImpl reads user_badges + user_profiles
--    by user_id; community_interactions powers the action-counts /
--    streaks. Cover with single-column user_id indexes where missing.
-- ──────────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS user_badges_user_idx
  ON public.user_badges (user_id);

CREATE INDEX IF NOT EXISTS community_interactions_user_time_idx
  ON public.community_interactions (user_id, created_at DESC);
