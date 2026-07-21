-- ===========================================================================
-- Bo reaction bucket: a shutout (score 0) is never a top run.
-- ---------------------------------------------------------------------------
-- quiz_pick_bo_message buckets by percentile: v_pct = % of TODAY's OTHER players
-- who scored higher. On a thin field (early in the day, or a quiet dev DB) almost
-- nobody has a higher score yet, so v_pct collapses to ~0 and even a rock-bottom
-- run lands in 'top_10' — e.g. a user who got all three wrong (score 0) was told
-- "Sharp. Scary sharp." Getting every question wrong is categorically not a top
-- performance regardless of who else has played, so floor a score of 0 to
-- 'bottom_50' (the gentle/light-hearted bucket). personal_best is unreachable at
-- 0 (0 can't beat a non-negative previous best), so its priority is unaffected.
-- Additive: CREATE OR REPLACE, same signature/return.
-- ===========================================================================
create or replace function public.quiz_pick_bo_message(
  p_user_id uuid, p_date date, p_score int
)
returns text
language plpgsql
volatile
security definer
set search_path = public
as $$
declare
  v_best_before int;
  v_total int;
  v_higher int;
  v_pct int;
  v_bucket text;
  v_msg text;
begin
  select coalesce(max(score), 0) into v_best_before
    from public.quiz_completions where user_id = p_user_id and quiz_date < p_date;
  select count(*) into v_total from public.quiz_completions where quiz_date = p_date;
  select count(*) into v_higher from public.quiz_completions
    where quiz_date = p_date and score > p_score and user_id <> p_user_id;
  v_pct := round(100.0 * v_higher / greatest(v_total, 1))::int;

  v_bucket := case
    when p_score > v_best_before then 'personal_best'
    when p_score = 0 then 'bottom_50'   -- all three wrong is never a top run
    when v_pct <= 10 then 'top_10'
    when v_pct <= 50 then 'top_50'
    else 'bottom_50'
  end;

  select replace(message, '{pct}', v_pct::text) into v_msg
    from public.quiz_bo_messages where bucket = v_bucket
    order by random() limit 1;

  return coalesce(v_msg, '');
end;
$$;
