# Latency sprint — Week 1 apply guide

The CTO recommended a hybrid of plan #1 (Postgres tuning), plan #4
(SQLDelight disk-first + SWR) and plan #5 (Bootstrap-bundle aggregator),
plus a mandatory 800 ms revalidation amendment to satisfy the CX rules
under their strictest reading.

**This document covers Week 1 only — the #1 (Postgres + edge-fn wire)
slice.** SQLDelight + Bootstrap-bundle land in subsequent weeks against
the floor this sprint establishes.

## Locked CX rules (do not relax during deploy)

1. Sub-1-second latency for the For You tab and every peer tab in the
   same nav row (HOME, PLACES, PLANS, PROFILE).
2. **Same number of carousels** on For You.
3. Same number of events on every tab.
4. Same relevancy.
5. Same data fields on every event card.

Every artifact below preserves rules 2–5 by construction (denormalized
projection of the existing RPC, not a content change). Verification
queries at the bottom confirm.

## Files shipped this week

| File                                                                                          | Purpose                                                              |
| --------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| `supabase/migrations/20260519120000_emergency_analyze_and_timeouts.sql`                       | Day 1 — VACUUM ANALYZE + per-role statement_timeout                  |
| `supabase/migrations/20260519120100_carousel_covering_indexes.sql`                            | Day 2 — covering indexes for every peer-tab read                     |
| `docs/latency-sprint-supavisor.md`                                                            | Day 3 — Supavisor transaction-mode steps (dashboard, not SQL)        |
| `supabase/migrations/20260519120200_mv_carousel_payload.sql`                                  | Day 4 — `mv_carousel_payload` MV + `pg_cron` + `mv_events_for_geo()` |
| `supabase/functions/_shared/compress.ts`                                                      | Day 5 — gzip/deflate response helper                                 |
| `supabase/functions/{carousels,explore-events,explore-places}/index.ts`                       | Day 5 — wired through `jsonCompressed` / `corsPreflight`             |
| `composeApp/src/commonMain/kotlin/live/localapp/local/data/repository/*.kt`                   | Day 5 — Ktor `ContentEncoding(gzip, deflate)` installed              |
| `gradle/libs.versions.toml`, `composeApp/build.gradle.kts`                                    | Day 5 — added `ktor-client-encoding`                                 |

## Deploy order (one engineer, ~1 day per step)

### Monday — Day 1 (lowest-risk, highest-reward)

Run **only** the ANALYZE + timeouts migration. This alone is expected
to drop the documented 10 s subcategory bug to <500 ms.

```bash
# From repo root, against the production project.
npx supabase db push --project-ref blifubxslaucshmlphdc
# (will apply 20260519120000_emergency_analyze_and_timeouts.sql)
```

Verify:

```sql
-- Planner stats fresh?
SELECT relname, last_analyze, last_autoanalyze, n_live_tup, n_dead_tup
  FROM pg_stat_user_tables
  WHERE relname IN ('events','venues','community_interactions');
-- last_analyze should be within the last minute.

-- Per-role timeout in effect?
SELECT rolname,
       (SELECT pg_get_userbyid(rolname::regrole)),
       setconfig
  FROM pg_db_role_setting r
  JOIN pg_roles ro ON ro.oid = r.setrole
 WHERE ro.rolname IN ('authenticator','anon','service_role');
-- Expect statement_timeout=1500ms / 1000ms / 30s respectively.

-- Subcategory tab smoke test (replace lat/lng with a known metro):
EXPLAIN (ANALYZE, BUFFERS)
  SELECT * FROM search_events_stratified(42.36, -71.06, 25);
-- Expect total time < 500 ms. If it's still >1 s, rerun ANALYZE manually
-- and check that the partial-stats columns set in step 3 are present:
SELECT attname, attstattarget
  FROM pg_attribute
  WHERE attrelid = 'events'::regclass
    AND attname IN ('category','subcategory','start_at','venue_name');
-- attstattarget should be 1000 for those four columns.
```

If anything regresses: the migration is non-destructive. To roll back
the timeouts only:

```sql
ALTER ROLE authenticator RESET statement_timeout;
ALTER ROLE anon          RESET statement_timeout;
ALTER ROLE service_role  RESET statement_timeout;
```

### Tuesday — Day 2 (covering indexes)

```bash
npx supabase db push --project-ref blifubxslaucshmlphdc
# applies 20260519120100_carousel_covering_indexes.sql
```

