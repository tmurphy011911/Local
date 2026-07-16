# TEST GAP AUDIT — Local (full codebase)

**Date:** 2026-06-12 · **Method:** 6 parallel subsystem audits covering every source file
**Surface audited:** ~100k lines — 55.5k Kotlin common · 3.9k platform Kotlin · 12k app Swift · 7.2k TS edge functions · 22.3k JS pipeline scripts
**Coverage today:** 74 lines of unit tests (`ComposeAppCommonTest.kt`, `BadgeAwardsTest.kt`) + 28 Maestro UI flows. No XCTest target. No Deno tests. No JS test runner. Everything below the UI is untested.

**Totals:** ~1,200+ named missing test cases · ~26 new Maestro flows spec'd · 5 test harnesses needed (kotlin-test exists; XCTest, Deno.test, node:test, RPC-integration do not).

---

## TOP P0s — ranked by blast radius

| # | Area | Gap | Why it matters |
|---|------|-----|----------------|
| 1 | `accept_plan_invite` RPC | No idempotency / exactly-once points test | Double-award of 2,500/5,000/10,000 points = corrupted gamification economy |
| 2 | `claude-proxy` | No auth-validation test — **and the function doesn't actually validate** | Anyone with the URL can burn Anthropic spend (ties to the known key-leak issue) |
| 3 | `rc-webhook` | No is_pro-from-expiry / idempotency / auth-header tests | Wrong paid status = paying users locked out or free users get Pro |
| 4 | `PremiumGate.isTrialActive` | No trial-expiry boundary tests (expiry instant, malformed ISO, empty) | The single chokepoint for ALL paywall gates ships on faith |
| 5 | `ChatViewModel.removeDuplicateSlots` | Multi-day collapse fix is in code but **verified by zero tests** | The "3-day Nantucket → 2 days" bug can silently regress |
| 6 | `parseTimeForSort` | 12 AM/12 PM ambiguity untested | Midnight slots can sort to noon → scrambled itineraries |
| 7 | `classify_events.js` backfill filter (~L956) | Known bug, untested: `miscellaneous`/`other` excluded → MILB teams invisible | 40+ games per team missing from Sports tab (documented in CLAUDE.md TODO) |
| 8 | `sync_municipal_feeds.js` iCal parser | RRULE recurring events likely NOT expanded — no test | "Every Tuesday" series = 1 event instead of 12 |
| 9 | `dedup_events.js` | Safety-gate + canonical-pick logic untested | A bad fuzzy match soft-deletes real events at scale |
| 10 | DeepLinkHandler (Android) + `PlanDetailTransitionController.decode` (iOS) | Cold/warm race + malformed-plan-JSON paths untested | Both are past-crash areas; collab share flow depends on them |
| 11 | `carousels` `isGeneralFeedEvent` + interleaving + team_name grouping | Product rules enforced by zero tests | The entire home feed's correctness is unverified |
| 12 | Bo plan-save pipeline (`parsePlanSlot`, 0-slot plans) | Empty-plan bug (CLAUDE.md TODO) has no regression test | Garbage plans can be saved |

---

## 1. ViewModels + chat logic (`ui/*ViewModel.kt`, `PremiumGate.kt`)

