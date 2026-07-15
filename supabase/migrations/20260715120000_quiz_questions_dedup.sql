-- ===========================================================================
-- Dedup guardrail for the quiz bank, so the question generator can never add a
-- duplicate of a question that's already been asked OR is still queued.
-- ---------------------------------------------------------------------------
-- normalized_question: lowercase, alphanumeric-only form of the prompt. A UNIQUE
-- index on (state, difficulty, normalized_question) makes a text-duplicate of any
-- existing row physically impossible (ON CONFLICT DO NOTHING at insert time), and
-- also collapses within-batch dupes. Every row is either asked (used_on set) or
-- queued (used_on null), so uniqueness over the whole table covers both cases.
--
-- The original seed already contains a handful of exact-normalized duplicates, so
-- collapse them first (a unique index can't be built over dupes). Keep the most
-- "load-bearing" row per group: an already-USED row (preserves rotation history)
-- before an unused one, then the most-used, then the oldest/lowest id.
-- Additive: new column + one-time cleanup + new index.
-- ===========================================================================
alter table public.quiz_questions
  add column if not exists normalized_question text
  generated always as (lower(regexp_replace(question, '[^[:alnum:]]+', '', 'g'))) stored;

delete from public.quiz_questions q
 using (
   select id,
          row_number() over (
            partition by state, difficulty, normalized_question
            order by (used_on is null), times_used desc, created_at, id
          ) as rn
     from public.quiz_questions
 ) d
 where q.id = d.id and d.rn > 1;

create unique index if not exists uq_quiz_questions_norm
  on public.quiz_questions (state, difficulty, normalized_question);
