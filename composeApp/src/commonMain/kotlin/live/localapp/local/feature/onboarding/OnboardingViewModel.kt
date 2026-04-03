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
    // Dynamic data from Supabase
    val eventCount: Int? = null,
    val venueCount: Int? = null,
    val neighborhoods: List<NeighborhoodInfo> = emptyList(),
    val venues: List<VenueSummary> = emptyList(),
    val categoryCounts: Map<String, Int> = emptyMap(),
    val isLoadingData: Boolean = false,
) {
    /** Tracker fills: 5 segments [Location, Discovery, Favourites, Notifications, Account] each 0f..1f */
    val trackerFills: List<Float>?
        get() = when (currentStep) {
            OnboardingStep.SPLASH -> null
            OnboardingStep.LOCATION -> listOf(0.5f, 0f, 0f, 0f, 0f)
            OnboardingStep.MANUAL_ADDRESS -> listOf(0.75f, 0f, 0f, 0f, 0f)
            OnboardingStep.SUCCESS -> listOf(1f, 0f, 0f, 0f, 0f)
            OnboardingStep.NEIGHBOURHOODS -> listOf(1f, 0.33f, 0f, 0f, 0f)
            OnboardingStep.VENUES -> listOf(1f, 0.66f, 0f, 0f, 0f)
            OnboardingStep.CATEGORIES -> listOf(1f, 1f, 0f, 0f, 0f)
            OnboardingStep.FAVOURITES -> listOf(1f, 1f, 1f, 0f, 0f)
            OnboardingStep.NOTIFICATIONS -> listOf(1f, 1f, 1f, 1f, 0f)
            OnboardingStep.PHONE -> listOf(1f, 1f, 1f, 1f, 1f)
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

    fun onAllowLocation(lat: Double, lng: Double) {
        _state.update {
            it.copy(
                locationGranted = true,
                userLat = lat,
                userLng = lng,
                currentStep = OnboardingStep.SUCCESS,
            )
        }
        loadOnboardingData()
    }

    fun onDenyLocation() {
        _state.update { it.copy(currentStep = OnboardingStep.MANUAL_ADDRESS) }
    }

    fun onManualAddressSubmit(lat: Double, lng: Double, city: String) {
        _state.update {
            it.copy(
                userLat = lat,
                userLng = lng,
                currentStep = OnboardingStep.SUCCESS,
            )
        }
        loadOnboardingData()
    }

    fun onSuccessComplete() {
        _state.update { it.copy(currentStep = OnboardingStep.NEIGHBOURHOODS) }
    }

    fun onNeighbourhoodsNext() {
        _state.update { it.copy(currentStep = OnboardingStep.VENUES) }
    }

    fun onVenuesNext() {
        _state.update { it.copy(currentStep = OnboardingStep.CATEGORIES) }
    }

    fun onCategoriesNext() {
        _state.update {
            it.copy(
                isPersonalised = true,
                currentStep = OnboardingStep.FAVOURITES,
                isLoadingData = true,
            )
        }
        loadFavouriteEvents()
    }

    fun onSkipAll() {
        _state.update {
            it.copy(
                isPersonalised = false,
                currentStep = OnboardingStep.FAVOURITES,
                isLoadingData = true,
            )
        }
        loadFavouriteEvents()
    }

    fun onSkipVenues() {
        _state.update { it.copy(currentStep = OnboardingStep.CATEGORIES) }
    }

    fun onSkipCategories() {
        _state.update {
            it.copy(
                isPersonalised = false,
                currentStep = OnboardingStep.FAVOURITES,
                isLoadingData = true,
            )
        }
        loadFavouriteEvents()
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
        _state.update { it.copy(currentStep = OnboardingStep.FAVOURITES) }
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

                val prefs = UserPreferences(
                    id = authUserId ?: "",
                    firstName = s.firstName.trim().ifEmpty { null },
                    lastName = s.lastName.trim().ifEmpty { null },
                    phone = s.phoneNumber.filter { it.isDigit() }.ifEmpty { null },
                    city = "boston",
                    userLat = s.userLat,
                    userLng = s.userLng,
                    selectedNeighborhoods = neighborhoodNames,
                    selectedVenues = venueNames,
                    selectedCategories = s.selectedCategories.toList(),
                    notifTickets = s.notifTickets,
                    notifNearby = s.notifNearby,
                    notifVenues = s.notifVenues,
                    onboardingComplete = true,
                )

                // If we have an auth user ID, save with that specific ID
                val uid = authUserId
                if (uid != null) {
                    userPreferencesRepository.savePreferencesWithId(uid, prefs)
                } else {
                    userPreferencesRepository.savePreferences(prefs)
                }
            } catch (_: Exception) {
                // Save failed — continue anyway, preferences will be lost
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
                val city = "boston" // TODO: derive from location
                val s = _state.value
                val lat = s.userLat ?: 42.3897
                val lng = s.userLng ?: -71.2358
                val data = onboardingRepository.loadOnboardingData(city, lat, lng)
                _state.update {
                    it.copy(
                        eventCount = data.eventCount,
                        venueCount = data.venueCount,
                        neighborhoods = data.neighborhoods,
                        venues = data.venueNames,
                        categoryCounts = data.categoryCounts,
                        isLoadingData = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoadingData = false) }
            }
        }
    }

    private fun loadFavouriteEvents() {
        viewModelScope.launch {
            try {
                val s = _state.value
                val city = "boston"
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
            "libraries" -> Icons.Outlined.Star
            "for-kids" -> Icons.Outlined.Star
            "film" -> Icons.Outlined.Movie
            "other" -> Icons.Outlined.Star
            else -> Icons.Outlined.Star
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
