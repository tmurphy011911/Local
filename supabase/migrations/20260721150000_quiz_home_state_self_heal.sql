-- ===========================================================================
-- Daily Local quiz: self-heal a NULL home_state at read time.
-- ---------------------------------------------------------------------------
-- A brand-new GPS-onboarding signup could be saved before set_home_state
-- derived a state (coords not yet landed; the GPS path set no geocoded_state),
-- leaving home_state NULL. get_daily_quiz then returned available:false and the
-- client silently hid the Daily Local card. The client now sets state on the
-- GPS path too (fixes new signups), and this migration heals already-saved
-- profiles: when home_state is NULL, resolve it inline from the profile's
-- current coords/geocoded_state (mirroring the trigger) before giving up.
-- Additive only: CREATE OR REPLACE of the two functions, same signatures.
-- ===========================================================================

create or replace function public.get_daily_quiz(p_user_id uuid)
returns jsonb
language plpgsql
security definer
set search_path to 'public'
as $function$
declare
  v_state text;
  v_date  date;
  v_tz    text;
  v_resets_at timestamptz;
  v_ids   jsonb;
  v_questions jsonb;
  v_completion jsonb;
  v_streak int;
  v_prior int;
  v_best  int;
begin
  select home_state into v_state from public.user_profiles where id = p_user_id;
  if v_state is null then
    -- Self-heal: GPS signups can be persisted before set_home_state could derive
    -- a state (raw coords hadn't landed, and the GPS path historically set no
    -- geocoded_state). Coords/geocoded_state are written back once Home loads, so
    -- resolve inline now — mirrors the set_home_state trigger. Without this the
    -- Daily Local card silently never appears for those users. Additive/idempotent.
    select coalesce(
             nullif(geocoded_state, ''),
             public.resolve_state_code(
               coalesce(hometown_lat, user_lat, manual_lat),
               coalesce(hometown_lng, user_lng, manual_lng),
               coalesce(hometown_name, city)
             )
           )
      into v_state
      from public.user_profiles
     where id = p_user_id;
  end if;
  if v_state is null then
    return jsonb_build_object('available', false, 'reason', 'no_home_state');
  end if;

  -- Only offer the quiz in live states (a seeded bank alone isn't enough).
  if not exists (select 1 from public.states where code = v_state and is_live) then
    return jsonb_build_object('available', false, 'reason', 'state_not_live', 'state', v_state);
  end if;

  -- The quiz day = the state's LOCAL calendar date (resets at local midnight).
  -- resets_at = the exact instant of the NEXT local midnight, so the client can
  -- count down to it directly (correct even when the device tz != home-state tz).
  select coalesce(timezone, 'America/New_York') into v_tz from public.states where code = v_state;
  v_date := (now() at time zone v_tz)::date;
  v_resets_at := ((v_date + 1)::timestamp at time zone v_tz);

  -- Current streak: today's if already played, else yesterday's (the at-risk one).
  select streak into v_streak from public.quiz_completions
   where user_id = p_user_id and quiz_date = v_date;
  if v_streak is null then
    select streak into v_streak from public.quiz_completions
     where user_id = p_user_id and quiz_date = v_date - 1;
  end if;
  v_streak := coalesce(v_streak, 0);

  v_ids := public.ensure_daily_quiz_set(v_state, v_date);
  if v_ids is null then
    return jsonb_build_object('available', false, 'reason', 'no_questions', 'state', v_state);
  end if;

  -- Questions in fixed easy -> medium -> hard order, preserving the set order.
  select jsonb_agg(
           jsonb_build_object(
             'id', q.id,
             'difficulty', q.difficulty,
             'question', q.question,
             'answers', q.answers,
             'max_points', case q.difficulty
                             when 'easy' then 100 when 'medium' then 200 else 300 end
           )
           order by ord.n
         )
    into v_questions
    from jsonb_array_elements_text(v_ids) with ordinality as ord(qid, n)
    join public.quiz_questions q on q.id = ord.qid;

  select to_jsonb(c) - 'user_id' into v_completion
    from public.quiz_completions c
   where c.user_id = p_user_id and c.quiz_date = v_date;

  -- Best-ever daily score + whether this is the user's first Daily Local.
  select count(*) into v_prior from public.quiz_completions
   where user_id = p_user_id and quiz_date < v_date;
  select coalesce(max(score), 0) into v_best from public.quiz_completions
   where user_id = p_user_id;
  if v_completion is not null then
    v_completion := v_completion
      || jsonb_build_object('best_all_time', v_best, 'is_first', v_prior = 0)
      || public.quiz_recent_stats(p_user_id, v_date, coalesce((v_completion->>'score')::int, 0));
  end if;

  return jsonb_build_object(
    'available',        true,
    'quiz_date',        v_date,
    'state',            v_state,
    'streak',           v_streak,
    'resets_at',        v_resets_at,
    'already_completed', v_completion is not null,
    'completion',       v_completion,
    'questions',        v_questions
  );
end;
$function$;

-- ── submit_quiz: same local-date keying ─────────────────────────────────────
create or replace function public.submit_quiz(p_user_id uuid, p_answers jsonb)
returns jsonb
language plpgsql
security definer
set search_path to 'public'
as $function$
declare
  v_state    text;
  v_date     date;
  v_tz       text;
  v_existing jsonb;
  v_set_ids  jsonb;
  a          jsonb;
  v_qid      text;
  v_sel      int;
  v_ms       int;
  v_q        public.quiz_questions%rowtype;
  v_correct  boolean;
  v_mult     int;
  v_base     int;
  v_pts      int;
  v_total    int := 0;
  v_ncorrect int := 0;
  v_breakdown jsonb := '[]'::jsonb;
  v_streak   int;
  v_prior    int;
  v_best     int;
  v_bo       text;
begin
  select home_state into v_state from public.user_profiles where id = p_user_id;
  if v_state is null then
    -- Self-heal: GPS signups can be persisted before set_home_state could derive
    -- a state (raw coords hadn't landed, and the GPS path historically set no
    -- geocoded_state). Coords/geocoded_state are written back once Home loads, so
    -- resolve inline now — mirrors the set_home_state trigger. Without this the
    -- Daily Local card silently never appears for those users. Additive/idempotent.
    select coalesce(
             nullif(geocoded_state, ''),
             public.resolve_state_code(
               coalesce(hometown_lat, user_lat, manual_lat),
               coalesce(hometown_lng, user_lng, manual_lng),
               coalesce(hometown_name, city)
             )
           )
      into v_state
      from public.user_profiles
     where id = p_user_id;
  end if;
  if v_state is null then
    return jsonb_build_object('ok', false, 'reason', 'no_home_state');
  end if;
  if not exists (select 1 from public.states where code = v_state and is_live) then
    return jsonb_build_object('ok', false, 'reason', 'state_not_live');
  end if;

  -- The quiz day = the state's LOCAL calendar date (resets at local midnight).
  select coalesce(timezone, 'America/New_York') into v_tz from public.states where code = v_state;
  v_date := (now() at time zone v_tz)::date;

  -- Already played today -> return the stored result, no re-award.
  select to_jsonb(c) - 'user_id' into v_existing
    from public.quiz_completions c
   where c.user_id = p_user_id and c.quiz_date = v_date;
  if v_existing is not null then
    return jsonb_build_object('ok', true, 'already_completed', true, 'completion', v_existing);
  end if;

  -- The set the answers must belong to (server side of truth).
  v_set_ids := public.ensure_daily_quiz_set(v_state, v_date);
  if v_set_ids is null then
    return jsonb_build_object('ok', false, 'reason', 'no_questions');
  end if;

  for a in select * from jsonb_array_elements(p_answers)
  loop
    v_qid := a->>'question_id';
    v_sel := coalesce((a->>'selected_index')::int, -1);
    v_ms  := least(greatest(coalesce((a->>'ms_remaining')::int, 0), 0), 10000);

    if not (v_set_ids ? v_qid) then
      continue;
    end if;

    select * into v_q from public.quiz_questions where id = v_qid;
    if not found then
      continue;
    end if;

    v_correct := coalesce(
      (v_q.answers -> v_sel ->> 'correct')::boolean, false
    ) and v_sel >= 0;

    v_mult := case v_q.difficulty when 'easy' then 1 when 'medium' then 2 else 3 end;
    v_base := case when v_correct then round(v_ms / 100.0)::int else 0 end;
    v_pts  := v_base * v_mult;

    if v_correct then v_ncorrect := v_ncorrect + 1; end if;
    v_total := v_total + v_pts;

    v_breakdown := v_breakdown || jsonb_build_object(
      'question_id',   v_qid,
      'difficulty',    v_q.difficulty,
      'selected_index',v_sel,
      'correct',       v_correct,
      'ms_remaining',  v_ms,
      'base',          v_base,
      'points',        v_pts
    );
  end loop;

  -- Streak = yesterday's streak + 1 (or 1 if no play yesterday).
  select streak into v_streak from public.quiz_completions
   where user_id = p_user_id and quiz_date = v_date - 1;
  v_streak := coalesce(v_streak, 0) + 1;

  insert into public.quiz_completions
    (user_id, quiz_date, state, score, correct_count, streak, breakdown)
    values (p_user_id, v_date, v_state, v_total, v_ncorrect, v_streak, v_breakdown)
    on conflict (user_id, quiz_date) do nothing;

  if v_total > 0 then
    insert into public.user_badges (user_id, state, action_type, milestone, points)
      values (p_user_id, v_state, 'daily_quiz', (v_date - date '1970-01-01'), v_total)
      on conflict (user_id, state, action_type, milestone) do nothing;
  end if;

  select count(*) into v_prior from public.quiz_completions
   where user_id = p_user_id and quiz_date < v_date;
  select coalesce(max(score), 0) into v_best from public.quiz_completions
   where user_id = p_user_id;

  v_bo := public.quiz_pick_bo_message(p_user_id, v_date, v_total);
  update public.quiz_completions set bo_message = v_bo
   where user_id = p_user_id and quiz_date = v_date;

  return jsonb_build_object(
    'ok', true,
    'already_completed', false,
    'completion', jsonb_build_object(
      'quiz_date',     v_date,
      'state',         v_state,
      'score',         v_total,
      'correct_count', v_ncorrect,
      'streak',        v_streak,
      'best_all_time', v_best,
      'is_first',      v_prior = 0,
      'bo_message',    v_bo,
      'breakdown',     v_breakdown
    ) || public.quiz_recent_stats(p_user_id, v_date, v_total)
  );
end;
$function$;
