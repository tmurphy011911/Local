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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import live.localapp.local.domain.model.BadgeAction
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.service.BadgeService
import live.localapp.local.domain.repository.CommunityInterestRepository
import live.localapp.local.domain.repository.EventPage
import live.localapp.local.domain.repository.EventRepository
import live.localapp.local.domain.repository.ExploreRepository
import live.localapp.local.domain.repository.PlaceFacet
import live.localapp.local.domain.repository.PlacePage
import live.localapp.local.domain.repository.InteractionType
import live.localapp.local.domain.repository.UserPreferences
import live.localapp.local.domain.repository.UserPreferencesRepository
import live.localapp.local.domain.repository.VenueRepository
import live.localapp.local.domain.repository.VenueWithMeta

/** Which result category is the Search tab currently showing. */
enum class SearchTab { EVENTS, PLACES }

/**
 * Server-side sort for the Places tab. The slug is sent to `explore-places`
 * via the `sort` query param; the server sorts the FULL scored list before
 * paginating, so non-popularity sorts don't get capped to the popularity
 * top-N that's already on screen.
 */
enum class PlaceSort(val label: String, val slug: String) {
    POPULAR("Most Popular", "popular"),
    CLOSEST("Closest", "closest"),
    MOST_EVENTS("Most events", "most_events"),
}

/**
 * Date-range filter for the Events tab. Server receives the enum's `slug` as
 * the `date_filter` query param; null means no filter (default 60-day window).
 *
 * `CUSTOM` is paired with `customStart` / `customEnd` on [PlacesUiState] —
 * the slug "custom" tells the server to use the explicit `date_start` /
 * `date_end` query params instead of a preset window.
 */
