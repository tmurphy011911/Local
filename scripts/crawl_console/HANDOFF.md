# Handoff — venue coverage push (crawl console)

Paste-in brief for a Claude Code session running **on the Mac mini** (which has
DB access + the service key; the cloud web session did not). All code below is
already committed to branch **`claude/remote-control-n8vyx1`**.

## Mission

Get **"0 events %" under 20% for every live state**, for the **PIR > 70** venue
cohort, working **MA → PA → NY**. ("0 events" = an active venue with zero active
upcoming events. PIR = `venues.public_interest_rank`.)

Backlog to clear (PIR>70): **MA** 297 venues, ~38% zero (~55 to rescue) ·
**PA** 252, ~36% (~41) · **NY** 436, ~79% (~258, the big one).

## The tool: local crawl console

`scripts/crawl_console/` — a localhost web app to fix venues one at a time.
Run it:
```
export SUPABASE_SERVICE_KEY=eyJ...        # prod service_role, project blifubxslaucshmlphdc
node scripts/crawl_console/server.js       # → http://localhost:5178
```
Flow: filter venues by **state + Min PIR + coverage (0 events)** → click one →
paste/adjust an events URL → **Test crawl (no writes)** → if good, **Save URL &
crawl for real** (persists `events_url` + upserts events; next scheduled crawl
maintains it).

**Parity:** Test/Save both drive `run_crawls.js`'s `crawlVenue()` — the same
per-venue entrypoint the weekly crawl uses. "Works in the console" == "works
next crawl."

## Changes made to `run_crawls.js` (backward-compatible; CLI path unchanged)

- Factored key init into exported `initSecrets()`.
- Added opt-in **DRY_RUN** mode (default off): `upsertEvents()` /
  `updateVenueCrawlStatus()` capture-instead-of-write, so Test previews exactly
  what a real crawl would save. Exposed `setDryRun` / `drainCapturedRows`.
- Exported `crawlVenue`, `upsertEvents`, `SUPABASE_URL`.
- **Daily date-token expansion**: URLs containing `{DD}`/`{D}` now expand over
  the next `URL_DATE_DAYS_AHEAD` days (default 14) instead of monthly. Fixes
  daily calendars (cinemas etc.). Month-only URLs unchanged.

## Key architecture facts (learned the hard way)

- **No `events.venue_id` FK.** A venue "has events" only if event rows match by
  `venue_state + lower(trim(venue_name))` == venue's `state + name`. So "0
  events" has THREE causes: (A) no `events_url`; (B) has URL but crawl yields
  nothing; (C) crawl works but writes a **mismatched `venue_name`** → events
  exist but don't join → reads as 0. Bucket C is fixed by name reconciliation
  (`match_event_venues.js`), NOT re-crawling.
- **The console does NOT classify.** `normalizeEvent` writes `category:"other",
  subcategory:null`. Taxonomy is a separate step: `node scripts/classify_events.js`
  — incremental/idempotent, auto-targets unclassified rows (active, future,
  `sub_subcategory IS NULL`, empty `tags`, not `no-public-interest`). Run it
  after a session of URL fixes; it sweeps up everything the console added.
- Tool shares one crawler instance (global DRY_RUN state) → **don't run two
  Test/Save actions at once** until the concurrency fix lands (see below).

## Done so far

- **Coolidge Corner Theatre (MA)** — saved
  `https://coolidge.org/showtimes?date={YYYY}-{MM}-{DD}`, JS render ON, **169
  events**. TODO: re-test with JS render OFF; if it still yields, flip
  `needs_stealth` off so future crawls don't burn 15 Scrapfly renders/crawl.

## Immediate worklist

1. **Diagnose 3 venues that returned 0 in the console** (each a different failure
   mode — fetch the page and see where it falls through the ladder in
   `crawlVenue`):
   - MOS exhibits — `https://www.mos.org/visit/exhibits` (Museum of Science is a
     **Ticketure** venue; `/visit/exhibits` is likely the wrong page — find the
     real events/tickets calendar).
   - Chicken Box — `https://thechickenbox.com/calendar/`
   - BSO / Boston Pops — the big Algolia-filtered URL
     (`bso.org/events?performances[refinementList]...`) — JS/SPA with a unix-ts
     range param; probably needs JS render or a different endpoint.
2. Continue MA zero-event venues in the console; then PA, then NY.
3. Run `node scripts/classify_events.js` after each session.

## Useful queries

**Bucket-split the zero-event venues for a state** (swap `'MA'`):
```sql
WITH v AS (
  SELECT id,name,events_url,last_crawled_at,crawl_success_rate,lower(trim(name)) nn
  FROM venues WHERE is_active AND state='MA' AND public_interest_rank>70),
e AS (SELECT lower(trim(venue_name)) nn FROM events
  WHERE is_active AND venue_state='MA' AND start_at>=now() GROUP BY 1),
z AS (SELECT v.* FROM v LEFT JOIN e ON e.nn=v.nn WHERE e.nn IS NULL)
SELECT CASE WHEN events_url IS NULL OR events_url='' THEN 'A: no URL'
            WHEN last_crawled_at IS NULL THEN 'B: never crawled'
            ELSE 'C: crawled, still 0' END bucket,
       count(*), round(avg(crawl_success_rate)::numeric,2) avg_success,
       round(avg(extract(day FROM now()-last_crawled_at))::numeric,0) avg_days
FROM z GROUP BY 1 ORDER BY 1;
```

**Unclassified count** (what next classify run will process):
```sql
SELECT count(*) FROM events
WHERE is_active AND start_at>=now() AND sub_subcategory IS NULL
  AND (tags IS NULL OR cardinality(tags)=0)
  AND subcategory IS DISTINCT FROM 'no-public-interest';
```

## Not-yet-built enhancements (nice-to-haves)

- Crawl the expanded daily URLs **concurrently** (multi-url path in `crawlVenue`
  is currently sequential → a 15-day cinema test takes ~5 min on JS render).
- **Live per-day progress** in the UI instead of a blind spinner.
- **Per-job isolation** so concurrent Test/Save actions can't corrupt each
  other's DRY_RUN state.
- Console **"Classify now"** button (optional; kicks `classify_events.js`).

## Notes

- `index.html` is force-added past a global `*.html` gitignore rule — if it goes
  missing after a checkout, `git checkout <branch> -- scripts/crawl_console/index.html`.
- Server loads `run_crawls.js` once at startup → **restart the server after any
  crawler code change** (Ctrl-C, re-run). `index.html` is read per-request (no
  restart needed).
