package live.localapp.local.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import live.localapp.local.domain.model.ActionProgress
import live.localapp.local.domain.model.BadgeAction
import live.localapp.local.domain.model.thresholdsFor
import live.localapp.local.domain.model.StateProgress
import live.localapp.local.domain.model.StatusTier
import live.localapp.local.domain.model.nextMilestoneFor
import live.localapp.local.domain.model.nextStatusTier
import live.localapp.local.domain.model.pointsForMilestone
import live.localapp.local.domain.model.statusTierForPoints
import live.localapp.local.domain.repository.BadgeRepository
import live.localapp.local.domain.repository.UserPreferencesRepository
import live.localapp.local.domain.repository.UsernameUpdateResult
import live.localapp.local.domain.service.BadgeCelebration
import live.localapp.local.domain.service.BadgeService

// ── Celebration events ──

sealed interface CelebrationEvent {
    data class MilestoneEarned(
        val action: BadgeAction,
        val milestone: Int,
        val points: Int,
        val state: String,
    ) : CelebrationEvent

    data class StatusLevelUp(
        val previousTier: StatusTier,
        val newTier: StatusTier,
        val totalPoints: Int,
    ) : CelebrationEvent

    data class NewState(
        val state: String,
    ) : CelebrationEvent

    /** Generic "+N FOMO" toast with a custom title (username / preferences /
     *  daily-quiz rewards that don't map to a badge milestone). */
    data class FomoEarned(
        val title: String,
        val points: Int,
    ) : CelebrationEvent

    /** Neutral dark-pill toast (Bo pull-to-refresh result). */
    data class InfoToast(
        val message: String,
        val star: Boolean,
    ) : CelebrationEvent
}

// ── UI State ──

data class ProgressUiState(
    val userName: String = "",
    val userInitial: String = "",
    // Stats
    val favoritesSaved: Int = 0,
    val plansCreated: Int = 0,
    val eventsExplored: Int = 0,
    // Bo pull-to-refresh — re-syncs FOMO total + progress stats.
    val isRefreshing: Boolean = false,
    val refreshFailed: Boolean = false,
    // Badge system
    val totalPoints: Int = 0,
    val currentTier: StatusTier = StatusTier.NEW_IN_TOWN,
    val nextTier: StatusTier? = StatusTier.FINDING_MY_WAY,
    val tierProgress: Float = 0f,  // 0..1 progress to next tier
    val stateProgresses: List<StateProgress> = emptyList(),
    val expandedState: String? = null,
    val statesExplored: Int = 0,
    // Global (all states combined) action progress
    val globalActions: List<ActionProgress> = emptyList(),
    val globalCompletedMilestones: Int = 0,
    val globalTotalMilestones: Int = 0,
    // Segment control
    val selectedSegment: Int = 0, // 0 = Earn (default), 1 = Progress
    // Celebrations
    //  - `celebration` is the one currently rendered by the global overlay.
    //  - `pendingCelebrations` is a FIFO queue of additional celebrations that
    //    fired while one was already on screen. They drain in order each time
    //    `dismissCelebration` is called. Lets a single action burst (e.g.
    //    "+12,500 PTS for invites" → "+5,000 PTS for 5+ Group Plan" → "LEVEL UP
    //    to Insider!") play through without earlier toasts being clobbered.
    val celebration: CelebrationEvent? = null,
    val pendingCelebrations: List<CelebrationEvent> = emptyList(),
    // Subscription / trial (server-owned, mirrored from user_profiles.trial_ends_at)
    val trialEndsAt: String? = null,
    // Account creation timestamp (server-owned, mirrored from user_profiles.created_at)
    val createdAt: String? = null,
    // Loading
    val isLoaded: Boolean = false,
)