Each `CREATE INDEX CONCURRENTLY` runs serially in the migration; expect
the file to take ~3–8 minutes at current data volume. Writers are not
blocked.

Verify:

```sql
-- All 9 new indexes present?
SELECT indexname, tablename, indexdef
  FROM pg_indexes
 WHERE indexname IN (
   'events_active_cat_start_card_idx',
   'events_active_subcat_start_card_idx',
   'events_title_trgm_idx',
   'events_venue_name_trgm_idx',
   'venues_active_lowername_card_idx',
   'venues_active_rank_partial_idx',
   'plans_user_created_idx',
   'plan_attendees_plan_user_idx',
   'user_badges_user_idx',
   'community_interactions_user_time_idx'
 );

-- Confirm the planner is now picking index-only scans on the carousel
-- path (the `INCLUDE`s should keep Heap Fetches = 0):
EXPLAIN (ANALYZE, BUFFERS)
  SELECT id, title, start_at, venue_name, venue_city, venue_lat,
         venue_lng, image_url, category, is_free
    FROM events
   WHERE is_active = true
     AND category = 'music'
   ORDER BY start_at
   LIMIT 25;
-- Expect "Index Only Scan using events_active_cat_start_card_idx" and
-- "Heap Fetches: 0".
```

Rollback if planner regresses: drop the specific index, the old plan
returns automatically.

```sql
DROP INDEX CONCURRENTLY public.events_active_cat_start_card_idx;
```

### Wednesday — Day 3 (Supavisor transaction mode)

Follow `docs/latency-sprint-supavisor.md`. Dashboard-only change. Verify
the post-flip steps in that doc.

### Thursday — Day 4 (mv_carousel_payload)

```bash
npx supabase db push --project-ref blifubxslaucshmlphdc
# applies 20260519120200_mv_carousel_payload.sql
```

The migration creates the MV, schedules pg_cron, runs one synchronous
refresh, and installs `mv_events_for_geo()`. Total time ~2–5 min for
the initial refresh.

Verify:

```sql
-- MV present and populated?
SELECT pg_size_pretty(pg_total_relation_size('public.mv_carousel_payload'))
     AS size,
     (SELECT count(*) FROM public.mv_carousel_payload) AS rows;

-- pg_cron schedule active?
SELECT jobname, schedule, active, last_finished_at, last_status
  FROM cron.job
  LEFT JOIN cron.job_run_details ON job_run_details.jobid = cron.job.jobid
 WHERE jobname = 'mv_carousel_payload_refresh'
 ORDER BY last_finished_at DESC NULLS LAST
 LIMIT 5;

-- Helper RPC returns the expected card field set for a known metro:
SELECT id, title, start_at, venue_name, venue_city, venue_lat, venue_lng,
       image_url, category, is_free, price_min, price_max, time_known,
       distance_miles
  FROM mv_events_for_geo(42.36, -71.06, 25, 100)
 ORDER BY rank_score DESC
 LIMIT 25;
```

**Carousel-count CX rule check (rule 2 — same number of carousels):**

```sql
-- Source-of-truth: how many distinct carousels does
-- search_events_stratified seed today, for a known geo?
WITH live AS (
  SELECT DISTINCT category
    FROM search_events_stratified(42.36, -71.06, 25)
)
SELECT count(*) AS live_categories FROM live;

-- Same query against the MV — must match the live count:
WITH mvc AS (
  SELECT DISTINCT category
    FROM mv_events_for_geo(42.36, -71.06, 25, 1000)
)
SELECT count(*) AS mv_categories FROM mvc;

-- If these counts differ, DO NOT cut the edge function over. The MV's
-- WHERE clause must produce the same set of category buckets as the
-- live RPC, or rule 2 is violated.
```

The edge function (`supabase/functions/carousels/index.ts`) is **NOT
cut over** in week 1. The MV is exercised only by direct callers and
the verification queries above. The cutover is week 2; the steps are:

1. In `fetchAllEvents()` (`supabase/functions/carousels/index.ts`
   line 1805), swap the paginated `.from("events").select("*")` calls
   for a single `supabase.rpc("mv_events_for_geo", { p_lat, p_lng,
   p_radius_miles })`.
