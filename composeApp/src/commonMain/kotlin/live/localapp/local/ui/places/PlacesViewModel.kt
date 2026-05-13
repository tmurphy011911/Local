package live.localapp.local.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.repository.CommunityInterestRepository
import live.localapp.local.domain.repository.ExploreRepository
import live.localapp.local.domain.repository.InteractionType
import live.localapp.local.domain.repository.UserPreferences
import live.localapp.local.domain.repository.UserPreferencesRepository
import live.localapp.local.domain.repository.VenueRepository
import live.localapp.local.domain.repository.VenueWithMeta

/** Which result category is the Search tab currently showing. */
enum class SearchTab { EVENTS, PLACES }

/**
 * Date-range filter for the Events tab. Server receives the enum's `slug` as
 * the `date_filter` query param; null means no filter (default 60-day window).
 */
enum class EventDateFilter(val label: String, val slug: String) {
    TODAY("Today", "today"),
    TOMORROW("Tomorrow", "tomorrow"),
    THIS_WEEKEND("This Weekend", "this_weekend"),
    NEXT_7_DAYS("Next 7 days", "next_7_days"),
    NEXT_30_DAYS("Next 30 days", "next_30_days"),
}

data class PlacesUiState(
    val activeTab: SearchTab = SearchTab.EVENTS,
    // filteredVenues / filteredEvents ARE the display lists. No client-side
    // filtering or scoring happens anymore — server returns pre-ranked pages.
    // The legacy fields `venues` and `events` are kept as aliases for any UI
    // code that still reads them; both now mirror their filtered counterparts.
    val venues: List<VenueWithMeta> = emptyList(),
    val filteredVenues: List<VenueWithMeta> = emptyList(),
    val events: List<Event> = emptyList(),
    val filteredEvents: List<Event> = emptyList(),
    // "Pool" lists — the most recent unfiltered server response. Local search
    // filters these in-memory on every keystroke so the user sees instant
    // results before the debounced server fetch lands. Updated only when a
    // server fetch returns with a blank query (otherwise we'd cache a
    // query-narrowed set as our "unfiltered" pool).
    val unfilteredVenuesPool: List<VenueWithMeta> = emptyList(),
    val unfilteredEventsPool: List<Event> = emptyList(),
    val selectedFilter: String = "All",
    val selectedSubFilter: String? = null,
    val searchQuery: String = "",
    val userLat: Double? = null,
    val userLng: Double? = null,
    val locationName: String = "",
    val streakDays: Int = 0,
    val isLoading: Boolean = false,
    // Events tab has its own loading flag — loadEvents and loadPlaces fire
    // independently and we want each tab to show its own skeleton rather
    // than the events screen staying blank while places finishes loading.
    val isEventsLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val selectedVenue: VenueWithMeta? = null,
    val selectedVenueEvents: List<Event> = emptyList(),
    val isSheetLoading: Boolean = false,
    val userPreferences: UserPreferences? = null,
    val radiusFilter: Double? = null,
    // Events tab — date range filter; null = default 60-day window.
    val dateFilter: EventDateFilter? = null,
    // Places tab — limit to venues that have upcoming events.
    val hasEventsOnly: Boolean = false,
    val favoriteVenueIds: Set<String> = emptySet(),
    val favoriteEventIds: Set<String> = emptySet(),
    val savedEvents: List<Event> = emptyList(),
    // Cursor-based pagination replaces the old window-based approach. `hasMore`
    // is still surfaced so the list screen's infinite-scroll trigger keeps working.
    val venueCursor: String? = null,
    val hasMore: Boolean = false,
    val eventCursor: String? = null,
    val eventsHasMore: Boolean = false,
    // Window-limit fields kept for API compatibility with the existing screen,
    // but unused internally.
    val visibleLimit: Int = 50,
    val eventsVisibleLimit: Int = 50,
)