### ChatViewModel.kt (5,542 lines) — highest-risk file in the app
| Unit | Line | Missing cases |
|------|------|---------------|
| `removeDuplicateSlots` | ~4824 | **P0**: 3-day trip, same venue Day 1+Day 3 → both kept, day count stays 3 (regression test for the known collapse bug). Within-day dup `[Toro, Toro, Neptune]` → `[Toro, Neptune]`. Cross-day ≥80% overlap → later day dropped; 79% → kept. Day left empty by dedup → kept, never dropped. |
| `slotKey` | ~4834 | null eventId → venue-name key; "Toro - South End" vs "Toro, Boston" normalize-match; blank name; Unicode |
| `parseTimeForSort` | ~5004 | **P0**: "12:00 AM"→0, "12:00 PM"→720, "12:30 AM"→30, "12:30 PM"→750. "7 PM"/"7PM"/"7:00 PM"→1140; "14:00"→840; garbage→Int.MAX_VALUE |
| `normalizeTimeDisplay` | ~4970 | unparseable → returned unchanged; 0→"12 AM"; 720→"12 PM"; 1155→"7:15 PM" |
| `sortSlotsByTime` | ~4988 | pre-dawn-as-late-night: `[10PM, 7PM, 12AM]`→`[7PM, 10PM, 12AM]`; all-morning plan NOT shifted; suspicious case `[6AM, 7PM, 12AM]` — verify intended order |
| `fuzzyVenueMatch` | ~1582 | "Hatch Shell"~"Hatch Memorial Shell" true; "The Sinclair"~"Sinclair Music Hall" true (stopwords); empty strings false; "Boston Breakers"≠"Boston Legacy FC" |
| `parsePlanSlot` | ~2162 | event_id present / venue_name-only / **both null → empty-id slot (the 0-slot plan bug)**; is_bo_web=true without event_id |
| `looksLikeNarratedSuggestions` | ~2212 | <100 chars false; <2 bold spans false; <2 weekdays false; all-three true (Bo narration-vs-plan-delta bug detector) |
| `buildPlanFromSelections` | ~866 | empty eventIds; nonexistent ids; duplicate ids |

### PremiumGate.kt (173 lines) — P0, gates all revenue
- `isTrialActive`: ends tomorrow→true; ended yesterday→false; **expiry == now boundary**; empty string→false; malformed ISO→false (swallowed)
- `isPremium`: 4-way truth table (paid×trial)
- `canCreatePlan`: YOCAL/MANUAL counters independent; first free, second gated; premium always true
- `paidSubscriptionUserId`: same user relaunch → cached paid status survives; user change → wiped (the launch-flash fix — currently test-less)
- Blocker: inject `Settings`/`Clock` (or map-backed Settings double) for boundary tests

### HomeViewModel.kt (859 lines)
- `isLocationInSupportedState`: null→true; Boston→true; bbox boundary edges; London→false; NJ/NY overlap tie-break
- `putWithLru`: update moves to end; cap eviction; cap=1, cap=0
- `fetchYocalPromptsIfNeeded`/`fetchWeatherIfNeeded`: 0.1° grid dedup; backoff exhaustion → fallback; midnight-local date boundary
- `loadUserPreferencesThenCarousels` (**P0 fallback ladder**): MANUAL+coords → manual; MANUAL no coords → salvage userLat/Lng; CURRENT+permission-denied → flip MANUAL; out-of-bounds → isUnsupportedState=true; userId=null cold start

### PlacesViewModel.kt (772 lines)
- Cache keys (L155/163): unescaped `|` → collision risk with pipes in query
- `setCustomDateRange`: start>end auto-swap; start==end
- tz-offset math (L506): DST spring-forward / fall-back
- load caching: empty page NOT cached; TTL just-expired vs just-fresh
- `deriveUserState`: null→MA; unknown→MA; border cities
- favorite toggles: unauthed → onAuthRequired, no local change; optimistic add/remove symmetry

### ProgressViewModel.kt (406)
- milestone dedup `${action}_${milestone}` (dup rows → counted once); tier progress on MAYOR (no next tier → coerce 1f, no ÷0); celebration FIFO queue
### PlansViewModel.kt (454)
- reconciliation: server+pending merge, upcoming/past partition, sort stability; `backfillSlotVenueCities` skip rules + failure → original returned
### PlanChatViewModel.kt (926)
- `parseAddEventDay` sentinel parsing; reactions for nonexistent message skipped; isYocal=true never isCurrentUser
### TasteProfileViewModel.kt (69)
- score coercion [0,20]; unknown dimension no-op; null profile early-return

**Blockers:** private fns need `@VisibleForTesting`/extraction; no Clock injection; `Settings()` constructed inline.

---

## 2. Data + domain (`data/`, `domain/`)

