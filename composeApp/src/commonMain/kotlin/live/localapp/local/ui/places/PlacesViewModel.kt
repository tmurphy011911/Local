package live.localapp.local.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.model.distanceMiles
import live.localapp.local.domain.repository.CommunityInterestRepository
import live.localapp.local.domain.repository.InteractionType
import live.localapp.local.domain.repository.UserPreferences
import live.localapp.local.domain.repository.UserPreferencesRepository
import live.localapp.local.domain.repository.VenueRepository
import live.localapp.local.domain.repository.VenueWithMeta

data class PlacesUiState(
    val venues: List<VenueWithMeta> = emptyList(),
    val filteredVenues: List<VenueWithMeta> = emptyList(),
    val selectedFilter: String = "All",
    val selectedSubFilter: String? = null,
    val searchQuery: String = "",
    val userLat: Double? = null,
    val userLng: Double? = null,
    val locationName: String = "",
    val streakDays: Int = 0,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    // Detail sheet
    val selectedVenue: VenueWithMeta? = null,
    val selectedVenueEvents: List<Event> = emptyList(),
    val isSheetLoading: Boolean = false,
    val userPreferences: UserPreferences? = null,
    val radiusFilter: Double? = null,
)

class PlacesViewModel(
    private val venueRepository: VenueRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val communityInterestRepository: CommunityInterestRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlacesUiState())
    val uiState: StateFlow<PlacesUiState> = _uiState.asStateFlow()

    private var venuesLoaded = false

    init {
        loadUserPreferences()
    }

    /** Call when the Places tab becomes visible for the first time. */
    fun ensureLoaded() {
        if (!venuesLoaded) {
            venuesLoaded = true
            loadVenues()
        }
    }

    private fun loadUserPreferences() {
        val userId = userPreferencesRepository.getLocalUserId() ?: return
        viewModelScope.launch {
            val prefs = userPreferencesRepository.loadPreferences(userId) ?: return@launch
            _uiState.update {
                it.copy(
                    userLat = prefs.userLat ?: it.userLat,
                    userLng = prefs.userLng ?: it.userLng,
                    userPreferences = prefs,
                )
            }
            loadVenues()
        }
    }

    fun setUserLocation(lat: Double, lng: Double) {
        val current = _uiState.value
        if (current.userLat != lat || current.userLng != lng) {
            _uiState.update { it.copy(userLat = lat, userLng = lng) }
            loadVenues()
        }
    }

    fun setLocationName(name: String) {
        _uiState.update { it.copy(locationName = name) }
    }

    fun setStreakDays(days: Int) {
        _uiState.update { it.copy(streakDays = days) }
    }

    fun debugSetLocation(lat: Double, lng: Double, name: String) {
        _uiState.update { it.copy(userLat = lat, userLng = lng, locationName = name) }
        loadVenues()
    }

    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true) }
        loadVenues()
    }

    fun setFilter(filter: String) {
        _uiState.update { it.copy(selectedFilter = filter, selectedSubFilter = null) }
        applyFilters()
    }

    fun setSubFilter(sub: String?) {
        _uiState.update { it.copy(selectedSubFilter = sub) }
        applyFilters()
    }

    fun setRadiusFilter(miles: Double?) {
        _uiState.update { it.copy(radiusFilter = miles) }
        applyFilters()
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        applyFilters()
    }

    fun selectVenue(venue: VenueWithMeta) {
        communityInterestRepository.recordClick(venue.venue.id, InteractionType.PLACE, venue.primaryCategory)
        _uiState.update {
            it.copy(
                selectedVenue = venue,
                selectedVenueEvents = venue.upcomingEvents,
                isSheetLoading = false,
            )
        }
    }

    fun dismissSheet() {
        _uiState.update { it.copy(selectedVenue = null, selectedVenueEvents = emptyList()) }
    }

    private fun loadVenues() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val state = _uiState.value
                val city = state.locationName.split(",").firstOrNull()?.trim()?.lowercase() ?: "boston"
                val lat = state.userLat ?: 42.3601
                val lng = state.userLng ?: -71.0589
                val rawVenues = venueRepository.getVenues(city, lat, lng)
                // De-dupe: if two venues in the same city have names where one starts with the other, keep the shorter name
                val venues = rawVenues.toMutableList()
                val toRemove = mutableSetOf<String>()
                for (i in venues.indices) {
                    for (j in venues.indices) {
                        if (i == j) continue
                        val a = venues[i].venue.name.lowercase()
                        val b = venues[j].venue.name.lowercase()
                        if (a != b && b.startsWith(a) && venues[i].venue.city == venues[j].venue.city) {
                            toRemove.add(venues[j].venue.id) // remove the longer name
                        }
                    }
                }
                val deduped = venues.filter { it.venue.id !in toRemove }
                _uiState.update {
                    it.copy(venues = deduped, isLoading = false, isRefreshing = false)
                }
                applyFilters()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false, isRefreshing = false) }
            }
        }
    }

    private fun applyFilters() {
        val state = _uiState.value
        var result = state.venues

        // Category filter — match on venue's own category or event-derived tags
        if (state.selectedFilter != "All") {
            val matchCategories = filterToVenueCategories(state.selectedFilter)
            result = result.filter { v ->
                val venCat = v.venue.category?.lowercase() ?: ""
                matchCategories.any { venCat.contains(it) } ||
                    v.primaryCategory?.let { pc -> matchCategories.any { pc.contains(it) } } == true ||
                    v.tags.any { t -> matchCategories.any { mc -> t.equals(mc, ignoreCase = true) } }
            }
        }

        // Radius filter
        if (state.radiusFilter != null) {
            result = result.filter { (it.distanceMiles ?: 999.0) <= state.radiusFilter }
        }

        // Search filter
        if (state.searchQuery.isNotBlank()) {
            val q = state.searchQuery.lowercase()
            result = result.filter {
                it.venue.name.lowercase().contains(q) ||
                    (it.venue.city.lowercase().contains(q)) ||
                    (it.venue.address?.lowercase()?.contains(q) == true) ||
                    it.tags.any { t -> t.lowercase().contains(q) }
            }
        }

        // Sort: saved venues first, then by combined score (public interest, proximity, events)
        val savedNames = state.userPreferences?.selectedVenues
            ?.map { it.lowercase().trim() }
            ?.toSet()
            ?: emptySet()

        val maxRank = result.maxOfOrNull { it.venue.publicInterestRank ?: 0 }?.toDouble()?.coerceAtLeast(1.0) ?: 1.0
        val maxEvents = result.maxOfOrNull { it.upcomingEventCount }?.toDouble()?.coerceAtLeast(1.0) ?: 1.0
        val venueIds = result.map { it.venue.id }

        // Same decay divisor logic as CarouselRepositoryImpl — steeper falloff the further from metro
        val milesFromMetro = distanceMiles(state.userLat ?: METRO_LAT, state.userLng ?: METRO_LNG, METRO_LAT, METRO_LNG)
        val decayDivisor = when {
            milesFromMetro <= 12.0 -> 6.0
            milesFromMetro <= 25.0 -> 3.5
            milesFromMetro <= 45.0 -> 2.0
            else                   -> 1.0
        }

        result = result.sortedWith(
            compareByDescending<VenueWithMeta> { v ->
                v.venue.name.lowercase().trim() in savedNames
            }.thenByDescending { v ->
                val rankScore      = (v.venue.publicInterestRank ?: 0).toDouble() / maxRank
                val proxScore      = 1.0 / (1.0 + (v.distanceMiles ?: 50.0) / decayDivisor)
                val eventScore     = v.upcomingEventCount.toDouble() / maxEvents
                val personalScore  = communityInterestRepository.personalRawScore(v.venue.id).coerceAtMost(5.0) / 5.0
                val communityScore = communityInterestRepository.normalizedScore(v.venue.id, venueIds)
                val affinity       = communityInterestRepository.categoryAffinity()
                val venueCat       = v.primaryCategory?.lowercase()
                val affinityBonus  = if (venueCat != null) (affinity[venueCat] ?: 0.0) * 0.10 else 0.0
                // rank 30% · personal 15% · community 10% · proximity 25% · events 10% · affinity up to 10%
                rankScore * 0.30 + personalScore * 0.15 + communityScore * 0.10 + proxScore * 0.25 + eventScore * 0.10 + affinityBonus
            }
        )

        _uiState.update { it.copy(filteredVenues = result) }
    }

    /** Maps a UI filter to venue category strings (matched via contains on venue.category) */
    private fun filterToVenueCategories(filter: String): List<String> = when (filter) {
        "Bars" -> listOf("bar", "brewery", "winery")
        "Food" -> listOf("restaurant", "cafe", "bakery")
        "Music" -> listOf("music", "amphitheater")
        "Art" -> listOf("art", "museum", "gallery", "library")
        "Comedy" -> listOf("comedy")
        "Theatre" -> listOf("theater", "theatre")
        "Sports" -> listOf("sport", "stadium", "college")
        else -> listOf(filter.lowercase())
    }

    companion object {
        private const val METRO_LAT = 42.3601
        private const val METRO_LNG = -71.0589

        val FILTERS = listOf("All", "Bars", "Food", "Music", "Art", "Comedy", "Theatre", "Sports")

        val SUB_FILTERS = mapOf(
            "Bars" to listOf("All Bars", "Dive Bars", "Rooftop", "Craft Beer", "Cocktail", "Wine Bars"),
            "Food" to listOf("All Food", "Seafood", "Italian", "Brunch", "Late Night", "Vegetarian"),
            "Music" to listOf("All Music", "Jazz", "Rock / Indie", "DJ / Electronic", "Classical", "Hip-Hop"),
            "Art" to listOf("All Arts", "Galleries", "Museums", "Street Art", "Installations"),
            "Comedy" to listOf("All Comedy", "Stand-Up", "Improv", "Open Mic"),
            "Theatre" to listOf("All Theatre", "Broadway", "Off-Broadway", "Opera", "Dance"),
            "Sports" to listOf("All Sports", "Live Games", "Watch Parties", "Outdoor"),
        )
    }
}
