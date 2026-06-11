# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What is Local?

Local is a KMP (Kotlin Multiplatform) event discovery app for iOS (primary) and Android. It features an AI concierge ("Yocal") powered by Claude that builds personalized itineraries, a home feed with interleaved carousels, venue/event search, plan management, and a badge/points system.

## Build Commands

```bash
# Compile check (fastest way to verify changes)
./gradlew composeApp:compileKotlinIosArm64

# Full build
./gradlew build

# Clean
./gradlew clean

# Android
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:installDebug

# iOS — open in Xcode and run from there
open iosApp/iosApp.xcodeproj
# Or build framework via Gradle:
./gradlew :composeApp:iosArm64Binaries

# Deploy Supabase edge functions
npx supabase functions deploy carousels --project-ref blifubxslaucshmlphdc
npx supabase functions deploy validate-venue --project-ref blifubxslaucshmlphdc

# Run crawl scripts (require SUPABASE_SERVICE_KEY env var)
SUPABASE_SERVICE_KEY=ey... node scripts/run_crawls.js
SUPABASE_SERVICE_KEY=ey... VENUE_NAME="Museum of Fine Arts" node scripts/run_crawls.js

# Trigger GitHub Actions crawl with venue filter
gh workflow run "Weekly — Crawl Venues & Classify Events" -f venue_filter="Museum of Fine Arts,MIT"

# TestFlight: bump build number in iosApp/Configuration/Config.xcconfig, archive in Xcode, distribute
```

## Architecture

### KMP Structure
- `composeApp/src/commonMain/` — shared Kotlin code (UI, ViewModels, repositories, models)
- `composeApp/src/androidMain/` — Android-specific (MainActivity, platform implementations)
- `composeApp/src/iosMain/` — iOS-specific (MainViewController, bridge objects for SwiftUI)
- `iosApp/` — Xcode project with SwiftUI shell, native EventDetailView, transition controllers

### App Flow
`SPLASH → AUTH (phone OTP) → ONBOARDING (preferences) → HOME (tabs)`

Tabs: Home | Places | Plans | Profile. Chat (Yocal) opens as a ModalBottomSheet from a floating button.

### Key Patterns

**DI (Koin):** All dependencies registered in `di/AppModule.kt`. ViewModels are singletons (except auth/onboarding which are factories). Access via `koin.get<T>()`.

**ViewModels:** Extend `androidx.lifecycle.ViewModel`. State via `MutableStateFlow<UiState>` exposed as `StateFlow`. Mutations via `_uiState.update { it.copy(...) }`.

**Repositories:** Interface in `domain/repository/`, implementation in `data/repository/`. All use `SupabaseClient.from("table").select/insert/update`. DTOs in `data/remote/dto/` with `@Serializable` + `@SerialName`.

**Navigation:** Custom tab routing in `ui/navigation/AppNavigation.kt` — no NavController. Tab state via `selectedTab` enum. All ViewModels hoisted at AppNavigation level.

**iOS Native Bridge:** Some screens use native SwiftUI (EventDetailView) via bridge objects (`EventDetailBridge.kt` → `ContentView.swift`). The Compose ModalBottomSheet hosts the chat; events opened from chat stay in Compose since they're already inside the sheet.

### Data Flow
```
App → ViewModel → Repository → SupabaseClient → Supabase PostgreSQL
                → ClaudeApiService → Claude Messages API (with tool use)
                → BadgeService → user_badges table
```

### Claude Chat (ChatViewModel)
The Yocal chat uses Claude with tool-use in a loop (`sendWithToolLoop`). Tools: `search_events`, `search_venues`, `get_event_details`, `recommend_and_save_venue`. Claude responds with structured JSON (`type: "chat" | "plan" | "plan_update" | "approve"`). The system prompt is built dynamically with a 90-day calendar reference, user preferences, and current plan context.

### Carousel Edge Function
`supabase/functions/carousels/index.ts` — serves the home feed. Uses geo-based bounding box queries (not city-name filtering). Results are interleaved by type (never two of the same carousel type back-to-back). Caches for 15 minutes, keyed by rounded lat/lng.

### Crawl Pipeline
Platform-specific crawlers auto-detect and use structured APIs before falling back to Claude HTML extraction:
- **BiblioCommons** — `*.bibliocommons.com` → JSON API for libraries
- **Localist** — `calendar.mit.edu`, etc. → `/api/2/events`
- **LiveWhale** — `events.brown.edu`, etc. → `/live/json/events/`
- **WordPress/Tribe** — detected via `wp-content` in HTML → `/wp-json/tribe/events/v1/events`
- **Ticketure** — detected via `ticketure` in HTML → `/api/events/available`
- **Drupal** — detected via `drupal` in HTML → `/jsonapi/node/event`
- Multi-URL support: comma-separated URLs in `events_url` field are crawled independently