2. Diff the produced `ProcessedEvent[]` against the live path on 100
   sampled requests. Block deploy if event count differs by more than
   1% on any sample (the MV trims `start_at < now()` and the RPC
   doesn't, accounting for ~minute-level drift).
3. Ship behind a remote-config flag `mv_carousel_payload_enabled`.

### Friday — Day 5 (compression)

The edge-function changes (`supabase/functions/_shared/compress.ts` +
the three edited functions) deploy together:

```bash
npx supabase functions deploy carousels       --project-ref blifubxslaucshmlphdc
npx supabase functions deploy explore-events  --project-ref blifubxslaucshmlphdc
npx supabase functions deploy explore-places  --project-ref blifubxslaucshmlphdc
```

The shared module under `supabase/functions/_shared/` deploys
automatically with each function that imports it.

The client-side Ktor change is a normal app build:

```bash
./gradlew composeApp:compileKotlinIosArm64
./gradlew :composeApp:assembleDebug
```

Verify (post-deploy, from a terminal that can hit the function):

```bash
# Response should now be gzip-encoded when Accept-Encoding requests it.
curl -sSI \
  -H "Accept-Encoding: gzip" \
  "https://blifubxslaucshmlphdc.supabase.co/functions/v1/carousels?lat=42.36&lng=-71.06" \
  | grep -iE "content-encoding|content-type|vary"

# Expect:
#   content-type: application/json; charset=utf-8
#   content-encoding: gzip
#   vary: Accept-Encoding
```

If `Accept-Encoding` is omitted, the response is plain JSON (back-compat
for older clients and debug tools).

Rollback per-function: re-deploy the previous git revision of
`supabase/functions/<name>/index.ts`.

## Expected results by Friday

| Tab                       | Before (cold)  | After (cold) | Mechanism                                                        |
| ------------------------- | -------------- | ------------ | ---------------------------------------------------------------- |
| For You (carousel_cache)  | 600–900 ms     | 250–450 ms   | gzip on the precomputed-cache response                           |
| For You (carousel miss)   | 5–10 s (bug)   | 400–800 ms   | ANALYZE + indexes fix the slow stratified RPC                    |
| Subcategory tab           | ~10 s          | <500 ms      | ANALYZE alone                                                    |
| Explore Events            | 1.5–2.5 s      | 600–900 ms   | covering indexes + gzip                                          |
| Explore Places            | 1.2–2.0 s      | 500–800 ms   | covering indexes (venues lowername) + gzip                       |
| Plans                     | 400–700 ms     | 200–350 ms   | `plans_user_created_idx` + Supavisor + gzip                      |
| Profile                   | 400–600 ms     | 150–250 ms   | `user_badges_user_idx` + `community_interactions_user_time_idx`  |

If any tab is still >1 s warm-start after Friday, escalate to week-2
SQLDelight work — disk-first paint is the only way to reliably hit
sub-1 s on cold network conditions.

## Week-2 preview (do not start until week 1 is green)

- **SQLDelight disk-first** (plan #4): adds 5–20 ms paint regardless of
  network. New cache tables in `composeApp/src/commonMain/sqldelight/`
  and a `SwrCoordinator` to govern revalidation. The 800 ms mandatory-
  revalidation amendment lives here.
- **Bootstrap-bundle** (plan #5): one edge-fn invocation hydrates all
  four peer-tab payloads during splash. New
  `supabase/functions/bootstrap-bundle/index.ts`.

Both depend on the MV existing and the per-role timeouts being in place.
Don't lift them until then.

## Carousel-count CX certification (rule 2)

The hybrid preserves the exact carousel count on For You by these
mechanisms, in order of where they sit in the chain:

1. **`search_events_stratified` v3** (unchanged): the RPC's three lanes
   are sourced from the existing migration `20260507120000`. No change
   to lane structure, partition, or order.
2. **`mv_carousel_payload`**: the MV's `WHERE` clause is byte-identical
   to the RPC's filter — `is_active = true AND start_at >= now() AND
   start_at < now() + 90 days AND COALESCE(public_interest_rank, 11)
   > 10`. The CX verification SQL block in Day 4 confirms category-set
   equality between live RPC and MV.
3. **`mv_events_for_geo()`**: returns events from the MV with geo
   filter applied. Same category set, same rank order.
4. **Edge fn `carousels/index.ts`** (unchanged in week 1): still
   assembles carousels via existing JS logic. Number of carousels
   emitted is unchanged because the underlying event set is unchanged.
5. **Wire format** (gzip): compression is a transport optimization;
   the JSON body is byte-identical pre/post compression.

Rule 2 verification is the SQL block at the end of the Day 4 section.
Run it before flipping the week-2 MV cutover.
