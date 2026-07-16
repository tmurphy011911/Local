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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.repository.CommunityInterestRepository
import live.localapp.local.domain.repository.DEFAULT_RADIUS_MILES
import live.localapp.local.domain.repository.InteractionType
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
    // 2-letter state from the geocoder for a typed home address (e.g. "PA").
    // Authoritative at state borders; stored as the profile's geocoded_state.
    val userState: String = "",
    val selectedNeighbourhoods: Set<String> = emptySet(),
    val selectedVenues: Set<String> = emptySet(),
    val selectedCategories: Set<String> = emptySet(),
    val isPersonalised: Boolean = false,
    val savedEvents: List<SavedEvent> = emptyList(),
    val firstName: String = "",
    val lastName: String = "",
    val phoneNumber: String = "",
    val email: String = "",
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
    /**
     * Tracker fills: 2 segments [Location, Notifications] each 0f..1f, matching the
     * shortened flow (LOCATION → NOTIFICATIONS). The SUCCESS + preference steps are
     * skipped in first-run onboarding (retained for a future lobby CTA), so their
     * branches here are dead — kept exhaustive, given mid-flow values in case they're
     * ever resurfaced inline.
     */
    val trackerFills: List<Float>?
        get() = when (currentStep) {
            OnboardingStep.SPLASH -> null
            OnboardingStep.LOCATION -> listOf(0.5f, 0f)
            OnboardingStep.MANUAL_ADDRESS -> listOf(1f, 0f)
            OnboardingStep.SUCCESS -> listOf(1f, 0f)
            OnboardingStep.NEIGHBOURHOODS -> listOf(1f, 0.25f)
            OnboardingStep.VENUES -> listOf(1f, 0.5f)
            OnboardingStep.CATEGORIES -> listOf(1f, 0.75f)
            OnboardingStep.FAVOURITES -> listOf(1f, 0.9f)
            OnboardingStep.NOTIFICATIONS -> listOf(1f, 1f)
            OnboardingStep.PHONE -> listOf(1f, 1f)
        }

    val categoriesValid: Boolean get() = selectedCategories.size >= 2
}

