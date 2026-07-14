-- ===========================================================================
-- Daily Local — daily state-scoped trivia quiz
-- ---------------------------------------------------------------------------
-- Additive-only (new tables + new RPCs). Touches no live object, so it is safe
-- to ship to a prod DB that serves every live App Store version at once.
--
-- Model
--   quiz_questions   : the per-state question bank (hidden; RPC-only reads)
--   daily_quiz_sets  : the 3 questions chosen for a (state, date) — same for
--                      everyone in that state that day; selected lazily on the
--                      first read of the day and pinned by the PK
--   quiz_completions : one row per (user, date) — enforces one play per day and
--                      stores the per-question breakdown for the results/share
--
-- Scoring (server-authoritative — the client never states its own points):
--   points_per_question = correct
--       ? round( clamp(ms_remaining, 0, 10000) / 100.0 * difficulty_mult )
--       : 0
--   difficulty_mult: easy 1, medium 2, hard 3   (10 pts / second remaining × mult)
--   => max 100 / 200 / 300  ->  600 / day.  Feeds the existing FOMO leaderboard
--   via a single user_badges row per day (milestone = epoch-day, monotonic).
--
-- Known V1 limitation: get_daily_quiz returns the correct flag so the client can
-- render the instant green/red feedback the PRD specifies. A user inspecting the
-- payload could therefore max the daily 600. Acceptable for trivia; the RPC still
-- owns the formula and the one-play-per-day lock. Revisit if abuse shows up.
-- ===========================================================================

-- 1. Tables --------------------------------------------------------------------

create table if not exists public.quiz_questions (
  id           text primary key,                 -- e.g. 'ma_easy_001'
  state        text not null,                     -- 'MA', 'PA', ...
  difficulty   text not null check (difficulty in ('easy','medium','hard')),
  question     text not null,
  answers      jsonb not null,                    -- [{ "text": ..., "correct": bool } x4]
  generated_at date,
  used_on      date,                              -- last date this q was served (rotation)
  times_used   int  not null default 0,
  created_at   timestamptz not null default now()
);

create index if not exists idx_quiz_questions_pick
  on public.quiz_questions (state, difficulty, used_on nulls first);

create table if not exists public.daily_quiz_sets (
  state        text not null,
  quiz_date    date not null,
  question_ids jsonb not null,                    -- ['..easy..','..medium..','..hard..'] in order
  created_at   timestamptz not null default now(),
  primary key (state, quiz_date)
);

create table if not exists public.quiz_completions (
  user_id      uuid not null,
  quiz_date    date not null,
  state        text not null,
  score        int  not null,
  correct_count int not null,
  streak       int  not null default 0,           -- consecutive-day streak including this play
  breakdown    jsonb not null,                    -- [{question_id,difficulty,selected_index,correct,ms_remaining,points}]
  completed_at timestamptz not null default now(),
  primary key (user_id, quiz_date)
);
-- streak column for tables created before it existed
alter table public.quiz_completions add column if not exists streak int not null default 0;

create index if not exists idx_quiz_completions_user on public.quiz_completions (user_id);

-- 2. RLS -----------------------------------------------------------------------
--    Bank + daily sets are RPC-only: RLS on, NO select policy -> direct reads
--    return nothing; only the SECURITY DEFINER RPCs below can see them. This
--    keeps the full answer bank off the wire.
alter table public.quiz_questions  enable row level security;
alter table public.daily_quiz_sets enable row level security;
alter table public.quiz_completions enable row level security;

-- Completions: a user may read their own history (share card, "yesterday's score").
-- Writes happen only through submit_quiz (SECURITY DEFINER), so no insert policy.
drop policy if exists "read own quiz completions" on public.quiz_completions;
create policy "read own quiz completions"
  on public.quiz_completions for select
  using (auth.uid() = user_id);