### GitHub Actions
- **Daily** (8am UTC): Ticketmaster sync → Eventbrite sync → deactivate past events
- **Weekly** (Sun 9am UTC): Crawl venues → classify events → extract venue names
- **Monthly** (1st, 8am UTC): Seed grids → discover venues → fix URLs → rank → backfill photos

## Backend Releases (monthly train)

Backend changes promote **dev → prod** on the monthly release train. Procedure in
`RELEASE.md`; to actually ship, use the `/release` skill. Two projects: prod
`blifubxslaucshmlphdc` (serves ALL live App Store versions), dev
`xbyqwekcanrzovytxmdv` (staging, fed daily by `sync_dev.yml`). The CLI is linked to
**prod** by default — always pass `--project-ref` / `supabase link` deliberately.

**Hard rule — additive-only / backward-compatible.** One prod DB + one set of edge
functions serves every installed client version at once, and updates roll out over
days. So anything promoted to prod must keep the **oldest live client** working:
- Schema: `ADD COLUMN`/`CREATE TABLE`/`CREATE INDEX`/new RPCs OK. **No**
  `DROP`/`RENAME`/`ALTER ... TYPE`/new `SET NOT NULL` on a live column. Destructive
  changes split across two releases (expand → ship → contract).
- Edge functions: new params optional; never remove/retype a response field a live
  client reads. Breaking shape → new endpoint, not an in-place edit.
- `.github/workflows/migration-lint.yml` fails the build on destructive DDL in a new
  migration unless it carries `-- migration-lint: destructive-ok <reason>`.

Migrations are authored in `supabase/migrations/` and applied with `supabase db push`
(not the SQL editor — that's the legacy source of remote-ledger drift; reconcile once
via `supabase migration repair`, see RELEASE.md).

## Key Files

| File | Purpose |
|------|---------|
| `App.kt` | Root composable, auth state machine |
| `Config.kt` | API keys (Supabase, Claude, Ticketmaster) |
| `di/AppModule.kt` | All Koin dependency registrations |
| `ui/navigation/AppNavigation.kt` | Tab router, celebration overlays, auth gating |
| `ui/chat/ChatViewModel.kt` | Claude chat loop, tool execution, plan building, system prompt |
| `ui/chat/ChatScreen.kt` | Chat UI, plan cards, "Also Happening" recommendations |
| `ui/home/HomeScreen.kt` | Carousel feed, See All sheet, debug menu |
| `ui/home/HomeViewModel.kt` | Carousel loading, favorites, location, badge tracking |
| `data/remote/ClaudeApiService.kt` | Claude API wrapper (sendRaw, parseStructured, cleanJson) |
| `data/repository/ServerCarouselRepository.kt` | Edge function client for carousel data |
| `data/repository/EventRepositoryImpl.kt` | Event queries with geo-radius search |
| `domain/model/Badge.kt` | Badge actions, milestone thresholds, status tiers |
| `supabase/functions/carousels/index.ts` | Home feed edge function (geo-based, interleaved) |
| `scripts/run_crawls.js` | Main venue crawler with platform detection |
| `iosApp/iosApp/EventDetailView.swift` | Native SwiftUI event detail screen |
| `iosApp/Configuration/Config.xcconfig` | iOS build config (team ID, bundle ID, version) |

## Supabase Tables

`venues`, `events`, `user_profiles`, `plans`, `user_badges`, `community_interactions`, `carousels`, `secrets`, `metros`

## Important Conventions

