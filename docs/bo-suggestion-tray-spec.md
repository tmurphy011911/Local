# Bo structured response system + Suggestion Tray

Spec for #6 in the Bo bug queue. Tonight (2026-05-06) shipped the prompt-level
fixes for #1–5; this doc captures the larger UI redesign for #6 so we don't
lose state.

## Problem

Bo's chat is paragraph-only today. Events are described as text bullets, the
user retypes which one they mean, then Bo tries to build a plan from free-text
references. That breaks in multiple interrelated ways:

- **Disambiguation tax** — user retypes "the Springsteen one"; Bo guesses wrong.
- **Plan-build trips** because the chat re-parses titles instead of carrying
  explicit event IDs.
- **Web_search rambles** because Bo treats any noun as research instead of a DB
  query (already partially mitigated by tonight's prompt changes).
- **Single-shot fail** — Bo doesn't keep digging when a search misses because
  there's no UI to say "look further" (also partially mitigated by SEARCH DEPTH
  rule shipped tonight, but a UI chip would be much stronger).
- **Off-topic drift** — Bo answers any question (also partially mitigated by
  SCOPE rule tonight).

Fix: replace the single-text-bubble model with a **response-shape system** keyed
to the user's ask. Bo picks the shape; the client renders the right component;
events are first-class objects with explicit IDs, never re-resolved from text.

## Ask taxonomy → response shape

| Ask shape | Example | Response component |
|---|---|---|
| Discovery / browse | "What's happening Saturday?" | `SuggestionTray` |
| Single-event detail | "What time is Springsteen?" | `EventBriefCard` |
| Single-venue detail | "What's at TD Garden?" | `VenueBriefCard` |
| Plan build | "Plan a Saturday around the Sox game" | `present_plan` (existing) |
| Plan edit | "Replace Tuesday" / "Not the Red Sox" | `present_plan` reissued with diff |
| Comparison | "ICA vs MFA?" | `ComparisonStrip` |
| Quick fact | "How much are LCD tickets?" | `QuickFactPill` |
| Clarifying question | Bo needs more info | Plain prose + chip suggestions |
| Acknowledgment | "Got it!" | Plain prose, ephemeral |
| Scope decline | "What is Lalibela?" | `ScopeRedirect` |
| Web-search-grounded | "Is Roadrunner 21+?" | `EventBriefCard` with cited fact line |

## Components

### `SuggestionTray` (load-bearing)

Used for any browsing intent. Two display modes via `groupBy`.

**Data shape Bo returns:**
```json
{
  "type": "suggestions",
  "headline": "Saturday, May 25",
  "groupBy": "day",
  "groups": [
    {
      "label": "Music",
      "events": [
        { "event_id": "evt:abc123", "boRationale": "huge crowd-pleaser" },
        { "event_id": "evt:def456", "boRationale": "early-evening pop spectacle" }
      ]
    }
  ],
  "footer": { "actions": ["search_wider", "different_category", "late_night"] }
}
```

Client hydrates each `event_id` against the local event repo — Bo's payload
stays tiny (just IDs + rationales), and we never trust Bo with display fields it
could hallucinate.

#### Render — Mode A (Day-first)

```
Saturday, May 25

  Music
   • LCD Soundsystem · Roadrunner · 8pm · $65+      [+]
     huge crowd-pleaser
   • Hatsune Miku EXPO · Agganis · 7pm · $80+       [+]
     J-pop spectacle

  Arts
   • ICA First Fridays · 6pm · Free                 [+]
     velour tracksuits & Y2K
   • SoWa Open Studios · 7pm · Free                 [+]
     80+ artists open their doors

  ┌─ Want more? ─────────────────────────────────┐
  │  [Search wider] [Different category] [Late night]
  └──────────────────────────────────────────────┘
```

#### Render — Mode B (Category-first)

```
Arts events this week

  Friday, May 24
   • ICA First Fridays · 6pm                        [+]
   • SoWa Open Studios · 7pm                        [+]

  Saturday, May 25
   • Studio Tour: ZUMIX · 2pm                       [+]
   • MFA After Hours · 7pm                          [+]

  Sunday, May 26
   • Plein Air Festival · 11am                      [+]
```

#### Render — Mode C (Flat)

For "free events tonight" — single category, single time-window, no grouping.

```
Free tonight

   • ICA First Fridays · 6pm · ICA Boston           [+]
   • Brewery Book Club · 7pm · Lamplighter          [+]
   • Open Mic · 8pm · Club Passim                   [+]
```

### `EventBriefCard` — single-event answer

```
┌────────────────────────────────────────┐
│  Springsteen & E Street: Land of Hope  │
│  Saturday, May 24 · 4:30 PM            │
│  TD Garden · 100 Legends Way           │
│  $89+                                   │
│                                         │
│  [Open detail]    [+ Add to plan]      │
└────────────────────────────────────────┘
```

### `VenueBriefCard` — single-venue answer

```
┌────────────────────────────────────────┐
│  TD Garden                              │
│  100 Legends Way, Boston · ★4.5         │
│                                         │
│  Upcoming:                              │
│   • Bruins vs Sabres · Tomorrow 7:30pm  [+]
│   • Springsteen · Sat 4:30pm            [+]
│   • Fleetwood Mac · Mon 8pm             [+]
│  [See all 12 events]                    │
└────────────────────────────────────────┘
```

### `ComparisonStrip`, `QuickFactPill`, `ScopeRedirect`

See full spec for details — concise alternatives to paragraph responses.

