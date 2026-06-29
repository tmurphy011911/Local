# Backend Release Runbook

How backend changes (DB migrations + edge functions) move from **dev** to **prod**
on the monthly release train — without breaking clients still on the old version.

> **The one rule that makes this safe:** a single prod DB + one set of edge
> functions serves **every** App Store version at once. When you promote 1.1.0's
> backend, users still on 1.0.0 hit the *same* DB and *same* functions. Updates
> roll out over days and some users never update. So everything you push to prod
> must be **backward-compatible with the oldest client still in the wild**.
> See [Backend change rules](#backend-change-rules).

---

## Projects

| Env  | Project ref            | Purpose                                  |
|------|------------------------|------------------------------------------|
| prod | `blifubxslaucshmlphdc` | Live App Store traffic. Serves ALL versions. |
| dev  | `xbyqwekcanrzovytxmdv` | Staging for the in-flight `1.x.0` build. |

⚠️ **The CLI is linked to PROD by default** (`supabase/.temp/project-ref`). Always
pass `--project-ref` explicitly on `functions deploy`, and `supabase link` before
`db push`, so you never push to prod thinking it's dev. The commands below are
explicit on purpose — don't shorten them.

---

## Where backend changes live

| Surface        | Authored as                         | Applied with                          |
|----------------|-------------------------------------|---------------------------------------|
| DB schema      | files in `supabase/migrations/`     | `supabase db push`                    |
| Edge functions | dirs in `supabase/functions/`       | `supabase functions deploy <fn>`      |
| Pipeline data  | scripts in `scripts/` (run vs prod) | GitHub Actions / manual; copied to dev by `sync_dev.yml` |

---

## Release-train timeline (July example)

| Phase            | Dates    | Backend action                                      | Target |
|------------------|----------|-----------------------------------------------------|--------|
| Submit 1.0.0     | Jul 1    | none — prod is the baseline                          | —      |
| Develop 1.1.0    | Jul 1–14 | author migrations + edit edge fns; apply locally     | **dev**  |
| Test 1.1.0       | Jul 14–21| `db push` + `functions deploy` to dev; QA dev build  | **dev**  |
| Prod-verify      | Jul 21–28| **promote** the *same* migrations + fns to prod      | **prod** |
| Submit 1.1.0     | Aug 1    | none — backend already live & backward-compatible    | —      |

During Jul 21 → ~mid-Aug, **prod runs 1.1.0's backend while most users are still
on 1.0.0.** That only works because the changes are additive/backward-compatible.

---

## A. Develop & test against dev (Jul 1–21)

```bash
# Point the CLI at dev, then apply your new migrations there.
supabase link --project-ref xbyqwekcanrzovytxmdv
supabase db push                       # applies any unapplied migrations to dev

# Deploy edge-function changes to dev.
supabase functions deploy <fn> --project-ref xbyqwekcanrzovytxmdv
```

Point your dev app build at the dev project (Supabase URL + anon key) and QA.
Dev gets fresh prod-like data daily via `sync_dev.yml`, so reads are realistic.

## B. Promote to prod (Jul 21–28)

```bash
# 1. Re-link to prod. (Do this deliberately; it's the dangerous step.)
supabase link --project-ref blifubxslaucshmlphdc

# 2. DRY RUN: see exactly what would change before touching prod.
supabase db push --dry-run

# 3. Push migrations to prod.
supabase db push

# 4. Deploy edge functions to prod — only the ones you changed.
supabase functions deploy <fn> --project-ref blifubxslaucshmlphdc

# 5. Re-link back to nothing-dangerous (optional safety: link to dev).
supabase link --project-ref xbyqwekcanrzovytxmdv
```

Then build the **prod-pointing 1.1.0** client and smoke-test it against prod
(this is the "verify against prod DB" week). Because the changes are additive,
the **live 1.0.0** app keeps working the whole time — verify that too.

---

## Backend change rules

A change is **safe to promote** only if every client version still installed can
keep working against it.

**DB schema — additive only per release:**
- ✅ `ADD COLUMN` (nullable, or with a default), `CREATE TABLE`, `CREATE INDEX`
  (prefer `CONCURRENTLY`), additive enum values, new RPC functions.
- ❌ `DROP COLUMN` / `DROP TABLE`, `RENAME`, `ALTER COLUMN ... TYPE`,
  adding `SET NOT NULL` to a column old clients don't populate, tightening a
  constraint, removing/renaming an RPC a live client calls.
- A genuinely destructive change is a **two-release** dance:
  1. **Expand:** add the new column/table/fn alongside the old; write to both.
  2. *(ship, wait for old clients to age out)*
  3. **Contract:** drop the old one in a later release.

**Edge functions — backward-compatible:**
- A 1.0 client calls the same endpoint as 1.1. New params must be **optional**;
  never remove a response field a live client reads or change its type.
- Breaking shape change → new endpoint (or a version param), not an edit in place.

> Destructive DDL in a new migration is caught by `.github/workflows/migration-lint.yml`.
> If a destructive change is genuinely safe (e.g. dropping a column no shipped
> client ever read), add a line `-- migration-lint: destructive-ok <reason>` to
> the migration to acknowledge it.

---

## One-time prerequisite: reconcile migration history

Migrations have historically been applied by hand (SQL editor), so the CLI's
remote migration ledger (`supabase_migrations.schema_migrations`) doesn't yet
match what's actually in each DB. Until that's baselined, `supabase db push`
will try to re-run migrations that are already applied.

Do this **once**, per project, before the first real `db push`:

```bash
supabase link --project-ref <ref>
supabase migration list          # shows Local vs Remote — find the mismatch

# For each migration that IS already applied in the DB but missing from the
# remote ledger, mark it applied WITHOUT re-running it:
supabase migration repair --status applied <migration_timestamp>

# For anything genuinely not yet applied (e.g. 20260605130000_reconcile_states_metros_drift
# on prod), apply it normally — but review it for additive-safety first.
supabase migration list          # re-run until Local and Remote agree
```

Run the reconcile against **prod first** (it's the source of truth for what's
live), then **dev**. Treat the prod reconcile as a gated DB op — dry-run / review
each unapplied migration before pushing it. See `/db-op`.

---

## App releases & branching (hotfix safety)

The client (iOS/Android) ships on a branch model so an urgent fix to the LIVE
app never drags in unfinished work.

```
main      = EXACTLY what's live in the stores. Tagged at every release.
develop   = the next release in progress. Feature branches merge here.
feature/* = one in-progress feature, branched off develop.
hotfix/*  = branched off the LIVE tag (main) when prod is on fire.
```

### THE iron rule: never archive from a dirty tree
A store build must be reproducible from git. Before you Archive (Xcode) or build
a release AAB:
1. Commit everything. `git status` is clean.
2. Bump the build number (`CURRENT_PROJECT_VERSION` in `iosApp/Configuration/Config.xcconfig`; Android `versionCode`).
3. `git tag vX.Y.Z` and note the iOS build number in the tag message.
4. THEN Archive / build the release.

If you archive from uncommitted changes, there is no commit that equals your live
app — and you cannot cleanly branch a hotfix from it. (This happened with 1.0.0:
build 102 was archived from an uncommitted tree, so no commit reproduces it.)

### Releasing the next version (feature release)
`develop` → finish + test → bump build number → commit → `git tag vX.Y.0` →
merge to `main` → Archive from `main` → upload. Promote any backend changes per
the dev→prod steps above (additive-only).

### Hotfix to the live app (the "prod is on fire while mid-feature" case)
```bash
git checkout vX.Y.Z                 # the live tag — NOT develop
git checkout -b hotfix/X.Y.(Z+1)
# fix ONLY the bug; bump the build number
git commit -am "Fix <bug>"
git tag vX.Y.(Z+1)
# Archive from this branch → upload to App Store / Play  (carries ZERO develop WIP)
git checkout main    && git merge hotfix/X.Y.(Z+1)   # main = new live
git checkout develop && git merge hotfix/X.Y.(Z+1)   # develop gets the fix too
```
Your in-progress features live on `develop` and are never part of the hotfix
build — that isolation is the whole point.

> Backend note: a hotfix's backend must stay additive/back-compatible with the
> live clients (same rule as any prod promotion). A client-only hotfix needs no
> migration.
