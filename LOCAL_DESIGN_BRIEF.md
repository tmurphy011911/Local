# Local — Design Brief for Claude Design

A complete reference so any visual deliverable (illustrations, GIFs, mockups, marketing
assets) lands on-brand without back-and-forth. Everything here is canonical — pulled
from the actual app codebase, not aspirational.

---

## 1. The App in One Paragraph

**Local** (localapp.live) is an event-and-place discovery app for cities, primarily
Boston for launch, expanding to other US metros. It surfaces every event happening
nearby — concerts, theatre, library readings, food pop-ups, sports games, kids
classes — in one place. **Bo**, the in-app AI companion, plans personalized
itineraries by chatting with users. The product replaces three behaviors: googling
"things to do this weekend," asking the group chat, and building shared spreadsheets
for trips.

**Audience:** late-20s to mid-40s urbanites, indie-leaning but not exclusionary.
Curious, social, slightly tired of generic recommendations.

**Tone:** confident, dry, occasionally cheeky. Punchy over verbose. Local sounds
like a sharp friend who lives in your neighborhood, not a brand.

---

## 2. Brand Voice

### Voice characteristics

- **Direct.** Subject—verb—object. No hedge words.
- **Editorial.** Clear point of view. Doesn't try to please everyone.
- **Dry humor.** Comfortable with a wink. Never tries too hard.
- **Specific.** Real places, real prices, real verbs ("save," "tap," "show up").
- **Mobile-native.** Short sentences. Em-dashes for rhythm. No corporate suffixes.

### Voice examples (what to imitate)

> "RIP to the 'what's good tonight?' group chat."
>
> "Stop making itinerary docs nobody opens. Tap to plan, tap to share. That's it."
>
> "The more you save, plan, and show up, the smarter Local gets. Almost suspicious."
>
> "Climb the ranks for real rewards — Ticketmaster gift cards on us."

### Voice anti-patterns (what NOT to write)

- ❌ "Discover amazing experiences in your area!"
- ❌ "Empower yourself with personalized recommendations."
- ❌ "Unlock the full potential of your weekends."
- ❌ Anything ending in an exclamation mark unless it's earned.
- ❌ "Click here," "Check it out," "Don't miss."

### Em-dash style

We use the em dash (`—`, U+2014) for tonal pauses. Never `--`. Never the en dash for
sentence-level breaks.

---

## 3. Design Tokens (canonical)

### Colors

Pulled directly from `composeApp/src/commonMain/kotlin/live/localapp/local/ui/theme/AppTheme.kt`.
**Use these hex values exactly — no eyeballed close matches.**

#### Primary palette

| Token | Hex | Usage |
|---|---|---|
| **Coral** | `#FF6B6B` | Primary brand. CTAs, accents, the styled italic word in headlines. |
| Coral Dark | `#E85555` | Pressed state on coral CTAs. Gradient end. |
| Coral Light | `#FFE8E8` | Coral-tinted surface (rare). |
| **Sage** | `#4ECBA0` | Secondary brand. "Free" pill, success states, places-pin accent. |
| Sage Dark | `#3AAD85` | Save-button gradient end. |
| Sage Light | `#E6F9F2` | Sage-tinted surface. |
| **Sun** | `#FFB830` | Warning, sports/highlight, paywall pricing badge. |
| Sun Dark | `#E6A020` | |
| Sun Light | `#FFF4DC` | Warning surface. |
| **Sky** | `#5B9CF6` | Location pill (in-supported-state), info accent. |
| Sky Dark | `#3A7CE8` | |
| Sky Light | `#EBF2FF` | Weather bar background. |
| **Plum** | `#8B5CF6` | Nightlife category, occasional progress accent. |
| Plum Dark | `#6D45C4` | |
| Plum Light | `#F3EEFF` | |
| Orange | `#FF9B4E` | Rare. Reserved for badges. |

#### Neutrals (Ink scale)

| Token | Hex | Usage |
|---|---|---|
| **Ink** | `#1A1523` | Primary text, icon strokes, the "official" near-black. |
| Ink2 | `#4A4458` | Secondary text. |
| Ink3 | `#8A849A` | Tertiary text, inactive states. |
| Pebble | `#C8C3D4` | Borders, dividers, inactive dots. |
| Mist | `#F0EDF6` | Skeleton loaders, subtle surface. |
| **Snow** | `#FAF8FC` | Primary background. The off-white the app sits on. |
| White | `#FFFFFF` | Card surfaces. |
| Cream | `#FAFAF8` | Marketing-only alt background. |

