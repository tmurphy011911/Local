package live.localapp.local.domain.repository

import live.localapp.local.domain.model.BadgeMilestone

interface BadgeRepository {
    /** Get all earned milestones for a user. */
    suspend fun getMilestones(userId: String): List<BadgeMilestone>

    /** Get action counts per state (e.g. how many events saved in MA). */
    suspend fun getActionCounts(userId: String): Map<String, Map<String, Int>>

    /** Award a milestone. Returns the milestone if newly created, null if already existed. */
    suspend fun awardMilestone(
        userId: String,
        state: String,
        actionType: String,
        milestone: Int,
        points: Int,
    ): BadgeMilestone?

    /** Get total points for a user across all states. */
    suspend fun getTotalPoints(userId: String): Int
}