- Use `compileKotlinIosArm64` for quick compile checks — full builds are slow
- Emoji characters using surrogate pairs (`\uD83D\uDE80`) don't render on iOS Compose — use Material Icons instead
- `imePadding()` is required on any screen with text input on iOS (keyboard doesn't push content automatically)
- The carousels edge function must be deployed separately after changes (`npx supabase functions deploy carousels --project-ref blifubxslaucshmlphdc`)
- Crawl scripts require `SUPABASE_SERVICE_KEY` env var (stored in GitHub Actions secrets, not in source)
- Events use geo-radius search (lat/lng bounding box), not city-name matching
- `cleanMarkdown()` in ChatScreen preserves bullet points — don't strip them
- `parseTimeForSort()` is the time parsing function in ChatViewModel (not `parseTimeToMinutes`)

## TODO

- [ ] **Sports classifier: team_name + subcategory gap fix.** Discovered 2026-05-27 while WooSox + Patriots showed only 1-2 games each on Sports tab despite full seasons in the events table. `buildSportsCarousels` in `supabase/functions/carousels/index.ts` groups by `team_name`, so any sports event with `team_name IS NULL` is invisible to the tab regardless of how many games exist.

  **Two root failure modes, both in `scripts/classify_events.js`:**

  1. **Miscategorized subcategory blocks the backfill.** The `TEAM_NAME_BACKFILL=1` mode at `classify_events.js:956-960` filters to `subcategory IN ('sports','hockey','baseball','basketball','football','soccer','rugby','lacrosse','college-sports','minor-league')`. But 40 Worcester Red Sox games landed with `subcategory='miscellaneous'` on first classify, so the backfill never picks them up. Same likely for Reading Fightin Phils (44), Jersey Shore BlueClaws (41), Frederick Keys (41), Aberdeen Ironbirds (40), Wilmington Blue Rocks (39), Harrisburg Senators (39), Lehigh Valley Iron Pigs (34), Sussex County Miners (36), Staten Island FerryHawks (36), Washington Wild Things (34) — all MILB/independent teams that hit `miscellaneous` on first pass.
     - **Fix**: widen the backfill's subcategory filter to include `'miscellaneous'` AND `'other'`. Risk: more LLM token spend on non-sports rows; mitigate by adding a venue-rank or title-pattern pre-filter so we only spend tokens on "looks like a sports event" rows.

  2. **Title pattern `" v "` (single letter) isn't recognized as a vs-game.** 6 New England Patriots games at Gillette Stadium use Ticketmaster's `New England Patriots v Buffalo Bills` format. Same for Gillette World Cup games (`2026 World Cup: Haiti v Scotland`). The classifier's LLM prompt should already handle this (LLMs read "v" as "versus"), but in practice the team_name comes back null on these rows, suggesting the prompt or the post-processing rejects them.
     - **Fix**: audit the LLM prompt at `classify_events.js:425` to confirm it handles `" v "` and `:` prefixes ("Preseason Game 1: New England Patriots v X"). If the prompt is fine, the post-processing trim is the issue.

  **Plus a separate-but-related bug:**

  3. **Stale rebranded team names.** 3 Boston Legacy FC games are tagged `team_name='Boston Breakers'` — the old team name (rebranded ~2024). Carousels group by team_name so these show under a non-existent team. Curated team-name dictionary in the classifier prompt is stale.
     - **Fix**: maintain a rename map (`'Boston Breakers' → 'Boston Legacy FC'`, etc.) and apply post-classification. Or run a one-time UPDATE.

  **Audit query for the broader gap** (run when starting):
  ```sql
  SELECT
    regexp_replace(title, '\s*(vs\.?|VS\.?|v\.?|@)\s.*$', '', 'i') AS inferred_team,
    count(*) AS n
  FROM events
  WHERE is_active = true
    AND team_name IS NULL
    AND (title ILIKE '% vs %' OR title ILIKE '% vs. %' OR title ILIKE '% v %')
    AND start_at >= now() AND start_at < now() + interval '120 days'
  GROUP BY 1
  ORDER BY n DESC
  LIMIT 50;
  ```
  Expected output: confirms ~700+ events with null team_name, grouped by detected leading team string. Filter for in-radius (PA/NJ/MD/CT) teams = real backlog. World Cup watch parties, recreational hockey leagues ("Sweaty Bettys vs Bulldogs"), high-school games = noise to skip.

  **Immediate-pain backfill quick wins** (until classifier is fixed — do as `/db-op`):
  - New England Patriots (6 games) — `team_name IS NULL AND venue_name='Gillette Stadium' AND title ILIKE 'New England Patriots v %'`
  - Boston Legacy FC rename (3 games) — `team_name='Boston Breakers' AND title ILIKE 'Boston Legacy FC%'`
  - Worcester Red Sox — DONE 2026-05-27 (logged in backfills.md)

  **Success criteria**: Sports tab in Boston metro shows packed rails for Red Sox / Bruins (when in season) / Celtics (when in season) / Patriots / Revolution / Banshees / Worcester Red Sox / Brockton Rox / Boston Legacy FC. No "team rail with 1 game" anomalies for pro/MILB teams in radius.

- [ ] Deploy `carousels` edge function to pick up city/state filtering added in commit `1230676` on `claude/mobile-editing-support-BDTIO` (stops MA-only carousels from leaking to out-of-market users):
  ```bash
  npx supabase functions deploy carousels --project-ref blifubxslaucshmlphdc
  ```
- [ ] Yocal punts on venue/opening-hours questions instead of trying. Repro: "When does Dorney Park open?" → Yocal responds "I don't have real-time access…" and lists generic "check their website / call ahead" advice without calling any tool. Root causes and options:
  1. **Fundamental gap**: Yocal's tools (`search_events`, `search_venues`, `get_event_details`, `recommend_and_save_venue`, `present_plan`, `update_plan` in `ClaudeApiService.kt:193-367`) include no web search / fetch, and seasonal hours aren't columns on `events` / `venues`.
  2. **Prompt gap**: even so, Yocal should call `search_events` + `search_venues` for the subject before disclaiming. Add a rule: *"Before saying 'I don't have real-time info,' always run `search_events` and `search_venues` first. Only disclaim if both return nothing."*
  3. **Bigger lift**: add a `web_search` tool (Claude's built-in `web_search_20250305` or a Brave/SerpAPI proxy) so Yocal can answer factual venue questions without DB coverage.
  4. **Biggest lift**: extend `venues` with a `seasonal_schedule` / `hours` field and backfill from the crawl pipeline for seasonal venues (amusement parks, seasonal museums, etc.).
- [ ] Investigate Yocal empty-plan bug: asking about "interactive science museum Philadelphia" → picking Astronomy Day at the Franklin Institute (Sat Apr 25, 11am) → confirming solo + "just astronomy day" produced a `present_plan` card titled "Saturday, April 25" with **0 stops** and a Save button that would persist a garbage plan. Two things to check:
  1. Does the event exist in the `events` table for 2026-04-25 with `is_active = true`?
     ```sql
     SELECT id, title, start_at, venue_name, is_active, source FROM events
     WHERE (title ILIKE '%astronomy%' OR venue_name ILIKE '%franklin institute%')
       AND start_at >= '2026-04-25' AND start_at < '2026-04-26';
     ```
     - Row exists & active → Yocal had it but dropped the `event_id` building the plan.
     - Row exists & inactive → deactivation likely blocked the slot.
     - No row → Yocal hallucinated the event (didn't come from `search_events`).
  2. Likely fix (any case): tighten `present_plan` tool schema in `ClaudeApiService.kt:298-367` so each `days[].slots` has `minItems: 1` and each slot requires `event_id` OR `venue_name` via `anyOf`. Add a matching line to the Yocal system prompt forbidding empty `slots`, and add a client-side guard in `parsePlanSlot` so a 0-slot plan doesn't render a Save button.
- [ ] **Yocal web_search + DB ingestion (design pending — product disagrees with the Claude-proposed private-first split, prefers going straight to shared DB).**
  - Goal: give Yocal `web_search` with no topic restriction. When the user wants to plan around something Yocal found that isn't in the DB, Yocal creates the event and — if no matching venue exists — creates the venue, so the plan is never a dead end.
  - **Product direction (preferred)**: Yocal-found rows go into the shared `events` / `venues` tables directly. Plan slots tagged as "Found by Yocal — may contain mistakes." Users can tick a "I confirmed this is correct" checkbox on the event/venue detail page.
  - **Claude's concerns** (captured for later, user disagrees with the mitigation): shared-DB writes surface in *every* user's carousels, search, geo queries — not just the requesting user's. Risks: hallucinated rows propagate before confirmation; duplicate venues with crawler-sourced rows (dedup is already non-trivial); wrong dates/prices/URLs erode trust; moderation surface (any user can trigger creation → rate-limits / abuse guards needed).
  - **Claude's alternative (user rejected — included for reference)**: private-by-default. New columns `events.source='yocal'`, `events.created_by_user_id`, `events.visibility='private'`; private rows only render inside the requesting user's plan, filtered out of carousels/search/geo. Promote to public only after N independent user confirmations or a moderation pass.
  - To decide before implementing: (1) dedup rules on venue create (fuzzy name + geo radius threshold); (2) abuse/rate-limit story for web-search-triggered writes; (3) what metadata to track (`source`, `confirmed_by`, `confirmed_at`, `source_url`, cited domain); (4) UI for the Yocal-found badge and the confirm checkbox; (5) whether Yocal's `web_search` needs an `allowed_domains` allowlist even if topic-unrestricted.
