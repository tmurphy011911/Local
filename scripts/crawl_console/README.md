# Crawl Console

A locally-hosted, human-in-the-loop tool for the **"has URL, 0 events"** (and
"no URL") venue backlog. Filter live venues by state + PIR, paste a candidate
events URL, run the **real production crawler** against it with no DB writes,
see exactly what it would extract, and — if it looks good — persist the URL and
the events so the next scheduled crawl keeps the venue green.

## Why it's trustworthy

`/api/test` and `/api/commit` both call `crawlVenue()` in
[`../run_crawls.js`](../run_crawls.js) — the *same* per-venue entrypoint the
weekly crawl uses (platform-detection ladder → Claude HTML fallback →
normalize). So **"works in the console" == "works next crawl."**

- **Test** runs `crawlVenue()` in an opt-in `DRY_RUN` mode: no writes to
  `events` or `venues`; extracted rows are captured in memory and previewed.
- **Save & crawl** writes `events_url` (and the stealth flag) to the venue,
  then runs `crawlVenue()` for real. Because `fetchVenues()` orders by
  "has `events_url`, stalest first," the scheduled crawl picks it up from then
  on with no further action.

## Run it

```bash
# reads/writes PROD by default (same as all crawl scripts)
SUPABASE_SERVICE_KEY=ey... node scripts/crawl_console/server.js
# → open http://localhost:5178
```

Env vars:

| Var | Default | Notes |
|-----|---------|-------|
| `SUPABASE_SERVICE_KEY` | *(required)* | bootstraps everything; other keys (Anthropic, Scrapfly) load from the `secrets` table |
| `PORT` | `5178` | |
| `HOST` | `127.0.0.1` | localhost-only bind |
| `SUPABASE_URL` | prod | set to `https://xbyqwekcanrzovytxmdv.supabase.co` to point at dev |

Requires Node 18+ (uses global `fetch`); the repo's crawls run on Node 22.
No `npm install` — the server uses only Node built-ins.

## Workflow

1. Pick **State** + **Min PIR** + **Coverage** (`0 events` is the backlog).
2. Click a venue. Its current URL prefills the box.
3. Paste/adjust the events URL. Toggle **JS render** for Squarespace / Cloudflare /
   SPA calendars that need a headless browser (routes through Scrapfly).
4. **Test crawl (no writes)** → eyeball the extracted events.
5. If good, **Save URL & crawl for real** → URL + events persisted; the venue
   goes green immediately and stays maintained by the scheduled crawl.

## Notes

- "0 events" uses the pipeline's `state + normalized(venue_name)` join (there is
  no `events.venue_id` FK), matching `venue_health_snapshot.js` and the
  dashboard — so a venue reads as 0 if its crawled events were written under a
  mismatched `venue_name`. If Test extracts events but the venue still shows 0
  after commit, that's a name-mismatch, not a crawl-yield problem
  (see `match_event_venues.js`).
- Comma-separated URLs and `{YYYY}`/`{MM}` date tokens are supported in the URL
  box, exactly as in the `events_url` column.
