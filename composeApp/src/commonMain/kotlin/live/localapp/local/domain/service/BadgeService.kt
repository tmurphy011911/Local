package live.localapp.local.domain.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import live.localapp.local.domain.model.BadgeAction
import live.localapp.local.domain.model.thresholdsFor
import live.localapp.local.domain.model.StatusTier
import live.localapp.local.domain.model.pointsForMilestone
import live.localapp.local.domain.model.statusTierForPoints
import live.localapp.local.domain.repository.BadgeRepository
import live.localapp.local.domain.repository.UserPreferencesRepository

/** Events emitted when a badge milestone or status change occurs. */
sealed interface BadgeCelebration {
    data class MilestoneEarned(
        val action: BadgeAction,
        val milestone: Int,
        val points: Int,
        val state: String,
    ) : BadgeCelebration

    data class StatusLevelUp(
        val previousTier: StatusTier,
        val newTier: StatusTier,
        val totalPoints: Int,
    ) : BadgeCelebration

    data class NewState(
        val state: String,
    ) : BadgeCelebration
}

/**
 * Lightweight service that checks and awards badge milestones.
 * Injected into any ViewModel that performs trackable actions.
 * Emits celebration events via SharedFlow for the UI to observe.
 */
class BadgeService(
    private val badgeRepository: BadgeRepository,
    private val userPrefsRepo: UserPreferencesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _celebrations = MutableSharedFlow<BadgeCelebration>(extraBufferCapacity = 5)
    val celebrations: SharedFlow<BadgeCelebration> = _celebrations.asSharedFlow()

    /** Emitted on every action so profile can refresh counts even without a milestone. */
    private val _dataChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 5)
    val dataChanged: SharedFlow<Unit> = _dataChanged.asSharedFlow()

    /** Known states the user has milestones in (cached to detect new states). */
    private val knownStates = mutableSetOf<String>()
    private var totalPointsCache = 0
    private var initialized = false

    /** Initialize cache from DB. Call once on app start or profile load. */
    fun initialize() {
        if (initialized) return
        scope.launch {
            val userId = userPrefsRepo.getLocalUserId() ?: return@launch
            try {
                val milestones = badgeRepository.getMilestones(userId)
                knownStates.addAll(milestones.map { it.state })
                // Deduplicate milestones by action+milestone to avoid double-counting across states
                val uniqueMilestones = milestones.distinctBy { "${it.actionType}_${it.milestone}" }
                totalPointsCache = uniqueMilestones.sumOf { it.points }
                initialized = true
            } catch (_: Throwable) {}
        }
    }

    /**
     * Record an action and check for new milestones.
     * @param action The type of action performed
     * @param state The US state abbreviation (e.g. "MA")
     * @param newCount The updated total count for this action in this state
     *
     * Fire-and-forget — body runs on [scope]. For multi-action sequences where
     * order and the [totalPointsCache] read/write must be serialized, use a
     * single coroutine that calls [recordActionImpl] in series (see
     * [claimCreatorBadges]).
     */
    fun recordAction(action: BadgeAction, state: String, newCount: Int = 0) {
        val userId = userPrefsRepo.getLocalUserId() ?: return
        scope.launch { recordActionImpl(userId, action, state, newCount) }
    }

    /**
     * Suspending core of [recordAction]. Extracted so multiple actions can be
     * processed sequentially without racing on [totalPointsCache] or interleaving
     * celebration emissions in non-deterministic order.
     *
     * Updates the lifetime counter in `user_action_counts` first, then uses
     * the resulting count for milestone checks. The counter is monotonic — it
     * never decreases when the user unsaves an event or deletes a plan — so
     * Profile-screen counts stay stable across undo actions.
     */
    private suspend fun recordActionImpl(
        userId: String,
        action: BadgeAction,
        state: String,
        newCount: Int,
    ) {
        val previousTotal = totalPointsCache
        val previousTier = statusTierForPoints(previousTotal)
        val actionKey = action.name.lowercase()

        // Get existing milestones for this action (across all states)
        val existing = try {
            badgeRepository.getMilestones(userId)
                .filter { it.actionType == actionKey }
                .map { it.milestone }
                .toSet()
        } catch (_: Throwable) { emptySet() }

        // Update the lifetime counter and read back the new value.
        //   newCount == 0 → per-action call (EVENT_SAVED, EVENT_SHARED,
        //                   PLAN_CREATED) — increment by 1.
        //   newCount  > 0 → snapshot call (INVITE_ACCEPTED, GROUP_*_CONFIRMED
        //                   via claimCreatorBadges) — ratchet up to the
        //                   snapshot, never down.
        val actualCount = if (newCount > 0) {
            badgeRepository.bumpActionCountTo(userId, actionKey, newCount)
        } else {
            badgeRepository.incrementActionCount(userId, actionKey)
        }

        var earnedPoints = 0
        var latestMilestone: Int? = null

        for (threshold in thresholdsFor(action)) {
            if (actualCount >= threshold && threshold !in existing) {
                val pts = pointsForMilestone(action, threshold)
                badgeRepository.awardMilestone(
                    userId = userId,
                    state = state,
                    actionType = actionKey,
                    milestone = threshold,
                    points = pts,
                )
                earnedPoints += pts
                latestMilestone = threshold
            }
        }

        if (latestMilestone != null) {
            totalPointsCache += earnedPoints

            _celebrations.emit(
                BadgeCelebration.MilestoneEarned(
                    action = action,
                    milestone = latestMilestone,
                    points = earnedPoints,
                    state = state,
                )
            )

            // Check for level up
            val newTier = statusTierForPoints(totalPointsCache)
            if (newTier != previousTier) {
                _celebrations.emit(
                    BadgeCelebration.StatusLevelUp(
                        previousTier = previousTier,
                        newTier = newTier,
                        totalPoints = totalPointsCache,
                    )
                )
            }
        }

        // Always notify so profile refreshes counts
        _dataChanged.emit(Unit)
    }

    /**
     * Award any pending creator-side badges based on confirmed accepts across
     * the user's owned plans. Call this when the creator opens a plan they own
     * — the count of new accepts since the last open will typically push them
     * across a milestone, surfacing a toast via the existing celebration flow.
     *
     * Reads a live snapshot from [BadgeRepository.getCreatorAcceptSnapshot]
     * (queries `plan_attendees`) and ratchets the lifetime counters in
     * `user_action_counts` up to that snapshot via [recordActionImpl]'s
     * bumpActionCountTo path. Deleting an owned plan can reduce the live
     * snapshot, but the stored counter never goes down.
     *
     * Snapshot zeros short-circuit — calling recordActionImpl with newCount=0
     * would trigger the per-action increment path, which is wrong for these
     * snapshot-driven actions.
     *
     * The three actions run sequentially in a single coroutine so that:
     *   1. [totalPointsCache] reads/writes don't race across actions.
     *   2. Celebration emissions arrive in a deterministic crescendo order —
     *      INVITE_ACCEPTED milestone → GROUP_5 milestone → GROUP_10 milestone,
     *      with any StatusLevelUp interleaved at the exact action that crossed
     *      the tier. The ProgressViewModel queue then plays them back in order.
     *
     * Safe to call repeatedly: each call is idempotent — only crossings of
     * previously-unawarded thresholds emit.
     */
    fun claimCreatorBadges() {
        val userId = userPrefsRepo.getLocalUserId() ?: return
        scope.launch {
            val snapshot = try {
                badgeRepository.getCreatorAcceptSnapshot(userId)
            } catch (_: Throwable) { return@launch }

            if (snapshot.totalAccepts > 0) {
                recordActionImpl(userId, BadgeAction.INVITE_ACCEPTED, "global", snapshot.totalAccepts)
            }
            if (snapshot.qualifiesForGroup5) {
                recordActionImpl(userId, BadgeAction.GROUP_5_CONFIRMED, "global", 1)
            }
            if (snapshot.qualifiesForGroup10) {
                recordActionImpl(userId, BadgeAction.GROUP_10_CONFIRMED, "global", 1)
            }
        }
    }
}
