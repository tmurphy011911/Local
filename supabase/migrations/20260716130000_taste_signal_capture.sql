-- Taste graph — Phase 0: signal capture.
--
-- The recommender is only as good as its signals. Today community_interactions
-- stores positive-only actions (click/favorite/share/plan_add, weights 1/2/4/6)
-- but carries NO location context and NO feature snapshot, and we log nothing
-- about what was SHOWN-but-ignored. This migration closes both gaps. It is the
-- single highest-leverage, lowest-risk slice — and the data is unrecoverable if
-- we don't start capturing it now (the user's set location is mutable; events
-- get deactivated/deleted).
--
-- Additive-only: ADD COLUMN IF NOT EXISTS / CREATE TABLE IF NOT EXISTS /
-- CREATE INDEX. No live column is dropped, renamed, retyped, or set NOT NULL,
-- so every currently-installed client keeps working unchanged.

-- ── 1. Interaction-time context on community_interactions ──
-- These let us learn travel propensity (§5.4 of the spec) and keep training
-- data alive after the underlying event row is gone. All nullable so existing
-- client inserts that don't set them still succeed.

alter table community_interactions
    -- Where the USER was anchored (live GPS or manually-set location) at the
    -- moment of the interaction. NOT their home — travel propensity is measured
    -- from where they actually were. Mutable & unrecoverable after the fact.
    add column if not exists actor_lat double precision,
    add column if not exists actor_lng double precision,
    -- Where the ITEM (event/venue) was. Snapshotted so distance survives event
    -- deletion. Distance travelled = haversine(actor, item) at interaction time.
    add column if not exists item_lat double precision,
    add column if not exists item_lng double precision,
    -- Snapshot of the item's durable features (subcategory, tags, performers,
    -- brands, price band, time-of-day) at interaction time. Survives the event.
    -- Shape mirrors the event feature vector; kept as jsonb so it can evolve
    -- without a migration.
    add column if not exists features jsonb;

comment on column community_interactions.actor_lat is
    'User active/set location latitude at interaction time (NOT home). Powers travel propensity.';
comment on column community_interactions.actor_lng is
    'User active/set location longitude at interaction time (NOT home).';
comment on column community_interactions.item_lat is
    'Item (event/venue) latitude snapshot; survives event deletion.';
comment on column community_interactions.item_lng is
    'Item (event/venue) longitude snapshot.';
comment on column community_interactions.features is
    'Snapshot of item durable features at interaction time (subcategory, tags, performers, brands, price_band, tod).';

-- Note: the "not interested" negative signal needs NO schema change — it is a
-- new interaction_type value ('not_interested') on this same table, with a
-- negative weight applied server-side. Documented here so it is not missed.

-- ── 2. Impressions: what was SHOWN (the missing denominator) ──
-- Without "shown but skipped" we can't tell "disliked" from "never saw," and
-- every quality/calibration metric needs this denominator. High-volume, so the
-- client SAMPLES (not every render) and batches inserts. Pruned on a retention
-- window by a later job — raw impressions are not kept forever.

create table if not exists event_impressions (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null,
    -- Matches community_interactions.item_id (text) so events + venues share it.
    item_id text not null,
    item_type text not null default 'event',          -- 'event' | 'place'
    -- Where it was shown: 'for_you' | 'explore_events' | 'explore_places' |
    -- 'bo' | 'carousel:<carousel_id>'. Free-text so new surfaces need no migration.
    surface text not null,
    position int,                                      -- rank within the surface (0-based), nullable
    actor_lat double precision,                        -- user active location when shown
    actor_lng double precision,
    item_lat double precision,                         -- item location snapshot
    item_lng double precision,
    features jsonb,                                    -- item feature snapshot (as above)
    shown_at timestamptz not null default now()
);

create index if not exists idx_event_impressions_user_shown
    on event_impressions(user_id, shown_at desc);
create index if not exists idx_event_impressions_item
    on event_impressions(item_id);

alter table event_impressions enable row level security;

-- Users write only their own impressions; read own only. Service role bypasses
-- RLS for the nightly aggregation jobs that turn impressions into signal.
create policy "impressions_insert_own" on event_impressions
    for insert to authenticated
    with check (auth.uid() = user_id);

create policy "impressions_select_own" on event_impressions
    for select to authenticated
    using (auth.uid() = user_id);