class ProgressViewModel(
    private val supabaseClient: SupabaseClient,
    private val userPrefsRepo: UserPreferencesRepository,
    private val badgeRepository: BadgeRepository,
    private val badgeService: BadgeService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProgressUiState())
    val uiState: StateFlow<ProgressUiState> = _uiState.asStateFlow()

    init {
        loadProfile()
        loadStats()
        loadBadgeData()
        badgeService.initialize()
        observeCelebrations()
    }

    private fun observeCelebrations() {
        viewModelScope.launch {
            badgeService.celebrations.collect { celebration ->
                val event = when (celebration) {
                    is BadgeCelebration.MilestoneEarned -> CelebrationEvent.MilestoneEarned(
                        action = celebration.action,
                        milestone = celebration.milestone,
                        points = celebration.points,
                        state = celebration.state,
                    )
                    is BadgeCelebration.StatusLevelUp -> CelebrationEvent.StatusLevelUp(
                        previousTier = celebration.previousTier,
                        newTier = celebration.newTier,
                        totalPoints = celebration.totalPoints,
                    )
                    is BadgeCelebration.NewState -> CelebrationEvent.NewState(
                        state = celebration.state,
                    )
                    is BadgeCelebration.FomoToast -> CelebrationEvent.FomoEarned(
                        title = celebration.title,
                        points = celebration.points,
                    )
                    is BadgeCelebration.InfoToast -> CelebrationEvent.InfoToast(
                        message = celebration.message,
                        star = celebration.star,
                    )
                }
                _uiState.update { state ->
                    if (state.celebration == null) {
                        // Nothing on screen — show this one immediately.
                        state.copy(celebration = event)
                    } else {
                        // Toast already in flight — queue this one for next dismiss.
                        state.copy(pendingCelebrations = state.pendingCelebrations + event)
                    }
                }
                loadBadgeData()
                loadStats()
            }
        }
        // Refresh on every action (even without milestone). loadProfile too, so a
        // username set elsewhere (e.g. the lobby CTA) updates the header here
        // without a relaunch.
        viewModelScope.launch {
            badgeService.dataChanged.collect {
                loadProfile()
                loadBadgeData()
                loadStats()
            }
        }
    }

    // ── Last-seen snapshot for animations (persisted across app launches) ──
    private val settings = com.russhwolf.settings.Settings()
    var lastSeenPoints: Int = settings.getInt("progress_last_points", 0)
        private set
    var lastSeenProgress: Float = settings.getFloat("progress_last_progress", 0f)
        private set
    var lastSeenTier: StatusTier = StatusTier.entries.firstOrNull { it.name == settings.getStringOrNull("progress_last_tier") } ?: StatusTier.NEW_IN_TOWN
        private set

    fun saveLastSeen() {
        lastSeenPoints = _uiState.value.totalPoints
        lastSeenProgress = _uiState.value.tierProgress
        lastSeenTier = _uiState.value.currentTier
        settings.putInt("progress_last_points", lastSeenPoints)
        settings.putFloat("progress_last_progress", lastSeenProgress)
        settings.putString("progress_last_tier", lastSeenTier.name)
        // Snapshot per-action progress for each state
        _uiState.value.stateProgresses.forEach { sp ->
            sp.actions.forEach { ap ->
                val key = "${sp.state}:${ap.action.name}"
                lastSeenActionCounts[key] = ap.currentCount
            }
        }
        // Snapshot global action progress
        _uiState.value.globalActions.forEach { ap ->
            lastSeenActionCounts["global:${ap.action.name}"] = ap.currentCount
        }
    }

    /** Get last-seen count for an action in a state (for animation start). */
    fun getLastSeenActionCount(state: String, action: BadgeAction): Int {
        return lastSeenActionCounts["$state:${action.name}"] ?: 0
    }

    /** Last-seen action counts: "MA:EVENT_SAVED" -> 12 */
    private val lastSeenActionCounts = mutableMapOf<String, Int>()

    fun selectSegment(index: Int) {
        // Save last-seen before switching so animations replay correctly
        if (_uiState.value.selectedSegment == 0) { // leaving Earn tab
            saveLastSeen()
        }
        _uiState.update { it.copy(selectedSegment = index) }
    }

    fun collapseAllStates() {
        _uiState.update { it.copy(expandedState = null) }
    }

    fun refresh() {
        val before = _uiState.value.totalPoints
        _uiState.update { it.copy(isRefreshing = true, refreshFailed = false) }
        viewModelScope.launch {
            val jobs = listOf(loadStats(), loadBadgeData())
            jobs.joinAll()
            val delta = _uiState.value.totalPoints - before
            _uiState.update { it.copy(isRefreshing = false) }
            val toast = if (delta > 0) "+$delta FOMO earned" else "You're all caught up"
            badgeService.emitInfoToast(toast)
        }
    }

    /**
     * Set the user's chosen handle. Uniqueness is enforced by the DB; [onError]
     * fires with a user-facing message when the handle is taken or the write
     * fails, so the dialog can stay open and show it.
     */
    fun saveUsername(name: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            val userId = userPrefsRepo.getLocalUserId()
            if (userId == null) { onError("You're not signed in"); return@launch }
            when (userPrefsRepo.updateUsername(userId, name)) {
                UsernameUpdateResult.Success -> {
                    _uiState.update { it.copy(userName = name, userInitial = name.firstOrNull()?.uppercase() ?: "") }
                    onSuccess()
                }
                UsernameUpdateResult.Taken -> onError("That username's taken — try another")
                UsernameUpdateResult.Error -> onError("Couldn't save — try again")
            }
        }
    }

    fun toggleStateExpanded(state: String) {
        _uiState.update {
            it.copy(expandedState = if (it.expandedState == state) null else state)
        }
    }

    fun dismissCelebration() {
        _uiState.update { state ->
            val next = state.pendingCelebrations.firstOrNull()
            state.copy(
                celebration = next,
                pendingCelebrations = if (next != null) state.pendingCelebrations.drop(1) else emptyList(),
            )
        }
    }

    private fun loadProfile() {
        viewModelScope.launch {
            try {
                val prefs = userPrefsRepo.getLocalUserId()?.let { id ->
                    userPrefsRepo.loadPreferences(id)
                }
                val name = prefs?.username ?: ""
                val initial = name.firstOrNull()?.uppercase() ?: ""
                _uiState.update { it.copy(userName = name, userInitial = initial, trialEndsAt = prefs?.trialEndsAt, createdAt = prefs?.createdAt) }
            } catch (e: Throwable) {
                println("ProgressVM.loadProfile error: ${e.message}")
            }
        }
    }

    @Serializable
    private data class InteractionCountDto(
        @SerialName("interaction_type") val interactionType: String,
    )

    @Serializable
    private data class PlanCountDto(
        val id: String,
    )

    private fun loadStats(): Job = viewModelScope.launch {
        run {
            val userId = userPrefsRepo.getLocalUserId() ?: return@launch
            try {
                val favorites = supabaseClient.from("community_interactions")
                    .select {
                        filter {
                            eq("user_id", userId)
                            eq("interaction_type", "favorite")
                        }
                    }
                    .decodeList<InteractionCountDto>()

                val plans = supabaseClient.from("plans")
                    .select {
                        filter { eq("user_id", userId) }
                    }
                    .decodeList<PlanCountDto>()

                val clicks = supabaseClient.from("community_interactions")
                    .select {
                        filter {
                            eq("user_id", userId)
                            eq("interaction_type", "click")
                        }
                    }
                    .decodeList<InteractionCountDto>()

                _uiState.update {
                    it.copy(
                        favoritesSaved = favorites.size,
                        plansCreated = plans.size,
                        eventsExplored = clicks.size,
                    )
                }
            } catch (e: Throwable) {
                println("ProgressVM.loadStats error: ${e.message}")
            }
        }
    }

    private fun loadBadgeData(): Job = viewModelScope.launch {
        run {
            val userId = userPrefsRepo.getLocalUserId() ?: return@launch
            try {
                val milestones = badgeRepository.getMilestones(userId)
                val actionCounts = badgeRepository.getActionCounts(userId)
                // Deduplicate milestones by action+milestone to avoid double-counting across states
                val totalPoints = milestones.distinctBy { "${it.actionType}_${it.milestone}" }.sumOf { it.points }
                val currentTier = statusTierForPoints(totalPoints)
                val next = nextStatusTier(currentTier)

                // Persist the current tier so early surfaces (splash, first-open)
                // can render the right Bo before badge data loads.
                settings.putString("bo_current_tier", currentTier.name)

                val tierProgress = if (next != null) {
                    val rangeStart = currentTier.threshold
                    val rangeEnd = next.threshold
                    ((totalPoints - rangeStart).toFloat() / (rangeEnd - rangeStart).toFloat()).coerceIn(0f, 1f)
                } else 1f

                // Build per-state progress
                val allStates = (milestones.map { it.state } + actionCounts.keys).distinct().sorted()

                val stateProgresses = allStates.map { state ->
                    val stateMilestones = milestones.filter { it.state == state }
                    val stateCounts = actionCounts[state] ?: emptyMap()

                    val actionProgresses = BadgeAction.entries.map { action ->
                        val actionKey = when (action) {
                            BadgeAction.EVENT_SAVED -> "event_saved"
                            BadgeAction.EVENT_SHARED -> "event_shared"
                            BadgeAction.PLACE_SAVED -> "place_saved"
                            BadgeAction.PLAN_CREATED -> "plan_created"
                            BadgeAction.INVITE_ACCEPTED -> "invite_accepted"
                            BadgeAction.GROUP_5_CONFIRMED -> "group_5_confirmed"
                            BadgeAction.GROUP_10_CONFIRMED -> "group_10_confirmed"
                        }
                        val completed = stateMilestones.count { it.actionType == actionKey }
                        // Per-action creator rewards are one-badge-per-occurrence and
                        // awarded server-side; count them from badges, not the counter
                        // table (see the global-action note below).
                        val count = if (action.isPerAction) completed else (stateCounts[actionKey] ?: 0)
                        val nextMilestone = nextMilestoneFor(action, count)
                        val pointsAtNext = if (nextMilestone != null) pointsForMilestone(action, nextMilestone) else null

                        ActionProgress(
                            action = action,
                            currentCount = count,
                            nextMilestone = nextMilestone,
                            pointsAtNext = pointsAtNext,
                            completedMilestones = completed,
                        )
                    }

                    val totalMilestones = BadgeAction.entries.sumOf { thresholdsFor(it).size }

                    StateProgress(
                        state = state,
                        totalPoints = stateMilestones.sumOf { it.points },
                        completedMilestones = stateMilestones.size,
                        totalMilestones = totalMilestones,
                        actions = actionProgresses,
                    )
                }

                // Compute global action progress (sum across all states)
                val globalActions = BadgeAction.entries.map { action ->
                    val actionKey = action.name.lowercase()
                    val totalCompleted = milestones.count { it.actionType == actionKey }
                    // Per-action creator rewards (invite_accepted, group_5/10_confirmed)
                    // are awarded server-side, one badge per occurrence, so the badge
                    // count IS the number of times they happened. Source the count from
                    // badges rather than the user_action_counts table — that counter is
                    // only bumped from the plan-open claimCreatorBadges path and reads 0
                    // until (and unless) that fires, leaving REWARDS stuck at 0 even
                    // though the server already awarded the FOMO.
                    val totalCount = if (action.isPerAction) totalCompleted
                                     else actionCounts.values.sumOf { it[actionKey] ?: 0 }
                    val nextMilestone = nextMilestoneFor(action, totalCount)
                    val pointsAtNext = if (nextMilestone != null) pointsForMilestone(action, nextMilestone) else null
                    ActionProgress(
                        action = action,
                        currentCount = totalCount,
                        nextMilestone = nextMilestone,
                        pointsAtNext = pointsAtNext,
                        completedMilestones = totalCompleted,
                    )
                }
                val globalCompleted = globalActions.sumOf { it.completedMilestones }
                val globalTotal = BadgeAction.entries.sumOf { thresholdsFor(it).size }

                _uiState.update {
                    it.copy(
                        totalPoints = totalPoints,
                        currentTier = currentTier,
                        nextTier = next,
                        tierProgress = tierProgress,
                        stateProgresses = stateProgresses,
                        statesExplored = allStates.size,
                        globalActions = globalActions,
                        globalCompletedMilestones = globalCompleted,
                        globalTotalMilestones = globalTotal,
                        isLoaded = true,
                    )
                }
            } catch (e: Throwable) {
                println("ProgressVM.loadBadgeData error: ${e.message}")
                _uiState.update { it.copy(isLoaded = true) }
            }
        }
    }
}