#### Plan Detail V5 palette (scoped)

A warmer palette used **only** on the Plan Detail screen. Don't use these
elsewhere — they exist to give plan detail a distinct "document" feel.

| Token | Hex |
|---|---|
| V5 Coral | `#F56B63` |
| V5 Coral Dark | `#E94F47` |
| V5 Coral Soft | `#FBE4E1` |
| V5 Coral Ink | `#7A231F` |
| V5 Ink | `#1A1715` |
| V5 Ink2 | `#3D3834` |
| V5 Ink3 | `#6B6560` |
| V5 Ink4 | `#A49E98` |
| V5 Line | `#E8E3DD` |
| V5 Line Soft | `#F1ECE6` |
| V5 Paper | `#FBF8F4` |
| V5 Shade | `#F5EFE8` |
| V5 Green | `#4AA27A` |

#### Card-background gradients (per category)

Each event-card category has a soft gradient. Always Start → End, top-left to
bottom-right.

| Category | Start | End |
|---|---|---|
| Coral (default) | `#FFF0EE` | `#FFE0DC` |
| Sage | `#EDFAF5` | `#D5F5E8` |
| Sun | `#FFF8EC` | `#FFF0CC` |
| Sky | `#EEF5FF` | `#DCEEFF` |
| Plum | `#F5F0FF` | `#EDE0FF` |

### Typography

Two type families, both variable fonts, bundled in the app.

| Family | Role | Weights used |
|---|---|---|
| **Fraunces** | Display, headlines, logo, italic accent words | 400, 700, 900 (+ italic at each) |
| **Nunito** | Body, UI labels, all running text | 400, 600, 700, 800, 900 |

Italic is reserved for the **accent word** in tutorial/hero headlines. Always coral.

#### Type scale (in pt/sp)

| Style | Family | Weight | Size | Use |
|---|---|---|---|---|
| Logo | Fraunces | 900 | 28 | "local" wordmark |
| Greeting Big | Fraunces | 900 | 24 | Home greeting, hero |
| AI Title | Fraunces | 700 | 20 | Bo modal title |
| Section Title | Fraunces | 700 | 18 | Carousel headers |
| Earn Pts | Fraunces | 900 | 15 | Points value |
| Plan/Event Title | Fraunces | 700 | 13 | Card titles |
| Points Counter | Fraunces | 900 | 64 | Profile streak number |
| Streak Value | Fraunces | 900 | 22 | Streak count |
| Earn Action | Nunito | 800 | 13 | Earn-rule action label |
| Segment Button | Nunito | 800 | 13 | Sub-tabs |
| Plan Name | Nunito | 800 | 13 | Plan list items |
| Label Small | Nunito | 800 | 12 | Pills, tags |
| Greeting Small | Nunito | 700 | 12 | Subhead under greeting |
| Tab Label | Nunito | 800 | 9 | Bottom-nav labels |
| Earn Desc | Nunito | 600 | 11 | Earn-rule body |
| Plan Meta | Nunito | 600 | 11 | Plan list meta |
| Label Tiny | Nunito | 800 | 11 | Tiny pills |
| Label Micro | Nunito | 800 | 9 | Micro pills |

### Shapes / corner radii

| Token | Radius | Use |
|---|---|---|
| Card Large | 20dp | Hero cards, tutorial GIF placeholder |
| Card Medium | 18dp | Plan card, See-All sheet |
| Card Small | 16dp | Buttons, inline cards |
| Pill | 20dp | Pills (location, "Free" badge) |
| Icon Box | 12dp | Square icon containers |
| Category Box | 18dp | Home category tile background |
| Segment Control | 14dp | Outer of segmented controls |
| Segment Button | 11dp | Inner segmented button |
| AI Input | 12dp | Chat input field |
| Avatar | 11dp | Squircle avatars |

### Spacing rhythm

The app uses a 4dp grid. Common values:

- 4dp — tight (icon-to-text)
- 8dp — small gap (between pills, inline)
- 12dp — section internal padding
- 14dp — card edge padding
- 16dp — card-to-card vertical
- 20dp — screen edge horizontal padding
- 24dp — generous block spacing
- 32dp — hero/onboarding edge padding

