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
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import live.localapp.local.Config

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
    val isUnsupportedState: Boolean = false,
    // City name reverse-geocoded from GPS lat/lng. Separate from locationName so
    // the Current Location row in the settings sheet can show the true GPS city
    // without getting mixed up with the manual address name.
    val gpsLocationName: String = "",
    // Location-specific search prompts for the Home typewriter. Fetched once
    // per location from the yocal-prompts edge function. Empty until loaded;
    // the card falls back to FALLBACK_YOCAL_PROMPTS in the meantime.
    val yocalPrompts: List<String> = emptyList(),
)

enum class LocationMode { CURRENT_LOCATION, MANUAL }

// Bounding boxes for supported states (approximate)
private data class StateBounds(val code: String, val latMin: Double, val latMax: Double, val lngMin: Double, val lngMax: Double)
private val SUPPORTED_STATE_BOUNDS = listOf(
    StateBounds("MA", 41.2, 42.9, -73.5, -69.9),
    StateBounds("RI", 41.1, 42.1, -72.0, -71.1),
    StateBounds("PA", 39.7, 42.3, -80.6, -74.6),
)

fun isLocationInSupportedState(lat: Double?, lng: Double?): Boolean {
    if (lat == null || lng == null) return true // assume supported if no location
    return SUPPORTED_STATE_BOUNDS.any { s ->
        lat >= s.latMin && lat <= s.latMax && lng >= s.lngMin && lng <= s.lngMax
    }
}

