-- Composite index for the carousels edge function's bounding-box queries.
-- fetchAllEvents and fetchRawSportsEvents both filter on
--   is_active = true AND start_at >= now() AND venue_lat BETWEEN ... AND venue_lng BETWEEN ...
-- then ORDER BY start_at. Without a covering composite index each of the
-- ~17 parallel queries per request does a seq scan (~1-3s on cold cache).
--
-- CONCURRENTLY so this doesn't lock writes on the events table during deploy.
-- IF NOT EXISTS guards against a re-run; the column order matches the query
-- planner's expected eq/range/range/order shape.

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_events_geo_active
  ON events (is_active, start_at, venue_lat, venue_lng);