---

## 4. Iconography

### LocalIcons system (17 icons, custom-drawn)

Each icon lives in `composeApp/src/commonMain/kotlin/live/localapp/local/ui/icons/LocalIcons.kt`,
authored on a 100×100 viewBox and rendered via Compose Canvas. They have signature
tap-driven animations.

| Icon | Use | Signature animation |
|---|---|---|
| **Events** | Explore tab "Events" sub-tab | Day stamps in (squash/stretch), ring expands |
| **Places** | Explore tab "Places" sub-tab | Pin drops with squash/stretch, ripples |
| **For You** | Home category #1 | Star rotates 360°, sparkles burst |
| **Music** | Home category | Vinyl spins 360° |
| **Sports** | Home category | Trophy bounces |
| **Outdoors** | Home category | Mountain sways, sun rises |
| **Experiences** | Home category | Outer rays expand+rotate, core pulses |
| **Tours** | Home category | Compass needle spins 540° with overshoot |
| **Nightlife** | Home category | Glass tilts, olive bobs |
| **Libraries** | Home category | Right page flips, left page swells |
| **Theatre** | Home category | Curtains part, star reveals |
| **Art** | Home category | Paint blobs pulse in sequence, brush slides in |
| **Food** | Home category | Three steam paths rise and fade |
| **Comedy** | Home category | Mic wobbles, sound waves pulse |
| **Kids** | Home category | Pinwheel spins 900° (2.5 rotations) |
| **Museums** | Home category | Pediment lifts in, columns fade up |
| **Other** | Home category fallback | Center sparkle rotates, orbit dots rotate |

**Style rules:**
- Geometric, filled. Not thin-line, not 3D.
- One accent color per icon (Coral, Sage, or Sun) plus Ink for strokes/details.
  Kids pinwheel is the deliberate exception (multi-color for spin readability).
- 3.5dp stroke is the heavy outline. 2.5dp is medium. 1.5dp for hairline accents.
- Strokes are always Ink (`#1A1523`).

When designing **anything** that includes icons (illustrations, marketing assets,
GIFs), use the same visual vocabulary — same stroke weight, same accent-color rule.

### Material icons

Where the LocalIcons system doesn't cover (UI affordances like close, arrow,
notification), we use **Material Icons Outlined**. Tinted Ink or Ink3.

---

## 5. Component Patterns

These are the recurring patterns across the app. Match these visually.

### Bottom nav (4 tabs)

- Home, Explore (search icon), Plans (calendar icon), Profile (person icon)
- Active state: coral icon + label, bold weight
- Inactive: Ink3 icon + label, regular weight
- Floating "Create Plan" button overlays from the right (coral pill, "+ Create Plan")

### Top header (`AppHeader`)