## Draft tray (cross-cutting)

Persistent bar above the keyboard collecting events the user `+`'s across
multiple Bo turns:

```
In your plan (3)                            [Build →]
  Fri  6:00  ICA First Fridays               [×]
  Sat  4:30  Springsteen                     [×]
  Sat  8:00  LCD Soundsystem                 [×]
```

`[Build →]` ships the whole list to Bo as one structured prompt:
*"Build a plan from these specific event_ids: [...]. Add transit, dinner, slot
timing."* Bo never has to guess which events the user wants.

## Dynamic chip logic on event-add

Bo's response intensity scales with tray state.

| Tray state | Bo response | Chip set |
|---|---|---|
| 0 events | Whatever the ask called for | Search-broadening chips |
| 1 added | Toast only, no prose. | "Find more like this" / "What's near it?" / "Build plan around this" |
| 2 same day, gap ≥2h | `SuggestionTray` filling the gap | "Skip the gap" / "Different cuisine" / "I'll figure it out" |
| 2 same day, no gap | Just chips | "Add another day" / "Pre-game" / "After-show" / "Build plan" |
| 2 different days | "Multi-day plan in the works." | "Add another day" / "Just these two" / "Build multi-day plan" |
| 3 events | Subtle nudge | "Build plan" emphasized + "Keep adding" / "Pace this" |
| 4+ events | Build CTA promoted to primary | Primary: Build · Overflow: Add another · Add transit · Pace this |

### Context-aware chip generation

Chips are computed by the **client** as a function of tray contents:

- **Event category → companion chips:**
  - Sports → "Pre-game bar nearby" / "Post-game spot"
  - Concert → "Dinner before" / "Late-night drinks"
  - Museum → "Coffee nearby" / "Another exhibit"
  - Theater → "Pre-show dinner" / "Drinks after"
  - Outdoor → "Rain backup" / "Lunch nearby"
  - Comedy → "Drinks before" / "Late dinner"
  - Family → "Kid-friendly nearby" / "Treat after"
  - Food event → "Drinks after" / "Coffee nearby" / "Music after"

- **Time-of-day → meal chips:**
  - ≤ 11 AM → "Breakfast/brunch nearby"
  - 11 AM – 3 PM → "Lunch nearby"
  - 5 – 8 PM → "Dinner before"
  - 8 – 11 PM → "Late-night spot after"
  - Multi-event day, no meal → "Add a meal"

- **Geography → proximity chips:**
  - Two events ≥ 2 mi apart → "Pick a spot in between"
  - Both same neighborhood → "Walk-friendly options"

- **Calendar → expansion chips:**
  - Single-day plan → "Add another day"
  - 2 consecutive days → "Add the next day too?"
  - Weekend booked, no Friday → "Friday night?"

- **Diversity signals:**
  - 3+ events all music → "Add some non-music"
  - All free → "Anything paid worth it?"
  - All daytime → "Evening plans?"
  - All evening → "Daytime activity?"

## Tool schema

Add four new structured-response tools alongside the existing `present_plan`:

- `present_suggestions` — primary browsing surface, returns ID-only event list
  with grouped headers
- `present_event_brief` — single event with one-line answer
- `present_venue_brief` — single venue + upcoming events
- `present_quick_fact` — one-line factual answer
- `present_scope_redirect` — out-of-scope decline with redirect chips

System prompt mandates: any event surfacing MUST go through one of these tools.
No paragraph-list event descriptions allowed.

## Implementation order (multi-day work, not for tonight)

1. Tool schemas in `ClaudeApiService.kt`
2. System prompt updates in `PlanChatViewModel.buildPlanSystemPrompt()` and
   `ChatViewModel.buildSystemPrompt()`
3. `SuggestionTray.kt` Compose component + chat parser glue
4. `DraftPlanTray.kt` + Build-from-draft handler
5. `DraftTrayChipGenerator.kt` — pure function `(List<DraftEvent>) -> List<DraftChip>`
6. `ScopeRedirect.kt` + scope guard
7. Action chips on prose (Solo/Couple/Group)
8. `EventBriefCard.kt` / `VenueBriefCard.kt` / `QuickFactPill.kt`
9. iOS bridge updates if any of these need native rendering
10. Telemetry: `tray_event_added`, `tray_event_dismissed`, `tray_built`,
    `chip_offered`, `chip_tapped`, `chip_to_build_conversion`

## Why this fixes the queued bugs

| Bug | How the tray fixes it |
|---|---|
| #1 Lalibela ramble | `present_scope_redirect` is the only allowed off-topic response. Web_search becomes scoped to event facts. |
| #2 Error after attendee count | "How many people?" becomes a chip choice, not typed text. |
| #3 Wrong-day plans | Plan slots come from explicit event_ids in the draft tray. |
| #4 Ignored Red Sox exclusion | Per-card "Not this" → adds to session-level exclusion set Bo passes back. |
| #5 Doesn't dig deep enough | Footer "Search wider" / "Different category" chips force `search_events` re-runs. |

## Status

- 2026-05-06: spec captured. Prompt-level fixes for #1, #3, #4, #5 shipped
  tonight (SCOPE / SEARCH DEPTH / EXCLUSIONS / DATE GROUNDING rules in both
  `PlanChatViewModel` and `ChatViewModel` system prompts; web_search bias toned
  down). Bug #2 fixed via empty-response retry guard in `sendWithToolLoop`.
- TODO: full UI implementation per the order above.