class OnboardingViewModel(
    private val onboardingRepository: OnboardingRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val communityInterestRepository: CommunityInterestRepository,
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
        // Shortened onboarding: skip SUCCESS + the preference steps
        // (NEIGHBOURHOODS/VENUES/CATEGORIES/FAVOURITES) and go straight to
        // NOTIFICATIONS. Those screens are retained for a future lobby CTA;
        // they're just no longer part of the first-run flow. Coords still
        // arrive via onAllowLocation while the user is on NOTIFICATIONS.
        _state.update {
            it.copy(
                locationGranted = true,
                locationMode = "current",
                currentStep = OnboardingStep.NOTIFICATIONS,
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
                // Shortened onboarding — straight to NOTIFICATIONS (skip SUCCESS
                // + preference steps, kept for a future lobby CTA). No need to
                // pre-load the picker data (the slow venues/events fetch).
                currentStep = OnboardingStep.NOTIFICATIONS,
            )
        }
    }

    fun onDenyLocation() {
        _state.update { it.copy(currentStep = OnboardingStep.MANUAL_ADDRESS) }
    }

    fun onManualAddressSubmit(lat: Double, lng: Double, city: String, state: String) {
        val resolvedCity = city.ifBlank { nearestCity(lat, lng) }
        _state.update {
            it.copy(
                userLat = lat,
                userLng = lng,
                userCity = resolvedCity,
                userState = state.trim().uppercase(),
                locationMode = "manual",
                // Shortened onboarding — straight to NOTIFICATIONS (skip SUCCESS
                // + preference steps, kept for a future lobby CTA).
                currentStep = OnboardingStep.NOTIFICATIONS,
            )
        }
    }

    fun onSuccessComplete() {
        val s = _state.value
        // Show personalization steps whenever there's *any* data for them,
        // not just when there's a high count. Smaller cities (Waltham,
        // Cambridge, etc.) often have <2 neighborhoods or <10 venues but
        // we still want users to see the personalization flow — skipping
        // straight to the push prompt undercuts the "look how local this
        // is" narrative arc.
        val nextStep = when {
            s.neighborhoods.isNotEmpty() -> OnboardingStep.NEIGHBOURHOODS
            s.venues.isNotEmpty() -> OnboardingStep.VENUES
            s.categoryCounts.isNotEmpty() -> OnboardingStep.CATEGORIES
            else -> OnboardingStep.NOTIFICATIONS
        }
        _state.update { it.copy(currentStep = nextStep) }
    }

    fun onNeighbourhoodsNext() {
        val s = _state.value
        val nextStep = when {
            s.venues.isNotEmpty() -> OnboardingStep.VENUES
            s.categoryCounts.isNotEmpty() -> OnboardingStep.CATEGORIES
            else -> OnboardingStep.NOTIFICATIONS
        }
        _state.update { it.copy(currentStep = nextStep) }
    }

    fun onVenuesNext() {
        val s = _state.value
        val nextStep = if (s.categoryCounts.isNotEmpty()) OnboardingStep.CATEGORIES else OnboardingStep.NOTIFICATIONS
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
        // Shortened flow: NOTIFICATIONS' previous step is now the location step,
        // not the (skipped) preference screens. Manual users go back to the
        // address entry; GPS users to the permission ask (which no longer
        // auto-skips on back — see OnboardingScreen's pregrantSkipped guard).
        val target = if (_state.value.locationMode == "manual") {
            OnboardingStep.MANUAL_ADDRESS
        } else {
            OnboardingStep.LOCATION
        }
        _state.update { it.copy(currentStep = target) }
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
            // The shortened flow can reach "save" (via onLocationPregranted) before
            // onAllowLocation has delivered the GPS coords. Persisting a coordless
            // current-location profile makes the server's home_state resolver fall
            // through to resolve_state_code(null, null, city): for a non-metro city
            // like "Waltham" that returns NULL, so home_state is never set — which
            // silently hides Daily Local (get_daily_quiz -> no_home_state) and mis-
            // scopes the leaderboard. Wait briefly for the coords to land first. On
            // timeout we still save: userCity stays blank -> "boston" -> MA, a valid
            // (if generic) home_state, never null. Manual-address users already carry
            // coords + geocoded_state, so this only gates the GPS path.
            if (_state.value.locationMode == "current" && _state.value.userLat == null) {
                withTimeoutOrNull(4000) {
                    _state.first { it.userLat != null && it.userLng != null }
                }
            }
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
                    email = s.email.trim().ifEmpty { null },
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
                    // Durable hometown anchor: the location chosen here, at
                    // onboarding. Manual users get the address they typed; GPS
                    // users get the resolved city. The DB makes it write-once, so
                    // this only "takes" on the first save and is immutable after.
                    hometownName = s.userCity.replaceFirstChar { it.uppercase() }.ifBlank { "Boston" },
                    hometownLat = s.userLat,
                    hometownLng = s.userLng,
                    // The geocoder's authoritative state for a typed address. The
                    // server trusts this over coordinate-derived state, fixing the
                    // border case (Philadelphia -> PA, not the NJ bbox neighbor).
                    geocodedState = s.userState.ifBlank { null },
                )

                // Mirror the chosen location into the local cache up-front, before
                // the Supabase round-trip. Home's cold-start hydration reads this
                // cache first; without it a freshly-onboarded manual user falls back
                // to the CURRENT_LOCATION default until (and unless) the remote read
                // lands — the regression where Home showed "Current Location" for
                // someone who entered their address by hand.
                userPreferencesRepository.cacheManualLocationLocally(
                    locationMode = prefs.locationMode,
                    manualLocationName = prefs.manualLocationName,
                    manualLat = prefs.manualLat,
                    manualLng = prefs.manualLng,
                )

                // If we have an auth user ID, save with that specific ID
                val uid = authUserId
                if (uid != null) {
                    userPreferencesRepository.savePreferencesWithId(uid, prefs)
                } else {
                    userPreferencesRepository.savePreferences(prefs)
                }

                // Mirror onboarding venue picks into community_interactions so
                // they show up as already-saved on the Places + Plans screens
                // (which read favorite venue ids from community_interactions,
                // not from user_profiles.selected_venues). Fire-and-forget per
                // venue — recordFavorite is non-blocking and idempotent (upsert
                // semantics inside CommunityInterestRepositoryImpl).
                val selectedVenuesWithIds = s.venues
                    .filter { it.name.lowercase().replace(" ", "-") in s.selectedVenues }
                    .mapNotNull { v -> v.id?.takeIf { it.isNotBlank() }?.let { it to v.name } }
                for ((venueId, _) in selectedVenuesWithIds) {
                    communityInterestRepository.recordFavorite(venueId, InteractionType.PLACE, null)
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
            // Seed the default search radius (25 mi) the moment onboarding
            // finishes, so it's concretely persisted and Home/Explore restore it
            // on this and every future launch — independent of view-model init
            // ordering. Only when the user hasn't already set one (they can't
            // yet during onboarding, but this keeps it idempotent on resume).
            if (userPreferencesRepository.getCachedRadiusFilter() == null) {
                userPreferencesRepository.cacheRadiusFilterLocally(DEFAULT_RADIUS_MILES)
            }
            onSaved()
        }
    }

    // ── Lobby-launched "preferences only" flow (reuses the pick screens) ──

    /**
     * Enter the preferences flow for the CURRENT (already-onboarded) user: seed
     * the known home location, load the pick data, and jump to the first step.
     * Used by the lobby "your preferences" CTA, not the first-run onboarding.
     */
    fun beginPreferencesOnly() {
        viewModelScope.launch {
            val uid = userPreferencesRepository.getLocalUserId() ?: return@launch
            authUserId = uid
            val p = userPreferencesRepository.loadPreferences(uid)
            val city = (p?.hometownName ?: p?.city ?: "").ifBlank { "boston" }
            _state.update {
                it.copy(
                    userCity = city,
                    userLat = p?.hometownLat ?: p?.userLat,
                    userLng = p?.hometownLng ?: p?.userLng,
                    currentStep = OnboardingStep.NEIGHBOURHOODS,
                    // Set up-front so the flow shows a loader, not an empty grid,
                    // in the frame before loadOnboardingData flips this itself.
                    isLoadingData = true,
                )
            }
            loadOnboardingData()
        }
    }

    /**
     * Persist just the flow's selections and flip preferences_completed (the
     * server awards 1000 FOMO on the false->true transition). [onSaved] runs on
     * the main scope with whether the write succeeded.
     */
    fun savePreferencesOnly(onSaved: (Boolean) -> Unit) {
        viewModelScope.launch {
            val s = _state.value
            val uid = authUserId ?: userPreferencesRepository.getLocalUserId()
            if (uid == null) { onSaved(false); return@launch }
            val neighborhoodNames = s.neighborhoods
                .filter { it.name.lowercase().replace(" ", "-") in s.selectedNeighbourhoods }
                .map { it.name }
            val venueNames = s.venues
                .filter { it.name.lowercase().replace(" ", "-") in s.selectedVenues }
                .map { it.name }
            val ok = userPreferencesRepository.savePreferencesSelection(
                uid, neighborhoodNames, venueNames, s.selectedCategories.toList(),
            )
            onSaved(ok)
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

    fun updateEmail(value: String) {
        _state.update { it.copy(email = value) }
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
