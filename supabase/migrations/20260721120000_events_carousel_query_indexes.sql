-- Carousel per-request query indexes (additive only).
--
-- Every personalized For-You request in the carousels edge function runs
-- per-city / per-venue / per-subcategory fetches against `events` using
-- case-insensitive matches:
--   fetchCityEvents:        venue_city  ILIKE '%<city>%'   (leading wildcard)
--   fetchVenueEvents:       venue_name  ILIKE '%<venue>%'  (leading wildcard)
--   fetchSubcategoryEvents: subcategory ILIKE '<subcat>'   (case-insens. exact)
-- Leading-wildcard ILIKE can never use a btree, so each of these is a
-- sequential scan of `events` — roughly 10 of them per personalized request,
-- which is both the For-You latency tail and steady IO pressure on the Nano
-- instance (see 2026-07-20 IO-exhaustion outage).
--
-- pg_trgm GIN indexes serve ILIKE with or without wildcards. Partial on
-- is_active = true (every one of these queries filters on it) to keep the
-- indexes small and cut write amplification from the crawl pipeline's bulk
-- upserts — inactive/expired rows are the long tail of the table.

create extension if not exists pg_trgm;

create index if not exists idx_events_active_venue_city_trgm
  on public.events using gin (venue_city gin_trgm_ops)
  where is_active = true;

create index if not exists idx_events_active_venue_name_trgm
  on public.events using gin (venue_name gin_trgm_ops)
  where is_active = true;

create index if not exists idx_events_active_subcategory_trgm
  on public.events using gin (subcategory gin_trgm_ops)
  where is_active = true;
