# Taste Graph — dev runbook (Phase 0 + Phase 1)

Steps that need credentials this branch was built without (dev service key,
Anthropic key, dev DB access). Run these on **dev** (`xbyqwekcanrzovytxmdv`)
first. Nothing here touches prod.

## ⚠️ Safety: the classifier defaults to PROD

`scripts/lib/secrets.js` defaults `SUPABASE_URL` to the **prod** project. Every
classifier command below therefore sets `SUPABASE_URL` to **dev** explicitly.
Do not omit it, or you will tag prod.

Dev URL: `https://xbyqwekcanrzovytxmdv.supabase.co`

---

## 1. Apply the two migrations to dev

```bash
# migration-lint passes (all additive: ADD COLUMN / CREATE TABLE / CREATE INDEX)
supabase link --project-ref xbyqwekcanrzovytxmdv    # link to DEV, not prod
supabase db push
```

Adds: `community_interactions.actor_lat/lng`, `.item_lat/lng`, `.features`;
`event_impressions` table; `events.performers/brands/tag_vocab_version` + GIN
indexes.

## 2. Confirm the Anthropic key exists in the dev `secrets` table

The classifier pulls `ANTHROPIC_API_KEY` from the dev `secrets` table (bootstrapped
by the dev service key). If dev is missing it (it has been before), insert it, or
pass it inline via env as the fallback.

## 3. Cheap eyeball test — 30 events, forced re-tag

```bash
SUPABASE_URL=https://xbyqwekcanrzovytxmdv.supabase.co \
SUPABASE_SERVICE_KEY=<DEV_SERVICE_KEY> \
MAX_EVENTS=30 FORCE=1 \
node scripts/classify_events.js
```

`MAX_EVENTS` caps the run so this costs ~pennies of Haiku. `FORCE=1` re-tags
already-classified rows with the new faceted vocab + entity extraction.

## 4. Inspect the result

```sql
select title, subcategory, tags, performers, brands, tag_vocab_version
from events
where tag_vocab_version = 'v1'
order by updated_at desc
limit 30;
```

Check: 6–15 tags per event, spread across facets; performers/brands populated on
named acts/teams; no junk tokens. Adjust `scripts/taste/tag_vocabulary.js` and
re-run step 3 if needed (bump `TAG_VOCAB_VERSION` when the vocab changes).

## 5. Full backfill — pick the mode

**TAGS_ONLY (recommended for PROD — preserves taxonomy):** enriches existing rows
with the new tags + performers/brands and leaves category/subcategory untouched.
Idempotent (skips rows already on the current vocab). New events keep getting full
classification via the normal weekly run — this is only for the existing backlog.

```bash
SUPABASE_URL=<dev-or-prod-url> \
SUPABASE_SERVICE_KEY=<SERVICE_KEY> \
TAGS_ONLY=1 \
node scripts/classify_events.js
```

**FORCE (full re-classification — dev only / deliberate):** re-derives
category/subcategory/tags for everything. Do NOT run on prod casually — it can flip
existing (good) classifications. Keeps `original_category` for revert.

```bash
SUPABASE_URL=https://xbyqwekcanrzovytxmdv.supabase.co \
SUPABASE_SERVICE_KEY=<DEV_SERVICE_KEY> \
FORCE=1 \
node scripts/classify_events.js
```

Both scope to active + upcoming events. Add `MAX_EVENTS=30` for a capped test run.

## 6. Build taste profiles (Phase 2 — after tagging exists)

Once events carry tags/entities (steps 3–5) and `community_interactions` has some
`actor_lat/lng` (needs a client build with the Phase-0 capture), build the
per-user taste summaries. Pure aggregation — service key only, no Anthropic.

```bash
# migration for the table (additive)
supabase db push          # picks up 20260716150000_user_taste_summary.sql

# dry run first — computes + prints a sample, writes nothing
SUPABASE_URL=https://xbyqwekcanrzovytxmdv.supabase.co \
SUPABASE_SERVICE_KEY=<DEV_SERVICE_KEY> \
DRY_RUN=1 MAX_USERS=10 \
node scripts/taste/build_taste_profiles.js

# real run (writes user_taste_summary)
SUPABASE_URL=https://xbyqwekcanrzovytxmdv.supabase.co \
SUPABASE_SERVICE_KEY=<DEV_SERVICE_KEY> \
node scripts/taste/build_taste_profiles.js
```

The math is unit-tested offline: `node scripts/taste/taste_model.test.js`.
Inspect: `select user_id, interest_1, interest_2, interest_3, travel_propensity,
typical_travel_miles, interaction_count from user_taste_summary limit 20;`

Wire it to run nightly (GitHub Actions / pg_cron) once validated.

## 6b. Prod rollout sequencing (what backfills vs. what's forward-only)

Order matters when running against prod:

1. **Tag events FIRST** (steps 3/5) — then **build profiles** (step 6). The
   interest richness (tag/entity scores) comes from the events' features, so
   profiles built before tagging get only subcategory-level interests.
2. **Interests backfill retroactively.** `build_taste_profiles.js` aggregates
   historical `community_interactions`, so existing users get real
   `interest_1..5` / subcategory / tag / entity scores from day one — no waiting
   for new activity. Interactions on still-existing (even deactivated) event rows
   resolve; interactions on deleted events are skipped.
3. **Travel propensity is FORWARD-ONLY.** It needs `actor_lat/lng` (active
   location at interaction time), a new column the shipped client only stamps
   going forward. Historical rows have it null → `travel_propensity` is null for
   everyone until location-stamped interactions accumulate post-launch. Same for
   `event_impressions` and `not_interested` — all forward-only. This is expected.

## 7. Prod promotion — later, on the release train

Migrations + classifier promote dev→prod via the normal train (see `RELEASE.md` /
`/change`). The classifier already degrades gracefully if the new columns are
absent (retry-without-columns path in `writeUpdates`), so ordering is forgiving,
but push the migrations before running the prod backfill.
