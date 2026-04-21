package live.localapp.local.feature.onboarding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SportsScore
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.repository.NeighborhoodInfo
import live.localapp.local.domain.repository.OnboardingRepository
import live.localapp.local.domain.repository.UserPreferences
import live.localapp.local.domain.repository.UserPreferencesRepository
import live.localapp.local.domain.repository.VenueSummary

enum class OnboardingStep {
    SPLASH, LOCATION, MANUAL_ADDRESS, SUCCESS,
    NEIGHBOURHOODS, VENUES, CATEGORIES,
    FAVOURITES, NOTIFICATIONS, PHONE,
}

enum class PhoneState { ENTRY, CHECKING, NEW_USER, RETURNING_COMPLETE, RETURNING_ONBOARDING }

data class SavedEvent(
    val id: String,
    val icon: ImageVector,
    val tag: String,
    val tagColor: Long, // ARGB as Long
    val tagBg: Long,
    val title: String,
    val venueCity: String,
    val meta: String,
    val thumbBgStart: Long,
    val thumbBgEnd: Long,
)

data class OnboardingState(
    val currentStep: OnboardingStep = OnboardingStep.SPLASH,
    val splashComplete: Boolean = false,
    val locationGranted: Boolean = false,
    val userLat: Double? = null,
    val userLng: Double? = null,
    val userCity: String = "",
    val selectedNeighbourhoods: Set<String> = emptySet(),
    val selectedVenues: Set<String> = emptySet(),
    val selectedCategories: Set<String> = emptySet(),
    val isPersonalised: Boolean = false,
    val savedEvents: List<SavedEvent> = emptyList(),
    val firstName: String = "",
    val lastName: String = "",
    val phoneNumber: String = "",
    val phoneState: PhoneState = PhoneState.ENTRY,
    val notifTickets: Boolean = true,
    val notifNearby: Boolean = true,
    val notifVenues: Boolean = true,
    val locationMode: String = "current", // "current" or "manual"
    // Dynamic data from Supabase
    val eventCount: Int? = null,
    val venueCount: Int? = null,
    val neighborhoods: List<NeighborhoodInfo> = emptyList(),
    val venues: List<VenueSummary> = emptyList(),
    val categoryCounts: Map<String, Int> = emptyMap(),
    val categoryPhotos: Map<String, String> = emptyMap(),
    val isLoadingData: Boolean = false,
) {
    /** Tracker fills: 3 segments [Location, Preferences, Notifications] each 0f..1f */
    val trackerFills: List<Float>?
        get() = when (currentStep) {
            OnboardingStep.SPLASH -> null
            OnboardingStep.LOCATION -> listOf(0.5f, 0f, 0f)
            OnboardingStep.MANUAL_ADDRESS -> listOf(0.75f, 0f, 0f)
            OnboardingStep.SUCCESS -> listOf(1f, 0f, 0f)
            OnboardingStep.NEIGHBOURHOODS -> listOf(1f, 0.25f, 0f)
            OnboardingStep.VENUES -> listOf(1f, 0.5f, 0f)
            OnboardingStep.CATEGORIES -> listOf(1f, 0.75f, 0f)
            OnboardingStep.FAVOURITES -> listOf(1f, 1f, 0f)
            OnboardingStep.NOTIFICATIONS -> listOf(1f, 1f, 0.5f)
            OnboardingStep.PHONE -> listOf(1f, 1f, 1f)
        }

    val categoriesValid: Boolean get() = selectedCategories.size >= 2
}

