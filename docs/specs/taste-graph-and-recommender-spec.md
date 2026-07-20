# Taste Graph & Personalized Recommender — Spec

> Status: **DRAFT for review** · Owner: Tom · Last updated: 2026-07-16
> Scope: cross-cutting (classifier · data model · edge functions · client · new B2B product)

---

## 0. TL;DR

Build a **single taste model per user** that learns from behavior we already collect, and route it into every surface: For You carousels, Explore Events/Places default order, and Bo. The same model, run in reverse (score *users* for a given *event*), becomes a **B2B propensity product** ("X users are 90% likely to be interested in your event").

The hard part is not the ML — it's that **events are ephemeral** (a concert happens once, most events have zero interactions), so "users who clicked X also like Y" can't operate on event IDs. It has to operate on **durable features** of events — subcategory, an expanded **tag vocabulary**, and extracted **entities** (artists, venues, brands). Requirement 1 (the 10+ tag system) is therefore not a side quest; it's the **substrate** the whole taste graph runs on.

Four things to build, in order:
1. **Rich event features** — expand tags 5 → 12–15 + extract entities (Req 1).
2. **A taste service** — one scorer that turns (user, event) → affinity, consumed everywhere (Reqs 4/5/6).
3. **Surfaces** — taste carousels, feed ordering, Bo personalization (Reqs 4/5/6).
4. **B2B propensity** — invert the scorer, aggregate, sell (Req 3).

---

## 1. Goals & non-goals

### Goals
- Surface events a user is likely to love, **including ones they never expressed interest in** (transitive discovery: Shane Gillis → baseball).
- One taste representation, consumed consistently by carousels, Explore, and Bo (today these use three divergent scorers — `coldStartScore` in `carousels/index.ts`, `scoreEvent` in `explore-events`, `scoreVenue` in `explore-places`).
- A per-user, human-readable **top-5 interests** projection for CRM/decisioning (Req 7).
- A B2B propensity API/report: calibrated counts of interested users per event, thresholded (90/75/50%), thresholds configurable (Req 3).
- Everything automated and cheap (Haiku + batch, precomputed vectors, no per-request LLM in hot paths).

### Non-goals (v1)
- A literal graph database. "Taste graph" is a mental model; the math is vector similarity + feature co-occurrence, all doable in Postgres + pgvector.
- Real-time (<1s) model updates. Nightly batch + lightweight incremental is fine.
- Replacing proximity/recency as ranking inputs — taste is *added*, not *substituted*. A 5-star-taste event 90 miles away should not beat a good one nearby.
- Perfect calibration of the B2B percentages on day one — the % is a knob, explicitly changeable later.

---

## 2. Mental model — where the transitive magic comes from

Your examples are all **feature-grain**, not event-grain:
- "users who click Shane Gillis like Bill Burr" → **entity → entity** affinity (`performer:shane_gillis` ↔ `performer:bill_burr`)
- "users who like Shane Gillis like baseball" → **entity → subcategory** affinity
- "users who like for-kids like museums" → **subcategory → subcategory** affinity

So the taste graph is a set of learned affinities **between durable features**, plus a **per-user position** in that feature space. A brand-new event with zero interactions still gets recommended because we know its *features*, and we know the user likes those features (or features close to them).

Two complementary mechanisms:

1. **Content match (cold-start-safe).** Each event → a feature vector (subcategory, tags, entities, price band, time-of-day, vibe). Each user → a taste vector (weighted average of features from events they engaged with). Score = similarity. This works on day-one events with no interactions — critical, since that's most events.

2. **Collaborative lift (the "didn't express interest" part).** Learn feature↔feature affinity from co-occurrence across the whole user base (people who engage `comedy` also engage `sports`). Expand the user's taste vector along these learned edges before scoring. This is what recommends baseball to the Shane Gillis fan.

v1 can ship mechanism (1) alone and still be a big win; (2) is what makes it feel magical.

---

## 3. Component A — Event features: expanded tags + entity extraction (Req 1)

### 3.1 What exists today
- `events.tags TEXT[]` — capped at **5**, drawn from a fixed ~90-value controlled vocab (`VALID_TAGS` in `scripts/classify_events.js`), plus `category` / `subcategory` (~13) / `sub_subcategory` (~120) / `team_name`.
- Classified weekly by `scripts/classify_events.js` using `claude-haiku-4-5-20251001` via the **Message Batches API** (cheap, async). Rule-based pre-pass handles obvious cases before spending tokens.

