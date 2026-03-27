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
                totalPointsCache = milestones.sumOf { it.points }
                initialized = true
            } catch (_: Throwable) {}
        }
    }

    /**
     * Record an action and check for new milestones.
     * @param action The type of action performed
     * @param state The US state abbreviation (e.g. "MA")
     * @param newCount The updated total count for this action in this state
     */
    fun recordAction(action: BadgeAction, state: String, newCount: Int = 0) {
        val userId = userPrefsRepo.getLocalUserId() ?: return

        scope.launch {
            // Check for new state
            if (state !in knownStates) {
                knownStates.add(state)
                _celebrations.emit(BadgeCelebration.NewState(state))
            }

            val previousTotal = totalPointsCache
            val previousTier = statusTierForPoints(previousTotal)

            // Get existing milestones for this action+state
            val existing = try {
                badgeRepository.getMilestones(userId)
                    .filter { it.state == state && it.actionType == action.name.lowercase() }
                    .map { it.milestone }
                    .toSet()
            } catch (_: Throwable) { emptySet() }

            // If caller didn't provide a count, fetch from DB
            val actualCount = if (newCount > 0) newCount else {
                try {
                    val counts = badgeRepository.getActionCounts(userId)
                    val actionKey = action.name.lowercase()
                    counts[state]?.get(actionKey) ?: 0
                } catch (_: Throwable) { 0 }
            }

            var earnedPoints = 0
            var latestMilestone: Int? = null

            for (threshold in thresholdsFor(action)) {
                if (actualCount >= threshold && threshold !in existing) {
                    val pts = pointsForMilestone(action, threshold)
                    badgeRepository.awardMilestone(
                        userId = userId,
                        state = state,
                        actionType = action.name.lowercase(),
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
                            newTier = newTier,
                            totalPoints = totalPointsCache,
                        )
                    )
                }
            }

            // Always notify so profile refreshes counts
            _dataChanged.emit(Unit)
        }
    }
}
