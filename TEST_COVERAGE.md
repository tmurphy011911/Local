# TEST COVERAGE — what our test cases actually assert (as of 2026-06-18)

Per-test inventory of everything the suite verifies *today*. Each bullet is a real
assertion or hard gate that runs — not an aspiration. (For what's **missing**, see
`TEST_GAP_AUDIT.md`.)

**Totals:** 2 unit-test files (6 functions, only 5 meaningful) · 17 regression flows ·
5 reusable subflows · 4-flow collab E2E · 2-flow paywall suite · 3 chaos flows.
Verification is **UI-level + badge-math only**; nothing else below the UI is tested.

---

## UNIT TESTS — `composeApp/src/commonTest/` (the only code-level coverage)

### `ComposeAppCommonTest.kt`
- `example()` — asserts `1 + 2 == 3`. **Scaffolding placeholder; covers zero app logic.**

### `BadgeAwardsTest.kt` — collaboration point-award math (pure functions, no mocks)
- `firstInviteAccept_awards_2500` — `thresholdsFor(INVITE_ACCEPTED).first() == 1`; `pointsForMilestone(INVITE_ACCEPTED, 1) == 2500`
- `group5_confirmed_awards_5000_oneTime` — `GROUP_5_CONFIRMED.isOneTime`; `thresholdsFor == [1]`; `pointsForMilestone(,1) == 5000`
- `group10_confirmed_awards_10000_oneTime` — `GROUP_10_CONFIRMED.isOneTime`; `thresholdsFor == [1]`; `pointsForMilestone(,1) == 10000`
- `inviteAccepted_isMilestoneLadder_notFlatPerAccept` — `!INVITE_ACCEPTED.isOneTime`; `thresholdsFor == MILESTONE_THRESHOLDS`; `pointsForMilestone(,1) == 2500`; `pointsForMilestone(,3) == 7500`; `2 ∉ thresholds`
- `groupAwards_pushUserAcrossStatusTiers` — `statusTierForPoints(5000) == SETTLING_IN`; `statusTierForPoints(10000) == GETTING_OUT_THERE`

**That is the entire unit-test surface.** Only `Badge.kt`'s `pointsForMilestone` /
`thresholdsFor` / `statusTierForPoints` / `isOneTime` are exercised.

---

## SUBFLOWS — reusable scaffolding (`.maestro/subflows/`)

Not standalone tests, but every flow's preconditions depend on them:
- `dismiss_splash` — repeat-taps the tap-to-continue splash until AUTH / Home / onboarding shows.
- `sign_in` — bypass sign-in: phone `5551234567` → any 6-digit OTP → email (if profile-less) → `testbypass@`.
- `sign_in_b` — bypass sign-in for the **distinct** device-B user (`5559876543` → `testbypass+b@`).
- `complete_onboarding` — seeds Boston GPS, "Skip all →" through neighbourhoods/venues/categories, "Skip →" notifications, dismisses Home tutorial; **gate:** lands on Home (`Explore` tab visible).
- `ensure_home` — launch → dismiss splash → sign in if needed → onboard if needed; **gate:** `assertVisible "Explore"`. Every `flows/` test starts here.

---

## REGRESSION FLOWS — `.maestro/flows/` (17)

Per flow: **Steps** (actions) · **✓** (every assertion that runs) · **✗** (blind spots).

### 01_onboarding  *(requires `TEST_FORCE_ONBOARDING=true`)*
**Steps:** `launchApp clearState:true` (wipe prefs) → `sign_in` (phone `5551234567` → OTP `123456` → email `testbypass@`) → `complete_onboarding` (Boston GPS, "Skip all →" past neighbourhoods/venues/categories/favourites, "Skip →" notifications, dismiss Home tutorial).
**✓** `Explore` visible · `Plans` visible (both primary surfaces reachable after a fresh onboard).
**✗** Each onboarding *step's* content & validation (neighbourhood/venue/category multi-select, min-count gating), back-navigation, the manual-address path, the SUCCESS event-count screen — all skipped via "Skip all".

### 02_home
**Steps:** `ensure_home` → `scroll` ×2.
**✓** `home_event_card` renders (feed not blank/crashed) · `home_concierge_input` present · `home_event_card` still visible after scrolling (scroll works, content below fold) · tabs `Home` + `Explore` + `Plans` present.
**✗** Carousel *content* correctness, ordering/interleaving, See-All sheet, pull-to-refresh, trial-countdown banner, title-casing.