class OnboardingViewModel(
    private val onboardingRepository: OnboardingRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())

    /** Supabase auth user ID, set when user authenticates before onboarding. */
    private var authUserId: String? = null

    fun setAuthUserId(id: String) {
        authUserId = id
        // Skip the splash step since it already played before auth
        _state.update { it.copy(splashComplete = true, currentStep = OnboardingStep.LOCATION) }
    }
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    // ── Navigation ──

    fun onSplashComplete() {
        _state.update { it.copy(splashComplete = true, currentStep = OnboardingStep.LOCATION) }
    }

    /**
     * Called when the OS-level location permission is already granted (e.g. a prior
     * install), so we can skip the ask screen and show the SuccessScreen loader while
     * the actual GPS coordinates arrive. Coords will be wired in via [onAllowLocation].
     */
    fun onLocationPregranted() {
        _state.update {
            it.copy(
                locationGranted = true,
                locationMode = "current",
                currentStep = OnboardingStep.SUCCESS,
            )
        }
    }

    fun onAllowLocation(lat: Double, lng: Double) {
        val city = nearestCity(lat, lng)
        println("onAllowLocation: lat=$lat lng=$lng city=$city")
        _state.update {
            it.copy(
                locationGranted = true,
                userLat = lat,
                userLng = lng,
                userCity = city,
                locationMode = "current",
                currentStep = OnboardingStep.SUCCESS,
            )
        }
        loadOnboardingData()
    }

    fun onDenyLocation() {
        _state.update { it.copy(currentStep = OnboardingStep.MANUAL_ADDRESS) }
    }

    fun onManualAddressSubmit(lat: Double, lng: Double, city: String) {
        val resolvedCity = city.ifBlank { nearestCity(lat, lng) }
        _state.update {
            it.copy(
                userLat = lat,
                userLng = lng,
                userCity = resolvedCity,
                locationMode = "manual",
                currentStep = OnboardingStep.SUCCESS,
            )
        }
        loadOnboardingData()
    }

    fun onSuccessComplete() {
        val s = _state.value
        val nextStep = when {
            s.neighborhoods.size >= 2 -> OnboardingStep.NEIGHBOURHOODS
            s.venues.size >= 10 -> OnboardingStep.VENUES
            s.categoryCounts.size >= 5 -> OnboardingStep.CATEGORIES
            else -> OnboardingStep.NOTIFICATIONS
        }
        _state.update { it.copy(currentStep = nextStep) }
    }

    fun onNeighbourhoodsNext() {
        val s = _state.value
        val nextStep = when {
            s.venues.size >= 10 -> OnboardingStep.VENUES
            s.categoryCounts.size >= 5 -> OnboardingStep.CATEGORIES
            else -> OnboardingStep.NOTIFICATIONS
        }
        _state.update { it.copy(currentStep = nextStep) }
    }

    fun onVenuesNext() {
        val s = _state.value
        val nextStep = if (s.categoryCounts.size >= 5) OnboardingStep.CATEGORIES else OnboardingStep.NOTIFICATIONS
        _state.update { it.copy(currentStep = nextStep) }
    }

    fun onCategoriesNext() {
        _state.update {
            it.copy(
                isPersonalised = true,
                currentStep = OnboardingStep.NOTIFICATIONS,
            )
        }
    }

    fun onSkipAll() {
        _state.update {
            it.copy(
                isPersonalised = false,
                currentStep = OnboardingStep.NOTIFICATIONS,
            )
        }
    }

    fun onSkipVenues() {
        val s = _state.value
        val nextStep = if (s.categoryCounts.size >= 5) OnboardingStep.CATEGORIES else OnboardingStep.NOTIFICATIONS
        _state.update { it.copy(currentStep = nextStep) }
    }

    fun onSkipCategories() {
        _state.update {
            it.copy(
                isPersonalised = false,
                currentStep = OnboardingStep.NOTIFICATIONS,
            )
        }
    }

    fun onFavouritesNext() {
        _state.update { it.copy(currentStep = OnboardingStep.NOTIFICATIONS) }
    }

    fun onNotificationsNext() {
        // Notifications is the last step — onComplete is called from OnboardingScreen
    }

    fun onSkipNotifications() {
        // Notifications is the last step — onComplete is called from OnboardingScreen
    }

    // ── Back navigation ──

    fun onBackFromNeighbourhoods() {
        _state.update { it.copy(currentStep = OnboardingStep.SUCCESS) }
    }

    fun onBackFromManualAddress() {
        _state.update { it.copy(currentStep = OnboardingStep.LOCATION) }
    }

    fun onBackFromVenues() {
        _state.update { it.copy(currentStep = OnboardingStep.NEIGHBOURHOODS) }
    }

    fun onBackFromCategories() {
        _state.update { it.copy(currentStep = OnboardingStep.VENUES) }
    }

    fun onBackFromNotifications() {
        _state.update { it.copy(currentStep = OnboardingStep.CATEGORIES) }
    }

    fun onBackFromPhone() {
        _state.update { it.copy(currentStep = OnboardingStep.NOTIFICATIONS) }
    }

    // ── Selections ──

    fun toggleNeighbourhood(id: String) {
        _state.update {
            val new = it.selectedNeighbourhoods.toMutableSet()
            if (id in new) new.remove(id) else new.add(id)
            it.copy(selectedNeighbourhoods = new)
        }
    }

    fun toggleVenue(id: String) {
        _state.update {
            val new = it.selectedVenues.toMutableSet()
            if (id in new) new.remove(id) else new.add(id)
            it.copy(selectedVenues = new)
        }
    }

    fun toggleCategory(id: String) {
        _state.update {
            val new = it.selectedCategories.toMutableSet()
            if (id in new) new.remove(id) else new.add(id)
            it.copy(selectedCategories = new)
        }
    }

    fun removeEvent(id: String) {
        _state.update { it.copy(savedEvents = it.savedEvents.filter { e -> e.id != id }) }
    }

    fun toggleNotifTickets() {
        _state.update { it.copy(notifTickets = !it.notifTickets) }
    }

    fun toggleNotifNearby() {
        _state.update { it.copy(notifNearby = !it.notifNearby) }
    }

    fun toggleNotifVenues() {
        _state.update { it.copy(notifVenues = !it.notifVenues) }
    }

    /** Save all onboarding selections to Supabase + local cache. */
    fun saveOnboardingPreferences(onSaved: () -> Unit) {
        viewModelScope.launch {
            try {
                val s = _state.value

                // Resolve IDs back to actual names for DB storage
                val neighborhoodNames = s.neighborhoods
                    .filter { it.name.lowercase().replace(" ", "-") in s.selectedNeighbourhoods }
                    .map { it.name }

                val venueNames = s.venues
                    .filter { it.name.lowercase().replace(" ", "-") in s.selectedVenues }
                    .map { it.name }

                val isManual = s.locationMode == "manual"
                val prefs = UserPreferences(
                    id = authUserId ?: "",
                    firstName = s.firstName.trim().ifEmpty { null },
                    lastName = s.lastName.trim().ifEmpty { null },
                    phone = s.phoneNumber.filter { it.isDigit() }.ifEmpty { null },
                    city = s.userCity.lowercase().ifBlank { "boston" },
                    userLat = s.userLat,
                    userLng = s.userLng,
                    selectedNeighborhoods = neighborhoodNames,
                    selectedVenues = venueNames,
                    selectedCategories = s.selectedCategories.toList(),
                    notifTickets = s.notifTickets,
                    notifNearby = s.notifNearby,
                    notifVenues = s.notifVenues,
                    onboardingComplete = true,
                    locationMode = s.locationMode,
                    manualLocationName = if (isManual) s.userCity.replaceFirstChar { it.uppercase() } else null,
                    manualLat = if (isManual) s.userLat else null,
                    manualLng = if (isManual) s.userLng else null,
                )

                // If we have an auth user ID, save with that specific ID
                val uid = authUserId
                if (uid != null) {
                    userPreferencesRepository.savePreferencesWithId(uid, prefs)
                } else {
                    userPreferencesRepository.savePreferences(prefs)
                }
            } catch (e: Exception) {
                // DB save failed (e.g. test user with non-UUID ID) — cache locally so app still works
                println("saveOnboardingPreferences: DB save failed (${e.message}), caching locally")
                try {
                    val s = _state.value
                    userPreferencesRepository.savePreferences(UserPreferences(
                        id = "", // let savePreferences generate a UUID
                        firstName = s.firstName.trim().ifEmpty { null },
                        city = s.userCity.lowercase().ifBlank { "boston" },
                        userLat = s.userLat, userLng = s.userLng,
                        onboardingComplete = true,
                    ))
                } catch (_: Exception) {}
            }
            onSaved()
        }
    }

    fun updateFirstName(value: String) {
        _state.update { it.copy(firstName = value) }
    }

    fun updateLastName(value: String) {
        _state.update { it.copy(lastName = value) }
    }

    fun updatePhoneNumber(value: String) {
        _state.update { it.copy(phoneNumber = value) }
    }

    fun submitPhone() {
        _state.update { it.copy(phoneState = PhoneState.CHECKING) }
    }

    fun onPhoneLookupResult(result: PhoneState) {
        _state.update { it.copy(phoneState = result) }
    }

    // ── Data loading ──

    private fun loadOnboardingData() {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingData = true) }
            try {
                val s = _state.value
                val city = s.userCity.lowercase().ifBlank { "boston" }
                val lat = s.userLat ?: 42.3897
                val lng = s.userLng ?: -71.2358
                println("loadOnboardingData: city=$city lat=$lat lng=$lng userCity='${s.userCity}'")
                val data = onboardingRepository.loadOnboardingData(city, lat, lng)
                println("loadOnboardingData: events=${data.eventCount} venues=${data.venueCount} neighborhoods=${data.neighborhoods.size} categories=${data.categoryCounts.size}")
                _state.update {
                    it.copy(
                        eventCount = data.eventCount,
                        venueCount = data.venueCount,
                        neighborhoods = data.neighborhoods,
                        venues = data.venueNames,
                        categoryCounts = data.categoryCounts,
                        categoryPhotos = data.categoryPhotos,
                        isLoadingData = false,
                    )
                }
            } catch (e: Exception) {
                println("loadOnboardingData: FAILED ${e.message}")
                e.printStackTrace()
                _state.update { it.copy(isLoadingData = false) }
            }
        }
    }

    private fun loadFavouriteEvents() {
        viewModelScope.launch {
            try {
                val s = _state.value
                val city = s.userCity.lowercase().ifBlank { "boston" }
                val lat = s.userLat ?: 42.3897
                val lng = s.userLng ?: -71.2358

                // Resolve selected IDs back to actual DB names
                val neighborhoodNames = s.neighborhoods
                    .filter { it.name.lowercase().replace(" ", "-") in s.selectedNeighbourhoods }
                    .map { it.name }
                    .toSet()
                    .ifEmpty { null }

                val venueNames = s.venues
                    .filter { it.name.lowercase().replace(" ", "-") in s.selectedVenues }
                    .map { it.name }
                    .toSet()
                    .ifEmpty { null }

                val categories = s.selectedCategories.ifEmpty { null }

                val events = onboardingRepository.getTopEvents(
                    city = city,
                    neighborhoods = neighborhoodNames,
                    venueNames = venueNames,
                    categories = categories,
                    userLat = lat,
                    userLng = lng,
                    limit = 5,
                )
                _state.update {
                    it.copy(
                        savedEvents = events.mapIndexed { i, e -> e.toSavedEvent(i) },
                        isLoadingData = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoadingData = false) }
            }
        }
    }

    private fun nearestCity(lat: Double, lng: Double): String {
        data class CityRef(val name: String, val lat: Double, val lng: Double)
        val cities = listOf(
            CityRef("Boston", 42.3601, -71.0589),
            CityRef("Cambridge", 42.3736, -71.1097),
            CityRef("Somerville", 42.3876, -71.0995),
            CityRef("Waltham", 42.3765, -71.2356),
            CityRef("Brookline", 42.3318, -71.1212),
            CityRef("Newton", 42.3370, -71.2092),
            CityRef("Worcester", 42.2626, -71.8023),
            CityRef("Springfield", 42.1015, -72.5898),
            CityRef("Lowell", 42.6334, -71.3162),
            CityRef("Providence", 41.8240, -71.4128),
            CityRef("New York", 40.7128, -74.0060),
            CityRef("Portland", 43.6591, -70.2568),
            CityRef("Philadelphia", 39.9526, -75.1652),
            CityRef("Pittsburgh", 40.4406, -79.9959),
            CityRef("Allentown", 40.6084, -75.4902),
            CityRef("Harrisburg", 40.2732, -76.8839),
        )
        return cities.minByOrNull {
            val dLat = it.lat - lat
            val dLng = it.lng - lng
            dLat * dLat + dLng * dLng
        }?.name ?: "Boston"
    }

    companion object {
        fun categoryIcon(key: String): ImageVector = when (key.lowercase()) {
            "music" -> Icons.Outlined.MusicNote
            "food" -> Icons.Outlined.Restaurant
            "arts" -> Icons.Outlined.Palette
            "sports" -> Icons.Outlined.SportsScore
            "comedy" -> Icons.Outlined.TheaterComedy
            "outdoors" -> Icons.Outlined.Park
            "nightlife" -> Icons.Outlined.NightsStay
            "theatre" -> Icons.Outlined.TheaterComedy
            "museums" -> Icons.Outlined.Place
            "tours" -> Icons.Outlined.Place
            "libraries" -> Icons.Outlined.Place
            "for-kids" -> Icons.Outlined.SportsScore
            "film" -> Icons.Outlined.Movie
            "other" -> Icons.Outlined.Place
            else -> Icons.Outlined.Place
        }

        fun categoryDisplayName(key: String): String = when (key.lowercase()) {
            "music" -> "Music"
            "food" -> "Food & Drink"
            "arts" -> "Arts"
            "sports" -> "Sports"
            "comedy" -> "Comedy"
            "outdoors" -> "Outdoors"
            "nightlife" -> "Nightlife"
            "theatre" -> "Theatre"
            "museums" -> "Museums"
            "tours" -> "Tours"
            "libraries" -> "Libraries"
            "for-kids" -> "For Kids"
            "film" -> "Film"
            "other" -> "Other"
            else -> key.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }

        fun categorySubtitle(key: String): String = when (key.lowercase()) {
            "music" -> "Concerts, live shows, jazz"
            "food" -> "Tastings, pop-ups, dining"
            "arts" -> "Museums, galleries, theatre"
            "sports" -> "Games, matches, tournaments"
            "comedy" -> "Stand-up, improv, open mic"
            "outdoors" -> "Markets, parks, nature"
            "nightlife" -> "Bars, clubs, late nights"
            "film" -> "Screenings, festivals, indie"
            else -> ""
        }

        private val NEIGHBORHOOD_ICONS = listOf(
            Icons.Outlined.Place,
        )

        private val VENUE_ICONS = listOf(
            Icons.Outlined.MusicNote,
            Icons.Outlined.Palette,
            Icons.Outlined.TheaterComedy,
            Icons.Outlined.Restaurant,
            Icons.Outlined.Mic,
            Icons.Outlined.Star,
        )

        fun venueIcon(index: Int): ImageVector =
            VENUE_ICONS[index % VENUE_ICONS.size]

        fun Event.toSavedEvent(index: Int): SavedEvent {
            val style = categoryStyle(category)
            val displayTag = categoryDisplayName(category)
            val meta = buildString {
                if (!venueName.isNullOrEmpty()) append(venueName)
                if (dateDisplay.isNotEmpty()) {
                    if (isNotEmpty()) append(" \u00B7 ")
                    append(dateDisplay)
                }
                if (distanceDisplay.isNotEmpty()) {
                    if (isNotEmpty()) append(" \u00B7 ")
                    append(distanceDisplay)
                }
                if (priceDisplay.isNotEmpty()) {
                    if (isNotEmpty()) append(" \u00B7 ")
                    append(priceDisplay)
                }
            }
            return SavedEvent(
                id = id,
                icon = style.icon,
                tag = displayTag,
                tagColor = style.tagColor,
                tagBg = style.tagBg,
                title = title,
                venueCity = venueCity,
                meta = meta,
                thumbBgStart = style.thumbBgStart,
                thumbBgEnd = style.thumbBgEnd,
            )
        }

        private data class CategoryStyle(
            val icon: ImageVector,
            val tagColor: Long,
            val tagBg: Long,
            val thumbBgStart: Long,
            val thumbBgEnd: Long,
        )

        private fun categoryStyle(category: String) = when (category.lowercase()) {
            "music" -> CategoryStyle(Icons.Outlined.MusicNote, 0xFFFF6B6B, 0xFFFFE8E8, 0xFFFFE8E8, 0xFFFFC8C8)
            "food" -> CategoryStyle(Icons.Outlined.Restaurant, 0xFFE6A020, 0xFFFFF4DC, 0xFFFFF8EC, 0xFFFFF0CC)
            "arts" -> CategoryStyle(Icons.Outlined.Palette, 0xFF3AAD85, 0xFFE6F9F2, 0xFFEDFAF5, 0xFFD5F5E8)
            "sports" -> CategoryStyle(Icons.Outlined.SportsScore, 0xFF3A7CE8, 0xFFEBF2FF, 0xFFEEF5FF, 0xFFDCEEFF)
            "comedy" -> CategoryStyle(Icons.Outlined.TheaterComedy, 0xFF6D45C4, 0xFFF3EEFF, 0xFFF5F0FF, 0xFFEDE0FF)
            "outdoors" -> CategoryStyle(Icons.Outlined.Park, 0xFF3AAD85, 0xFFE6F9F2, 0xFFEDFAF5, 0xFFD5F5E8)
            "nightlife" -> CategoryStyle(Icons.Outlined.NightsStay, 0xFF6D45C4, 0xFFF3EEFF, 0xFFF5F0FF, 0xFFEDE0FF)
            "film" -> CategoryStyle(Icons.Outlined.Movie, 0xFF3A7CE8, 0xFFEBF2FF, 0xFFEEF5FF, 0xFFDCEEFF)
            else -> CategoryStyle(Icons.Outlined.Star, 0xFF3A7CE8, 0xFFEBF2FF, 0xFFEEF5FF, 0xFFDCEEFF)
        }
    }
}