-- 3. ensure_daily_quiz_set(state, date) ---------------------------------------
--    Returns the 3 question ids for (state, date), selecting them on first call
--    and pinning them for everyone that day. Deterministic pick (md5(id||date))
--    so concurrent first-callers agree; PK + ON CONFLICT dedups the race. Prefers
--    never-used questions, falls back to the stalest (full rotation on exhaustion).
create or replace function public.ensure_daily_quiz_set(
  p_state text,
  p_date  date
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_ids     jsonb;
  v_easy    text;
  v_medium  text;
  v_hard    text;
begin
  -- Already chosen for this state/day?
  select question_ids into v_ids
    from public.daily_quiz_sets
   where state = p_state and quiz_date = p_date;
  if v_ids is not null then
    return v_ids;
  end if;

  -- Pick one per difficulty: unused first, then stalest; deterministic tiebreak.
  select id into v_easy   from public.quiz_questions
    where state = p_state and difficulty = 'easy'
    order by used_on nulls first, md5(id || p_date::text) limit 1;
  select id into v_medium from public.quiz_questions
    where state = p_state and difficulty = 'medium'
    order by used_on nulls first, md5(id || p_date::text) limit 1;
  select id into v_hard   from public.quiz_questions
    where state = p_state and difficulty = 'hard'
    order by used_on nulls first, md5(id || p_date::text) limit 1;

  if v_easy is null or v_medium is null or v_hard is null then
    return null;   -- no bank for this state yet
  end if;

  v_ids := jsonb_build_array(v_easy, v_medium, v_hard);

  insert into public.daily_quiz_sets (state, quiz_date, question_ids)
    values (p_state, p_date, v_ids)
    on conflict (state, quiz_date) do nothing;

  -- Re-read the winner (handles the race where another caller inserted first).
  select question_ids into v_ids
    from public.daily_quiz_sets
   where state = p_state and quiz_date = p_date;

  -- Mark the winning trio as used for rotation bookkeeping.
  update public.quiz_questions
     set used_on = p_date, times_used = times_used + 1
   where id in (select jsonb_array_elements_text(v_ids))
     and (used_on is null or used_on < p_date);

  return v_ids;
end;
$$;

-- 3b. quiz_recent_stats(user, date, score) -----------------------------------
--    Personal-best + recent-form context for the share card, over the user's
--    PRIOR completions (before p_date):
--      best_before   : highest prior score (0 if none)
--      attempts_before: count of prior completions
--      recent_higher : run of most-recent prior attempts strictly ABOVE p_score
--      recent_atmost : run of most-recent prior attempts AT/BELOW p_score
--    (recent_higher and recent_atmost are the leading run on either side of the
--     p_score threshold, so exactly one is > 0 when there are prior attempts.)
create or replace function public.quiz_recent_stats(
  p_user_id uuid, p_date date, p_score int
)
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
  with prior as (
    select score, row_number() over (order by quiz_date desc) rn
      from public.quiz_completions
     where user_id = p_user_id and quiz_date < p_date
  )
  select jsonb_build_object(
    'best_before',     coalesce((select max(score) from prior), 0),
    'attempts_before', (select count(*) from prior),
    'recent_higher',   (select count(*) from (
        select sum(case when score >  p_score then 0 else 1 end) over (order by rn) brk from prior
      ) t where brk = 0),
    'recent_atmost',   (select count(*) from (
        select sum(case when score <= p_score then 0 else 1 end) over (order by rn) brk from prior
      ) t where brk = 0)
  );
$$;

-- 4. get_daily_quiz(user_id) --------------------------------------------------
--    Resolves the user's home_state, ensures today's set exists, and returns the
--    3 questions (with the correct flag for instant feedback) plus whether the
--    user already played today and, if so, their stored result.
create or replace function public.get_daily_quiz(
  p_user_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_state text;
  v_date  date := current_date;
  v_ids   jsonb;
  v_questions jsonb;
  v_completion jsonb;
  v_streak int;
  v_prior int;
  v_best  int;
begin
  select home_state into v_state from public.user_profiles where id = p_user_id;
  if v_state is null then
    return jsonb_build_object('available', false, 'reason', 'no_home_state');
  end if;

  -- Only offer the quiz in live states (a seeded bank alone isn't enough).
  if not exists (select 1 from public.states where code = v_state and is_live) then
    return jsonb_build_object('available', false, 'reason', 'state_not_live', 'state', v_state);
  end if;

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
    'streak',           v_streak,              -- current consecutive-day streak
    'already_completed', v_completion is not null,
    'completion',       v_completion,          -- null if not played yet
    'questions',        v_questions
  );
end;
$$;

-- 5. submit_quiz(user_id, answers) --------------------------------------------
--    answers = [{ "question_id": text, "selected_index": int, "ms_remaining": int }]
--    Validates each answer against the bank, computes the score with the
--    authoritative formula, writes the completion (one per day), and credits FOMO
--    via a single user_badges row. Idempotent: replaying returns the stored result.
create or replace function public.submit_quiz(
  p_user_id uuid,
  p_answers jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_state    text;
  v_date     date := current_date;
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
    return jsonb_build_object('ok', false, 'reason', 'no_home_state');
  end if;
  if not exists (select 1 from public.states where code = v_state and is_live) then
    return jsonb_build_object('ok', false, 'reason', 'state_not_live');
  end if;

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

    -- The question must be part of today's pinned set for this state.
    if not (v_set_ids ? v_qid) then
      continue;
    end if;

    select * into v_q from public.quiz_questions where id = v_qid;
    if not found then
      continue;
    end if;

    -- Correct iff the selected answer's object carries "correct": true.
    v_correct := coalesce(
      (v_q.answers -> v_sel ->> 'correct')::boolean, false
    ) and v_sel >= 0;

    v_mult := case v_q.difficulty when 'easy' then 1 when 'medium' then 2 else 3 end;
    -- Pure time-based: base = 10 pts/sec (rounded), total = base × difficulty.
    -- Round-then-multiply so the on-card "base × mult = total" always reconciles.
    -- Max 100/200/300 (base up to 100 at 10s left).
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

  -- Credit FOMO once per day. milestone = epoch-day => monotonic, so the
  -- UNIQUE(user_id,state,action_type,milestone) makes re-award a no-op.
  if v_total > 0 then
    insert into public.user_badges (user_id, state, action_type, milestone, points)
      values (p_user_id, v_state, 'daily_quiz', (v_date - date '1970-01-01'), v_total)
      on conflict (user_id, state, action_type, milestone) do nothing;
  end if;

  -- Best-ever daily score + first-play flag (prior = completions before today).
  select count(*) into v_prior from public.quiz_completions
   where user_id = p_user_id and quiz_date < v_date;
  select coalesce(max(score), 0) into v_best from public.quiz_completions
   where user_id = p_user_id;

  -- Bo's reaction line (picked now that the completion is in place, so today's
  -- percentile counts this run) and stored on the completion for stable re-open.
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
$$;

-- 6. Grants -------------------------------------------------------------------
grant execute on function public.get_daily_quiz(uuid)        to anon, authenticated;
grant execute on function public.submit_quiz(uuid, jsonb)    to anon, authenticated;
-- ensure_daily_quiz_set is an internal helper; no direct grant (called via SECURITY DEFINER).
