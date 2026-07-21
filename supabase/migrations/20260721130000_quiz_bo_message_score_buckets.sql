-- ===========================================================================
-- Bo reaction buckets: absolute score thresholds instead of percentiles.
-- ---------------------------------------------------------------------------
-- Percentile bucketing compared against everyone who played today, which made
-- Bo's line depend on WHEN you played (thin early-morning fields skewed the
-- percentile) rather than how you actually did. Bucket by absolute score:
--   personal_best : beat your own best (unchanged, still takes priority)
--   top_10        : score > 425
--   top_50        : 200 <= score <= 425
--   bottom_50     : score < 200
-- Bucket names are kept so the quiz_bo_messages table, its check constraint,
-- and the seeded lines are untouched. v_pct is still computed to fill {pct},
-- but {pct} lines claim "only N% beat you" — under score bucketing that claim
-- can be false (a 425+ run on a competitive day may sit at any percentile) —
-- so a {pct} line is only eligible when the real percentile is <= 10 and the
-- claim holds. The score-0 floor from 20260714 is subsumed (0 < 200).
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
    when p_score > 425 then 'top_10'
    when p_score >= 200 then 'top_50'
    else 'bottom_50'
  end;

  select replace(message, '{pct}', v_pct::text) into v_msg
    from public.quiz_bo_messages
    where bucket = v_bucket
      and (message not like '%{pct}%' or v_pct <= 10)
    order by random() limit 1;

  return coalesce(v_msg, '');
end;
$$;