### 3.2 Your guardrails question — recommendation: **controlled vocab + separate entity extraction. Do NOT go free-form.**

Your two examples actually contain **three different kinds of tag**, and they need different homes:

| Kind | Your examples | Cardinality | Home | Why |
|---|---|---|---|---|
| **Descriptor / vibe / format / audience** | `art`, `studio`, `meditation`, `small`, `niche`, `group`, `paid`, `live`, `concert` | Low (~150–250) | **Controlled vocab** in `events.tags` | These must *generalize* — "small" and "intimate" and "cozy" have to collapse to one token or collaborative filtering fragments into noise. |
| **Entity** | `matt_maeson`, `tree_house`, `shane_gillis` | Very high (thousands) | **Separate structured columns/tables** | High-cardinality, needs canonicalization/dedup, drives entity↔entity affinity. Never mix into the vibe vocab. |
| **Already structured** | `brewery` (venue type), `music` (subcategory) | — | Existing `subcategory`/venue metadata | Don't re-encode what you already have. |

**Guardrail decision:** free-form tags are the classic recommender-killer — the model will emit "family friendly", "family-friendly", "kid friendly", "good for kids" as four tokens and your co-occurrence matrix turns to mush. **Use a controlled vocabulary the LLM must map into**, and grow the vocabulary through a governed process (below), not ad hoc per event.

### 3.3 The plan
1. **Expand the controlled vibe vocab** from ~90 to ~150–250 tokens, organized into **facets** so the model fills each facet deliberately:
   - `audience` (family-friendly, 21+, date-night, solo-friendly, group, kids)
   - `format` (standup, live-music, class/workshop, festival, market, screening, guided)
   - `vibe` (intimate, high-energy, chill, artsy, rowdy, meditative, upscale, dive)
   - `setting` (indoor, outdoor, studio, brewery-taproom, park, gallery, arena)
   - `commitment` (free, paid, ticketed, drop-in, RSVP)
   - `scale` (niche, small, big-crowd, marquee)
   - `interest` (art, music, food-drink, comedy, sports, learning, wellness, nightlife…)
2. **Raise the cap** from 5 to **12–15** (target ~10+ as you asked), allocated across facets (e.g. ≥1 audience, ≥1 vibe, up to N interest).
3. **Extract entities** into structured fields in the same Haiku pass:
   - `performers TEXT[]` (canonicalized slugs: `shane_gillis`, `matt_maeson`)
   - reuse existing `team_name` for sports
   - `brands TEXT[]` (festival/series/promoter names)
   - venue is already structured — link, don't re-tag.
   - Feed these into a canonical **entities** table (`entity_id`, `type`, `canonical_name`, `aliases[]`) so "Boston Breakers"/"Boston Legacy FC" collapse — the same stale-name problem `team_name` already has (noted in CLAUDE.md TODO).
