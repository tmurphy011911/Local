# Maestro E2E regression suite

Cross-platform (Android + iOS) UI regression flows for the Local app. The same
flows run on both platforms because the app id is identical: `live.localapp.local`.

## Prerequisites

- **Debug build** installed on the target device/emulator. The suite relies on
  `Config.TEST_AUTH_BYPASS = true` (skip real SMS OTP) and
  `Config.TEST_FORCE_ONBOARDING = true` (route the canned account through
  onboarding). Both are inert in release builds.
- A booted **Android emulator** and/or **iOS simulator**.
- Maestro on PATH (`~/.maestro/bin`).

### Install the debug build

```bash
# Android
./gradlew :composeApp:installDebug

# iOS — build & install the debug app onto the booted simulator from Xcode,
# or via xcodebuild, then confirm it is installed:
xcrun simctl get_app_container booted live.localapp.local
```

## Run

```bash
# Android emulator
maestro --device emulator-5554 test .maestro/flows

# iOS simulator (use the booted device's UDID from `xcrun simctl list devices booted`)
maestro --device <SIM_UDID> test .maestro/flows

# A single flow
maestro test .maestro/flows/01_onboarding.yaml

# By tag
maestro test --include-tags smoke .maestro/flows
```

Flows run alphabetically. `01_onboarding.yaml` clears app state and drives the
full new-user flow; `02`–`06` launch without clearing state, so they reuse the
signed-in/onboarded local state (and fall back to signing in if state was
cleared, via `subflows/ensure_home.yaml`).

## Layout

| File | Covers |
|------|--------|
| `subflows/sign_in.yaml` | Bypass auth: email + phone + any 6-digit OTP |
| `subflows/complete_onboarding.yaml` | Location (seeded Boston) → skip prefs → Home |
| `subflows/ensure_home.yaml` | Launch + guarantee we land on Home, any start state |
| `flows/01_onboarding.yaml` | New-user onboarding end-to-end |
| `flows/02_home.yaml` | Home feed loads + scrolls |
| `flows/03_explore.yaml` | Explore tab search |
| `flows/04_plans.yaml` | Plans tab + Create Plan picker |
| `flows/05_profile.yaml` | Profile (badges/progress) screen |
| `flows/06_chat.yaml` | Bo chat opens from Home and responds |

## Known brittleness (selectors are text-based; no testTags in the UI yet)

These spots may need refining after the first run, and are the best candidates
for adding `Modifier.testTag(...)` + `Modifier.semantics { testTagsAsResourceId = true }`
to harden the suite:

- **Auth fields** — email/phone have no labels; selected via the `you@email.com`
  placeholder and the `+1` prefix. A `testTag` on each field would be sturdier.
- **OTP field** — focused by a screen-percentage tap.
- **Chat entry** — the Home "Ask Bo" bar is tapped via the `"Bo"` text.
- **Manual address** — intentionally avoided (network autocomplete); the suite
  uses `setLocation` + the GPS permission path instead.

Live-model and network-backed assertions (chat reply, search results) are kept
soft (`optional` / existence-only) to avoid flakiness.