class HomeViewModel(
    private val carouselRepository: CarouselRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val communityInterestRepository: CommunityInterestRepository,
    private val eventRepository: EventRepository,
    private val badgeService: BadgeService,
    private val venueRepo: VenueRepository,
    private val yocalPromptsRepository: live.localapp.local.domain.repository.YocalPromptsRepository,
) : ViewModel() {

    private var lastYocalPromptsGrid: String? = null
    private var yocalPromptsFetchJob: kotlinx.coroutines.Job? = null

    /** Fetch typewriter prompts for the user's grid (rounded 0.1°). Cached per
     *  grid in-memory; the edge function caches per grid for 24h. Retries with
     *  backoff so a single 502 doesn't leave the home card on the fallback for
     *  the whole session. Falls through silently — UI degrades to hardcoded. */
    private fun fetchYocalPromptsIfNeeded(lat: Double?, lng: Double?) {
        if (lat == null || lng == null) return
        val grid = "${kotlin.math.round(lat * 10) / 10},${kotlin.math.round(lng * 10) / 10}"
        if (grid == lastYocalPromptsGrid && _uiState.value.yocalPrompts.isNotEmpty()) return
        yocalPromptsFetchJob?.cancel()
        yocalPromptsFetchJob = viewModelScope.launch {
            val backoffs = listOf(0L, 3000L, 8000L, 20000L)
            for ((i, delayMs) in backoffs.withIndex()) {
                if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
                val prompts = try { yocalPromptsRepository.getPrompts(lat, lng) } catch (_: Throwable) { emptyList() }
                if (prompts.isNotEmpty()) {
                    lastYocalPromptsGrid = grid
                    _uiState.update { it.copy(yocalPrompts = prompts) }
                    return@launch
                }
                if (i == backoffs.lastIndex) {
                    println("[YocalPrompts] gave up after ${backoffs.size} attempts — falling back to hardcoded")
                }
            }
        }
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var carouselJob: kotlinx.coroutines.Job? = null

    val reviewPromptService = live.localapp.local.domain.service.ReviewPromptService()

    init {
        // Record app open and update streak
        val streak = (userPreferencesRepository as? live.localapp.local.data.repository.UserPreferencesRepositoryImpl)
            ?.recordAppOpenAndGetStreak() ?: 0
        _uiState.update { it.copy(streakDays = streak) }
        reviewPromptService.recordAppOpen()

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
                    val cityDisplay = prefs.city.replaceFirstChar { c -> c.uppercase() }
                    val hasPermission = live.localapp.local.platform.checkLocationPermissionGranted()
                    val storedMode = if (prefs.locationMode == "manual") LocationMode.MANUAL else LocationMode.CURRENT_LOCATION
                    // If stored as "current" but we don't have permission, treat as manual
                    var mode = if (storedMode == LocationMode.CURRENT_LOCATION && !hasPermission) LocationMode.MANUAL else storedMode
                    // Repair corrupted state: if MANUAL but saved coords are missing (e.g. a
                    // past silent geocoding failure left only the name behind), revert to
                    // CURRENT_LOCATION so the pill label doesn't lie about the coords we
                    // actually query with.
                    val manualCorrupted = mode == LocationMode.MANUAL && (prefs.manualLat == null || prefs.manualLng == null)
                    if (manualCorrupted) mode = LocationMode.CURRENT_LOCATION
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
                            manualLocationName = if (manualCorrupted) "" else (prefs.manualLocationName ?: if (mode == LocationMode.MANUAL) cityDisplay else ""),
                            manualLat = if (manualCorrupted) null else prefs.manualLat,
                            manualLng = if (manualCorrupted) null else prefs.manualLng,
                            isUnsupportedState = !isLocationInSupportedState(effectiveLat ?: prefs.userLat, effectiveLng ?: prefs.userLng),
                        )
                    }
                    // Persist the repair so the next cold open starts clean.
                    if (manualCorrupted) saveLocationPrefs()
                }
            }
            // Load carousels once, with prefs already set
            loadCarousels()
            // Prime typewriter prompts for the current location.
            val s = _uiState.value
            fetchYocalPromptsIfNeeded(s.userLat ?: 42.3601, s.userLng ?: -71.0589)
        }
    }

    fun setUserLocation(lat: Double, lng: Double) {
        val current = _uiState.value
        if (current.userLat != lat || current.userLng != lng) {
            // Derive location name from preferences city, or keep current
            val prefs = current.userPreferences
            val locName = if (current.locationName.isBlank() && prefs != null) {
                prefs.city.replaceFirstChar { c -> c.uppercase() }
            } else current.locationName
            _uiState.update { it.copy(userLat = lat, userLng = lng, locationName = locName, isUnsupportedState = !isLocationInSupportedState(lat, lng)) }
            carouselRepository.invalidateAll()
            loadCarousels()
            fetchYocalPromptsIfNeeded(lat, lng)
            // Fire-and-forget reverse geocode to populate the GPS-specific city name
            // shown in Location Settings. Coarse-rounded key avoids re-fetching on
            // every tiny GPS tick.
            viewModelScope.launch { refreshGpsLocationName(lat, lng) }
        }
    }

    private var lastReverseGeocodeKey: String = ""

    private suspend fun refreshGpsLocationName(lat: Double, lng: Double) {
        // Round to ~1km resolution — avoid re-fetching on tiny GPS ticks.
        val key = "${(lat * 100).toInt()},${(lng * 100).toInt()}"
        if (key == lastReverseGeocodeKey) return
        lastReverseGeocodeKey = key
        val client = HttpClient()
        try {
            val response = client.post("${Config.SUPABASE_URL}/functions/v1/geocode") {
                header("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
                contentType(ContentType.Application.Json)
                setBody("""{"lat":$lat,"lng":$lng}""")
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            val city = json["city"]?.jsonPrimitive?.content.orEmpty()
            val stateCode = json["state"]?.jsonPrimitive?.content.orEmpty()
            val display = if (city.isNotBlank() && stateCode.isNotBlank()) "$city, $stateCode"
                else city
            if (display.isNotBlank()) {
                _uiState.update {
                    it.copy(
                        gpsLocationName = display,
                        locationName = if (it.locationMode == LocationMode.CURRENT_LOCATION) display else it.locationName,
                    )
                }
            }
        } catch (_: Throwable) {
            // Silent — sheet falls back to generic "Using device GPS" subtitle.
        } finally {
            client.close()
        }
    }

    fun setLocationMode(mode: LocationMode) {
        val current = _uiState.value
        _uiState.update { it.copy(locationMode = mode) }
        when (mode) {
            LocationMode.MANUAL -> {
                if (current.manualLat != null && current.manualLng != null) {
                    _uiState.update { it.copy(userLat = current.manualLat, userLng = current.manualLng, locationName = current.manualLocationName) }
                    carouselRepository.invalidateAll()
                    loadCarousels()
                }
            }
            LocationMode.CURRENT_LOCATION -> {
                // Restore the GPS-resolved name immediately so the pill flips back
                // from the manual address. Coords will sync when RequestLocationEffect fires.
                if (current.gpsLocationName.isNotBlank()) {
                    _uiState.update { it.copy(locationName = current.gpsLocationName) }
                }
            }
        }
        saveLocationPrefs()
    }

    fun setManualLocation(lat: Double, lng: Double, name: String) {
        val displayName = name.removeSuffix(", USA").removeSuffix(", United States").replace(Regex(",\\s*[A-Z]{2}$"), "")
        _uiState.update {
            it.copy(
                manualLat = lat, manualLng = lng, manualLocationName = displayName,
                userLat = lat, userLng = lng, locationName = displayName,
                locationMode = LocationMode.MANUAL,
                isUnsupportedState = !isLocationInSupportedState(lat, lng),
            )
        }
        carouselRepository.invalidateAll()
        loadCarousels()
        // Wipe stale prompts and refetch for the new metro.
        _uiState.update { it.copy(yocalPrompts = emptyList()) }
        lastYocalPromptsGrid = null
        fetchYocalPromptsIfNeeded(lat, lng)
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
        reviewPromptService.onPositiveMoment(live.localapp.local.domain.service.ReviewTrigger.EVENT_SHARED)
    }

    fun toggleEventFavorite(eventId: String, category: String? = null) {
        val isFav = eventId in _uiState.value.favoriteEventIds
        if (isFav) {
            communityInterestRepository.removeFavorite(eventId, InteractionType.EVENT)
            _uiState.update {
                it.copy(
                    favoriteEventIds = it.favoriteEventIds - eventId,
                    savedThisWeek = it.savedThisWeek.filterNot { e -> e.id == eventId },
                )
            }
        } else {
            communityInterestRepository.recordFavorite(eventId, InteractionType.EVENT, category)
            _uiState.update { it.copy(favoriteEventIds = it.favoriteEventIds + eventId) }
            // Badge: track event saved
            badgeService.recordAction(BadgeAction.EVENT_SAVED, deriveUserState())
            // Review prompt on 5th save
            if (_uiState.value.favoriteEventIds.size == 5) {
                reviewPromptService.onPositiveMoment(live.localapp.local.domain.service.ReviewTrigger.EVENT_SAVED_5TH)
            }
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
        carouselJob?.cancel()
        carouselJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val state = _uiState.value
                val category = state.selectedCategory

                val lat = state.userLat ?: state.userPreferences?.userLat ?: 42.3601
                val lng = state.userLng ?: state.userPreferences?.userLng ?: -71.0589

                // ONE edge-function call. The carousels response includes categories, so
                // we fetch carousels first then read cachedCategories. Previously we fired
                // getAvailableCategories AND getCarousels in parallel, which caused TWO
                // simultaneous round-trips to the same edge function on cold start.
                val carousels = carouselRepository.getCarousels(
                    userLat = lat,
                    userLng = lng,
                    category = if (category == "foryou") null else category,
                    userPreferences = state.userPreferences,
                    locationName = state.locationName,
                )
                val categories = if (loadCategories || state.availableCategories.isEmpty()) {
                    carouselRepository.getAvailableCategories(
                        userPreferences = state.userPreferences,
                        userLat = lat,
                        userLng = lng,
                    )
                } else state.availableCategories
                // Guard against racing category switches — if the user tapped a new
                // category while our fetch was in flight, the cancel may not have
                // interrupted us in time (no suspension point between the await and
                // this update). Writing state unconditionally was the reason Music's
                // carousels appeared briefly on the For You tab after a quick tap.
                _uiState.update { s ->
                    if (s.selectedCategory != category) s
                    else s.copy(
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
                        val retryCategory = s.selectedCategory
                        val retryLat = s.userLat ?: s.userPreferences?.userLat ?: 42.3601
                        val retryLng = s.userLng ?: s.userPreferences?.userLng ?: -71.0589
                        val carousels = carouselRepository.getCarousels(
                            retryLat, retryLng,
                            if (retryCategory == "foryou") null else retryCategory,
                            s.userPreferences, s.locationName,
                        )
                        val cats = carouselRepository.getAvailableCategories(s.userPreferences, retryLat, retryLng)
                        _uiState.update { cur ->
                            if (cur.selectedCategory != retryCategory) cur
                            else cur.copy(availableCategories = cats, carousels = carousels, isLoading = false, isRefreshing = false, error = null)
                        }
                        return@launch
                    } catch (_: Exception) {}
                }
                _uiState.update { it.copy(error = e.message, isLoading = false, isRefreshing = false) }
            }
        }
    }
}
