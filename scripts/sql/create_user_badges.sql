-- User badge milestones table
-- Tracks milestone achievements per user, per state, per action type
CREATE TABLE IF NOT EXISTS user_badges (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID NOT NULL,
    state TEXT NOT NULL,             -- e.g. "MA", "ME", "NY"
    action_type TEXT NOT NULL,       -- "event_saved", "event_shared", "plan_created", "invite_accepted", "group_5_confirmed", "group_10_confirmed"
    milestone INT NOT NULL,          -- 1, 5, 10, 25, 50, 100, 250, 500, 1000
    points INT NOT NULL,             -- points awarded for this milestone
    earned_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE(user_id, state, action_type, milestone)
);

-- Index for fast lookups by user
CREATE INDEX IF NOT EXISTS idx_user_badges_user_id ON user_badges(user_id);

-- RLS policies
ALTER TABLE user_badges ENABLE ROW LEVEL SECURITY;

-- Users can read their own badges
CREATE POLICY "Users can read own badges"
    ON user_badges FOR SELECT
    USING (auth.uid() = user_id);

-- Users can insert their own badges
CREATE POLICY "Users can insert own badges"
    ON user_badges FOR INSERT
    WITH CHECK (auth.uid() = user_id);

-- Anon can read/insert (for users without auth)
CREATE POLICY "Anon can read badges"
    ON user_badges FOR SELECT
    USING (true);

CREATE POLICY "Anon can insert badges"
    ON user_badges FOR INSERT
    WITH CHECK (true);
