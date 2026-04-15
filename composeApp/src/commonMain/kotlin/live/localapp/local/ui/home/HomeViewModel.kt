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
import live.localapp.local.domain.repository.VenueRepository
import live.localapp.local.domain.service.BadgeService

data class HomeUiState(
    val userName: String = "",
    val locationName: String = "",
    val userLat: Double? = null,
    val userLng: Double? = null,
    val streakDays: Int = 0,
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
    val locationMode: LocationMode = LocationMode.CURRENT_LOCATION,
    val manualLocationName: String = "",
    val manualLat: Double? = null,
    val manualLng: Double? = null,
    val hasLocationPermission: Boolean = true,
)

enum class LocationMode { CURRENT_LOCATION, MANUAL }

class HomeViewModel(
    private val carouselRepository: CarouselRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val communityInterestRepository: CommunityInterestRepository,
    private val eventRepository: EventRepository,
    private val badgeService: BadgeService,
    private val venueRepo: VenueRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // Record app open and update streak
        val streak = (userPreferencesRepository as? live.localapp.local.data.repository.UserPreferencesRepositoryImpl)
            ?.recordAppOpenAndGetStreak() ?: 0
        _uiState.update { it.copy(streakDays = streak) }

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
                    val cityDisplay = prefs.city.replaceFirstChar { c -> c.uppercase() }.let { city ->
                        // Append state abbreviation if known
                        val state = when (city.lowercase()) {
                            "boston", "cambridge", "somerville", "newton", "waltham",
                            "brookline", "worcester", "springfield", "lowell" -> "MA"
                            "providence" -> "RI"
                            "portland" -> "ME"
                            "new york" -> "NY"
                            "philadelphia", "pittsburgh", "allentown", "harrisburg" -> "PA"
                            else -> null
                        }
                        if (state != null) "$city, $state" else city
                    }
                    val hasPermission = live.localapp.local.platform.checkLocationPermissionGranted()
                    val storedMode = if (prefs.locationMode == "manual") LocationMode.MANUAL else LocationMode.CURRENT_LOCATION
                    // If stored as "current" but we don't have permission, treat as manual
                    val mode = if (storedMode == LocationMode.CURRENT_LOCATION && !hasPermission) LocationMode.MANUAL else storedMode
                    val effectiveLat = if (mode == LocationMode.MANUAL) prefs.manualLat ?: prefs.userLat else prefs.userLat
                    val effectiveLng = if (mode == LocationMode.MANUAL) prefs.manualLng ?: prefs.userLng else prefs.userLng
                    val effectiveName = if (mode == LocationMode.MANUAL && !prefs.manualLocationName.isNullOrBlank()) prefs.manualLocationName else cityDisplay
                    _uiState.update {
                        it.copy(
                            userName = prefs.firstName ?: "",
                            userLat = effectiveLat ?: it.userLat,
                            userLng = effectiveLng ?: it.userLng,
                            locationName = if (it.locationName.isBlank()) effectiveName else it.locationName,
                            userPreferences = prefs,
                            locationMode = mode,
                            hasLocationPermission = hasPermission,
                            manualLocationName = prefs.manualLocationName ?: if (mode == LocationMode.MANUAL) cityDisplay else "",
                            manualLat = prefs.manualLat,
                            manualLng = prefs.manualLng,
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
            // Derive location name from preferences city, or keep current
            val prefs = current.userPreferences
            val locName = if (current.locationName.isBlank() && prefs != null) {
                val city = prefs.city.replaceFirstChar { c -> c.uppercase() }
                val state = when (city.lowercase()) {
                    "boston", "cambridge", "somerville", "newton", "waltham",
                    "brookline", "worcester", "springfield", "lowell" -> "MA"
                    "providence" -> "RI"; "portland" -> "ME"; "new york" -> "NY"
                    "philadelphia", "pittsburgh", "allentown", "harrisburg" -> "PA"
                    else -> null
                }
                if (state != null) "$city, $state" else city
            } else current.locationName
            _uiState.update { it.copy(userLat = lat, userLng = lng, locationName = locName) }
            carouselRepository.invalidateAll()
            loadCarousels()
        }
    }

    fun setLocationMode(mode: LocationMode) {
        val current = _uiState.value
        _uiState.update { it.copy(locationMode = mode) }
        if (mode == LocationMode.MANUAL && current.manualLat != null && current.manualLng != null) {
            _uiState.update { it.copy(userLat = current.manualLat, userLng = current.manualLng, locationName = current.manualLocationName) }
            carouselRepository.invalidateAll()
            loadCarousels()
        }
        saveLocationPrefs()
    }

    fun setManualLocation(lat: Double, lng: Double, name: String) {
        val displayName = name.removeSuffix(", USA").removeSuffix(", United States")
        _uiState.update {
            it.copy(
                manualLat = lat, manualLng = lng, manualLocationName = displayName,
                userLat = lat, userLng = lng, locationName = displayName,
                locationMode = LocationMode.MANUAL,
            )
        }
        carouselRepository.invalidateAll()
        loadCarousels()
        saveLocationPrefs()
    }

    fun setHasLocationPermission(granted: Boolean) {
        _uiState.update { it.copy(hasLocationPermission = granted) }
    }

    private fun saveLocationPrefs() {
        viewModelScope.launch {
            val state = _uiState.value
            val prefs = state.userPreferences ?: return@launch
            val updated = prefs.copy(
                locationMode = if (state.locationMode == LocationMode.MANUAL) "manual" else "current",
                manualLocationName = state.manualLocationName,
                manualLat = state.manualLat,
                manualLng = state.manualLng,
            )
            val userId = userPreferencesRepository.getLocalUserId() ?: return@launch
            try { userPreferencesRepository.savePreferencesWithId(userId, updated) } catch (_: Exception) {}
        }
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

    fun setUserName(name: String) {
        _uiState.update { it.copy(userName = name) }
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
                val rawEvents = eventRepository.getEventsByIds(eventIds)

                // Enrich events missing images with venue photos
                val venueNames = rawEvents.filter { it.imageUrl == null }.mapNotNull { it.venueName }.distinct()
                val venuePhotos = mutableMapOf<String, String>()
                for (name in venueNames) {
                    try {
                        val venue = venueRepo.findVenueByNameAndCity(name, rawEvents.firstOrNull { it.venueName == name }?.venueCity ?: "Boston")
                        if (venue?.photoUrl != null) venuePhotos[name] = venue.photoUrl
                    } catch (_: Throwable) {}
                }
                val events = rawEvents.map { event ->
                    if (event.imageUrl == null && event.venueName != null) {
                        val photo = venuePhotos[event.venueName]
                        if (photo != null) event.copy(imageUrl = photo) else event
                    } else event
                }

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

                val lat = state.userLat ?: state.userPreferences?.userLat ?: 42.3601
                val lng = state.userLng ?: state.userPreferences?.userLng ?: -71.0589

                val categories = if (loadCategories || state.availableCategories.isEmpty()) {
                    carouselRepository.getAvailableCategories(
                        userPreferences = state.userPreferences,
                        userLat = lat,
                        userLng = lng,
                    )
                } else {
                    state.availableCategories
                }

                val carousels = carouselRepository.getCarousels(
                    userLat = lat,
                    userLng = lng,
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
                // Auto-retry once after a short delay (handles race on first load after onboarding)
                if (_uiState.value.carousels.isEmpty()) {
                    kotlinx.coroutines.delay(1500)
                    try {
                        val s = _uiState.value
                        val retryLat = s.userLat ?: s.userPreferences?.userLat ?: 42.3601
                        val retryLng = s.userLng ?: s.userPreferences?.userLng ?: -71.0589
                        val cats = carouselRepository.getAvailableCategories(s.userPreferences, retryLat, retryLng)
                        val carousels = carouselRepository.getCarousels(retryLat, retryLng, if (s.selectedCategory == "foryou") null else s.selectedCategory, s.userPreferences, s.locationName)
                        _uiState.update { it.copy(availableCategories = cats, carousels = carousels, isLoading = false, isRefreshing = false, error = null) }
                        return@launch
                    } catch (_: Exception) {}
                }
                _uiState.update { it.copy(error = e.message, isLoading = false, isRefreshing = false) }
            }
        }
    }
}
