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
    // Per-category carousel storage. Switching tabs no longer clears the prior
    // tab's data — returning to a category renders its cached carousels
    // immediately (and re-fetches in the background). Keeping the section
    // composables alive across tab swaps is what lets `rememberSaveable` in
    // EventCardRow preserve each carousel's scroll position per (category,
    // carousel slug). LRU-capped via putWithLru below.
    val carouselsByCategory: Map<String, List<CarouselSection>> = emptyMap(),
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
) {
    /** Carousels visible for the currently-selected category. Derived so all
     *  existing reads of `state.carousels` keep working unchanged after the
     *  per-category reshape. Excluded from equals/hashCode (not a ctor prop). */
    val carousels: List<CarouselSection> get() = carouselsByCategory[selectedCategory].orEmpty()
}

private const val CAROUSEL_CACHE_CAP = 8

/** Insert-or-update with LRU semantics: the touched key moves to the most-
 *  recent slot, oldest entries are evicted past [cap]. Keeps the per-category
 *  map bounded across long sessions of tab-swiping. */
private fun Map<String, List<CarouselSection>>.putWithLru(
    key: String,
    value: List<CarouselSection>,
    cap: Int = CAROUSEL_CACHE_CAP,
): Map<String, List<CarouselSection>> {
    val next = LinkedHashMap<String, List<CarouselSection>>(this.size + 1)
    for ((k, v) in this) if (k != key) next[k] = v
    next[key] = value
    while (next.size > cap) next.remove(next.keys.first())
    return next
}

enum class LocationMode { CURRENT_LOCATION, MANUAL }

// Bounding boxes for supported states (approximate)
private data class StateBounds(val code: String, val latMin: Double, val latMax: Double, val lngMin: Double, val lngMax: Double)
// NOTE: must stay in sync with the `states` table (is_live=true rows). This
// list is hardcoded (not read from the table) so it ships with the app binary —
// adding a live state requires a release, not just a DB flip. This list ships in
// the batched NY+NJ release; both rows must be is_live=true in prod before/at the
// release going out so the binary and the DB agree.
private val SUPPORTED_STATE_BOUNDS = listOf(
    StateBounds("MA", 41.2, 42.9, -73.5, -69.9),
    StateBounds("RI", 41.1, 42.1, -72.0, -71.1),
    StateBounds("PA", 39.7, 42.3, -80.6, -74.6),
    StateBounds("NY", 40.4, 45.1, -79.8, -71.8),
    // NJ box is loose and overlaps NY (north Jersey) + PA (Delaware Valley); the
    // server-side resolveUserState tie-break (prefer is_live, then nearest center)
    // disambiguates cross-river metros. Client bounds only gate "is this a
    // supported state at all", so the overlap is harmless here.
    StateBounds("NJ", 38.9, 41.4, -75.6, -73.9),
)

fun isLocationInSupportedState(lat: Double?, lng: Double?): Boolean {
    if (lat == null || lng == null) return true // assume supported if no location
    return SUPPORTED_STATE_BOUNDS.any { s ->
        lat >= s.latMin && lat <= s.latMax && lng >= s.lngMin && lng <= s.lngMax
    }
}

// Sample cities per supported state. Drives the placeholder in Bo's scope
// editor sheet so users in PA don't see MA towns as examples.
private val PLACEHOLDER_CITIES_BY_STATE = mapOf(
    "MA" to listOf("Brookline", "Cambridge", "Hyannis"),
    "RI" to listOf("Providence", "Newport", "Warwick"),
    "PA" to listOf("Philadelphia", "Pittsburgh", "Lancaster"),
    "NY" to listOf("New York City", "Buffalo", "Albany"),
    "NJ" to listOf("Newark", "Jersey City", "Princeton"),
)
private val DEFAULT_PLACEHOLDER_CITIES = listOf("a city or neighborhood")

