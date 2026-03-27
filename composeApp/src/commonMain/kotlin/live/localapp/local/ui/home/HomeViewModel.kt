package live.localapp.local.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import live.localapp.local.domain.model.CarouselSection
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.repository.CarouselRepository
import live.localapp.local.domain.repository.CategoryInfo
import live.localapp.local.domain.repository.CommunityInterestRepository
import live.localapp.local.domain.repository.EventRepository
import live.localapp.local.domain.repository.InteractionType
import live.localapp.local.domain.model.BadgeAction
import live.localapp.local.domain.repository.UserPreferences
import live.localapp.local.domain.repository.UserPreferencesRepository
import live.localapp.local.domain.service.BadgeService

data class HomeUiState(
    val userName: String = "",
    val locationName: String = "Waltham, MA",
    val userLat: Double = 42.3897,
    val userLng: Double = -71.2358,
    val streakDays: Int = 2,
    val selectedCategory: String = "foryou",
    val availableCategories: List<CategoryInfo> = emptyList(),
    val carousels: List<CarouselSection> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val userPreferences: UserPreferences? = null,
    val radiusFilter: Double? = null,
    val savedThisWeek: List<Event> = emptyList(),
    val favoriteEventIds: Set<String> = emptySet(),
)

class HomeViewModel(
    private val carouselRepository: CarouselRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val communityInterestRepository: CommunityInterestRepository,
    private val eventRepository: EventRepository,
    private val badgeService: BadgeService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadUserPreferencesThenCarousels()
        loadSavedThisWeek()
    }

    private fun loadUserPreferencesThenCarousels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            // Try local cache first, recover from Supabase if lost
            val userId = userPreferencesRepository.getLocalUserId()
                ?: userPreferencesRepository.recoverProfile()
            if (userId != null) {
                val prefs = userPreferencesRepository.loadPreferences(userId)
                if (prefs != null) {
                    _uiState.update {
                        it.copy(
                            userName = prefs.firstName ?: "",
                            userLat = prefs.userLat ?: it.userLat,
                            userLng = prefs.userLng ?: it.userLng,
                            userPreferences = prefs,
                        )
                    }
                }
            }
            // Load carousels once, with prefs already set
            loadCarousels()
        }
    }

    fun setUserLocation(lat: Double, lng: Double) {
        val current = _uiState.value
        if (current.userLat != lat || current.userLng != lng) {
            _uiState.update { it.copy(userLat = lat, userLng = lng) }
            carouselRepository.invalidateAll()
            loadCarousels()
        }
    }

    fun debugSetLocation(lat: Double, lng: Double, name: String) {
        _uiState.update { it.copy(userLat = lat, userLng = lng, locationName = name) }
        carouselRepository.invalidateAll()
        loadCarousels()
    }

    fun selectCategory(category: String) {
        _uiState.update { it.copy(selectedCategory = category) }
        loadCarousels(loadCategories = false)
    }

    fun recordEventClick(eventId: String, category: String? = null) {
        communityInterestRepository.recordClick(eventId, InteractionType.EVENT, category)
    }

    fun recordEventShare(eventId: String, category: String? = null) {
        communityInterestRepository.recordShare(eventId, InteractionType.EVENT, category)
        badgeService.recordAction(BadgeAction.EVENT_SHARED, deriveUserState())
    }

    fun toggleEventFavorite(eventId: String, category: String? = null) {
        val isFav = eventId in _uiState.value.favoriteEventIds
        if (isFav) {
            communityInterestRepository.removeFavorite(eventId, InteractionType.EVENT)
            _uiState.update { it.copy(favoriteEventIds = it.favoriteEventIds - eventId) }
        } else {
            communityInterestRepository.recordFavorite(eventId, InteractionType.EVENT, category)
            _uiState.update { it.copy(favoriteEventIds = it.favoriteEventIds + eventId) }
            // Badge: track event saved
            badgeService.recordAction(BadgeAction.EVENT_SAVED, deriveUserState())
        }
    }

    fun recordEventFavorite(eventId: String, category: String? = null) {
        communityInterestRepository.recordFavorite(eventId, InteractionType.EVENT, category)
        _uiState.update { it.copy(favoriteEventIds = it.favoriteEventIds + eventId) }
    }

    fun recordPlaceShare(placeId: String) {
        communityInterestRepository.recordShare(placeId, InteractionType.PLACE)
    }

    fun setRadiusFilter(miles: Double?) {
        _uiState.update { it.copy(radiusFilter = miles) }
    }

    /** Derive the US state from the user's city preference. */
    private fun deriveUserState(): String {
        val city = _uiState.value.userPreferences?.city ?: "boston"
        return cityToState(city)
    }

    private fun cityToState(city: String): String = when (city.lowercase().trim()) {
        "boston", "cambridge", "somerville", "newton", "waltham",
        "brookline", "worcester", "springfield", "lowell" -> "MA"
        "portland", "south portland", "scarborough" -> "ME"
        "new york", "brooklyn", "manhattan", "queens" -> "NY"
        "providence", "warwick", "cranston" -> "RI"
        "burlington", "montpelier" -> "VT"
        "portsmouth", "concord", "nashua", "manchester" -> "NH"
        "hartford", "new haven", "stamford" -> "CT"
        else -> "MA"
    }

    fun refresh() {
        carouselRepository.invalidateCaches()
        _uiState.update { it.copy(isRefreshing = true) }
        loadCarousels()
        loadSavedThisWeek()
    }

    private fun loadSavedThisWeek() {
        viewModelScope.launch {
            try {
                val userId = userPreferencesRepository.getLocalUserId() ?: return@launch
                val eventIds = communityInterestRepository.getFavoriteEventIds(userId)
                _uiState.update { it.copy(favoriteEventIds = eventIds.toSet()) }
                if (eventIds.isEmpty()) return@launch
                val events = eventRepository.getEventsByIds(eventIds)

                val now = kotlinx.datetime.Instant.fromEpochMilliseconds(kotlin.time.Clock.System.now().toEpochMilliseconds())
                val tz = TimeZone.currentSystemDefault()
                val todayEpochDay = now.toLocalDateTime(tz).date.toEpochDays()
                val endEpochDay = todayEpochDay + 7

                val thisWeek = events.filter { event ->
                    val startAt = event.startAt ?: return@filter false
                    try {
                        val eventDate = Instant.parse(startAt).toLocalDateTime(tz).date
                        val eventEpochDay = eventDate.toEpochDays()
                        eventEpochDay in todayEpochDay..endEpochDay
                    } catch (_: Exception) {
                        false
                    }
                }

                _uiState.update { it.copy(savedThisWeek = thisWeek.sortedBy { it.startAt ?: "" }) }
            } catch (_: Exception) {
                // Non-fatal
            }
        }
    }

    private fun loadCarousels(loadCategories: Boolean = true) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val state = _uiState.value
                val category = state.selectedCategory

                val categories = if (loadCategories || state.availableCategories.isEmpty()) {
                    carouselRepository.getAvailableCategories(
                        userPreferences = state.userPreferences,
                        userLat = state.userLat,
                        userLng = state.userLng,
                    )
                } else {
                    state.availableCategories
                }

                val carousels = carouselRepository.getCarousels(
                    userLat = state.userLat,
                    userLng = state.userLng,
                    category = if (category == "foryou") null else category,
                    userPreferences = state.userPreferences,
                    locationName = state.locationName,
                )
                _uiState.update {
                    it.copy(
                        availableCategories = categories,
                        carousels = carousels,
                        isLoading = false,
                        isRefreshing = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false, isRefreshing = false) }
            }
        }
    }
}
