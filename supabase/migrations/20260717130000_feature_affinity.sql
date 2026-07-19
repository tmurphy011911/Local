-- Collaborative feature-affinity matrix (taste graph — the "Shane Gillis fans
-- also like Bill Burr / baseball" layer). For each feature the user base engages
-- (subcategory / tag / performer / brand), its co-occurring features with a
-- P(related | seed) weight. Built nightly by scripts/taste/build_feature_affinity.js
-- (math in scripts/taste/feature_affinity_model.js).
--
-- Non-sensitive AGGREGATE — no per-user data, just population-level co-occurrence.
-- Read by the carousels edge function to enrich the "If You Like <X>" rails.
--
-- Additive-only: CREATE TABLE / INDEX / policy.

create table if not exists feature_affinity (
    seed         text not null,
    seed_type    text not null,   -- entity | subcategory | tag
    related      text not null,
    related_type text not null,
    weight       double precision not null,  -- P(related | seed), 0-1
    refreshed_at timestamptz not null default now(),
    primary key (seed, related)
);

create index if not exists idx_feature_affinity_seed on feature_affinity(seed);

alter table feature_affinity enable row level security;

-- Aggregate + non-sensitive: readable by anyone (the carousels edge fn reads it);
-- only the service role (bypasses RLS) writes it from the nightly job.
create policy "feature_affinity_read" on feature_affinity
    for select to anon, authenticated
    using (true);