fun scopeEditorPlaceholder(lat: Double?, lng: Double?): String {
    val cities = if (lat != null && lng != null) {
        SUPPORTED_STATE_BOUNDS.firstOrNull { s ->
            lat >= s.latMin && lat <= s.latMax && lng >= s.lngMin && lng <= s.lngMax
        }?.let { PLACEHOLDER_CITIES_BY_STATE[it.code] } ?: DEFAULT_PLACEHOLDER_CITIES
    } else DEFAULT_PLACEHOLDER_CITIES
    return "e.g. ${cities.joinToString(", ")}"
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

        // Kick the typewriter-prompt fetch off immediately, in parallel with the
        // slower prefs/carousel load. On a cold start that sequence (Supabase
        // round-trip → carousels → Bo chips) can take >6s, which left the For You
        // typewriter timing out to the generic fallback and — because the choice
        // is sticky per category — staying there until the user left and returned
        // to the tab. Seed from the locally-cached manual coords when present,
        // else the metro default. The resolved fetch at the end of
        // loadUserPreferencesThenCarousels supersedes this if the grid differs.
        val cachedLoc = userPreferencesRepository.getCachedManualLocation()
        fetchYocalPromptsIfNeeded(cachedLoc.manualLat ?: 42.3601, cachedLoc.manualLng ?: -71.0589)

        loadUserPreferencesThenCarousels()
        loadSavedThisWeek()
    }

    /**
     * Re-run the prefs load + carousel fetch. Use this after onboarding
     * completes: HomeViewModel is a Koin singleton, so its initial
     * loadUserPreferencesThenCarousels() ran before auth/onboarding (no
     * userId), leaving state.userPreferences = null. Without this hook
     * the For You feed keeps firing anonymous edge-fn calls forever and
     * favVenue/neighborhood rails never appear.
     */
    fun refreshUserPreferences() {
        loadUserPreferencesThenCarousels()
    }

    private fun loadUserPreferencesThenCarousels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            // Hydrate the location chip from the local cache up-front so the
            // chip always renders something for a logged-in user on cold
            // start, even while the Supabase round-trip is in flight. Order
            // of preference: cached manual address → last reverse-geocoded
            // GPS city → onboarding city. The remote load below reconciles.
            val cache = userPreferencesRepository.getCachedManualLocation()
            val cachedGps = userPreferencesRepository.getCachedGpsLocationName().orEmpty()
            val cachedCity = userPreferencesRepository.getCachedUserCity().orEmpty()
                .replaceFirstChar { c -> c.uppercase() }
            // Only default to CURRENT_LOCATION when the user explicitly chose it
            // AND we actually hold location permission. A null/unknown cached mode
            // (or a stored "current" we can't act on) falls back to MANUAL rather
            // than silently flipping a hand-entered address to GPS and firing the
            // OS prompt. Mirrors the permission gate applied to the remote load below.
            val cachedMode = when {
                cache.locationMode == "manual" -> LocationMode.MANUAL
                live.localapp.local.platform.checkLocationPermissionGranted() -> LocationMode.CURRENT_LOCATION
                else -> LocationMode.MANUAL
            }
            val cachedManualName = cache.manualLocationName.orEmpty()
            val fallbackName = when {
                cachedMode == LocationMode.MANUAL && cachedManualName.isNotBlank() -> cachedManualName
                cachedGps.isNotBlank() -> cachedGps
                cachedCity.isNotBlank() -> cachedCity
                else -> ""
            }
            if (fallbackName.isNotBlank() || cache.locationMode != null) {
                _uiState.update {
                    it.copy(
                        locationMode = cachedMode,
                        manualLocationName = cachedManualName,
                        manualLat = cache.manualLat,
                        manualLng = cache.manualLng,
                        gpsLocationName = cachedGps,
                        userLat = if (cachedMode == LocationMode.MANUAL) cache.manualLat ?: it.userLat else it.userLat,
                        userLng = if (cachedMode == LocationMode.MANUAL) cache.manualLng ?: it.userLng else it.userLng,
                        locationName = if (it.locationName.isBlank() && fallbackName.isNotBlank()) fallbackName else it.locationName,
                        isUnsupportedState = if (cachedMode == LocationMode.MANUAL && cache.manualLat != null && cache.manualLng != null)
                            !isLocationInSupportedState(cache.manualLat, cache.manualLng) else it.isUnsupportedState,
                    )
                }
            }

            // Try local cache first, recover from Supabase if lost
            val userId = userPreferencesRepository.getLocalUserId()
                ?: userPreferencesRepository.recoverProfile()
            if (userId != null) {
                val remote = userPreferencesRepository.loadPreferences(userId)
                if (remote != null) {
                    // Backfill any manual-location fields missing from the remote row
                    // with the locally-cached values. Covers the race where the user
                    // picked an address then killed the app before the Supabase write
                    // completed — the chip would otherwise revert to the default city.
                    val cacheBeforeMerge = userPreferencesRepository.getCachedManualLocation()
                    val remoteHasManual = remote.manualLocationName != null || remote.manualLat != null
                    val prefs = remote.copy(
                        locationMode = if (!remoteHasManual && cacheBeforeMerge.locationMode != null) cacheBeforeMerge.locationMode else remote.locationMode,
                        manualLocationName = remote.manualLocationName ?: cacheBeforeMerge.manualLocationName,
                        manualLat = remote.manualLat ?: cacheBeforeMerge.manualLat,
                        manualLng = remote.manualLng ?: cacheBeforeMerge.manualLng,
                    )
                    // Mirror remote into the local cache so future cold starts can
                    // hydrate immediately. Covers users whose address was saved
                    // before the local-cache code existed — their cache is empty
                    // until this runs once.
                    userPreferencesRepository.cacheManualLocationLocally(
                        locationMode = prefs.locationMode,
                        manualLocationName = prefs.manualLocationName,
                        manualLat = prefs.manualLat,
                        manualLng = prefs.manualLng,
                    )
                    val cityDisplay = prefs.city.replaceFirstChar { c -> c.uppercase() }
                    val hasPermission = live.localapp.local.platform.checkLocationPermissionGranted()
                    val storedMode = if (prefs.locationMode == "manual") LocationMode.MANUAL else LocationMode.CURRENT_LOCATION
                    // If stored as "current" but we don't have permission, treat as manual
                    var mode = if (storedMode == LocationMode.CURRENT_LOCATION && !hasPermission) LocationMode.MANUAL else storedMode
                    // Coord salvage: when MANUAL is the saved mode but manualLat/Lng
                    // weren't separately persisted (e.g. an autocomplete pick that
                    // skipped the dedicated columns), prefs.userLat/userLng still
                    // carry the chosen address coords from onboarding. Use those
                    // instead of flipping the user back to CURRENT_LOCATION — that
                    // flip silently triggered the OS GPS prompt for users who
                    // explicitly picked Manual.
                    val manualCoordsMissing = mode == LocationMode.MANUAL && (prefs.manualLat == null || prefs.manualLng == null)
                    val manualCorrupted = manualCoordsMissing && prefs.userLat == null && prefs.userLng == null
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
            reloadCarouselsForLocationChange()
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
                // Cache the resolved name so the chip can render it instantly
                // on the next cold start, instead of waiting on the geocode
                // round-trip and showing a blank pill.
                userPreferencesRepository.cacheGpsLocationNameLocally(display)
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
        // Mirror the mode flip locally so it survives a kill before the
        // Supabase write lands. Manual coords/name are preserved so the user
        // can flip back to MANUAL without re-entering the address.
        userPreferencesRepository.cacheManualLocationLocally(
            locationMode = if (mode == LocationMode.MANUAL) "manual" else "current",
            manualLocationName = current.manualLocationName,
            manualLat = current.manualLat,
            manualLng = current.manualLng,
        )
        when (mode) {
            LocationMode.MANUAL -> {
                if (current.manualLat != null && current.manualLng != null) {
                    _uiState.update { it.copy(userLat = current.manualLat, userLng = current.manualLng, locationName = current.manualLocationName) }
                    reloadCarouselsForLocationChange()
                }
            }
            LocationMode.CURRENT_LOCATION -> {
                // Restore the GPS-resolved name immediately so the pill flips back
                // from the manual address. If we already have a GPS fix from the
                // session's RequestLocationEffect, we can also restore the coords
                // and trigger a hard reload right now so the user doesn't sit on
                // the previous manual market's carousels waiting for the next GPS
                // tick. The lat/lng we keep around from setUserLocation lives in
                // userPreferences.userLat/userLng (synced via saveLocationPrefs).
                if (current.gpsLocationName.isNotBlank()) {
                    _uiState.update { it.copy(locationName = current.gpsLocationName) }
                }
                val prefs = current.userPreferences
                val gpsLat = prefs?.userLat
                val gpsLng = prefs?.userLng
                if (gpsLat != null && gpsLng != null) {
                    _uiState.update {
                        it.copy(userLat = gpsLat, userLng = gpsLng,
                                isUnsupportedState = !isLocationInSupportedState(gpsLat, gpsLng))
                    }
                    reloadCarouselsForLocationChange()
                } else {
                    // No cached GPS coords — still clear the stale manual rails
                    // and show the skeleton; the next RequestLocationEffect tick
                    // will land setUserLocation and trigger the fetch.
                    carouselRepository.invalidateAll()
                    _uiState.update {
                        it.copy(carouselsByCategory = emptyMap(), isLoading = true, error = null)
                    }
                }
            }
        }
        saveLocationPrefs()
    }

    fun setManualLocation(lat: Double, lng: Double, name: String) {
        val displayName = name.removeSuffix(", USA").removeSuffix(", United States").replace(Regex(",\\s*[A-Z]{2}$"), "")
        // Cache locally up-front so the selection survives an immediate app
        // kill that races the Supabase write below.
        userPreferencesRepository.cacheManualLocationLocally(
            locationMode = "manual",
            manualLocationName = displayName,
            manualLat = lat,
            manualLng = lng,
        )
        _uiState.update {
            it.copy(
                manualLat = lat, manualLng = lng, manualLocationName = displayName,
                userLat = lat, userLng = lng, locationName = displayName,
                locationMode = LocationMode.MANUAL,
                isUnsupportedState = !isLocationInSupportedState(lat, lng),
            )
        }
        reloadCarouselsForLocationChange()
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
        // Skip the no-op tap so we don't blank out the current tab's content.
        if (_uiState.value.selectedCategory == category) return
        // Don't clear prior data — keep it in carouselsByCategory so it renders
        // instantly if cached, preserving each carousel's scroll position via
        // rememberSaveable on EventCardRow. Show the skeleton only when there's
        // nothing cached for the new tab.
        val hasCached = _uiState.value.carouselsByCategory[category]?.isNotEmpty() == true
        _uiState.update {
            it.copy(selectedCategory = category, isLoading = !hasCached, error = null)
        }
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

    fun toggleEventFavorite(eventId: String, category: String? = null, source: String = "home_card") {
        val isFav = eventId in _uiState.value.favoriteEventIds
        if (isFav) {
            communityInterestRepository.removeFavorite(eventId, InteractionType.EVENT)
            _uiState.update {
                it.copy(
                    favoriteEventIds = it.favoriteEventIds - eventId,
                    savedThisWeek = it.savedThisWeek.filterNot { e -> e.id == eventId },
                )
            }
            live.localapp.local.observability.Analytics.track(
                live.localapp.local.observability.AnalyticsEvent.eventUnsaved(eventId = eventId)
            )
        } else {
            communityInterestRepository.recordFavorite(eventId, InteractionType.EVENT, category)
            _uiState.update { it.copy(favoriteEventIds = it.favoriteEventIds + eventId) }
            live.localapp.local.observability.Analytics.track(
                live.localapp.local.observability.AnalyticsEvent.eventSaved(eventId = eventId, source = source)
            )
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
        if (_uiState.value.radiusFilter == miles) return
        _uiState.update { it.copy(radiusFilter = miles) }
        // Radius affects which events the edge function returns (it drives
        // the bbox + post-filter), so a fresh fetch is required. Without
        // this, the user would just see the previously-fetched pool
        // re-sliced client-side, leaving top rails sparse when expanding
        // and unchanged when shrinking (cache keys on tab:uid only).
        reloadCarouselsForLocationChange()
    }

    /**
     * Hard reload for any location-affecting change (radius, address, GPS
     * mode toggle). Clears the visible carousels so the skeleton loader
     * paints immediately — without this, isLoading stays false (it's
     * guarded by `it.carousels.isEmpty()` in loadCarousels) and the user
     * sees stale rails until the new fetch lands, which is misleading
     * when the pool they're looking at no longer matches their settings.
     *
     * Invalidates all carouselRepository caches (in-memory + disk
     * boundary) so the fresh fetch can't be short-circuited.
     */
    private fun reloadCarouselsForLocationChange() {
        carouselRepository.invalidateAll()
        _uiState.update {
            it.copy(
                carouselsByCategory = emptyMap(),
                isLoading = true,
                error = null,
            )
        }
        loadCarousels(skipDiskCache = true)
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

    private fun loadCarousels(loadCategories: Boolean = true, skipDiskCache: Boolean = false) {
        carouselJob?.cancel()
        carouselJob = viewModelScope.launch {
            // Stale-while-revalidate: paint disk-cached carousels immediately
            // (sub-100ms), then fire the fresh fetch. The user sees the
            // previous session's home tab on cold open instead of a blank
            // loading state for the 3-8s the edge function takes.
            //
            // skipDiskCache short-circuits this path for "hard reload" cases
            // (radius change, address change, GPS-mode toggle) where the
            // disk-cached payload was fetched against a different radius /
            // location AND we want the user to see the skeleton, not stale
            // rails, until the fresh response with their new settings lands.
            val state = _uiState.value
            val category = state.selectedCategory
            if (!skipDiskCache) try {
                val cached = carouselRepository.getCachedCarouselsFromDisk(
                    category = if (category == "foryou") null else category,
                    userPreferences = state.userPreferences,
                    locationName = state.locationName,
                )
                if (!cached.isNullOrEmpty()) {
                    // Disk read also hydrates the in-memory categories cache —
                    // pull it into state so the tab strip renders alongside the
                    // carousels. Otherwise the strip stays at "For You" only
                    // until the fresh fetch returns (or forever, if it fails).
                    val cachedCats = carouselRepository.peekCachedCategories()
                    _uiState.update { s ->
                        val nextMap = s.carouselsByCategory.putWithLru(category, cached)
                        val nextCats = if (cachedCats.isNotEmpty()) cachedCats else s.availableCategories
                        if (s.selectedCategory != category) {
                            // User switched while disk read was in flight — stash
                            // the data but don't touch the visible loading state.
                            s.copy(carouselsByCategory = nextMap, availableCategories = nextCats)
                        } else {
                            s.copy(
                                carouselsByCategory = nextMap,
                                availableCategories = nextCats,
                                isLoading = false,
                                error = null,
                            )
                        }
                    }
                }
            } catch (_: Throwable) { /* disk-read failure is never fatal — fresh fetch handles it */ }

            _uiState.update { it.copy(isLoading = it.carousels.isEmpty(), error = null) }
            try {
                val s = _uiState.value
                val lat = s.userLat ?: s.userPreferences?.userLat ?: 42.3601
                val lng = s.userLng ?: s.userPreferences?.userLng ?: -71.0589

                // "Any" radius = null in UI; we pass 200 mi to the edge fn
                // (covers all of New England comfortably; the MV bbox + per-
                // subcategory caps keep payload bounded).
                val radiusMiles = state.radiusFilter?.toInt() ?: 200
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
                    radiusMiles = radiusMiles,
                )
                val categories = if (loadCategories || state.availableCategories.isEmpty()) {
                    carouselRepository.getAvailableCategories(
                        userPreferences = state.userPreferences,
                        userLat = lat,
                        userLng = lng,
                    )
                } else state.availableCategories
                // With per-category storage, a slow fetch arriving after the
                // user switched tabs writes safely into its own slot — the
                // visible tab is derived from `selectedCategory`, so there's
                // no chance of Music's data flashing on For You. We still
                // guard the visible loading/refresh flags so they only flip
                // when the response matches what the user is looking at.
                _uiState.update { s ->
                    val nextMap = s.carouselsByCategory.putWithLru(category, carousels)
                    if (s.selectedCategory != category) {
                        s.copy(
                            carouselsByCategory = nextMap,
                            availableCategories = categories,
                        )
                    } else {
                        s.copy(
                            carouselsByCategory = nextMap,
                            availableCategories = categories,
                            isLoading = false,
                            isRefreshing = false,
                        )
                    }
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
                        val retryRadius = s.radiusFilter?.toInt() ?: 200
                        val carousels = carouselRepository.getCarousels(
                            retryLat, retryLng,
                            if (retryCategory == "foryou") null else retryCategory,
                            s.userPreferences, s.locationName,
                            radiusMiles = retryRadius,
                        )
                        val cats = carouselRepository.getAvailableCategories(s.userPreferences, retryLat, retryLng)
                        _uiState.update { cur ->
                            val nextMap = cur.carouselsByCategory.putWithLru(retryCategory, carousels)
                            if (cur.selectedCategory != retryCategory) {
                                cur.copy(carouselsByCategory = nextMap, availableCategories = cats)
                            } else {
                                cur.copy(
                                    carouselsByCategory = nextMap,
                                    availableCategories = cats,
                                    isLoading = false,
                                    isRefreshing = false,
                                    error = null,
                                )
                            }
                        }
                        return@launch
                    } catch (_: Exception) {}
                }
                _uiState.update { it.copy(error = e.message, isLoading = false, isRefreshing = false) }
            }
        }
    }
}
