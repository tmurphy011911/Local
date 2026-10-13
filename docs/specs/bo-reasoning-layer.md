# Feature Spec — Bo Reasoning Layer (intent → surface routing + output guard)

**Status:** Draft v0.3 (2026-10-13) · **Owner:** TM · **Surface:** client (KMP `commonMain`) + one proxied model call · **Backend break risk:** none (additive, flag-gated)
**Earliest realistic release:** v1.10.0 (Wed 2026-11-11), more likely v1.11.0 (11-25) — multi-phase, flag-gated so it merges to `develop` incrementally. *(Earlier v1.5.0/v1.6.0 targets lapsed during the Oct Supabase outage.)*

---

## 1. Problem

Bo frequently picks the wrong *action* for a request — answering in prose when the user wanted the event tray, missing that "what's at fenway?" is a single-venue ask, treating a refinement as a brand-new search. The root cause is architectural, not prompt-tuning:

**All routing today is a single Haiku 4.5 call choosing a terminal tool, steered by one ~300-line multi-purpose system prompt.** The model that has to persona, search, shape prose, respect the web budget, *and* honor the calendar rules is also the thing deciding the surface — buried in the "RESPONSE SHAPING" block. There is no dedicated intent step, no confidence signal, no deterministic routing for the common unambiguous cases beyond one hand-rolled regex.

## 2. Goal

A dedicated **reasoning layer** that (a) classifies each turn into an explicit **intent** (+ params + target surface) and deterministically routes it to the correct surface, and (b) **guards Bo's output** — rejecting/repairing a response that's wrong for the intent (e.g. a plan built from too little info) — reliably enough that the common cases stop misfiring, while never doing *worse* than today on the ambiguous tail.

## 3. Non-goals / scope boundaries (v1)

- **Multi-venue recommendations** ("best breweries near me") as a first-class surface — no such surface exists today; deferred to a fast-follow. *(Open decision — see §12.)*
- **Directions / navigation** — doesn't exist anywhere in Bo; not added here.
- **Save/bookmark & prefs** — these are client gestures (draft tray, prefs sheet), not Bo intents; unchanged.
- Server-side classification / moving Bo off the client-driven model. v1 stays client-side to match the existing architecture.

---

## 4. Current state (grounded)

### 4.1 Architecture
- `claude-proxy/index.ts` is a **thin auth+entitlement proxy** — verifies JWT, enforces `is_pro`/`trial_ends_at` (402 if unentitled), injects `ANTHROPIC_API_KEY`, forwards the client's body **verbatim** to Anthropic. It chooses nothing.
- The **client builds the entire Anthropic request.** `ChatViewModel.sendWithToolLoop` (`ChatViewModel.kt:2639`) runs a max-5-round tool loop. Tool schemas are built in `ClaudeApiService.claudeTools` (`ClaudeApiService.kt:210-653`).
- **Model:** Haiku 4.5 (`claude-haiku-4-5-20251001`) for every turn — `MODEL_CHAT`/`MODEL_PLAN` (`ClaudeApiService.kt:811,819`). Sonnet was swapped out for latency.
- **Streaming:** SSE (`sendRawStreaming`, `:1000-1216`); proxy emits a keep-alive so the 30s socket read doesn't trip. Text deltas suppressed during tool rounds; only a final pure-text reply streams.
- **History:** stateful per session — `conversationHistory` (`:643`), trimmed to last 20 (`trimHistory :6208`), full list sent each round. Cleared on reset.

### 4.2 Surfaces = terminal tools
| Surface | Terminal tool | Domain type |
|---|---|---|
| Event tray / map (one surface, toggles view) | `present_suggestions` | `Suggestions` |
| Single event brief | `present_event_brief` | `EventBrief` |
| Single venue brief (→ venue page; **can embed up to 6 event ids**) | `present_venue_brief` | `VenueBrief` |
| Quick fact pill | `present_quick_fact` | `QuickFact` |
| Out-of-scope redirect + chips | `present_scope_redirect` | `ScopeRedirect` |
| Multi-day plan card | `present_plan` / `update_plan` | (legacy `ParsedDay` path) |
| Plain prose | none (`type: chat`) | text bubble |

Non-terminal tools: `search_events`, `search_venues`, `get_event_details`, `recommend_and_save_venue`, `save_web_finding`, plus managed `web_search`/`web_fetch` (web-budget gated).

