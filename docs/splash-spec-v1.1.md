# Splash Screen — Option A "Editorial" — v1.1

**Local · iOS + Android · Engineering Spec**
*Version 1.1 · 2026-05-26 · supersedes v1.0*

Premium-feel splash that introduces the brand, demonstrates the breadth of event types through a decelerating word-reel, and lands on the brand promise. Total runtime ≈ 3.4s before the tap affordance appears.

v1.1 closes the open items from the `/review` pass on v1.0: rewires the platform-mandated first frames (LaunchScreen.storyboard fonts are unreliable; Android 12+ SplashScreen API can't show arbitrary text); darkens reel hues that failed WCAG AA; gates the animation behind a remote kill switch; adds a brand-vs-short variant so returning users don't sit through the full sequence; adds a single accessibility label.

---

## 0. Status vs the current codebase

| Concern | Current state | v1.1 disposition |
|---|---|---|
| Splash implementation | Single shared Compose composable at `composeApp/src/commonMain/.../feature/onboarding/SplashScreen.kt` | Replace with editorial version (still shared) |
| iOS launch screen | None (no `UILaunchScreen` in Info.plist) | Add cream-background launch screen via Info.plist `UILaunchScreen` key |
| Android system splash | No SplashScreen API wiring — uses `Theme.Material.Light.NoActionBar` | Add `Theme.App.Starting` + `installSplashScreen()` in `MainActivity` |
| Font assets | Variable TTFs already bundled (Fraunces upright + italic, Nunito upright + italic) | No new bundling needed |
| Color tokens | `AppColors` has `Cream`, `Ink/Ink2/Ink3`, `Coral`, `Sage/SageDk`, `Sun/SunDk`, `Orange/OrangeDk`, `Sky/SkyDk`, `Plum/PlumDk` | Add `SplashSunReel`, `SplashOrangeReel` (darker variants that pass AA at 44pt); reuse existing `*Dk` tokens for Sage/Sky/Plum |
| App-ready gate | `App.kt` already implements `postSplashRoute` — splash plays full timeline, route resolves in parallel | Keep; tie "Tap to continue" reveal to this same flag |
| Kill switch | None | Add `SplashConfigService` that reads `splash_animation_enabled` from Supabase, caches via `multiplatform-settings`, defaults `true`. No variant cooldown — full splash every cold start. |
| Icon swap | Coral squircle "L" still in use | **Out of scope for this PR.** Icon redesign ships separately so App Store + Play Store listing updates are atomic. |

---

## 1. Layout (reference 402×874 logical px, scale proportionally)

| Element | Position | Notes |
|---|---|---|
| **Wordmark** "local" | top: 180, h-centered | 88sp, see §3 |
| **Eyebrow** "Every single" | top: 360, h-centered | 30sp italic, fades in at t=750ms |
| **Reel viewport** | top: 392, h-centered, w: 320, h: 56 | Masked top/bottom, see §4 |
| **Tagline** "One single app." | top: 472, h-centered | 26sp italic |
| **Accent underline** | top: 514, h-centered, w: 36, h: 2 | Coral, 2px corner radius |
| **Tap line** "Tap anywhere to continue" | bottom: 92, h-centered | 13sp uppercase |

Background: `AppColors.Cream` (`#FAFAF8`), flat, no gradient.

---

## 2. Color tokens (v1.1)

### Identity (wordmark + accent — WCAG-exempt as logo)
| Token | Hex |
|---|---|
| Coral | `#FF6B6B` |
| Ink | `#1A1523` |
| Ink2 | `#4A4458` |
| Ink3 | `#8A849A` |

### Reel-safe (≥3:1 contrast on Cream at 44sp italic large-text)
| Token | Hex | Used by reel item |
|---|---|---|
| Coral | `#FF6B6B` (3.3:1, AA large) | `event` (bookend + final) |
| SageDk (existing) | `#3AAD85` | `open mic`, `gig` |
| SkyDk (existing) | `#3A7CE8` | `pop-up`, `concert` |
| **SplashSunReel (new)** | **`#B86E00`** | `trivia night` |
| **SplashOrangeReel (new)** | **`#B85000`** | `show` |
| PlumDk (existing) | `#6D45C4` | `story time`, `festival`, `class` |
| Ink (existing) | `#1A1523` | `place`, `workshop` |

Brand `Sun`, `Orange`, and `Sage` keep their original hex on the wordmark + accent underline (identity); the darker reel-safe variants are used *only* inside the reel where the 44sp body-text contrast rule binds.

---

## 3. Wordmark

Five letters: `l` `o` `c` `a` `l`.

- **Font:** Fraunces, weight 900 (Black), upright
- **Size:** 88.sp
- **Letter spacing:** -0.04em (~-3.5sp)
- **Per-letter color:**
  - Index 0 (`l`) → Coral
  - Index 1–3 (`o`, `c`, `a`) → Ink
  - Index 4 (`l`) → Coral

### Entry animation
Each letter rises from below and fades in.

| Property | From | To |
|---|---|---|
| translateY | +0.6em (≈53dp) | 0 |
| alpha | 0 | 1 |
| Duration (translate) | 520ms | |
| Duration (alpha) | 320ms | |
| Easing | `cubic-bezier(0.2, 0.9, 0.25, 1.15)` (Compose: custom `CubicBezierEasing`) | |
| Per-letter stagger | 70ms | |
| Initial delay | 120ms | |

Total wordmark settle: ~920ms from splash mount.

### Reduce Motion path
Skip the per-letter stagger. Fade the entire wordmark in over 240ms.

---

## 4. Reel

Vertically scrolling column of 13 items; one visible at a time through a 56dp-tall masked viewport. Reel position is `translateY(-i × 56dp)`.

### Items, in order

| i | Word | Color token |
|---|---|---|
| 0 | event | Coral |
| 1 | workshop | Ink |
| 2 | story time | PlumDk |
| 3 | trivia night | SplashSunReel |
| 4 | class | SageDk |
| 5 | show | SplashOrangeReel |
| 6 | place | Ink |
| 7 | pop-up | SkyDk |
| 8 | festival | PlumDk |
| 9 | open mic | SageDk |
| 10 | game | SplashSunReel |
| 11 | concert | SkyDk |
| 12 | **event** | Coral *(final, scaled to 1.06)* |

### Word styling
- Font: Fraunces, weight 700 (Bold), italic
- Size: 44.sp
- Letter spacing: -0.025em (~-1.1sp)
- Row height: 56.sp (matches viewport)
- Final word i=12: `scale(1.06)`, 420ms transition, 80ms delay after arrival.

### Viewport mask
56dp-tall viewport with vertical gradient fade at top + bottom.

Compose implementation: render the column inside a `Box` that has the reel column behind two solid `AppColors.Cream` gradient overlays — top 22% and bottom 22% — drawn over the column. Cheaper than offscreen compositing.

### Timing (decelerating cycle)

```
interval(k) = lerp(minStep, maxStep, ((k-1) / (n-2))^3)
  minStep = 70 ms
  maxStep = 360 ms
  n       = 13 items
```

Cumulative arrival times (ms from splash mount), with `START = 750`:

| i | Word | Arrival (ms) |
|---|---|---|
| 0 | event | 750 |
| 1 | open mic | 820 |
| 2 | pop-up | 890 |
| 3 | show | 962 |
| 4 | story time | 1038 |
| 5 | trivia night | 1122 |
| 6 | place | 1219 |
| 7 | festival | 1336 |
| 8 | workshop | 1481 |
| 9 | concert | 1663 |
| 10 | gig | 1892 |
| 11 | class | 2180 |
| 12 | **event** | 2540 |

Total cycle: ~2540ms from mount (~1790ms of pure cycling).

### Reel transition curve
- Cycle: `cubic-bezier(0.4, 0.05, 0.55, 0.95)`, duration `max(90, min(380, nextGap × 0.9))ms`
- Final landing: `cubic-bezier(0.18, 0.9, 0.3, 1.08)`, duration 480ms

### Reduce Motion path
Skip the cycle entirely. Show only the final `event` item, fading in over 240ms after the wordmark.

---

## 5. Eyebrow + tagline + accent (post-reel)

### "Every single" (eyebrow)
- Font: Fraunces 500 italic
- Size: 30.sp
- Color: Ink2
- **Appears at:** t=750ms (synced with reel mount)
- **Entry:** alpha 0 → 1 over 240ms (no translateY — keeps the visual anchor stable). New in v1.1 — v1.0 had no eyebrow animation.

### "One single app." (tagline)
- Font: Fraunces 500 italic
- Size: 26.sp
- Color: Ink
- **Appears at:** t=2790ms (reel landing + 250ms)
- **Entry:** alpha 0 → 1 over 480ms, translateY +12dp → 0 over 580ms with `cubic-bezier(0.2, 0.85, 0.3, 1.05)`

### Coral accent underline
- 36 × 2 dp rounded rect (`RoundedCornerShape(2.dp)`), color Coral
- Centered, 42dp below the tagline
- **Animation:** scaleX 0 → 1 from center origin, 600ms with `cubic-bezier(0.2, 0.85, 0.3, 1.1)`, 200ms delay after tagline

### "Tap anywhere to continue"
- Font: Nunito SemiBold (600)
- Size: 13.sp
- Letter spacing: 0.04em (~0.5sp)
- All caps
- Color: Ink3
- **Appears when:** `max(t=3440ms, app-ready)` — never before either condition is met. App-ready = `postSplashRoute != null` in `App.kt`. (Spec-2790 + 650 reveal delay = 3440ms baseline.)
- **Entry:** alpha 0 → 1 over 600ms

---

## 6. Interaction

- Tap anywhere on the splash dismisses it as soon as the wordmark begins entering (do not gate on full animation completion). The dismiss callback is wrapped via `rememberUpdatedState` to avoid the Compose `pointerInput` stale-capture gotcha (see `feedback_compose_pointerinput_stale_capture.md`).
- If the user has not tapped after the tap line appears, auto-dismiss after a further 4000ms.
- Honor OS Reduce Motion:
  - Wordmark: full fade (240ms), no per-letter stagger.
  - Reel: skip the cycle; show only the final `event`, fading in over 240ms after the wordmark.
  - Eyebrow / tagline / accent / tap line: fade only, no translateY or scale.

---

## 7. Variants

Two modes only — animated (every cold start) and static (kill-switch fallback).
v1.0 had a 12h-cooldown "short splash" for repeat launches; v1.1 removed it
as overcomplicated. The full editorial timeline plays every cold start.

| Variant | When | Behavior |
|---|---|---|
| **Animated** | Every cold start (default) | Full §3–§5 timeline. Under OS Reduce Motion, the same beats fire at the same times but each motion collapses to a fade — no per-letter stagger, reel snaps straight to the final `event`, no translateY/scaleX. |
| **Static** | `splash_animation_enabled = false` (remote kill switch) | Static wordmark frame only; dismiss as soon as app-ready |

---

## 8. Master timeline (mount = t0) — brand variant

| t (ms) | Event |
|---|---|
| 0 | Mount. Cream background painted. |
| 120 | First wordmark letter `l` (coral) begins entry. |
| 920 | Wordmark fully settled. |
| 750 | Eyebrow fade-in begins. Reel mounts at i=0. |
| 820 → 2540 | Reel cycles through 12 transitions, decelerating. |
| 2540 | Reel lands on final `event`. 480ms scale-up to 1.06 begins at 2620. |
| 2790 | Tagline enters. |
| 2990 | Coral accent underline scales in. |
| max(3440, app-ready) | "Tap anywhere to continue" fades in. |
| tap line + 4000 | Auto-dismiss if no tap. |

---

## 9. Accessibility

- Single root accessibility label on the splash: `"Local — every single event, one single app. Tap to continue."`
- All animated text inside the splash is marked accessibility-hidden so screen readers don't read the 13 sequential reel words.
- Respect Reduce Motion (§6).

---

## 10. Platform implementation

The splash itself is a single shared Compose composable (`composeApp/src/commonMain/.../SplashScreen.kt`) — the same code runs on both platforms. The platform-divergent piece is the system-mandated first frame *before* Compose mounts.

### iOS
- Add a modern launch screen via Info.plist `UILaunchScreen` key:
  ```xml
  <key>UILaunchScreen</key>
  <dict>
      <key>UIColorName</key>
      <string>SplashCream</string>
  </dict>
  ```
- Add a `SplashCream` color asset in `Assets.xcassets` set to `#FAFAF8`.
- The existing app icon shows centered by default on iOS launch screens. When the icon-replacement PR ships, both update together.
- *Do not* try to animate the launch screen — iOS launch screens are static. The Compose splash takes over at first frame of `ContentView`.

### Android
- Add `androidx.core:core-splashscreen` to `composeApp/build.gradle.kts` androidMain deps:
  ```kotlin
  implementation("androidx.core:core-splashscreen:1.0.1")
  ```
- Add `Theme.App.Starting` in `composeApp/src/androidMain/res/values/themes.xml` extending `Theme.SplashScreen`:
  - `windowSplashScreenBackground` → `@color/splash_cream` (`#FAFAF8`)
  - `windowSplashScreenAnimatedIcon` → existing `@mipmap/ic_launcher_foreground` (no change yet)
  - `postSplashScreenTheme` → existing `Theme.Material.Light.NoActionBar`
- Update `AndroidManifest.xml` `<application>` to use `Theme.App.Starting`.
- Call `installSplashScreen()` in `MainActivity.onCreate` **before** `super.onCreate`. Wire `setKeepOnScreenCondition` to a `@Volatile` flag flipped by Compose once the app-ready check completes (matches the `postSplashRoute` pattern already in `App.kt`).
- Pre-12 fallback: the SplashScreen library handles the polyfill — pre-12 devices get the static splash via the same theme, no animation in the system layer.

### KMP Feature Ownership Matrix entry (add to roles/mobile.md)
| Feature | iOS | Android | Parity | Reason |
|---|---|---|---|---|
| Splash screen first frame | Info.plist `UILaunchScreen` cream + app icon | `Theme.App.Starting` via core-splashscreen + app icon | divergent (permanent) | platform-required first frame |
| Splash animation | Shared Compose | Shared Compose | identical | same shared composable |

---

## 11. Kill switch

New service `live.localapp.local.domain.service.SplashConfigService`:

- On construction, reads `splash_animation_enabled` from `multiplatform-settings` (default `true`).
- Returns the cached value synchronously for the current cold-start.
- Fires an async Supabase fetch against `app_config` (or whatever table is chosen during db-op) to refresh the cached value for the *next* launch.
- If the Supabase fetch fails (table missing, network down, etc.), the cached value is unchanged.

Wiring: `App.kt` reads the flag before mounting `SplashScreen`. When `false`, `SplashScreen` skips all animation and renders only the static wordmark + cream background until app-ready, then calls `onComplete`.

The Supabase table creation + initial row insert is a separate `/db-op` task. Without that work the flag is effectively local-default-true with no remote control surface — which is the correct fail-open behavior for a brand splash.

---

## 12. Out of scope for this PR

- App icon redesign (current coral squircle → new typographic-derived icon). Ships in its own PR.
- Supabase migration to create the `app_config` table backing the kill switch. Local default `true` is safe until then.
- Adapting any other surface to the wordmark identity (auth screen, onboarding, etc.).