### 03_explore
**Steps:** tap `Explore` → tap `explore_search_field`, type "music" → `scroll` → tap `explore_tab_places` → tap `Home`.
**✓** `explore_search_field` + `explore_tab_events` + `explore_tab_places` all present · search field still present after switching to Places sub-tab · `Explore` visible after returning Home.
**✗** That the search **returns results** (liveness only — no result assertion), filters, radius, location, sort. (Those are 12's job.)

### 04_plans
**Steps:** tap `Plans` → tap `plan_create_btn` (FAB) → dismiss scrim (tap 50%,20%) → tap `Home`.
**✓** `Upcoming Plans | Add Your Saved Events to a Plan` (optional — populated or empty) · picker offers **both** `Build a Plan Manually` **and** `Ask Bo` · `Explore` visible back on Home.
**✗** Actual plan creation, list contents, past/upcoming split, hero card, streak header.

### 05_profile
**Steps:** tap `nav_profile_avatar` → `scrollUntilVisible "Log Out"`.
**✓** Progress screen opens · `Log Out` present · `Notifications` row present.
**✗** Badge grid, points bar, tier/status, username edit + validation, celebration overlay, Taste Profile link.

### 06_chat
**Steps:** tap `home_concierge_input`, type "Fun things to do this weekend" → tap `home_concierge_send` → wait 15s → wait 30s.
**✓** Chat sheet opens (`Ask Bo` header, which exists nowhere on Home) · sheet still alive after 30s (survives the live Claude stream without crashing).
**✗** Bo's reply content, streaming correctness, plan-card generation, suggestion tray, scroll-anchor, persistence — all deliberately unasserted (would flake on a live model).

### 07_plan_build_manual  — *the deepest wizard gate*
**Steps:** Plans → `plan_create_btn` → `Build a Plan Manually` → tap name field (positional) + type "Maestro Regression Plan" → `Tap to pick` (DateRangePicker; `pick_dates.js` computes real start/end day-cell labels, scroll+tap each, `Done`) → `Continue` → `Continue` → `Add an event`.
**✓** Each step renders & advances: `Name your plan` → typed title "Maestro Regression Plan" **rendered back** → `When is it?` → `Continue` → `How many people?` → `Build your plan` → Add-event sheet shows **all** of `Add an event` + `Event` + `Location` + `Start time` + `Pick a start time`.
**✗** **Saving the event or plan** (hard-requires a live Google Places autocomplete pick — no offline path; deliberately stopped here). Party-size *value*, multi-day, persisted plan.

### 08_plan_build_bo
**Steps:** Plans → `plan_create_btn` → `Ask Bo` → wait 15s.
**✓** The picker's Ask-Bo route reaches Bo's chat surface (`Ask Bo` header).
**✗** Plan generation (live Claude). Full type→send is 06's job; this is route-only.

### 09_plan_detail  *(conditional — no-ops if account has no plans)*
**Steps:** Plans → if `plan_card` visible, tap it.
**✓** (when a plan exists) `Share Plan` CTA + a `.*stop.*` count render. In **both** branches: `Share Plan | Plans | Upcoming Plans | Add Your Saved Events…` holds.
**✗** Plan edit/rename, delete, multi-day render, add/remove stop, attendee list, reservation details. On a fresh force-onboarded run this often **no-ops entirely** (no seeded plan).

### 10_event_detail  *(Android-real / iOS-partial)*
**Steps:** tap `home_event_card`.
**✓** `home_event_card` present · detail opens with primary CTA `RSVP | Get Tickets | More Info`.
**✗** Countdown timer, venue card, favorite-state-on-detail, hero transition. **iOS:** native `EventDetailView.swift` has no accessibilityIdentifiers, so the CTA anchor **only holds on Android**.

### 11_favorite
**Steps:** tap `home_event_favorite` twice (favorite → unfavorite).
**✓** `home_event_card` + `home_event_favorite` present · feed survives the toggle round-trip (`home_event_card` still present).
**✗** That the favorite **persisted** (no relaunch check), that it surfaces in Profile, the DB write. Verifies *doesn't crash*, not *correctly saved*.

### 12_explore_filters  — *the only flow that asserts real data*
**Steps:** Explore → Places tab → `location_pill` → `Current Location` → change radius `25 mi` → search "Aquarium" → tap result → back → Events tab presets/chips → Places sort.
**✓** search field + both subtabs · Location sheet shows `Location Settings` + `RADIUS` + `Current Location` · pill reads `.*Boston.*` after GPS switch · **`venue_card` rows actually render** (real covered-market data) · pill reads `.*25 mi.*` after radius change + rows re-render · "Aquarium" search **returns a `venue_card`** · tapping it opens venue detail (`Directions`) · back returns to search · `This Weekend` preset toggles (×2) keep UI alive · `explore_category_chip` toggles (×2) · `Closest`/`Most Popular` sort taps · `Explore` back on Home. (search field re-asserted after every step.)
**✗** That sort **actually reorders**, that the category filter **actually filters**, result counts, custom date range, the uncovered-city empty state.

### 13_signout_signin
**Steps:** avatar → `scrollUntilVisible "Log Out"` → `Log Out` → confirm dialog (optional) → `Sign in` (optional) → `sign_in` subflow → onboard if forced.
**✓** After the full round-trip: `Explore` visible (60s wait) + `Plans` visible — signed back in on Home.
**✗** That logged-out state **actually gates** content (no assertion on the signed-out surface), session/token cleared, that B's data doesn't leak.

### 14_paywall  *(assumes free/trial account, pro=false)*
**Steps:** avatar → `scrollUntilVisible "Upgrade to Local Pro"` → tap → `Not Now`.
**✓** Paywall opens: headline `Plan together, effortlessly.` + `Ask Bo` row + `Create Plans` row · `Not Now` dismisses back to Progress (`Log Out` visible).
**✗** Actual purchase, RevenueCat offerings/pricing, intro-eligibility, restore, the "Manage Subscription" path a pro account would show.

### 15_notifications
**Steps:** avatar → `scrollUntilVisible "Notifications"` → tap.
**✓** Notification Settings screen renders its `Allow Notifications` toggle row.
**✗** Toggle behavior (intentionally not flipped — it routes to OS settings), persistence, mirror of OS permission state.

### 16_event_search_detail  *(advanced — complements 10 & 12)*
**Steps:** Explore → `location_pill` → `Current Location` → Events tab → search "music" → tap result.
**✓** `explore_tab_events` present · `explore_event_card` renders for the query (real data) · tapping it opens event detail with CTA `RSVP | Get Tickets | More Info`.
**✗** Result relevance/ranking, iOS native detail internals.

### 17_venue_favorite  *(advanced — Places counterpart to 11)*
**Steps:** Explore → Places → `Current Location` → tap `venue_favorite` twice.
**✓** `venue_card` rows render · `venue_favorite` present · list survives the favorite/unfavorite round-trip (`venue_card` still present).
**✗** Persistence, Profile reflection. Exercises the **distinct** `favoriteVenueIds` repo path vs 11's event path, but again *doesn't-crash*, not *correctly-saved*.

---

## COLLAB E2E — `.maestro/flows-collab/` (two devices, orchestrated by `collab_e2e.sh`)

End-to-end share→accept→points across device A (`testbypass@`) + device B (`testbypass+b@`):
- **A1_share** (A) — open seeded "Collab E2E Plan" → Gates `Share Plan` + `1 person unconfirmed` → tap Share (logs `MAESTRO_SHARE_TOKEN` for the orchestrator).
- **B_signin** (B) — sign B into the distinct account, land on Home.
- **B_accept** (B) — orchestrator fires the warm deep link → Gate `Accept Invite` → tap → accepted plan appears → **Gates the accepted-copy rules:** `plan_share_btn` still present BUT `confirmed` tally **NOT** visible (`assertNotVisible`).
- **A2_award** (A) — reopen plan → Gate: creator-only `Share Plan` present.
- **Orchestrator `verify` (the real payoff):** asserts in the DB that **A got the +2,500 `INVITE_ACCEPTED` badge** AND **B has a confirmed `plan_attendees` row**.

This is the only test that verifies anything **server-side** (the `accept_plan_invite` RPC effects).

---

## PAYWALL SUITE — `.maestro/flows-paywall/` (orchestrated by `paywall_gating.sh`, toggles trial state)

- **paywall_gates** (free account: `trial_ends_at` set past) — proves each gated action shows the paywall (`Plan together, effortlessly.`) instead of performing it:
  - Gate 3 — open a venue → paywall (place detail did NOT open)
  - Gate 4 — save a venue → paywall (not saved)
  - Gate 1 — open an event → paywall (event detail did NOT open)
  - Gate 2 — save an event → paywall (not saved)
  - Gate 6 — open a plan → paywall
- **paywall_premium_bypass** (premium: `trial_ends_at` set future) — proves the **same** account upgraded is NOT gated — every action succeeds with `assertNotVisible "Plan together, effortlessly."`:
  - Gates 4 + 2 — save venue/event toggle, no paywall
  - Gate 1 — open event → detail (`RSVP|Get Tickets|More Info`), no paywall
  - Gate 3 — open venue → detail (`Directions`), no paywall
  - Gate 6 — open plan → detail (`Share Plan`), no paywall

Together they lock the single `premiumGate.isPremium` chokepoint from both sides.

---

## CHAOS FLOWS — `.maestro/flows-chaos/` (crash/hang hunters, NOT assertions)

These deliberately assert **nothing** (every tap is `optional`); the verdict is process
death / hang / new crash report scraped by `chaos.sh`:
- **nav_churn** — 40× rapid tab + chat-sheet thrash (carousel-reload-on-Main hang class)
- **lifecycle_churn** — 30× background/foreground mid-animation (warm-start + deep-link race)
- **random_taps** — 25× pseudo-random taps/swipes/long-press ("monkey" for iOS)

Validated 2026-06-18 on the iOS sim: survived clean (no crash/hang/new crash report).

---

## What is NOT covered (one-line pointers — full detail in `TEST_GAP_AUDIT.md`)

- **All non-badge logic** below the UI: ViewModels, `PremiumGate.isTrialActive`, time parsing/sort, `removeDuplicateSlots`, DTO mapping, `ClaudeApiService` JSON, geo math — **0 unit tests**.
- **iOS native Swift** (12k lines) — no XCTest target exists.
- **Edge functions + RPCs** (except `accept_plan_invite`'s effects via collab) — no Deno tests.
- **Pipeline scripts** (22k lines) — no test runner.
- **UI depth:** plan edit/delete, multi-day render, deep-link cold start, empty/error states, pull-to-refresh, persistence-across-relaunch — none asserted.