enum class EventDateFilter(val label: String, val slug: String) {
    TODAY("Today", "today"),
    TOMORROW("Tomorrow", "tomorrow"),
    THIS_WEEK("This Week", "this_week"),
    THIS_WEEKEND("This Weekend", "this_weekend"),
    NEXT_WEEKEND("Next Weekend", "next_weekend"),
    NEXT_30_DAYS("Next 30 Days", "next_30_days"),
    CUSTOM("Select Date", "custom"),
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
    // Bo pull-to-refresh: whether the last manual refresh failed (drives the
    // settle haptic + retry toast).
    val refreshFailed: Boolean = false,
    val error: String? = null,
    val selectedVenue: VenueWithMeta? = null,
    val selectedVenueEvents: List<Event> = emptyList(),
    val isSheetLoading: Boolean = false,
    val userPreferences: UserPreferences? = null,
    val radiusFilter: Double? = null,
    // Events tab — date range filter; null = default 60-day window.
    val dateFilter: EventDateFilter? = null,
    // Populated only when [dateFilter] == CUSTOM. Inclusive on both ends.
    val customStart: LocalDate? = null,
    val customEnd: LocalDate? = null,
    // Places tab — limit to venues that have upcoming events.
    val hasEventsOnly: Boolean = false,
    // Places tab — client-side sort over the loaded venue list.
    val placeSort: PlaceSort = PlaceSort.POPULAR,
    // Places tab — two-tier geo→type filter. selectedCity is the raw venue.city
    // (null = all cities in radius); selectedVenueCategory is the raw
    // venue.category slug within that city (null = all types). The facet lists
    // drive the chip rows; category names are raw slugs (title-case in UI).
    val selectedCity: String? = null,
    val selectedVenueCategory: String? = null,
    val cityFacets: List<PlaceFacet> = emptyList(),
    val categoryFacets: List<PlaceFacet> = emptyList(),
    val favoriteVenueIds: Set<String> = emptySet(),
    val favoriteEventIds: Set<String> = emptySet(),
    val savedEvents: List<Event> = emptyList(),
    // Saved venues for the Places-tab carousel — sourced from the full favorite
    // set, independent of the current sort/search page (mirrors savedEvents).
    val savedVenues: List<VenueWithMeta> = emptyList(),
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
    private val eventRepository: EventRepository,
    private val badgeService: BadgeService,
) : ViewModel() {

    /** Set by the host (AppNavigation) to route a logged-out save to the
     *  sign-in flow. Invoked instead of persisting when there's no user id. */
    var onAuthRequired: ((itemId: String, type: InteractionType, category: String?) -> Unit)? = null

    private val _uiState = MutableStateFlow(PlacesUiState())
    val uiState: StateFlow<PlacesUiState> = _uiState.asStateFlow()

    private var venuesLoaded = false
    private var eventsLoaded = false
    private var loadedLat: Double? = null
    private var loadedLng: Double? = null

    // Per-filter response cache. Switching back to a filter we recently
    // fetched returns instantly without a server round-trip — the dominant
    // user-perceived latency win for the Explore tabs. Entries are scoped
    // to the active lat/lng (cleared when location changes via clearCaches).
    // Keep TTL short — server's edge-function cache (5 min) is the deeper
    // layer; this client cache exists to skip the network entirely on quick
    // back-and-forth taps.
    private data class CachedEventsPage(val page: EventPage, val savedAt: Long)
    private data class CachedPlacesPage(val page: PlacePage, val savedAt: Long)
    private val eventsCache = mutableMapOf<String, CachedEventsPage>()
    private val placesCache = mutableMapOf<String, CachedPlacesPage>()
    private val cacheTtlMs = 60_000L

    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    private fun PlacesUiState.eventsCacheKey(): String = listOf(
        userLat?.toString() ?: "", userLng?.toString() ?: "",
        userPreferences?.id ?: "anon",
        searchQuery, selectedFilter,
        dateFilter?.slug ?: "", customStart?.toString() ?: "", customEnd?.toString() ?: "",
        radiusFilter?.toString() ?: "",
    ).joinToString("|")

    private fun PlacesUiState.placesCacheKey(): String = listOf(
        userLat?.toString() ?: "", userLng?.toString() ?: "",
        userPreferences?.id ?: "anon",
        searchQuery, selectedFilter, selectedCity ?: "", selectedVenueCategory ?: "", placeSort.slug,
        hasEventsOnly.toString(), radiusFilter?.toString() ?: "",
    ).joinToString("|")

    private fun clearCaches() {
        eventsCache.clear()
        placesCache.clear()
    }

    init {
        // Restore the persisted search radius (or the 25 mi post-onboarding
        // default) so Explore opens filtered the same way Home does. Kept in
        // sync with Home via the shared location sheet (onRadiusChanged mirrors
        // to both VMs) and re-checked in ensureLoaded().
        _uiState.update { it.copy(radiusFilter = userPreferencesRepository.effectiveRadiusFilter()) }
        loadUserPreferences()
        loadFavoriteVenueIds()
    }

    /** Call when the Explore tab becomes visible. Reloads if location changed. */
    fun ensureLoaded() {
        // Adopt a radius default/choice that was persisted after this singleton's
        // init ran — e.g. the 25 mi default that becomes effective when a fresh
        // user finishes onboarding. Cache is the source of truth, so this also
        // picks up a change made on Home.
        val effectiveRadius = userPreferencesRepository.effectiveRadiusFilter()
        val radiusChanged = effectiveRadius != _uiState.value.radiusFilter
        if (radiusChanged) _uiState.update { it.copy(radiusFilter = effectiveRadius) }
        val state = _uiState.value
        val locationChanged = state.userLat != loadedLat || state.userLng != loadedLng
        if (locationChanged) clearCaches()
        if (!venuesLoaded || locationChanged || radiusChanged) {
            venuesLoaded = true
            loadedLat = state.userLat
            loadedLng = state.userLng
            loadPlaces(reset = true)
        }
        if (!eventsLoaded || locationChanged || radiusChanged) {
            eventsLoaded = true
            loadEvents(reset = true)
        }
    }

    fun setActiveTab(tab: SearchTab) {
        if (_uiState.value.activeTab == tab) return
        _uiState.update { it.copy(activeTab = tab) }
    }

    /**
     * Deep-link entry point for Home's "See All" rails. Switches to the Events
     * sub-tab and applies a category ([category]; null/blank = "All") plus a
     * [dateFilter] (null = default window) in a single state update, then fires
     * one events reload. Callers flip the bottom-nav tab to Explore separately.
     */
    fun openEventsFiltered(category: String?, dateFilter: EventDateFilter?) {
        val cat = category?.takeIf { it.isNotBlank() } ?: "All"
        _uiState.update {
            it.copy(
                activeTab = SearchTab.EVENTS,
                selectedFilter = cat,
                selectedSubFilter = null,
                dateFilter = dateFilter,
                customStart = null,
                customEnd = null,
            )
        }
        eventsLoaded = true
        loadEvents(reset = true)
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
        // Reload immediately when coords actually change. Previously this
        // relied on ensureLoaded() firing on next tab visit, which meant
        // changing location from the LocationSettingsSheet while sitting on
        // the Explore tab updated the pill but left stale results in place
        // until the user bounced off the tab and back. Cached per-filter
        // pages are invalidated above (loadedLat/Lng null'd) so this re-fetch
        // hits the network with the new coords.
        clearCaches()
        loadPlaces(reset = true)
        loadEvents(reset = true)
    }

    fun setLocationName(name: String) {
        _uiState.update { it.copy(locationName = name) }
    }

    fun setStreakDays(days: Int) {
        _uiState.update { it.copy(streakDays = days) }
    }

    /**
     * Manual Bo pull-to-refresh. Unlike filter/sort reloads this bypasses the
     * fresh-page cache (a pull should always hit the network), awaits both the
     * places and events loads, then diffs against the pre-refresh ids to drive
     * the NEW ribbons and surface a per-tab result toast.
     */
    fun refresh() {
        val before = _uiState.value
        val oldEventIds = before.filteredEvents.mapTo(HashSet()) { it.id }
        val oldPlaceIds = before.filteredVenues.mapTo(HashSet()) { it.venue.id }
        _uiState.update { it.copy(isRefreshing = true, refreshFailed = false) }

        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            loadPlaces(reset = true, force = true)
            loadEvents(reset = true, force = true)
            placesLoadJob?.join()
            eventsLoadJob?.join()

            val st = _uiState.value
            val newEventIds = st.filteredEvents.mapNotNull { it.id.takeIf { id -> id !in oldEventIds } }.toSet()
            val newPlaceIds = st.filteredVenues.mapNotNull { it.venue.id.takeIf { id -> id !in oldPlaceIds } }.toSet()
            val failed = st.error != null

            // Prepend the genuinely-new items to the top of each list (stable
            // sort keeps the server order within the new / existing groups).
            val eventsSorted = st.filteredEvents.sortedByDescending { it.id in newEventIds }
            val venuesSorted = st.filteredVenues.sortedByDescending { it.venue.id in newPlaceIds }

            _uiState.update {
                it.copy(
                    filteredEvents = eventsSorted,
                    events = eventsSorted,
                    filteredVenues = venuesSorted,
                    venues = venuesSorted,
                    refreshFailed = failed,
                    isRefreshing = false,
                )
            }
        }
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
        // Persist immediately so the choice survives an app kill / cold start.
        userPreferencesRepository.cacheRadiusFilterLocally(miles)
        loadPlaces(reset = true)
        loadEvents(reset = true)
    }

    fun setDateFilter(filter: EventDateFilter?) {
        if (_uiState.value.dateFilter == filter) return
        // Switching away from CUSTOM clears the picked range; the next CUSTOM
        // tap will re-open the picker fresh.
        _uiState.update { state ->
            when {
                filter == EventDateFilter.CUSTOM -> state.copy(dateFilter = filter)
                // Removing the date filter also drops the category. Category
                // chips are a sub-filter that only renders in the "date active"
                // mode of EventDateFilterRow — leaving that mode (date -> null)
                // hides them, so a still-applied category would keep filtering
                // results with no visible chip to remove it. Reset it here in
                // the same atomic update so the one reload below uses the
                // cleared category.
                filter == null -> state.copy(
                    dateFilter = null,
                    customStart = null,
                    customEnd = null,
                    selectedFilter = "All",
                    selectedSubFilter = null,
                )
                else -> state.copy(dateFilter = filter, customStart = null, customEnd = null)
            }
        }
        // Don't optimistically narrow the visible list — loadEvents() clears
        // it and shows the skeleton until the server response lands, so the
        // user sees a clear "loading new results" state instead of a stale
        // slice that may not match the canonical set.
        loadEvents(reset = true)
    }

    /** Apply an inclusive [start, end] custom date range and switch to CUSTOM. */
    fun setCustomDateRange(start: LocalDate, end: LocalDate) {
        val (a, b) = if (start <= end) start to end else end to start
        _uiState.update {
            it.copy(
                dateFilter = EventDateFilter.CUSTOM,
                customStart = a,
                customEnd = b,
            )
        }
        loadEvents(reset = true)
    }

    fun setHasEventsOnly(enabled: Boolean) {
        if (_uiState.value.hasEventsOnly == enabled) return
        _uiState.update { it.copy(hasEventsOnly = enabled) }
        loadPlaces(reset = true)
    }

    fun setPlaceSort(sort: PlaceSort) {
        if (_uiState.value.placeSort == sort) return
        _uiState.update { it.copy(placeSort = sort) }
        // Server-side sort — refetch from offset 0 with the new sort slug
        // so non-popularity sorts pull the best venues across the full list,
        // not just the popularity top-N already loaded. loadPlaces() clears
        // the visible list and shows the skeleton until the response lands.
        loadPlaces(reset = true)
    }

    /** Top-level city chip. Tapping the active city clears it (back to "all
     *  cities in radius"); switching cities resets the venue-type drill-down. */
    fun setSelectedCity(city: String?) {
        val next = if (city == _uiState.value.selectedCity) null else city
        // Drop the previous city's sub-filter (venue-type) chips immediately so
        // they can't linger — or be tapped into a mismatched state — while the
        // new city's facets load. loadPlaces repopulates them: instantly on a
        // cache hit, or after the skeleton on a real fetch. Only cleared here
        // (city change), NOT in setSelectedVenueCategory / search / sort, so
        // the chip row doesn't skeleton-flicker on same-city refetches.
        _uiState.update {
            it.copy(selectedCity = next, selectedVenueCategory = null, categoryFacets = emptyList())
        }
        loadPlaces(reset = true)
    }

    /** Second-tier venue-type chip (raw venue.category slug). Tapping the
     *  active type clears it (back to "all types in this city"). */
    fun setSelectedVenueCategory(category: String?) {
        val next = if (category == _uiState.value.selectedVenueCategory) null else category
        _uiState.update { it.copy(selectedVenueCategory = next) }
        loadPlaces(reset = true)
    }

    private var searchDebounceJob: Job? = null
    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchDebounceJob?.cancel()
        searchDebounceJob = viewModelScope.launch {
            delay(180)
            loadPlaces(reset = true)
            loadEvents(reset = true)
        }
    }

    // ══════ Places ══════

    /** Coordinates a manual pull-to-refresh across the places + events loads. */
    private var refreshJob: Job? = null

    private var placesLoadJob: Job? = null
    private fun loadPlaces(reset: Boolean, force: Boolean = false) {
        placesLoadJob?.cancel()

        // Cache short-circuit (Option B) — same pattern as loadEvents. A manual
        // pull-to-refresh passes force=true to always hit the network.
        if (reset && !force) {
            val state0 = _uiState.value
            val key = state0.placesCacheKey()
            val hit = placesCache[key]
            if (hit != null && nowMs() - hit.savedAt < cacheTtlMs) {
                _uiState.update {
                    it.copy(
                        filteredVenues = hit.page.items,
                        venues = hit.page.items,
                        venueCursor = hit.page.cursor,
                        hasMore = hit.page.hasMore,
                        cityFacets = hit.page.cityFacets,
                        categoryFacets = hit.page.categoryFacets,
                        isLoading = false,
                        error = null,
                    )
                }
                return
            }
        }

        placesLoadJob = viewModelScope.launch {
            if (reset) {
                // Clear the visible list and flip on the skeleton so the user
                // sees an unambiguous "loading new results" state on filter /
                // sort / radius / search changes, instead of the old list
                // lingering until the response lands. Cache hits short-circuit
                // above this point, so this only fires on real fetches.
                _uiState.update {
                    it.copy(
                        filteredVenues = emptyList(),
                        venues = emptyList(),
                        venueCursor = null,
                        hasMore = false,
                        isLoading = true,
                        error = null,
                    )
                }
            }
            try {
                val state = _uiState.value
                val lat = state.userLat ?: METRO_LAT
                val lng = state.userLng ?: METRO_LNG
                val cursor = if (reset) null else state.venueCursor
                // The two-tier venue-type drill-down (raw slug from the facet)
                // takes precedence; fall back to the legacy selectedFilter map.
                val category = state.selectedVenueCategory
                    ?: state.selectedFilter.takeIf { it != "All" }?.let { filterToServerCategory(it) }

                val page = exploreRepository.fetchPlaces(
                    userLat = lat,
                    userLng = lng,
                    userId = state.userPreferences?.id,
                    query = state.searchQuery,
                    category = category,
                    city = state.selectedCity,
                    radiusMiles = state.radiusFilter,
                    hasEventsOnly = state.hasEventsOnly,
                    sort = state.placeSort.slug,
                    cursor = cursor,
                    limit = 50,
                )

                _uiState.update {
                    val merged = if (reset) page.items else it.filteredVenues + page.items
                    it.copy(
                        filteredVenues = merged,
                        venues = merged,
                        venueCursor = page.cursor,
                        hasMore = page.hasMore,
                        // Facets are computed over the full scored list, so
                        // they're identical across pages — safe to set always.
                        cityFacets = page.cityFacets,
                        categoryFacets = page.categoryFacets,
                        isLoading = false,
                        error = null,
                    )
                }
                if (reset && page.items.isNotEmpty()) {
                    // Don't cache empty pages — otherwise a transient 0-result
                    // (server hiccup, geo edge case) pins the tab blank for
                    // the full TTL on every revisit. Re-fetch instead.
                    val key = state.placesCacheKey()
                    placesCache[key] = CachedPlacesPage(page, nowMs())
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
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
    private fun loadEvents(reset: Boolean, force: Boolean = false) {
        eventsLoadJob?.cancel()

        // Cache short-circuit: switching back to a recently-viewed filter
        // combo on a reset should skip the network entirely. A manual
        // pull-to-refresh passes force=true to always hit the network.
        if (reset && !force) {
            val state0 = _uiState.value
            val key = state0.eventsCacheKey()
            val hit = eventsCache[key]
            if (hit != null && nowMs() - hit.savedAt < cacheTtlMs) {
                _uiState.update {
                    it.copy(
                        filteredEvents = hit.page.items,
                        events = hit.page.items,
                        eventCursor = hit.page.cursor,
                        eventsHasMore = hit.page.hasMore,
                        isEventsLoading = false,
                        error = null,
                    )
                }
                return
            }
        }

        eventsLoadJob = viewModelScope.launch {
            if (reset) {
                // Clear the visible list and flip on the skeleton so the user
                // sees an unambiguous "loading new results" state on filter /
                // date / radius / search changes, instead of the old list
                // lingering until the response lands. Cache hits short-circuit
                // above this point, so this only fires on real fetches.
                _uiState.update {
                    it.copy(
                        filteredEvents = emptyList(),
                        events = emptyList(),
                        eventCursor = null,
                        eventsHasMore = false,
                        isEventsLoading = true,
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

                // Only send the slug when CUSTOM has both endpoints picked,
                // otherwise CUSTOM-with-no-range would render an empty list.
                val activeSlug = when (state.dateFilter) {
                    EventDateFilter.CUSTOM -> if (state.customStart != null && state.customEnd != null) "custom" else null
                    null -> null
                    else -> state.dateFilter.slug
                }
                // (local − UTC) in minutes — server uses this to align the
                // today/tomorrow/weekend windows to the user's local midnight
                // instead of UTC midnight. kotlinx-datetime 0.7's `offsetAt`
                // moved, so we compute via a UTC↔local round-trip diff.
                val tzOffsetMin = run {
                    val nowMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
                    val instant = kotlinx.datetime.Instant.fromEpochMilliseconds(nowMs)
                    val localDt = instant.toLocalDateTime(TimeZone.currentSystemDefault())
                    val asUtcMs = localDt.toInstant(TimeZone.UTC).toEpochMilliseconds()
                    ((asUtcMs - nowMs) / 60_000L).toInt()
                }
                val page = exploreRepository.fetchEvents(
                    userLat = lat,
                    userLng = lng,
                    userId = state.userPreferences?.id,
                    query = state.searchQuery,
                    category = category,
                    radiusMiles = state.radiusFilter,
                    dateFilter = activeSlug,
                    dateStart = state.customStart?.toString(),
                    dateEnd = state.customEnd?.toString(),
                    tzOffsetMinutes = tzOffsetMin,
                    cursor = cursor,
                    limit = 50,
                )

                _uiState.update {
                    val merged = if (reset) page.items else it.filteredEvents + page.items
                    it.copy(
                        filteredEvents = merged,
                        events = merged,
                        eventCursor = page.cursor,
                        eventsHasMore = page.hasMore,
                        isEventsLoading = false,
                        error = null,
                    )
                }
                if (reset && page.items.isNotEmpty()) {
                    // Cache the first-page response for this filter combo so
                    // a quick back-and-forth tap returns instantly via the
                    // short-circuit at the top of loadEvents. Skip the empty
                    // case — otherwise a transient zero-result pins the tab
                    // blank for the full TTL on every revisit.
                    val key = state.eventsCacheKey()
                    eventsCache[key] = CachedEventsPage(page, nowMs())
                }
            } catch (e: Exception) {
                println("PlacesVM.loadEvents failed: ${e.message}")
                _uiState.update { it.copy(isEventsLoading = false, error = e.message) }
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
                venueRepository.getUpcomingEventsForVenue(venue.venue.name, venue.venue.city, venue.venue.latitude, venue.venue.longitude)
            } catch (_: Exception) {
                emptyList()
            }
            if (_uiState.value.selectedVenue?.venue?.id == venue.venue.id) {
                _uiState.update { it.copy(selectedVenueEvents = events, isSheetLoading = false) }
            }
        }
    }

    /**
     * Resolve a venue by its denormalized name + city (events carry no venue FK
     * in the read path — see EventDto) and open the venue detail overlay. Used by
     * the event-detail "view venue" affordance. No-op if no matching venue row
     * exists, so the caller can fall back gracefully.
     */
    fun openVenueByNameAndCity(name: String, city: String, distanceMiles: Double? = null) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val venue = try {
                venueRepository.findVenueByNameAndCity(name, city)
            } catch (_: Exception) {
                null
            } ?: return@launch
            selectVenue(
                VenueWithMeta(
                    venue = venue,
                    distanceMiles = distanceMiles,
                    googleRating = venue.googleRating,
                    primaryCategory = venue.category,
                )
            )
        }
    }

    fun dismissSheet() {
        _uiState.update { it.copy(selectedVenue = null, selectedVenueEvents = emptyList()) }
    }

    // ══════ Favorites ══════

    fun toggleVenueFavorite(venueId: String, category: String? = null) {
        if (userPreferencesRepository.getLocalUserId() == null) {
            onAuthRequired?.invoke(venueId, InteractionType.PLACE, category)
            return
        }
        val current = _uiState.value.favoriteVenueIds
        if (venueId in current) {
            communityInterestRepository.removeFavorite(venueId, InteractionType.PLACE)
            _uiState.update {
                it.copy(
                    favoriteVenueIds = current - venueId,
                    // savedVenues is independent of the active sort/search page —
                    // drop the unsaved venue directly rather than re-deriving.
                    savedVenues = it.savedVenues.filterNot { v -> v.venue.id == venueId },
                )
            }
        } else {
            communityInterestRepository.recordFavorite(venueId, InteractionType.PLACE, category)
            // The just-saved venue is on screen (filtered list or saved carousel)
            // — add it optimistically, with full meta where available.
            val state = _uiState.value
            val venue = (state.filteredVenues + state.savedVenues).firstOrNull { it.venue.id == venueId }
            _uiState.update {
                it.copy(
                    favoriteVenueIds = current + venueId,
                    savedVenues = if (venue != null && it.savedVenues.none { v -> v.venue.id == venueId }) {
                        it.savedVenues + venue
                    } else {
                        it.savedVenues
                    },
                )
            }
            // Badge: track place saved (mirrors EVENT_SAVED in HomeViewModel)
            badgeService.recordAction(BadgeAction.PLACE_SAVED, deriveUserState())
        }
    }

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

    fun toggleEventFavorite(eventId: String, category: String? = null, source: String = "places_card") {
        if (userPreferencesRepository.getLocalUserId() == null) {
            onAuthRequired?.invoke(eventId, InteractionType.EVENT, category)
            return
        }
        val current = _uiState.value.favoriteEventIds
        if (eventId in current) {
            communityInterestRepository.removeFavorite(eventId, InteractionType.EVENT)
            _uiState.update {
                it.copy(
                    favoriteEventIds = current - eventId,
                    // savedEvents is independent of the active date filter — drop
                    // the unsaved event from it directly rather than re-deriving
                    // from filteredEvents (which a filter may have excluded).
                    savedEvents = it.savedEvents.filterNot { e -> e.id == eventId },
                )
            }
            live.localapp.local.observability.Analytics.track(
                live.localapp.local.observability.AnalyticsEvent.eventUnsaved(eventId = eventId)
            )
        } else {
            communityInterestRepository.recordFavorite(eventId, InteractionType.EVENT, category)
            live.localapp.local.observability.Analytics.track(
                live.localapp.local.observability.AnalyticsEvent.eventSaved(eventId = eventId, source = source)
            )
            // The just-saved event is on screen (in the filtered list or the
            // saved section) — add it optimistically so the saved carousel
            // updates without a round-trip and regardless of the active filter.
            val state = _uiState.value
            val event = (state.filteredEvents + state.savedEvents).firstOrNull { it.id == eventId }
            _uiState.update {
                it.copy(
                    favoriteEventIds = current + eventId,
                    savedEvents = if (event != null && it.savedEvents.none { e -> e.id == eventId }) {
                        // Keep start-date sorted so a newly-saved event slots in by
                        // date (matching Home), not appended to the end.
                        (it.savedEvents + event).sortedBy { e -> e.startAt ?: "" }
                    } else {
                        it.savedEvents
                    },
                )
            }
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
            // Hydrate the saved carousels from the full favorite sets,
            // independent of the active filters — by-id fetches surface saved
            // items even when they fall outside the current page.
            // Drop past events so Saved Events sheds them once their date passes.
            // Sort by start date (soonest first) to match the Home saved-events
            // carousel (HomeViewModel: savedUpcoming.sortedBy { startAt }). Without
            // this the Explore carousel showed most-recently-saved order.
            val savedEvents = if (eventIds.isEmpty()) emptyList()
                else eventRepository.getEventsByIds(eventIds).filter { it.isUpcoming }
                    .sortedBy { it.startAt ?: "" }
            val savedVenues = if (venueIds.isEmpty()) {
                emptyList()
            } else {
                venueRepository.getVenuesByIds(venueIds).map { VenueWithMeta(venue = it, primaryCategory = it.category) }
            }
            _uiState.update { it.copy(savedEvents = savedEvents, savedVenues = savedVenues) }
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