### Pure quick wins (no mocking, ~90 cases)
- **Badge.kt**: `statusTierForPoints` boundaries (0, 499, 500, >1M); `nextStatusTier(MAYOR)`→null; `nextMilestoneFor` at 1000→null; one-time vs ladder thresholds; `boLevel` index mapping
- **Location.kt `distanceMiles`** (Haversine): same point→0; Boston→NYC ≈215mi regression; pole-to-pole; 1-foot precision
- **Event.kt**: `dateDisplay` null/garbage→""; Today/Tomorrow/weekday/MonDD ladder; timeKnown=false hides time; midnight sentinel; tz-suffix variants; `priceDisplay` free/min==max/min-only/both-null; `distanceDisplay` <0.1→"Here"; `displayCity` "(CVB)" strip
- **Plan.kt**: `isAcceptedCopy` null/non-null; creatorId vs acceptedFromCreatorId divergence; reservation/ticket enum parsing incl. invalid strings
- **TasteProfile.kt `topN`**: empty; n>size; tie-break stability
- **ClaudeApiService pure fns** (P0): `cleanJson` — fenced blocks, truncated mid-object, no closing brace, multiple `{...}`; `parseStructured` missing type/days; `extractText` multi-block joins + orphan punctuation; `extractToolCalls` missing id/name→filtered; `cacheableSystem` missing cache-break delimiter
- **All DTO round-trips**: EventDto/VenueDto/BadgeDto/CarouselDto/TasteProfileDto with all-null optionals; ISO date format variants; HTML-entity decode in title

