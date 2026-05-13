-- Speed up Explore search.
--
-- The explore-events and explore-places edge functions filter with
-- `ilike '%query%'` patterns (leading wildcard). Without trigram support,
-- Postgres falls back to a sequential scan over the full table on every
-- keystroke — for events (~10K rows) and venues (~9K rows) that adds up to
-- 100-300ms per search call.
--
-- pg_trgm + GIN indexes let those ilike patterns use an index scan instead,
-- typically 50-200x faster on tables this size.

create extension if not exists pg_trgm;

-- ── events ──────────────────────────────────────────────────────────────
-- explore-events query uses: title.ilike.%X%, venue_name.ilike.%X%
create index if not exists idx_events_title_trgm
  on events using gin (title gin_trgm_ops);

create index if not exists idx_events_venue_name_trgm
  on events using gin (venue_name gin_trgm_ops);

-- ── venues ──────────────────────────────────────────────────────────────
-- explore-places query uses: name.ilike.%X%, city.ilike.%X%
create index if not exists idx_venues_name_trgm
  on venues using gin (name gin_trgm_ops);

create index if not exists idx_venues_city_trgm
  on venues using gin (city gin_trgm_ops);
