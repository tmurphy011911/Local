-- Per-user taste profile, populated by TasteRepositoryImpl every time the
-- user taps an event/venue card or saves a plan. Read by ChatViewModel's
-- system-prompt builder once the user has ≥3 interactions to surface a
-- "LEARNED TASTE" block to Bo (top cuisines, categories, neighborhoods,
-- price comfort, preferred times of day).
--
-- Until this migration ran, every read failed with "Could not find the
-- table 'public.user_taste_profiles' in the schema cache" and every write
-- was silently swallowed by TasteRepositoryImpl's try/catch — so Bo had
-- zero personalization data for any user.

create table if not exists user_taste_profiles (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null unique,
    -- Each scores map is {token: count} where token is a free-text label
    -- (cuisine name, category, subcategory, neighborhood, time bucket).
    -- Counts are capped at 20 by the client (see TasteRepositoryImpl.cap)
    -- so a user with strong preferences doesn't drown out the long tail.
    cuisine_scores jsonb not null default '{}'::jsonb,
    category_scores jsonb not null default '{}'::jsonb,
    venue_type_scores jsonb not null default '{}'::jsonb,
    -- price_comfort = {avg_min, avg_max, free_ratio}; a running average
    -- across the user's saved-plan slots. Lets Bo match price tier
    -- without asking.
    price_comfort jsonb not null default '{"avg_min": 0, "avg_max": 0, "free_ratio": 0}'::jsonb,
    neighborhood_scores jsonb not null default '{}'::jsonb,
    time_preferences jsonb not null default '{}'::jsonb,
    interaction_count int not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_user_taste_profiles_user_id
    on user_taste_profiles(user_id);

-- Auto-touch updated_at on UPDATE so we can later add freshness logic
-- without changing client code.
create or replace function _user_taste_profiles_touch_updated_at()
returns trigger language plpgsql as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

drop trigger if exists user_taste_profiles_touch_updated_at on user_taste_profiles;
create trigger user_taste_profiles_touch_updated_at
    before update on user_taste_profiles
    for each row execute function _user_taste_profiles_touch_updated_at();

-- RLS: each user reads + writes only their own row. Service role bypasses
-- by default for any future server-side aggregation jobs.
alter table user_taste_profiles enable row level security;

create policy "taste_select_own" on user_taste_profiles
    for select to authenticated
    using (auth.uid() = user_id);

create policy "taste_insert_own" on user_taste_profiles
    for insert to authenticated
    with check (auth.uid() = user_id);

create policy "taste_update_own" on user_taste_profiles
    for update to authenticated
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);
