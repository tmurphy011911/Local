package live.localapp.local.ui.plans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.model.Venue
import live.localapp.local.domain.repository.CommunityInterestRepository
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
    val templateRotationKey: Int = 0,
    /** Set when an external surface (e.g. the chat-success "View Plan"
     *  link) wants the Plans tab to auto-open a specific plan. PlansScreen
     *  observes this and routes through its normal onPlanClick path so the
     *  full plan-detail wiring fires; cleared after consumption. */
    val pendingOpenPlanId: String? = null,
)

class PlansViewModel(
    private val planRepository: PlanRepository,
    private val eventRepository: EventRepository,
    private val venueRepository: VenueRepository,
    private val communityInterestRepository: CommunityInterestRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlansUiState())
    val uiState: StateFlow<PlansUiState> = _uiState.asStateFlow()

    init {
        loadPlans()
        loadSavedItems()
    }

    /** Set the pending-open plan id. Consumed by PlansScreen on observation. */
    fun requestOpenPlan(planId: String?) {
        _uiState.update { it.copy(pendingOpenPlanId = planId) }
    }

    /** Clear the pending-open signal once PlansScreen has acted on it. */
    fun consumePendingOpenPlan() {
        _uiState.update { it.copy(pendingOpenPlanId = null) }
    }

    fun refresh() {
        _uiState.update {
            it.copy(
                isRefreshing = true,
                templateRotationKey = it.templateRotationKey + 1,
            )
        }
        loadPlans()
        loadSavedItems()
    }

    fun removeSlotFromPlan(planId: String, dayIndex: Int, slotId: String) {
        // Optimistic UI update — remove the slot from the plan in state.
        // Empty days are kept; the UI asks the user if they want to remove them.
        _uiState.update { state ->
            fun updatePlans(plans: List<Plan>) = plans.map { plan ->
                if (plan.id != planId) return@map plan
                val updatedDays = plan.days.mapIndexed { i, day ->
                    if (i != dayIndex) day
                    else day.copy(slots = day.slots.filter { it.id != slotId })
                }
                plan.copy(days = updatedDays)
            }
            state.copy(
                upcomingPlans = updatePlans(state.upcomingPlans),
                pastPlans = updatePlans(state.pastPlans),
            )
        }

        // Persist the updated plan to the DB
        viewModelScope.launch {
            val allPlans = _uiState.value.upcomingPlans + _uiState.value.pastPlans
            val updatedPlan = allPlans.find { it.id == planId } ?: return@launch
            planRepository.updatePlan(updatedPlan)
        }
    }

    /** Remove an entire day from the plan. */
    fun removeDayFromPlan(planId: String, dayIndex: Int) {
        _uiState.update { state ->
            fun updatePlans(plans: List<Plan>) = plans.map { plan ->
                if (plan.id != planId) return@map plan
                plan.copy(days = plan.days.filterIndexed { i, _ -> i != dayIndex })
            }
            state.copy(
                upcomingPlans = updatePlans(state.upcomingPlans),
                pastPlans = updatePlans(state.pastPlans),
            )
        }
        viewModelScope.launch {
            val allPlans = _uiState.value.upcomingPlans + _uiState.value.pastPlans
            val updatedPlan = allPlans.find { it.id == planId } ?: return@launch
            planRepository.updatePlan(updatedPlan)
        }
    }

    /** Add a blank day to the plan. */
    fun addDayToPlan(planId: String) {
        _uiState.update { state ->
            fun updatePlans(plans: List<Plan>) = plans.map { plan ->
                if (plan.id != planId) return@map plan
                val nextIndex = plan.days.size + 1
                val label = "Day $nextIndex"
                val shortName = "Day $nextIndex"
                val newDay = live.localapp.local.domain.model.PlanDay(label = label, shortName = shortName, slots = emptyList())
                plan.copy(days = plan.days + newDay)
            }
            state.copy(
                upcomingPlans = updatePlans(state.upcomingPlans),
                pastPlans = updatePlans(state.pastPlans),
            )
        }
        viewModelScope.launch {
            val allPlans = _uiState.value.upcomingPlans + _uiState.value.pastPlans
            val updatedPlan = allPlans.find { it.id == planId } ?: return@launch
            planRepository.updatePlan(updatedPlan)
        }
    }

    /** Toggle a slot's reservation-confirmed flag (creator-only, set via the per-stop overflow). */
    fun setSlotReserved(planId: String, dayIndex: Int, slotId: String, reserved: Boolean) {
        _uiState.update { state ->
            fun updatePlans(plans: List<Plan>) = plans.map { plan ->
                if (plan.id != planId) return@map plan
                val updatedDays = plan.days.mapIndexed { i, day ->
                    if (i != dayIndex) day
                    else day.copy(slots = day.slots.map { s ->
                        if (s.id == slotId) s.copy(
                            reservationState = if (reserved) live.localapp.local.domain.model.ReservationState.MADE
                                else live.localapp.local.domain.model.ReservationState.NONE,
                        ) else s
                    })
                }
                plan.copy(days = updatedDays)
            }
            state.copy(
                upcomingPlans = updatePlans(state.upcomingPlans),
                pastPlans = updatePlans(state.pastPlans),
            )
        }
        viewModelScope.launch {
            val allPlans = _uiState.value.upcomingPlans + _uiState.value.pastPlans
            val updatedPlan = allPlans.find { it.id == planId } ?: return@launch
            planRepository.updatePlan(updatedPlan)
        }
    }

    /**
     * Save reservation details from the Reservation Details Sheet.
     * Promotes the slot to ReservationState.MADE with party_size + reservation_time.
     * Per spec §3.1.4 — if reservation_time differs from slot.time, [time] is updated
     * to the new reservation time so the day re-sorts correctly. Caller is responsible
     * for surfacing any overlap warning that results.
     */
    fun setReservationDetails(planId: String, dayIndex: Int, slotId: String, partySize: Int, reservationTime: String) {
        mutateSlot(planId, dayIndex, slotId) { s ->
            val newTime = if (reservationTime.isNotBlank() && reservationTime != s.time) reservationTime else s.time
            s.copy(
                reservationState = live.localapp.local.domain.model.ReservationState.MADE,
                reservationPartySize = partySize,
                reservationTime = reservationTime,
                ticketState = live.localapp.local.domain.model.TicketState.NONE,
                ticketCount = null,
                time = newTime,
            )
        }
    }

    /** Mark a restaurant slot as `No reservations available` (State C). Triggers Swap flow at the UI layer. */
    fun setReservationUnavailable(planId: String, dayIndex: Int, slotId: String) {
        mutateSlot(planId, dayIndex, slotId) { s ->
            s.copy(
                reservationState = live.localapp.local.domain.model.ReservationState.UNAVAILABLE,
                reservationPartySize = null,
                reservationTime = null,
            )
        }
    }

    /** Clear reservation — return to State A. */
    fun clearReservation(planId: String, dayIndex: Int, slotId: String) {
        mutateSlot(planId, dayIndex, slotId) { s ->
            s.copy(
                reservationState = live.localapp.local.domain.model.ReservationState.NONE,
                reservationPartySize = null,
                reservationTime = null,
            )
        }
    }

    /** Save ticket details from the Ticket Details Sheet. State B (BOUGHT) with ticket_count. */
    fun setTicketDetails(planId: String, dayIndex: Int, slotId: String, ticketCount: Int) {
        mutateSlot(planId, dayIndex, slotId) { s ->
            s.copy(
                ticketState = live.localapp.local.domain.model.TicketState.BOUGHT,
                ticketCount = ticketCount,
                reservationState = live.localapp.local.domain.model.ReservationState.NONE,
                reservationPartySize = null,
                reservationTime = null,
            )
        }
    }

    /** Mark a ticketed slot as `No tickets available` (State C). Triggers Swap flow at the UI layer. */
    fun setTicketsUnavailable(planId: String, dayIndex: Int, slotId: String) {
        mutateSlot(planId, dayIndex, slotId) { s ->
            s.copy(
                ticketState = live.localapp.local.domain.model.TicketState.UNAVAILABLE,
                ticketCount = null,
            )
        }
    }

    /** Clear tickets — return to State A. */
    fun clearTickets(planId: String, dayIndex: Int, slotId: String) {
        mutateSlot(planId, dayIndex, slotId) { s ->
            s.copy(
                ticketState = live.localapp.local.domain.model.TicketState.NONE,
                ticketCount = null,
            )
        }
    }

    /** Apply a per-slot mutation, persist to the backend. Re-sorts the day by time
     *  when the slot's `time` field changes (handles the reservation time-update cascade). */
    private fun mutateSlot(
        planId: String,
        dayIndex: Int,
        slotId: String,
        block: (live.localapp.local.domain.model.PlanSlot) -> live.localapp.local.domain.model.PlanSlot,
    ) {
        _uiState.update { state ->
            fun updatePlans(plans: List<Plan>) = plans.map { plan ->
                if (plan.id != planId) return@map plan
                val updatedDays = plan.days.mapIndexed { i, day ->
                    if (i != dayIndex) day
                    else {
                        val updatedSlots = day.slots.map { if (it.id == slotId) block(it) else it }
                            .sortedBy { parseSlotTimeMinutes(it.time) } // chronological — see [parseSlotTimeMinutes]
                        day.copy(slots = updatedSlots)
                    }
                }
                plan.copy(days = updatedDays)
            }
            state.copy(
                upcomingPlans = updatePlans(state.upcomingPlans),
                pastPlans = updatePlans(state.pastPlans),
            )
        }
        viewModelScope.launch {
            val allPlans = _uiState.value.upcomingPlans + _uiState.value.pastPlans
            val updatedPlan = allPlans.find { it.id == planId } ?: return@launch
            planRepository.updatePlan(updatedPlan)
        }
    }

    /**
     * Swap an existing slot in place. Preserves position. For restaurants,
     * the original time is kept (per spec §3.4 — diners want their reserved
     * time slot). For events, the alternative event's actual start time is used.
     */
    fun swapSlot(
        planId: String,
        dayIndex: Int,
        oldSlotId: String,
        newSlot: live.localapp.local.domain.model.PlanSlot,
    ) {
        _uiState.update { state ->
            fun updatePlans(plans: List<Plan>) = plans.map { plan ->
                if (plan.id != planId) return@map plan
                val updatedDays = plan.days.mapIndexed { i, day ->
                    if (i != dayIndex) day
                    else {
                        val replaced = day.slots.map { existing ->
                            if (existing.id != oldSlotId) existing
                            else newSlot.copy(id = newSlot.id.ifBlank { existing.id })
                        }
                        day.copy(slots = replaced.sortedBy { parseSlotTimeMinutes(it.time) })
                    }
                }
                plan.copy(days = updatedDays)
            }
            state.copy(
                upcomingPlans = updatePlans(state.upcomingPlans),
                pastPlans = updatePlans(state.pastPlans),
            )
        }
        viewModelScope.launch {
            val allPlans = _uiState.value.upcomingPlans + _uiState.value.pastPlans
            val updatedPlan = allPlans.find { it.id == planId } ?: return@launch
            planRepository.updatePlan(updatedPlan)
        }
    }

    /** Add a slot to a specific day. */
    fun addSlotToDay(planId: String, dayIndex: Int, slot: live.localapp.local.domain.model.PlanSlot) {
        _uiState.update { state ->
            fun updatePlans(plans: List<Plan>) = plans.map { plan ->
                if (plan.id != planId) return@map plan
                val updatedDays = plan.days.mapIndexed { i, day ->
                    if (i != dayIndex) day
                    else day.copy(slots = day.slots + slot)
                }
                plan.copy(days = updatedDays)
            }
            state.copy(
                upcomingPlans = updatePlans(state.upcomingPlans),
                pastPlans = updatePlans(state.pastPlans),
            )
        }
        viewModelScope.launch {
            val allPlans = _uiState.value.upcomingPlans + _uiState.value.pastPlans
            val updatedPlan = allPlans.find { it.id == planId } ?: return@launch
            planRepository.updatePlan(updatedPlan)
        }
    }

    fun addPendingInvite(plan: Plan) {
        _uiState.update { state ->
            // Don't add duplicates
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
            // Record the accept via RPC (awards points to creator, prevents duplicates)
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
                // Badge: INVITE_ACCEPTED for the accepting user
                if (result.success) {
                    val badgeService = org.koin.mp.KoinPlatform.getKoin().get<live.localapp.local.domain.service.BadgeService>()
                    badgeService.recordAction(live.localapp.local.domain.model.BadgeAction.INVITE_ACCEPTED, "global")
                    // Check group thresholds
                    if (result.acceptCount >= 5) {
                        badgeService.recordAction(live.localapp.local.domain.model.BadgeAction.GROUP_5_CONFIRMED, "global")
                    }
                    if (result.acceptCount >= 10) {
                        badgeService.recordAction(live.localapp.local.domain.model.BadgeAction.GROUP_10_CONFIRMED, "global")
                    }
                }
            }

            // Save a copy to the user's plans
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

    /** Update group size: optimistic local state + DB persist. */
    fun setGroupSize(planId: String, size: Int) {
        _uiState.update { state ->
            fun updatePlans(plans: List<Plan>) = plans.map { p ->
                if (p.id == planId) p.copy(groupSize = size) else p
            }
            state.copy(
                upcomingPlans = updatePlans(state.upcomingPlans),
                pastPlans = updatePlans(state.pastPlans),
            )
        }
        viewModelScope.launch { planRepository.updateGroupSize(planId, size) }
    }

    /** Update plan name: optimistic local state + DB persist. */
    fun setPlanName(planId: String, newName: String) {
        _uiState.update { state ->
            fun updatePlans(plans: List<Plan>) = plans.map { p ->
                if (p.id == planId) p.copy(title = newName) else p
            }
            state.copy(
                upcomingPlans = updatePlans(state.upcomingPlans),
                pastPlans = updatePlans(state.pastPlans),
            )
        }
        viewModelScope.launch { planRepository.updatePlanName(planId, newName) }
    }

    /** Toggle whether attendees can edit: optimistic local state + DB persist. */
    fun setAttendeesCanEdit(planId: String, canEdit: Boolean) {
        _uiState.update { state ->
            fun updatePlans(plans: List<Plan>) = plans.map { p ->
                if (p.id == planId) p.copy(attendeesCanEdit = canEdit) else p
            }
            state.copy(
                upcomingPlans = updatePlans(state.upcomingPlans),
                pastPlans = updatePlans(state.pastPlans),
            )
        }
        viewModelScope.launch { planRepository.updateAttendeesCanEdit(planId, canEdit) }
    }

    fun deletePlan(planId: String) {
        viewModelScope.launch {
            planRepository.deletePlan(planId)
            // Optimistic remove from UI
            _uiState.update { state ->
                state.copy(
                    upcomingPlans = state.upcomingPlans.filter { it.id != planId },
                    pastPlans = state.pastPlans.filter { it.id != planId },
                )
            }
        }
    }

    private fun loadPlans() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val rawPlans = planRepository.getPlans()
            // Backfill venueCity on slots that have an eventId but no city
            // — happens for plans saved before PlanSlotDto added the field.
            // Without this, the plan-detail subtitle row stays blank
            // ("KIMCHILI KOREAN RESTAURANT" with no city beneath it).
            val plans = backfillSlotVenueCities(rawPlans)
            _uiState.update {
                it.copy(
                    upcomingPlans = plans.filter { p -> !p.isPast }.sortedBy { p -> p.createdAt },
                    pastPlans = plans.filter { p -> p.isPast }.sortedByDescending { p -> p.createdAt },
                    isLoading = false,
                    isRefreshing = false,
                )
            }
        }
    }

    /**
     * Reads `event.venueCity` for any slot missing it and stamps the slot.
     * Single batched eventRepository call so the cost is one round trip
     * regardless of how many plans / slots are missing the field.
     * Slots without an `eventId` (venue stops) are left alone — the city
     * for those is captured at venue lookup time, not from event rows.
     */
    private suspend fun backfillSlotVenueCities(plans: List<live.localapp.local.domain.model.Plan>): List<live.localapp.local.domain.model.Plan> {
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

    private fun loadSavedItems() {
        viewModelScope.launch {
            try {
                val userId = userPreferencesRepository.getLocalUserId()
                println("PlansVM.loadSavedItems: userId=$userId")
                if (userId == null) return@launch

                val eventIds = communityInterestRepository.getFavoriteEventIds(userId)
                val venueIds = communityInterestRepository.getFavoriteVenueIds(userId)
                println("PlansVM.loadSavedItems: ${eventIds.size} fav events, ${venueIds.size} fav venues")

                val now = kotlin.time.Clock.System.now().toString()
                val events = if (eventIds.isNotEmpty()) {
                    eventRepository.getEventsByIds(eventIds).filter {
                        val startAt = it.startAt ?: return@filter true // keep always-on
                        startAt >= now
                    }
                } else emptyList()
                val venues = if (venueIds.isNotEmpty()) venueRepository.getVenuesByIds(venueIds) else emptyList()
                println("PlansVM.loadSavedItems: loaded ${events.size} events, ${venues.size} venues")

                _uiState.update {
                    it.copy(
                        savedEvents = events,
                        savedVenues = venues,
                    )
                }
            } catch (e: Throwable) {
                println("PlansVM.loadSavedItems error: ${e::class.simpleName}: ${e.message}")
            }
        }
    }
}

/**
 * Parse a slot's `time` field (e.g. "7:00 PM", "8 PM", "19:00") into total
 * minutes since midnight so day-sort comparisons are chronological. String
 * comparison is wrong — "11:23 PM" sorts before "6:30 PM" alphabetically.
 * Returns Int.MAX_VALUE for unparseable strings so they sink to the end.
 */
private fun parseSlotTimeMinutes(s: String): Int {
    if (s.isBlank()) return Int.MAX_VALUE
    val cleaned = s.trim().uppercase()
    val isPm = cleaned.contains("PM")
    val isAm = cleaned.contains("AM")
    val withoutMeridiem = cleaned
        .replace("PM", "")
        .replace("AM", "")
        .replace(".", "")
        .trim()
    val parts = withoutMeridiem.split(":")
    val hour = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return Int.MAX_VALUE
    val minute = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
    val h24 = when {
        isPm && hour < 12 -> hour + 12
        isAm && hour == 12 -> 0
        else -> hour
    }
    return h24 * 60 + minute
}
