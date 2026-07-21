package live.localapp.local.ui.plans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import live.localapp.local.data.sync.ChangeKind
import live.localapp.local.data.sync.MutationLog
import live.localapp.local.data.sync.PlanMutation
import live.localapp.local.data.sync.PlanRealtimeWatcher
import live.localapp.local.data.sync.PlanReconciler
import live.localapp.local.data.sync.PlanSyncer
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.model.isLastDayPast
import live.localapp.local.domain.service.BadgeService
import live.localapp.local.domain.model.PlanDay
import live.localapp.local.domain.model.PlanSlot
import live.localapp.local.domain.model.ReservationState
import live.localapp.local.domain.model.TicketState
import live.localapp.local.domain.model.Venue
import live.localapp.local.domain.repository.CommunityInterestRepository
import live.localapp.local.domain.repository.InteractionType
import live.localapp.local.domain.repository.EventRepository
import live.localapp.local.domain.repository.PlanRepository
import live.localapp.local.domain.repository.UserPreferencesRepository
import live.localapp.local.domain.repository.VenueRepository

data class PlansUiState(
    val upcomingPlans: List<Plan> = emptyList(),
    val pastPlans: List<Plan> = emptyList(),
    val pendingInvites: List<Plan> = emptyList(),
    val pendingAcceptAfterAuth: Plan? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val savedEvents: List<Event> = emptyList(),
    val savedVenues: List<Venue> = emptyList(),
    // Bo pull-to-refresh: whether the last manual refresh failed.
    val refreshFailed: Boolean = false,
    val templateRotationKey: Int = 0,
    /** Set when an external surface (e.g. the chat-success "View Plan"
     *  link) wants the Plans tab to auto-open a specific plan. PlansScreen
     *  observes this and routes through its normal onPlanClick path so the
     *  full plan-detail wiring fires; cleared after consumption. */
    val pendingOpenPlanId: String? = null,
    /** Number of pending plan mutations the syncer hasn't yet flushed.
     *  Surfaced for an unobtrusive "syncing N changes" affordance. */
    val pendingSyncCount: Int = 0,
    /** Most recent error from the syncer, if any op exhausted retries. */
    val syncError: String? = null,
)

/**
 * Owns the user's plans + saved events/venues for the Plans tab. Every
 * mutation is appended to the persistent [MutationLog] and applied
 * optimistically to local state; the [PlanSyncer] flushes the log to
 * Supabase in the background. `loadPlans()` reconciles the server snapshot
 * against any still-pending ops via [PlanReconciler] so a refresh never
 * overwrites an unsynced edit.
 */
