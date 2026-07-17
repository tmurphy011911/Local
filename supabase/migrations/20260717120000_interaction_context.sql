-- SECURITY FIX (review finding): move the sensitive per-interaction ACTOR
-- LOCATION off community_interactions into an own-RLS companion table.
--
-- community_interactions is read broadly (the client's loadHistory reads it
-- unfiltered to build community-wide signals), so the actor_lat/lng columns
-- added in 20260716130000 would expose every user's physical-location history to
-- any client that queries the table. Event/item location and the interaction
-- itself are public, but the USER's location is not.
--
-- Fix: the client writes actor location here instead, keyed to the interaction
-- row. RLS restricts read/write to the acting user; the taste builder reads it
-- via the service role (which bypasses RLS) to compute travel propensity.
--
-- The now-unused community_interactions.actor_lat/lng columns are left in place
-- (additive-only rule — 20260716130000 already shipped to dev) but the client no
-- longer writes them, so they stay null and carry no data. Deprecated.
--
-- Additive-only: CREATE TABLE / CREATE INDEX / policies. No destructive DDL.

create table if not exists interaction_context (
    id uuid primary key default gen_random_uuid(),
    -- Links to community_interactions.id (soft link — the client insert is
    -- fire-and-forget, so no hard FK to avoid coupling failure modes).
    community_interaction_id uuid,
    user_id uuid not null,
    -- The user's active/set location at interaction time (spec §5.4). SENSITIVE —
    -- protected by own-row RLS below.
    actor_lat double precision,
    actor_lng double precision,
    created_at timestamptz not null default now()
);

create index if not exists idx_interaction_context_ci
    on interaction_context(community_interaction_id);
create index if not exists idx_interaction_context_user
    on interaction_context(user_id);

alter table interaction_context enable row level security;

-- Own-row only. Service role bypasses RLS for the nightly taste builder.
create policy "interaction_context_insert_own" on interaction_context
    for insert to authenticated
    with check (auth.uid() = user_id);

create policy "interaction_context_select_own" on interaction_context
    for select to authenticated
    using (auth.uid() = user_id);
