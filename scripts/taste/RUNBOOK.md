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

## 5. Full dev backfill (only after step 4 looks right)

```bash
SUPABASE_URL=https://xbyqwekcanrzovytxmdv.supabase.co \
SUPABASE_SERVICE_KEY=<DEV_SERVICE_KEY> \
FORCE=1 \
node scripts/classify_events.js
```

## 6. Prod promotion — later, on the release train

Migrations + classifier promote dev→prod via the normal train (see `RELEASE.md` /
`/change`). The classifier already degrades gracefully if the new columns are
absent (retry-without-columns path in `writeUpdates`), so ordering is forgiving,
but push the migrations before running the prod backfill.
