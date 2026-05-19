# Day 3 — Supavisor transaction mode

Part of the latency sprint hybrid (CTO plan #1, week 1).

Supavisor is the connection pooler in front of Postgres. Today every
PostgREST and edge-function call opens (or reuses, if lucky) a backend
connection in **session mode**. Session mode reserves a backend per
client connection for the lifetime of the client — under burst load
(carousel cron + crawler + concurrent users) we run out of backends
before we run out of pool slots, so reads queue and the 8s
`statement_timeout` fires.

The Day-1 migration drops `statement_timeout` to 1.5s on `authenticator`
to fail fast. Day 3 raises throughput so we don't fail at all.

## Switch to transaction mode

Supabase Dashboard → **Project Settings → Database → Connection
pooling**. Set:

| Field                 | Value             | Why                                        |
| --------------------- | ----------------- | ------------------------------------------ |
| Pool mode             | **transaction**   | One backend per *transaction*, not per *connection* |
| Default pool size     | **25**            | 25 backends × ~30 ms p50 RPC ≈ 833 RPS sustained |
| Max client connections| **800**           | Mobile clients fan in here                 |

This is safe for every read path the four peer tabs use:

- `carousels`, `explore-events`, `explore-places` edge functions —
  each invocation is a single, stateless `rpc()` call. No `SET LOCAL`,
  no advisory locks, no prepared statements spanning transactions.
- `PlanRepositoryImpl` PostgREST queries — stateless `from().select()`.
- `BadgeRepositoryImpl` — same.

It would NOT be safe for any code path that:

- Holds an advisory lock across statements
- Uses session-level temp tables
- Sets a session variable expecting it to persist beyond a statement

We don't do any of those today. If a future RPC needs session state,
route it through a separate connection string (the direct Postgres URL,
not the pooler) — this is exactly what the `service_role` crawl scripts
already do for `VACUUM ANALYZE`.

## Verify after switching

```sql
-- From the SQL editor, expect transaction mode active:
SELECT name, setting FROM pg_settings WHERE name IN
  ('default_transaction_isolation', 'idle_in_transaction_session_timeout');

-- Active connections fanning into 25 backends max:
SELECT count(*) FROM pg_stat_activity
  WHERE application_name LIKE 'PostgREST%';
```

Then watch `pgbouncer_pools.cl_waiting` in Supabase logs — if it spikes
above 5 for more than 30 seconds during a normal hour, raise
`default_pool_size` to 40 and re-measure.

## Rollback

Flip pool mode back to **session** in the dashboard. No client change.
No data change. PostgREST and edge functions reconnect on the next
request.
