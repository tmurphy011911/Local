-- Weekly per-venue upcoming-event-count history, so we can detect a venue that
-- SILENTLY goes dark — i.e. one that used to carry a healthy stream of upcoming
-- events and now carries ~none, WITHOUT any hard crawl error. The existing
-- signal (venues.crawl_success_rate) only catches hard failures (site down,
-- 404); a venue whose calendar moved to a JS widget or a new URL still returns
-- 200 OK on the old page, so the crawl "succeeds" while extracting zero events.
-- That green-but-empty case is invisible today (e.g. ICA Boston).
--
-- venue_health_snapshot.js writes one row per watched venue per weekly run, then
-- compares each venue's newest count against its own recent baseline to flag
-- sharp drops. Event-to-venue linkage matches the rest of the pipeline: by
-- venue_name + state, not an FK (events has no venue_id). We still store the
-- venue's id here for a stable baseline key even if its name later drifts.
--
-- Additive / backward-compatible: a brand-new table + indexes only. No existing
-- column is touched, so every live client keeps working unchanged.

create table if not exists venue_event_snapshots (
  id                   bigint generated always as identity primary key,
  venue_id             uuid,
  venue_name           text not null,
  venue_state          text,
  public_interest_rank integer,
  upcoming_count       integer not null,
  snapshot_date        date not null default (now() at time zone 'utc')::date,
  created_at           timestamptz not null default now()
);

comment on table venue_event_snapshots is
  'Weekly count of active upcoming events per watched venue; baseline history for silent-failure detection (venue_health_snapshot.js).';
comment on column venue_event_snapshots.upcoming_count is
  'Active events (is_active=true, start_at >= snapshot_date) attributed to this venue by name+state, as of snapshot_date.';
comment on column venue_event_snapshots.venue_id is
  'Best-effort stable key for baseline lookups; may be null. Primary event match is still by venue_name+state.';

-- Baseline lookup: a venue's recent snapshots, newest first.
create index if not exists idx_venue_event_snapshots_venue
  on venue_event_snapshots (venue_id, snapshot_date desc);

-- Name+state fallback key, and per-run scans.
create index if not exists idx_venue_event_snapshots_name_state
  on venue_event_snapshots (venue_state, lower(venue_name), snapshot_date desc);

create index if not exists idx_venue_event_snapshots_date
  on venue_event_snapshots (snapshot_date);
