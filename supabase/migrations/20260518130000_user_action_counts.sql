-- Lifetime monotonic counters per (user_id, action_type).
--
-- Counts only ever go UP — unsaving an event or deleting a plan does not
-- decrement the counter. This keeps Profile-screen counts ("25 events saved")
-- stable even when the underlying community_interactions / plans rows get
-- removed.
--
-- Source tables (community_interactions, plans, plan_attendees) remain the
-- truth for "what's currently saved/created/accepted." This table is purely
-- the lifetime tally that drives the badge system.

CREATE TABLE IF NOT EXISTS user_action_counts (
    user_id UUID NOT NULL,
    action_type TEXT NOT NULL,
    count INT NOT NULL DEFAULT 0,
    last_updated TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (user_id, action_type)
);

CREATE INDEX IF NOT EXISTS idx_user_action_counts_user_id
    ON user_action_counts(user_id);

-- Increment by 1, return new count. Used for per-action calls (EVENT_SAVED,
-- EVENT_SHARED, PLAN_CREATED — each call site means one action just happened).
CREATE OR REPLACE FUNCTION increment_action_count(
    p_user_id UUID,
    p_action_type TEXT
)
RETURNS INT
LANGUAGE SQL
AS $$
    INSERT INTO user_action_counts (user_id, action_type, count)
    VALUES (p_user_id, p_action_type, 1)
    ON CONFLICT (user_id, action_type)
    DO UPDATE
        SET count = user_action_counts.count + 1,
            last_updated = now()
    RETURNING count;
$$;

-- Ratchet up to a snapshot count, return new count. Used for derived /
-- snapshot actions (INVITE_ACCEPTED, GROUP_*_CONFIRMED) where the caller
-- computes the live count and we never want the counter to drop below the
-- highest snapshot ever seen — so deleting a plan with 5 accepts on it
-- doesn't claw back the 5 from the global accept counter.
CREATE OR REPLACE FUNCTION bump_action_count_to(
    p_user_id UUID,
    p_action_type TEXT,
    p_snapshot INT
)
RETURNS INT
LANGUAGE SQL
AS $$
    INSERT INTO user_action_counts (user_id, action_type, count)
    VALUES (p_user_id, p_action_type, p_snapshot)
    ON CONFLICT (user_id, action_type)
    DO UPDATE
        SET count = GREATEST(user_action_counts.count, p_snapshot),
            last_updated = now()
    RETURNING count;
$$;

ALTER TABLE user_action_counts ENABLE ROW LEVEL SECURITY;

-- Matches the user_badges policy pattern. Both anon + authed reads/writes
-- pending the broader RLS tightening pass flagged in the loyalty audit.
CREATE POLICY "Users read own action counts"
    ON user_action_counts FOR SELECT
    USING (auth.uid() = user_id);

CREATE POLICY "Users insert own action counts"
    ON user_action_counts FOR INSERT
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Users update own action counts"
    ON user_action_counts FOR UPDATE
    USING (auth.uid() = user_id);

CREATE POLICY "Anon all on user_action_counts"
    ON user_action_counts FOR ALL
    USING (true)
    WITH CHECK (true);