### 4.3 How routing happens today
1. **Model-decided inline** — the "RESPONSE SHAPING" block in `buildSystemPrompt` (`:5856-6160`) instructs which terminal tool to emit (e.g. `:6001` browsing→`present_suggestions`, `:6004` single-event→`present_event_brief`, `:6008` off-topic→`present_scope_redirect`; ABSOLUTE RULE 5 `:5955` "after a successful search, present — never ask").
2. **Client `tool_choice` forcing** — the only deterministic routing:
   - Browse fast-path: `inferBrowseSearchInput(text)` regex-classifies (`:2013,2599`), pre-runs `search_events`, forces `tool_choice:{tool:present_suggestions}`.
   - Round 2+ forces `tool_choice:{any}` so it can't fall back to prose.
3. **No** classifier, embeddings, routing table, or confidence signal.
4. Prior art: a pre-conversation `BoIntentPicker` ("Find events / Create a plan / I'm just browsing", `BoIntentPicker.kt`) — a UI hint, not a response router.

### 4.4 Known gaps this exposes
- **Modify presented events** — *no tool exists.* "drop the outdoor ones" can only be served by re-running a search and re-emitting a whole new tray.
- **Multi-venue recs** — no surface; only single venue via `present_venue_brief` or venues-as-plan-slots.
- **Modify plan** — *does* exist: `update_plan` (edits one day).

---

## 5. Proposed design

### 5.1 Overview — a tiered classifier feeding deterministic routing

Insert one step **before** the main tool loop, reusing the pre-flight phase Bo already runs each turn (token refresh, web-budget GET, browse-prime search). The classifier takes:

```
classify(message, recentHistory, currentSurfaceState) -> {
  intent,                 // enum, see §5.2
  target,                 // NEW_SURFACE | CURRENT_SURFACE
  params,                 // extracted slots (location, category, time_window, price, venue_ref, event_ref, plan delta, ...)
  secondaryIntent?,       // for compound asks
  confidence              // 0..1
}
```

**Three tiers, degrade gracefully:**
1. **Deterministic rules (0 latency, 0 cost)** — extend the existing `inferBrowseSearchInput` regex into a small rule set covering the top unambiguous patterns (browse events, single-venue "what's at/going on at X", off-topic, obvious modify-deltas like "only free"/"cheaper"/"drop the …"). Route with no model call.
2. **LLM classifier (1 small Haiku call)** — for everything the rules don't catch. A **tiny single-purpose prompt** (not the 300-line system prompt), cacheable, returning the struct above. This is where reliability comes from: one job, small prompt.
3. **Low-confidence fallback** — below a threshold, **do not force** routing; fall through to today's inline model-decides path. Worst case = current behavior. Core safety property.

Routing: on confident classification, drive `tool_choice` to the mapped terminal tool (generalizing the existing browse force-path to the whole taxonomy).

### 5.2 Intent taxonomy (final)

| Intent | Target | Routes to | Notes |
|---|---|---|---|
| `EVENTS_SEARCH` | NEW | `search_events` → `present_suggestions` | fresh query |
| `EVENTS_MODIFY` | CURRENT | re-filter or re-search current tray (§5.4) | **new capability** |
| `VENUE_SINGLE` | NEW | `present_venue_brief` (+ embedded events) | |
| `EVENT_SINGLE` | NEW | `present_event_brief` | |
| `QUICK_FACT` | NEW | `present_quick_fact` | |
| `INFO` | NEW | prose | "what's new in town" |
| `PLAN_BUILD` | NEW | `present_plan` | |
| `PLAN_MODIFY` | CURRENT | `update_plan` | already exists |
| `OUT_OF_SCOPE` | NEW | `present_scope_redirect` | |
| `CLARIFY` | — | ask one question | reserved for genuinely underspecified only |

Dropped from earlier drafts (not Bo intents): save/bookmark, directions, set-prefs. Deferred: multi-venue recs.

### 5.3 Surface-state contract (classifier input)

`EVENTS_MODIFY` / `PLAN_MODIFY` are meaningless without knowing what's on screen. The client already holds this — pass a compact snapshot:

```
currentSurface: {
  kind: NONE | SUGGESTIONS | VENUE | EVENT | PLAN,
  suggestions?: { params, items:[{id, title}] (ordered, as shown), facets: {categories[], hasFree, days[]} },  // ordered items needed so positional refs ("the first one", "more like the first") resolve — Phase-0 finding
  selectedIndex?,  // the currently-selected carousel item (§5.7)
  plan?: { title, days:[{label, slotCount}] }
}
```