- White background, 20dp horizontal padding
- Logo "local" left (lowercase L's tinted Coral), pill row right
- Pill row: `[Location pill]` + avatar
- Location pill: 28dp tall, Sky Light bg + Sky border (or Sun bg + Sun border if
  user is in unsupported state — shows warning triangle)

### Cards

- White surface, 18dp radius, 1.5dp Pebble border (no shadow on flat cards)
- Plan/hero cards add a 5dp downward Pebble shadow (offset y, no blur)
- Image area is full-bleed when present, 120dp tall in slot/plan rows

### Buttons

- **Primary CTA:** coral linear gradient (Coral → Coral Dark), 16dp radius, 16dp
  vertical padding, white Nunito Black 16pt label
- **Secondary CTA:** Ink solid (or transparent with Ink Bold label for "Maybe later"
  / "Not Now" copy)
- **Pressable list item:** white card, slight scale-down on press (`pressable`
  modifier)

### Pills & badges

- Pill = 20dp radius, 4dp vertical / 8dp horizontal padding
- "Free" badge: green (`#E8F5E9` bg, `#A5D6A7` border, `#2E7D32` text)
- Category pill on event cards: Ink Bold 12pt, on coral/sage/sun tint
- Day-counter pill on plan card: white pill on colored card header

### Sheets

All sheets use `ModalBottomSheet` with:
- White container
- 36dp × 4dp Pebble drag handle, top-centered
- Top corners rounded 24dp (large radius for sheet feel)
- Skip-partial expansion (always full-height)

### Bo chat bubble

- User: coral right-aligned bubble, white text, 18dp radius (top-right tighter)
- Bo: snow/white left-aligned bubble, ink text, 18dp radius (top-left tighter)
- Plan card output: full-width white card with day-tabs header

### Plan card (multi-day)

- Coral header (Day 1 of N + day name + stop count)
- Optional Sky-Light weather bar
- Horizontal slot pager with image cards (120dp tall)
- Sage gradient "Save to My Plans" CTA at bottom

---

## 6. Key Screens

For visual mockups, refer to actual screens. Each has its own composition rules:

### Home

- AppHeader → "Tell me what you're planning" coral hero card with typewriter
  placeholder → category strip (animated icons) → carousel feed (interleaved by
  type, never same kind back-to-back).

### Explore

- AppHeader → "Events" / "Places" sub-tabs (with animated LocalIcons.Events/
  LocalIcons.Places) → search bar → filter pills → result list.

### Plans

- AppHeader → list of plan cards. Empty state: illustration + "No plans yet" + CTA.

### Profile

- AppHeader → tier hero (huge points counter on Ink-near-black gradient) → streak
  → earn-points action list → settings list.

### Bo chat (modal sheet)

- Header: "Bo" Fraunces Bold + close X
- Message stream
- Bottom: text input (12dp radius) + Send button

### Plan detail (V5 palette)

- Cream/paper background
- Day tabs at top
- Per-slot rows with thumbnail, title, time, attendee chips, travel time
- Bottom CTA varies by user role (creator/invitee/past)

### Paywall (`PaywallView` on iOS, `PremiumPaywall.kt` on Android)

- Ink-near-black background (`#1A1523`)
- Coral L app-icon mark
- Headline: "Bo Premium" — italic accent: "Plan together, effortlessly"
- Benefit rows with coral-tinted icon boxes
- Two pricing cards (Annual + Monthly), Coral selected state
- Primary CTA: "Start Local Pro" (coral gradient)
- Secondary: "Not Now" (white at 60% opacity)

### Tutorials (Home + Bo)

- Snow background, 5-step pager (Home) / 4-step pager (Bo)
- 240–260dp gradient placeholder for the GIF/illustration
- Headline (Fraunces Black 26) + italic accent word in Coral
- Subtext (Nunito SemiBold 15, Ink2)
- Page dots (active: Coral, 10dp; inactive: Pebble, 6dp)
- CTA: Ink for "Skip", Coral gradient for "Get Started"

---

## 7. Animation & Motion

### Easing curves

- Standard: `cubic-bezier(0.4, 0, 0.2, 1)` — most UI moves
- Bounce: `cubic-bezier(0.34, 1.56, 0.64, 1)` — playful entrance
- Squash: `cubic-bezier(0.5, 0, 0.5, 1)` — pin/stamp drops
- EaseOutFast: `cubic-bezier(0.25, 0.5, 0.4, 1)` — ripples, reveals

### Common durations

- 150ms — small UI hover/active states
- 300ms — sheet-in / sheet-out
- 500–800ms — content reveals, tutorial entrance
- 700–1000ms — icon signature animations

### Tutorial card transitions

- Horizontal pager swipe — no custom decoration
- Page dot scale: 6dp → 10dp on active, 200ms ease

---

## 8. Asset Specs (for the GIFs Claude Design will produce)

The tutorial cards currently render a gradient placeholder where a GIF should
play. Spec for each:

### General specs

- **Format:** Lottie JSON (preferred — vector, scales clean) OR animated WebP
  (Coil3 plays these via `AsyncImage`).
- **Aspect ratio:** roughly 4:3 (placeholder is 240–260dp tall × ~screen-padded width)
- **Background:** transparent, OR matched to Snow (`#FAF8FC`)
- **Loop:** 3–5 seconds, smooth loop point
- **Motion:** subtle, signature to the card's verb. Not flashy. Not infinite spinner.
- **Color palette:** Coral, Sage, Sun, Ink — use 2 colors max per GIF for
  consistency with the icon system.
- **Stroke weight:** 3.5dp outline if illustrated like LocalIcons; flatter
  illustrative styles welcome.

### Per-tutorial GIF brief

#### Home tutorial

| Step | Theme | Suggested motion |
|---|---|---|
| 1 — Welcome to Local | "Search → result" | Search bar with cursor typing "fun things to do…", then a list of 3 event cards stacks in. Coral. |
| 2 — Meet Bo | "AI replies" | Chat bubble appears with a typing dot indicator, then a plan card slides in. Sage accent for Bo bubble. |
| 3 — Plans without spreadsheets | "Spreadsheet → plan" | An ugly grid spreadsheet dissolves into a clean plan card. Sun → Coral transition. |
| 4 — Built for you, by you | "Personalization" | Generic tile grid (gray) tiles flip and re-color into Coral/Sage/Sun preference tiles. |
| 5 — Show up, level up | "Points → reward" | Points counter ticks up, a Ticketmaster-style ticket icon flips in at the end. Sun accent. |

#### Bo tutorial (4 steps)

| Step | Theme | Suggested motion |
|---|---|---|
| 1 — Tell Bo what you're planning | "Type a prompt" | Text typing in chat input ("date night this Friday"), send button pulses. |
| 2 — Get a personalized plan | "Plan card builds" | A plan card composes itself: image fades in → title → time → next slot. |
| 3 — Share with your group | "Tap to share" | Plan card duplicates and slides toward 3 avatar circles. |
| 4 — Book and go instantly | "Reserve / get directions" | Three tappable buttons (reserve, ticket, map) animate in a row. |

### What to AVOID in motion

- ❌ Infinite spinning loaders that don't tie to the card content.
- ❌ Over-elaborate parallax. Keep it 2D, paper-cut, vector.
- ❌ Photo realism. We're illustrative.
- ❌ Stock-icon faces or generic tech imagery.
- ❌ Anything that takes longer than 5 seconds to convey its idea.

---

## 9. Brand Don'ts (across everything)

- Never use a stock-photo person of any kind.
- Never write "personalized experience," "amazing," or "discover" unironically.
- Never use sans-serif italics for accent words (italic is Fraunces only).
- Never put coral text on coral background.
- Never use shadow + border together. Pick one.
- Never animate the entire icon as one thing — the LocalIcons system animates
  internal groups (curtains, blades, pin), and that's the visual signature.
- Never write a tutorial card without a verb in the headline.

---

## 10. References (in this repo)

| What | Path |
|---|---|
| Color/type/shape tokens | `composeApp/src/commonMain/kotlin/live/localapp/local/ui/theme/AppTheme.kt` |
| LocalIcons (17 custom icons) | `composeApp/src/commonMain/kotlin/live/localapp/local/ui/icons/LocalIcons.kt` |
| Home tutorial composable | `composeApp/src/commonMain/kotlin/live/localapp/local/ui/home/HomeTutorial.kt` |
| Bo tutorial (Compose, Android) | `composeApp/src/commonMain/kotlin/live/localapp/local/ui/chat/BoTutorial.kt` |
| Bo tutorial (SwiftUI, iOS) | `iosApp/iosApp/BoTutorialView.swift` |
| Paywall (iOS) | `iosApp/iosApp/PaywallView.swift` |
| Paywall (Compose) | `composeApp/src/commonMain/kotlin/live/localapp/local/ui/chat/PremiumPaywall.kt` |
| App header | `composeApp/src/commonMain/kotlin/live/localapp/local/ui/common/AppHeader.kt` |
| Plan card (multi-day) | `composeApp/src/commonMain/kotlin/live/localapp/local/ui/chat/ChatScreen.kt` (search `MultiDayPlanCard`) |

---

## 11. Quick-reference deliverable checklist

If Claude Design is being briefed for a specific deliverable, attach this checklist:

- [ ] Uses **only** the documented color hex values
- [ ] Uses **Fraunces** for any display/headline copy, **Nunito** for body
- [ ] Italic = Fraunces italic only, in Coral
- [ ] Em dashes (`—`), never `--`
- [ ] Strokes are Ink (`#1A1523`); accent fills are one of Coral/Sage/Sun
- [ ] Corners use the documented shape tokens (no arbitrary radii)
- [ ] Voice matches the examples in §2 (direct, dry, specific)
- [ ] No exclamation marks unless earned
- [ ] No stock photo people
- [ ] Animations under 5s, with a clean loop point if looping

---

*Brief reflects app state as of 2026-04-27. Update this doc when tokens or
component patterns change in the codebase.*
