-- v2: allow a deliberate "I moved" home-city change, at most once per 365 days.
--
-- Hometown is otherwise write-once (guard_hometown_write). This adds an
-- authorized path: the change_home_city RPC sets a txn-local flag the guard
-- honors, enforces the rate limit, re-derives home_state, and stamps the change.

alter table public.user_profiles
  add column if not exists hometown_place_id   text,
  add column if not exists hometown_changed_at timestamptz;

-- Relax the write-once guard ONLY when the authorized RPC has set the txn-local
-- flag. Every other UPDATE still preserves the existing hometown (unchanged
-- behavior).
create or replace function public.guard_hometown_write()
returns trigger
language plpgsql
as $$
begin
  if tg_op = 'UPDATE'
     and (old.hometown_name is not null or old.hometown_lat is not null)
     and coalesce(current_setting('app.allow_hometown_change', true), '') <> 'on' then
    new.hometown_name   := old.hometown_name;
    new.hometown_lat    := old.hometown_lat;
    new.hometown_lng    := old.hometown_lng;
    new.hometown_set_at := old.hometown_set_at;
    return new;
  end if;
  if new.hometown_name is not null or new.hometown_lat is not null then
    new.hometown_set_at := coalesce(new.hometown_set_at, now());
  end if;
  return new;
end;
$$;

-- change_home_city: rate-limited (1x/365d) home-city change. SECURITY DEFINER so
-- it can flip the guard flag + write across the immutable column; targets the
-- AUTHENTICATED user (auth.uid() when present), and rejects an authenticated
-- caller trying to change someone else's profile.
create or replace function public.change_home_city(
  p_user_id  uuid,
  p_name     text,
  p_lat      double precision,
  p_lng      double precision,
  p_place_id text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid   uuid := coalesce(auth.uid(), p_user_id);
  v_last  timestamptz;
  v_state text;
begin
  if auth.uid() is not null and auth.uid() <> p_user_id then
    return jsonb_build_object('ok', false, 'error', 'unauthorized');
  end if;
  if v_uid is null or p_name is null or p_lat is null or p_lng is null then
    return jsonb_build_object('ok', false, 'error', 'invalid');
  end if;

  select hometown_changed_at into v_last from public.user_profiles where id = v_uid;
  if v_last is not null and now() - v_last < interval '365 days' then
    return jsonb_build_object(
      'ok', false, 'error', 'rate_limited',
      'next_allowed_at', to_char((v_last + interval '365 days') at time zone 'UTC', 'YYYY-MM-DD'));
  end if;

  v_state := public.resolve_state_code(p_lat, p_lng, p_name);

  perform set_config('app.allow_hometown_change', 'on', true);
  update public.user_profiles
     set hometown_name      = p_name,
         hometown_lat       = p_lat,
         hometown_lng       = p_lng,
         hometown_place_id  = p_place_id,
         hometown_changed_at = now(),
         home_state         = v_state
   where id = v_uid;

  return jsonb_build_object(
    'ok', true,
    'home_state', v_state,
    'next_allowed_at', to_char((now() + interval '365 days') at time zone 'UTC', 'YYYY-MM-DD'));
end;
$$;

grant execute on function public.change_home_city to anon, authenticated, service_role;

comment on function public.change_home_city is
  'v2 home-city change. Rate-limited 1x/365d, re-derives home_state, bypasses the write-once hometown guard via a txn-local flag. Scoped to the authenticated user.';