Fresh-vs-modify tiebreak rule (write it down): **modify wins when the utterance is a delta** (only/cheaper/drop/sooner/more like/swap/move) *and* a compatible surface is open; **fresh search wins when it names a new subject.** Ambiguous → classifier confidence decides; low → fall back.

### 5.4 New capability — `EVENTS_MODIFY`

Two execution paths, chosen by the classifier's `operation`:
- **Local re-filter (no model, no network, sub-100ms):** when the delta is expressible over fields the tray already carries — `is_free`, `category`/`subcategory`, `iso_date`/day, price, sort-by-soonest. Re-filter/re-sort the in-memory `Suggestions` list and re-emit `present_suggestions`. *This fixes latency, not just correctness.*
- **Re-search + merge:** when the delta needs new data — "more results", "wider area", "different category", "next weekend instead". Merge carried params with the delta, re-run `search_events`, re-present.

Either path **resets carousel selection to the new item 1 and recenters the map** (see §5.7).

**Data dependency:** "indoor/outdoor" filtering needs a field on events. If not present, that specific delta falls to re-search-with-keyword. Verify what `search_events` returns exposes (id,title,venue_name,venue_city,date,iso_date,time,category,subcategory,is_free,price — no explicit indoor/outdoor).

### 5.5 Output guard — reject / repair a wrong response

The layer isn't only pre-flight routing; it doubles as a **guard on Bo's output**, with two checkpoints. The pre-flight intent (§5.1) is reused as the oracle the output is judged against.

**(a) Precondition gate (pre-build).** Before Bo builds an expensive/committal surface, check the intent's minimum viable inputs; if unmet → route to `CLARIFY` (or a gather step) instead of letting Bo build a thin/hallucinated result:
- `PLAN_BUILD`: a location (context or explicit) **+ ≥1 of** {day/date, vibe/category, seed events}. None → "what day, and what are you in the mood for?" *(the "plan x with too little info" case)*.
- `VENUE_SINGLE` / `EVENT_SINGLE`: a resolvable ref.
- `EVENTS_MODIFY` / `PLAN_MODIFY`: a matching open surface (per §5.3).
- `EVENTS_SEARCH`: usually satisfiable via defaults; rarely gated.

**(b) Output validator (post-build, pre-render).** After Bo emits a terminal tool but before the surface renders. Mostly **deterministic, client-side, free**:
- **Intent/surface agreement** — emitted surface must match the pre-flight intent (classifier `EVENTS_SEARCH` but Bo emitted `present_scope_redirect` → reject).
- `present_plan`: ≥1 slot; every slot id resolves to a real DB event / validated venue / staged bo-web id (no hallucinated slots); times monotonic.
- `present_suggestions`: ≥1 event; all ids resolve.
- `present_venue_brief`: venue resolves; embedded event ids resolve (guards the eventless-venue-brief bug).
- **LLM judge**: reserved for semantic checks rules can't express ("does this plan actually answer the request?") — sparing, only when a rule is ambiguous.

**On rejection, resolve in order:** (1) **repair/retry** — inject a corrective message into the existing tool loop, bounded to **1 retry** (reuse the loop's 5-round cap / truncated-stream single-retry machinery); (2) **downgrade** — plan→clarify, or plan→events tray to gather picks; (3) **fall through (accept)** — if the *validator* is low-confidence, don't block. Same safety principle as the router: never worse than today.

Ships behind its **own** flag `FEATURE_BO_GUARD_ENABLED`, A/B'd independently of the router — an over-eager guard degrades UX (retry loops) faster than a missing one.

### 5.6 Compound & ambiguity
- **Compound:** classifier returns `secondaryIntent`. Router prefers a surface that can *embed* the secondary — e.g. "what's at fenway this weekend" → `VENUE_SINGLE` primary with events embedded via `present_venue_brief.upcomingEventIds` (already supported). Otherwise primary only.
- **Follow-up:** not a separate intent — a follow-up on events *is* `EVENTS_MODIFY` with carried params; on a plan, `PLAN_MODIFY`.
- **Clarify:** only when a required slot is missing and can't be defaulted. Prefer presenting over asking (keeps ABSOLUTE RULE 5's spirit).

### 5.7 Events surface interaction model

