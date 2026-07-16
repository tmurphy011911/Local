-- Track events_url discovery attempts so the monthly fix_venue_urls run stops
-- re-spending Claude/Scrapfly tokens on venues it has already tried and failed
-- on. Before this, every monthly run re-attempted every venue missing an
-- events_url — including thousands with no discoverable events page — which
-- burned tokens and blew the job's 6h timeout.
--
-- Additive / backward-compatible: brand-new columns with defaults, plus a
-- partial index for the retry-eligibility scan. No existing column is altered,
-- so every live client keeps working unchanged.

alter table venues add column if not exists events_url_attempts integer not null default 0;
alter table venues add column if not exists events_url_last_attempt timestamptz;
alter table venues add column if not exists events_url_result text;

comment on column venues.events_url_attempts is
  'Number of times fix_venue_urls has tried to discover an events_url for this venue.';
comment on column venues.events_url_last_attempt is
  'When fix_venue_urls last attempted events_url discovery (retry-gate backoff key).';
comment on column venues.events_url_result is
  'Outcome of the last attempt: found | no_events_page | website_down | empty_page | discovered_404.';

-- Speeds up the retry-gate selection: active venues missing an events_url,
-- ordered stalest-first.
create index if not exists idx_venues_events_url_retry
  on venues (events_url_last_attempt)
  where events_url is null and is_active = true;
