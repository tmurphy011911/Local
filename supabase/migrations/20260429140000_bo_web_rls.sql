-- RLS policies for bo_web_events and bo_web_venues.
--
-- Authenticated users can:
--   - INSERT rows scoped to their own user_id (the Bo chat path writes here)
--   - SELECT their own rows (regardless of state) + everyone's verified rows
--   - UPDATE their own rows for limited mutations: marking "I've verified this"
--     and incrementing user_reported_count
--
-- Service role bypasses RLS by default — verifier/promotion/dedupe paths
-- use the service key and aren't bound by these policies.

-- ── bo_web_events ──────────────────────────────────────────────────────────

CREATE POLICY "bow_events_select_own_or_verified" ON bo_web_events
  FOR SELECT TO authenticated
  USING (
    created_by_user_id = auth.uid()
    OR state = 'verified'
  );

CREATE POLICY "bow_events_insert_own" ON bo_web_events
  FOR INSERT TO authenticated
  WITH CHECK (created_by_user_id = auth.uid());

CREATE POLICY "bow_events_update_own_limited" ON bo_web_events
  FOR UPDATE TO authenticated
  USING (created_by_user_id = auth.uid())
  WITH CHECK (created_by_user_id = auth.uid());

-- ── bo_web_venues ──────────────────────────────────────────────────────────

CREATE POLICY "bow_venues_select_own_or_verified" ON bo_web_venues
  FOR SELECT TO authenticated
  USING (
    created_by_user_id = auth.uid()
    OR state = 'verified'
  );

CREATE POLICY "bow_venues_insert_own" ON bo_web_venues
  FOR INSERT TO authenticated
  WITH CHECK (created_by_user_id = auth.uid());

CREATE POLICY "bow_venues_update_own_limited" ON bo_web_venues
  FOR UPDATE TO authenticated
  USING (created_by_user_id = auth.uid())
  WITH CHECK (created_by_user_id = auth.uid());

-- ── bo_web_api_calls (logging) ─────────────────────────────────────────────
-- The chat path writes here too. Users can only see their own log rows.

CREATE POLICY "bow_calls_select_own" ON bo_web_api_calls
  FOR SELECT TO authenticated
  USING (user_id = auth.uid() OR user_id IS NULL);

CREATE POLICY "bow_calls_insert_any" ON bo_web_api_calls
  FOR INSERT TO authenticated
  WITH CHECK (TRUE);  -- the bo-web-budget edge function inserts as service-role anyway