class PlacesViewModel(
    private val venueRepository: VenueRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val communityInterestRepository: CommunityInterestRepository,
    private val exploreRepository: ExploreRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlacesUiState())
    val uiState: StateFlow<PlacesUiState> = _uiState.asStateFlow()

    private var venuesLoaded = false
    private var eventsLoaded = false
    private var loadedLat: Double? = null
    private var loadedLng: Double? = null

    init {
        loadUserPreferences()
        loadFavoriteVenueIds()
    }

    /** Call when the Explore tab becomes visible. Reloads if location changed. */
    fun ensureLoaded() {
        val state = _uiState.value
        val locationChanged = state.userLat != loadedLat || state.userLng != loadedLng
        if (!venuesLoaded || locationChanged) {
            venuesLoaded = true
            loadedLat = state.userLat
            loadedLng = state.userLng
            loadPlaces(reset = true)
        }
        if (!eventsLoaded || locationChanged) {
            eventsLoaded = true
            loadEvents(reset = true)
        }
    }

    fun setActiveTab(tab: SearchTab) {
        if (_uiState.value.activeTab == tab) return
        _uiState.update { it.copy(activeTab = tab) }
    }

    private fun loadUserPreferences() {
        val userId = userPreferencesRepository.getLocalUserId() ?: return
        viewModelScope.launch {
            val prefs = userPreferencesRepository.loadPreferences(userId) ?: return@launch
            _uiState.update { it.copy(userPreferences = prefs) }
            // Prefs change affects ranking — re-fetch both feeds.
            loadPlaces(reset = true)
            loadEvents(reset = true)
        }
    }

    fun setUserLocation(lat: Double, lng: Double) {
        val state = _uiState.value
        // Short-circuit when called with the same coords. AppNavigation calls this
        // on every recomposition of the Explore tab, so without this guard every
        // unrelated state change would null loadedLat and force ensureLoaded to
        // re-fetch, creating an infinite reload loop if the fetch fails or even
        // just on rapid state churn.
        if (state.userLat == lat && state.userLng == lng) return
        _uiState.update { it.copy(userLat = lat, userLng = lng) }
        loadedLat = null
        loadedLng = null
    }

    fun setLocationName(name: String) {
        _uiState.update { it.copy(locationName = name) }
    }

    fun setStreakDays(days: Int) {
        _uiState.update { it.copy(streakDays = days) }
    }

    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true) }
        loadPlaces(reset = true)
        loadEvents(reset = true)
    }

    fun setFilter(filter: String) {
        _uiState.update { it.copy(selectedFilter = filter, selectedSubFilter = null) }
        loadPlaces(reset = true)
        loadEvents(reset = true)
    }

    fun setSubFilter(sub: String?) {
        _uiState.update { it.copy(selectedSubFilter = sub) }
    }

    fun setRadiusFilter(miles: Double?) {
        _uiState.update { it.copy(radiusFilter = miles) }
        loadPlaces(reset = true)
        loadEvents(reset = true)
    }

    fun setDateFilter(filter: EventDateFilter?) {
        if (_uiState.value.dateFilter == filter) return
        _uiState.update { it.copy(dateFilter = filter) }
        loadEvents(reset = true)
    }

    fun setHasEventsOnly(enabled: Boolean) {
        if (_uiState.value.hasEventsOnly == enabled) return
        _uiState.update { it.copy(hasEventsOnly = enabled) }
        loadPlaces(reset = true)
    }

    private var searchDebounceJob: Job? = null
    fun setSearchQuery(query: String) {
        val trimmed = query.trim().lowercase()
        _uiState.update { state ->
            // Apply a client-side filter to the cached unfiltered pool so the
            // user sees instant matches on every keystroke. The debounced
            // server call below will replace these with the canonical set
            // (including items not currently in the pool) once it lands.
            val locallyFilteredVenues = if (trimmed.isBlank()) {
                state.unfilteredVenuesPool
            } else {
                state.unfilteredVenuesPool.filter { vm ->
                    val v = vm.venue
                    v.name.lowercase().contains(trimmed) ||
                        (v.city?.lowercase()?.contains(trimmed) == true)
                }
            }
            val locallyFilteredEvents = if (trimmed.isBlank()) {
                state.unfilteredEventsPool
            } else {
                state.unfilteredEventsPool.filter { e ->
                    e.title.lowercase().contains(trimmed) ||
                        (e.venueName?.lowercase()?.contains(trimmed) == true)
                }
            }
            state.copy(
                searchQuery = query,
                filteredVenues = locallyFilteredVenues,
                venues = locallyFilteredVenues,
                filteredEvents = locallyFilteredEvents,
                events = locallyFilteredEvents,
            )
        }
        searchDebounceJob?.cancel()
        searchDebounceJob = viewModelScope.launch {
            delay(220)
            loadPlaces(reset = true, isSearchUpdate = true)
            loadEvents(reset = true, isSearchUpdate = true)
        }
    }

    // ══════ Places ══════

    private var placesLoadJob: Job? = null
    private fun loadPlaces(reset: Boolean, isSearchUpdate: Boolean = false) {
        placesLoadJob?.cancel()
        placesLoadJob = viewModelScope.launch {
            if (reset) {
                // On filter / tab changes we clear stale items so the skeleton
                // takes over. On search-triggered reloads we keep the local
                // pre-filter results visible — clearing here would flash empty
                // between every keystroke and the server response.
                _uiState.update {
                    it.copy(
                        isLoading = true,
                        error = null,
                        filteredVenues = if (isSearchUpdate) it.filteredVenues else emptyList(),
                        venues = if (isSearchUpdate) it.venues else emptyList(),
                        venueCursor = null,
                        hasMore = false,
                    )
                }
            }
            try {
                val state = _uiState.value
                val lat = state.userLat ?: METRO_LAT
                val lng = state.userLng ?: METRO_LNG
                val cursor = if (reset) null else state.venueCursor
                val category = state.selectedFilter.takeIf { it != "All" }
                    ?.let { filterToServerCategory(it) }
                // Smaller page size while searching — keeps first results visible faster.
                val limit = if (state.searchQuery.isNotBlank()) 20 else 50

                val page = exploreRepository.fetchPlaces(
                    userLat = lat,
                    userLng = lng,
                    userId = state.userPreferences?.id,
                    query = state.searchQuery,
                    category = category,
                    radiusMiles = state.radiusFilter,
                    hasEventsOnly = state.hasEventsOnly,
                    cursor = cursor,
                    limit = limit,
                )

                _uiState.update {
                    val merged = if (reset) page.items else it.filteredVenues + page.items
                    // Only refresh the unfiltered pool when this fetch was for
                    // the blank-query baseline. A query-narrowed response is
                    // not a valid "everything we have" pool to filter against.
                    val nextPool = if (it.searchQuery.isBlank()) merged else it.unfilteredVenuesPool
                    it.copy(
                        filteredVenues = merged,
                        venues = merged,
                        unfilteredVenuesPool = nextPool,
                        venueCursor = page.cursor,
                        hasMore = page.hasMore,
                        isLoading = false,
                        isRefreshing = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false, isRefreshing = false) }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (!state.hasMore || state.venueCursor == null) return
        loadPlaces(reset = false)
    }

    // ══════ Events ══════

    private var eventsLoadJob: Job? = null
    private fun loadEvents(reset: Boolean, isSearchUpdate: Boolean = false) {
        eventsLoadJob?.cancel()
        eventsLoadJob = viewModelScope.launch {
            if (reset) {
                // Same split as loadPlaces — filter/tab resets clear so a
                // skeleton shows; search-triggered resets keep the local
                // pre-filter results visible while the server is in flight.
                _uiState.update {
                    it.copy(
                        isEventsLoading = true,
                        filteredEvents = if (isSearchUpdate) it.filteredEvents else emptyList(),
                        eventCursor = null,
                    )
                }
            }
            try {
                val state = _uiState.value
                val lat = state.userLat ?: METRO_LAT
                val lng = state.userLng ?: METRO_LNG
                val cursor = if (reset) null else state.eventCursor
                val category = state.selectedFilter.takeIf { it != "All" }
                    ?.let { filterToServerCategory(it) }
                val limit = if (state.searchQuery.isNotBlank()) 20 else 50

                val page = exploreRepository.fetchEvents(
                    userLat = lat,
                    userLng = lng,
                    userId = state.userPreferences?.id,
                    query = state.searchQuery,
                    category = category,
                    radiusMiles = state.radiusFilter,
                    dateFilter = state.dateFilter?.slug,
                    cursor = cursor,
                    limit = limit,
                )

                _uiState.update {
                    val merged = if (reset) page.items else it.filteredEvents + page.items
                    val favs = it.favoriteEventIds
                    val saved = merged.filter { e -> e.id in favs }
                    // Same pool-update rule as loadPlaces — only refresh when
                    // this fetch was the blank-query baseline.
                    val nextPool = if (it.searchQuery.isBlank()) merged else it.unfilteredEventsPool
                    it.copy(
                        filteredEvents = merged,
                        events = merged,
                        unfilteredEventsPool = nextPool,
                        eventCursor = page.cursor,
                        eventsHasMore = page.hasMore,
                        savedEvents = saved,
                        isEventsLoading = false,
                    )
                }
            } catch (e: Exception) {
                println("PlacesVM.loadEvents failed: ${e.message}")
                _uiState.update { it.copy(isEventsLoading = false) }
            }
        }
    }

    fun loadMoreEvents() {
        val state = _uiState.value
        if (!state.eventsHasMore || state.eventCursor == null) return
        loadEvents(reset = false)
    }

    // ══════ Venue detail ══════

    fun selectVenue(venue: VenueWithMeta) {
        communityInterestRepository.recordClick(venue.venue.id, InteractionType.PLACE, venue.primaryCategory)
        _uiState.update {
            it.copy(
                selectedVenue = venue,
                selectedVenueEvents = emptyList(),
                isSheetLoading = true,
            )
        }
        viewModelScope.launch {
            val events = try {
                venueRepository.getUpcomingEventsForVenue(venue.venue.name, venue.venue.city)
            } catch (_: Exception) {
                emptyList()
            }
            if (_uiState.value.selectedVenue?.venue?.id == venue.venue.id) {
                _uiState.update { it.copy(selectedVenueEvents = events, isSheetLoading = false) }
            }
        }
    }

    fun dismissSheet() {
        _uiState.update { it.copy(selectedVenue = null, selectedVenueEvents = emptyList()) }
    }

    // ══════ Favorites ══════

    fun toggleVenueFavorite(venueId: String, category: String? = null) {
        val current = _uiState.value.favoriteVenueIds
        if (venueId in current) {
            communityInterestRepository.removeFavorite(venueId, InteractionType.PLACE)
            _uiState.update { it.copy(favoriteVenueIds = current - venueId) }
        } else {
            communityInterestRepository.recordFavorite(venueId, InteractionType.PLACE, category)
            _uiState.update { it.copy(favoriteVenueIds = current + venueId) }
        }
    }

    fun toggleEventFavorite(eventId: String, category: String? = null) {
        val current = _uiState.value.favoriteEventIds
        if (eventId in current) {
            communityInterestRepository.removeFavorite(eventId, InteractionType.EVENT)
            _uiState.update { it.copy(favoriteEventIds = current - eventId) }
        } else {
            communityInterestRepository.recordFavorite(eventId, InteractionType.EVENT, category)
            _uiState.update { it.copy(favoriteEventIds = current + eventId) }
        }
        // Re-derive savedEvents carousel
        val state = _uiState.value
        _uiState.update {
            it.copy(savedEvents = state.filteredEvents.filter { e -> e.id in state.favoriteEventIds })
        }
    }

    private fun loadFavoriteVenueIds() {
        val userId = userPreferencesRepository.getLocalUserId() ?: return
        viewModelScope.launch {
            val venueIds = communityInterestRepository.getFavoriteVenueIds(userId)
            val eventIds = communityInterestRepository.getFavoriteEventIds(userId)
            _uiState.update {
                it.copy(
                    favoriteVenueIds = venueIds.toSet(),
                    favoriteEventIds = eventIds.toSet(),
                )
            }
        }
    }

    // ══════ Helpers ══════

    /**
     * Map the UI filter chip label to a server-side category slug. The edge
     * functions accept categories as either venue.category or event.category/subcategory.
     * Anything unknown falls through as the lowercased label and the server
     * treats a no-match as "no filter".
     */
    private fun filterToServerCategory(filter: String): String? = when (filter) {
        "All" -> null
        "Bars" -> "bar"
        "Food" -> "restaurant"
        "Music" -> "music"
        "Art" -> "art"
        "Comedy" -> "comedy"
        "Theatre" -> "theater"
        "Sports" -> "sport"
        else -> filter.lowercase()
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
