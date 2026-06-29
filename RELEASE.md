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

> **Two independent dials — don't conflate them.**
> - **Which code** (features, version, your change) = the **git branch**. A change
>   lives only on the branch you commit it to. This is what isolates 1.1.0 work
>   from the live 1.0.0 line.
> - **Which backend + separate install** = the **build type** (debug / staging
>   "Local Dev" / release). All three compile the *same* source from the current
>   branch; they only differ in which DB they hit and that staging installs as a
>   separate `.dev` app.
>
> So a code change shows up in **every** build type built from that branch — build
> type does NOT hide code. Switch **branches** to compare code with/without a
> change; switch the **backend menu** to compare dev vs prod data with the same
> code.

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

### Release schedule — the bi-weekly train
Submit a new build **every other Wednesday**; each scheduled release is a **minor**
bump (`vX.Y.0`). Anchor: `v1.1.0` = **Wed 2026-07-08**, then +14 days / +0.1.0:

| Version | Submit (Wed) |
|---------|--------------|
| v1.1.0  | 2026-07-08   |
| v1.2.0  | 2026-07-22   |
| v1.3.0  | 2026-08-05   |
| v1.4.0  | 2026-08-19   |
| …       | +14 days each |

Each cycle ≈ 2 weeks on `develop`. Hotfixes (`vX.Y.Z` patch) ship off-schedule,
any day, off the live tag.

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

---

## Submitting to the App Store & Play

Run this once you're on the release commit (`main` for a feature release, or the
`hotfix/*` branch) — committed clean and `git tag`-ged.

### Pre-submit checklist (both platforms)
- [ ] Working tree clean and tagged `vX.Y.Z` (never archive dirty).
- [ ] **Build numbers bumped and STRICTLY INCREASING** — iOS `CURRENT_PROJECT_VERSION` and Android `versionCode` (both in lockstep, currently 102). App Store Connect and Play both reject a build number ≤ the last uploaded one for the same marketing version.
- [ ] Marketing version set — iOS `MARKETING_VERSION`, Android `versionName` (`iosApp/Configuration/Config.xcconfig`, `composeApp/build.gradle.kts`).
- [ ] `Config.TEST_AUTH_BYPASS = false` and `REVIEW_BUILD_BYPASS = false` (the latter only `true` for a closed review track, never a prod submission).
- [ ] `Config.FEATURE_*` flags set as intended for this release.
- [ ] Sanity: a release build resolves to **prod** (no DEV badge). It's structural, but eyeball it.

### iOS → App Store Connect / TestFlight
1. **Quit the iOS Simulator first.** The K/N Release link runs in the Gradle daemon at 8g; daemon + Xcode + Simulator oversubscribes a 16GB Mac and OOMs. Cold link is ~11 min.
2. Xcode: destination **Any iOS Device (arm64)** → **Product → Archive**.
3. Organizer → **Distribute App → App Store Connect → Upload**. Team `2BGU22C396`.
4. The **"Upload Symbols Failed — no dSYM for Sentry.framework"** warning is **NOT a blocker** — your own code symbolicates via `Local.app.dSYM`; ship it.
5. App Store Connect (build appears after ~5–15 min processing): add it to a **TestFlight** group for testers, or attach it to the App Store version and **Submit for Review**.

### Android → Play Console
1. Build a **signed release AAB**: Android Studio → **Build → Generate Signed Bundle/APK → Android App Bundle**, using your upload keystore. (No gradle `signingConfig`, so this is the GUI path; Play App Signing re-signs server-side.)
2. Play Console → app → **Testing** (internal/closed) for testers, or **Production** → create release → upload the AAB → roll out (consider a staged % rollout for prod).
3. The **"missing native debug symbols"** warning (Sentry's `.so`) is **safe to ignore** — documented in `composeApp/build.gradle.kts`; Sentry symbolicates via its own pipeline.

### After both are live
- Confirm `main` points at the tagged release commit; `git push origin main --tags`.
- If this was a hotfix, you've already merged it back into `develop`.

---

## Staging build — the side-by-side "Local (Dev)" app

A separate app (`live.localapp.local.dev`, labeled "Local (Dev)") that installs
ALONGSIDE the real app. Use it to run an in-progress branch next to the live app,
and to distribute test builds via TestFlight / Play internal testing. Its backend
is **switchable** at runtime (tap the logo → Dev/Prod), defaulting to **dev** —
so you can point the `.dev` app at dev for safe testing, or at prod to validate
against live data, all without touching the real app. When it's on dev, dev-auth
is on (any OTP, phone→dev account); on prod it uses real auth. Only the plain
`release` build of the real app is hard-locked to prod.

### Android — ready now
- **Local side-by-side:** `./gradlew :composeApp:installStaging` → installs "Local (Dev)" next to "Local". (Debug-signed, so no keystore needed.)
- **Distribute via Play:** Android Studio → Build → Generate Signed Bundle (AAB), select the **staging** build type, sign with your upload key → upload to the `.dev` app's internal-testing track in Play Console.

### iOS — one-time Xcode wiring (GUI; the files are already in place)
Files committed: `Config-Staging.xcconfig` (bundle id `.dev` + `IS_STAGING=YES`),
`IS_STAGING` key in `Info.plist`, `IS_STAGING=NO` default in `Config.xcconfig`.
In Xcode:
1. Project → **Info** tab → **Configurations** → duplicate **Release** → name it **Staging**.
2. For the **Staging** row, set the project's config file to **Config-Staging.xcconfig** (Set "Based on Configuration File" for the iosApp target/project → Config-Staging).
3. **Product → Scheme → Manage Schemes** → duplicate the `iosApp` scheme → name it **"Local Dev"**. Edit it so **Run** and **Archive** use the **Staging** configuration.
4. Run/Archive the **Local Dev** scheme → installs/archives the `.dev` app pointed at dev.

### Accounts (only when distributing to others)
- **Apple:** register bundle id `live.localapp.local.dev`; create its App Store Connect app record; let Xcode make the provisioning profile. Archive the "Local Dev" scheme → upload → TestFlight.
- **Google:** create the `.dev` app (or just an internal-testing track) in Play Console; upload the signed staging AAB.

### Backend mapping
```
debug build    → switchable (Local-logo menu), default DEV
staging build  → switchable (Local-logo menu), default DEV   app id .dev
release build  → PROD, hard-locked, no switch                the real store app
```
`isStagingBuild` (Android `BuildConfig.IS_STAGING`, iOS Info.plist `IS_STAGING=YES`)
+ `isDebugBuild` define `Config.backendSwitchable`; only a plain release is locked.
A DEV/PROD badge by the logo shows the live choice on any switchable build.
