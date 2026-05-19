package live.localapp.local.domain.repository

import live.localapp.local.domain.model.BadgeMilestone

interface BadgeRepository {
    /** Get all earned milestones for a user. */
    suspend fun getMilestones(userId: String): List<BadgeMilestone>

    /**
     * Get lifetime action counts (e.g. how many events the user has ever
     * saved). Counts are monotonic — they never decrement when the underlying
     * favorite/plan rows are removed. Sourced from `user_action_counts`.
     *
     * Shape preserved as `Map<state, Map<actionType, count>>` for backward
     * compatibility; all counts are now grouped under the "global" key.
     */
    suspend fun getActionCounts(userId: String): Map<String, Map<String, Int>>

    /**
     * Increment the lifetime counter for an action by 1. Used at the moment
     * an action genuinely happens (favoriting an event, sharing it, creating
     * a plan). Returns the new count.
     */
    suspend fun incrementActionCount(userId: String, actionType: String): Int

    /**
     * Ratchet the lifetime counter up to a snapshot, never down. Used for
     * actions whose count is derived from a live query (e.g. confirmed accepts
     * across the creator's owned plans) — deleting a plan can reduce the live
     * count but the stored counter must stay at the all-time high.
     * Returns the new count.
     */
    suspend fun bumpActionCountTo(userId: String, actionType: String, snapshot: Int): Int

    /**
     * Live creator-side snapshot from `plan_attendees`, scoped to plans the
     * user owns. Used by [live.localapp.local.domain.service.BadgeService.claimCreatorBadges]
     * to compute the value to pass into [bumpActionCountTo].
     */
    suspend fun getCreatorAcceptSnapshot(userId: String): CreatorAcceptSnapshot

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

/**
 * Live snapshot of a creator's accept stats — computed on demand, never stored.
 * The lifetime counters are stored separately in `user_action_counts` and only
 * ratchet up from these values.
 */
data class CreatorAcceptSnapshot(
    /** Total confirmed non-creator attendees across all plans the user owns. */
    val totalAccepts: Int,
    /** True if any single owned plan has >= 5 confirmed non-creator attendees. */
    val qualifiesForGroup5: Boolean,
    /** True if any single owned plan has >= 10 confirmed non-creator attendees. */
    val qualifiesForGroup10: Boolean,
)