Events (`present_suggestions`) lead with the **map + synced carousel as the default** presentation; the list is demoted to an optional toggle (not the entry view).
- Opens with **item 1 selected** — its pin active, map centered on its venue.
- Carousel swipe is **per-event**: advances selection to the next event; the **map pans only when the venue changes** (consecutive events at the same venue keep the pin highlighted, no camera move). *(Decision: per-event swipe over per-venue.)*
- Selection ↔ map are two-way synced (tap a pin → selects its event); `venuePins()` grouping stays as the pin model.
- After an `EVENTS_MODIFY` re-filter/re-search, selection **resets to the new item 1** and the map recenters on it. *(Decision: reset-to-1, not keep-selection.)*

Touches both platforms: Android `BoResultsMap` (`ChatScreen.kt:3061-3082`), iOS `BoResultsMapView` MapKit (`ChatView.swift:2322-2470`), pin grouping `venuePins()` (`platform/BoResultsMapView.kt:28`). This is an **interaction/UI change on an existing surface** — no new tool, no backend.

---

## 6. What needs to be implemented (delta)

| # | Work | Where |
|---|---|---|
| 1 | Intent enum + classifier output model | new `commonMain` model |
| 2 | Tier-1 deterministic rules (extend `inferBrowseSearchInput`) | `ChatViewModel.kt` (near `:2013,2599`) |
| 3 | Tier-2 classifier call + tiny prompt (proxied Haiku, cacheable) | `ClaudeApiService` + new prompt in `data/bo/` |
| 4 | Surface-state snapshot builder from live `Suggestions`/plan | `ChatViewModel` + `BoStructuredResponse.kt` |
| 5 | Router: intent → `tool_choice` force (generalize browse path) | `sendWithToolLoop` (`:2639`, force sites `:2772-2779`) |
| 6 | `EVENTS_MODIFY` local re-filter + re-search paths | `ChatViewModel` + `Suggestions` handling |
| 7 | Confidence gate → fall back to today's inline path | `sendWithToolLoop` |
| 8 | Feature flag `FEATURE_BO_REASONING_ENABLED` | config + PostHog flag (§13.4) |
| 9 | Trim/relocate now-redundant routing rules from the big system prompt | `buildSystemPrompt` "RESPONSE SHAPING" |
| 10 | Offline eval harness + labeled utterance set | new test asset |
| 11 | PostHog instrumentation (§10) | `AnalyticsEvent.kt` |
| 12 | Precondition gate — per-intent min-input rules (§5.5a) | new `commonMain` guard module |
| 13 | Output validators — intent/surface agreement + per-surface payload/ref checks (§5.5b) | guard module + `toStructuredResponse` (`:4971`) |
| 14 | Rejection resolver — bounded 1-retry via existing tool loop → downgrade → accept | `sendWithToolLoop` (retry sites `:2796,:2989-2995`) |
| 15 | Guard flag `FEATURE_BO_GUARD_ENABLED` (separate from router flag) | config + PostHog flag |
| 16 | (deferred) LLM judge for semantic-only checks | guard module |
| 17 | Events surface: map+carousel as default, per-event swipe w/ venue-change pan, two-way selection sync, reset-to-1 on modify (§5.7) | `ChatScreen.kt:3061`, `ChatView.swift:2322-2470`, `venuePins()` |
| 18 | Shadow-mode plumbing — compute+log classifier/guard decisions without acting (§13.2) | `sendWithToolLoop` + `AnalyticsEvent.kt` |
| 19 | Routing instrumentation — capture chosen surface/intent (+ optionally message text, privacy-gated) on `bo_message_sent`/`bo_response_received`; today neither is logged | `AnalyticsEvent.kt:201,207`, capture sites `ChatViewModel.kt:903,2063` |

**No backend/DB change required.** `claude-proxy` is untouched (the classifier is just another proxied Anthropic call under the existing entitlement check). No migration. So the live-app additive/back-compat invariant is trivially satisfied.

---