class PlansViewModel(
    private val planRepository: PlanRepository,
    private val eventRepository: EventRepository,
    private val venueRepository: VenueRepository,
    private val communityInterestRepository: CommunityInterestRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val mutationLog: MutationLog,
    private val syncer: PlanSyncer,
    private val realtime: PlanRealtimeWatcher,
    private val badgeService: BadgeService,
) : ViewModel() {

    /**
     * Server snapshot, before reconciliation. We hold this separately so that
     * when the pending-ops log changes (an op syncs, an op gets enqueued),
     * we can recompute the user-visible plans without going back to Supabase.
     */
    private val _serverPlans = MutableStateFlow<List<Plan>>(emptyList())

    private val _uiState = MutableStateFlow(PlansUiState())
    val uiState: StateFlow<PlansUiState> = _uiState.asStateFlow()

    init {
        // Keep the cached server snapshot consistent with each op the syncer
        // confirms, BEFORE the op leaves the log. Otherwise a synced delete/edit
        // flickers back: the tombstone op disappears from the pending log while
        // _serverPlans still holds the stale row, so reconcile resurrects it
        // until a Realtime/refetch catches up (Bugs #11, #27).
        syncer.onSynced = ::applySyncedToServerSnapshot
        loadPlans()
        loadSavedItems()
        observeReconciliation()
        observeSyncerState()
        observeRealtime()
    }

    private fun applySyncedToServerSnapshot(mutation: PlanMutation) {
        when (mutation) {
            is PlanMutation.DeletePlan ->
                _serverPlans.update { plans -> plans.filter { it.id != mutation.planId } }
            else ->
                _serverPlans.update { plans ->
                    plans.map { if (it.id == mutation.planId) mutation.applyTo(it) else it }
                }
        }
    }

    /**
     * Apply Supabase Realtime row updates to [_serverPlans] so a write from
     * another device (or a Bo edit on the web someday) appears here without
     * a manual refresh. The reconciler then folds local pending ops on top.
     */
    private fun observeRealtime() {
        // Subscribe to row changes for the current user. If userId isn't set
        // yet (pre-onboarding splash), skip — the next refresh after login
        // will re-instantiate this VM with auth ready.
        userPreferencesRepository.getLocalUserId()?.let { realtime.start(it) }

        viewModelScope.launch {
            realtime.changes.collect { change ->
                when (change.kind) {
                    ChangeKind.DELETE -> {
                        _serverPlans.update { plans -> plans.filter { it.id != change.planId } }
                    }
                    ChangeKind.INSERT, ChangeKind.UPDATE -> {
                        val fresh = try { planRepository.getPlan(change.planId) } catch (_: Throwable) { null }
                            ?: return@collect
                        _serverPlans.update { plans ->
                            val without = plans.filter { it.id != change.planId }
                            without + fresh
                        }
                    }
                }
            }
        }
    }

    /**
     * Recompute upcoming/past plans whenever either the server snapshot or
     * the pending-op log changes. Single source of truth for what the UI
     * sees: `reconcile(server, pending)`.
     */
    private fun observeReconciliation() {
        viewModelScope.launch {
            combine(_serverPlans, mutationLog.pending) { server, pending ->
                PlanReconciler.reconcile(server, pending.map { it })
            }.collect { reconciled ->
                _uiState.update {
                    it.copy(
                        // Past = last day before today (computed from day labels;
                        // the DB is_past flag is never maintained). See Plan.isLastDayPast.
                        upcomingPlans = reconciled.filter { p -> !p.isLastDayPast() }.sortedBy { p -> p.createdAt },
                        pastPlans = reconciled.filter { p -> p.isLastDayPast() }.sortedByDescending { p -> p.createdAt },
                    )
                }
            }
        }
    }

    private fun observeSyncerState() {
        viewModelScope.launch {
            combine(mutationLog.pending, syncer.state) { pending, sync ->
                PlansUiState(
                    pendingSyncCount = pending.size,
                    syncError = sync.lastError ?: pending.firstOrNull { it.attempts >= 5 }?.lastError,
                )
            }.collect { snippet ->
                _uiState.update {
                    it.copy(
                        pendingSyncCount = snippet.pendingSyncCount,
                        syncError = snippet.syncError,
                    )
                }
            }
        }
    }

    /** Set the pending-open plan id. Consumed by PlansScreen on observation.
     *  Also kicks loadPlans() because the requested plan is typically one
     *  the user just saved in chat — and the cached upcomingPlans list won't
     *  contain it yet, so PlansScreen's LaunchedEffect would silently fail
     *  to find a match and never open the detail sheet. */
    fun requestOpenPlan(planId: String?) {
        _uiState.update { it.copy(pendingOpenPlanId = planId) }
        if (planId != null) loadPlans()
    }

    /** Clear the pending-open signal once PlansScreen has acted on it. */
    fun consumePendingOpenPlan() {
        _uiState.update { it.copy(pendingOpenPlanId = null) }
    }

    fun refresh() {
        _uiState.update {
            it.copy(
                isRefreshing = true,
                refreshFailed = false,
                templateRotationKey = it.templateRotationKey + 1,
            )
        }
        // Also kick the syncer in case retries are pending — refresh is
        // the user's "try again" affordance.
        syncer.kick()
        viewModelScope.launch {
            joinAll(loadPlans(), loadSavedItems())
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    // ── Slot-level mutations ───────────────────────────────────────────

    fun removeSlotFromPlan(planId: String, dayIndex: Int, slotId: String) {
        enqueue(PlanMutation.RemoveSlot(newOpId(), planId, nowMillis(), dayIndex, slotId))
    }

    fun setSlotReserved(planId: String, dayIndex: Int, slotId: String, reserved: Boolean) {
        enqueue(PlanMutation.SetSlotReserved(newOpId(), planId, nowMillis(), dayIndex, slotId, reserved))
    }

    fun setReservationDetails(planId: String, dayIndex: Int, slotId: String, partySize: Int, reservationTime: String) {
        enqueue(PlanMutation.SetReservationDetails(newOpId(), planId, nowMillis(), dayIndex, slotId, partySize, reservationTime))
    }

    fun setReservationUnavailable(planId: String, dayIndex: Int, slotId: String) {
        enqueue(PlanMutation.SetReservationUnavailable(newOpId(), planId, nowMillis(), dayIndex, slotId))
    }

    fun clearReservation(planId: String, dayIndex: Int, slotId: String) {
        enqueue(PlanMutation.ClearReservation(newOpId(), planId, nowMillis(), dayIndex, slotId))
    }

    fun setTicketDetails(planId: String, dayIndex: Int, slotId: String, ticketCount: Int) {
        enqueue(PlanMutation.SetTicketDetails(newOpId(), planId, nowMillis(), dayIndex, slotId, ticketCount))
    }

    fun setTicketsUnavailable(planId: String, dayIndex: Int, slotId: String) {
        enqueue(PlanMutation.SetTicketsUnavailable(newOpId(), planId, nowMillis(), dayIndex, slotId))
    }

    fun clearTickets(planId: String, dayIndex: Int, slotId: String) {
        enqueue(PlanMutation.ClearTickets(newOpId(), planId, nowMillis(), dayIndex, slotId))
    }

    /**
     * Swap an existing slot in place. Preserves position. For restaurants,
     * the original time is kept (per spec §3.4 — diners want their reserved
     * time slot). For events, the alternative event's actual start time is used.
     */
    fun swapSlot(planId: String, dayIndex: Int, oldSlotId: String, newSlot: PlanSlot) {
        enqueue(PlanMutation.SwapSlot(newOpId(), planId, nowMillis(), dayIndex, oldSlotId, newSlot))
    }

    fun addSlotToDay(planId: String, dayIndex: Int, slot: PlanSlot) {
        enqueue(PlanMutation.AddSlot(newOpId(), planId, nowMillis(), dayIndex, slot))
    }

    // ── Day-level mutations ────────────────────────────────────────────

    fun removeDayFromPlan(planId: String, dayIndex: Int) {
        enqueue(PlanMutation.RemoveDay(newOpId(), planId, nowMillis(), dayIndex))
    }

    fun addDayToPlan(planId: String) {
        // Compute the next index from the *currently visible* state (server +
        // pending ops already applied), so consecutive add-day taps don't
        // collide on the same label.
        val current = (_uiState.value.upcomingPlans + _uiState.value.pastPlans)
            .find { it.id == planId } ?: return
        val nextIndex = current.days.size + 1
        val newDay = PlanDay(
            label = "Day $nextIndex",
            shortName = "Day $nextIndex",
            slots = emptyList(),
        )
        enqueue(PlanMutation.AddDay(newOpId(), planId, nowMillis(), newDay))
    }

    // ── Plan-level mutations ───────────────────────────────────────────

    fun setGroupSize(planId: String, size: Int) {
        enqueue(PlanMutation.SetGroupSize(newOpId(), planId, nowMillis(), size))
    }

    fun setPlanName(planId: String, newName: String) {
        enqueue(PlanMutation.SetPlanName(newOpId(), planId, nowMillis(), newName))
    }

    fun setAttendeesCanEdit(planId: String, canEdit: Boolean) {
        enqueue(PlanMutation.SetAttendeesCanEdit(newOpId(), planId, nowMillis(), canEdit))
    }

    fun deletePlan(planId: String) {
        enqueue(PlanMutation.DeletePlan(newOpId(), planId, nowMillis()))
    }

    /** Hand a typed mutation to the syncer. The reconciler picks it up via
     *  the [MutationLog.pending] flow and re-renders the UI immediately. */
    private fun enqueue(mutation: PlanMutation) {
        viewModelScope.launch { syncer.enqueue(mutation) }
    }

    // ── Invites (unchanged — go through dedicated repo paths) ───────────

    fun addPendingInvite(plan: Plan) {
        _uiState.update { state ->
            if (state.pendingInvites.any { it.id == plan.id }) return
            state.copy(pendingInvites = state.pendingInvites + plan)
        }
    }

    fun dismissInvite(planId: String) {
        _uiState.update { state ->
            state.copy(pendingInvites = state.pendingInvites.filter { it.id != planId })
        }
    }

    fun acceptInvite(plan: Plan) {
        viewModelScope.launch {
            val shareToken = plan.shareToken
            if (shareToken != null) {
                val result = planRepository.acceptPlanInvite(shareToken)
                if (result.error == "already_accepted") {
                    println("PlansVM: invite already accepted, skipping save")
                    _uiState.update { state ->
                        state.copy(
                            pendingInvites = state.pendingInvites.filter { it.id != plan.id },
                            pendingAcceptAfterAuth = null,
                        )
                    }
                    return@launch
                }
                // INVITE_ACCEPTED / GROUP_5_CONFIRMED / GROUP_10_CONFIRMED points
                // belong to the *plan creator*, not the accepter — and the creator
                // is usually offline at the moment of accept. They're claimed
                // (and toasted) on the creator's device the next time they open
                // the affected plan; see PlanDetailScreen + BadgeService.claimCreatorBadges.
            }

            planRepository.savePlan(plan)
            _uiState.update { state ->
                state.copy(
                    pendingInvites = state.pendingInvites.filter { it.id != plan.id },
                    pendingAcceptAfterAuth = null,
                )
            }
            refresh()
        }
    }

    /** Mark a plan to be accepted after login/onboarding completes. */
    fun setPendingAcceptAfterAuth(plan: Plan) {
        _uiState.update { it.copy(pendingAcceptAfterAuth = plan) }
    }

    /** Call after auth completes — if there's a pending invite, accept it. */
    fun processPendingAccept() {
        val plan = _uiState.value.pendingAcceptAfterAuth ?: return
        acceptInvite(plan)
    }

    /** Get the current user ID (for isCreator checks). */
    fun currentUserId(): String? = userPreferencesRepository.getLocalUserId()

    // ── Loading ────────────────────────────────────────────────────────

    private fun loadPlans(): Job = viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true) }
        val rawPlans = planRepository.getPlans()
        val plans = backfillSlotVenueCities(rawPlans)
        _serverPlans.value = plans
        _uiState.update { it.copy(isLoading = false) }
    }

    /**
     * Reads `event.venueCity` for any slot missing it and stamps the slot.
     * Single batched eventRepository call so the cost is one round trip
     * regardless of how many plans / slots are missing the field.
     * Slots without an `eventId` (venue stops) are left alone — the city
     * for those is captured at venue lookup time, not from event rows.
     */
    private suspend fun backfillSlotVenueCities(plans: List<Plan>): List<Plan> {
        val needsFill = plans.flatMap { p ->
            p.days.flatMap { d -> d.slots }
        }.filter { s ->
            s.eventId != null && s.venueCity.isNullOrBlank()
        }.mapNotNull { it.eventId }.distinct()
        if (needsFill.isEmpty()) return plans

        val eventsById = try {
            eventRepository.getEventsByIds(needsFill).associateBy { it.id }
        } catch (e: Throwable) {
            println("PlansVM.backfillSlotVenueCities: getEventsByIds failed — ${e.message}")
            return plans
        }

        return plans.map { plan ->
            plan.copy(
                days = plan.days.map { day ->
                    day.copy(
                        slots = day.slots.map { slot ->
                            if (slot.eventId != null && slot.venueCity.isNullOrBlank()) {
                                val ev = eventsById[slot.eventId]
                                if (ev != null) slot.copy(venueCity = ev.venueCity) else slot
                            } else slot
                        }
                    )
                }
            )
        }
    }

    private fun loadSavedItems(): Job = viewModelScope.launch {
        run {
            try {
                val userId = userPreferencesRepository.getLocalUserId()
                if (userId == null) return@launch

                val eventIds = communityInterestRepository.getFavoriteEventIds(userId)
                val venueIds = communityInterestRepository.getFavoriteVenueIds(userId)

                // Drop past events so they fall out of Saved Events once their
                // date passes. isUpcoming parses the timestamp (never a raw ISO
                // string compare, which breaks on offset/precision differences).
                val events = if (eventIds.isNotEmpty()) {
                    eventRepository.getEventsByIds(eventIds).filter { it.isUpcoming }
                } else emptyList()
                val venues = if (venueIds.isNotEmpty()) venueRepository.getVenuesByIds(venueIds) else emptyList()

                _uiState.update {
                    it.copy(savedEvents = events, savedVenues = venues)
                }
            } catch (e: Throwable) {
                println("PlansVM.loadSavedItems error: ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Un-saves a venue from the "Ask Bo for Upcoming Events at..." list. Mirrors
     * PlacesViewModel.toggleVenueFavorite's remove branch — clears the favorite
     * and optimistically drops the row so the list updates without a refetch.
     */
    fun unsaveVenue(venueId: String) {
        communityInterestRepository.removeFavorite(venueId, InteractionType.PLACE)
        _uiState.update { state ->
            state.copy(savedVenues = state.savedVenues.filterNot { it.id == venueId })
        }
    }
}

// ── Helpers ────────────────────────────────────────────────────────────

private fun newOpId(): String =
    "op_" + kotlin.time.Clock.System.now().toEpochMilliseconds().toString(36) +
        "_" + kotlin.random.Random.nextLong().toString(36)

private fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
