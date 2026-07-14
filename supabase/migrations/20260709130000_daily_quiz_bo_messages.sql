-- ===========================================================================
-- Daily Local — Bo's post-quiz reaction line
-- ---------------------------------------------------------------------------
-- After a run, Bo says a line chosen by how the user did versus EVERYONE who
-- played today (global — the time-based scoring is uniform across states):
--   personal_best : beat their own best (takes priority)
--   top_10        : <=10% of today's players scored higher
--   top_50        : <=50% scored higher
--   bottom_50     : >50% scored higher
-- The chosen line is stored on the completion (stable on re-open). {pct} in a
-- line is replaced with the % who scored higher.
-- Additive only.
-- ===========================================================================

create table if not exists public.quiz_bo_messages (
  id      bigint generated always as identity primary key,
  bucket  text not null check (bucket in ('personal_best','top_10','top_50','bottom_50')),
  message text not null
);
create index if not exists idx_quiz_bo_messages_bucket on public.quiz_bo_messages (bucket);

alter table public.quiz_completions add column if not exists bo_message text;

-- Pick a random Bo line for the user's bucket, {pct} substituted. VOLATILE
-- because it uses random(). SECURITY DEFINER so the RPCs (which own the award)
-- can read the messages regardless of RLS.
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