4. **Implementation:** extend `buildPrompt`/output schema in `scripts/classify_events.js` (add `tags` breadth + `performers`/`brands`); keep Haiku + Batches API. Backfill via the existing `FORCE=1` path. New events flow through the same weekly classify step, plus consider tagging **on ingest** for freshness (Bo/For You want same-day events tagged).
5. **Vocabulary governance:** vocab lives in one versioned file; adding a token is a reviewed change; run a monthly "unmapped descriptor" report (what the model *wanted* to say but couldn't) to grow it deliberately. Version-stamp events with the vocab version they were tagged under.

### 3.4 Open question
- Are tags **user-visible** (shown on the event card) or **machine-only** features? Recommendation: a curated subset is user-visible; the full set is machine features. Affects how "clean" they must read.

---

## 4. Component B — Signal collection (the biggest gap)

The recommender is only as good as its signals. Today you have **only positive signals** and no notion of **what was shown but ignored**.

### 4.1 What exists
- **`community_interactions`** (the real store the rankers read): `interaction_type ∈ {click, favorite, share, plan_add}` with weights **1 / 2 / 4 / 6**, carrying `item_id`, `item_type`, `category`, `user_id`. → `get_community_scores` / `get_category_affinity` → `affinityMap`.
- **`user_taste_profiles`**: per-user jsonb count maps (`category_scores`, `venue_type_scores`, `neighborhood_scores`, `time_preferences`, `price_comfort`), written by `TasteRepositoryImpl`, capped at 20/token. Already feeds Bo's "LEARNED TASTE" block.
- **PostHog** (`AnalyticsEvent.kt`): event-centric analytics.

### 4.2 Gaps to close (recommended, roughly in priority order)
1. **Impressions / negative signals.** We log clicks but not what was *shown and skipped*. Without impressions you can't tell "didn't like" from "never saw," and every recommender quality metric (CTR, calibration) needs the denominator. **Add lightweight impression logging** (carousel/feed items that entered the viewport) — sampled, batched, cheap. This is the single highest-leverage add.
2. **`venue_id` + `subcategory` + `tags` on interaction events.** Recon found PostHog taps carry `event_id` but **not** `venue_id`/`category`. Enrich `community_interactions` (or a companion table) so a click records the event's *features*, not just its id — otherwise every consumer re-joins to `events` (and ephemeral events get deleted).
3. **Dwell / depth signals.** `event_viewed` fires on open; add dwell time or scroll depth as a soft-positive.
4. **Explicit "not interested" / hide control.** A first-class negative that both improves the feed and gives the model gold-standard negatives.
5. **Snapshot event features at interaction time.** Because events get deactivated/deleted, store the feature vector alongside the interaction so training data survives the event.
6. **Capture the user's active set location at interaction time.** Store the lat/lng the user had active (live GPS or manually-set location) *on the interaction record*. This is required for travel-propensity (§5.4) — distance is measured from where the user was anchored *then*, not from home, and the set location is mutable so it can't be recovered after the fact. Cheap to add (one lat/lng pair per interaction) and unlocks a whole taste dimension.

### 4.3 Note
Keep the two lanes distinct: `community_interactions` = **the recommender's feedback store** (source of truth for taste); PostHog = **product analytics + experiment readout**. Don't try to make PostHog the model's data source.

---

## 5. Component C — The user taste model + top-5 interests (Req 7)

### 5.1 Representation
Per user, store **both** a machine vector and a human projection:

- **Dense taste vector** — the user's position in feature space. Two viable encodings (pick per phase, see §11):
  - *v1 (interpretable, ships fast):* a sparse weighted map over features (subcategory, tag, entity, venue-type, price band, time) — an evolution of today's `user_taste_profiles` + `affinityMap`, but over the **richer** feature set from Component A. Decay old interactions; weight by interaction type (1/2/4/6) and recency.
  - *v2 (more powerful):* a dense embedding (e.g. ALS/implicit matrix factorization over the user×feature matrix, or text-embedding the events a user liked and averaging). Store in **pgvector** for fast nearest-neighbor.
- **Collaborative expansion:** apply learned feature↔feature affinity (a precomputed co-occurrence/PMI matrix, refreshed nightly) to broaden the vector into adjacent tastes (the Shane-Gillis→baseball edge).

### 5.2 The top-5 interests (Req 7)
Project the taste vector down to a **ranked, human-readable top-N** and persist it:

```
user_taste_summary(
  user_id            uuid pk,
  interest_1..interest_5   text,     -- e.g. 'breweries', 'standup-comedy', 'live-music', 'art', 'baseball'
  interest_scores    jsonb,          -- {token: weight} full ranked list
  taste_vector       vector,         -- pgvector, machine representation
  top_entities       text[],         -- ['shane_gillis','tree_house_brewing', ...]
  refreshed_at       timestamptz,
  model_version      text
)
```

- `interest_1..5` are a **denormalized convenience projection** for CRM/marketing/decisioning (your exact ask — "so I can decision specifically off those"). Not precious about the exact schema; this is the shape.
- Human-readable interest labels come from a fixed display-name map over feature tokens (no per-user LLM call).

### 5.3 Cold-start
- **New user, no signals:** seed from onboarding `selected_categories`/`selected_venues`/`selected_neighborhoods` (`user_profiles`) + local popularity. This path already exists (`coldStartScore`); keep it as the fallback until ≥N interactions (today Bo uses ≥3).
- **Sparse user:** blend onboarding priors with observed behavior, shifting weight to behavior as it accumulates.

### 5.4 Travel propensity ("willingness to travel") — a first-class dimension
Distance is currently a **global** ranking penalty (radius-adaptive weighting in `carousels/index.ts`), identical for everyone. It should instead be a **per-user taste attribute**: a homebody and a road-tripper can have identical event taste but need very different feeds. This answers a question orthogonal to "what do they like" — namely "how far will they go to get it."

- **Anchor = the user's *active set location at interaction time*, NOT home.** Users move around and manually change their location; engagement near a weekend-getaway location says nothing about willingness to travel from home. Travel distance is measured from wherever the user was anchored **when the interaction happened**. This means active location must be **captured on the interaction record** (§4.2) — it's mutable and can't be reconstructed later.
- **What we learn:** a per-user **distance-tolerance curve**, not a single radius, from `distance(event location, interaction-time set location)` across their history. Weight by interaction strength (a `plan_add` 40 miles out is a far stronger travel signal than a `click`).
- **It's conditional, not scalar.** People travel far for *special/rare* events and stay local for *commodity* ones ("90 minutes for Shane Gillis, not for open-mic night"). Model travel tolerance as **base willingness × event draw** (affinity × uniqueness/marquee-ness), so the distance the scorer tolerates *expands for high-affinity, rare events* and *tightens for generic ones*.
- **Store:** on `user_taste_summary` — e.g. `travel_propensity` (0–1 scalar), `typical_travel_miles` (p50/p75 of engaged-event distance), optionally per-category travel willingness (some people travel for concerts but not markets).
- **Relationship to the existing radius filter:** the user-set `radius_filter` (default 25mi, "any" sentinel) is a **hard cutoff**; travel propensity is the **learned soft shaping within it**. They compose — the filter bounds the candidate set, propensity shapes the order — and learned propensity can inform a smarter *default* radius.
- **Cold start:** seed from the onboarding/`radius_filter` setting + regional density (rural users inherently travel further); refine as engagement accrues.
- **Learning trap (important):** we mostly *show* nearby events today, so history is proximity-biased and we may never discover that a user would travel. The **exploration budget** (§6) must occasionally surface a further-out high-affinity match — that's how travelers get discovered. This is a dependency, not an afterthought.

## 6. Component D — The taste/recommender service (the unification)

**One scorer, consumed by every surface, callable in both directions.**

```
score(user_taste, event_features) -> affinity ∈ [0,1]
```

- **User → events** (rank a candidate set for a user): powers For You, Explore, Bo.
- **Event → users** (score the whole eligible user base for one event): powers B2B propensity (§10). Same function, transposed.

Design points:
- Implement as a Postgres function / edge module reading precomputed `taste_vector` (user) and `event_features`/event embedding, plus the feature-affinity matrix. Use **pgvector** ANN so "score N users for an event" is a nearest-neighbor query, not O(users×events).
- **Blend, don't replace.** Final ranking = `w_taste·tasteAffinity + w_prox·proximity + w_recency·freshness + w_quality·venueRank + w_community·buzz`. Keep proximity/recency meaningful (reuse the radius-adaptive weighting already in `carousels/index.ts`).
- **Consolidate** today's three scorers (`coldStartScore`, `scoreEvent`, `scoreVenue`) to call this one service so surfaces stay consistent. (Can be phased — wrap first, converge later.)
- **Exploration:** reserve a fraction of slots for epsilon-greedy / diversity picks so the feed doesn't collapse into a filter bubble and so we keep gathering signal on things the user hasn't seen. This *is* the "recommend things they didn't express interest in" goal — make it a first-class, tunable parameter, not an accident.
- **Explainability:** the scorer must emit *why* (the driving feature/entity) so carousels can say "If you like Shane Gillis" and Bo can narrate. Plumb a `reason` alongside the score.

---

## 7. Component E — For You taste carousels (Req 4)

Add a new **taste-rail** class to `carousels/index.ts`, alongside today's template rails, rendered on the **Home "For You"** feed (`carouselsByCategory["foryou"]`).

- **Rail seed:** pick salient elements of the user's taste summary — a top entity (`shane_gillis`), a venue-type (`breweries`), a subcategory (`concerts`), or a discovered cluster.
- **Contents:** pull events via the taste scorer (§6), filtered to the seed.
- **Title:** LLM-generated, catchy, in your voice — "If you like Shane Gillis", "For the concert-goer in you", "Still loving breweries?". **Generate offline and cache** (mirror the existing `localIdioms` pattern: one background Haiku call, cached per user/seed, fire-and-forget). Never block feed render on an LLM call.
- **Guardrails:** template fallback if generation fails or is unsafe; title moderation; cap the number of taste rails per feed; suppress a rail if it can't fill ≥K events; de-dupe seeds against the interest set so you don't show "For the concert-goer" *and* "If you like [a specific concert]".
- **Interleave** with existing rails (Picked for You, neighborhood, curated "25 Must-Know", recurring, sports) using the current no-two-same-type-adjacent logic.

---

## 8. Component F — Explore Events/Places default order (Req 5)

- Both already **server-ranked** (`explore-events` `scoreEvent`, `explore-places` `scoreVenue`) with an `affinityMap`. This is the *smallest* change: swap/augment the affinity input with the richer taste vector from §5, via the unified scorer (§6).
- Default sort (`PlaceSort.popular`, Events default) becomes **taste-ranked**; keep explicit `closest` / `most_events` sorts untouched (user intent overrides taste).
- Places: user's taste over **venue-type**/vibe now drives order, not just category affinity.
- Watch the proximity/taste tradeoff — Explore is a browse surface; don't let a great-taste match 80 miles out dominate page 1.

---

## 9. Component G — Bo personalization (Req 6)

Bo already receives a "LEARNED TASTE" block from `user_taste_profiles` in the system prompt (`ChatViewModel`). Extend on three axes:

1. **Result ordering.** Order the `search_events_stratified` candidate set through the taste scorer *before* Bo's `present_suggestions`, so the LLM curates from a taste-ranked shortlist. (Bo's final selection stays LLM-driven; we're biasing the input.)
2. **Prose.** Personalize the client-derived `searchingOpener` / `searchingNarration` (`buildSearchingOpener`, `narrateToolCalls` — zero-cost, no Claude round) with taste hooks: "Since you're into breweries, checking taprooms first…". Keep the `localIdioms` (Haiku, cached) layer as-is.
3. **System prompt.** Inject the top-5 interests + salient entities so tone and recommendations lean into taste without being asked. Keep it subtle — Bo should feel like it *knows* the user, not like it's reciting a profile.

Guardrail: taste biases, never *filters*, Bo. If the user explicitly asks for something off-taste, taste must not suppress it.

---

## 10. Component H — B2B propensity product (Req 3)

The taste scorer, inverted and aggregated.

- **Query:** given a business's event (or a hypothetical event described by features), score every eligible user (active-in-window) → propensity ∈ [0,1] via §6 (event→users direction, pgvector ANN over user taste vectors).
- **Location anchor for B2B = HOME, not session location.** A venue is a fixed point asking "who is in my catchment and would travel here?" — that's a question about where people are *based*, not where they happen to be browsing. So B2B computes `distance(user home location → venue)` and applies the user's learned travel-propensity curve (§5.4) to decide whether they'd realistically make that trip. Note the asymmetry: travel propensity is **learned** from session-anchored interaction history (§5.4), but for B2B it is **applied** against home location. A traveler with home 30 miles out still counts toward a venue's audience; a homebody at the same distance largely doesn't.
- **Travel propensity widens the audience estimate (a selling point).** The reachable audience isn't just "users within a fixed radius" — it's "nearby users **plus** users further out whose taste *and* travel behavior say they'd come." More accurate and more compelling than a flat radius.
- **Output:** calibrated, bucketed counts —
  - "X users active this week who may be interested"
  - "Y users ≥90% likely", "Z ≥75%", "A ≥50%+"
- **Calibration:** raw similarity ≠ probability. Calibrate scores against observed engagement (e.g. isotonic/Platt on historical click/save given score) so "90%" means something. Thresholds are **config**, changeable later, as you asked.
- **Privacy (hard requirement):** businesses receive **aggregate counts only** — never user identity, never PII. Enforce a **minimum aggregation floor** (e.g. suppress counts < k) to prevent re-identification. This is a separate data-access boundary from the consumer app.
- **Scope note:** this is a **later phase** built *on* the taste graph, likely with its own dashboard/API, auth, billing, and its own calibration/threshold config. Don't couple its schema to the consumer surfaces. It's the monetization payoff, not a v1 blocker.

---

## 11. Data model changes (summary)

New / changed (names illustrative):
- `events`: raise `tags` cap → 12–15; add `performers TEXT[]`, `brands TEXT[]`; add `feature_vector`/embedding (pgvector) or a `event_features` companion; `tag_vocab_version`.
- `entities` (`entity_id`, `type`, `canonical_name`, `aliases[]`) + `event_entities` join (or arrays if simpler for v1).
- `event_impressions` (user_id, item_id, feature snapshot, surface, ts) — **new**, sampled.
- Enrich `community_interactions` with feature snapshot **and the user's active set location (lat/lng) at interaction time** (or companion `interaction_context`).
- `user_taste_summary` (§5.2) — the top-5 + vector + top entities **+ travel fields** (`travel_propensity`, `typical_travel_miles`).
- `feature_affinity` (feature_a, feature_b, weight) — the co-occurrence/PMI matrix, refreshed nightly.
- B2B: `propensity_config` (thresholds), plus whatever the dashboard needs.
- Enable **pgvector**.

---

## 12. Infra, pipelines & cadence

- **Tagging:** weekly `classify_events.js` (Haiku + Batches) for the bulk; add on-ingest tagging for freshness. Backfill via `FORCE=1`.
- **Feature-affinity matrix + user taste vectors:** nightly batch (GitHub Actions or `pg_cron`), with lightweight incremental updates on new interactions. Reuse the existing MV-refresh pattern (`mv_events_for_geo`, `mv_carousel_payload` on `pg_cron`).
- **Carousel titles:** background Haiku, cached (localIdioms pattern).
- **Cost control:** Haiku + batch for tagging; embeddings/affinity are batch, not per-request; scoring reads precomputed vectors; pgvector ANN for the B2B fan-out.

---

## 13. Phasing

**Phase 0 — Signal foundation** (unblocks everything, low risk)
- Impression logging; enrich interactions with feature snapshots; "not interested" control.

**Phase 1 — Rich features** (Req 1)
- Expand tag vocab + facets; raise cap to ~12–15; entity extraction; backfill; vocab governance. *Ships value on its own (better filtering/search even before ML).*

**Phase 2 — Taste model + service** (Reqs 5, 7)
- Build `user_taste_summary` (incl. top-5); unified taste scorer; wire into Explore Events/Places default order (smallest surface change); enable pgvector.

**Phase 3 — For You taste carousels + Bo** (Reqs 4, 6)
- Taste rails with cached LLM titles; Bo ordering + prose + system-prompt taste.

**Phase 4 — Collaborative lift**
- Feature↔feature affinity matrix; transitive expansion (the Shane-Gillis→baseball magic); exploration/diversity tuning.

**Phase 5 — B2B propensity product** (Req 3)
- Invert scorer; calibrate; aggregate with privacy floor; dashboard/API; billing.

---

## 14. Metrics & evaluation (how we know it works)

- **Offline:** held-out interaction recall@k / NDCG on the taste ranker vs. today's scorers.
- **Online (A/B via PostHog):** For You CTR, save rate, plan-add rate, session depth, return rate; Explore tap-through; Bo suggestion-tap rate.
- **Discovery metric (your explicit goal):** share of engagement coming from events *outside* the user's expressed categories — track that "didn't express interest" recs actually land.
- **Guardrail metrics:** diversity/coverage (are we bubbling?), proximity sanity (are we sending people too far?), calibration curve for the B2B %.

---

## 15. Risks & guardrails

- **Ephemeral events / cold-start** → solved by feature-grain content matching; don't build event-ID CF. *(Central design risk.)*
- **Free-form tag drift** → controlled vocab + governance (§3.2).
- **Filter bubble / popularity rich-get-richer** → exploration budget + diversity constraints as first-class knobs.
- **Proximity vs taste** → taste augments, never overrides, geo/recency weighting.
- **Scorer divergence** → consolidate to one taste service; don't let four surfaces drift.
- **Signal poverty (only positives)** → Phase 0 impressions before trusting the model.
- **B2B re-identification** → aggregate-only, min-count floor, separate access boundary.
- **Latency/cost** → precompute everything; no LLM in hot paths; pgvector ANN for fan-out.
- **Transparency** → consider a user-facing "why am I seeing this?" and an editable interests view (also improves signal).

---

## 16. Open questions / decisions needed

1. **Taste vector encoding for v1:** interpretable sparse map (fast, evolves `user_taste_profiles`) vs. learned embedding (pgvector). Recommend **start sparse, add embeddings in Phase 4.**
2. **Tags user-visible or machine-only?** (§3.4)
3. **Entities: full canonical `entities` table now, or arrays first?** Recommend arrays in Phase 1, canonical table when entity↔entity affinity lands (Phase 4).
4. **How aggressive is exploration?** Needs a product call on discovery-vs-relevance.
5. **B2B: who's the customer and what's the surface** (self-serve dashboard vs. sales collateral)? Shapes Phase 5 heavily.
6. **On-ingest tagging** worth the added pipeline complexity for freshness, or is weekly enough?

---

## 17. What you missed (explicit callouts)

Beyond the six requirements you listed:

1. **The tag system is load-bearing, not cosmetic.** It's the bridge that lets a zero-interaction event get recommended. Prioritize it first (Phase 1) and get the vocab right.
2. **You're missing negative/impression signals.** Everything you collect today is positive. Without "shown but skipped," you can't measure quality or calibrate the B2B %. This is Phase 0 and it's the highest-leverage gap.
3. **Cold-start (users AND events)** needs an explicit fallback path — you have one for users (onboarding prefs); events are covered by content features, but only if tagging is good.
4. **One scorer, or you'll have four.** You already have three divergent rankers. Adding taste without consolidating makes For You / Explore / Bo feel like different apps.
5. **Exploration/diversity must be deliberate.** "Recommend things they didn't ask for" is in tension with "rank by taste." Budget for it explicitly or the feed narrows over time.
6. **Calibration for the B2B %.** A similarity score is not a probability. "90% likely" needs calibrating against real outcomes or the number is fiction.
7. **Privacy boundary for the B2B product.** Aggregate-only, minimum-count floor — decide this before any data leaves the consumer boundary.
8. **Explainability plumbing.** "If you like Shane Gillis" requires the scorer to emit *why*. Design it in from the start; retrofitting reasons is painful.
9. **Entity canonicalization.** Artists/venues/series need alias-merging (you already feel this pain with stale `team_name`s). Without it, "Shane Gillis" and "Shane Gillis Live" are two different tastes.
10. **Evaluation harness.** Decide the success metric now (discovery share, save rate, session depth) so Phase 2+ ships against a target, not a vibe.
11. **Feedback-loop / staleness of interests.** Taste drifts; decay old signals and refresh the top-5 on a cadence, or a user who went to one kids' event in 2024 is "a parent" forever.
```

---

## 18. Follow-ups — v1.3

### 18.1 Seasonal / holiday rails
Time-gated For-You rails (Halloween, Thanksgiving, etc.), **universal but taste-ranked**. Deliberately lean — no table, no migration, no nightly job:
- **1 season config** (below) + an **active-season gate** (returns the season whose month-day window contains today; 0–1 active) + a **tag-filtered fetch** (`fetchOccasionEvents`, a clone of `fetchSubcategoryEvents` filtering `tags @> {tag}`) + **one rail block** in `buildCarousels` (ranked by `sortFn`, placed high during its window, ≥3-event gate).
- **Off-season = the rail isn't built** → zero footprint the other 11 months.
- **Dependency:** events tagged with the occasion tag. The `occasion` facet is already in `scripts/taste/tag_vocabulary.js` (added 2026-07-17), so the first tag backfill picks it up — no further vocab work.

Season config (product-owned titles/windows):
```ts
const SEASONS = [
  { tag: "halloween",    title: "Halloween Haunts",        from: "10-01", to: "10-31" },
  { tag: "thanksgiving", title: "Thanksgiving is coming",  from: "11-20", to: "11-30" },
  { tag: "christmas",    title: "'Tis the Season",         from: "12-01", to: "12-26" },
  { tag: "new-years",    title: "Ring in the New Year",    from: "12-27", to: "01-02" }, // wraps year-end
  { tag: "valentines",   title: "Valentine's Picks",       from: "02-01", to: "02-14" },
  { tag: "st-patricks",  title: "St. Patrick's Day",       from: "03-10", to: "03-17" },
  { tag: "pride",        title: "Pride",                   from: "06-01", to: "06-30" },
  { tag: "july-4th",     title: "Fourth of July",          from: "06-20", to: "07-04" },
  // …remaining occasion tags, same shape
];
```
Note: the New Year's window wraps the year boundary — the gate needs a trivial `from > to` wrap check.

### 18.2 Cross-domain collaborative enrichment
The "Shane Gillis → baseball" leap (entity → *subcategory/tag* adjacency, not just entity → entity). Deferred from the collaborative layer pending **per-source flood-control** — cap N events per adjacent feature so a broad subcategory (comedy) can't drown the rail. The `feature_affinity` matrix already stores cross-type relations (`related_type` = subcategory/tag/entity); this is serve-side only.
