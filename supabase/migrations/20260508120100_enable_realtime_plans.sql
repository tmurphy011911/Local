-- Phase 3 of the plan-edit overhaul: subscribe the iOS/Android app to plan
-- row changes via Supabase Realtime. Without these statements, the realtime
-- channel set up in `PlanRealtimeWatcher` would never receive events for
-- this table.

-- 1. Add the table to the realtime publication so postgres replication
--    streams INSERT/UPDATE/DELETE events to the realtime broker.
do $$
begin
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and tablename = 'plans'
    ) then
        execute 'alter publication supabase_realtime add table plans';
    end if;
end $$;

-- 2. Use REPLICA IDENTITY FULL so UPDATE/DELETE events carry the full row.
--    Without this, the realtime broker only sends the primary key on
--    UPDATE/DELETE — the watcher needs the row id to know which plan to
--    re-fetch (or to drop on delete).
alter table plans replica identity full;
