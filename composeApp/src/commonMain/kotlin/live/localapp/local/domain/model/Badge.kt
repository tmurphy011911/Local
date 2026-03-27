package live.localapp.local.domain.model

/** Trackable actions that earn milestones. */
enum class BadgeAction(
    val label: String,
    val basePoints: Int,
    /** One-time actions only have milestone = 1, not the full ladder. */
    val isOneTime: Boolean = false,
) {
    EVENT_SAVED("Events Saved", 25),
    EVENT_SHARED("Events Shared", 75),
    PLAN_CREATED("Plans Created", 250),
    PLAN_SHARED("Plans Shared", 500),
    GROUP_5_CONFIRMED("5+ Group Plan", 5000, isOneTime = true),
    GROUP_10_CONFIRMED("10+ Group Plan", 10000, isOneTime = true),
}

/** Milestone thresholds for repeating actions. */
val MILESTONE_THRESHOLDS = listOf(1, 3, 5, 10, 15, 25, 50, 75, 100, 150, 250, 500, 1000)

/** One-time actions only have a single milestone of 1. */
val ONE_TIME_THRESHOLD = listOf(1)

/** A single earned milestone row. */
data class BadgeMilestone(
    val id: String,
    val userId: String,
    val state: String,
    val actionType: String,
    val milestone: Int,
    val points: Int,
    val earnedAt: String,
)

/** Global status tiers. */
enum class StatusTier(val label: String, val threshold: Int) {
    NEW_IN_TOWN("New in Town", 0),
    FINDING_MY_WAY("Finding My Way", 500),
    GETTING_OUT_THERE("Getting Out There", 2500),
    REGULAR("Regular", 10000),
    INSIDER("Insider", 50000),
    TOWNIE("Townie", 150000),
    LOCAL("Local", 500000),
    LOCAL_LEGEND("Local Legend", 1000000),
}

/** Determine status tier from total points. */
fun statusTierForPoints(points: Int): StatusTier {
    return StatusTier.entries.last { points >= it.threshold }
}

/** Get the next status tier, or null if already at max. */
fun nextStatusTier(current: StatusTier): StatusTier? {
    val idx = StatusTier.entries.indexOf(current)
    return if (idx < StatusTier.entries.size - 1) StatusTier.entries[idx + 1] else null
}

/** Progress within a single action toward the next milestone. */
data class ActionProgress(
    val action: BadgeAction,
    val currentCount: Int,
    val nextMilestone: Int?,       // null if all milestones complete
    val pointsAtNext: Int?,        // points earned when next milestone hit
    val completedMilestones: Int,
    val totalMilestones: Int = if (action.isOneTime) 1 else MILESTONE_THRESHOLDS.size,
)

/** Per-state summary. */
data class StateProgress(
    val state: String,
    val totalPoints: Int,
    val completedMilestones: Int,
    val totalMilestones: Int,      // MILESTONE_THRESHOLDS.size * BadgeAction.entries.size
    val actions: List<ActionProgress>,
)

/** Compute the next milestone threshold for a given count. */
fun nextMilestoneFor(action: BadgeAction, count: Int): Int? {
    val thresholds = if (action.isOneTime) ONE_TIME_THRESHOLD else MILESTONE_THRESHOLDS
    return thresholds.firstOrNull { it > count }
}

/** Compute points for a specific milestone of an action. */
fun pointsForMilestone(action: BadgeAction, milestone: Int): Int {
    // One-time actions have fixed points (basePoints), not scaled
    if (action.isOneTime) return action.basePoints
    return milestone * action.basePoints
}

/** Thresholds for an action. */
fun thresholdsFor(action: BadgeAction): List<Int> {
    return if (action.isOneTime) ONE_TIME_THRESHOLD else MILESTONE_THRESHOLDS
}