## 7. Latency & cost
- Adds at most **one small Haiku call** per turn, and **zero** for tier-1 rule hits and local re-filters. Tiny cacheable prompt keeps it fast; it slots into the existing pre-flight sequence.
- Net latency may *drop* for `EVENTS_MODIFY` (local re-filter replaces a full search+present round) and for rule-routed browse.
- **Cost climate (2026-10) — now a gating concern, not a footnote.** The org just hit a Supabase spend cap (total egress outage 10-11→10-13) and has paused scheduled Anthropic jobs ([PR #27](project_anthropic_scheduled_pause_2026_09_10.md)) + monthly discovery ([til 2027](project_monthly_discovery_cost_pause_2026_08_02.md)) to cut spend. A per-turn classifier call × all Pro users is a real multiplier in that climate. Mitigations are mandatory, not optional: maximize tier-1 rule hits (free), **sample shadow mode aggressively** (§13.2), and the Phase-0 cost estimate (§13.5) must clear before any rollout.

## 8. Feature flag & rollout
- Gate behind `FEATURE_BO_REASONING_ENABLED` + `FEATURE_BO_GUARD_ENABLED` (fail safe to today's inline routing), mirroring the [PTR flag pattern](project_bo_pull_to_refresh_2026_07_15.md). Full rollout mechanics in §13.

## 9. Backward-compat & release
- Purely additive client change; no schema, no edge-fn shape change. Safe for the bi-weekly train with multiple live versions.
- Merge to `develop` incrementally behind the flag; flip on for a release once eval + dogfood pass. Target **v1.10.0 (2026-11-11) earliest, likely v1.11.0 (11-25)** — multi-phase build; the next train (v1.8.0, 10-14) and v1.9.0 (10-28) are too soon.

## 10. Testing, eval & metrics
- **Offline eval (Phase 0, do first):** a labeled dataset of utterances → expected intent+target. **⚠️ Data reality (grounded):** Bo captures **no message text and no routing decision** today — `boMessageSent` logs only `messageLength`+`hasAttachedEvent` (`AnalyticsEvent.kt:201`); `boResponseReceived` logs only `responseMs/hadWebSearch/hadChips/resultEventCount` (`:207`); `claude-proxy` doesn't persist; history is client-memory only. **There are no transcripts to mine.** So the set is built one of two ways (open decision 9): (a) **hand-seed** ~200–300 representative utterances (seed in `docs/specs/bo-reasoning-eval-seed.md`), or (b) **ship capture first** — instrument message text (or a privacy-safe derivative) + the chosen surface, collect a window, then label. CI-runnable accuracy/confusion matrix either way. Single most important artifact — how we know the layer is actually better.
- **Maestro flows** for each routed intent (scaffold via `/scaffold-tests`), incl. modify-events refine, the swipe/map sync, and the fresh-vs-modify tiebreak.
- **PostHog:** per-intent volume, classifier confidence distribution, fallback rate, `EVENTS_MODIFY` local-vs-research ratio, ask/clarify rate, and a **mis-route proxy** (user immediately rephrases / abandons). Dashboard 1667086 / `AnalyticsEvent.kt`.

## 11. Phasing / milestones
- **Phase 0 — Eval + shadow (GO/NO-GO GATE):** taxonomy enum + labeled dataset (hand-seeded, §10) + offline harness, **and** prod shadow mode (§13.2). No behavior change. **Pass criteria — see §13.5.** If the dedicated classifier doesn't beat inline routing on the offline set, stop: the problem is model-capability, not classification.
- **Phase 1 — Classifier + routing (flag off in prod):** tiers 1–3, force-route the safe unambiguous intents, confidence fallback. Ship dark.
- **Phase 2 — `EVENTS_MODIFY` + events interaction (§5.7):** local re-filter + re-search **and** the map-synced carousel — coupled, ship together.
- **Phase 3 — Output guard (`FEATURE_BO_GUARD_ENABLED`):** precondition gate (3a, higher-value/lower-risk) + deterministic output validators (3b) + bounded rejection resolver. Rules-only; no LLM judge. *If time-boxed, ship 3a, treat 3b as stretch.*
- **Phase 4 — Compound + clarify + tuning:** embed-secondary routing, confidence tuning, expand forced intents; **trim redundant prompt rules** (required for correctness, not cleanup).
- **Phase 5 (deferred) — multi-venue recs surface** and **LLM semantic judge**, if we decide they're in.

## 12. Decisions (locked 2026-10-13 — implementing as written)
All resolved to the recommended default:
1. **Multi-venue recs** — **DEFERRED** to a fast-follow (Phase 5).
2. **Modify-events mechanism** — **both** local re-filter and re-search, chosen per-delta (§5.4).
3. **Mechanism** — **dedicated tiered classifier** (§5.1).
4. **Release target** — **v1.10.0 (11-11) earliest, v1.11.0 (11-25) likely.**
5. **Output guard scope** — **in v1**, Phase 3 (3a precondition gate first, 3b validators stretch).
6. **LLM judge** — **rules-only** for v1; semantic judge deferred (Phase 5).
7. **Flag mechanism** — **PostHog flags** for gradual/cohort rollout + `app_config` as global kill switch. *(Verify `app_config` cohort/% support during Phase 1; falls back to PostHog-only if not.)*
8. **Events list view** — **keep as an optional toggle** (map is default entry view).
9. **Eval data source** — **hand-seed** now (`bo-reasoning-eval-seed.md`) to unblock Phase 0; add privacy-gated message-text capture in parallel to grow a real set.

---

## 13. Rollout & production testing (no live-user impact)

**Reframe:** Bo can't be meaningfully tested on the dev backend — dev lacks the Anthropic key, JWT secret, and real event data. Bo needs prod data + prod backend to behave realistically. So "test in prod safely" means **ship the new code into the prod binary but keep it dormant behind flags, and control exposure precisely.** This works because the change is 100% client-side and flag-gated; `claude-proxy` doesn't change.

### 13.1 Offline eval — 0 users
Classifier + guard run against the labeled set (§10). Correctness proven before any exposure. This is the Phase-0 gate. **Cold-start caveat:** no historical transcripts exist (§10 ⚠️), so the first set is hand-seeded; the richer real-distribution set comes from shadow mode (§13.2) once shipped — labeling requires captured user text (privacy decision 9).

### 13.2 Prod shadow mode — 0 user *impact* (the core technique)
Run the classifier + guard on **real prod traffic**, log what they *would* do via PostHog, but **do not change what the user sees.** Record "classifier would route→X, Bo did→Y" and "guard would have rejected this plan."
- Real-traffic, real-data accuracy measurement with zero user impact.
- **Builds the baseline mis-route metric we lack today.**
- **Sample aggressively (≤10% of turns)** — shadow still costs a real Haiku call though users see nothing (see §7 cost climate).
- Limit: gives would-reject *rate* + latency/cost, **not** correctness — correctness comes from §13.1.

### 13.3 Staff / dogfood cohort — blast radius = us
Flip the *acting* flags on for team accounts on prod. Real backend, real acting behavior, tiny blast radius. **The only way to validate the §5.7 map/carousel interaction** — a UI change can't be shadowed. Also where UX risks (over-asking, repair-retry latency) surface before real users.

### 13.4 TestFlight/Play internal → staged % A/B
Flags on for internal tracks, then a real percentage rollout on prod Pro users: **1% → 5% → 25% → 50% → 100%**, with a held-back **control** on today's behavior. Compare treatment vs. control: mis-route proxy, ask/clarify rate, latency p95, abandonment, crash/Sentry volume, **per-turn Anthropic cost**. Each sub-flag (`router`, `guard`, `interaction`) rolls out independently.
- **Mechanism:** PostHog (proj 433802) does flags + % rollout + experiments → use it for the rollout layer; keep `app_config` as the global **kill switch**. *(Open decision 7.)*

### 13.5 Go/no-go pass criteria (Phase 0 → Phase 1)
Proceed only if, on the offline set + shadow data:
- Dedicated classifier accuracy **materially beats** inline-routing baseline (set the bar when the baseline is measured).
- Shadow "would-reject" rate on good responses (false-reject proxy) is low enough that the guard won't loop users.
- Projected added latency (p95) **and per-turn cost** within budget (cost now explicitly gating — §7).
If not met: the problem is model-capability, not classification — revisit prompt/tool-descriptions/model choice instead of building Phases 1–3.

**Phase-0 results (2026-10-13), seed = 56 hand-labeled rows, Haiku 4.5:**
- **Classifier intent accuracy: 96.4%** (54/56) after dropping `target` (derived from intent) and sharpening the sports / info-vs-events / quick-fact-vs-venue boundaries. Confidence well-calibrated: **correct 0.90 / wrong 0.45** — the 2 misses are low-confidence, so the §5.1 fall-through catches them.
- **Inline-routing baseline: 33.9%** (19/56) — **not trustworthy as-is.** The offline harness collapses today's multi-turn ask-first + search-loop flow into one call and buckets prose/clarify/scope-redirects as `info`, so it *under-measures* real routing. Treat the gap as **directional, not quantitative** until a backend-in-the-loop baseline exists.
- **Fair, real wins:** `events_modify` 0/11 today (no such surface — structural gap the new capability fills); one-shot commitment vs. ask-then-serve round-trips (a UX win, but in tension with the ask-first philosophy, §14.1).
- **Insight → spec change:** positional refs ("the first one", "more like the first") can't resolve unless the surface-state snapshot (§5.3) carries the **actual item list**, not just facets. Folded into §5.3.
- **Verdict:** GO to build Phase 1 on the classifier strength; baseline needs a rematch with the real app in the loop before any delta is quoted; grow the eval set from shadow traffic (small, self-labeled set today).

### 13.6 Kill switch & rollback
Flags **fail safe** (off = today's behavior) and the layer's **low-confidence fall-through** means even "on," a shaky decision defers to the current path. **Pre-commit rollback thresholds** (latency p95, ask-rate, crash rate, Bo Sentry volume, cost/turn) so pulling a flag is a trigger, not a debate — you **can't hot-patch a client binary**, so the flag is the only fast lever. §13.1–13.2 must catch most issues before §13.4 exposure.

---

## 14. Risks (ranked by consequence)

1. **The guard fights Bo's "present, never ask" design — top product risk.** ABSOLUTE RULE 5 deliberately makes Bo act over interrogate; the precondition gate reintroduces the very questions the team removed. Wrong threshold → Bo feels bureaucratic even if accuracy rises. → *Ask only when it truly can't default; ask-rate is a guardrail metric.*
2. **Classifier-as-oracle compounds errors.** The guard validates output against the classifier's intent; a wrong classification makes the guard *enforce* the wrong answer. And it's Haiku classifying — the same model whose weakness causes today's mis-routes. → *Phase-0 gate (§13.5) must prove the classifier beats inline routing first.*
3. **Latency on the critical path.** Bo already fought a 30s timeout; the classifier adds a hop before streaming, and a guard repair-retry doubles the turn on exactly the turns already going wrong. → *Lean on tier-1 rules + local re-filter (0 hops); cap repair at 1; measure p95.*
4. **Can't prove the win.** No mis-route baseline exists; the proxy metric is noisy; a hand-built eval reflects our assumptions. → *Establish the baseline in shadow first.*
5. **Three routers that must agree** (tier-1 rules, classifier prompt, system-prompt RESPONSE-SHAPING) across two viewmodels and two platforms → drift bugs in the flagship feature. → *Phase 4 prompt-trim is required for correctness; surface-state across the KMP/native boundary is real plumbing.*
6. **Validator catches structure, not meaning.** A real-but-irrelevant event, or a hallucinated venue already staged into bo-web, passes the "resolves" check. → *Scope the claim honestly: rules-only guard prevents broken surfaces, not incorrect ones.*
7. **Cost multiplier — elevated in the current spend climate.** Extra classifier call × all Pro users × every message, right after an org spend-cap outage and active AI-cost cuts (§7). → *Maximize rule hits; sample shadow; cost is a §13.5 gate.*
8. **Cold-start: no ground-truth data exists.** Bo logs neither message text nor routing decision (§10 ⚠️), nothing persists transcripts — so the go/no-go eval can't run on real history. → *Hand-seed to unblock the gate; grow a real set from shadow/capture (decision 9).*

**Meta-risk:** the whole design assumes mis-routing is a *classification* problem a Haiku classifier solves. If it's a *model-capability* problem, the layer adds latency/cost/complexity without fixing the root cause — which is exactly what the Phase-0 go/no-go gate (§13.5) exists to catch cheaply.

---

## 15. Concrete schemas & rules (build detail)

### 15.1 Classifier — implemented as a forced-tool call
The classifier is **one Anthropic call with a single tool forced** (`tool_choice:{type:"tool", name:"classify"}`), so output is schema-validated by the API rather than parsed from prose — the same structured-output mechanism Bo already uses. Tiny cacheable system prompt (persona-free: one job).

```jsonc
// tool: classify — input_schema (== the classifier's output)
{
  "intent": "EVENTS_SEARCH|EVENTS_MODIFY|VENUE_SINGLE|EVENT_SINGLE|QUICK_FACT|INFO|PLAN_BUILD|PLAN_MODIFY|OUT_OF_SCOPE|CLARIFY", // required
  "target": "NEW|CURRENT",           // required
  "confidence": 0.0,                  // required, 0..1 — router's fall-through gate reads this
  "params": {
    "location": "string?", "strict_location": true,
    "category": "string?", "keyword": "string?",
    "time_window": "today|tonight|this_weekend|next_weekend|this_week|next_week|this_month|null",
    "start_date": "YYYY-MM-DD?", "end_date": "YYYY-MM-DD?",
    "is_free": "bool?", "price_ceiling": "string?",
    "venue_ref": "string?",           // VENUE_SINGLE
    "event_ref": "string?",           // EVENT_SINGLE / QUICK_FACT
    "occasion": "string?"             // "date night", "with kids" → feeds PLAN_BUILD vibe
  },
  "modify": {                          // present iff intent ∈ {EVENTS_MODIFY, PLAN_MODIFY}
    "operation": "FILTER|EXPAND|SORT|RESET|ADD|REMOVE|RETIME|SWAP",
    "delta": { /* field-level changes, same keys as params */ },
    "execution_hint": "LOCAL|RESEARCH" // classifier guess; router overrides if fields unavailable (§5.4)
  },
  "secondary_intent": "…|null",        // compound (§5.6)
  "rationale": "string"                // ≤120 chars, logged for eval/shadow; never shown to user
}
```
Router: if `confidence < THRESHOLD` → **do not force**, fall through to today's inline path (§5.1 tier 3). Else map `intent`→terminal tool and force `tool_choice`.

### 15.2 Guard — result schema
Precondition (§5.5a) is **pure deterministic** (no LLM). Output validation (§5.5b) is deterministic checks, with the LLM judge (deferred) merged only for semantic verdicts.

```jsonc
{
  "checkpoint": "PRECONDITION|OUTPUT_VALIDATION",
  "verdict": "PASS|REJECT|ACCEPT_LOW_CONFIDENCE",
  "reasons": [ { "code": "MISSING_SLOT|INTENT_SURFACE_MISMATCH|UNRESOLVED_REF|EMPTY_PAYLOAD|NON_MONOTONIC_TIMES|HALLUCINATED_SLOT",
                 "detail": "string" } ],
  "resolution": "RETRY|DOWNGRADE|CLARIFY|ACCEPT",   // §5.5 ladder
  "corrective_message": "string?",   // injected into the tool loop on RETRY (bounded to 1)
  "downgrade_target": "intent/surface?",
  "confidence": 0.0                  // guard's own; low → ACCEPT_LOW_CONFIDENCE (never block)
}
```

### 15.3 `PLAN_BUILD` precondition decision rules
Plan building is the most expensive/committal surface (multi-round, web tools, DB saves), so its bar is higher than events search. Signals:
- `hasSeeds` = current surface is SUGGESTIONS with selected/tray events, **or** draft tray non-empty
- `hasLocation` = `params.location` set **or** device location known (usually true)
- `hasWhen` = `params.time_window` or `params.start_date` set
- `hasVibe` = `params.category` / `params.keyword` / `params.occasion` set

**Decision (first match wins):**
1. `hasSeeds` → **BUILD** (user already has picks; enough to assemble).
2. `hasLocation && (hasWhen || hasVibe)` → **BUILD**.
3. `hasLocation && !hasWhen && !hasVibe` → **CLARIFY** — one combined question, don't interrogate.
4. `!hasLocation` (rare) → **CLARIFY** location first.

Worked cases: "plan my saturday" → 2 (when+location) → BUILD · "plan a date night" → 2 (occasion=vibe) → BUILD · "build with these" (tray) → 1 → BUILD · "plan something" → 3 → CLARIFY. Only the genuinely empty case asks — keeps the ABSOLUTE RULE 5 tension in check (risk §14.1).

**CLARIFY templates (one question, combine missing slots, insider tone):**
- missing when+vibe → *"What day are you thinking, and what's the vibe — chill, big night out, something with the kids?"*
- missing when only → *"What day should I build around?"*
- missing vibe only → *"What are you in the mood for?"*

### 15.4 Output-validation rules (per surface)
| Surface | Deterministic checks | On fail |
|---|---|---|
| *any* | emitted surface == classifier intent (unless secondary embeds it, §5.6) | REJECT→RETRY, else DOWNGRADE |
| `present_plan`/`update_plan` | ≥1 slot; each slot id ∈ {DB event, validated venue, staged bo-web id}; times monotonic within a day | REJECT→RETRY once → DOWNGRADE to CLARIFY |
| `present_suggestions` | ≥1 event; all `event_id` resolve | REJECT→RETRY (re-search) |
| `present_venue_brief` | venue resolves; embedded `upcomingEventIds` resolve | strip bad ids; REJECT only if venue itself unresolved |
| `present_event_brief`/`present_quick_fact` | `event_id` resolves; `answerLine`/`text` non-empty & within length | REJECT→RETRY |
| `present_scope_redirect` | classifier intent **was** OUT_OF_SCOPE (guards false-positive redirects) | REJECT→RETRY without scope tool |

All checks that can't be evaluated (missing data to verify against) → **PASS** (never block on inability to check — §5.5 fall-through).
