-- Push notifications foundation: per-user delivery preferences + a send log.
--
-- Backs the OneSignal-transport push system. All audience targeting runs
-- server-side in scripts/push/*; OneSignal is pure delivery keyed on
-- external_user_id = the Supabase auth uid, so we never store device tokens.
--
-- user_notification_prefs — one row per user, owner-readable/writable (the
-- in-app notification settings screen). push_enabled gates everything; the
-- per-push booleans let a user mute a single push type; timezone (IANA) is
-- derived from user_profiles lat/lng when null (for local-time quiet hours).
--
-- push_sends — service-role only (no client access). The unique (user_id,
-- dedupe_key) index makes the hourly cron idempotent (a rerun can't double-send
-- the same plan reminder); (user_id, push_type, sent_at) backs frequency caps.
--
-- Additive / backward-compatible: new tables only, no changes to live columns.
-- (user_id is a bare uuid, matching user_taste_profiles — no FK to auth.users.)

create table if not exists public.user_notification_prefs (
  user_id             uuid primary key,
  push_enabled        boolean not null default false,
  thu_weekend         boolean not null default true,
  saved_event_remind  boolean not null default true,
  plan_reminder       boolean not null default true,
  churn_reactivation  boolean not null default true,
  timezone            text,
  quiet_start_hour    int not null default 21,  -- local hour, 9pm
  quiet_end_hour      int not null default 9,    -- local hour, 9am
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now()
);

alter table public.user_notification_prefs enable row level security;

drop policy if exists notif_prefs_owner_rw on public.user_notification_prefs;
create policy notif_prefs_owner_rw
  on public.user_notification_prefs
  for all
  to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create table if not exists public.push_sends (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null,
  push_type     text not null,   -- thu_weekend | saved_event_remind | plan_reminder | churn_reactivation
  dedupe_key    text,            -- e.g. 'plan_reminder:{plan_id}:{date}'
  onesignal_id  text,            -- notification id returned by OneSignal
  meta          jsonb,
  sent_at       timestamptz not null default now()
);

alter table public.push_sends enable row level security;
-- No policies: service_role bypasses RLS; authenticated/anon clients get nothing.

create unique index if not exists push_sends_dedupe
  on public.push_sends (user_id, dedupe_key) where dedupe_key is not null;
create index if not exists push_sends_user_type_time
  on public.push_sends (user_id, push_type, sent_at desc);
