create or replace view public.bi_auth_debug as
  select 'audit_total'::text as k, count(*)::text as v from auth.audit_log_entries
  union all
  select 'audit_actions', coalesce(string_agg(distinct payload->>'action', ', '), '(none)')
    from auth.audit_log_entries
  union all
  select 'sessions_total', count(*)::text from auth.sessions;
grant select on public.bi_auth_debug to bi_readonly;
