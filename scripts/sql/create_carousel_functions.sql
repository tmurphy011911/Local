-- ============================================================
-- SQL functions for server-side carousel building
-- Run once against your Supabase project via the SQL editor.
-- ============================================================

-- Get aggregated community scores per item, split by personal vs community
CREATE OR REPLACE FUNCTION get_community_scores(p_user_id text DEFAULT null)
RETURNS TABLE(
  item_id text,
  personal_clicks int,
  personal_favorites int,
  personal_shares int,
  personal_plan_adds int,
  community_clicks int,
  community_favorites int,
  community_shares int,
  community_plan_adds int
) AS $$
  SELECT
    ci.item_id,
    COALESCE(SUM(CASE WHEN ci.user_id = p_user_id AND ci.interaction_type = 'click' THEN 1 ELSE 0 END), 0)::int as personal_clicks,
    COALESCE(SUM(CASE WHEN ci.user_id = p_user_id AND ci.interaction_type = 'favorite' THEN 1 ELSE 0 END), 0)::int as personal_favorites,
    COALESCE(SUM(CASE WHEN ci.user_id = p_user_id AND ci.interaction_type = 'share' THEN 1 ELSE 0 END), 0)::int as personal_shares,
    COALESCE(SUM(CASE WHEN ci.user_id = p_user_id AND ci.interaction_type = 'plan_add' THEN 1 ELSE 0 END), 0)::int as personal_plan_adds,
    COALESCE(SUM(CASE WHEN ci.user_id != p_user_id AND ci.interaction_type = 'click' THEN 1 ELSE 0 END), 0)::int as community_clicks,
    COALESCE(SUM(CASE WHEN ci.user_id != p_user_id AND ci.interaction_type = 'favorite' THEN 1 ELSE 0 END), 0)::int as community_favorites,
    COALESCE(SUM(CASE WHEN ci.user_id != p_user_id AND ci.interaction_type = 'share' THEN 1 ELSE 0 END), 0)::int as community_shares,
    COALESCE(SUM(CASE WHEN ci.user_id != p_user_id AND ci.interaction_type = 'plan_add' THEN 1 ELSE 0 END), 0)::int as community_plan_adds
  FROM community_interactions ci
  GROUP BY ci.item_id;
$$ LANGUAGE sql STABLE;

-- Get category affinity for a user (weighted interaction counts per category)
CREATE OR REPLACE FUNCTION get_category_affinity(p_user_id text)
RETURNS TABLE(category text, weight double precision) AS $$
  SELECT
    ci.category,
    SUM(CASE ci.interaction_type
      WHEN 'click' THEN 1.0
      WHEN 'favorite' THEN 2.0
      WHEN 'share' THEN 4.0
      WHEN 'plan_add' THEN 6.0
      ELSE 1.0
    END) as weight
  FROM community_interactions ci
  WHERE ci.user_id = p_user_id AND ci.category IS NOT NULL
  GROUP BY ci.category;
$$ LANGUAGE sql STABLE;