### Needs extraction or fakes
- Repo mappers: PlanSlotDto reservation/ticket state strings → enums; BadgeRepository RPC result `"3"`→3, garbage→0 (can't distinguish error from zero — flag); EventRepository `isInfraTimeout` chain depth
- Geo bbox math: latDelta=r/69, lngDelta=r/50 (note: edge functions use /55 and /45 — **client/server bbox mismatch worth a parity test**)
- **plan sync (P0 group)**: `PlanMutation.applyTo` idempotent AddSlot, RemoveSlot-missing no-op, re-sort after add; `PlanReconciler.reconcile` ordered replay, DeletePlan filtering, orphaned pending ops; PlanSyncer drain (needs TestDispatcher + fake RPC)

---

## 3. Screens vs Maestro — 26 new flows spec'd

Depth coverage estimated ~40%: happy paths green, but CRUD round-trips, empty/error states, deep links, and persistence are uncovered.

**P0 flows:** plan edit name → relaunch persists (33) · plan delete + confirm (34) · plan add/remove stop (35) · multi-day render (40) · deep-link cold start (59) / warm start (60) · trial-countdown banner render/dismiss/persist (18) · favorite-persists-across-relaunch (44) · onboarding per-step back-nav (49)

**P1 flows:** See-All sheet (19) · pull-to-refresh home/plans/profile (20/31/47) · empty states — no plans / search no-results (30/22) · radius boundary (23) · plan share sheet (38) · attendee unconfirmed count updates (39) · add day (41) · event-detail countdown (42) · username edit+validation (45) · chat scroll anchor / streaming / background persistence (26/27/29) · manual-plan validation: empty-day save blocked (73) · custom-event add (75) · phone-format + invalid-OTP (55/57) · tab persistence (67)

**Embedded logic to extract into unit tests** (~15 fns): `trialDaysRemaining`, `toTitleCase` (HomeScreen ~L146–192) · `formatPhone` (PhoneAuthScreen ~L103) · `fullLabel`/`rangeDates`/`isoDate`/`formatTime` (ManualPlanSheet ~L86–114) · `computeCountdown` (EventDetailScreen ~L98) · `searchPlaceholder` (PlacesScreen ~L356)

---

## 4. Platform Kotlin + Swift

**Infrastructure gap: no XCTest target exists at all.** Create `iosAppTests` bundle; start with PlanDetailModel (pure decode/parse, lowest friction).

### P0
- **MainActivity DeepLinkHandler** (162): cold start (path before callback) vs warm (callback first); regex `/[pev]/[A-Za-z0-9_-]+` rejects `/x/abc`; rapid successive links; host check
- **PlanDetailModel.swift** (335): plan JSON decode w/ missing optionals; malformed→nil; `totalStops`; `isEffectivelyMultiDay` (1 day / 2-with-1-empty / 2 full); `stopType` ladder; **address-parse right-to-left walk**: "Boston, MA 02109"→"Boston"; bare "02114"→nil; "USA" dropped; `looksLikeStateZip`/`looksLikeBareZip` regex table
- **EventDetailView.swift** (889): `computeCountdown` ±4h live window, past, no-date; `formatDate` timeKnown=false→UTC calendar day (cross-TZ midnight); fractional-seconds ISO fallback
- **PlanDetailTransitionController** (132): malformed planJson→onDismissed, no crash; `isCreator` exact match; `presentWhenReady` 20×50ms poll bail-out
- **ContentView bridges** (813): upcomingEventsJson malformed → silently `[]` (add logging + test); ChatSheetBridge paywall 0.4s-delay race; purchase-fail → no approvePlan
- **NativeDateRangePickerBridge** (334): 3-state select machine (first tap, earlier-tap moves start, later sets end, third resets); epoch-millis conversion; swipe-dismiss → onCancel only if uncommitted

### P1
- PlanMapHero.android (394): walk/drive 1609m threshold; zoom clamp [8,16]; <2 coords → no legs; missing map style → silent null (flag)
- Transition controllers: image download fire-and-forget no timeout; `fromPlanContext` reset on dismiss
- PlacesAutocompleteBridge: <3 chars → "[]"; completer error → "[]"
- NativeTimePickerBridge: didFinish blocks double-callback on swipe-dismiss
- AnalyticsBridge/Analytics.android: no double-init guard on Sentry (flag); flush 2s cap; sanitize drops nulls
- ChatView TypewriterCompletionStore persists across sheet reopen; PlanChatView `colorFor` determinism

Known-trap scan: no live `.simultaneousGesture(DragGesture(minimumDistance:0))` occurrences (PlanDetailView fixed via ButtonStyle); SafeShape try/catch present but fallback-renders untested.

---

## 5. Edge functions + RPCs (~130 cases, zero Deno tests)

### carousels/index.ts (3,034 lines) — P0 cluster
- `isGeneralFeedEvent`: each clause independently + combinations (curated/always_on/weekly/monthly)
- weekly/monthly keyword detection ("trivia night", "first friday", …)
- `deduplicateCrossSource`: ≥50% word-overlap threshold; filler-word stripping; same-venue-same-day variants
- `rankForColdStart`: first-3-distinct-categories, 25% cap
- sports grouping by `team_name` — null invisible (known bug); WooSox/Brockton render separately
- interleaving: never two same-type back-to-back
- proximity weight tiers (≤12/25/45/+mi) and decay divisors; cache keying at 0.1°; city/state cross-market leak guard
- `reclassifyEvent` venue rewrites + film patterns; `isGovernmentNoise`

### claude-proxy (102) — P0
- **missing token→401, garbage JWT→401 (currently NOT enforced — test will fail until fixed)**; is_pro allow; trial-future allow; trial-past→402; missing profile→500; stream/non-stream passthrough; upstream 429/5xx propagation

### rc-webhook (123) — P0
- wrong/missing auth header→401; **CANCELLATION w/ future expiry → is_pro stays true** (expiry-derived, not event-type); stale-timestamp ignored / newer applied; `$RCAnonymousID:` ignored 200; null expiry→false; TEST event no-op

### bo-web-budget (286) — P0
- 5 sequential gates: not-live-state deny; global $500/month; 30/day Eastern-midnight reset; 10/hour rolling; adversarial >50% rejection on ≥3 rows; cost math ($0.01+$0.002×results / $0.005)

### yocal-prompts (296)
- 0.1° cache key; 24h TTL; **isolate-cache staleness (known prod issue from load test)**; off-audience regex per pattern; parse robustness; <5 usable prompts→502

### explore-events (659) / explore-places (552)
- date windows w/ tzOffsetMin (DST); venue round-robin interleave `[A1,B1,C1,A2,B2]`; cold-vs-hot affinity weights; venue-rank≤10 floor; radius clamp 100; cursor encode/decode; venue dedup by normalized name; 25-events-per-venue cap; sort=most_events re-sorts before paginating

### Other: verify-bo-web-event (486, P0 — geo city-mismatch gate, Sonnet→Opus escalation, JSON robustness) · save-bo-web-finding (250, P0 — future-date validation, UUID checks) · validate-venue (330 — stub enrichment preserves non-nulls, INSERT-fail → `validated_not_saved` 200) · geocode/healthz/bo-chips/share-preview/override/_shared (P1–P2)

### RPCs (integration, dev DB `xbyqwekcanrzovytxmdv`)
- **`accept_plan_invite` (P0)**: token-not-found; own-plan; double-accept→'already_accepted'; attendee ON CONFLICT safe; **exactly-once 2,500 award**; 5-accept→5k and 10-accept→10k boundaries
- `get_category_affinity` weights (click1/fav2/share4/plan6); `get_community_scores` personal/community split with null user; `search_events_stratified` MV parity + deterministic pagination

---

## 6. Pipeline scripts (~22k lines, no test runner configured)

### P0
- **classify_events.js** (1,117): backfill filter excludes `miscellaneous`/`other` (test reproduces MILB invisibility — fails until fixed); `" v "` single-letter pattern → team_name extracted; stale "Boston Breakers"→"Boston Legacy FC" rename map; AUTO_EXCLUDE_PATTERNS table ("AA meeting" match, "AA-12 Battery Shop" no-match; "School Committee" match, "School of the Arts" no-match)
- **sync_municipal_feeds.js** (879): **RRULE expansion (likely missing — weekly VEVENT should emit N instances in 90-day window)**; RSS description must be entity-decoded+stripped before Claude date extraction; pubDate NEVER used as start_at; Drupal missing-title silent-skip should log; per-parser fixtures (Drupal/Localist/iCal/Tribe/RSS)
- **dedup_events.js** (507): `tokenize` stopwords/short tokens; `ticketmasterEventId` 16-hex + word-boundary (`?qty=2` matches, `…Aextra` doesn't); `localDateET` UTC→ET midnight crossing; Pass 0 TM-ID dedup canonical pick; Pass 1 fuzzy buckets ("A$AP Rocky" vs "A$AP Rocky - Don't Be Dumb" merge); **45% safety gate aborts**
- **run_crawls.js** (2,155): `decodeXmlEntities` double-encoded loop, no infinite loop, missing `&nbsp;`; `parseFeedItems` RSS/Atom/missing-title; `crawlRssFeed` JSON-LD-first, pubDate-not-start_at; adapter fixtures × 6 platforms
- **dedupe_venues_fuzzy.js / dedupe_pass.js**: signature + canonical-pick (soft-delete blast radius)

### P1
- ticketmaster_sync `detectSubcategory` (" v " matches; WooSox ≠ null/miscellaneous), genre map, dedupe_hash skip
- discover_municipal_feeds `probeDomain` fixtures (Drupal/Localist/iCal/404), `.ics` anchor regex, 22-min budget resume gap
- match_event_venues, split_aggregator_events, collapse_crawler_dupes, fix_venue_urls

**Skip:** eventbrite_sync (dead code), `_tmp_*`, test_chat_*, one-time migrations, seeds (P3).

**Harness:** `node --test` (Node 18+ built-in), fixtures in `scripts/__fixtures__/`, undici MockAgent or nock for HTTP.

---

## Recommended build order

| Phase | What | Harness | ~Cases | Effort |
|-------|------|---------|--------|--------|
| **1. Pure Kotlin quick wins** | PremiumGate, Badge, Location, Event/Plan display, cleanJson/extractText, DTO round-trips, time parse/sort + removeDuplicateSlots (after extraction) | kotlin-test (exists) | ~150 | 2–3 days |
| **2. P0 Maestro flows** | Plan CRUD (33–35, 40), deep links (59–60), trial banner (18), favorite persistence (44) | Maestro (exists) | 8 flows | 2–3 days |
| **3. Pipeline P0** | classify backfill, dedup, RRULE, run_crawls parsers + fixtures | node:test (new) | ~120 | 3–5 days |
| **4. Edge-function P0** | proxy auth, rc-webhook, bo-web-budget, carousels rules, accept_plan_invite vs dev DB | Deno.test + dev DB (new) | ~80 | 4–6 days |
| **5. XCTest bootstrap** | iosAppTests target; PlanDetailModel → EventDetailView countdown/date → transition decode → date-range state machine | XCTest (new) | ~70 | 3–4 days |
| **6. P1 Maestro + ViewModel tests** | remaining 18 flows; HomeViewModel/PlacesViewModel with fakes (Clock/Settings injection) | existing | ~200 | 1–2 wks |

Phases 1+2 alone cover every client-side P0. All phases wire into `run_all.sh` as new sections (unit → flows → e2e) to stay one-command hands-off.
