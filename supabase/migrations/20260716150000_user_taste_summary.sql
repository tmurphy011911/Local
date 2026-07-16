-- Taste graph — Phase 2: the per-user taste summary.
--
-- Computed by scripts/taste/build_taste_profiles.js (math in
-- scripts/taste/taste_model.js) from community_interactions joined to event
-- features, on a nightly cadence. v1 is SPARSE + interpretable (jsonb score
-- maps) per the spec decision; learned embeddings + a pgvector column come in
-- Phase 4. Consumed by the recommender/scorers in Phase 2b.
--
-- Additive-only: CREATE TABLE / policy. Nothing destructive.

create table if not exists user_taste_summary (
    user_id uuid primary key,
    -- Weighted, recency-decayed feature scores {token: score}.
    subcategory_scores jsonb not null default '{}'::jsonb,
    tag_scores jsonb not null default '{}'::jsonb,
    entity_scores jsonb not null default '{}'::jsonb,
    -- Merged interest ranking (subcategories + interest-facet tags + entities).
    interest_scores jsonb not null default '{}'::jsonb,
    -- Human-readable top-5 interests, denormalized for CRM / decisioning
    -- (spec §5.2 / Req 7). Derived from interest_scores.
    interest_1 text,
    interest_2 text,
    interest_3 text,
    interest_4 text,
    interest_5 text,
    -- Top entity slugs (performers/brands) the user gravitates to.
    top_entities text[] not null default '{}',
    -- Travel propensity (spec §5.4). travel_propensity in [0,1]; typical_travel_miles
    -- = p75 distance the user has shown they'll go (from interaction-time active
    -- location, NOT home). Both null until >= 3 travel samples.
    travel_propensity double precision,
    typical_travel_miles double precision,
    interaction_count int not null default 0,
    model_version text,
    refreshed_at timestamptz not null default now()
);

-- pgvector `taste_vector` column intentionally deferred to Phase 4 (learned
-- embeddings). v1 ranks off the jsonb score maps above.

alter table user_taste_summary enable row level security;

-- Each user may read their own summary (e.g. a future "your interests" screen).
-- Only the service role (bypasses RLS) writes it from the nightly job — there is
-- no client write path.
create policy "taste_summary_select_own" on user_taste_summary
    for select to authenticated
    using (auth.uid() = user_id);
