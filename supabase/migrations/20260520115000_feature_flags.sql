-- ──────────────────────────────────────────────────────────────────────
-- Server-controlled feature flags for the Bo overhaul rollout
-- (bo_resilience, image_resolve, bo_preview_card, bo_diff_card, bo_undo,
--  bo_parser_guard, bo_tight_tool_schema).
--
-- Flags are non-secret and readable by all roles via RLS SELECT-only;
-- writes are gated to service_role (no write policy = nobody but service
-- role can mutate). Realtime is enabled so the KMP client can refresh
-- its cache the moment a flag flips without a re-launch.
--
-- An audit table + AFTER UPDATE trigger captures every flip so we can
-- reconstruct the rollback timeline from the Supabase Studio UI.
-- ──────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS feature_flags (
  name        TEXT PRIMARY KEY,
  enabled     BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_by  TEXT
);

ALTER TABLE feature_flags ENABLE ROW LEVEL SECURITY;

-- Flags are not secrets; anyone authenticated or anonymous can read.
-- No write policy → only service_role (bypasses RLS) can mutate.
CREATE POLICY feature_flags_read_all
  ON feature_flags
  FOR SELECT
  USING (TRUE);

-- Realtime: stream UPDATE/INSERT/DELETE to subscribed clients.
-- REPLICA IDENTITY FULL so UPDATE payloads carry both old and new rows.
ALTER TABLE feature_flags REPLICA IDENTITY FULL;
ALTER PUBLICATION supabase_realtime ADD TABLE feature_flags;

-- Audit trail for every flip.
CREATE TABLE IF NOT EXISTS feature_flag_changes (
  id         BIGSERIAL PRIMARY KEY,
  flag_name  TEXT NOT NULL,
  old_value  BOOLEAN,
  new_value  BOOLEAN,
  actor      TEXT,
  changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_feature_flag_changes_name_time
  ON feature_flag_changes (flag_name, changed_at DESC);

ALTER TABLE feature_flag_changes ENABLE ROW LEVEL SECURITY;
-- No policies → service-role-only reads/writes. (Studio UI uses service role.)

CREATE OR REPLACE FUNCTION public.feature_flags_audit()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF NEW.enabled IS DISTINCT FROM OLD.enabled THEN
    INSERT INTO feature_flag_changes (flag_name, old_value, new_value, actor)
    VALUES (NEW.name, OLD.enabled, NEW.enabled, NEW.updated_by);
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS feature_flags_audit_trg ON feature_flags;
CREATE TRIGGER feature_flags_audit_trg
  AFTER UPDATE ON feature_flags
  FOR EACH ROW
  EXECUTE FUNCTION public.feature_flags_audit();

-- Seed rows for the Bo overhaul. ON CONFLICT keeps re-applies safe.
INSERT INTO feature_flags (name, enabled, updated_by) VALUES
  ('bo_resilience',        TRUE,  'migration'),
  ('image_resolve',        TRUE,  'migration'),
  ('bo_preview_card',      TRUE,  'migration'),
  ('bo_diff_card',         TRUE,  'migration'),
  ('bo_undo',              TRUE,  'migration'),
  ('bo_parser_guard',      TRUE,  'migration'),
  ('bo_tight_tool_schema', FALSE, 'migration')
ON CONFLICT (name) DO NOTHING;
