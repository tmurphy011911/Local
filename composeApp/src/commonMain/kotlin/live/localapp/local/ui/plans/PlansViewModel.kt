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
    val expandedPlanId: String? = null,
    val isLoading: Boolean = false,
    val savedEvents: List<Event> = emptyList(),
    val savedVenues: List<Venue> = emptyList(),
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

    fun toggleExpand(planId: String) {
        _uiState.update { state ->
            state.copy(expandedPlanId = if (state.expandedPlanId == planId) null else planId)
        }
    }

    fun refresh() {
        loadPlans()
        loadSavedItems()
    }

    fun removeSlotFromPlan(planId: String, dayIndex: Int, slotId: String) {
        // Optimistic UI update — remove the slot from the plan in state
        _uiState.update { state ->
            fun updatePlans(plans: List<Plan>) = plans.map { plan ->
                if (plan.id != planId) return@map plan
                val updatedDays = plan.days.mapIndexed { i, day ->
                    if (i != dayIndex) day
                    else day.copy(slots = day.slots.filter { it.id != slotId })
                }.filter { it.slots.isNotEmpty() } // remove empty days
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
            val plans = planRepository.getPlans()
            _uiState.update {
                it.copy(
                    upcomingPlans = plans.filter { p -> !p.isPast }.sortedBy { p -> p.createdAt },
                    pastPlans = plans.filter { p -> p.isPast }.sortedByDescending { p -> p.createdAt },
                    isLoading = false,
                )
            }
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
