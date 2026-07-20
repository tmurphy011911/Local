package live.localapp.local.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import live.localapp.local.data.remote.ClaudeApiService
import live.localapp.local.data.remote.WeatherService
import live.localapp.local.data.remote.ClaudeMessage
import live.localapp.local.data.remote.ClaudeResponse
import live.localapp.local.data.remote.ParsedChatResponse
import live.localapp.local.data.remote.ParsedResponseType
import live.localapp.local.data.remote.StreamEvent
import live.localapp.local.data.remote.claudeTools
import live.localapp.local.data.remote.toolsWithWeb
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import live.localapp.local.Config
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.repository.EventRepository
import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.model.PlanAttendee
import live.localapp.local.domain.model.PlanDay
import live.localapp.local.domain.model.PlanSlot
import live.localapp.local.domain.model.PlanTravel
import live.localapp.local.domain.model.BadgeAction
import live.localapp.local.domain.model.TasteProfile
import live.localapp.local.domain.repository.PlanRepository
import live.localapp.local.domain.service.BadgeService
import live.localapp.local.domain.repository.UserPreferences
import live.localapp.local.domain.repository.UserPreferencesRepository
import live.localapp.local.domain.repository.TasteRepository
import live.localapp.local.domain.repository.VenueRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// ── Message models ──

/**
 * Canonical instruction sent when the user taps the "Build from web" footer
 * action on an empty-DB suggestion tray. Shared between the chip (which sends
 * it) and [ChatViewModel.handleMessage] (which detects it to open the
 * save_web_finding tool gate for this turn — see toolsForRound). Keep the two
 * in lockstep via this constant; string-matching a literal would silently
 * break the gate if either copy drifted.
 */
const val BUILD_FROM_WEB_INTENT =
    "Build a plan from the web findings you just surfaced. For each promising event, call save_web_finding(kind='event') to stage it, then call present_plan with the returned bo_web_events ids as the slot event_ids (with is_bo_web: true). Don't ask another question first — just build the plan."

enum class MessageRole { AI, USER }

sealed interface MessageContent {
    data class Text(val text: String) : MessageContent
    data class DayCardRef(val dayIndex: Int, val snapshot: List<ItineraryDay>? = null) : MessageContent
    data class MultiDayPlanRef(val dayCount: Int, val snapshot: List<ItineraryDay>? = null) : MessageContent
    /** Phase 6 — Bo's structured-response payload, to be rendered inline. */
    data class Structured(
        val response: live.localapp.local.domain.model.BoStructuredResponse,
        val hydratedEvents: Map<String, live.localapp.local.domain.model.Event> = emptyMap(),
    ) : MessageContent
}

/** Whether a plan message has been superseded by a later update. */
var supersededPlanIds = mutableSetOf<String>()

data class ChatMessage(
    val id: String = generateId(),
    val role: MessageRole,
    val content: MessageContent,
)

data class QuickReply(
    val label: String,
    val isGreen: Boolean = false,
    val action: String = label,
)

/**
 * Captured at the moment the user taps "Create plan from N events" on the
 * iOS suggestion tray. Held while we run the client-side confirmation
 * dialog (Bo confirmation message + chips). When the user picks a chip
 * the appropriate build intent is sent to Bo using these event_ids; the
 * field is then cleared. Reset to null whenever the chat resets.
 */
data class BuildHandoff(
    val eventIds: List<String>,
    val addingToExisting: Boolean,
)

// ── Itinerary models ──

data class ItinerarySlot(
    val id: String,
    val time: String,
    val emoji: String,
    val name: String,
    val detail: String,
    val price: String,
    val isFree: Boolean,
    val imageUrl: String? = null,
    val eventId: String? = null,
    val isCustom: Boolean = false,
    val isBoWeb: Boolean = false,
    val ticketUrl: String? = null,
    val venueRating: Double? = null,
    val venueAddress: String? = null,
    val venueCity: String? = null,
    val venueName: String? = null,
    val venueLat: Double? = null,
    val venueLng: Double? = null,
    val priceMin: Double? = null,
    val priceMax: Double? = null,
    val reservationUrl: String? = null,
    val sourceUrl: String? = null,
    val venueWebsite: String? = null,
    val venueCategory: String? = null,
)

data class TravelInfo(
    val mode: String, // "walk" or "drive"
    val mins: Int,
)

data class ItineraryDay(
    val label: String,
    val shortName: String,
    val slots: List<ItinerarySlot> = emptyList(),
    val isApproved: Boolean = false,
    val travel: List<TravelInfo> = emptyList(),
)

// ── UI State ──

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val quickReplies: List<QuickReply> = emptyList(),
    val isTyping: Boolean = false,
    val isBusy: Boolean = false,
    val isSearching: Boolean = false,
    val inputEnabled: Boolean = true,
    val inputPlaceholder: String = "What are you looking for?",
    // Streaming text — words appearing in real-time from Claude
    val streamingText: String = "",
    // Real-time narration of what Bo is doing while searching, derived from
    // the tool-call args (not Claude prose). Ordered steps, e.g.
    // ["Searching within 20 miles of Waltham", "Filtering to your dates"].
    // Empty = the indicator falls back to the playful rotating gerunds.
    val searchingNarration: List<String> = emptyList(),
    // Hyperlocal "searching" idioms generated by Claude for the user's set
    // location (e.g. Philly → "Taking the Broad Street Line", "Avoiding traffic
    // on the Schuylkill"). The searching indicator interleaves one of these
    // every ~3rd rotation to forge an emotional, place-specific connection.
    // Empty = the indicator shows only the generic rotating gerunds.
    val localIdioms: List<String> = emptyList(),
    // One-time prose acknowledgment shown ABOVE the searching steps at the
    // start of a browse turn (e.g. "Let me find Live music this week for you.
    // Searching within 20 miles of Waltham."). Echoes the user's request +
    // scope so the wait opens with a human beat before the ✓ steps animate.
    // Set only for genuine browse intents (gated on inferBrowseSearchInput);
    // null on plan/approve/chat turns so it never flashes awkward text.
    val searchingOpener: String? = null,
    // Weather forecasts keyed by day index
    val weather: Map<Int, live.localapp.local.data.remote.WeatherForecast> = emptyMap(),
    // Itinerary state
    val days: List<ItineraryDay> = emptyList(),
    val approvedDayCount: Int = 0,
    val currentDayIndex: Int = 0,
    val planTitle: String = "",
    val planAttendeeCount: Int = 1,
    /**
     * Phase 6 — draft tray contents. Persists across multiple Bo turns so
     * the user can browse, add events, browse more, add more, then tap
     * Build to ship the entire list to Bo as one prompt. Cleared on
     * plan completion or chat reset.
     */
    val draftTray: List<live.localapp.local.domain.model.DraftTrayEvent> = emptyList(),
    /**
     * Phase 6 — chips computed from draftTray contents. Rendered above the
     * Build button. Re-computed by DraftTrayChipGenerator whenever the
     * tray changes.
     */
    val draftTrayChips: List<live.localapp.local.domain.model.DraftTrayChip> = emptyList(),
    /**
     * The goal the user picked from the intent sheet on open (Find events /
     * Create a plan / Just browsing). Null until they choose — that null is
     * what tells the UI to present the sheet. Cleared on chat reset so a fresh
     * session re-asks. Drives Bo's framing copy + whether saving auto-drafts a
     * plan (CREATE_PLAN) vs. waits for an explicit ask (FIND_EVENTS/BROWSING).
     */
    val boIntent: BoIntent? = null,
    /**
     * Persistent saved-events set (favorites in community_interactions). The
     * ❤️ on a result card toggles membership here AND records/removes the
     * favorite server-side, so saves show up in the user's Saved Events and
     * survive closing Bo. Loaded on open; drives the filled-heart state.
     */
    val favoriteEventIds: Set<String> = emptySet(),
    /**
     * Set while the client-side "Create plan" confirmation dialog is open.
     * Null when no handoff is in flight. Populated when the user taps the
     * iOS Build CTA; cleared when they pick a final chip ("No, create",
     * "Add meals", "Add drinks", "Add my own event", "Something else") or
     * the chat is reset.
     */
    val pendingBuildHandoff: BuildHandoff? = null,
    /**
     * Set when the user enters chat from a saved-event "build a plan around X"
     * tap. Tells Bo (via the system prompt) to use this exact event_id as the
     * anchor slot in the plan they're about to build, without showing the
     * messy "event_id: evt:abc" string in the visible user message.
     * Cleared after the first turn so it doesn't bleed into unrelated chats.
     */
    val pendingAnchorEventId: String? = null,
    val pendingAnchorEventTitle: String? = null,
    // Plan success overlay
    val showPlanSuccess: Boolean = false,
    // Server-side id of the plan that triggered the current success overlay,
    // so the celebration's Share CTA can generate a real share URL.
    val lastSavedPlanId: String? = null,
    val lastSavedPlanTitle: String = "",
    val lastSavedPlanCity: String = "",
    // Auth required prompt
    val showAuthPrompt: Boolean = false,
    // User info
    val userName: String = "",
    val userPreferences: UserPreferences? = null,
    // Current location (may differ from prefs if user switched via debug menu)
    val currentLat: Double? = null,
    val currentLng: Double? = null,
    val currentLocationName: String = "",
    /** Search radius in miles. Drives the radius slider in the scope editor +
     *  the bbox query in searchEvents. Defaults to 20mi, the legacy implicit
     *  value. */
    val searchRadiusMiles: Double = 20.0,
)

private var idCounter = 0L
private fun generateId(): String = "msg_${++idCounter}"

class ChatViewModel(
    private val claudeApi: ClaudeApiService,
    private val eventRepo: EventRepository,
    private val venueRepo: VenueRepository,
    private val userPrefsRepo: UserPreferencesRepository,
    private val planRepo: PlanRepository,
    private val badgeService: BadgeService,
    private val tasteRepo: TasteRepository,
    private val boTools: live.localapp.local.data.bo.BoTools,
    private val boChipsRepo: live.localapp.local.domain.repository.BoChipsRepository,
    private val communityInterestRepository: live.localapp.local.domain.repository.CommunityInterestRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /**
     * External gate for plan approval. Set by the UI layer.
     * Returns true if the plan can be approved, false if the paywall should show.
     */
    var onPlanApprovalCheck: (() -> Boolean)? = null

    /** Called when plan approval is blocked by the gate (paywall needed). */
    var onPlanBlocked: (() -> Unit)? = null

    /** Called after a plan is successfully saved. */
    var onPlanSaved: (() -> Unit)? = null

    /** Persistent storage for the search-scope banner. Closing and re-opening
     *  Bo used to drop the chip back to default; now we save the user's
     *  chosen location + coords + radius and restore on init. */
    private val scopeSettings = com.russhwolf.settings.Settings()
    private companion object {
        const val PREF_SCOPE_NAME = "bo.scope.name"
        const val PREF_SCOPE_LAT = "bo.scope.lat"
        const val PREF_SCOPE_LNG = "bo.scope.lng"
        const val PREF_SCOPE_RADIUS = "bo.scope.radius_miles"
        // Sentinel tool_result content returned when a data tool hit a network
        // timeout. The tool loop's circuit breaker counts consecutive rounds
        // where every data tool returned this and bails early.
        const val TOOL_INFRA_TIMEOUT_MARKER = """{"error":"infra_timeout"}"""
        // Sentinel returned when Bo emits a CLIENT tool_use for a server-side
        // tool (web_search / web_fetch) that the current round didn't include.
        // Haiku does this when the prompt urges a search but the per-round gate
        // omitted the tool. The loop turns this into a "tool now enabled, retry"
        // signal instead of the old "Unknown tool" dead end that made Bo answer
        // from DB scraps only (the "web result cut off" bug).
        const val TOOL_WEB_PHANTOM_MARKER = """{"_phantom_web_tool":true}"""
    }

    private fun persistScope(name: String, lat: Double?, lng: Double?, radiusMiles: Double) {
        try {
            scopeSettings.putString(PREF_SCOPE_NAME, name)
            if (lat != null && lng != null) {
                scopeSettings.putDouble(PREF_SCOPE_LAT, lat)
                scopeSettings.putDouble(PREF_SCOPE_LNG, lng)
            }
            scopeSettings.putDouble(PREF_SCOPE_RADIUS, radiusMiles)
        } catch (_: Throwable) { /* disk write failures are recoverable */ }
    }

    private fun clearPersistedScope() {
        try {
            scopeSettings.remove(PREF_SCOPE_NAME)
            scopeSettings.remove(PREF_SCOPE_LAT)
            scopeSettings.remove(PREF_SCOPE_LNG)
            scopeSettings.remove(PREF_SCOPE_RADIUS)
        } catch (_: Throwable) { /* disk write failures are recoverable */ }
    }

    private fun loadPersistedScope(): Quadruple<String, Double?, Double?, Double>? {
        return try {
            val name = scopeSettings.getStringOrNull(PREF_SCOPE_NAME)?.takeIf { it.isNotBlank() }
                ?: return null
            val lat = if (scopeSettings.hasKey(PREF_SCOPE_LAT)) scopeSettings.getDouble(PREF_SCOPE_LAT, 0.0) else null
            val lng = if (scopeSettings.hasKey(PREF_SCOPE_LNG)) scopeSettings.getDouble(PREF_SCOPE_LNG, 0.0) else null
            val radius = if (scopeSettings.hasKey(PREF_SCOPE_RADIUS)) scopeSettings.getDouble(PREF_SCOPE_RADIUS, 20.0) else 20.0
            Quadruple(name, lat, lng, radius)
        } catch (_: Throwable) { null }
    }

    private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    /** Events returned by recent search_events calls in this session. Kept
     *  as a thin local list for plan-build code that iterates by insertion
     *  order; the canonical map-by-id cache lives in BoTools.searchResultCache
     *  and both are populated together. We could collapse to just the map
     *  but several call sites iterate, so the list stays for now. */
    private val searchResultCache = mutableListOf<Event>()

    /**
     * The exact event_ids the user picked when they tapped "Build a plan with
     * my N picks" (suggestion-tray Create Plan CTA). Set in [sendBuildIntent]
     * for the build-NEW path; consumed + cleared in the PLAN branch of
     * [handleClaudeResponse]. Bo occasionally enumerates all N events in its
     * prose but emits fewer than N refs in `present_plan` — dropping a stop the
     * user explicitly chose. We hold the authoritative pick list here so the
     * plan builder can detect the omission and re-inject any missing pick,
     * rather than silently trusting Bo's tool call. Empty when the current turn
     * isn't a build-from-picks turn.
     */
    private var pendingBuildExpectedIds: List<String> = emptyList()

    /** Venues validated this session via `recommend_and_save_venue`. Keyed by
     *  normalized name. `validate-venue` returns rich Google metadata (lat/lng,
     *  rating, website, photo, category, reservation_url) even when the venue
     *  isn't persisted to our `venues` table ("validated_not_saved"). Without
     *  caching it here, the plan builder's `venueRepo.searchByName` miss drops
     *  ALL of it and the slot renders bare — no rating, no Directions chip (no
     *  lat/lng), no reservation chip. */
    private val validatedVenueCache = mutableMapOf<String, ValidatedVenue>()

    private data class ValidatedVenue(
        val name: String,
        val city: String?,
        val state: String?,
        val address: String?,
        val lat: Double?,
        val lng: Double?,
        val googleRating: Double?,
        val website: String?,
        val photoUrl: String?,
        val category: String?,
        val reservationUrl: String?,
    )

    private fun venueKey(s: String) = s.trim().lowercase()

    /**
     * The keyword Bo passed in its most recent `search_events` call. Stamped
     * onto Suggestions trays so "Find more" on a day group keeps the same
     * filter applied. Without this, load-more reverts to "popular events on
     * that day" and the user's original intent ("story times this week",
     * "jazz tonight") gets lost. Cleared on resetChat.
     */
    private var lastSearchKeyword: String? = null

    /** The location + strictness of the most recent search_events call, so the
     *  inline "Find more" handler re-applies the SAME city constraint. Without
     *  this, Find more on a strict "Events in Watertown" tray drops the city
     *  filter and appends broad 20mi-radius results (Red Sox at Fenway, A$AP
     *  Rocky at TD Garden) into the Watertown day group. Cleared on resetChat. */
    private var lastSearchLocation: String? = null
    private var lastSearchStrictLocation: Boolean = false

    /** The category/keyword + date window of the most recent search_events
     *  call, so expandSuggestionsFromPool can run a radius-scoped
     *  category-COMPLETE supplemental search (matching Explore) when the
     *  stratified pool under-returns a single category. */
    private var lastBrowseQuery: String? = null
    private var lastSearchDateRange: String? = null
    private var lastSearchStartDate: String? = null
    private var lastSearchEndDate: String? = null

    /** Cached taste profile — loaded at init, refreshed after plan approvals. */
    private var tasteProfile: TasteProfile? = null

    private val weatherService = WeatherService()

    /** Update current location (called from AppNavigation when location changes). */
    fun setCurrentLocation(lat: Double, lng: Double, name: String) {
        _uiState.update { it.copy(currentLat = lat, currentLng = lng, currentLocationName = name) }
        ensureLocalIdioms(name)
    }

    // ── Hyperlocal searching idioms ─────────────────────────────────────────
    // In-memory cache of Claude-generated, place-specific gerund idioms, keyed
    // by the lowercased location name. Survives for the life of the ViewModel
    // (singleton) so switching back to a place is instant and free.
    private val localIdiomCache = mutableMapOf<String, List<String>>()
    private var localIdiomJob: kotlinx.coroutines.Job? = null
    private var localIdiomKeyInFlight: String? = null

    /**
     * Ensure [localIdioms] in state reflects hyperlocal searching idioms for
     * [name]. Cache hit → apply instantly. Cache miss → kick off a background
     * Haiku call (non-blocking, never gates a user turn) that generates ~12
     * short, affectionate, place-specific gerunds and caches them.
     *
     * Deliberately fire-and-forget: if generation fails or is slow, the
     * indicator just shows the generic gerunds. This must NEVER add latency to
     * Bo's actual responses, so it runs entirely off the request path.
     */
    private fun ensureLocalIdioms(name: String) {
        val key = name.trim().lowercase()
        if (key.isBlank()) {
            _uiState.update { it.copy(localIdioms = emptyList()) }
            return
        }
        localIdiomCache[key]?.let { cached ->
            _uiState.update { it.copy(localIdioms = cached) }
            return
        }
        // Already generating for this exact place — don't double-fire.
        if (localIdiomKeyInFlight == key && localIdiomJob?.isActive == true) return
        // Clear any stale idioms from the previous location while we generate.
        _uiState.update { it.copy(localIdioms = emptyList()) }
        localIdiomKeyInFlight = key
        localIdiomJob?.cancel()
        localIdiomJob = viewModelScope.launch {
            val idioms = try {
                generateLocalIdioms(name.trim())
            } catch (e: Throwable) {
                println("ensureLocalIdioms: generation failed for '$name': ${e.message}")
                emptyList()
            }
            if (idioms.isNotEmpty()) {
                localIdiomCache[key] = idioms
                // Only apply if the user is still on this location.
                if (_uiState.value.currentLocationName.trim().lowercase() == key) {
                    _uiState.update { it.copy(localIdioms = idioms) }
                }
            }
        }
    }

    /**
     * One Haiku call → 50+ hyperlocal gerund idioms. The model is asked
     * for affectionate, insider, present-continuous phrases a local would
     * recognize (transit lines, landmarks, neighborhoods, local rituals) and
     * to OMIT anything it isn't confident is real, so obscure places yield a
     * shorter list rather than hallucinations. To reliably reach a large count
     * the model may draw on the surrounding region/metro, not just the exact
     * town, so the indicator never cycles the same few phrases.
     */
    private suspend fun generateLocalIdioms(name: String): List<String> {
        val system = """
            You write short, playful "loading" phrases for a local-events app's searching animation.
            Given a place, return AS MANY present-continuous gerund phrases (3-6 words) as you can —
            aim for 50 to 60 — that a LOCAL would instantly recognize as theirs.

            Draw from every angle so the list is long and varied:
            - Transit lines, train/subway/bus routes, ferries
            - Highways, bridges, rotaries, notorious traffic spots
            - Landmarks, parks, waterfronts, mountains, beaches
            - Neighborhoods and adjacent towns in the same metro/region
            - Local food, drinks, markets, and the "best ___" arguments
            - Sports teams, fight songs, rivalries
            - Weather, seasons, and everyday local rituals
            - Colleges, theaters, music venues, festivals

            Rules:
            - Each phrase MUST feel specific to this place or its region. Avoid phrases that could apply anywhere.
            - Warm, affectionate, a little cheeky — like a friend who lives there. Not touristy.
            - Examples for Philadelphia: "Taking the Broad Street Line", "Avoiding traffic on the Schuylkill",
              "Dodging the Italian Market crowd", "Arguing about the best cheesesteak".
            - Examples for Doylestown: "Taking SEPTA out of the city", "Circling the courthouse", "Stopping by Mom's".
            - Only include a phrase if you are CONFIDENT the reference is real for this place or its region.
              It's fine to lean on the broader metro/region to reach the count, but never invent fake names.
            - No street addresses, no business you're unsure exists, no hashtags, no emoji.
            - End each phrase with "..." (three dots).

            Output ONLY a JSON array of 50-60 strings. No prose, no markdown. Example:
            ["Taking the Broad Street Line...", "Avoiding traffic on the Schuylkill..."]
        """.trimIndent()

        val response = claudeApi.sendRaw(
            systemPrompt = system,
            messages = listOf(ClaudeMessage.text("user", "Place: $name")),
            model = ClaudeApiService.MODEL_CHAT,
        )
        val raw = response.content.firstOrNull { it.type == "text" }?.text?.trim().orEmpty()
        if (raw.isBlank()) return emptyList()
        // Be forgiving: strip code fences and grab the JSON array.
        val jsonStr = raw
            .removePrefix("```json").removePrefix("```").removeSuffix("```")
            .let { s ->
                val start = s.indexOf('[')
                val end = s.lastIndexOf(']')
                if (start >= 0 && end > start) s.substring(start, end + 1) else s
            }
            .trim()
        return try {
            claudeApi.json.decodeFromString<List<String>>(jsonStr)
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length <= 48 }
                .map { if (it.endsWith("...")) it else "$it..." }
                .distinct()
                .take(60)
        } catch (e: Throwable) {
            println("generateLocalIdioms: parse failed: ${e.message} raw=${raw.take(200)}")
            emptyList()
        }
    }

    /**
     * Set the chat's search scope — both the named location and the radius
     * in miles. Driven by the search-scope banner editor in the chat header.
     * The banner is the canonical "where + how wide Bo is searching" value;
     * Bo's search tools read both fields. If [name] geocodes we update the
     * coords too so the search center moves; if it doesn't we still keep
     * the name so the user sees their intent.
     */
    fun setChatSearchScope(name: String, radiusMiles: Double) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val coords = geocodeCity(trimmed)
        val clampedRadius = radiusMiles.coerceIn(1.0, 100.0)
        val finalLat = coords?.first ?: _uiState.value.currentLat
        val finalLng = coords?.second ?: _uiState.value.currentLng
        _uiState.update {
            it.copy(
                currentLocationName = trimmed,
                currentLat = finalLat,
                currentLng = finalLng,
                searchRadiusMiles = clampedRadius,
            )
        }
        persistScope(trimmed, finalLat, finalLng, clampedRadius)
        ensureLocalIdioms(trimmed)
    }

    /**
     * Same as [setChatSearchScope] but coords come from the caller (iOS uses
     * Apple's CLGeocoder, which handles arbitrary places — "Concord, MA",
     * "South End", "94110" — that the in-memory geocodeCity doesn't know).
     * Falls back to the legacy hardcoded geocoder when the caller couldn't
     * resolve coords (NaN). Without this, typing any town outside the
     * hardcoded KNOWN_CITIES list causes every Bo search to come back as
     * UNRESOLVED_LOCATION.
     */
    fun setChatSearchScopeWithCoords(name: String, lat: Double, lng: Double, radiusMiles: Double) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val clampedRadius = radiusMiles.coerceIn(1.0, 100.0)
        val finalLat: Double
        val finalLng: Double
        if (lat.isFinite() && lng.isFinite()) {
            finalLat = lat
            finalLng = lng
        } else {
            val fallback = geocodeCity(trimmed)
            finalLat = fallback?.first ?: _uiState.value.currentLat ?: 42.3601
            finalLng = fallback?.second ?: _uiState.value.currentLng ?: -71.0589
        }
        _uiState.update {
            it.copy(
                currentLocationName = trimmed,
                currentLat = finalLat,
                currentLng = finalLng,
                searchRadiusMiles = clampedRadius,
            )
        }
        persistScope(trimmed, finalLat, finalLng, clampedRadius)
        ensureLocalIdioms(trimmed)
    }

    /** Legacy single-arg setter — kept so any existing Swift callers still
     *  compile. Forwards to [setChatSearchScope] with the current radius. */
    fun setChatSearchLocationByName(name: String) {
        setChatSearchScope(name, _uiState.value.searchRadiusMiles)
    }

    // One-shot navigation events
    private val _navigateToPlans = Channel<Unit>(Channel.BUFFERED)
    val navigateToPlans = _navigateToPlans.receiveAsFlow()

    // Claude conversation history — supports both text and tool_use/tool_result blocks
    private val conversationHistory = mutableListOf<ClaudeMessage>()

    // Tracks the in-flight welcome stream so "Start over" can cancel it before
    // launching a new one. Without this, two greetings race into streamingText
    // and the user sees the previous one flicker back during reset.
    private var welcomeJob: kotlinx.coroutines.Job? = null

    /** Full text of the most recent turn that was revealed LIVE via
     *  [streamingText] (set in [sendStreaming]'s Done handler). When
     *  [addAiMessage] finalizes a bubble whose text matches this, we mark the
     *  bubble already-typed in [TypewriterCompletionStore] so the per-message
     *  typewriter does NOT replay the whole message — the live stream already
     *  showed it in full. Without this the finalized bubble re-animates from
     *  zero right after the stream completes, which reads as: the full message
     *  flashes, vanishes, then re-streams. Consumed (nulled) on match. Not set
     *  for tool rounds or structured responses, which never stream user-facing
     *  text into [streamingText]. */
    private var lastStreamedText: String? = null

    // Must be declared BEFORE init {} — buildSystemPrompt() reads this from the
    // welcome coroutine, and on Kotlin/Native a field declared after the init
    // block stays at its null-pointer default until that line of the class
    // body executes, causing a SIGSEGV inside .lowercase() on launch.
    /** Stores the last user message for APPROVE detection. */
    private var lastUserMessage: String = ""

    init {
        // Restore the persisted search-scope banner BEFORE any other init
        // so the chip is correct on first paint after Bo reopens. Closing +
        // re-opening Bo used to drop the user's chosen location/radius;
        // they had to re-edit every session.
        loadPersistedScope()?.let { saved ->
            _uiState.update {
                it.copy(
                    currentLocationName = saved.a,
                    currentLat = saved.b ?: it.currentLat,
                    currentLng = saved.c ?: it.currentLng,
                    searchRadiusMiles = saved.d,
                )
            }
            // Warm hyperlocal idioms for the restored location so the first
            // search after reopening Bo already has place-specific flavor.
            ensureLocalIdioms(saved.a)
        }
        loadUserPrefsAndWelcome()
    }

    private fun loadUserPrefsAndWelcome() {
        viewModelScope.launch {
            val userId = userPrefsRepo.getLocalUserId()
                ?: userPrefsRepo.recoverProfile()
            if (userId != null) {
                val prefs = userPrefsRepo.loadPreferences(userId)
                if (prefs != null) {
                    _uiState.update {
                        it.copy(
                            userName = prefs.firstName ?: "",
                            userPreferences = prefs,
                        )
                    }
                }
                // Load taste profile for personalized recommendations
                try { tasteProfile = tasteRepo.getTasteProfile(userId) } catch (_: Throwable) {}
                // Load saved-events so already-favorited results show a filled ❤️.
                try {
                    val favs = communityInterestRepository.getFavoriteEventIds(userId).toSet()
                    _uiState.update { it.copy(favoriteEventIds = favs) }
                } catch (_: Throwable) {}
            }
            showWelcome()
        }
    }

    // ── Welcome ──

    private fun showWelcome() {
        // Cancel any in-flight welcome stream — without this, a "Start over" tap
        // mid-greeting leaves the previous Claude call still emitting TextDeltas
        // into streamingText *while* the new one starts. The user sees the old
        // greeting flicker back, get cleared, then the new one type out.
        welcomeJob?.cancel()
        welcomeJob = viewModelScope.launch {
            _uiState.update { it.copy(isTyping = true) }

            // Kick off the personalized-chips edge-function call NOW, in
            // parallel with the greeting. By the time the greeting Claude
            // call returns ~2–4s later, the chips call (Haiku, cached
            // server-side) should already be done. We await with a short
            // join below; if it hasn't finished, we fall through to the
            // rules-based set so the welcome screen is never chipless.
            val chipsJob = async { fetchPersonalizedChips() }

            try {
                val systemPrompt = buildSystemPrompt()
                // Send a welcome prompt to Claude — it will greet the user naturally
                conversationHistory.add(ClaudeMessage.text("user", "[System: The user just opened the chat. Greet them warmly in 1-2 short sentences. Ask what they'd like to do. Keep it casual and brief. Do NOT use tools. Do NOT use any special formatting. NO emoji of any kind — not even one in the greeting. Plain text only.]"))
                val response = sendStreaming(systemPrompt, null, isToolRound = false, model = live.localapp.local.data.remote.ClaudeApiService.MODEL_CHAT)
                val text = claudeApi.extractText(response) ?: "Hey! What are you looking to plan?"
                // Remove the synthetic user message from history — replace with clean start
                conversationHistory.removeLastOrNull()

                // addAiMessage atomically appends the message AND clears
                // streamingText in a single _uiState.update — clearing it in a
                // separate update first creates a one-frame gap where the
                // bubble disappears before reappearing as a finalized message,
                // which reads as "the typewriter deletes itself and starts over."
                addAiMessage(text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Reset called mid-stream: drop the partial greeting and let the
                // new welcome (already launched) own the state.
                chipsJob.cancel()
                throw e
            } catch (_: Exception) {
                addAiMessage("Hey! What are you looking to plan?")
            }

            val prefs = _uiState.value.userPreferences
            // Personalized chips from edge fn beat rules-based when present.
            // Cap at 3s past greeting completion so a slow edge function
            // can't visibly block the welcome state. Empty list (failure,
            // timeout, or unboarded user) falls through to rules-based.
            val edgeChips = withTimeoutOrNull(3000) { chipsJob.await() } ?: emptyList()
            val chips = edgeChips.ifEmpty { buildPersonalizedChips(prefs).map { it.label } }
            _uiState.update {
                it.copy(
                    isTyping = false,
                    inputEnabled = true,
                    inputPlaceholder = "What are you looking for?",
                    quickReplies = chips.map { label -> QuickReply(label) },
                )
            }
        }
    }

    /**
     * Hits the `bo-chips` edge function with the user's id, current local
     * hour, and day-of-week. The edge function does the personalization
     * (profile + taste + upcoming plan) and returns 4 chip labels. Returns
     * empty list on any failure or if the user isn't onboarded yet.
     */
    private suspend fun fetchPersonalizedChips(): List<String> {
        val userId = userPrefsRepo.getLocalUserId() ?: return emptyList()
        val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
        val now = kotlin.time.Clock.System.now().toLocalDateTime(tz)
        // ordinal is 0=Mon..6=Sun; edge function expects 1=Mon..7=Sun (ISO).
        val dowIso = now.dayOfWeek.ordinal + 1
        val prefs = _uiState.value.userPreferences
        return boChipsRepo.getChips(
            userId = userId,
            localHour = now.hour,
            dayOfWeek = dowIso,
            lat = prefs?.userLat ?: _uiState.value.currentLat,
            lng = prefs?.userLng ?: _uiState.value.currentLng,
        )
    }

    private fun categoryDisplayName(key: String): String = when (key) {
        "music" -> "Live Music"
        "sports" -> "Sports"
        "arts" -> "Arts"
        "theatre" -> "Theatre"
        "comedy" -> "Comedy"
        "food" -> "Food & Drink"
        "outdoors" -> "Outdoors"
        "museums" -> "Museums"
        "tours" -> "Tours"
        "libraries" -> "Libraries"
        "for-kids" -> "For Kids"
        else -> key.replaceFirstChar { it.uppercase() }
    }

    /**
     * Welcome-screen chip set, prioritized by signal strength: explicit user
     * favorites (saved venues, neighborhoods, taste-profile top categories)
     * always beat generic prompts. Always returns exactly 4. Re-ranked by
     * time-of-day and day-of-week so the same user opening Bo at noon vs.
     * 9pm Friday sees different starter prompts. Order:
     *
     *   1. Time-of-day anchor ("Tonight", "This weekend", etc.) — always first
     *   2. Favorite venue (if saved during onboarding)
     *   3. Top taste-profile category, else first onboarding category
     *   4. Neighborhood, weekend chip, or evergreen fallback
     */
    private fun buildPersonalizedChips(prefs: UserPreferences?): List<QuickReply> {
        val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
        val now = kotlin.time.Clock.System.now().toLocalDateTime(tz)
        val hour = now.hour
        val dow = now.dayOfWeek

        // Time-of-day anchor: morning users plan ahead, evening users want now.
        val timeChip = when {
            hour < 11 -> when (dow) {
                kotlinx.datetime.DayOfWeek.FRIDAY -> "Plans for tonight"
                kotlinx.datetime.DayOfWeek.SATURDAY, kotlinx.datetime.DayOfWeek.SUNDAY -> "Today's plans"
                else -> "Things to do this week"
            }
            hour < 16 -> when (dow) {
                kotlinx.datetime.DayOfWeek.FRIDAY, kotlinx.datetime.DayOfWeek.SATURDAY -> "Plans for tonight"
                kotlinx.datetime.DayOfWeek.SUNDAY -> "Sunday Funday"
                else -> "Plans for tonight"
            }
            hour < 22 -> "Happening right now"
            else -> "Plans for tomorrow"
        }

        // Weekend chip swaps phrasing based on where we are in the week.
        val weekendChip = when (dow) {
            kotlinx.datetime.DayOfWeek.SATURDAY -> "Sunday Funday"
            kotlinx.datetime.DayOfWeek.SUNDAY -> "Next weekend"
            else -> "This weekend"
        }

        val chips = mutableListOf<QuickReply>()
        chips.add(QuickReply(timeChip))

        // Favorite venue — strongest personalization signal we have.
        prefs?.selectedVenues?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { venue ->
            chips.add(QuickReply("What's on at $venue"))
        }

        // Taste profile beats onboarding categories because it reflects actual
        // engagement (tap-throughs, saves) rather than a one-time picker.
        val topCategory = tasteProfile?.categoryScores
            ?.entries?.sortedByDescending { it.value }?.firstOrNull()?.key
            ?: prefs?.selectedCategories?.firstOrNull()
        if (topCategory != null && chips.size < 4) {
            chips.add(QuickReply("${categoryDisplayName(topCategory)} this week"))
        }

        // Neighborhood — only useful if we have one and there's still room.
        prefs?.selectedNeighborhoods?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { hood ->
            if (chips.size < 4) chips.add(QuickReply("Things to do in $hood"))
        }

        // Fill remaining slots with weekend + evergreen fallbacks, in order.
        val fallbacks = listOf(weekendChip, "Date night ideas", "Free things to do")
        for (fb in fallbacks) {
            if (chips.size >= 4) break
            if (chips.none { it.label.equals(fb, ignoreCase = true) }) {
                chips.add(QuickReply(fb))
            }
        }

        return chips.take(4)
    }

    // ── Public actions ──

    fun sendMessage(text: String) {
        if (text.isBlank() || _uiState.value.isBusy) return
        val trimmed = text.trim()
        live.localapp.local.observability.Analytics.track(
            live.localapp.local.observability.AnalyticsEvent.boMessageSent(
                messageLength = trimmed.length,
                hasAttachedEvent = false,
            )
        )
        addUserMessage(trimmed)
        handleMessage(trimmed)
    }

    /**
     * Build-plan-from-selections CTA (iOS suggestion-tray Create Plan button).
     *
     * Runs a CLIENT-SIDE confirmation dialog before going to Bo:
     *  1. Adds the user's tap as a chat bubble ("Build me a plan with my N picks")
     *  2. Synthesizes a Bo confirmation message listing the picks + asking
     *     whether to add anything else
     *  3. Shows two chips: "No, create the plan" / "Yes"
     *  4. Branches in [onQuickReply] — "No" goes straight to a build intent;
     *     "Yes" shows a follow-up message with four extras chips (meals,
     *     drinks, own event, something else)
     *
     * Doing this client-side instead of asking Bo means no Claude round-trip
     * for the confirmation step (faster), deterministic wording, and a
     * simpler Bo system prompt. The actual build intent is sent in
     * [sendBuildIntent] once the user picks an extras chip (or "No").
     */
    fun buildPlanFromSelections(eventIds: List<String>) {
        if (eventIds.isEmpty() || _uiState.value.isBusy) return
        val n = eventIds.size
        // If Bo has already produced a plan in this chat, the user's intent
        // on a SECOND batch of selections is to extend that plan rather than
        // start a new one. iOS's CTA already shows "Add N events to my plan"
        // in this case — match it here so Bo's tool selection lines up
        // (update_plan vs present_plan).
        val addingToExisting = _uiState.value.days.isNotEmpty()
        val display = if (addingToExisting) {
            "Add $n event${if (n == 1) "" else "s"} to my plan"
        } else {
            "Build me a plan with my $n pick${if (n == 1) "" else "s"}"
        }
        addUserMessage(display)

        viewModelScope.launch {
            val events = eventIds.mapNotNull { id ->
                searchResultCache.find { it.id == id }
                    ?: try { eventRepo.getEvent(id) } catch (_: Throwable) { null }
            }
            val confirmationText = buildHandoffConfirmation(events, addingToExisting)
            addMessage(
                ChatMessage(
                    role = MessageRole.AI,
                    content = MessageContent.Text(confirmationText),
                )
            )
            _uiState.update {
                it.copy(
                    pendingBuildHandoff = BuildHandoff(eventIds, addingToExisting),
                    quickReplies = listOf(
                        QuickReply(
                            label = "Create the plan",
                            isGreen = true,
                            action = "handoff_build_now",
                        ),
                        QuickReply(
                            label = "Add something else",
                            action = "handoff_show_extras",
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Save the user's staged picks to Saved Events and acknowledge in-chat.
     * Called from the iOS results drawer's "Save Events" CTA. Posts two Bo
     * messages: a confirmation listing what was saved (chronological) + where
     * to find it, then a short "anything else?" follow-up. The structured
     * results message stays in history and naturally scrolls up as these two
     * append below it. Idempotent per-event (no-op for already-saved ids).
     */
    fun saveEventsFromSelections(eventIds: List<String>) {
        val ids = eventIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return
        ids.forEach { saveEventIfNew(it) }
        viewModelScope.launch {
            val events = ids.mapNotNull { id ->
                searchResultCache.find { it.id == id }
                    ?: try { eventRepo.getEvent(id) } catch (_: Throwable) { null }
            }
            addMessage(
                ChatMessage(
                    role = MessageRole.AI,
                    content = MessageContent.Text(buildSaveAcknowledgment(events, ids.size)),
                )
            )
            addMessage(
                ChatMessage(
                    role = MessageRole.AI,
                    content = MessageContent.Text("Anything else I can help you find?"),
                )
            )
        }
    }

    /** Bo's save confirmation — bullet list of saved events + where to find them. */
    private fun buildSaveAcknowledgment(
        events: List<live.localapp.local.domain.model.Event>,
        count: Int,
    ): String {
        val intro = if (count == 1) {
            "I've saved this event for you:"
        } else {
            "I've saved these $count events for you:"
        }
        // Chronological by start_at so the bullets read in date order; undated
        // events sink to the bottom.
        val sorted = events.sortedWith(compareBy(nullsLast()) { ev -> ev.startAt })
        val bullets = sorted.joinToString("\n") { ev ->
            val datePart = formatHandoffEventDate(ev.startAt, ev.timeKnown)
            if (datePart.isEmpty()) "• ${ev.title}" else "• ${ev.title} — $datePart"
        }
        // Hydration can fail for every id (rare) — fall back to intro-only.
        val body = if (bullets.isBlank()) "" else "\n$bullets"
        return "$intro$body\n\nYou can find them anytime by expanding the " +
            "“Saved Events” carousel on the home page."
    }

    /** Bo's confirmation message — bullet list of picks + the question. */
    private fun buildHandoffConfirmation(
        events: List<live.localapp.local.domain.model.Event>,
        addingToExisting: Boolean,
    ): String {
        // Singular vs plural so it never reads "these 1 event". Singular drops
        // the count entirely ("this event"); plural keeps it ("these 3 events").
        val intro = if (events.size == 1) {
            if (addingToExisting) "I'll add this event:" else "I'll create a plan with this event:"
        } else {
            if (addingToExisting) "I'll add these ${events.size} events:"
            else "I'll create a plan with these ${events.size} events:"
        }
        // Order chronologically by start_at so the bullets read in the same
        // sequence the plan will. Events with no start_at sink to the bottom.
        val sorted = events.sortedWith(
            compareBy(nullsLast()) { ev -> ev.startAt }
        )
        val bullets = sorted.joinToString("\n") { ev ->
            val datePart = formatHandoffEventDate(ev.startAt, ev.timeKnown)
            if (datePart.isEmpty()) "• ${ev.title}" else "• ${ev.title} — $datePart"
        }
        return "$intro\n$bullets\n\nBefore I do, is there anything else I should add to the plan? I can find restaurants for food or drinks, or insert anything you've already got planned."
    }

    /** "Wednesday May 27th, 7:30 PM" — used in the handoff confirmation list. */
    private fun formatHandoffEventDate(startAt: String?, timeKnown: Boolean): String {
        if (startAt.isNullOrBlank()) return ""
        return try {
            val instant = kotlinx.datetime.Instant.parse(startAt)
            val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
            val local = instant.toLocalDateTime(tz)
            val weekdays = arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
            val months = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
            val day = local.dayOfMonth
            val datePart = "${weekdays[local.dayOfWeek.ordinal]} ${months[local.monthNumber - 1]} $day${ordinalSuffix(day)}"
            if (!timeKnown || (local.hour == 0 && local.minute == 0)) {
                datePart
            } else {
                val amPm = if (local.hour < 12) "AM" else "PM"
                val displayHour = when {
                    local.hour == 0 -> 12
                    local.hour > 12 -> local.hour - 12
                    else -> local.hour
                }
                val timePart = if (local.minute == 0) {
                    "$displayHour $amPm"
                } else {
                    "$displayHour:${local.minute.toString().padStart(2, '0')} $amPm"
                }
                "$datePart, $timePart"
            }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun ordinalSuffix(day: Int): String = when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }

    /** "Create the plan" chip — build immediately, no extras. */
    private fun handoffBuildImmediate() {
        val pending = _uiState.value.pendingBuildHandoff ?: return
        addUserMessage("Create the plan")
        _uiState.update { it.copy(pendingBuildHandoff = null, quickReplies = emptyList()) }
        sendBuildIntent(pending.eventIds, pending.addingToExisting, extras = null)
    }

    /** "Add something else" chip — show the four extras chips. Keeps pendingBuildHandoff. */
    private fun handoffShowExtrasChips() {
        if (_uiState.value.pendingBuildHandoff == null) return
        addUserMessage("Add something else")
        addMessage(
            ChatMessage(
                role = MessageRole.AI,
                content = MessageContent.Text("Let me know what you'd like to add."),
            )
        )
        _uiState.update {
            it.copy(
                quickReplies = listOf(
                    QuickReply(label = "Add meals", action = "handoff_add_meals"),
                    QuickReply(label = "Add drinks", action = "handoff_add_drinks"),
                    QuickReply(label = "Add my own event", action = "handoff_add_own_event"),
                    QuickReply(label = "Something else", action = "handoff_something_else"),
                ),
            )
        }
    }

    /** One of the four extras chips. Builds with the given extras flag. */
    private fun handoffBuildWithExtras(extra: String) {
        val pending = _uiState.value.pendingBuildHandoff ?: return
        val label = when (extra) {
            "meals" -> "Add meals"
            "drinks" -> "Add drinks"
            "own_event" -> "Add my own event"
            else -> "Something else"
        }
        addUserMessage(label)
        _uiState.update { it.copy(pendingBuildHandoff = null, quickReplies = emptyList()) }
        sendBuildIntent(pending.eventIds, pending.addingToExisting, extras = extra)
    }

    /**
     * Send the actual build intent to Bo. Called from the chip handlers after
     * the client-side confirmation dialog has resolved. The display bubble is
     * already in the chat (the chip handler added it); this only appends the
     * technical instruction to conversation history.
     */
    private fun sendBuildIntent(
        eventIds: List<String>,
        addingToExisting: Boolean,
        extras: String?,
    ) {
        if (eventIds.isEmpty()) return
        // Remember the authoritative pick set for post-build reconciliation.
        // Only the build-NEW path (present_plan) reconciles here; the
        // add-to-existing path goes through update_plan / PLAN_UPDATE, which
        // has its own merge semantics, so we clear the guard for it.
        pendingBuildExpectedIds = if (addingToExisting) emptyList()
            else eventIds.map { it.removePrefix("evt:") }
        val extrasInstruction = when (extras) {
            "meals" -> " The user wants meal stops layered in — add restaurant slots (use recommend_and_save_venue) in any gap > 2 hours and at appropriate meal times."
            "drinks" -> " The user wants drink stops layered in — add bar/lounge/cocktail slots (use recommend_and_save_venue) in any gap > 2 hours and at appropriate evening times."
            "own_event" -> " The user wants to add their OWN event to the plan — something they already have planned (their reservation, a friend's house, a gym class, a dinner they're cooking, etc.). This is NOT a request for Bo to research or recommend a venue. Open with this exact question: \"What would you like to add?\". After their reply, before calling present_plan, check for ambiguity and ask follow-ups ONE AT A TIME:\n  (a) DAY CHECK — if the plan has events on multiple days AND the user did NOT name a day, ask: \"Which day — {Day A} or {Day B}?\". INTERPRET MULTI-DAY ANSWERS CAREFULLY: \"both\" / \"both days\" / \"all of them\" / \"every day\" / a list like \"Wed and Fri\" means add the slot to EACH named day (one slot per day), not just one. A single named day means just that day. NEVER drop a day the user named.\n  (b) PLACE CHECK — if the user named an activity but NO specific place (e.g. \"dinner before\", \"drinks after\", \"pre-game snack\"), ask: \"Did you have a place in mind, or want me to find one?\". Wait for the answer.\n  (c) RESOLUTION — if they say \"find one\" → call recommend_and_save_venue for that slot type. If they name a personal/non-public place (their house, a friend's apartment, the office, etc.), DO NOT call recommend_and_save_venue and DO NOT search the DB — use it directly per (e).\n  (d) TIME CHECK — if no time was given but it can be inferred from \"before\"/\"after\" relative to a named event, infer it (≥ 90 minutes before for dinner, ≥ 30 minutes for drinks/snacks). If unclear, ask for a time.\n  (e) SLOT ENCODING (CRITICAL — field mapping for CUSTOM PERSONAL slots in present_plan):\n      - venue_name = the ACTIVITY in title case (\"Dinner\", \"Drinks\", \"Brunch\", \"Workout\", \"Game night\"). This is what shows as the slot's PROMINENT top label.\n      - note = \"at {location}\" with the location verbatim from the user (\"at Your House\", \"at Jane's apartment\", \"at the gym\"). This is the secondary line beneath the activity.\n      - time = the inferred or user-stated time.\n      - DO NOT put the location in venue_name. DO NOT put the activity in note. The slot must read \"Dinner\" on top, \"at Your House\" below — NEVER the reverse.\n  (f) MULTI-DAY EMISSION — if the user wants the custom slot on multiple days, emit a separate slot on EACH named day in the present_plan call. Each gets its own venue_name/note/time line. Do NOT collapse them into one day. If you forget a day the user named, the user notices.\n  Never silently pick a day, venue, or time the user did not specify or explicitly delegate."
            "something_else" -> " The user wants to add something else (not a meal, drink, or specific event) to the plan. Open with: \"What would you like to add?\". After their reply, apply the SAME clarifying-question and slot-encoding rules as the own_event path above — including the venue_name=activity / note=\"at {location}\" mapping for custom personal slots, and the multi-day rule (\"both\" / \"all\" / a list of days means a slot on EACH named day, never just one)."
            else -> ""
        }
        val technical = if (addingToExisting) {
            "[Add-to-existing-plan intent from suggestion tray] " +
                "event_ids to add: ${eventIds.joinToString(", ")}. " +
                "Call update_plan (NOT present_plan). Preserve every existing slot in the current plan " +
                "(their event_ids and venue_names are listed in the CURRENT PLAN context above) and APPEND " +
                "the new events into the correct day(s) based on each event row's start_at. Use each new " +
                "event's EXACT event_id verbatim. Layer transit stops in any gap > 2 hours. If a new event " +
                "falls on a day not already in the plan, add that day.$extrasInstruction Do NOT ask any " +
                "clarifying question first (unless explicitly instructed above by the extras line) — just update."
        } else {
            "[Build intent from suggestion tray] " +
                "event_ids: ${eventIds.joinToString(", ")}. " +
                "Use these EXACT event_ids in present_plan slots — derive each slot's date and time from " +
                "the event row's start_at, and layer transit stops in any gap > 2 hours.$extrasInstruction " +
                "Do NOT ask any clarifying question first (unless explicitly instructed above by the extras " +
                "line) — just build."
        }
        // The display bubble was already added by the chip handler. Pass an
        // empty `text` (it's only used to cache lastUserMessage) and the real
        // instruction as historyText.
        handleMessage(text = "", historyText = technical)
    }

    /**
     * Inline "Find more" — appends additional events to an existing
     * structured suggestion message without a chat round-trip with Bo.
     * Re-runs eventRepo.searchEventsBroad for the group's day or category,
     * filters out events already in the message, and pushes a new message
     * snapshot. Fires [ChatBridge.onLoadMoreFinished] (via [onSuggestionLoadMoreFinished])
     * when done.
     */
    fun loadMoreSuggestionsForGroup(messageId: String, dayKey: String?, categoryKey: String?) {
        viewModelScope.launch {
            val groupKey = dayKey ?: categoryKey ?: return@launch
            val msgIndex = _uiState.value.messages.indexOfFirst { it.id == messageId }
            if (msgIndex < 0) {
                onSuggestionLoadMoreFinished?.invoke(messageId, groupKey, 0)
                return@launch
            }
            val msg = _uiState.value.messages[msgIndex]
            val content = msg.content as? MessageContent.Structured
            val suggestions = content?.response as? live.localapp.local.domain.model.BoStructuredResponse.Suggestions
            if (content == null || suggestions == null) {
                onSuggestionLoadMoreFinished?.invoke(messageId, groupKey, 0)
                return@launch
            }
            val state = _uiState.value
            val prefs = state.userPreferences
            val effLat = prefs?.let { if (it.locationMode == "manual") it.manualLat ?: it.userLat else it.userLat }
            val effLng = prefs?.let { if (it.locationMode == "manual") it.manualLng ?: it.userLng else it.userLng }
            val userLat = state.currentLat ?: effLat
            val userLng = state.currentLng ?: effLng
            if (userLat == null || userLng == null) {
                onSuggestionLoadMoreFinished?.invoke(messageId, groupKey, 0)
                return@launch
            }

            val existingIds: Set<String> = suggestions.groups
                .flatMap { it.events.map { ref -> ref.eventId } }
                .toSet()

            val tz = kotlinx.datetime.TimeZone.currentSystemDefault()

            // Date scoping:
            //   - dayKey set (groupBy=day): use Bo's day, ±1 day buffer for
            //     UTC/local boundary slop, then strict-filter to local day.
            //   - dayKey null but categoryKey set (groupBy=category): infer
            //     the date range from the EXISTING events in the same
            //     category group, so Find more on "Music" doesn't leak in
            //     events from other dates (the bug where load-more on a
            //     May-15 tray returned May-11 + Red Sox games on other
            //     dates). Without this we'd be searching the full 90-day
            //     window with just a keyword.
            val targetGroup = suggestions.groups.firstOrNull { g ->
                if (dayKey != null) {
                    val first = g.events.firstOrNull()?.let { content.hydratedEvents[it.eventId] }
                    first?.startAt?.let {
                        try {
                            kotlinx.datetime.Instant.parse(it).toLocalDateTime(tz).date.toString() == dayKey
                        } catch (_: Throwable) { false }
                    } == true
                } else {
                    g.label.equals(categoryKey, ignoreCase = true)
                }
            }
            val groupDayLocal: String? = if (dayKey != null) dayKey else {
                // Pick the day that the events in THIS category group actually
                // sit on. If they're heterogeneous (multi-day tray), pick the
                // most common day so Find more keeps the scope tight.
                targetGroup?.events?.mapNotNull { content.hydratedEvents[it.eventId]?.startAt }
                    ?.mapNotNull {
                        try { kotlinx.datetime.Instant.parse(it).toLocalDateTime(tz).date.toString() } catch (_: Throwable) { null }
                    }
                    ?.groupingBy { it }?.eachCount()
                    ?.maxByOrNull { it.value }?.key
            }
            val (searchStart, searchEnd) = if (groupDayLocal != null) {
                try {
                    val day = kotlinx.datetime.LocalDate.parse(groupDayLocal)
                    val before = kotlinx.datetime.LocalDate.fromEpochDays(day.toEpochDays() - 1).toString()
                    val after = kotlinx.datetime.LocalDate.fromEpochDays(day.toEpochDays() + 1).toString()
                    before to after
                } catch (_: Throwable) {
                    groupDayLocal to groupDayLocal
                }
            } else null to null

            // Carry forward the original search's keyword when the tray is
            // grouped by day (so categoryKey is null). Without this, "Find
            // more" on a Friday group of "Story Times This Week" would drop
            // the "story time" filter and return whatever's popular on
            // Friday — Red Sox games, concerts, anything.
            val effectiveKeyword = categoryKey ?: suggestions.originalKeyword
            val moreRaw = try {
                eventRepo.searchEventsBroad(
                    userLat = userLat,
                    userLng = userLng,
                    // Honor the chip's radius slider. Was hardcoded to 20mi
                    // before, which silently overrode whatever the user had
                    // set on the banner — "Find more" returned the same 20mi
                    // pool even with the chip at 50mi.
                    radiusMiles = state.searchRadiusMiles,
                    dateRange = if (groupDayLocal != null) "custom" else null,
                    startDate = searchStart,
                    endDate = searchEnd,
                    keyword = effectiveKeyword,
                    limit = 80,
                )
            } catch (_: Throwable) { emptyList() }

            val onTargetDay: (live.localapp.local.domain.model.Event) -> Boolean = filter@{ ev ->
                if (groupDayLocal == null) return@filter true
                val s = ev.startAt ?: return@filter false
                try {
                    kotlinx.datetime.Instant.parse(s).toLocalDateTime(tz).date.toString() == groupDayLocal
                } catch (_: Throwable) { false }
            }

            // Strict category filter — when the user clicked Find more on a
            // specific category group, the keyword search can return events
            // whose category/subcategory don't actually match (e.g. a Sports
            // event whose title contains "Music"). Drop those — better to
            // return fewer results than to invent a new "Sports" group when
            // the user asked for more Music.
            val targetCategoryLower = categoryKey?.trim()?.lowercase()
            val matchesTargetCategory: (live.localapp.local.domain.model.Event) -> Boolean = filter@{ ev ->
                if (targetCategoryLower.isNullOrBlank()) return@filter true
                val cat = ev.category?.lowercase()?.trim().orEmpty()
                val sub = ev.subcategory?.lowercase()?.trim().orEmpty()
                cat == targetCategoryLower ||
                    sub == targetCategoryLower ||
                    cat.contains(targetCategoryLower) ||
                    sub.contains(targetCategoryLower) ||
                    targetCategoryLower.contains(cat) ||
                    (sub.isNotEmpty() && targetCategoryLower.contains(sub))
            }

            // Strict-location filter — carry forward the original search's city
            // constraint. If the brief was "Events in Watertown" (strict), Find
            // more must NOT append broad 20mi-radius results (Red Sox at Fenway,
            // A$AP Rocky at TD Garden) just because they fall inside the bbox.
            val strictLocLower = lastSearchLocation?.trim()?.lowercase()
            val matchesStrictLocation: (live.localapp.local.domain.model.Event) -> Boolean = filter@{ ev ->
                if (!lastSearchStrictLocation || strictLocLower.isNullOrBlank()) return@filter true
                val ec = ev.venueCity?.trim()?.lowercase() ?: return@filter false
                ec == strictLocLower || ec.contains(strictLocLower) || strictLocLower.contains(ec)
            }

            // Keyword / venue-name match — when the original search was
            // keyword-scoped (e.g. a named venue like "New Art Center"),
            // searchEventsBroad re-runs the SAME stratified RPC, whose breadth
            // lane (top-ranked per category) returns unrelated high-rank events
            // — Red Sox @ Fenway — that happen to fall on the target day. The
            // keyword only feeds the RPC's keyword lane; the breadth/niche lanes
            // ignore it, and none of the post-filters above catch the leak, so a
            // day-grouped "Find more" on a venue brief appends Red Sox games
            // under the "New Art Center — Upcoming" header. Require the carried
            // keyword to appear in the title or venue name. Only the day-grouped
            // path (categoryKey == null) uses this — category-grouped load-more
            // already constrains via matchesTargetCategory above.
            val keywordLower = if (categoryKey == null)
                suggestions.originalKeyword?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            else null
            val matchesKeyword: (live.localapp.local.domain.model.Event) -> Boolean = filter@{ ev ->
                if (keywordLower == null) return@filter true
                (ev.venueName?.lowercase()?.contains(keywordLower) == true) ||
                    ev.title.lowercase().contains(keywordLower)
            }

            val newEvents = moreRaw
                .asSequence()
                .filter { it.id !in existingIds }
                .filter(onTargetDay)
                .filter(matchesTargetCategory)
                .filter(matchesStrictLocation)
                .filter(matchesKeyword)
                .take(30)
                .toList()
            if (newEvents.isEmpty()) {
                onSuggestionLoadMoreFinished?.invoke(messageId, groupKey, 0)
                return@launch
            }

            // Cache for downstream usage (event detail tap, build flow).
            searchResultCache.addAll(newEvents)

            val newRefs = newEvents.map { ev ->
                live.localapp.local.domain.model.BoStructuredResponse.Suggestions.EventRef(
                    eventId = ev.id,
                    rationale = null,
                )
            }
            val targetGroupLabel = targetGroup?.label
            // If we couldn't find a matching group to append into, DROP the
            // results rather than inventing a new group. The user explicitly
            // asked for "more in this category"; surfacing a fresh group with
            // unrelated events betrays that intent. (Old behavior: appended
            // a new group labeled with the categoryKey, which is how Sports
            // events leaked in under a Music load-more.)
            val updatedGroups = if (targetGroupLabel != null) {
                suggestions.groups.map { g ->
                    if (g.label == targetGroupLabel) g.copy(events = g.events + newRefs) else g
                }
            } else {
                println("loadMoreSuggestionsForGroup: no matching group for dayKey=$dayKey categoryKey=$categoryKey — dropping ${newEvents.size} loaded events")
                onSuggestionLoadMoreFinished?.invoke(messageId, groupKey, 0)
                return@launch
            }
            val updatedSuggestions = suggestions.copy(groups = updatedGroups)
            val updatedHydrated = content.hydratedEvents + newEvents.associateBy { it.id }
            val updatedContent = MessageContent.Structured(
                response = updatedSuggestions,
                hydratedEvents = updatedHydrated,
            )
            val updatedMsg = msg.copy(content = updatedContent)
            _uiState.update { s ->
                s.copy(messages = s.messages.toMutableList().also { it[msgIndex] = updatedMsg })
            }
            onSuggestionLoadMoreFinished?.invoke(messageId, groupKey, newEvents.size)
        }
    }

    /** Bridged out to [ChatBridge.onLoadMoreFinished] so iOS can clear its
     *  per-group loading spinner and hide the Find-more tile when 0 added. */
    var onSuggestionLoadMoreFinished: ((messageId: String, groupKey: String, addedCount: Int) -> Unit)? = null

    /**
     * Pin a saved event as the "anchor" for the next plan Bo builds. Set via
     * the saved-event card tap path so Bo gets the literal event_id without
     * polluting the user-visible chat with `evt:abc123…` ids. Injected into
     * the system prompt for the next turn, then cleared so it doesn't bleed
     * into unrelated conversations.
     */
    fun setPendingAnchorEvent(eventId: String, eventTitle: String) {
        _uiState.update {
            it.copy(
                pendingAnchorEventId = eventId,
                pendingAnchorEventTitle = eventTitle,
            )
        }
    }

    fun onQuickReply(reply: QuickReply) {
        if (_uiState.value.isBusy) return
        when (reply.action) {
            "reset" -> resetChat()
            "save_plan" -> approveCurrentPlan()
            "build_plan_from_narration" -> sendMessage(
                "Yes — build that as a plan card now using the events you just listed. Call present_plan."
            )
            "show_as_tray" -> sendMessage(
                "Show those options as a tappable list. Call present_suggestions using the events you just searched — group them appropriately (by day if I picked a date range, by category otherwise). Do NOT respond with prose; the tool call IS the response."
            )
            "handoff_build_now" -> handoffBuildImmediate()
            "handoff_show_extras" -> handoffShowExtrasChips()
            "handoff_add_meals" -> handoffBuildWithExtras("meals")
            "handoff_add_drinks" -> handoffBuildWithExtras("drinks")
            "handoff_add_own_event" -> handoffBuildWithExtras("own_event")
            "handoff_something_else" -> handoffBuildWithExtras("something_else")
            else -> sendMessage(reply.label)
        }
    }

    // Plan editing is conversation-driven — no interactive card manipulation.
    // Users say "swap the dinner spot" or "remove the last event" and Claude
    // responds with a fresh plan card.

    fun recordEventTap(eventId: String) {
        viewModelScope.launch {
            val userId = userPrefsRepo.getLocalUserId() ?: return@launch
            val event = try { eventRepo.getEvent(eventId) } catch (_: Throwable) { null } ?: return@launch
            try { tasteRepo.recordEventTap(userId, event) } catch (_: Throwable) {}
        }
    }

    suspend fun getEventById(id: String): live.localapp.local.domain.model.Event? {
        return eventRepo.getEvent(id)
    }

    suspend fun getVenueByName(name: String): live.localapp.local.domain.model.Venue? {
        return venueRepo.searchByName(name)
    }

    /**
     * Build ItineraryDay list from ParsedChatResponse by batch-fetching events from the DB.
     * Uses Claude's suggested time if provided, otherwise falls back to event's start_at.
     */
    /**
     * When Bo provides an event_id whose DB venue doesn't match the venue
     * Bo named in `ref.note` (i.e. event_id mismatch), salvage the slot by
     * extracting the venue Bo intended ("...at X..." in the note) and
     * looking it up. Returns null if no recoverable venue can be parsed —
     * in which case the slot is dropped entirely, since rendering it with
     * the wrong event would lie to the user.
     */
    private suspend fun buildVenueSlotFromMismatchedNote(ref: live.localapp.local.data.remote.EventRef): ItinerarySlot? {
        val match = Regex("\\bat\\s+([A-Z][\\w'&.\\- ]{2,}?)(?=[,.;]|\\s+(?:in|on|this|tonight|tomorrow|today|—|–|-)|$)")
            .find(ref.note) ?: return null
        val venueName = match.groupValues[1].trim().trimEnd(',', '.', ';')
        if (venueName.length < 3) return null
        val venue = try { venueRepo.searchByName(venueName) } catch (_: Throwable) { null }
        return ItinerarySlot(
            id = generateId(),
            time = ref.time,
            emoji = "",
            name = venue?.name ?: venueName,
            detail = ref.note,
            price = "",
            isFree = false,
            imageUrl = venue?.photoUrl,
            eventId = null,
            isCustom = false,
            venueRating = venue?.googleRating,
            venueAddress = venue?.address,
            venueName = venue?.name ?: venueName,
            venueLat = venue?.latitude,
            venueLng = venue?.longitude,
            reservationUrl = venue?.reservationUrl,
            venueWebsite = venue?.website,
            venueCategory = venue?.category,
        )
    }

    private suspend fun buildPlanFromRefs(parsed: ParsedChatResponse): List<ItineraryDay> {
        println("buildPlanFromRefs: ${parsed.days.size} days, searchResultCache size=${searchResultCache.size}")
        return parsed.days.map { parsedDay ->
            println("buildPlanFromRefs: day='${parsedDay.label}' with ${parsedDay.events.size} refs")
            parsedDay.events.forEach { ref ->
                println("  ref: id='${ref.id}' time='${ref.time}' isVenue=${ref.isVenue} note='${ref.note.take(50)}'")
            }

            // Collect all event IDs (non-venue refs), strip any "evt:" prefix Claude may add.
            // CRITICAL: drop blanks. Bo sometimes emits a plan slot with neither event_id
            // nor venue_name (only a free-text note like "at Island Creek Raw Bar"); that
            // produces an EventRef with id="" and isVenue=false. An empty id reaching the
            // batched events fetch builds `id=in.(,<uuid>,)`, which Postgres rejects with
            // "invalid input syntax for type uuid: ''" — the whole plan build throws, Bo
            // retries, and ends up narrating the plan in plain text. Filtering blanks here
            // is the deterministic guard; the slot loop below recovers those refs as venues.
            val eventIds = parsedDay.events
                .filter { !it.isVenue }
                .map { it.id.removePrefix("evt:") }
                .filter { it.isNotBlank() }
            val eventsById = if (eventIds.isNotEmpty()) {
                val fetched = eventRepo.getEventsByIds(eventIds)
                println("buildPlanFromRefs: fetched ${fetched.size}/${eventIds.size} events from DB")
                fetched.associateBy { it.id }
            } else emptyMap()

            // Also check searchResultCache for any events not found in batch query
            val cacheById = searchResultCache.associateBy { it.id }
            println("buildPlanFromRefs: cache has ${cacheById.size} events")

            val slots = parsedDay.events.mapNotNull { ref ->
                // Fix B — degenerate ref recovery. Bo sometimes emits a plan slot
                // with neither event_id nor venue_name, only a free-text note like
                // "at Island Creek Raw Bar". That parses to id="" / isVenue=false.
                // Fix A already filters these out of the DB fetch (so they can't
                // crash the batched query), but if we let them fall through to the
                // event branch below they'd be dropped silently ("event '' not
                // found"), losing the stop Bo intended. Recover the venue name from
                // the note and render a venue slot instead.
                if (!ref.isBoWeb && !ref.isVenue && ref.id.removePrefix("evt:").isBlank()) {
                    val recovered = buildVenueSlotFromMismatchedNote(ref)
                    if (recovered != null) {
                        println("buildPlanFromRefs: recovered blank-id ref as venue slot '${recovered.name}' from note '${ref.note.take(60)}'")
                        return@mapNotNull recovered
                    }
                    println("buildPlanFromRefs: blank-id ref with no recoverable venue in note '${ref.note.take(60)}' — dropping")
                    return@mapNotNull null
                }
                if (ref.isBoWeb) {
                    // Bo-Web event: hydrate from the save_web_finding cache that
                    // was populated when Bo invoked the tool earlier this turn.
                    // No DB roundtrip — the input payload Bo passed has every
                    // field we need.
                    val cached = boWebFindingsCache[ref.id]
                    if (cached == null) {
                        // Cache miss usually means save_web_finding never ran or
                        // returned a different id than Bo put in the slot. Render
                        // a minimal placeholder slot from ref data so the plan
                        // still shows — better than dropping a stop silently.
                        println("buildPlanFromRefs: bo-web id '${ref.id}' missing from cache (cache size=${boWebFindingsCache.size} keys=${boWebFindingsCache.keys.take(5)}) — rendering placeholder")
                        return@mapNotNull ItinerarySlot(
                            id = generateId(),
                            time = ref.time,
                            emoji = "✨",
                            name = ref.note.take(60).ifBlank { "Bo's pick" },
                            detail = ref.note,
                            price = "",
                            isFree = false,
                            eventId = ref.id,
                            isCustom = false,
                            isBoWeb = true,
                        )
                    }
                    val title = cached["title"]?.jsonPrimitive?.contentOrNull
                        ?: cached["name"]?.jsonPrimitive?.contentOrNull
                        ?: ref.id
                    val venueName = cached["venue_name"]?.jsonPrimitive?.contentOrNull
                        ?: cached["name"]?.jsonPrimitive?.contentOrNull
                    val venueAddress = cached["venue_address"]?.jsonPrimitive?.contentOrNull
                        ?: cached["address"]?.jsonPrimitive?.contentOrNull
                    val venueCity = cached["venue_city"]?.jsonPrimitive?.contentOrNull
                        ?: cached["city"]?.jsonPrimitive?.contentOrNull
                    val venueLat = cached["venue_lat"]?.jsonPrimitive?.doubleOrNull
                        ?: cached["lat"]?.jsonPrimitive?.doubleOrNull
                    val venueLng = cached["venue_lng"]?.jsonPrimitive?.doubleOrNull
                        ?: cached["lng"]?.jsonPrimitive?.doubleOrNull
                    val ticketUrl = cached["ticket_url"]?.jsonPrimitive?.contentOrNull
                    val sourceUrl = cached["source_url"]?.jsonPrimitive?.contentOrNull
                    val imageUrl = cached["image_url"]?.jsonPrimitive?.contentOrNull
                    val isFree = cached["is_free"]?.jsonPrimitive?.booleanOrNull == true
                    return@mapNotNull ItinerarySlot(
                        id = generateId(),
                        time = ref.time,
                        emoji = "",
                        name = title,
                        detail = ref.note.ifBlank {
                            listOfNotNull(venueName, venueCity).joinToString(" · ").ifBlank { ref.note }
                        },
                        price = "",
                        isFree = isFree,
                        imageUrl = imageUrl,
                        eventId = ref.id,
                        isCustom = false,
                        isBoWeb = true,
                        ticketUrl = ticketUrl,
                        venueAddress = venueAddress,
                        venueName = venueName,
                        venueLat = venueLat,
                        venueLng = venueLng,
                        sourceUrl = sourceUrl,
                    )
                }
                if (ref.isVenue) {
                    // Venue ref — look up by name in the venue repo. When the
                    // venue isn't in our DB (Bo just discovered it via
                    // recommend_and_save_venue → "validated_not_saved"), the DB
                    // lookup misses, so fall back to the validated Google
                    // metadata cached when that tool ran. Prefer DB fields,
                    // backfill each null from the cache.
                    val venue = try { venueRepo.searchByName(ref.id) } catch (_: Exception) { null }
                    val validated = validatedVenueCache[venueKey(ref.id)]
                    val vName = venue?.name ?: validated?.name ?: ref.id
                    val vCity = venue?.city ?: validated?.city
                    val vRating = venue?.googleRating ?: validated?.googleRating
                    val vAddress = venue?.address ?: validated?.address
                    val vLat = venue?.latitude ?: validated?.lat
                    val vLng = venue?.longitude ?: validated?.lng
                    val vReservation = venue?.reservationUrl ?: validated?.reservationUrl
                    val vWebsite = venue?.website ?: validated?.website
                    val vCategory = venue?.category ?: validated?.category
                    val vPhoto = venue?.photoUrl ?: validated?.photoUrl
                    ItinerarySlot(
                        id = generateId(),
                        time = ref.time,
                        emoji = "",
                        name = vName,
                        detail = ref.note.ifBlank {
                            buildString {
                                append(vCity ?: "")
                                if (vRating != null) append(" · $vRating")
                            }.ifBlank { ref.id }
                        },
                        price = "",
                        isFree = false,
                        imageUrl = vPhoto,
                        eventId = null,
                        isCustom = false,
                        venueRating = vRating,
                        venueAddress = vAddress,
                        venueCity = vCity,
                        venueName = vName,
                        venueLat = vLat,
                        venueLng = vLng,
                        reservationUrl = vReservation,
                        venueWebsite = vWebsite,
                        venueCategory = vCategory,
                    )
                    .also { println("  slot: name='${it.name}' (db=${venue != null}, validatedCache=${validated != null}) lat=$vLat lng=$vLng rating=$vRating reservationUrl=$vReservation website=$vWebsite category=$vCategory") }
                } else {
                    // Event ref — look up from DB (strip evt: prefix)
                    val cleanId = ref.id.removePrefix("evt:")
                    val event = eventsById[cleanId] ?: cacheById[cleanId]
                    if (event == null) {
                        println("buildPlanFromRefs: event '${ref.id}' not found in DB or cache, dropping")
                        return@mapNotNull null
                    }

                    // Sanity check: when Bo's note names a venue but the
                    // event_id Bo provided points to a different venue,
                    // the event_id is wrong (Bo confused IDs while writing
                    // present_plan). Don't render a slot whose title says
                    // one thing and whose description says another. Drop
                    // back to a venue-name slot so the card matches Bo's
                    // intent — title = the real venue, no hallucinated
                    // event mismatch.
                    val noteLower = ref.note.lowercase()
                    val eventVenueLower = event.venueName?.lowercase()?.trim().orEmpty()
                    // Token-based fuzzy match — a venue is "the same" if the
                    // tokens of the shorter name are all present in the longer
                    // one, in any order. Catches:
                    //   "Hatch Shell" vs "Hatch Memorial Shell"
                    //   "The Sinclair" vs "Sinclair Music Hall"
                    //   "MIT Museum" vs "MIT Museum at Cambridge"
                    // Without this, Bo's natural-language shorthand triggers
                    // the mismatch guard and we drop event_id (losing event
                    // detail navigation + image) for what is actually a match.
                    fun tokenize(s: String): Set<String> = s
                        .lowercase()
                        .replace(Regex("[^a-z0-9\\s]"), " ")
                        .split(Regex("\\s+"))
                        .filter { it.length >= 3 && it !in setOf("the", "and", "for", "boston") }
                        .toSet()
                    fun fuzzyVenueMatch(a: String, b: String): Boolean {
                        if (a.isBlank() || b.isBlank()) return false
                        if (a.contains(b) || b.contains(a)) return true
                        val ta = tokenize(a); val tb = tokenize(b)
                        if (ta.isEmpty() || tb.isEmpty()) return false
                        val (smaller, larger) = if (ta.size <= tb.size) ta to tb else tb to ta
                        return smaller.isNotEmpty() && smaller.all { it in larger }
                    }
                    val noteMentionsDifferentVenue = eventVenueLower.isNotBlank() &&
                        noteLower.isNotBlank() &&
                        !fuzzyVenueMatch(noteLower, eventVenueLower) &&
                        // Look for any "at <Capitalized Venue>" or "at <venue>" pattern
                        // in Bo's note that doesn't match the DB event's venue.
                        Regex("\\bat\\s+([A-Z][\\w'&.\\- ]{2,})", RegexOption.IGNORE_CASE)
                            .findAll(ref.note)
                            .map { it.groupValues[1].trim().lowercase() }
                            .any { mentioned ->
                                mentioned.isNotBlank() && !fuzzyVenueMatch(mentioned, eventVenueLower)
                            }
                    if (noteMentionsDifferentVenue) {
                        println("buildPlanFromRefs: event_id mismatch — Bo's note '${ref.note.take(80)}' " +
                            "names a venue different from event.venueName='${event.venueName}'. " +
                            "Dropping event_id and rendering as venue slot to avoid wrong-event corruption.")
                        // Fall through: synthesize a venue slot below using Bo's note text.
                        return@mapNotNull buildVenueSlotFromMismatchedNote(ref)
                    }

                    // Derive the slot time from the DB event's start_at —
                    // Claude routinely misreads the calendar or invents approximate
                    // times, so the DB is the source of truth.
                    //
                    // EXCEPTION: many events in the DB sit at exactly 00:00 local
                    // time (midnight Eastern) because the ingestion pipeline parsed
                    // a date-only source and defaulted to atStartOfDay(). For those,
                    // prefer Bo's ref.time (which usually picks a sensible hour from
                    // context like "afternoon walking tour"). The DST-aware UTC
                    // pattern (04:00+00 in summer, 05:00+00 in winter) is the
                    // signature of date-only events; we treat *any* exact midnight
                    // local time as the same case.
                    val parsedDisplay = event.startAt?.let { iso ->
                        try {
                            val instant = kotlinx.datetime.Instant.parse(iso)
                            val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                            val hour = local.hour
                            val minute = local.minute
                            val amPm = if (hour < 12) "AM" else "PM"
                            val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
                            if (minute == 0) "$displayHour $amPm" else "$displayHour:${minute.toString().padStart(2, '0')} $amPm"
                        } catch (_: Exception) { null }
                    }
                    // Date-only events (timeKnown=false in DB, or midnight ET as a
                    // legacy heuristic) get Bo's ref.time when available — Bo picks
                    // a context-appropriate hour. Time-known events always use the
                    // DB value to prevent Claude from inventing wrong times.
                    val isDateOnly = !event.timeKnown ||
                        (parsedDisplay == "12 AM")
                    val time = when {
                        parsedDisplay == null -> ref.time
                        isDateOnly && ref.time.isNotBlank() -> {
                            println("buildPlanFromRefs: event '${event.title}' is date-only (timeKnown=${event.timeKnown}); using Bo's ref.time='${ref.time}' instead of DB '$parsedDisplay'")
                            ref.time
                        }
                        else -> parsedDisplay
                    }

                    // Fall back to venue photo if event has no image. Log the
                    // resolved URL so missing-image bugs are easy to triage —
                    // we can see whether (a) the event row had no image_url
                    // in DB, (b) the venue lookup failed, or (c) the URL is
                    // present but malformed/blocked from rendering.
                    val venuePhoto = event.venueName?.let { name ->
                        try { venueRepo.searchByName(name)?.photoUrl } catch (_: Exception) { null }
                    }
                    val slotImage = event.imageUrl ?: venuePhoto
                    println("buildPlanFromRefs: slotImage for '${event.title}' " +
                        "event.imageUrl=${event.imageUrl?.take(60) ?: "null"} " +
                        "venuePhoto=${venuePhoto?.take(60) ?: "null"} " +
                        "→ slotImage=${slotImage?.take(60) ?: "null"}")

                    // Look up venue website + category for events too
                    val eventVenue = event.venueName?.let { name ->
                        try { venueRepo.searchByName(name) } catch (_: Exception) { null }
                    }
                    val eventVenueWebsite = eventVenue?.website
                    val eventVenueCategory = eventVenue?.category

                    ItinerarySlot(
                        id = generateId(),
                        time = time,
                        emoji = "",
                        name = event.title,
                        detail = ref.note.ifBlank { "${event.venueName ?: ""} · ${event.venueCity}" },
                        price = event.priceDisplay,
                        isFree = event.isFree,
                        imageUrl = slotImage,
                        eventId = event.id,
                        isCustom = false,
                        ticketUrl = event.ticketUrl,
                        venueAddress = event.venueAddress,
                        venueCity = event.venueCity,
                        venueName = event.venueName,
                        venueLat = event.venueLat,
                        venueLng = event.venueLng,
                        priceMin = event.priceMin,
                        priceMax = event.priceMax,
                        sourceUrl = event.sourceUrl,
                        venueWebsite = eventVenueWebsite,
                        venueCategory = eventVenueCategory,
                    )
                    .also { println("  slot: name='${it.name}' sourceUrl=${event.sourceUrl} venueWebsite=$eventVenueWebsite ticketUrl=${event.ticketUrl} venueCategory=$eventVenueCategory") }
                }
            }

            // Derive the day label from the earliest DB-backed event's date if
            // any event slot exists — Claude's parsedDay.label often disagrees
            // with the real date (e.g. claims "Friday, April 24" for a Saturday
            // event). For venue-only days without any event_id anchor, fall back
            // to Claude's label since there's no DB anchor to trust.
            val earliestEventStart = slots
                .mapNotNull { slot -> slot.eventId?.let { id -> eventsById[id] ?: cacheById[id] } }
                .mapNotNull { it.startAt }
                .mapNotNull { iso -> try { kotlinx.datetime.Instant.parse(iso) } catch (_: Exception) { null } }
                .minOrNull()

            val (label, shortName) = if (earliestEventStart != null) {
                val local = earliestEventStart.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                val dowFull = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
                val monthFull = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
                val derivedLabel = "${dowFull[local.dayOfWeek.ordinal]}, ${monthFull[local.month.ordinal]} ${local.dayOfMonth}"
                val derivedShort = dowFull[local.dayOfWeek.ordinal].take(3).uppercase()
                derivedLabel to derivedShort
            } else {
                val fallbackShort = parsedDay.label.split(",").firstOrNull()?.trim()?.take(3)?.uppercase() ?: "DAY"
                parsedDay.label to fallbackShort
            }

            ItineraryDay(
                label = label,
                shortName = shortName,
                slots = sortSlotsByTime(slots.map { it.copy(time = normalizeTimeDisplay(it.time)) }),
            )
        }
        // Drop empty days. An empty day card with a Save button persists
        // a garbage plan; better to ship the days that have content. If
        // the user wanted that day populated they can ask Bo to fill it.
        // Logs the parsedDay label so you can see in console which day
        // came back empty and why every slot was dropped (each drop
        // path inside the loop prints its own reason).
        .filter { day ->
            if (day.slots.isEmpty()) {
                println("buildPlanFromRefs: dropping empty day '${day.label}' — every ref was rejected upstream (event_id miss, Bo-Web cache miss, or venue mismatch). Check logs above for per-ref drop reasons.")
                false
            } else true
        }
    }

    /**
     * Guard against Bo silently dropping a stop the user explicitly chose.
     *
     * The build-from-picks handoff ([sendBuildIntent]) passes the exact picked
     * event_ids to Bo and asks it to echo them into `present_plan`. Bo usually
     * complies, but sometimes its prose enumerates all N picks while its tool
     * call emits only N-1 refs — so the rebuilt plan card is missing a stop the
     * user picked (e.g. "3 picks" → 2-stop card). Since [expectedIds] is the
     * authoritative client-side pick list, we detect any pick absent from the
     * built plan and re-inject it by resolving the event and routing it through
     * [buildPlanFromRefs] (one synthetic single-event day per missing pick, so
     * each produced day carries the correct DB-derived label). The new slot is
     * then merged into the day whose label matches; if no day matches, the day
     * is appended. No-op when [expectedIds] is empty or all picks survived.
     */
    private suspend fun reconcileExpectedPicks(
        days: List<ItineraryDay>,
        expectedIds: List<String>,
        pruneNonPicks: Boolean = false,
    ): List<ItineraryDay> {
        if (expectedIds.isEmpty()) return days

        // PRUNE (pure build-from-picks only): drop EVENT slots the user did not
        // pick — Bo substitutes events it knows when it can't resolve the
        // client-injected pick ids, producing a plan full of wrong events.
        // Non-event slots (transit, meal/venue extras — no eventId) are kept.
        val working = if (pruneNonPicks) {
            val keep = expectedIds.distinct().toSet()
            days.map { day ->
                day.copy(slots = day.slots.filter { slot ->
                    val id = slot.eventId?.removePrefix("evt:")
                    id == null || id in keep
                })
            }.filter { it.slots.isNotEmpty() }
        } else days

        val present = working
            .flatMap { it.slots }
            .mapNotNull { it.eventId?.removePrefix("evt:") }
            .toSet()
        val missing = expectedIds.distinct().filter { it !in present }
        if (missing.isEmpty()) return working

        println("reconcileExpectedPicks: Bo omitted ${missing.size}/${expectedIds.size} picked event(s) from present_plan: $missing — re-injecting")

        // Resolve + build slots by reusing buildPlanFromRefs. One synthetic day
        // per missing pick; buildPlanFromRefs derives each day's label from the
        // event's own start_at, so labels line up with the existing plan days.
        val synthParsed = ParsedChatResponse(
            type = ParsedResponseType.PLAN,
            text = "",
            days = missing.map { id ->
                live.localapp.local.data.remote.ParsedDay(
                    label = "",
                    events = listOf(
                        live.localapp.local.data.remote.EventRef(id = id, time = "", note = "", isVenue = false),
                    ),
                )
            },
        )
        val rebuilt = buildPlanFromRefs(synthParsed)
        if (rebuilt.isEmpty()) {
            println("reconcileExpectedPicks: could not resolve any omitted pick (DB/cache miss) — leaving plan as-is")
            return working
        }

        val merged = working.toMutableList()
        rebuilt.forEach { newDay ->
            val idx = merged.indexOfFirst { it.label == newDay.label }
            if (idx >= 0) {
                val combined = sortSlotsByTime(merged[idx].slots + newDay.slots)
                merged[idx] = merged[idx].copy(slots = combined)
            } else {
                merged.add(newDay)
            }
        }
        return merged
    }

    fun resetChat() {
        live.localapp.local.observability.Analytics.track(
            live.localapp.local.observability.AnalyticsEvent.boThreadCleared()
        )
        // Reset must `cancelAndJoin` (not just `cancel`) so the in-flight
        // welcome stream is FULLY torn down before we clear state and launch
        // the next one. Without the join, a pending TextDelta callback from
        // the old stream can land between our `_uiState.value = ChatUiState()`
        // and the new welcome's first emission — the user sees the partial
        // greeting (e.g. "Hey there! W") flash, then delete, then the new
        // greeting type out from scratch.
        viewModelScope.launch {
            welcomeJob?.cancelAndJoin()
            conversationHistory.clear()
            searchResultCache.clear()
            pendingBuildExpectedIds = emptyList()
            lastSearchKeyword = null
            lastSearchLocation = null
            lastSearchStrictLocation = false
            lastBrowseQuery = null
            lastSearchDateRange = null
            lastSearchStartDate = null
            lastSearchEndDate = null
            boTools.resetCache()  // shared BoTools cache — both surfaces clear together
            // Bo scope is conversation-scoped — "Start over" ends the
            // conversation, so the chip dies with it. AppNavigation will
            // sync home → Bo on the next chat open (it gates on empty
            // conversation history).
            clearPersistedScope()
            _uiState.value = ChatUiState()
            showWelcome()
        }
    }

    // ── Core: Single unified message handler ──

    /** Every event this turn's search_events call(s) returned (the full pool,
     *  before the 80-row cap Bo reads). The results tray/map is filled from
     *  this — not Bo's curated picks — so the map shows every match. Reset at
     *  the start of each user turn. */
    private val turnSearchPool = mutableListOf<live.localapp.local.domain.model.Event>()

    private fun handleMessage(text: String, historyText: String = text) {
        _uiState.update { it.copy(quickReplies = emptyList()) }
        turnSearchPool.clear()
        // Cache previously cleared every message — caused Bo to "forget"
        // events it had just narrated, then contradict itself on the next
        // turn. Now we persist across messages within the same chat
        // session; cleared only on /reset (resetChat()).
        lastUserMessage = text
        viewModelScope.launch {
            setBusy(true)
            // Show the searching idiom from the moment the turn starts —
            // SEARCH-FIRST means virtually every turn calls a tool, so the
            // idiom is the right indicator. Without this, users stared at
            // the three-dots typing indicator for ~10s before the first
            // tool_use chunk flipped isSearching=true. If Bo replies with
            // pure text (no tools), the idiom briefly transitions to the
            // bubble — fine, it never deceives.
            _uiState.update { it.copy(isSearching = true, isTyping = false, searchingNarration = emptyList(), searchingOpener = null) }
            // historyText may differ from `text` when a clean user-visible
            // bubble is paired with a verbose technical instruction for Bo
            // (e.g. the Build-plan-from-tray flow).
            appendTextToHistory("user", historyText)

            // Stamp the turn start so bo_response_received carries real user-
            // perceived latency (request → rendered response, retries included)
            // instead of the hardcoded 0L it shipped with.
            val turnStartMs = kotlin.time.Clock.System.now().toEpochMilliseconds()

            // Browse fast-path: when the user's text maps to a concrete search
            // (e.g. "Live music this week"), run it now and prime history so the
            // single Claude call goes straight to present_suggestions — halving
            // the round-trips. Skipped when historyText carries a technical
            // instruction (build-from-tray etc.) rather than the literal text.
            // Done ONCE here (not per-retry attempt) so a retry reuses the
            // already-primed history instead of double-searching.
            val browseInput = if (historyText == text) inferBrowseSearchInput(text) else null
            // Open the wait with a human beat: a one-line acknowledgment that
            // echoes the request + scope, shown above the ✓ steps. Browse-only
            // (browseInput != null) so it never renders for plan/approve/chat
            // turns where "Let me find <text> for you" would read wrong.
            if (browseInput != null) {
                val opener = buildSearchingOpener(
                    text,
                    _uiState.value.currentLocationName,
                    _uiState.value.searchRadiusMiles.toInt(),
                )
                _uiState.update { it.copy(searchingOpener = opener) }
            }
            val primeStartMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
            val forcePresentSuggestions = browseInput != null && primeBrowseSearch(browseInput)
            println("⏱️ bo-latency: prime phase ${kotlin.time.Clock.System.now().toEpochMilliseconds() - primeStartMs}ms (browseInput=$browseInput forcePresent=$forcePresentSuggestions)")

            // Build-from-web turn: the "Build from web" chip stages findings
            // surfaced on a PRIOR turn, so no search runs now and the web-search
            // gate stays closed. Without this flag save_web_finding would be
            // absent from the tools array exactly when Bo is told to call it.
            val buildFromWeb = historyText == BUILD_FROM_WEB_INTENT

            var attempts = 0
            val maxAttempts = 2
            while (attempts < maxAttempts) {
                try {
                    val systemPrompt = buildSystemPrompt()
                    val parsed = sendWithToolLoop(systemPrompt, forcePresentSuggestions, buildFromWeb)
                    val shape = when (parsed.type) {
                        ParsedResponseType.SUGGESTIONS -> {
                            val sj = parsed.structuredJson?.jsonObject
                            val groups = (sj?.get("groups") as? kotlinx.serialization.json.JsonArray)?.size ?: 0
                            val events = (sj?.get("groups") as? kotlinx.serialization.json.JsonArray)?.sumOf { g ->
                                (g.jsonObject["events"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
                            } ?: 0
                            "groups=$groups events=$events"
                        }
                        ParsedResponseType.EVENT_BRIEF, ParsedResponseType.VENUE_BRIEF,
                        ParsedResponseType.QUICK_FACT, ParsedResponseType.SCOPE_REDIRECT -> {
                            "structured=${parsed.structuredJson?.jsonObject?.keys?.joinToString(",")}"
                        }
                        else -> "days=${parsed.days.size} chips=${parsed.chips.size}"
                    }
                    println("Claude response: type=${parsed.type} text=${parsed.text.take(100)} $shape")
                    run {
                        val eventCount = (parsed.structuredJson?.jsonObject?.get("groups") as? kotlinx.serialization.json.JsonArray)?.sumOf { g ->
                            (g.jsonObject["events"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
                        } ?: 0
                        live.localapp.local.observability.Analytics.track(
                            live.localapp.local.observability.AnalyticsEvent.boResponseReceived(
                                responseMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - turnStartMs,
                                hadWebSearch = parsed.text.contains("web", ignoreCase = true),
                                hadChips = parsed.chips.isNotEmpty(),
                                resultEventCount = eventCount,
                            )
                        )
                    }
                    handleClaudeResponse(parsed)
                    break
                } catch (e: Throwable) {
                    // Entitlement rejection (free user / lapsed trial → proxy 402) is
                    // EXPECTED control flow, not a failure. Route straight to the
                    // paywall: retrying a 402 just 402s again, and recording it to
                    // Sentry buries real crashes under handled-paywall noise.
                    if (e is live.localapp.local.data.remote.BoEntitlementRequiredException ||
                        e.message?.contains("Local Pro required", true) == true) {
                        println("ChatVM: entitlement required — paywall, no retry/record")
                        _uiState.update { it.copy(streamingText = "") }
                        handleAiError(
                            if (e is Exception) e
                            else live.localapp.local.data.remote.BoEntitlementRequiredException()
                        )
                        break
                    }
                    attempts++
                    println("Claude error (attempt $attempts/$maxAttempts): ${e::class.simpleName}: ${e.message}")
                    e.printStackTrace()
                    live.localapp.local.observability.Analytics.track(
                        live.localapp.local.observability.AnalyticsEvent.boResponseFailed(
                            errorCode = e::class.simpleName ?: "Unknown",
                            phase = "llm",
                        )
                    )
                    if (attempts < maxAttempts) {
                        println("ChatVM: retrying in 2s...")
                        _uiState.update { it.copy(streamingText = "") }
                        kotlinx.coroutines.delay(2000)
                        continue
                    }
                    // Only report to Sentry once we've actually given up — recording
                    // per-attempt inflated the error count for every transient blip.
                    live.localapp.local.observability.Analytics.recordError(e, "bo_response_failed")
                    _uiState.update { it.copy(streamingText = "") }
                    try {
                        handleAiError(if (e is Exception) e else RuntimeException(e.message ?: "Unknown error"))
                    } catch (_: Throwable) {
                        addAiMessage("Something went wrong. Please try again.")
                        setBusy(false)
                    }
                }
            }
        }
    }

    // ── Tool execution loop ──

    /**
     * Send a single streaming request to Claude. Emits text deltas to the UI
     * in real-time via streamingText, then returns the assembled ClaudeResponse.
     * For tool-use rounds (isToolRound=true), streaming text is hidden and
     * the searching indicator is shown instead.
     */
    private suspend fun sendStreaming(
        systemPrompt: String,
        tools: JsonArray?,
        isToolRound: Boolean,
        model: String = live.localapp.local.data.remote.ClaudeApiService.MODEL_PLAN,
        toolChoice: kotlinx.serialization.json.JsonObject? = null,
    ): ClaudeResponse {
        // Sanitize conversation history before sending — Anthropic returns 400 if
        // any tool_use block isn't followed in the next message by a matching
        // tool_result. We've seen this fire on the edit path after a long
        // chat — a tool execution must've returned without its result being
        // appended (timeout, race, or upstream cancellation). Walk the history
        // and synthesize a placeholder tool_result for any dangling tool_use
        // ids. Safe: a synthetic result tells Bo the tool ran but produced
        // nothing actionable, never lies about the data.
        sanitizeToolUsePairing()

        val streamBuffer = StringBuilder()
        var lastEmitTime = 0L
        val emitIntervalMs = 40L // Smooth ~25fps updates

        // pause_turn continuation: Anthropic interrupts long server-tool turns
        // (web_search / web_fetch) with stop_reason="pause_turn" BEFORE Bo has
        // written the final answer. The turn isn't done — we must echo the
        // partial assistant content (server_tool_use + tool_result blocks intact)
        // back and re-call to let Bo finish. Without this, iOS users watch Bo
        // "start typing and never finish" — the half-built turn just stops.
        // We work on a LOCAL copy so conversationHistory keeps its normal
        // server-tool-stripped shape; the caller appends the final turn itself.
        val msgs = conversationHistory.toMutableList()
        val maxPauseContinues = 3
        var pauseContinues = 0
        var response: ClaudeResponse? = null

        while (true) {
            var roundResponse: ClaudeResponse? = null
            claudeApi.sendRawStreaming(
                systemPrompt = systemPrompt,
                messages = msgs,
                tools = tools,
                model = model,
                toolChoice = toolChoice,
            ).collect { event ->
                when (event) {
                    is StreamEvent.TextDelta -> {
                        if (!isToolRound) {
                            streamBuffer.append(event.text)
                            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                            if (now - lastEmitTime >= emitIntervalMs) {
                                lastEmitTime = now
                                val current = streamBuffer.toString()
                                _uiState.update { it.copy(
                                    streamingText = current,
                                    isTyping = false,
                                ) }
                            }
                        }
                    }
                    is StreamEvent.ToolUseStart -> {
                        // Pre-tool-use text is preamble narration ("Let me check…",
                        // "I'm not finding May 15, let me try broader…") — the
                        // NO-PREAMBLE prompt rule forbids it but Claude emits it
                        // anyway, especially between rounds of a multi-search
                        // turn. Discard it so the user sees only the searching
                        // idiom + the final answer instead of a wall of self-narration.
                        streamBuffer.clear()
                        _uiState.update { it.copy(isSearching = true, streamingText = "") }
                    }
                    is StreamEvent.InputDelta -> { /* Accumulated internally by the Flow */ }
                    is StreamEvent.Done -> {
                        // Flush any remaining buffered text
                        if (!isToolRound && streamBuffer.isNotEmpty()) {
                            val full = streamBuffer.toString()
                            _uiState.update { it.copy(streamingText = full) }
                            // Remember what was just revealed live so the
                            // finalized bubble doesn't replay the typewriter.
                            lastStreamedText = full
                        }
                        roundResponse = event.response
                    }
                }
            }

            val r = roundResponse ?: throw RuntimeException("Stream ended without a complete response")

            if (r.stopReason == "pause_turn" && pauseContinues < maxPauseContinues) {
                // Resume the paused turn: append the partial assistant content
                // VERBATIM (server_tool_use + *_tool_result preserved) and loop.
                pauseContinues++
                msgs.add(
                    ClaudeMessage.blocks(
                        "assistant",
                        claudeApi.buildAssistantContentBlocks(r, preserveServerTools = true),
                    ),
                )
                continue
            }

            response = r
            break
        }

        return response ?: throw RuntimeException("Stream ended without a complete response")
    }

    /** Terminal tool names — these produce UI output, not results for Claude. */
    private val terminalTools = setOf(
        "present_plan", "update_plan",
        // Phase 6 structured-response tools — same handling: terminate the
        // tool loop and route the JSON payload through to the UI.
        "present_suggestions", "present_event_brief", "present_venue_brief",
        "present_quick_fact", "present_scope_redirect",
    )

    /** Subset of terminalTools that's a structured-response tool (Phase 6). */
    private val structuredTools = setOf(
        "present_suggestions", "present_event_brief", "present_venue_brief",
        "present_quick_fact", "present_scope_redirect",
    )

    /**
     * Build a ParsedChatResponse from a terminal tool call. Handles
     * present_plan, update_plan, plus the Phase-6 structured tools
     * (present_suggestions, present_event_brief, etc.).
     */
    private fun parsePlanToolCall(
        toolCall: live.localapp.local.data.remote.ToolCall,
        text: String,
    ): ParsedChatResponse {
        val input = toolCall.input
        val chips = input["chips"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

        // Phase 6 — structured-response tools. Parse the input JSON and
        // hand it to the UI; the conversion to BoStructuredResponse domain
        // model happens in handleClaudeResponse.
        when (toolCall.name) {
            "present_suggestions" -> return ParsedChatResponse(
                type = ParsedResponseType.SUGGESTIONS,
                text = text,
                structuredJson = input,
            )
            "present_event_brief" -> return ParsedChatResponse(
                type = ParsedResponseType.EVENT_BRIEF,
                text = text,
                structuredJson = input,
            )
            "present_venue_brief" -> return ParsedChatResponse(
                type = ParsedResponseType.VENUE_BRIEF,
                text = text,
                structuredJson = input,
            )
            "present_quick_fact" -> return ParsedChatResponse(
                type = ParsedResponseType.QUICK_FACT,
                text = text,
                structuredJson = input,
            )
            "present_scope_redirect" -> return ParsedChatResponse(
                type = ParsedResponseType.SCOPE_REDIRECT,
                text = text,
                structuredJson = input,
            )
        }

        if (toolCall.name == "update_plan") {
            val dayIndex = input["day_index"]?.jsonPrimitive?.intOrNull ?: 0
            val label = input["label"]?.jsonPrimitive?.contentOrNull ?: ""
            val slots = input["slots"]?.jsonArray?.map { parsePlanSlot(it.jsonObject) } ?: emptyList()
            // attendee_count may be passed when the user changes the group
            // size mid-conversation. Optional; null means "no change."
            val attendeeCount = input["attendee_count"]?.jsonPrimitive?.intOrNull
            return ParsedChatResponse(
                type = ParsedResponseType.PLAN_UPDATE,
                text = text,
                dayIndex = dayIndex,
                attendeeCount = attendeeCount,
                days = listOf(live.localapp.local.data.remote.ParsedDay(label = label, events = slots)),
                chips = chips,
            )
        }

        // present_plan
        val title = input["title"]?.jsonPrimitive?.contentOrNull ?: "Your Plan"
        val attendeeCount = input["attendee_count"]?.jsonPrimitive?.intOrNull
        val daysArray = input["days"]?.jsonArray ?: kotlinx.serialization.json.buildJsonArray {}
        val parsedDays = daysArray.map { dayEl ->
            val dayObj = dayEl.jsonObject
            val label = dayObj["label"]?.jsonPrimitive?.contentOrNull ?: ""
            val slots = dayObj["slots"]?.jsonArray?.map { parsePlanSlot(it.jsonObject) } ?: emptyList()
            live.localapp.local.data.remote.ParsedDay(label = label, events = slots)
        }

        return ParsedChatResponse(
            type = ParsedResponseType.PLAN,
            text = text,
            planTitle = title,
            attendeeCount = attendeeCount,
            days = parsedDays,
            chips = chips,
        )
    }

    /** Parse a single slot from present_plan/update_plan JSON into an EventRef. */
    private fun parsePlanSlot(slotObj: JsonObject): live.localapp.local.data.remote.EventRef {
        val eventId = slotObj["event_id"]?.jsonPrimitive?.contentOrNull
        val venueName = slotObj["venue_name"]?.jsonPrimitive?.contentOrNull
        val time = slotObj["time"]?.jsonPrimitive?.contentOrNull ?: ""
        val note = slotObj["note"]?.jsonPrimitive?.contentOrNull ?: ""
        val isBoWeb = slotObj["is_bo_web"]?.jsonPrimitive?.booleanOrNull == true
        // Defensive: if Bo flagged is_bo_web but only filled venue_name (no event_id),
        // it found a specific event but classified it as a venue stop — exactly the
        // bug the prompt warns against. Log loudly so we can spot it; treat as venue
        // ref so something renders, but the slot will be missing event context.
        if (isBoWeb && eventId == null && venueName != null) {
            println("parsePlanSlot WARNING: is_bo_web=true with venue_name but no event_id — Bo lost the event title for venue '$venueName'. Slot will render as venue without event context.")
        }
        return live.localapp.local.data.remote.EventRef(
            id = eventId ?: venueName ?: "",
            time = time,
            note = note,
            isVenue = eventId == null && venueName != null,
            isBoWeb = isBoWeb,
        )
    }

    /**
     * Send the conversation to Claude and handle the tool-use loop:
     * send → if tool_use → execute tool → append tool_result → send again → repeat
     * until Claude gives a final text (stop_reason == "end_turn") or calls a
     * terminal tool (present_plan/update_plan).
     *
     * Uses streaming so users see words appearing in real-time.
     */
    /**
     * Heuristic detector for "Bo narrated a plan in prose instead of
     * calling present_plan." Looks for plan-build commitment phrasing
     * combined with structured-output signals (Day N headers, bullet
     * lists, longer-form text). False positives are bounded by the
     * single-retry cap upstream.
     */
    /**
     * Detects when Bo emitted a prose list of event options after a successful
     * search — the "Bo skipped present_suggestions and just wrote a paragraph
     * with **bold names**" failure mode. Distinct from looksLikeNarratedPlan
     * (which detects day-by-day itinerary commitments). Used to surface a
     * "Show as tappable list" recovery chip so the user can force Bo back
     * into a tray on the next turn without re-typing their question.
     *
     * Heuristic: at least two bold spans (`**X**`) AND at least two weekday
     * mentions (Friday/Saturday/etc.) — the canonical "Friday, X; Saturday,
     * Y" shape. Also matches when the searchResultCache is populated, which
     * tells us we have real events to convert.
     */
    private fun looksLikeNarratedSuggestions(text: String, searchedCount: Int): Boolean {
        if (text.length < 100) return false
        if (searchedCount < 2) return false
        val boldSpans = Regex("""\*\*[^*]{2,80}\*\*""").findAll(text).count()
        if (boldSpans < 2) return false
        val lower = text.lowercase()
        val weekdayMatches = listOf(
            "monday", "tuesday", "wednesday", "thursday",
            "friday", "saturday", "sunday",
        ).count { lower.contains(it) }
        if (weekdayMatches < 2) return false
        // Skip if it ALSO looks like a full plan — that has its own chip.
        if (looksLikeNarratedPlan(text)) return false
        return true
    }

    private fun looksLikeNarratedPlan(text: String): Boolean {
        if (text.length < 120) return false
        val lower = text.lowercase()
        val commitments = listOf(
            "here's your", "here is your", "boom — here", "boom—here",
            "boom, here", "boom! here", "your full", "your day-by-day",
            "your itinerary", "i've put together", "i've built",
            "i've assembled", "let me save a few more", "let me save",
            "let me build", "alright! here", "perfect! here",
            "here's the plan", "your boston", "your nantucket",
            "your trip", "your weekend", "comprehensive 6-day",
            "comprehensive 5-day", "your 6-day", "your 5-day",
            "your 4-day", "your 3-day",
        )
        val structureSignals = listOf(
            "day 1", "day 2", "day 3", "day 4", "day 5", "day 6",
            "morning:", "afternoon:", "evening:", "tonight:",
            "**day", "**morning", "**afternoon",
            "\n-", "\n•", "\n*",
        )
        val hasCommitment = commitments.any { lower.contains(it) }
        val hasStructure = structureSignals.any { lower.contains(it) }
        return hasCommitment && (hasStructure || text.length > 500)
    }

    /**
     * Turn a round's data-tool calls into short, human-readable narration
     * steps for the searching indicator (e.g. "Searching within 20 miles of
     * Waltham", "Filtering to your dates", "Matching music events"). Derived
     * entirely from the already-parsed tool args, so it costs zero extra
     * tokens and never reopens the Bo-prose-preamble failure mode. Returns an
     * ordered, de-duped list capped at 4 lines; empty when no narratable tool
     * was called (the indicator then falls back to the playful gerunds).
     */
    /**
     * Build the one-line browse opener shown above the searching steps. Echoes
     * the user's verbatim request plus the search scope (radius + chip
     * location). Verbatim text keeps it honest and zero-cost (no Claude round)
     * — the browse fast-path runs the search before Bo's single call, so the
     * opener MUST be client-derived. Falls back to a scope-only line when the
     * query or location is missing.
     */
    private fun buildSearchingOpener(query: String, location: String, radiusMiles: Int): String {
        val q = query.trim().trimEnd('.', '!', '?', ' ')
        val scope = if (location.isNotBlank())
            "Searching within $radiusMiles miles of $location."
        else
            "Searching nearby."
        return if (q.isBlank()) scope else "Let me find $q for you. $scope"
    }

    private fun narrateToolCalls(calls: List<live.localapp.local.data.remote.ToolCall>): List<String> {
        val steps = mutableListOf<String>()
        for (call in calls) {
            val input = call.input
            fun str(k: String) = input[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            fun bool(k: String) = input[k]?.jsonPrimitive?.booleanOrNull
            when (call.name) {
                "search_events" -> {
                    val loc = str("location")
                    val radius = _uiState.value.searchRadiusMiles.toInt()
                    steps += when {
                        loc != null && bool("strict_location") == false -> "Searching within $radius miles of $loc"
                        loc != null -> "Searching events in $loc"
                        else -> "Searching nearby events"
                    }
                    str("keyword")?.let { steps += "Looking for “$it”" }
                    if (str("start_date") != null || str("date_range") != null) steps += "Filtering to your dates"
                    str("category")?.let { steps += "Matching $it events" }
                }
                "search_venues" -> {
                    val loc = str("location")
                    steps += if (loc != null) "Finding places in $loc" else "Finding places nearby"
                    str("keyword")?.let { steps += "Looking for “$it”" }
                    str("category")?.let { steps += "Matching $it spots" }
                }
                "get_event_details" -> steps += "Pulling up the details"
                "recommend_and_save_venue" -> steps += "Lining up the spot"
            }
        }
        return steps.distinct().take(4)
    }

    // ════════════════════════════════════════════════════════════════
    // Browse fast-path (one-call latency optimization)
    //
    // For unambiguous "browse" turns ("Live music this week", "Things to
    // do tonight", "Comedy this weekend") the search params are knowable
    // from the user's text alone. The normal flow burns TWO sequential
    // Haiku calls — call #1 decides "I should search", call #2 shapes the
    // tray from the results. We cut call #1 entirely: run search_events
    // ourselves, prime the conversation with a synthetic tool exchange,
    // then force Bo straight to present_suggestions. Bo still curates,
    // groups, labels, and writes rationales (call #2 is preserved) — only
    // the redundant "decide to search" round disappears (~half the wait).
    //
    // Conservative by design: [inferBrowseSearchInput] returns null (→
    // normal LLM flow) for anything plan-shaped, venue-specific, or
    // factual. A miss just costs the old two-call path; a false fire would
    // at worst produce a suggestions tray, which is the right default for a
    // browse anyway.
    // ════════════════════════════════════════════════════════════════

    /**
     * Maps a browse-style user message to concrete search_events params, or
     * returns null when the turn isn't an unambiguous browse (plan-shaped,
     * venue-specific, or factual). Only fires when at least one concrete
     * signal (category or date range) is present.
     */
    private fun inferBrowseSearchInput(userText: String): JsonObject? {
        val lower = userText.lowercase().trim()
        if (lower.isBlank() || lower.length > 60) return null

        // Plan-shaped → let the LLM drive the multi-step build flow.
        val planSignals = listOf(
            "plan", "build", "itinerary", "trip", "vacation", "getaway",
            "date night", "put together", "schedule", "make a", "make me",
            "for us", "day trip", "weekend in", "weekend away", "around",
        )
        if (planSignals.any { lower.contains(it) }) return null
        // Venue-specific ("what's on at X") or factual openers aren't browses.
        if (lower.contains(" at ")) return null
        val factualStarts = listOf(
            "is ", "are ", "does ", "do ", "can ", "how ", "when ",
            "where ", "why ", "who ", "should ",
        )
        if (factualStarts.any { lower.startsWith(it) }) return null

        // Venue/place-discovery intent ("hottest restaurants", "best bars",
        // "good cafes", "where to eat") is NOT an event browse. The fast-path
        // only ever runs search_events, so priming food EVENTS for a restaurant
        // query yields a tray of non-matches AND force-terminates
        // present_suggestions on round 0 — before Bo can call search_venues or
        // escalate to web_search. That was the "empty card for 'Hottest
        // restaurants in Watertown'" bug. Bounce these to the normal LLM flow,
        // which calls search_venues, hits the empty-DB gate, then web search.
        // Substring match is intentionally loose: a false positive only costs
        // the old two-call path (the safe default), never correctness.
        // Any dining/food-and-drink ask (incl. bare "food" / "eat" / "brunch")
        // is venue discovery, not an event browse. Priming food EVENTS for these
        // both yields non-matches AND force-terminates present_suggestions on
        // round 0 — before Bo can search venues or escalate to web_search (which
        // a dining ask should always reach). Bounce them to the normal flow.
        if (isDiningIntent(userText)) return null

        // Category keyword → canonical category (the set search_events +
        // present_suggestions understand).
        val categoryMap = listOf(
            listOf("live music", "music", "concert", "concerts", "band", "bands", "gig") to "music",
            listOf("comedy", "standup", "stand-up", "stand up", "comedian") to "comedy",
            listOf("theatre", "theater", "play", "plays", "musical", "broadway") to "theatre",
            listOf("art ", "arts", "gallery", "galleries", "exhibit", "exhibition") to "arts",
            listOf("sports", "sport", "game", "games") to "sports",
            listOf("food", "eat", "dining", "restaurant", "restaurants", "foodie", "brunch") to "food",
            listOf("museum", "museums") to "museums",
            listOf("outdoor", "outdoors", "hike", "hiking", "nature") to "outdoors",
            listOf("kids", "kid", "family", "children", "for kids", "family-friendly") to "for-kids",
            listOf("tour", "tours") to "tours",
            listOf("library", "libraries", "story time", "storytime") to "libraries",
        )
        val category = categoryMap.firstOrNull { (keys, _) -> keys.any { lower.contains(it) } }?.second

        // Date phrase → canonical date_range. Check the more-specific
        // "next ..." forms before the bare token.
        val dateRange = when {
            lower.contains("next weekend") -> "next_weekend"
            lower.contains("next week") -> "next_week"
            lower.contains("this weekend") || lower.contains("the weekend") -> "this_weekend"
            lower.contains("this week") -> "this_week"
            lower.contains("this month") -> "this_month"
            lower.contains("tonight") || lower.contains("today") -> "today"
            lower == "weekend" -> "this_weekend"
            else -> null
        }

        if (category == null && dateRange == null) return null

        return kotlinx.serialization.json.buildJsonObject {
            if (category != null) put("category", JsonPrimitive(category))
            if (dateRange != null) put("date_range", JsonPrimitive(dateRange))
        }
    }

    /**
     * Pre-run search_events for a recognized browse turn and inject a
     * synthetic assistant(tool_use) + user(tool_result) pair into
     * [conversationHistory], so the upcoming single Claude call can go
     * straight to present_suggestions. Returns true when priming succeeded
     * (≥ 3 events) and the caller should force present_suggestions; false
     * when the search was thin — in which case nothing was added to history
     * and the normal LLM flow handles the "few results, widen?" path.
     */
    private suspend fun primeBrowseSearch(input: JsonObject): Boolean {
        val resultJson = executeToolCall("search_events", input)
        val eventCount = try {
            (Json.parseToJsonElement(resultJson).jsonObject["events"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
        } catch (_: Throwable) { 0 }
        if (eventCount < 3) {
            println("primeBrowseSearch: only $eventCount event(s) for $input — falling back to normal LLM flow")
            return false
        }

        val synthId = "toolu_preload_${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
        val assistantBlocks = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonObject {
                put("type", JsonPrimitive("tool_use"))
                put("id", JsonPrimitive(synthId))
                put("name", JsonPrimitive("search_events"))
                put("input", input)
            })
        }
        val toolResultBlocks = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonObject {
                put("type", JsonPrimitive("tool_result"))
                put("tool_use_id", JsonPrimitive(synthId))
                put("content", JsonPrimitive(resultJson))
            })
        }
        conversationHistory.add(ClaudeMessage.blocks("assistant", assistantBlocks))
        conversationHistory.add(ClaudeMessage.blocks("user", toolResultBlocks))

        // Surface the same searching narration the in-loop path would have.
        val narration = narrateToolCalls(
            listOf(live.localapp.local.data.remote.ToolCall(synthId, "search_events", input)),
        )
        if (narration.isNotEmpty()) {
            _uiState.update { it.copy(isSearching = true, searchingNarration = narration.distinct().takeLast(6)) }
        }
        println("primeBrowseSearch: primed $eventCount events for $input — forcing present_suggestions (one-call path)")
        return true
    }

    private suspend fun sendWithToolLoop(
        systemPrompt: String,
        forcePresentSuggestions: Boolean = false,
        buildFromWeb: Boolean = false,
    ): ParsedChatResponse {
        val maxToolRounds = 5
        var toolRounds = 0
        // One-shot retry guard for the empty-response path. Anthropic occasionally
        // returns end_turn with no text content after very short user messages
        // (a single digit, "yes", "no"). Letting that surface as "trouble
        // generating" made Bo look broken; instead we retry once with a
        // continuation nudge before showing the user anything.
        var emptyResponseRetried = false
        // One-shot retry guard for a truncated SSE stream. A flaky network can
        // close the socket mid-response (no terminating message_stop); the
        // streaming layer flags that with stopReason == STOP_REASON_INTERRUPTED.
        // Without this, Bo commits the partial text as a finished reply — the
        // "described one event then trailed off" bug. We re-send the identical
        // request once to let the full answer come through.
        var truncationRetried = false
        // Track whether ANY search_events call this turn returned events. If we
        // hit the tool-round cap with this still false, every DB search came
        // back empty (or timed out) — we want the forcing message to push Bo
        // into the EMPTY DB FALLBACK flow rather than the default "present a
        // plan" nudge, which previously caused Bo to hallucinate references
        // to earlier turns' suggestion trays ("Which of these sounds most
        // appealing..." with nothing to choose from).
        var turnHadAnyEvents = false
        // Whether at least one search_events call has actually executed this
        // turn. Web search is HARD-GATED on (searchEventsRan && !turnHadAnyEvents)
        // — i.e. we only even OFFER the web_search tool once the DB has been
        // queried AND came back empty. Prompt rules alone are soft; gating the
        // tool out of the request makes "no web_search unless DB came up 0"
        // enforceable, not just requested. This kills the latency tail where
        // Bo reached for the 5-15s server-side web_search to "enrich" results
        // the DB already had.
        var searchEventsRan = false
        // Venue-side mirror of the two trackers above. A discovery query like
        // "best restaurants in Watertown" drives search_VENUES, not
        // search_events — so without these, an empty venue search could never
        // unlock web_search (the gate keyed on search_events only, leaving a
        // venue-shaped blind spot where Bo just emitted "no matches" chips).
        // searchVenues returns plain text: empty = "No venues found...", and
        // each hit is a line starting with "[<id>]". The infra-timeout marker
        // is JSON ({"error":...}) so it never starts with "[" — a timed-out
        // round correctly counts as "no venues", not a false hit.
        var searchVenuesRan = false
        var turnHadAnyVenues = false
        // Circuit breaker. Counts CONSECUTIVE tool rounds in which every data
        // tool came back with the infra-timeout marker (network unreachable).
        // Without this, a total Supabase outage made Bo grind all 5 rounds ×
        // the full socket timeout (~minutes) before the cap forced completion.
        // Two consecutive dead rounds is enough signal to stop and tell the
        // user the network is down rather than burn the rest of the budget.
        var consecutiveTimeoutRounds = 0
        // Start with Haiku for fast clarifying questions. Switch to Opus when tools are needed.
        var currentModel = live.localapp.local.data.remote.ClaudeApiService.MODEL_CHAT
        // Set once Bo has tried to call web_search/web_fetch but the round's
        // gate hadn't included the server-side tool (the phantom-call case).
        // Forces the server tool into every subsequent round's tool list so
        // Bo's retry actually runs server-side instead of dead-ending again.
        var forceWebTools = false

        // Phase 1: Bo's web search/fetch gate. Per-turn check against:
        //  - Live-state rule (user must be in a state with is_live=true)
        //  - Per-user 30/day + 10/hour caps
        //  - Global $500/month cap
        // The system prompt is augmented with the gate verdict so Bo delivers
        // the right user-facing message when blocked.
        val budget = fetchBoWebBudget()
        // Per-round tool list. web_search is added ONLY once the DB has been
        // searched and returned nothing (see [toolsForRound]); web_fetch stays
        // available per budget for the user-pastes-a-URL case (not a latency
        // concern). On the common path (DB has results), web_search never
        // enters the list, so the tools array — and its cache breakpoint —
        // stays identical across rounds and the tool cache stays warm.
        fun toolsForRound(): JsonArray {
            // Unlock web_search once ANY DB discovery search (events OR venues)
            // has run this turn and come back empty. Broadened from events-only
            // so restaurant/venue queries ("hottest restaurants in Watertown")
            // can reach the empty-DB web fallback instead of dead-ending on chips.
            val dbCameUpEmpty =
                (searchEventsRan || searchVenuesRan) && !turnHadAnyEvents && !turnHadAnyVenues
            // Second unlock: pure-information turns (the INFORMATION VS EVENTS
            // carve-out). The user asked for facts about a place/venue, not a
            // list of events, so the DB-first gate doesn't apply — make
            // web_search available from the start. Still budget-gated below; the
            // prompt decides whether to actually call it.
            val infoIntent = isInfoOnlyIntent(lastUserMessage)
            // Third unlock: dining/food-and-drink discovery. Our DB may carry
            // some restaurants, but it will never have more than the open web,
            // so a "good restaurants nearby" ask should reach web_search from
            // the start — even when the local DB returned venue hits (which the
            // dbCameUpEmpty gate would otherwise suppress).
            val diningIntent = isDiningIntent(lastUserMessage)
            // Fourth unlock: Bo already reached for web_search this turn but the
            // tool wasn't in the round (phantom call). Force it in now so the
            // retry runs server-side. Still budget-gated — forceWebTools is only
            // set when budget.allowWebSearch was true.
            return toolsWithWeb(
                base = claudeTools,
                allowWebSearch = budget?.allowWebSearch == true && (dbCameUpEmpty || infoIntent || diningIntent || forceWebTools),
                allowWebFetch = budget?.allowWebFetch == true,
                includeWebFinding = buildFromWeb,
            )
        }
        val augmentedSystemPrompt = augmentSystemPromptWithBudget(systemPrompt, budget)
        val webCallTally = mutableListOf<Triple<String, String?, Int?>>()  // tool, query, result_count

        try {
        while (true) {
            // Don't stream text deltas to the UI inside the tool loop. Bo's
            // search-first behavior means almost every turn calls a tool, and
            // any text Bo emits before/alongside the tool_use is preamble
            // narration we want to suppress anyway. Streaming would show the
            // text mid-stream and then yank it when ToolUseStart fires —
            // the "Bo's response started streaming and then disappeared"
            // glitch. The searching idiom + final response bubble give the
            // user enough feedback. The maxToolRounds final-completion
            // branch (below) explicitly streams the final response when it's
            // a pure text reply.
            // tool_choice forcing: on the FIRST round, Bo is free to ask a
            // clarifying question (no tool call) — that's fine. From round 2
            // onward, after at least one tool has executed, force any tool
            // call. This eliminates the "Bo narrates prose with **bold
            // venue names** instead of calling present_suggestions" failure
            // mode at the API layer. Bo must either call another data tool
            // or a terminal tool (present_plan / present_suggestions /
            // present_event_brief / etc.) — prose is unreachable.
            // When the browse fast-path pre-ran the search and primed history,
            // force present_suggestions on round 0 so Bo emits the tray in a
            // single call (no "decide to search" round). Otherwise: free on
            // round 0 (Bo may ask a clarifying question), force any tool after.
            val choice: kotlinx.serialization.json.JsonObject? = when {
                toolRounds == 0 && forcePresentSuggestions ->
                    kotlinx.serialization.json.buildJsonObject {
                        put("type", JsonPrimitive("tool"))
                        put("name", JsonPrimitive("present_suggestions"))
                    }
                toolRounds == 0 -> null
                else -> kotlinx.serialization.json.buildJsonObject { put("type", JsonPrimitive("any")) }
            }
            val roundStartMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
            val response = sendStreaming(augmentedSystemPrompt, toolsForRound(), isToolRound = true, model = currentModel, toolChoice = choice)
            println("⏱️ bo-latency: Claude round $toolRounds (model=$currentModel choice=${choice?.get("name") ?: choice?.get("type") ?: "free"}) ${kotlin.time.Clock.System.now().toEpochMilliseconds() - roundStartMs}ms")
            tallyWebCalls(response, webCallTally)

            val toolCalls = claudeApi.extractToolCalls(response)
            val text = claudeApi.extractText(response) ?: ""

            // Truncation recovery: the stream closed before its terminating
            // message_stop (dropped socket / network timeout). Any text is a
            // partial thought and any tool JSON may be incomplete — committing
            // it produces the "Bo described one event then trailed off" bug.
            // Re-send the identical request once (nothing was appended to
            // history this round, so the next iteration retries cleanly). If it
            // truncates again, fall through and degrade gracefully.
            if (response.stopReason == live.localapp.local.data.remote.ClaudeApiService.STOP_REASON_INTERRUPTED &&
                !truncationRetried
            ) {
                println("ChatVM: stream truncated (no message_stop) — retrying round once")
                truncationRetried = true
                continue
            }

            // Surface what Bo is about to do as human-readable narration,
            // derived from the data-tool args (not Claude prose — the
            // NO-PREAMBLE rule still suppresses prose). The searching
            // indicator shows these steps instead of the generic gerunds.
            val narrationSteps = narrateToolCalls(toolCalls.filter { it.name !in terminalTools })
            if (narrationSteps.isNotEmpty()) {
                _uiState.update { st ->
                    st.copy(
                        isSearching = true,
                        // Accumulate steps across rounds so they stack up and
                        // persist; the gerund typewriter stays the live line.
                        searchingNarration = (st.searchingNarration + narrationSteps).distinct().takeLast(6),
                    )
                }
            }

            // Check for terminal tools (present_plan / update_plan)
            val terminalCall = toolCalls.firstOrNull { it.name in terminalTools }
            if (terminalCall != null) {
                // Execute any non-terminal tools first (shouldn't happen often, but just in case)
                val searchCalls = toolCalls.filter { it.name !in terminalTools }
                if (searchCalls.isNotEmpty()) {
                    val assistantBlocks = claudeApi.buildAssistantContentBlocks(response)
                    conversationHistory.add(ClaudeMessage.blocks("assistant", assistantBlocks))
                    val searchResults: List<String> = coroutineScope {
                        searchCalls.map { call -> async { executeToolCall(call.name, call.input) } }.awaitAll()
                    }
                    val toolResultBlocks = kotlinx.serialization.json.buildJsonArray {
                        searchCalls.forEachIndexed { i, call ->
                            add(kotlinx.serialization.json.buildJsonObject {
                                put("type", JsonPrimitive("tool_result"))
                                put("tool_use_id", JsonPrimitive(call.id))
                                put("content", JsonPrimitive(searchResults[i]))
                            })
                        }
                        // Send a synthetic result for the terminal tool so history stays valid
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("type", JsonPrimitive("tool_result"))
                            put("tool_use_id", JsonPrimitive(terminalCall.id))
                            put("content", JsonPrimitive("Plan displayed to user."))
                        })
                    }
                    conversationHistory.add(ClaudeMessage.blocks("user", toolResultBlocks))
                } else {
                    // Just the terminal tool — add assistant response + synthetic result to history
                    val assistantBlocks = claudeApi.buildAssistantContentBlocks(response)
                    conversationHistory.add(ClaudeMessage.blocks("assistant", assistantBlocks))
                    val toolResultBlocks = kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("type", JsonPrimitive("tool_result"))
                            put("tool_use_id", JsonPrimitive(terminalCall.id))
                            put("content", JsonPrimitive("Plan displayed to user."))
                        })
                    }
                    conversationHistory.add(ClaudeMessage.blocks("user", toolResultBlocks))
                }

                println("sendWithToolLoop: Terminal tool ${terminalCall.name}, text=${text.take(200)}")
                return parsePlanToolCall(terminalCall, text)
            }

            // No tool calls at all — pure text response
            if (toolCalls.isEmpty() || response.stopReason == "end_turn") {
                stripToolMessagesFromHistory()
                if (text.isNotEmpty()) appendTextToHistory("assistant", text)

                val isApproval = _uiState.value.days.isNotEmpty() && lastUserMessage.lowercase().let { msg ->
                    msg.contains("looks good") || msg.contains("approve") ||
                        msg.contains("save") || msg.contains("add to my plans")
                }

                // Narrated-plan miss: Bo described a plan in prose instead
                // of calling present_plan. Surface a "Build this as a plan"
                // chip the user can tap — that sends an explicit "build it"
                // message which forces Bo to emit present_plan on the next
                // turn. Beats the silent retry that doubled latency.
                val showBuildChip = !isApproval &&
                    looksLikeNarratedPlan(text) &&
                    searchResultCache.isNotEmpty()
                // Narrated-suggestions miss: Bo wrote a prose list of
                // options ("**Brighton Music Hall** on Friday…") instead of
                // calling present_suggestions. Same recovery pattern — chip
                // forces Bo to re-emit as a tappable tray on the next turn.
                val showTrayChip = !isApproval && !showBuildChip &&
                    looksLikeNarratedSuggestions(text, searchResultCache.size) &&
                    searchResultCache.isNotEmpty()
                val chips: List<String> = when {
                    showBuildChip -> listOf("Build this as a plan")
                    showTrayChip -> listOf("Show as tappable list")
                    else -> emptyList()
                }

                // Empty-response retry (bug fix): Bo sometimes returns no text
                // after a short user message like a number ("4" answering
                // "How many people?"). Stop reason = end_turn, no tool calls,
                // empty content. Previously this surfaced "Sorry, I had
                // trouble generating a response" which made Bo look broken
                // when really it was a transient empty response. Retry ONCE
                // with a tiny continuation nudge appended to history before
                // showing the user-facing failure message.
                if (text.isEmpty() && !emptyResponseRetried) {
                    println("ChatVM: empty assistant text — retrying once with continuation nudge")
                    emptyResponseRetried = true
                    conversationHistory.add(
                        ClaudeMessage.text(
                            "user",
                            "(continue — your last response was empty. Acknowledge the user's last message and proceed.)"
                        )
                    )
                    continue
                }

                val finalText = text.ifEmpty {
                    // Post-retry empty: graceful actionable message instead
                    // of "trouble generating", which always reads like Bo
                    // crashed.
                    "Got it. What would you like to do next?"
                }
                return ParsedChatResponse(
                    type = if (isApproval) ParsedResponseType.APPROVE else ParsedResponseType.CHAT,
                    text = finalText,
                    chips = chips,
                )
            }

            // Show searching indicator on first tool call round and switch to Opus
            if (toolRounds == 0) {
                _uiState.update { it.copy(isSearching = true, streamingText = "") }
                currentModel = live.localapp.local.data.remote.ClaudeApiService.MODEL_PLAN
                println("ChatVM: switching to MODEL_PLAN for tool use")
            }

            // Check tool round cap
            toolRounds++
            if (toolRounds >= maxToolRounds) {
                println("Tool loop capped at $maxToolRounds rounds — forcing completion")
                val assistantBlocks = claudeApi.buildAssistantContentBlocks(response)
                conversationHistory.add(ClaudeMessage.blocks("assistant", assistantBlocks))
                val anchor = dateAnchorText()
                val finalRoundResults: List<String> = coroutineScope {
                    toolCalls.map { call -> async { executeToolCall(call.name, call.input) } }.awaitAll()
                }
                // Also fold the final round's results into the per-turn
                // events-found tracker — Bo may have finally gotten a hit in
                // the last allowed round.
                finalRoundResults.forEachIndexed { i, r ->
                    if (toolCalls[i].name == "search_events") {
                        searchEventsRan = true
                        if (r.contains("\"events\":[{")) turnHadAnyEvents = true
                    }
                    if (toolCalls[i].name == "search_venues") {
                        searchVenuesRan = true
                        if (r.trimStart().startsWith("[")) turnHadAnyVenues = true
                    }
                }
                // At the round cap we can't grant another web round — if Bo
                // emitted a phantom web_search here, tell it plainly to finish
                // without one rather than echoing the internal marker.
                val finalResultContents = finalRoundResults.map { r ->
                    if (r == TOOL_WEB_PHANTOM_MARKER)
                        """{"status":"web_search_unavailable","note":"No more web searches available this turn. Answer with what you have now."}"""
                    else r
                }
                val toolResultBlocks = kotlinx.serialization.json.buildJsonArray {
                    toolCalls.forEachIndexed { i, call ->
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("type", JsonPrimitive("tool_result"))
                            put("tool_use_id", JsonPrimitive(call.id))
                            put("content", JsonPrimitive(anchor + finalResultContents[i]))
                        })
                    }
                }
                conversationHistory.add(ClaudeMessage.blocks("user", toolResultBlocks))
                // Conditional forcing message. The default "present a plan or
                // respond naturally" nudge is fine when Bo has events to
                // present, but when every search_events call this turn
                // returned 0 (e.g. backfill-induced timeouts, genuinely quiet
                // calendar), the prior wording let Bo hallucinate references
                // to earlier turns' trays. Push it into the EMPTY DB
                // FALLBACK flow instead.
                val forcingMessage = if (buildFromWeb) {
                    // Build-from-web never runs search_events, so turnHadAnyEvents
                    // is false here — but the empty-DB fallback is wrong for this
                    // path. Push Bo to stage any remaining findings and emit the
                    // plan, not a groups=[] suggestion tray.
                    "Build the plan now. For each web finding, call save_web_finding(kind='event') if you haven't already, then call present_plan with the returned bo_web_events ids as the slot event_ids and is_bo_web: true. Do NOT call present_suggestions — the user already asked you to build the plan."
                } else if (turnHadAnyEvents) {
                    "You have enough information now. Please present a plan using the present_plan tool, or just respond naturally."
                } else {
                    "Every search_events call this turn returned 0 events. Do NOT reference earlier turns' suggestion trays or plans — the user may have changed location, dates, or intent since. Follow the EMPTY DB FALLBACK flow from the system prompt: if web_search is AVAILABLE, run ONE web_search query for the user's location + date + intent, then call present_suggestions with groups=[], a one-line headline acknowledging the empty DB plus a recap of any web findings, and footer.actions = [\"build_from_web\", \"search_wider\", \"different_day\"] when web has usable findings, or [\"search_wider\", \"different_day\"] otherwise. Do NOT respond with plain CHAT prose like \"Want me to try a different date range?\" — chips are tappable, prose forces the user to type."
                }
                conversationHistory.add(ClaudeMessage.text("user", forcingMessage))

                // Final response — stream with text visible. One-shot truncation
                // retry: this branch runs once (we're past the round loop), so a
                // dropped socket here would otherwise commit Bo's partial thought
                // as the finished reply — the same "trailed off mid-sentence" bug
                // the in-loop guard at the top of this function prevents. Re-send
                // identically once; nothing was appended to history yet.
                var finalResponse = sendStreaming(augmentedSystemPrompt, toolsForRound(), isToolRound = false, model = currentModel)
                if (finalResponse.stopReason == live.localapp.local.data.remote.ClaudeApiService.STOP_REASON_INTERRUPTED) {
                    println("ChatVM: final-response stream truncated — retrying once")
                    finalResponse = sendStreaming(augmentedSystemPrompt, toolsForRound(), isToolRound = false, model = currentModel)
                }
                tallyWebCalls(finalResponse, webCallTally)
                val finalText = claudeApi.extractText(finalResponse) ?: ""
                val finalTerminal = claudeApi.extractToolCalls(finalResponse).firstOrNull { it.name in terminalTools }

                if (finalTerminal != null) {
                    val ab = claudeApi.buildAssistantContentBlocks(finalResponse)
                    conversationHistory.add(ClaudeMessage.blocks("assistant", ab))
                    val tr = kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("type", JsonPrimitive("tool_result"))
                            put("tool_use_id", JsonPrimitive(finalTerminal.id))
                            put("content", JsonPrimitive("Plan displayed to user."))
                        })
                    }
                    conversationHistory.add(ClaudeMessage.blocks("user", tr))
                    return parsePlanToolCall(finalTerminal, finalText)
                }

                stripToolMessagesFromHistory()
                if (finalText.isNotEmpty()) appendTextToHistory("assistant", finalText)
                return ParsedChatResponse(
                    type = ParsedResponseType.CHAT,
                    text = finalText.ifEmpty { "I found some great options but had trouble putting them together. Could you try asking again?" },
                )
            }

            // Regular tool execution — add response to history and continue loop.
            // Parallel execution: when Bo emits multiple tool_use blocks in a
            // single response (e.g. a batch of recommend_and_save_venue calls
            // for plan-build), run them concurrently. Tool results are then
            // assembled in the SAME order Bo emitted them, matching tool_use_id
            // expectations on the next round-trip.
            val assistantBlocks = claudeApi.buildAssistantContentBlocks(response)
            conversationHistory.add(ClaudeMessage.blocks("assistant", assistantBlocks))

            val anchor = dateAnchorText()
            toolCalls.forEach { call ->
                println("Executing tool: ${call.name} with input: ${call.input}")
            }
            val results: List<String> = coroutineScope {
                toolCalls.map { call ->
                    async { executeToolCall(call.name, call.input) }
                }.awaitAll()
            }
            results.forEachIndexed { i, r ->
                println("Tool result (${toolCalls[i].name}): ${r.take(200)}")
            }
            // Track non-empty DB hits across the turn. Empty results render
            // as `{"events":[]...}`; non-empty as `{"events":[{...`. Cheap
            // string check — avoids re-parsing each tool result.
            results.forEachIndexed { i, r ->
                if (toolCalls[i].name == "search_events") {
                    searchEventsRan = true
                    if (r.contains("\"events\":[{")) turnHadAnyEvents = true
                }
                if (toolCalls[i].name == "search_venues") {
                    searchVenuesRan = true
                    if (r.trimStart().startsWith("[")) turnHadAnyVenues = true
                }
            }

            // Phantom web-tool recovery. Bo (Haiku) sometimes emits a CLIENT
            // tool_use for web_search/web_fetch when the prompt pushes a search
            // but the round's gate didn't include the server-side tool. Left as
            // an "Unknown tool" error, Bo abandons the web and answers from DB
            // scraps — the "web result cut off" bug. Instead: if the user's
            // web budget allows, force the server tool into the next round and
            // hand Bo a tool_result telling it to retry (which then runs
            // server-side). If budget is exhausted/region-blocked, tell Bo
            // plainly so it stops retrying and answers with what it has.
            val resultContents = results.mapIndexed { i, r ->
                if (r != TOOL_WEB_PHANTOM_MARKER) return@mapIndexed r
                if (budget?.allowWebSearch == true) {
                    forceWebTools = true
                    """{"status":"web_search_enabled","note":"Web search is now available this turn. Call web_search again with your query to get live results before answering."}"""
                } else {
                    """{"status":"web_search_unavailable","note":"Web search isn't available right now. Answer using your existing knowledge and the local database results, or ask the user to refine — do not call web_search again."}"""
                }
            }

            // Circuit breaker: did this round's data tools ALL time out?
            // (Terminal/UI tools don't count — only data fetches that hit the
            // network.) If so, the backend is unreachable; bumping the counter.
            // A round with even one real result resets it — a single transient
            // timeout shouldn't trip the breaker.
            val dataResults = results.filterIndexed { i, _ -> toolCalls[i].name !in terminalTools }
            val roundAllTimedOut = dataResults.isNotEmpty() &&
                dataResults.all { it == TOOL_INFRA_TIMEOUT_MARKER }
            if (roundAllTimedOut) {
                consecutiveTimeoutRounds++
                println("Circuit breaker: round timed out (consecutive=$consecutiveTimeoutRounds)")
            } else {
                consecutiveTimeoutRounds = 0
            }
            if (consecutiveTimeoutRounds >= 2) {
                println("Circuit breaker TRIPPED — backend unreachable, bailing early")
                stripToolMessagesFromHistory()
                val msg = "I'm having trouble reaching the network right now, so I can't pull up events. Give it a moment and try again — a quick retry usually does it."
                appendTextToHistory("assistant", msg)
                return ParsedChatResponse(type = ParsedResponseType.CHAT, text = msg)
            }

            val toolResultBlocks = kotlinx.serialization.json.buildJsonArray {
                toolCalls.forEachIndexed { i, call ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("type", JsonPrimitive("tool_result"))
                        put("tool_use_id", JsonPrimitive(call.id))
                        put("content", JsonPrimitive(anchor + resultContents[i]))
                    })
                }
            }
            conversationHistory.add(ClaudeMessage.blocks("user", toolResultBlocks))
        }
        } finally {
            // Fire-and-forget the per-turn usage log. Anthropic doesn't return
            // cost; we estimate from result counts and reconcile monthly.
            if (webCallTally.isNotEmpty()) {
                viewModelScope.launch { logBoWebCalls(webCallTally.toList()) }
            }
        }
    }

    /**
     * Short date anchor re-stated in every tool_result so Claude doesn't drift
     * across tool rounds. Prepends to each tool result content.
     * Gives today's date + explicit mappings for "this [weekday]" and "next [weekday]".
     */
    private fun dateAnchorText(): String {
        return try {
            val now = kotlinx.datetime.Instant.fromEpochMilliseconds(kotlin.time.Clock.System.now().toEpochMilliseconds())
            val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
            val today = now.toLocalDateTime(tz).date
            val dowFull = arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
            val monthFull = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

            val todayDow = today.dayOfWeek.ordinal
            val todayLabel = "${dowFull[todayDow]}, ${monthFull[today.monthNumber - 1]} ${today.dayOfMonth}"

            // For each weekday, compute "this [day]" (nearest upcoming) and "next [day]" (week after)
            val baseEpoch = today.toEpochDays()
            val thisWeek = StringBuilder()
            val nextWeek = StringBuilder()
            for (i in 0..6) {
                val thisDate = kotlinx.datetime.LocalDate.fromEpochDays(baseEpoch + ((i - todayDow + 7) % 7))
                val nextDate = kotlinx.datetime.LocalDate.fromEpochDays(thisDate.toEpochDays() + 7)
                val dowName = dowFull[i]
                thisWeek.append("$dowName=${monthFull[thisDate.monthNumber - 1]} ${thisDate.dayOfMonth}; ")
                nextWeek.append("$dowName=${monthFull[nextDate.monthNumber - 1]} ${nextDate.dayOfMonth}; ")
            }

            "[Today: $todayLabel. This week: $thisWeek. Next week: $nextWeek. Always verify any date you state against this.]\n"
        } catch (_: Throwable) { "" }
    }

    /**
     * Strip tool_use and tool_result messages from conversation history.
     * These are one-time context and bloat the history for future turns.
     */
    private fun stripToolMessagesFromHistory() {
        conversationHistory.removeAll { msg ->
            // Remove messages whose content is a JsonArray containing tool_use or tool_result blocks
            val content = msg.content
            if (content is kotlinx.serialization.json.JsonArray) {
                content.any { element ->
                    val type = element.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                    type == "tool_use" || type == "tool_result"
                }
            } else false
        }
    }

    /**
     * Walk conversationHistory and ensure every assistant `tool_use` block has
     * a corresponding `tool_result` block in the immediately-following user
     * message. If a tool_use is dangling (no matching id in the next message),
     * inject a synthetic placeholder tool_result so the Anthropic API doesn't
     * reject the request with `messages.N: tool_use ids were found without
     * tool_result blocks immediately after`.
     *
     * This is defense-in-depth — the tool loop in [sendWithToolLoop] already
     * pairs them by construction, but exception paths or future refactors can
     * leave dangling state. Better to ship a working chat than a 400.
     */
    private fun sanitizeToolUsePairing() {
        // Reverse-direction guard. The forward pass below ensures every
        // assistant `tool_use` has a following `tool_result`. But the messages
        // array must ALSO not BEGIN with an orphaned `tool_result`: Anthropic
        // 400s with "tool_result block(s) provided when previous message does
        // not contain any tool_use blocks", and the first message must be role
        // `user`. trimHistory() keeps a blind takeLast(20), which in a long
        // tool-heavy session can slice the history so the first surviving
        // message is a `user` tool_result (its matching assistant tool_use was
        // dropped) or a leading `assistant` turn. That's a deterministic 400
        // that fails every retry and surfaces as "something went wrong" on a
        // simple chip tap. Drop leading messages until the first one is a safe
        // conversation start: a `user` message with no tool_result blocks.
        while (conversationHistory.isNotEmpty()) {
            val first = conversationHistory.first()
            val firstContent = first.content
            val startsWithToolResult = firstContent is kotlinx.serialization.json.JsonArray &&
                firstContent.any { el ->
                    (el as? kotlinx.serialization.json.JsonObject)
                        ?.get("type")?.jsonPrimitive?.contentOrNull == "tool_result"
                }
            if (first.role == "assistant" || startsWithToolResult) {
                println("sanitizeToolUsePairing: dropping leading orphan message (role=${first.role}, toolResult=$startsWithToolResult)")
                conversationHistory.removeAt(0)
            } else break
        }

        var i = 0
        while (i < conversationHistory.size) {
            val msg = conversationHistory[i]
            val content = msg.content
            if (msg.role != "assistant" || content !is kotlinx.serialization.json.JsonArray) {
                i++; continue
            }
            val toolUseIds = content.mapNotNull { el ->
                if (el.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                    el.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                } else null
            }
            if (toolUseIds.isEmpty()) { i++; continue }

            // Look at the next message — must be user with matching tool_results.
            val nextIdx = i + 1
            val nextMsg = conversationHistory.getOrNull(nextIdx)
            val nextContent = nextMsg?.content
            val resultIds: Set<String> = if (
                nextMsg != null && nextMsg.role == "user" &&
                nextContent is kotlinx.serialization.json.JsonArray
            ) {
                nextContent.mapNotNull { el ->
                    if (el.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_result") {
                        el.jsonObject["tool_use_id"]?.jsonPrimitive?.contentOrNull
                    } else null
                }.toSet()
            } else emptySet()

            val missing = toolUseIds.filter { it !in resultIds }
            if (missing.isEmpty()) { i++; continue }

            println("sanitizeToolUsePairing: synthesizing ${missing.size} placeholder tool_result(s) for dangling tool_use ids: $missing")
            val syntheticBlocks = kotlinx.serialization.json.buildJsonArray {
                missing.forEach { id ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("type", JsonPrimitive("tool_result"))
                        put("tool_use_id", JsonPrimitive(id))
                        put("content", JsonPrimitive("(no result — tool execution was interrupted)"))
                    })
                }
            }
            if (
                nextMsg != null && nextMsg.role == "user" &&
                nextContent is kotlinx.serialization.json.JsonArray
            ) {
                // Merge synthetic results into the existing user-blocks message.
                val merged = kotlinx.serialization.json.buildJsonArray {
                    nextContent.forEach { add(it) }
                    syntheticBlocks.forEach { add(it) }
                }
                conversationHistory[nextIdx] = ClaudeMessage.blocks("user", merged)
            } else {
                // Insert a fresh user-blocks message right after the assistant.
                conversationHistory.add(nextIdx, ClaudeMessage.blocks("user", syntheticBlocks))
            }
            i++
        }
    }

    // ── Tool Execution ──

    private suspend fun executeToolCall(name: String, input: JsonObject): String {
        repeat(2) { attempt ->
            try {
                val result = when (name) {
                    "search_events" -> searchEvents(input)
                    "search_venues" -> searchVenues(input)
                    "get_event_details" -> getEventDetails(input)
                    "recommend_and_save_venue" -> recommendAndSaveVenue(input)
                    "save_web_finding" -> saveWebFinding(input)
                    // web_search / web_fetch are Anthropic server-side tools — they
                    // should never reach here as a client tool_use. When they do,
                    // the round's gate omitted the server tool while the prompt
                    // still urged a search; flag it as a phantom so the loop can
                    // re-enable the real tool and let Bo retry (see sendWithToolLoop).
                    "web_search", "web_fetch" -> return TOOL_WEB_PHANTOM_MARKER
                    else -> return """{"error": "Unknown tool: $name"}"""
                }
                return result
            } catch (e: live.localapp.local.data.repository.SearchInfraTimeoutException) {
                // Infra is unreachable — don't burn a second attempt (another
                // full socket timeout). Return a distinct marker the tool loop
                // counts toward its circuit breaker so Bo bails fast.
                println("Tool execution TIMEOUT ($name): ${e.message} — not retrying")
                return TOOL_INFRA_TIMEOUT_MARKER
            } catch (e: Exception) {
                println("Tool execution error ($name, attempt ${attempt + 1}): ${e.message}")
                if (attempt == 0) kotlinx.coroutines.delay(1000)
            }
        }
        return """{"error": "Failed to execute $name after retries"}"""
    }

    private suspend fun searchEvents(input: JsonObject): String {
        // The chat's location chip (state.currentLocationName + radius miles)
        // is the canonical search scope — the chip's RADIUS is what defines
        // the scope, not an additional venue_city filter. When the user sets
        // "Mashpee · 32mi" they want anywhere within 32mi of Mashpee, NOT
        // just events whose venue_city is literally "Mashpee" (which would
        // exclude every event in Hyannis / Falmouth / Provincetown / etc.
        // even though those are squarely inside the 32mi radius).
        //
        // So: location is used as the bbox CENTER; the radius is the scope;
        // strict_location is OFF by default. Bo can still pass strict=true
        // when the user explicitly said "only in {place}" / "just {place}".
        val chipLocation = _uiState.value.currentLocationName.takeIf { it.isNotBlank() }
        val explicitLocation = input["location"]?.jsonPrimitive?.contentOrNull
        val location = explicitLocation ?: chipLocation
        val strictLocation = input["strict_location"]?.jsonPrimitive?.booleanOrNull ?: false
        val category = input["category"]?.jsonPrimitive?.contentOrNull
        var dateRange = input["date_range"]?.jsonPrimitive?.contentOrNull
        val keyword = input["keyword"]?.jsonPrimitive?.contentOrNull
        val limit = input["limit"]?.jsonPrimitive?.intOrNull ?: 10000
        val startDate = input["start_date"]?.jsonPrimitive?.contentOrNull
        val endDate = input["end_date"]?.jsonPrimitive?.contentOrNull

        // Remember the last keyword so the inline "Find more" handler can
        // re-apply it to load-more searches on day-grouped trays. Don't
        // overwrite with null — a follow-up search without a keyword (e.g.
        // Bo broadening) shouldn't erase the original filter.
        if (!keyword.isNullOrBlank()) lastSearchKeyword = keyword
        // The effective single-topic query (keyword OR category) + date window,
        // for the category-complete supplemental search in the expansion.
        (keyword ?: category)?.takeIf { it.isNotBlank() }?.let { lastBrowseQuery = it }
        lastSearchDateRange = dateRange
        lastSearchStartDate = startDate
        lastSearchEndDate = endDate

        // Remember the location + strictness too, so "Find more" re-applies the
        // SAME city constraint. A strict "in Watertown" search must not have its
        // load-more leak in broad-radius Boston events.
        lastSearchLocation = location
        lastSearchStrictLocation = strictLocation

        // Phase 2: dropped searchEmptyMisses short-circuit. The cache was
        // keyed by (location, dateRange, startDate, endDate) WITHOUT
        // strict_location, so a strict-true search that returned zero
        // poisoned a subsequent strict-false retry for the same window. With
        // tool_choice: any forcing Bo to call tools, the right answer is to
        // run the cheap RPC again (sub-second) and let the result speak for
        // itself, not lie via stale cache.

        // If specific dates provided, use custom date range instead of keyword
        if (startDate != null && dateRange == null) {
            dateRange = "custom"
        }

        // Resolve coords for the search center.
        //
        // Priority:
        //  1. If `location` came from the chip (no explicit override from Bo)
        //     AND state already has valid coords, use those. The chip's
        //     coords were geocoded by iOS's CLGeocoder when the user set it,
        //     so they're accurate even for places the in-memory geocodeCity
        //     doesn't know ("Concord, MA", "South End", arbitrary US towns).
        //  2. If Bo passed an explicit location, geocode it through the
        //     in-memory list. If that fails, return UNRESOLVED_LOCATION so
        //     Bo can correct course — never silently fall back to user
        //     coords, which would surface results from the wrong region.
        //  3. If no location at all, use state coords (current location).
        val state = _uiState.value
        val locationRequested = !location.isNullOrBlank()
        val locationCameFromChip = explicitLocation.isNullOrBlank() && !chipLocation.isNullOrBlank()
        val locationCoords: Pair<Double, Double>? = when {
            // Chip path: trust state coords (set by iOS CLGeocoder).
            locationCameFromChip && state.currentLat != null && state.currentLng != null -> {
                state.currentLat!! to state.currentLng!!
            }
            locationRequested -> geocodeCity(location!!)
            else -> null
        }
        if (locationRequested && !locationCameFromChip && locationCoords == null) {
            println("searchEvents: Bo-passed location='$location' did not geocode")
            return kotlinx.serialization.json.buildJsonObject {
                put("error", JsonPrimitive("unresolved_location"))
                put("location", JsonPrimitive(location ?: ""))
                put("guidance", JsonPrimitive(
                    "I couldn't geocode '$location'. Ask the user to clarify the city, or try a nearby town. Do NOT silently search elsewhere — that would surface results from the wrong region."
                ))
                put("events", kotlinx.serialization.json.buildJsonArray { })
            }.toString()
        }
        val prefs = state.userPreferences
        val prefsEffectiveLat = prefs?.let { if (it.locationMode == "manual") it.manualLat ?: it.userLat else it.userLat }
        val prefsEffectiveLng = prefs?.let { if (it.locationMode == "manual") it.manualLng ?: it.userLng else it.userLng }
        val userLat = locationCoords?.first ?: state.currentLat ?: prefsEffectiveLat
        val userLng = locationCoords?.second ?: state.currentLng ?: prefsEffectiveLng
        println("searchEvents: lat=$userLat lng=$userLng location=$location chip=${state.currentLocationName} fromChip=$locationCameFromChip")

        // If Bo explicitly passed a location that differs from the chip AND
        // we geocoded it successfully, update the chip to reflect the new
        // search scope. The chip is meant to always show "where we're
        // searching now" — staying out of sync confuses the user.
        if (!explicitLocation.isNullOrBlank() && locationCoords != null) {
            val explicitTrim = explicitLocation.trim()
            if (explicitTrim.isNotBlank() && !explicitTrim.equals(state.currentLocationName.trim(), ignoreCase = true)) {
                _uiState.update {
                    it.copy(
                        currentLocationName = explicitTrim,
                        currentLat = locationCoords.first,
                        currentLng = locationCoords.second,
                    )
                }
            }
        }

        // Bo gets the three-lane stratified pool (breadth + niche + keyword).
        // Bo's `category` and `keyword` collapse into a single keyword hint —
        // the RPC's keyword lane already ilikes across all five surfaces, so
        // either one is matched the same way. Prefer keyword when both are
        // set (more specific signal).
        val effectiveKeyword = listOf(keyword, category).firstOrNull { !it.isNullOrBlank() }
        // Radius comes from the search-scope banner (state.searchRadiusMiles).
        // When the user widens the slider, every subsequent search expands.
        // When strict_location is also on, the city filter is applied AFTER
        // the bbox query — so widening the radius doesn't bypass strict.
        val effectiveRadius = _uiState.value.searchRadiusMiles
        val rawEvents = if (userLat != null && userLng != null) {
            eventRepo.searchEventsBroad(
                userLat = userLat,
                userLng = userLng,
                radiusMiles = effectiveRadius,
                dateRange = dateRange,
                startDate = startDate,
                endDate = endDate,
                keyword = effectiveKeyword,
                // Cap raised to match the RPC ceiling — `search_events_stratified`
                // hard-caps `p_max_results` at 500, so any higher value just
                // silently truncates. We ask for the full 500 every time and
                // let Bo (and the display cap below) decide how much to use.
                limit = limit.coerceAtMost(500),
            )
        } else {
            // Rare fallback when we have no coords (geocoding failed and the
            // user's location isn't known) — degrade to the strict-filter
            // search so Bo still gets something rather than nothing.
            eventRepo.searchEvents(
                location = location,
                category = category,
                dateRange = dateRange,
                keyword = keyword,
                limit = limit,
                startDate = startDate,
                endDate = endDate,
                userLat = userLat,
                userLng = userLng,
                radiusMiles = effectiveRadius,
            )
        }

        // Drop events at rank <= 10 venues (community centers, K-12 fields,
        // junk listings). Matches the same global floor the Explore and
        // carousels surfaces enforce — Yocal should never recommend venues
        // the user can't actually discover elsewhere in the app.
        val lowRankNames = venueRepo.getLowRankVenueNames()
        val cleaned = if (lowRankNames.isEmpty()) rawEvents else rawEvents.filter { e ->
            val vn = e.venueName?.trim()?.lowercase() ?: return@filter true
            val normalized = vn.removePrefix("the ").replace(Regex("[^a-z0-9\\s]"), "").trim()
            vn !in lowRankNames && normalized !in lowRankNames
        }
        // Strict-location filter: when the user said "in {city}" / named a
        // place with no modifier (or the chip is set to a city), drop every
        // result whose venue_city doesn't match. We keep both counts (cleaned
        // = full 20mi pool, events = in-scope) so the response can surface a
        // coverage breadcrumb to Bo, who can offer the user the choice to
        // widen rather than silently expanding.
        val events = if (strictLocation && !location.isNullOrBlank()) {
            val target = location.trim().lowercase()
            cleaned.filter { e ->
                val ec = e.venueCity?.trim()?.lowercase() ?: return@filter false
                ec == target || ec.contains(target) || target.contains(ec)
            }.also { kept ->
                println("searchEvents: strict_location=true location='$location' kept ${kept.size}/${cleaned.size} after city filter")
            }
        } else cleaned
        val coverageInScope = events.size
        val coverageWithin20mi = cleaned.size

        // Cache full event objects — local list-keyed cache for the
        // plan-building paths AND the shared BoTools map-keyed cache so
        // get_event_details (delegated to BoTools) can answer without a
        // second round-trip.
        searchResultCache.addAll(events)
        turnSearchPool.addAll(events)
        events.forEach { boTools.searchResultCache[it.id] = it }

        // Data-driven search narration — tell the user what we actually found
        // ("Found 12 arts events", "Lots of music events") + the tailoring/sort
        // steps, instead of generic "Searching nearby" filler. Counts are
        // radius-scoped and drop no-public-interest so they match the tray.
        run {
            val nLat = _uiState.value.currentLat
            val nLng = _uiState.value.currentLng
            val nRadius = _uiState.value.searchRadiusMiles * 1.1
            val inRadius = events.filter { ev ->
                if (ev.subcategory == "no-public-interest") return@filter false
                val vlat = ev.venueLat ?: ev.eventLat
                val vlng = ev.venueLng ?: ev.eventLng
                nLat == null || nLng == null || (vlat != null && vlng != null &&
                    live.localapp.local.domain.model.distanceMiles(nLat, nLng, vlat, vlng) <= nRadius)
            }
            val byCat = inRadius
                .groupingBy { computeEventCategoryKey(it).third }
                .eachCount()
                .entries.sortedByDescending { it.value }
            val lines = mutableListOf<String>()
            for ((label, n) in byCat.take(4)) {
                if (n <= 0 || label == "Other") continue
                val lc = label.lowercase()
                lines += if (n >= 15) "Lots of $lc events" else "Found $n $lc event${if (n == 1) "" else "s"}"
            }
            if (tasteProfile != null && (tasteProfile?.interactionCount ?: 0) >= 3) {
                lines += "Tailoring to your preferences"
            }
            lines += "Sorting by what's best"
            lines += "Putting it on the map"
            if (lines.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        isSearching = true,
                        searchingNarration = (it.searchingNarration + lines).distinct().takeLast(7),
                    )
                }
            }
        }

        // Cap what Claude sees in the tool result. This is the single biggest
        // latency lever on a browse/plan turn: each compact row is ~50 tokens,
        // and Claude must READ the entire tool_result before it can emit a tray
        // or plan. At the old cap of 300 (~15k tokens) Bo — now on Haiku, not
        // Sonnet/Opus — spent most of the turn just ingesting events it would
        // never surface (a tray shows ~15-30, a plan a handful). The tool_result
        // is new every turn, so prompt caching can't amortize it either.
        //
        // 80 keeps the stratified pool's most-relevant head (the RPC already
        // sorts by breadth/niche/keyword relevance), which is far more than any
        // tray or plan uses, while cutting the tool_result to ~4k tokens — a 4×
        // reduction in the dominant cost. The FULL pool (up to 500) still lives
        // in searchResultCache for client-side hydration and the "Find more"
        // load-more path, so nothing the user can reach is lost — Bo just stops
        // reading rows it was always going to discard.
        val totalFound = events.size
        // Named-venue determinism: search_events is a 3-lane stratified pool
        // (breadth + niche + keyword). When the user passes a keyword that
        // names a specific venue (e.g. "New Art Center"), the breadth lane can
        // flood the head of the pool with unrelated high-rank events (Red Sox
        // @ Fenway, etc.), burying the named venue's few low-rank events past
        // the 80-row display cap below — so Bo reads the cap, doesn't see them,
        // and (non-deterministically) either gives up or has to self-correct
        // with strict_location. Float keyword/venue-name matches to the front
        // FIRST so the named venue's events always lead what Bo reads.
        // sortedByDescending on a Boolean is stable: it only hoists matches to
        // the head and preserves the RPC's relevance order within each group.
        val keywordLower = effectiveKeyword?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val ordered = if (keywordLower == null) {
            events
        } else {
            events.sortedByDescending { e ->
                (e.venueName?.lowercase()?.contains(keywordLower) == true) ||
                    e.title.lowercase().contains(keywordLower)
            }
        }
        val displayEvents = ordered.take(80)

        // ── JSON tool result (Phase 1 refactor) ────────────────────────
        // Bo reads JSON natively — no parsing of "=== Saturday ===" headers
        // or "[evt:xxx]" markers. Event IDs are first-class fields so Bo
        // can't lose them, mis-extract them, or invent them.
        val radiusInt = effectiveRadius.toInt()
        if (displayEvents.isEmpty()) {
            return kotlinx.serialization.json.buildJsonObject {
                put("events", kotlinx.serialization.json.buildJsonArray { })
                put("coverage", kotlinx.serialization.json.buildJsonObject {
                    put("returned", JsonPrimitive(0))
                    put("total_in_scope", JsonPrimitive(0))
                    if (strictLocation && !location.isNullOrBlank()) {
                        put("location", JsonPrimitive(location))
                        put("strict", JsonPrimitive(true))
                        put("total_in_radius", JsonPrimitive(coverageWithin20mi))
                        put("radius_miles", JsonPrimitive(radiusInt))
                    }
                })
                if (strictLocation && !location.isNullOrBlank() && coverageWithin20mi > 0) {
                    put("guidance", JsonPrimitive(
                        "0 in $location, $coverageWithin20mi within ${radiusInt}mi. Ask the user: \"I don't see anything in $location — want me to widen to nearby towns?\" Wait for confirmation before re-searching with strict_location=false."
                    ))
                }
            }.toString()
        }

        return kotlinx.serialization.json.buildJsonObject {
            put("events", kotlinx.serialization.json.buildJsonArray {
                displayEvents.forEach { e ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("id", JsonPrimitive(e.id))
                        put("title", JsonPrimitive(e.title))
                        put("venue_name", JsonPrimitive(e.venueName ?: ""))
                        put("venue_city", JsonPrimitive(e.venueCity))
                        val isFlexible = e.category == "always_on" || e.startAt == null
                        if (isFlexible) {
                            put("date", JsonPrimitive("Flexible"))
                            put("time", JsonPrimitive("anytime"))
                            put("iso_date", kotlinx.serialization.json.JsonNull)
                        } else {
                            try {
                                val instant = kotlinx.datetime.Instant.parse(e.startAt!!)
                                val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                                val dowFull = local.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
                                val monthNames = arrayOf("January","February","March","April","May","June","July","August","September","October","November","December")
                                val monthName = monthNames[local.monthNumber - 1]
                                put("date", JsonPrimitive("$dowFull, $monthName ${local.dayOfMonth}"))
                                put("iso_date", JsonPrimitive(local.date.toString()))
                                val h = local.hour
                                val m = local.minute
                                val timeStr = if (!e.timeKnown) "TBD"
                                else {
                                    val ampm = if (h < 12) "am" else "pm"
                                    val dh = if (h == 0) 12 else if (h > 12) h - 12 else h
                                    if (m == 0) "${dh}${ampm}" else "${dh}:${m.toString().padStart(2, '0')}${ampm}"
                                }
                                put("time", JsonPrimitive(timeStr))
                            } catch (_: Exception) {
                                put("date", JsonPrimitive(e.startAt?.take(10) ?: "?"))
                                put("iso_date", JsonPrimitive(e.startAt?.take(10) ?: ""))
                                put("time", JsonPrimitive("?"))
                            }
                        }
                        put("category", JsonPrimitive(e.category ?: "other"))
                        put("subcategory", JsonPrimitive(e.subcategory ?: ""))
                        put("is_free", JsonPrimitive(e.isFree))
                        put("price", JsonPrimitive(if (e.isFree) "FREE" else e.priceDisplay))
                    })
                }
            })
            put("coverage", kotlinx.serialization.json.buildJsonObject {
                put("returned", JsonPrimitive(displayEvents.size))
                put("total_in_scope", JsonPrimitive(totalFound))
                put("radius_miles", JsonPrimitive(radiusInt))
                if (strictLocation && !location.isNullOrBlank()) {
                    put("location", JsonPrimitive(location))
                    put("strict", JsonPrimitive(true))
                    put("total_in_radius", JsonPrimitive(coverageWithin20mi))
                }
            })
            // Honest "there's more" hint so Bo can offer to dig deeper
            // instead of claiming the well is dry. Only present when the
            // result was capped.
            if (totalFound > displayEvents.size) {
                put("has_more", JsonPrimitive(true))
                put("guidance", JsonPrimitive(
                    "Result capped: showed $totalFound but returned ${displayEvents.size}. To see more, call search_events again with a narrower keyword, category, or date filter."
                ))
            }
            // Coverage guidance for strict-mode + nearby results — Bo MUST
            // ask before widening per the COVERAGE-DRIVEN CONFIRMATION rule.
            if (strictLocation && !location.isNullOrBlank() && coverageWithin20mi > coverageInScope) {
                val extra = coverageWithin20mi - coverageInScope
                put("widen_available", JsonPrimitive(true))
                put("widen_guidance", JsonPrimitive(
                    "$coverageInScope in $location; $extra more within ${radiusInt}mi. Ask the user before re-searching with strict_location=false."
                ))
            }
        }.toString()
    }

    private suspend fun searchVenues(input: JsonObject): String {
        val location = input["location"]?.jsonPrimitive?.contentOrNull
        val category = input["category"]?.jsonPrimitive?.contentOrNull
        val keyword = input["keyword"]?.jsonPrimitive?.contentOrNull
        val limit = input["limit"]?.jsonPrimitive?.intOrNull ?: 10000

        // Honor the chat search scope (the "Where should Bo search?" pill) exactly
        // like searchEvents does: prefer the scope coords, fall back to the user's
        // profile location. Previously venue searches ignored the scope entirely
        // (city-name match only) — so Bo recommended places in the wrong place.
        val state = _uiState.value
        val prefs = state.userPreferences
        val prefsLat = prefs?.let { if (it.locationMode == "manual") it.manualLat ?: it.userLat else it.userLat }
        val prefsLng = prefs?.let { if (it.locationMode == "manual") it.manualLng ?: it.userLng else it.userLng }
        val scopeLat = state.currentLat ?: prefsLat
        val scopeLng = state.currentLng ?: prefsLng
        val haveCoords = scopeLat != null && scopeLng != null

        val venues = venueRepo.searchVenues(
            location = location,
            category = category,
            keyword = keyword,
            limit = limit,
            userLat = scopeLat,
            userLng = scopeLng,
            radiusMiles = if (haveCoords) state.searchRadiusMiles else null,
        )

        return venues.joinToString("\n") { v ->
            val cat = v.category ?: v.placeType ?: ""
            val rating = v.googleRating?.let { " | Rating: $it" } ?: ""
            "[${v.id}] ${v.name} | ${v.city}${if (v.address != null) " (${v.address})" else ""} | $cat$rating"
        }.ifEmpty { "No venues found matching your search." }
    }

    private suspend fun getEventDetails(input: JsonObject): String =
        boTools.getEventDetails(input)

    private val httpClient = HttpClient()

    // ── Bo web tools (Phase 1) ────────────────────────────────────────────

    private data class BoWebBudget(
        val allowWebSearch: Boolean,
        val allowWebFetch: Boolean,
        val reason: String,           // ok | not_live_state | user_day_cap | user_hour_cap | monthly_cap | no_profile | no_state
        val userState: String?,
        val liveStatesList: String,
    )

    /** GET /functions/v1/bo-web-budget — gate decision for this turn. */
    private suspend fun fetchBoWebBudget(): BoWebBudget? {
        val userId = userPrefsRepo.getLocalUserId()
        if (userId == null) {
            println("Bo[web-gate]: NULL userId — skipping fetch")
            return null
        }
        return try {
            println("Bo[web-gate]: fetching for user=$userId")
            val res = httpClient.get("${Config.SUPABASE_URL}/functions/v1/bo-web-budget?user_id=$userId") {
                header("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
            }
            val body = res.bodyAsText()
            println("Bo[web-gate]: status=${res.status.value} body=${body.take(300)}")
            if (res.status.value !in 200..299) return null
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
            BoWebBudget(
                allowWebSearch = obj["allow_web_search"]?.jsonPrimitive?.booleanOrNull == true,
                allowWebFetch = obj["allow_web_fetch"]?.jsonPrimitive?.booleanOrNull == true,
                reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                userState = obj["user_state"]?.jsonPrimitive?.contentOrNull,
                liveStatesList = obj["live_states_list"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        } catch (e: Throwable) {
            println("Bo[web-gate]: fetch error: ${e.message}")
            null
        }
    }

    /** Append per-turn budget context so Bo delivers the right blocked message. */
    private fun augmentSystemPromptWithBudget(systemPrompt: String, budget: BoWebBudget?): String {
        if (budget == null) {
            println("Bo[web-gate]: NULL budget (fetch failed) — web tools NOT included")
            return systemPrompt
        }
        println("Bo[web-gate]: reason=${budget.reason} allowSearch=${budget.allowWebSearch} allowFetch=${budget.allowWebFetch}")
        val lines = buildString {
            appendLine()
            appendLine("============================================")
            appendLine("WEB SEARCH IS ${if (budget.allowWebSearch) "AVAILABLE" else "DISABLED"}")
            appendLine("============================================")
            when (budget.reason) {
                "ok" -> {
                    appendLine("You have web_search and web_fetch tools available, but use them SPARINGLY.")
                    appendLine("They exist to verify FACTS about events and venues we already have, not as a")
                    appendLine("general-purpose research engine. Most questions can be answered from the DB.")
                    appendLine("")
                    appendLine("USE web_search ONLY when ALL of these are true:")
                    appendLine("  1. The user is asking about a specific event/venue we already discussed in")
                    appendLine("     this conversation, or one returned by search_events / search_venues.")
                    appendLine("  2. You need a factual detail we don't store: open hours, ticket availability,")
                    appendLine("     price, weather affecting an outdoor event, current capacity, age limits.")
                    appendLine("  3. You've already run search_events or search_venues at least twice with")
                    appendLine("     broadened terms. web_search is the LAST resort, never the first response.")
                    appendLine("")
                    appendLine("OR — EMPTY-DB EXCEPTION — rules 1-3 above DO NOT apply when search_events OR")
                    appendLine("search_venues has returned 0 results across at least one broadening attempt")
                    appendLine("AND the user is browsing for local things to do — events OR venues, e.g.")
                    appendLine("'best restaurants in Watertown', 'cool bars nearby' (not asking a factual")
                    appendLine("question). In that case web_search is REQUIRED (not last resort) — see the")
                    appendLine("EMPTY DB FALLBACK section below for the exact flow.")
                    appendLine("")
                    appendLine("OR — DINING/FOOD-AND-DRINK EXCEPTION — rules 1-3 above DO NOT apply when the")
                    appendLine("user is asking for restaurants, bars, cafes, breweries, or anywhere to eat or")
                    appendLine("drink (e.g. 'good restaurants nearby', 'where can I get brunch', 'cool")
                    appendLine("cocktail bars'). Our DB carries only a thin slice of local dining — the open")
                    appendLine("web always has more. So for a dining ask, run ONE web_search for the cuisine/")
                    appendLine("vibe + location (e.g. 'best Italian restaurants in Waltham MA') EVEN WHEN")
                    appendLine("search_venues returned hits, and fold the web finds in alongside any DB")
                    appendLine("matches. Do NOT dead-end on a short DB-only list for dining. Still ONE")
                    appendLine("web_search call, not a research spree.")
                    appendLine("")
                    appendLine("DO NOT use web_search for:")
                    appendLine("  • Explaining what something is (history, geography, biography, definitions).")
                    appendLine("    Example: user says 'what about Lalibela' — that's a noun. First treat it as")
                    appendLine("    a possible local venue/event: run search_events('Lalibela') and search_venues.")
                    appendLine("    If both return nothing, ONE-SENTENCE redirect: 'I didn't find a local match")
                    appendLine("    for that. Did you mean an Ethiopian restaurant nearby?' — and STOP. Do NOT")
                    appendLine("    web_search to research what Lalibela is in Ethiopia.")
                    appendLine("  • General-knowledge questions (weather alone, news, biology, pop culture, advice).")
                    appendLine("  • Anything outside event/venue discovery.")
                    appendLine("  • A first-pass response when you could have searched our DB first.")
                    appendLine("")
                    appendLine("SCOPE DECLINE — when the user asks something out of scope, reply ONE sentence:")
                    appendLine("'That's outside what I can help with — I'm here to plan local events. Want me to")
                    appendLine("find <relevant local thing> instead?' Then stop. Do NOT free-text the off-topic")
                    appendLine("answer. Do NOT web_search to look it up. Do NOT volunteer trivia.")
                    appendLine("")
                    appendLine("SEARCH DEPTH — keep digging:")
                    appendLine("  • If search_events returns < 3 results AND user is browsing, you MUST run a")
                    appendLine("    second search_events with broadened terms before responding. Try in order:")
                    appendLine("    drop the keyword, drop the category, widen the radius.")
                    appendLine("  • EXCEPTION: if the user named a specific date, DO NOT widen the date window")
                    appendLine("    as a depth fallback — answer honestly for that date instead. Otherwise you")
                    appendLine("    silently answer a different question than the one asked.")
                    appendLine("  • Never say 'I couldn't find anything' after one search.")
                    appendLine("")
                    appendLine("EMPTY DB FALLBACK — the exact flow when search_events OR search_venues keeps returning 0:")
                    appendLine("  1. After ONE broadening attempt, if search_events / search_venues still")
                    appendLine("     returns 0, STOP retrying. More retries waste tool rounds and produce 'no")
                    appendLine("     results' prose the user has to type to escape from.")
                    appendLine("  2. Run ONE web_search query summarizing location + date + activity/venue intent")
                    appendLine("     (e.g. 'things to do in Ipswich MA May 23-24 weekend', or 'best restaurants")
                    appendLine("     in Watertown MA'). This is the ONLY exception to 'web_search is the last resort'.")
                    appendLine("  3. Then call present_suggestions with:")
                    appendLine("       - headline: one line acknowledging the empty DB AND recapping what")
                    appendLine("         web_search surfaced. E.g. 'Quiet in our DB for Ipswich May 23-24, but")
                    appendLine("         the Crane Estate has a guided trail walk Saturday and Castle Hill's")
                    appendLine("         lawn concerts kick off the season — here's what I found online.'")
                    appendLine("         If web_search returned nothing useful, say so honestly: 'Quiet in our")
                    appendLine("         DB AND nothing concrete online either for that date.'")
                    appendLine("       - groups: [] (empty array — web findings aren't yet saved as event_ids,")
                    appendLine("         and you have no DB events to include).")
                    appendLine("       - footer.actions:")
                    appendLine("           • If web_search returned usable findings → [\"build_from_web\",")
                    appendLine("             \"search_wider\", \"different_day\"]")
                    appendLine("           • If web_search returned nothing OR is disabled → [\"search_wider\",")
                    appendLine("             \"different_day\"]")
                    appendLine("  4. Do NOT respond with plain CHAT prose ('Want me to try a different date")
                    appendLine("     range?') in this case — call present_suggestions so the chips are")
                    appendLine("     tappable. Prose forces the user to type. Chips are one tap.")
                    appendLine("  5. Do NOT reference earlier turns' suggestion trays or plans — the user may")
                    appendLine("     have changed location, dates, or intent since. Acknowledge only the")
                    appendLine("     current empty result.")
                    appendLine("")
                    appendLine("PRESENT_SUGGESTIONS IS MANDATORY when you mention events — no exceptions:")
                    appendLine("  • The moment search_events returns ≥ 1 useful event AND you intend to")
                    appendLine("    discuss them, you MUST call present_suggestions in the SAME response.")
                    appendLine("    Prose-only descriptions of search results are forbidden.")
                    appendLine("  • A 1-event tray is correct UX. Do NOT skip present_suggestions because")
                    appendLine("    'only one result feels too sparse' — present it and let the user decide.")
                    appendLine("  • If an event came back from search_events but you judge it irrelevant")
                    appendLine("    (wrong distance, wrong type), SKIP it silently — don't mention it in")
                    appendLine("    prose and then refuse to display it. That confuses the user.")
                    appendLine("  • If after all your searches NOTHING is worth presenting, route through")
                    appendLine("    EMPTY DB FALLBACK above — call present_suggestions with groups=[] and")
                    appendLine("    footer.actions — never end on CHAT prose alone.")
                    appendLine("")
                    appendLine("REFERENCE INTEGRITY — what you can say:")
                    appendLine("  • You MAY NOT reference 'these suggestions', 'those options', 'the events I")
                    appendLine("    mentioned', 'these picks', or any similar phrase UNLESS you called")
                    appendLine("    present_suggestions in THIS response. Cross-turn references are")
                    appendLine("    forbidden — the user may have changed location/dates, may have scrolled")
                    appendLine("    past the prior tray, or may not associate this turn with it.")
                    appendLine("  • If you find yourself wanting to ask 'Want me to build a plan around")
                    appendLine("    these?', make sure 'these' is in fact a present_suggestions tray you")
                    appendLine("    just emitted. If not, drop the demonstrative ('these'/'those') and")
                    appendLine("    rephrase concretely — or, better, call present_suggestions so the")
                    appendLine("    references become real.")
                    appendLine("  • Confirmation questions about phantom suggestions are the worst Bo")
                    appendLine("    behavior we've ever shipped — they make the chat look broken.")
                    appendLine("")
                    appendLine("EXCLUSIONS — when user says 'not X' or 'something other than X':")
                    appendLine("  • Re-run search_events excluding X. Do NOT reuse cached results.")
                    appendLine("  • Track exclusions across the whole conversation, not just this turn.")
                    appendLine("")
                    appendLine("Use web_fetch only when user pastes a URL. Hard cap: 2 web_search calls per turn.")
                    appendLine("")
                    appendLine("FUZZY-INTENT KEYWORDS: 'dance', 'drinks', 'cozy' etc. are intents not literal tags. The keyword filter searches title + venue_name + description + category, so a music event with 'DJ, dancing under the stars' in description WILL match keyword='dance'. Trust the search; widen the keyword if first pass is sparse.")
                    appendLine("DATE-WINDOW: 'next couple weeks' / 'sometime soon' = 30 days, not 14.")
                    appendLine("")
                    appendLine("EVENT vs VENUE — READ CAREFULLY (this applies to BOTH search_events results AND")
                    appendLine("web_search findings, equally):")
                    appendLine("")
                    appendLine("If you discover a SPECIFIC EVENT (a workshop, concert, talk, class, performance,")
                    appendLine("screening, anything happening at a specific date/time at a venue), the slot MUST")
                    appendLine("represent that event. THE EVENT IS THE THING THE USER IS GOING TO. The venue is just")
                    appendLine("where it happens. Examples: 'Threads That Bind weaving workshop at Newton Free Library',")
                    appendLine("'Pottery class at the Y', 'Author talk at MIT', 'Trivia night at Trillium'.")
                    appendLine("")
                    appendLine("RULES (no exceptions, regardless of source):")
                    appendLine("  • If the event came from search_events → put its event_id in the slot's event_id field.")
                    appendLine("  • If the event came from web_search → call save_web_finding(kind='event') with the")
                    appendLine("    EVENT title (the workshop/class/talk name, NOT the venue name), include venue_name,")
                    appendLine("    venue_address, start_at (ISO 8601, future, full datetime), source_url, and")
                    appendLine("    source_domains. Use the returned id in slot.event_id and set is_bo_web: true.")
                    appendLine("  • NEVER put a discovered event into venue_name. venue_name is ONLY for plain")
                    appendLine("    restaurant/bar/cafe stops (just 'go eat dinner here' — no specific event).")
                    appendLine("    If you found 'a weaving workshop at the library', the SLOT IS the workshop, not the")
                    appendLine("    library — the slot's display name comes from the event title, so readers see")
                    appendLine("    'Threads That Bind' (the event), not 'Newton Free Library' (the venue).")
                    appendLine("")
                    appendLine("DATE INTEGRITY — never lie about overlap:")
                    appendLine("Before suggesting any event with a fixed date, you MUST check that its date(s) actually")
                    appendLine("fall within the user's plan window. If the user says 'June 1–3' and you find an event")
                    appendLine("on June 11, those dates DO NOT overlap. Period. Do NOT say 'overlaps with your dates'")
                    appendLine("when it doesn't. Acceptable responses when an event you found is outside the user's")
                    appendLine("dates:")
                    appendLine("  • Drop the event silently and find ones that ARE in the window.")
                    appendLine("  • Or, if the event is genuinely compelling, ask: 'There's also X on June 11–14 — would")
                    appendLine("    you want to shift your trip, or should I stick to June 1–3?'")
                    appendLine("Never tell a user an event 'overlaps' when it doesn't. This breaks trust instantly.")
                    appendLine("")
                    appendLine("PROACTIVE OPTIONS — when the user asks for a category ('artsy events', 'something")
                    appendLine("outdoorsy', 'a fun date night'):")
                    appendLine("  • Run search_events FIRST. If it returns 5+ results, present 3-4 as options")
                    appendLine("    via present_suggestions and STOP — do NOT run web_search to enrich them.")
                    appendLine("  • Web_search is FORBIDDEN here unless search_events returned 0 across a")
                    appendLine("    broadening attempt (the EMPTY DB FALLBACK case). When the DB has results,")
                    appendLine("    answer from the DB only — web_search adds several seconds for findings the")
                    appendLine("    user did not ask for. Speed beats long-tail enrichment.")
                    appendLine("  • Surface 3-6 distinct options from search_events when the user is in discovery")
                    appendLine("    mode. Don't pick ONE without showing alternatives unless the user explicitly")
                    appendLine("    asked for a specific thing.")
                    appendLine("")
                    appendLine("FORMATTING — when responding after web_search:")
                    appendLine("  • Write a single coherent message. Don't lead with 'I'll check…' before searching;")
                    appendLine("    the user already sees a searching indicator. Just answer the question directly.")
                    appendLine("  • NEVER put a period, comma, or any punctuation on its own line. Sentences end with")
                    appendLine("    their period inline, not orphaned to the next paragraph.")
                    appendLine("  • Keep paragraphs whole. No mid-sentence line breaks.")
                }
                "not_live_state" -> {
                    appendLine("Web search is DISABLED — user is in ${budget.userState ?: "a non-live state"}.")
                    appendLine("If the user asks anything requiring web lookup, respond exactly:")
                    appendLine("  \"Local is not yet live in your state. It is currently live in ${budget.liveStatesList}.\"")
                }
                "user_day_cap", "user_hour_cap" -> {
                    appendLine("Web search is DISABLED — user hit their quota.")
                    appendLine("If they ask something requiring it, respond: \"I've hit my lookup limit for now — try again later.\"")
                }
                "deboosted" -> {
                    // Don't expose to the user that they've been deboosted for
                    // a high rejection rate. Treat like a generic limit.
                    appendLine("Web search is DISABLED for this user. Use database tools only.")
                    appendLine("If asked something requiring web search, respond: \"I'll stick with what I have in our database.\"")
                }
                "monthly_cap" -> {
                    appendLine("Web search is DISABLED — global monthly budget reached.")
                }
                else -> {
                    appendLine("Web search disabled (${budget.reason}).")
                }
            }
            appendLine("============================================")
        }
        return systemPrompt + lines
    }

    private fun tallyWebCalls(response: ClaudeResponse, tally: MutableList<Triple<String, String?, Int?>>) {
        for (block in response.content) {
            if (block.type == "server_tool_use" && (block.name == "web_search" || block.name == "web_fetch")) {
                val q = block.input?.get("query")?.jsonPrimitive?.contentOrNull
                    ?: block.input?.get("url")?.jsonPrimitive?.contentOrNull
                tally.add(Triple(block.name!!, q, null))
            }
        }
    }

    /**
     * Bo's save_web_finding tool dispatcher. Forwards the input payload to the
     * save-bo-web-finding edge function which inserts into bo_web_events or
     * bo_web_venues with state='unverified' and returns the new row id. Bo uses
     * that id as the slot's event_id in present_plan / update_plan, plus
     * is_bo_web=true so the UI shows the "Bo found this — verifying" badge.
     */
    /**
     * Cache of save_web_finding input payloads keyed by the returned bo_web_events
     * (or bo_web_venues) id, so buildPlanFromRefs can hydrate Bo-Web slots
     * without a DB roundtrip. Lives for the chat session.
     */
    private val boWebFindingsCache get() = boTools.boWebFindingsCache

    private suspend fun saveWebFinding(input: JsonObject): String =
        boTools.saveWebFinding(input)

    /** POST /functions/v1/bo-web-budget — log per-turn web tool usage. */
    private suspend fun logBoWebCalls(calls: List<Triple<String, String?, Int?>>) {
        val userId = userPrefsRepo.getLocalUserId() ?: return
        try {
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("user_id", JsonPrimitive(userId))
                put("caller", JsonPrimitive("bo_chat"))
                putJsonArray("calls") {
                    for ((tool, query, count) in calls) {
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("tool", JsonPrimitive(tool))
                            if (query != null) put("query", JsonPrimitive(query))
                            if (count != null) put("result_count", JsonPrimitive(count))
                        })
                    }
                }
            }
            httpClient.post("${Config.SUPABASE_URL}/functions/v1/bo-web-budget") {
                header("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
        } catch (e: Throwable) {
            println("logBoWebCalls error: ${e.message}")
        }
    }

    private suspend fun recommendAndSaveVenue(input: JsonObject): String {
        val name = input["name"]?.jsonPrimitive?.contentOrNull
            ?: return """{"error": "name is required"}"""
        val city = input["city"]?.jsonPrimitive?.contentOrNull
            ?: return """{"error": "city is required"}"""
        val cuisineType = input["cuisine_or_type"]?.jsonPrimitive?.contentOrNull

        return try {
            val response = httpClient.post("${Config.SUPABASE_URL}/functions/v1/validate-venue") {
                header("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("name", name)
                    put("city", city)
                    cuisineType?.let { put("cuisine_or_type", it) }
                }.toString())
            }

            val body = response.bodyAsText()
            println("validate-venue response: status=${response.status.value} body=${body.take(500)}")

            if (response.status.value !in 200..299) {
                """{"error": "Failed to validate venue: ${response.status.value}"}"""
            } else {
                // Cache the validated metadata so the plan builder can hydrate
                // the slot (rating, lat/lng, website, reservation) even when the
                // venue isn't in our DB ("validated_not_saved"). Otherwise all
                // this is handed to Bo as a string and then lost.
                try {
                    val v = claudeApi.json.parseToJsonElement(body).jsonObject["venue"]?.jsonObject
                    if (v != null) {
                        fun s(k: String) = v[k]?.jsonPrimitive?.contentOrNull
                        fun d(k: String) = v[k]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                        val parsed = ValidatedVenue(
                            name = s("name") ?: name,
                            city = s("city"),
                            state = s("state"),
                            address = s("address"),
                            lat = d("lat"),
                            lng = d("lng"),
                            googleRating = d("google_rating"),
                            website = s("website"),
                            photoUrl = s("photo_url"),
                            category = s("category") ?: s("place_type"),
                            reservationUrl = s("reservation_url"),
                        )
                        // Key under both the name Bo passed and the canonical
                        // Google name (e.g. "Vernick Food and Drink" vs
                        // "Vernick Food & Drink") so the plan-ref lookup hits.
                        validatedVenueCache[venueKey(name)] = parsed
                        validatedVenueCache[venueKey(parsed.name)] = parsed
                    }
                } catch (e: Throwable) {
                    println("recommendAndSaveVenue: metadata cache parse failed: ${e.message}")
                }
                body
            }
        } catch (e: Exception) {
            println("recommend_and_save_venue error: ${e.message}")
            """{"error": "Failed to validate venue: ${e.message}"}"""
        }
    }

    /**
     * After a plan is built, find any venues whose DB description is null
     * and ask Claude (Haiku, cheap + fast) for a one-sentence description
     * each. Persist to the venues table so the venue detail page renders
     * meaningful text instead of an empty space below the rating.
     *
     * Fire-and-forget — never blocks the UI. Slot detail in the current
     * plan card stays as Bo's note (or city/rating fallback); the next
     * time the user opens venue detail, it'll have the description.
     */
    /**
     * Hydrate slot.imageUrl post-build for any slot that came back null.
     * buildPlanFromRefs already does an event.imageUrl ?: venuePhoto fallback,
     * but the venue lookup is one-shot and can miss when the venues row hasn't
     * been geo-hydrated yet. Run a second-pass batched lookup so the slot
     * card renders a real photo instead of the coral fallback tile.
     */
    private fun backfillMissingSlotImages(days: List<ItineraryDay>) {
        viewModelScope.launch {
            try {
                val needNames = days.flatMap { it.slots }
                    .filter { it.imageUrl.isNullOrBlank() && !it.venueName.isNullOrBlank() }
                    .mapNotNull { it.venueName?.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                if (needNames.isEmpty()) return@launch
                val photoByName = mutableMapOf<String, String>()
                for (n in needNames) {
                    val v = try { venueRepo.searchByName(n) } catch (_: Throwable) { null }
                    val url = v?.photoUrl?.takeIf { it.isNotBlank() } ?: continue
                    photoByName[n.lowercase()] = url
                }
                if (photoByName.isEmpty()) return@launch
                _uiState.update { state ->
                    state.copy(
                        days = state.days.map { day ->
                            day.copy(slots = day.slots.map { s ->
                                if (!s.imageUrl.isNullOrBlank()) s
                                else {
                                    val key = s.venueName?.trim()?.lowercase() ?: return@map s
                                    val url = photoByName[key] ?: return@map s
                                    s.copy(imageUrl = url)
                                }
                            })
                        }
                    )
                }
                println("backfillMissingSlotImages: filled ${photoByName.size} venue photos")
            } catch (e: Throwable) {
                println("backfillMissingSlotImages: ${e.message}")
            }
        }
    }

    private fun backfillMissingVenueDescriptions(days: List<ItineraryDay>) {
        viewModelScope.launch {
            try {
                val venueNames = days.flatMap { it.slots }
                    .mapNotNull { it.venueName?.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                if (venueNames.isEmpty()) return@launch

                data class Need(val id: String, val name: String, val city: String?, val category: String?)
                val needs = mutableListOf<Need>()
                for (n in venueNames) {
                    val v = try { venueRepo.searchByName(n) } catch (_: Throwable) { null } ?: continue
                    if (v.description.isNullOrBlank()) {
                        needs.add(Need(v.id, v.name, v.city, v.category))
                    }
                }
                if (needs.isEmpty()) return@launch
                println("backfillMissingVenueDescriptions: ${needs.size} venues need descriptions")

                val list = needs.joinToString("\n") { n ->
                    "- ${n.name}${n.city?.let { ", $it" } ?: ""}${n.category?.let { " (${it})" } ?: ""}"
                }
                val sys = "You write one-sentence venue descriptions for a local-events app. Each description must be 12-22 words, factual, and specific to the place — what kind of food/vibe/setting/specialty it's known for. Never start with the venue name. No marketing fluff."
                val user = "Write a one-sentence description for each venue below. Reply with a JSON object mapping the exact venue name to its description, no other text.\n\n$list"

                val resp = try {
                    claudeApi.sendRaw(
                        systemPrompt = sys,
                        messages = listOf(ClaudeMessage.text("user", user)),
                        model = ClaudeApiService.MODEL_CHAT,
                    )
                } catch (e: Throwable) {
                    println("backfillMissingVenueDescriptions: Claude call failed — ${e.message}")
                    return@launch
                }

                val text = resp.content
                    .filter { it.type == "text" }
                    .mapNotNull { it.text }
                    .joinToString("")
                    .trim()
                val jsonStart = text.indexOf('{')
                val jsonEnd = text.lastIndexOf('}')
                if (jsonStart < 0 || jsonEnd <= jsonStart) {
                    println("backfillMissingVenueDescriptions: no JSON in reply — ${text.take(200)}")
                    return@launch
                }
                val parsed = try {
                    Json.parseToJsonElement(text.substring(jsonStart, jsonEnd + 1)).jsonObject
                } catch (e: Throwable) {
                    println("backfillMissingVenueDescriptions: JSON parse failed — ${e.message}")
                    return@launch
                }

                val byName = needs.associateBy { it.name }
                for ((name, desc) in parsed) {
                    val target = byName[name] ?: continue
                    val descStr = desc.jsonPrimitive.contentOrNull?.trim().orEmpty()
                    if (descStr.isBlank()) continue
                    venueRepo.updateVenueDescription(target.id, descStr)
                }
            } catch (e: Throwable) {
                println("backfillMissingVenueDescriptions: unexpected error — ${e.message}")
            }
        }
    }

    // ── Process Claude's parsed natural language response ──

    private suspend fun handleClaudeResponse(parsed: ParsedChatResponse) {
        // Clear streaming text immediately — stream is done, permanent messages take over
        _uiState.update { it.copy(streamingText = "") }

        val responseText = parsed.text

        // Phase 6 — structured-response tools land here. Each one renders
        // as a Structured MessageContent inline in the message stream.
        // Bo's wrapper prose (≤ 2 sentences) is shown above the structured
        // payload, same pattern as plan cards.
        if (parsed.type in setOf(
                ParsedResponseType.SUGGESTIONS,
                ParsedResponseType.EVENT_BRIEF,
                ParsedResponseType.VENUE_BRIEF,
                ParsedResponseType.QUICK_FACT,
                ParsedResponseType.SCOPE_REDIRECT,
            )
        ) {
            handleStructuredResponse(parsed)
            return
        }

        when (parsed.type) {
            ParsedResponseType.CHAT -> {
                if (responseText.isNotBlank()) {
                    addAiMessage(responseText)
                }
                // Smart auto-chips based on context
                val chips = if (_uiState.value.days.isNotEmpty()) {
                    // Plan exists — offer approval + contextual update option
                    listOf("Looks good, add to My Plans", "Show me other options")
                } else {
                    emptyList()
                }
                finishTurn(chips)
            }

            ParsedResponseType.PLAN -> {
                consecutiveQuestionCount = 0
                val title = parsed.planTitle ?: "Your Plan"
                val builtDays = buildPlanFromRefs(parsed)
                val datedDays = validatePlanDates(builtDays)
                val sequencedDays = enforceSequentialDayLabels(datedDays)
                val dedupedDays = removeDuplicateSlots(sequencedDays)
                // Re-inject any event the user explicitly picked OR anchored
                // (tapped a saved event → "build a plan around X") that Bo
                // omitted from present_plan (prose lists N, tool call emits
                // < N, or Bo fails to text-match the anchor venue and drops
                // it entirely). No-op unless this turn began as a build-from-
                // picks handoff or a saved-event anchor tap. Both guards are
                // cleared so they never bleed into a later turn's plan.
                val anchorId = _uiState.value.pendingAnchorEventId?.removePrefix("evt:")
                val expectedIds = (pendingBuildExpectedIds + listOfNotNull(anchorId)).distinct()
                // Pure build-from-picks (no anchor) → the plan's EVENT slots must
                // be EXACTLY the picks. Prune any event Bo substituted (it does
                // this when the picks came from the client-injected tray and Bo
                // couldn't resolve their ids). NOT for the anchor case, where Bo
                // legitimately adds related events around the saved event.
                val pruneNonPicks = pendingBuildExpectedIds.isNotEmpty() && anchorId == null
                val validatedDays = reconcileExpectedPicks(dedupedDays, expectedIds, pruneNonPicks)
                pendingBuildExpectedIds = emptyList()
                // Clear the anchor only once a plan actually materialized — if
                // Bo asked a clarifying question first (INFO turn), the anchor
                // must survive to the eventual plan turn.
                if (anchorId != null) {
                    _uiState.update { it.copy(pendingAnchorEventId = null, pendingAnchorEventTitle = null) }
                }

                // If every day got dropped (empty refs from Bo, or every
                // event_id missed the DB / Bo-Web cache), don't render a
                // ghost plan card with a Save button — that persists a
                // garbage plan. Surface Bo's text response instead and
                // offer to retry. The drop-reason logs from
                // buildPlanFromRefs above already explain why.
                if (validatedDays.isEmpty()) {
                    println("ParsedResponseType.PLAN: validatedDays is empty — suppressing plan card render. Bo's text response was kept.")
                    if (responseText.isNotBlank()) {
                        addAiMessage(responseText)
                    } else {
                        addAiMessage("I couldn't pull together a concrete plan for that — none of the events I had came back with enough detail. Want me to try with different dates or activities?")
                    }
                    finishTurn(parsed.chips)
                    return
                }

                _uiState.update {
                    it.copy(
                        days = validatedDays,
                        planTitle = title,
                        // Always start at 1; user picks the real count via
                        // the GroupSizeSheet on first plan-detail open.
                        // Bo's inferred `attendee_count` is dropped here
                        // (intentional — see DO NOT ASK ABOUT GROUP SIZE
                        // rule) so downstream surfaces (celebration card
                        // "Share with N others" copy, savePlan attendees
                        // list) don't echo a number the user didn't pick.
                        planAttendeeCount = 1,
                        currentDayIndex = 0,
                        approvedDayCount = 0,
                    )
                }

                if (responseText.isNotBlank()) {
                    addAiMessage(responseText)
                }

                delay(350)

                // Mark any existing plan cards as superseded
                markOldPlanCardsSuperseded()

                val snapshot = _uiState.value.days.toList()
                if (validatedDays.size > 1) {
                    addMessage(ChatMessage(role = MessageRole.AI, content = MessageContent.MultiDayPlanRef(validatedDays.size, snapshot = snapshot)))
                } else {
                    addMessage(ChatMessage(role = MessageRole.AI, content = MessageContent.DayCardRef(0, snapshot = snapshot)))
                }

                fetchWeatherForPlan(validatedDays)
                backfillMissingVenueDescriptions(validatedDays)
                backfillMissingSlotImages(validatedDays)
                finishTurn(filterApproveChips(parsed.chips))
            }

            ParsedResponseType.PLAN_UPDATE -> {
                val dayIndex = parsed.dayIndex ?: _uiState.value.currentDayIndex
                val builtDays = buildPlanFromRefs(parsed)
                val updatedDay = builtDays.firstOrNull()

                // Slot identity for before/after comparison — used to decide
                // whether the update actually changed the plan (see below).
                fun dayKeys(d: ItineraryDay?): Set<String> =
                    d?.slots?.mapNotNull { it.eventId ?: it.name.trim().lowercase().ifBlank { null } }?.toSet()
                        ?: emptySet()
                val beforeKeys = dayKeys(_uiState.value.days.getOrNull(dayIndex))

                // Only apply an update that actually carries slots. An empty
                // updatedDay means buildPlanFromRefs resolved nothing (DB misses
                // / empty refs) — applying it would WIPE a previously-good day,
                // which is how "add June 12" left the day blank. Leave the plan
                // untouched and let the narration gate below tell the truth.
                if (updatedDay != null && updatedDay.slots.isNotEmpty()) {
                    val validatedDay = validatePlanDates(listOf(updatedDay)).first()
                    _uiState.update { state ->
                        val days = state.days.toMutableList()
                        if (dayIndex in days.indices) {
                            days[dayIndex] = validatedDay
                        }
                        // Dedup the whole plan so an update doesn't collide with
                        // a venue already present on another day.
                        state.copy(
                            days = removeDuplicateSlots(days),
                            currentDayIndex = dayIndex,
                            // Honor an attendee-count change passed with the
                            // update (user said "actually make it 4 people").
                            // Null = no change; keep prior count.
                            planAttendeeCount = parsed.attendeeCount?.coerceAtLeast(1)
                                ?: state.planAttendeeCount,
                        )
                    }
                } else if (parsed.attendeeCount != null) {
                    // Slots-only update with attendee_count and no day match
                    // (or empty slots) — still apply the count change.
                    _uiState.update { state ->
                        state.copy(planAttendeeCount = parsed.attendeeCount.coerceAtLeast(1))
                    }
                }

                // Couple narration to the real plan delta. Bo's prose ("I've
                // updated Friday with three great stops") is generated
                // independently of what actually landed; the user then replies
                // "I don't see them in the plan." Treat the update as landed
                // only when the target day's content actually CHANGED (or this
                // was a pure attendee-count change). Otherwise substitute an
                // honest, actionable message instead of a false success.
                val attendeeOnlyUpdate =
                    (updatedDay == null || updatedDay.slots.isEmpty()) && parsed.attendeeCount != null
                val afterKeys = dayKeys(_uiState.value.days.getOrNull(dayIndex))
                val updateLanded = attendeeOnlyUpdate ||
                    (afterKeys.isNotEmpty() && afterKeys != beforeKeys)

                if (updateLanded) {
                    if (responseText.isNotBlank()) {
                        addAiMessage(responseText)
                    }

                    // Mark old plan cards as superseded
                    markOldPlanCardsSuperseded()

                    val updateSnapshot = _uiState.value.days.toList()
                    if (updateSnapshot.size > 1) {
                        addMessage(ChatMessage(role = MessageRole.AI, content = MessageContent.MultiDayPlanRef(updateSnapshot.size, snapshot = updateSnapshot)))
                    } else {
                        addMessage(ChatMessage(role = MessageRole.AI, content = MessageContent.DayCardRef(dayIndex, snapshot = updateSnapshot)))
                    }

                    fetchWeatherForPlan(_uiState.value.days)
                    backfillMissingVenueDescriptions(_uiState.value.days)
                    backfillMissingSlotImages(_uiState.value.days)
                } else {
                    // Nothing changed — don't supersede/re-render the existing
                    // (still-accurate) plan cards or claim success.
                    println("PLAN_UPDATE: update did not land (no slot delta on day '${_uiState.value.days.getOrNull(dayIndex)?.label}') — suppressing Bo's success text, leaving existing cards intact")
                    addAiMessage("I couldn't lock those into your plan — the spots I had didn't come back with enough detail. Want me to widen the search radius or try different places for that day?")
                }

                finishTurn(filterApproveChips(parsed.chips))
            }

            ParsedResponseType.APPROVE -> {
                // Gate check BEFORE adding Claude's "I've added X to your plans" text
                // so the user doesn't see a false confirmation when we're about to
                // paywall them. Also resets isBusy via finishTurn so a second tap of
                // "Save to My Plans" re-triggers the flow (previously got stuck).
                val gateCheck = onPlanApprovalCheck
                if (gateCheck != null && !gateCheck()) {
                    println("APPROVE blocked by premium gate — showing paywall, leaving chat resumable")
                    onPlanBlocked?.invoke()
                    finishTurn(filterApproveChips(emptyList()))
                    return
                }

                if (responseText.isNotBlank()) {
                    addAiMessage(responseText)
                }
                approvePlan()
            }
            // Structured types are handled above the when{} via early-return
            // in handleStructuredResponse. Listing them here keeps the when
            // exhaustive without re-running the work.
            ParsedResponseType.SUGGESTIONS,
            ParsedResponseType.EVENT_BRIEF,
            ParsedResponseType.VENUE_BRIEF,
            ParsedResponseType.QUICK_FACT,
            ParsedResponseType.SCOPE_REDIRECT -> { /* unreachable */ }
        }
    }

    /**
     * The plan card itself renders a green "Save to My Plans" button, so we
     * no longer surface a duplicate quick-reply chip below the chat. This
     * function still strips any "Looks good" / "add to My Plans" variants
     * Bo might emit on its own — keeps the chip row free of duplicate save
     * affordances regardless of what Bo decides to suggest.
     */
    private fun filterApproveChips(chips: List<String>): List<String> {
        return chips.filter {
            !it.contains("Looks good", ignoreCase = true) &&
                !it.contains("add to My Plans", ignoreCase = true)
        }
    }

    // ── Phase 6 — structured-response handling + draft-tray actions ──

    private suspend fun handleStructuredResponse(parsed: ParsedChatResponse) {
        val json = parsed.structuredJson?.jsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())

        // Bo may emit a short prose intro alongside the terminal tool — shown
        // ONLY when substantive (not a "Let me check…" preamble and not
        // duplicated by the structured tool's own answerLine). Empirical guard:
        // ≤ 240 chars, no leading "Let me / I'll / Searching / etc." patterns.
        // The actual show is DEFERRED to just before the card renders — an
        // eventless brief (below) folds this prose into its answerLine instead,
        // and we must not render it twice.
        val wrapperProse = parsed.text.trim()

        var structured = parseStructuredResponse(parsed.type, json) ?: run {
            // Malformed payload — surface Bo's prose (if acceptable) so the turn
            // isn't a silent dead-end, then end.
            if (wrapperProse.isNotBlank() && isAcceptableWrapperProse(wrapperProse)) {
                addAiMessage(wrapperProse)
            }
            finishTurn(emptyList())
            return
        }

        // Deterministic comprehensiveness: rebuild the suggestion tray from the
        // FULL search pool this turn (bucketed by Bo's groupBy), so the map +
        // carousel show EVERY matching event — not just the handful Bo curated
        // from the 80-row tool cap. Bo still owns the structure (groupBy,
        // headline, footer); the client fills the groups completely.
        (structured as? live.localapp.local.domain.model.BoStructuredResponse.Suggestions)?.let { sug ->
            expandSuggestionsFromPool(sug)?.let { structured = it }
        }

        // Hydrate event_ids referenced by the response so the UI can show
        // titles/venues/times without an additional fetch on render.
        // Cache-first: the events Bo just referenced almost always came from
        // the search it ran THIS turn, so they're already in searchResultCache.
        // Serving from cache avoids a redundant DB round-trip — and on a flaky
        // network that round-trip would return empty, which previously rendered
        // a header ("Upcoming at Fenway Park") with NO event cards beneath it.
        // We only hit the DB for ids the cache is missing.
        val eventIds = collectEventIdsFor(structured)
        var hydrated = if (eventIds.isNotEmpty()) {
            val fromCache = searchResultCache.filter { it.id in eventIds }.associateBy { it.id }
            val missing = eventIds.filter { it !in fromCache }
            val fromDb = if (missing.isNotEmpty()) {
                try { eventRepo.getEventsByIds(missing).associateBy { it.id } } catch (_: Throwable) { emptyMap() }
            } else emptyMap()
            fromCache + fromDb
        } else emptyMap()

        // Venue-brief salvage. Bo sometimes builds a present_venue_brief whose
        // upcomingEventIds resolve to NOTHING — most often because it copied a
        // venue UUID (from a same-turn search_venues call, where rows print as
        // "[uuid] Name | City | type") into the event-id slot, or invented an
        // id. The card then renders its header with zero event rows — the
        // "Upcoming at New Art Center in Newton" ghost the user hit. If THIS
        // turn's search actually surfaced events for that venue, rebuild the
        // brief from searchResultCache by venue-name match so the card is never
        // empty when we genuinely have the data to fill it.
        val vb = structured as? live.localapp.local.domain.model.BoStructuredResponse.VenueBrief
        if (vb != null && hydrated.isEmpty()) {
            val target = vb.venueName.trim().lowercase()
            if (target.isNotEmpty()) {
                val matches = searchResultCache
                    .filter { e ->
                        val vn = e.venueName?.trim()?.lowercase() ?: return@filter false
                        vn == target || vn.contains(target) || target.contains(vn)
                    }
                    .distinctBy { it.id }
                    .sortedBy { it.startAt ?: "" }
                    .take(8)
                if (matches.isNotEmpty()) {
                    structured = vb.copy(upcomingEventIds = matches.map { it.id })
                    hydrated = matches.associateBy { it.id }
                    println("ChatVM: VenueBrief salvage — rebuilt ${matches.size} events for '${vb.venueName}' from cache")
                }
            }
        }

        // Never-empty-brief guard. A Venue/Event brief that resolves to zero
        // renderable events with a blank answerLine would degrade to a bare
        // "Upcoming at <venue>" header — and on iOS the bridge collapses the
        // whole reply to exactly that string, dead-ending the turn (the
        // "Upcoming at Symphony Hall" freeze). Web-discovered events have no
        // event-card path in a brief (save_web_finding hydration is plan-only),
        // so Bo's real answer lives in THIS turn's prose. Fold that prose into
        // answerLine so both platforms show the answer; fall back to a graceful
        // line when Bo wrote no prose either.
        var wrapperProseConsumed = false
        structured = when (val s = structured) {
            is live.localapp.local.domain.model.BoStructuredResponse.VenueBrief -> {
                val hasEvents = s.upcomingEventIds.any { hydrated.containsKey(it) }
                if (!hasEvents && s.answerLine.isNullOrBlank()) {
                    wrapperProseConsumed = wrapperProse.isNotBlank()
                    s.copy(answerLine = wrapperProse.ifBlank {
                        "I couldn't pull up upcoming events for ${s.venueName} right now — want me to widen the search?"
                    })
                } else s
            }
            is live.localapp.local.domain.model.BoStructuredResponse.EventBrief -> {
                val hasEvent = hydrated.containsKey(s.eventId)
                if (!hasEvent && s.answerLine.isBlank()) {
                    wrapperProseConsumed = wrapperProse.isNotBlank()
                    s.copy(answerLine = wrapperProse.ifBlank {
                        "I couldn't pull up the details for that one right now."
                    })
                } else s
            }
            else -> s
        }

        // Deferred wrapper-prose bubble — shown for briefs/trays that carry a
        // short intro, UNLESS it was just folded into an eventless brief's
        // answerLine above (else it would render twice).
        if (!wrapperProseConsumed && wrapperProse.isNotBlank() && isAcceptableWrapperProse(wrapperProse)) {
            addAiMessage(wrapperProse)
        }

        addMessage(
            ChatMessage(
                role = MessageRole.AI,
                content = MessageContent.Structured(
                    response = structured,
                    hydratedEvents = hydrated,
                ),
            )
        )
        finishTurn(emptyList())
    }

    /**
     * Rebuild a [BoStructuredResponse.Suggestions] from the full [turnSearchPool]
     * (every event this turn's search returned), bucketed by Bo's groupBy, so
     * the tray/map is comprehensive instead of Bo's 80-cap-curated handful.
     *
     * Scoping: we read the categories of Bo's OWN picks to learn what the user
     * is after, then fill those categories completely from the pool. Returns
     * null (keep Bo's original) for NONE grouping or when the pool is too thin
     * to improve on.
     */
    /**
     * Interest score for ordering the Bo results tray (higher = show sooner).
     * With no taste signal it ranks by "most prominent / likely-attended":
     * venue `public_interest_rank` + real-happening signals (a real start time,
     * one-time vs recurring venue-hours, has an image, is ticketed). A taste
     * boost lifts the user's engaged categories once we have signal
     * (interactionCount >= 3, the same floor the system prompt uses), and is
     * scaled so a strong taste match can outweigh raw venue prominence.
     */
    private fun scoreForTray(
        ev: live.localapp.local.domain.model.Event,
        rankByName: Map<String, Int>,
    ): Double {
        var s = 0.0
        // Venue prominence (0-98, higher = better). null = unranked (neutral);
        // <=10 is the app's junk floor (community centers, K-12 fields, etc.).
        val rank = rankByName[ev.venueName?.lowercase()?.trim()]
        when {
            rank == null -> {}
            rank <= 10 -> s -= 120.0
            else -> s += rank.toDouble()
        }
        // A real, time-specific happening beats all-day / venue-hours filler.
        if (ev.timeKnown) s += 60.0 else s -= 40.0
        if (ev.category.lowercase() != "always_on") s += 50.0
        if (!ev.imageUrl.isNullOrBlank()) s += 25.0
        if (!ev.ticketUrl.isNullOrBlank() || ev.priceMin != null || ev.priceMax != null) s += 20.0
        // Taste boost — categories the user actually engages with rise.
        val tp = tasteProfile
        if (tp != null && tp.interactionCount >= 3) {
            val catScore = tp.categoryScores[computeEventCategoryKey(ev).first]
                ?: tp.categoryScores[ev.category.lowercase()]
                ?: tp.categoryScores[(ev.subcategory ?: "").lowercase()]
                ?: 0
            s += catScore * 10.0
        }
        return s
    }

    private suspend fun expandSuggestionsFromPool(
        sug: live.localapp.local.domain.model.BoStructuredResponse.Suggestions,
    ): live.localapp.local.domain.model.BoStructuredResponse.Suggestions? {
        val groupBy = sug.groupBy
        val pool = turnSearchPool.distinctBy { it.id }

        // Resolve Bo's picks to learn which categories the user wants, so we
        // fill those completely rather than dumping every category in the pool.
        val boIds = sug.groups.flatMap { it.events }.map { it.eventId }.toSet()
        val boEvents = boIds.mapNotNull { id -> searchResultCache.find { it.id == id } }
        val boCategories = boEvents.map { computeEventCategoryKey(it).first }.toSet()
        val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
        // Days Bo actually grouped/picked — the authoritative intent for a
        // multi-day query. A per-day "next weekend" search (Bo calls
        // search_events once per calendar day) leaves lastSearchStartDate at
        // only the LAST day, so use Bo's picks' full span for BOTH the
        // complete-search window and the day filter below.
        val boDays = boEvents.map { computeEventDayKey(it, tz).first }
            .filterNot { it == "__tbd" }.toSet()
        val query = lastBrowseQuery?.trim()?.takeIf { it.isNotBlank() }
        val kw = query?.lowercase()

        // VENUE-SCOPED QUERY GUARD. When the user asks about ONE specific venue
        // ("what's on at Brookline Booksmith"), Bo searches keyword=<venue name>,
        // so `query` is that venue — NOT a category or date browse. The category
        // expansion below fills Bo's picks' categories from the WHOLE radius,
        // which floods the tray with OTHER venues (the "Upcoming at Brookline
        // Booksmith but showing Old North Church" bug). Detect a venue query and
        // scope the tray to ONLY that venue's events below — skipping the
        // radius/category widening AND the radius drop (a named venue may sit
        // outside the current search radius). Genre keywords ("jazz") are
        // rejected two ways: they resolve to a category, and even when they
        // don't, requiring the keyword to cover most of a venue's name keeps
        // "jazz" from latching onto "Wally's Jazz Cafe".
        val venueScope: String? = kw
            ?.takeIf { it.length >= 3 && categoryKeyForQuery(it) == null }
            ?.let { k ->
                val isVenue = pool.any { ev ->
                    val vn = ev.venueName?.trim()?.lowercase() ?: return@any false
                    when {
                        vn == k -> true
                        // Bo's keyword IS most of a venue's name → venue query.
                        vn.contains(k) -> k.length >= (vn.length * 0.6).toInt()
                        // Stored venue name sits fully inside Bo's longer keyword.
                        k.contains(vn) -> vn.length >= 4
                        else -> false
                    }
                }
                if (isVenue) k else null
            }

        // Radius-scoped CATEGORY-COMPLETE supplemental search. Bo's stratified
        // pool balances across categories, so a single-category query ("arts")
        // under-returns vs Explore. For a single-topic query, re-search that
        // category the way Explore does (ilike), with a high limit, then keep
        // only venues within the user's radius. This is the fix for "Explore
        // finds more than Bo".
        val lat = _uiState.value.currentLat
        val lng = _uiState.value.currentLng
        val radius = _uiState.value.searchRadiusMiles
        // Run for ANY dated query — a single category ("arts") OR a no-category
        // date browse (where the stratified pool over-returns popular categories
        // like music and under-returns arts/comedy). category=null pulls every
        // category in the bbox; we then bucket by category so each is complete.
        // Date window for the category-complete search: cover ALL of Bo's
        // intended days (span of `boDays`), not just lastSearchStartDate — else
        // a multi-day "next weekend" only category-completes the last day.
        val winStart = boDays.minOrNull() ?: lastSearchStartDate?.take(10)
        val winEnd = boDays.maxOrNull() ?: lastSearchEndDate?.take(10)
        val hasDateWindow = winStart != null || lastSearchDateRange != null
        val complete: List<live.localapp.local.domain.model.Event> =
            if (venueScope == null && (query != null || hasDateWindow) && lat != null && lng != null) {
                try {
                    val useCustom = winStart != null
                    val dr = if (useCustom) "custom" else lastSearchDateRange
                    eventRepo.searchEvents(
                        location = null, category = query, dateRange = dr, keyword = null,
                        limit = 1500,
                        startDate = if (useCustom) winStart else lastSearchStartDate,
                        endDate = if (useCustom) winEnd else lastSearchEndDate,
                        userLat = lat, userLng = lng, radiusMiles = radius,
                        useRadiusBbox = true,
                    ).filter { ev ->
                        val vlat = ev.venueLat ?: ev.eventLat
                        val vlng = ev.venueLng ?: ev.eventLng
                        vlat != null && vlng != null &&
                            live.localapp.local.domain.model.distanceMiles(lat, lng, vlat, vlng) <= radius
                    }.also { found ->
                        searchResultCache.addAll(found)
                        found.forEach { boTools.searchResultCache[it.id] = it }
                        println("expandSuggestions: category-complete '${query ?: "<all>"}' -> ${found.size} within ${radius.toInt()}mi")
                    }
                } catch (_: Throwable) { emptyList() }
            } else emptyList()

        // Pool fallback (browse / when the complete search yields nothing):
        // keep events matching Bo's categories or the search keyword.
        val scoped = pool.filter { ev ->
            (boCategories.isNotEmpty() && computeEventCategoryKey(ev).first in boCategories) ||
                (kw != null && (
                    ev.category?.contains(kw, true) == true ||
                    ev.subcategory?.contains(kw, true) == true ||
                    ev.title.contains(kw, true) ||
                    ev.venueName?.contains(kw, true) == true))
        }
        val rawUniverse = if (complete.isNotEmpty()) {
            (complete + scoped + boEvents).distinctBy { it.id }
        } else {
            // No supplemental results — fall back to the pool. Empty scope (pure
            // date browse) means "show everything that day".
            (scoped.ifEmpty { pool } + boEvents).distinctBy { it.id }
        }
        // HARD RADIUS GUARD: this is a radius-scoped MAP search ("Within N
        // miles"), so only surface events we can actually place within the
        // radius. Two failure modes this closes:
        //   1. Coord-bearing events beyond the radius — the stratified RPC's
        //      breadth/niche lanes leak far high-rank rows (Great Barrington in
        //      a 20mi Waltham search).
        //   2. Events with NO usable coordinate — empirically these are
        //      far-flung recurring/regional rows whose "venue" is an org with
        //      no lat/lng (e.g. a Plymouth "First Fridays" or a Pittsfield
        //      "First Fridays at Five" surfacing in a 20mi Waltham search).
        //      They also can't be pinned or centered on the map (tapping their
        //      card left the map frozen), so dropping them fixes that too.
        // Applies even to Bo's own picks. 1.1× buffer for rounding. When we
        // have no user location at all, keep everything (can't filter).
        val maxMi = radius * 1.1
        val universeGeo = rawUniverse.filter { ev ->
            // Never surface no-public-interest events (matches Explore/Places).
            if (ev.subcategory == "no-public-interest") return@filter false
            if (lat == null || lng == null) return@filter true
            val vlat = ev.venueLat ?: ev.eventLat
            val vlng = ev.venueLng ?: ev.eventLng
            vlat != null && vlng != null &&
                live.localapp.local.domain.model.distanceMiles(lat, lng, vlat, vlng) <= maxMi
        }
        // CATEGORY-SCOPE GUARD: when the query named a specific category
        // ("arts"), constrain the WHOLE universe to that tab — Bo's own picks
        // (boEvents) and the scoped pool can inject off-category rows (e.g. a
        // July-4th parade Bo curated under an "arts this weekend" search shows
        // in the Arts result). The DAY/NONE bucketing otherwise never re-filters
        // by category, so the day tabs leaked every category. Uses the same
        // bucketer as the tabs. Skipped for generic date browses (query names
        // no category → intentCategoryKey null → show everything).
        val intentCategoryKey = query?.let { categoryKeyForQuery(it) }
        val universe = when {
            // Venue query: ONLY this venue's events, from Bo's picks + the raw
            // pool (bypassing the radius guard above — a named venue may be
            // outside the radius, and any of Bo's own off-venue picks are
            // dropped here too). No category filtering — a venue's whole slate
            // (readings, tours, kids' events) belongs under "Upcoming at X".
            venueScope != null -> (pool + boEvents).distinctBy { it.id }.filter { ev ->
                if (ev.subcategory == "no-public-interest") return@filter false
                val vn = ev.venueName?.trim()?.lowercase() ?: return@filter false
                vn == venueScope || vn.contains(venueScope) || venueScope.contains(vn)
            }
            intentCategoryKey != null ->
                universeGeo.filter { computeEventCategoryKey(it).first == intentCategoryKey }
            else -> universeGeo
        }
        // Rebuild whenever we have anything to show — the radius guard above
        // must run even when the count didn't grow (to drop Bo's far picks).
        if (universe.isEmpty()) return null

        // DAY/DATE SCOPE GUARD (all groupings): constrain to exactly the day(s)
        // Bo intended. For DAY grouping = the days Bo grouped, so "next weekend
        // (Jul 10-12)" shows ONLY Jul 10-12 — not a tab for every day from now
        // through the weekend (the stratified pool spans ~90 days and DAY
        // grouping previously had no date bound). For CATEGORY/NONE = the
        // implied day(s) of Bo's picks, so a recurring Sunday event can't sit
        // under a "Friday" header. Primary = Bo's picks' days (`boDays`); if Bo
        // had no resolvable picks, fall back to the raw search window.
        val dayScoped: List<live.localapp.local.domain.model.Event> = when {
            boDays.isNotEmpty() ->
                universe.filter { computeEventDayKey(it, tz).first in boDays }
            lastSearchStartDate != null -> {
                val s = lastSearchStartDate!!.take(10)
                val e = (lastSearchEndDate ?: lastSearchStartDate)!!.take(10)
                universe.filter {
                    val k = computeEventDayKey(it, tz).first
                    k != "__tbd" && k in s..e
                }
            }
            else -> universe
        }
        if (dayScoped.isEmpty()) return null

        // RANKING: order each group by an interest score, not chronologically —
        // otherwise whatever starts earliest leads (an all-day "Club Opens at
        // 9 AM" was floating to the front while the evening headliners sank).
        // Default ranks by prominence/likely-attendance (venue
        // public_interest_rank + real-happening signals); a taste boost lifts
        // the user's engaged categories when we have signal. One batch
        // venue-rank query for just the shown set. Ties break chronologically.
        val rankByName = try {
            eventRepo.getVenueRanks(dayScoped.mapNotNull { it.venueName })
        } catch (_: Throwable) { emptyMap() }
        fun trayOrder(list: List<live.localapp.local.domain.model.Event>) =
            list.sortedWith(
                compareByDescending<live.localapp.local.domain.model.Event> { scoreForTray(it, rankByName) }
                    .thenBy { it.startAt ?: "" }
            )

        // NONE grouping (single flat tray — common for "arts this Friday"):
        // one group with every matching event, best-first. (Dedup of the
        // Ticketmaster ticket-product rows is handled at the DATA layer by
        // dedup_events.js — losers are is_active=false, so they never reach
        // here — not with a Bo-only heuristic.)
        if (groupBy == live.localapp.local.domain.model.BoStructuredResponse.Suggestions.GroupBy.NONE) {
            return sug.copy(groups = listOf(
                live.localapp.local.domain.model.BoStructuredResponse.Suggestions.Group(
                    label = sug.groups.firstOrNull()?.label ?: "",
                    events = trayOrder(dayScoped).map { ev ->
                        live.localapp.local.domain.model.BoStructuredResponse.Suggestions.EventRef(ev.id, null)
                    },
                )
            ))
        }

        data class Bucket(val sort: Long, val label: String, val events: MutableList<live.localapp.local.domain.model.Event>)
        val buckets = LinkedHashMap<String, Bucket>()
        val byDay = groupBy == live.localapp.local.domain.model.BoStructuredResponse.Suggestions.GroupBy.DAY
        for (ev in dayScoped) {
            val key: String; val sort: Long; val label: String
            if (byDay) {
                val (k, s, l) = computeEventDayKey(ev, tz); key = k; sort = s; label = l
            } else {
                val (k, p, l) = computeEventCategoryKey(ev); key = k; sort = p.toLong(); label = l
            }
            buckets.getOrPut(key) { Bucket(sort, label, mutableListOf()) }.events += ev
        }

        val newGroups = buckets.values
            .sortedBy { it.sort }
            .map { b ->
                val sortedEvents = trayOrder(b.events)
                live.localapp.local.domain.model.BoStructuredResponse.Suggestions.Group(
                    label = b.label,
                    events = sortedEvents.map { ev ->
                        live.localapp.local.domain.model.BoStructuredResponse.Suggestions.EventRef(
                            eventId = ev.id,
                            rationale = null,
                        )
                    },
                )
            }
        if (newGroups.isEmpty()) return null
        return sug.copy(groups = newGroups)
    }

    /** True when prose alongside a structured terminal tool is short and
     *  doesn't read like a preamble that the searching idiom already
     *  conveyed. Drops the "Let me check…" / "I'll search for…" patterns
     *  the prompt forbids, plus anything > 240 chars (Bo broke BREVITY). */
    private fun isAcceptableWrapperProse(text: String): Boolean {
        if (text.length > 240) return false
        val lower = text.lowercase().trimStart()
        val preambleStarts = listOf(
            "let me", "i'll ", "i'll,", "i will ", "i'm going to", "i'm gonna",
            "i'm checking", "i'm searching", "i'm filtering", "i'm pulling",
            "i'm getting", "i'm looking", "looking for", "looking up",
            "searching for", "searching the", "checking the", "checking for",
            "filtering for", "let's see", "one moment", "give me a second",
            "hold on", "one sec", "i'll look", "i'll search",
        )
        return preambleStarts.none { lower.startsWith(it) }
    }

    private fun parseStructuredResponse(
        type: ParsedResponseType,
        json: kotlinx.serialization.json.JsonObject,
    ): live.localapp.local.domain.model.BoStructuredResponse? = try {
        when (type) {
            ParsedResponseType.SUGGESTIONS -> {
                val groupBy = when (json["groupBy"]?.jsonPrimitive?.contentOrNull) {
                    "day" -> live.localapp.local.domain.model.BoStructuredResponse.Suggestions.GroupBy.DAY
                    "category" -> live.localapp.local.domain.model.BoStructuredResponse.Suggestions.GroupBy.CATEGORY
                    else -> live.localapp.local.domain.model.BoStructuredResponse.Suggestions.GroupBy.NONE
                }
                val groups = (json["groups"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { g ->
                    val obj = g.jsonObject
                    val label = obj["label"]?.jsonPrimitive?.contentOrNull ?: ""
                    val events = (obj["events"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { ev ->
                        val evObj = ev.jsonObject
                        val id = evObj["event_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        live.localapp.local.domain.model.BoStructuredResponse.Suggestions.EventRef(
                            eventId = id.removePrefix("evt:"),
                            rationale = evObj["boRationale"]?.jsonPrimitive?.contentOrNull,
                        )
                    } ?: emptyList()
                    live.localapp.local.domain.model.BoStructuredResponse.Suggestions.Group(label, events)
                } ?: emptyList()
                val footer = json["footer"]?.jsonObject?.let { f ->
                    live.localapp.local.domain.model.BoStructuredResponse.Suggestions.Footer(
                        text = f["text"]?.jsonPrimitive?.contentOrNull,
                        actions = (f["actions"] as? kotlinx.serialization.json.JsonArray)
                            ?.mapNotNull { live.localapp.local.domain.model.BoStructuredResponse.Suggestions.Action.fromString(it.jsonPrimitive.contentOrNull) }
                            ?: emptyList(),
                    )
                }
                live.localapp.local.domain.model.BoStructuredResponse.Suggestions(
                    headline = json["headline"]?.jsonPrimitive?.contentOrNull ?: "",
                    groupBy = groupBy,
                    groups = groups,
                    footer = footer,
                    originalKeyword = lastSearchKeyword,
                )
            }
            ParsedResponseType.EVENT_BRIEF -> live.localapp.local.domain.model.BoStructuredResponse.EventBrief(
                eventId = (json["event_id"]?.jsonPrimitive?.contentOrNull ?: "").removePrefix("evt:"),
                answerLine = json["answerLine"]?.jsonPrimitive?.contentOrNull ?: "",
            )
            ParsedResponseType.VENUE_BRIEF -> live.localapp.local.domain.model.BoStructuredResponse.VenueBrief(
                venueName = json["venue_name"]?.jsonPrimitive?.contentOrNull ?: "",
                answerLine = json["answerLine"]?.jsonPrimitive?.contentOrNull,
                upcomingEventIds = (json["upcomingEventIds"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.removePrefix("evt:") }
                    ?: emptyList(),
            )
            ParsedResponseType.QUICK_FACT -> live.localapp.local.domain.model.BoStructuredResponse.QuickFact(
                text = json["text"]?.jsonPrimitive?.contentOrNull ?: "",
                ctaEventId = json["ctaEventId"]?.jsonPrimitive?.contentOrNull?.removePrefix("evt:"),
            )
            ParsedResponseType.SCOPE_REDIRECT -> live.localapp.local.domain.model.BoStructuredResponse.ScopeRedirect(
                explanation = json["explanation"]?.jsonPrimitive?.contentOrNull ?: "",
                redirectChips = (json["redirectChips"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { c ->
                    val obj = c.jsonObject
                    val label = obj["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val intent = obj["intent"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    live.localapp.local.domain.model.BoStructuredResponse.ScopeRedirect.Chip(label, intent)
                } ?: emptyList(),
            )
            else -> null
        }
    } catch (e: Throwable) {
        println("ChatVM: parseStructuredResponse failed: ${e.message}")
        null
    }

    private fun collectEventIdsFor(r: live.localapp.local.domain.model.BoStructuredResponse): Set<String> = when (r) {
        is live.localapp.local.domain.model.BoStructuredResponse.Suggestions ->
            r.groups.flatMap { g -> g.events.map { it.eventId } }.toSet()
        is live.localapp.local.domain.model.BoStructuredResponse.EventBrief -> setOf(r.eventId)
        is live.localapp.local.domain.model.BoStructuredResponse.VenueBrief -> r.upcomingEventIds.toSet()
        is live.localapp.local.domain.model.BoStructuredResponse.QuickFact -> r.ctaEventId?.let { setOf(it) } ?: emptySet()
        is live.localapp.local.domain.model.BoStructuredResponse.ScopeRedirect -> emptySet()
    }

    /**
     * ❤️ on a result card. Toggles the saved-event (favorite) in state AND
     * server-side (community_interactions) so it lands in the user's Saved
     * Events and survives closing Bo — same store the Home/Places hearts use.
     * Also mirrors the event into the in-chat draft tray so the "build a plan"
     * flow still sees it. Resolving the Event (cache → repo) supplies the
     * category + tray data; the favorite still toggles if resolution fails.
     */
    fun saveEventIfNew(eventId: String) {
        if (eventId.isBlank() || eventId in _uiState.value.favoriteEventIds) return
        _uiState.update { it.copy(favoriteEventIds = it.favoriteEventIds + eventId) }
        viewModelScope.launch {
            val event = searchResultCache.find { it.id == eventId }
                ?: try { eventRepo.getEvent(eventId) } catch (_: Throwable) { null }
            communityInterestRepository.recordFavorite(
                eventId, live.localapp.local.domain.repository.InteractionType.EVENT, event?.category,
            )
            live.localapp.local.observability.Analytics.track(
                live.localapp.local.observability.AnalyticsEvent.eventSaved(eventId = eventId, source = "bo_card"),
            )
        }
    }

    /**
     * Add an event to the draft tray. Idempotent (no-op if already present).
     * Tapping + also saves the event to Saved Events the first time (no-op if
     * already saved) — the only new behavior over the pre-existing tray add.
     */
    fun addToDraftTray(event: live.localapp.local.domain.model.Event) {
        saveEventIfNew(event.id)
        val current = _uiState.value.draftTray
        if (current.any { it.eventId == event.id }) return
        val draft = live.localapp.local.domain.model.DraftTrayEvent(
            eventId = event.id,
            title = event.title,
            startAt = event.startAt,
            // dateDisplay no longer appends ", TBD" for time-unknown events
            // — it's just the day label in that case. substringAfter falls
            // through to empty, which we leave as-is (no "Time TBD" filler).
            timeLabel = if (event.dateDisplay.contains(",")) event.dateDisplay.substringAfter(",").trim() else "",
            dateLabel = event.dateDisplay.substringBefore(",").trim(),
            venueName = event.venueName,
            category = event.category,
            subcategory = event.subcategory,
            imageUrl = event.imageUrl,
            isFree = event.isFree,
        )
        val updated = current + draft
        _uiState.update {
            it.copy(
                draftTray = updated,
                draftTrayChips = live.localapp.local.domain.service.DraftTrayChipGenerator.chipsFor(updated),
            )
        }
    }

    /** Remove an event from the draft tray by id. */
    fun removeFromDraftTray(eventId: String) {
        val updated = _uiState.value.draftTray.filterNot { it.eventId == eventId }
        _uiState.update {
            it.copy(
                draftTray = updated,
                draftTrayChips = live.localapp.local.domain.service.DraftTrayChipGenerator.chipsFor(updated),
            )
        }
    }

    /** Reset the draft tray (called on plan-build success or chat reset). */
    fun clearDraftTray() {
        _uiState.update { it.copy(draftTray = emptyList(), draftTrayChips = emptyList()) }
    }

    /**
     * Record the goal the user picked from the intent sheet on open and have Bo
     * reply with an acknowledgment + qualifying questions. buildSystemPrompt()
     * reads [ChatUiState.boIntent] each turn to steer Bo's framing + planning
     * behavior. Once set the intent sheet stops showing (boIntent is non-null)
     * until the chat is reset.
     */
    fun setBoIntent(intent: BoIntent) {
        if (_uiState.value.boIntent == intent) return
        _uiState.update { it.copy(boIntent = intent) }
        respondToIntent(intent)
    }

    /** Stream Bo's "Great! …" reply to an intent pick. Reuses [welcomeJob] and
     *  joins the prior greeting first so two streams don't clash in
     *  streamingText. Tools are disabled — this is a conversational prompt for
     *  details, not a search. */
    private fun respondToIntent(intent: BoIntent) {
        val prior = welcomeJob
        welcomeJob = viewModelScope.launch {
            // Let the greeting finish first. join() doesn't rethrow the prior
            // job's outcome — it only throws if THIS coroutine is cancelled
            // (e.g. resetChat), which should propagate. Joining also keeps the
            // ordering correct: greeting bubble → user's pick → Bo's "Great!".
            prior?.join()
            // Echo the pick as the user's own bubble, the way it'd read if they
            // had typed it.
            addUserMessage(userIntentMessage(intent))
            _uiState.update { it.copy(isTyping = true, quickReplies = emptyList()) }
            try {
                val systemPrompt = buildSystemPrompt()
                conversationHistory.add(ClaudeMessage.text("user", intentReplyPrompt(intent)))
                val response = sendStreaming(
                    systemPrompt, null, isToolRound = false,
                    model = live.localapp.local.data.remote.ClaudeApiService.MODEL_CHAT,
                )
                val text = claudeApi.extractText(response) ?: defaultIntentReply(intent)
                conversationHistory.removeLastOrNull()
                addAiMessage(text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                addAiMessage(defaultIntentReply(intent))
            }
            _uiState.update { it.copy(isTyping = false, inputEnabled = true) }
        }
    }

    /** The user-facing bubble text for an intent pick — phrased as if the user
     *  said it themselves. */
    private fun userIntentMessage(intent: BoIntent): String = when (intent) {
        BoIntent.FIND_EVENTS -> "I am looking for events"
        BoIntent.CREATE_PLAN -> "I'd like to create a plan that I can reference in my plans"
        BoIntent.BROWSING -> "I'm just browsing"
    }

    private fun intentReplyPrompt(intent: BoIntent): String = when (intent) {
        BoIntent.FIND_EVENTS ->
            "[System: The user just chose 'Find events'. Reply warmly, opening with 'Great!' (or similar), and ask 1-2 brief qualifying questions to narrow down what they want — e.g. what kind of events they're into (music, food, comedy, etc.) and roughly when. Keep it to 1-2 short sentences. Do NOT use tools. NO emoji. Plain text only.]"
        BoIntent.CREATE_PLAN ->
            "[System: The user just chose 'Create a plan'. Reply warmly, opening with 'Great!', and ask 1-2 brief qualifying questions to start the plan — e.g. the occasion or vibe, and what day. Keep it to 1-2 short sentences. Do NOT use tools. NO emoji. Plain text only.]"
        BoIntent.BROWSING ->
            "[System: The user just chose 'I'm just browsing'. Reply warmly, opening with 'Great!', and lightly ask what they're in the mood for, or offer to show what's happening this week. Keep it short and low-pressure, 1-2 short sentences. Do NOT use tools. NO emoji. Plain text only.]"
    }

    private fun defaultIntentReply(intent: BoIntent): String = when (intent) {
        BoIntent.FIND_EVENTS -> "Great! What kind of events are you into — music, food, comedy? And when are you thinking?"
        BoIntent.CREATE_PLAN -> "Great! What's the occasion, and what day works? I'll start pulling things together."
        BoIntent.BROWSING -> "Great! Want me to show you what's happening this week, or are you after something specific?"
    }

    /** Tap a chip / footer action — treat as a structured user message to Bo. */
    fun sendDraftTrayChip(intent: String) {
        if (intent.isBlank()) return
        sendMessage(intent)
    }

    /** Build CTA — ship the whole tray to Bo. */
    fun buildFromDraftTray() {
        val tray = _uiState.value.draftTray
        if (tray.isEmpty()) return
        val eventIds = tray.map { it.eventId }
        // Dismiss the tray immediately so the button doesn't linger while Bo
        // builds — users were tapping it twice thinking the first tap missed.
        clearDraftTray()
        // Route through the SAME client-side confirmation flow iOS uses
        // (buildPlanFromSelections): a clean "Build me a plan with my N picks"
        // bubble plus the "anything else? restaurants?" clarifying step.
        // Previously this sent the raw "Build a plan from these specific
        // event_ids: …" intent via sendMessage, which (a) leaked the internal
        // prompt into the chat as a user bubble (#22) and (b) skipped the
        // clarifying step, going straight to creation (#23).
        buildPlanFromSelections(eventIds)
    }

    private fun finishTurn(chips: List<String>) {
        val quickReplies = chips.map { label ->
            val isApprove = label.contains("Looks good", ignoreCase = true)
            val isBuildFromNarration = label.equals("Build this as a plan", ignoreCase = true)
            val isShowAsTray = label.equals("Show as tappable list", ignoreCase = true)
            QuickReply(
                label = label,
                action = when {
                    isApprove -> "save_plan"
                    isBuildFromNarration -> "build_plan_from_narration"
                    isShowAsTray -> "show_as_tray"
                    else -> label
                },
                isGreen = isApprove || isBuildFromNarration || isShowAsTray,
            )
        }

        _uiState.update {
            it.copy(
                isBusy = false, isTyping = false, isSearching = false,
                inputEnabled = true,
                quickReplies = quickReplies,
            )
        }
    }

    /**
     * Directly approve and save the current plan. Called from the "Save to My
     * Plans" button and the save_plan quick-reply chip so a "Looks good…" user
     * message is NOT added to the chat. Runs the gate check first — if blocked,
     * invokes [onPlanBlocked] so the UI can present the paywall; the plan is
     * resumable (state is left intact so a retry after subscribe works).
     */
    fun approveCurrentPlan() {
        if (_uiState.value.days.isEmpty()) {
            println("approveCurrentPlan: no plan to approve, ignoring")
            return
        }
        approvePlan()
    }

    private fun approvePlan() {
        // Primary gate check happens in the APPROVE branch before Claude's
        // confirmation text is displayed. This is a defensive second check —
        // if it ever fires, still reset busy state so the user can retry.
        val gateCheck = onPlanApprovalCheck
        if (gateCheck != null && !gateCheck()) {
            println("approvePlan: BLOCKED by premium gate (defensive)")
            onPlanBlocked?.invoke()
            finishTurn(filterApproveChips(emptyList()))
            return
        }
        println("approvePlan: gate passed, saving plan")

        viewModelScope.launch {
            val state = _uiState.value

            // Phase 6 — clear the draft tray now that the plan is being
            // saved. The tray's purpose was to accumulate event_ids between
            // turns; once the plan is approved, those IDs are committed
            // into the saved plan and the tray is no longer relevant.
            if (state.draftTray.isNotEmpty()) clearDraftTray()

            // Mark all days approved
            _uiState.update { s ->
                val days = s.days.map { it.copy(isApproved = true) }
                s.copy(days = days, approvedDayCount = days.size)
            }

            // Persist to Supabase
            try {
                val plan = Plan(
                    id = "",
                    title = state.planTitle.ifBlank { "My Plan" },
                    city = state.userPreferences?.city?.replaceFirstChar { it.uppercase() } ?: "Boston",
                    days = state.days.map { day ->
                        PlanDay(
                            label = day.label,
                            shortName = day.shortName,
                            slots = day.slots.map { slot ->
                                PlanSlot(
                                    id = slot.id,
                                    time = slot.time,
                                    emoji = slot.emoji,
                                    name = slot.name,
                                    detail = slot.detail,
                                    price = slot.price,
                                    isFree = slot.isFree,
                                    eventId = slot.eventId,
                                    isCustom = slot.isCustom,
                                    isBoWeb = slot.isBoWeb,
                                    imageUrl = slot.imageUrl,
                                    ticketUrl = slot.ticketUrl,
                                    venueRating = slot.venueRating,
                                    venueAddress = slot.venueAddress,
                                    // venueCity/venueName must carry through — the
                                    // plan-detail subtitle renders "{venueName} · {city}",
                                    // so dropping them leaves Bo-added events with no
                                    // location line (e.g. Boston Family Days).
                                    venueCity = slot.venueCity,
                                    venueName = slot.venueName,
                                    venueLat = slot.venueLat,
                                    venueLng = slot.venueLng,
                                    priceMin = slot.priceMin,
                                    priceMax = slot.priceMax,
                                    reservationUrl = slot.reservationUrl,
                                    sourceUrl = slot.sourceUrl,
                                    venueWebsite = slot.venueWebsite,
                                    venueCategory = slot.venueCategory,
                                )
                            },
                            travel = day.travel.map { PlanTravel(it.mode, it.mins) },
                        )
                    },
                    // Save with creator-only + groupSize=null. The first time
                    // the user opens the plan detail, the GroupSizeSheet
                    // auto-prompt fires and the user picks the real count.
                    // Bo's `attendee_count` on present_plan is intentionally
                    // ignored here — inferring from "parents visiting" or
                    // "double date" gave the user a number they didn't pick
                    // and silently skipped the sheet.
                    attendees = listOf(
                        PlanAttendee(
                            id = "self",
                            name = "You",
                            initials = userPrefsRepo.getLocalFirstName()?.firstOrNull()?.uppercase() ?: "Y",
                            confirmed = true,
                            avatarColor = 0xFFFF6B6B,
                        ),
                    ),
                    createdAt = "",
                    dateDisplay = "",
                    groupSize = null,
                )
                val savedPlanId = planRepo.savePlan(plan)
                if (savedPlanId != null) {
                    // Remember the id/title so the celebration overlay's Share Plan
                    // button can share this exact plan without round-tripping
                    // through the Plans tab first.
                    _uiState.update { it.copy(lastSavedPlanId = savedPlanId, lastSavedPlanTitle = plan.title, lastSavedPlanCity = plan.city) }
                }
                onPlanSaved?.invoke()
                // Badge: track plan created
                val userState = cityToState(state.userPreferences?.city ?: "boston")
                badgeService.recordAction(BadgeAction.PLAN_CREATED, userState)
                // Review prompt: plan saved is a high-value moment
                live.localapp.local.domain.service.ReviewPromptService().onPositiveMoment(
                    live.localapp.local.domain.service.ReviewTrigger.PLAN_SAVED
                )

                // Taste: learn from approved plan
                val userId = userPrefsRepo.getLocalUserId()
                if (userId != null) {
                    val allSlots = state.days.flatMap { it.slots }
                    val allEvents = allSlots.mapNotNull { slot ->
                        slot.eventId?.let { id ->
                            searchResultCache.find { it.id == id } ?: try { eventRepo.getEvent(id) } catch (_: Throwable) { null }
                        }
                    }
                    try {
                        tasteRepo.recordPlanApproval(userId, allSlots, allEvents)
                        tasteProfile = tasteRepo.getTasteProfile(userId)
                    } catch (_: Throwable) {}
                }
            } catch (e: Throwable) {
                println("Failed to save plan: ${e.message}")
            }

            _uiState.update { it.copy(isBusy = false, isTyping = false, isSearching = false, showPlanSuccess = true) }
        }
    }

    fun clearSuccessState() {
        if (_uiState.value.showPlanSuccess) {
            _uiState.update { it.copy(showPlanSuccess = false) }
            resetChat()
        }
    }

    /**
     * Share the plan that triggered the current success overlay. Looks up its
     * share token and hands the system share sheet a real URL — previously the
     * Share Plan button on the celebration just cleared the overlay, which
     * looked like the chat had taken over.
     */
    fun shareLastSavedPlan() {
        val state = _uiState.value
        val planId = state.lastSavedPlanId ?: return
        val title = state.lastSavedPlanTitle.ifBlank { "My Plan" }
        val city = state.lastSavedPlanCity
        viewModelScope.launch {
            val token = try { planRepo.getShareToken(planId) } catch (_: Throwable) { null } ?: planId
            val text = if (city.isNotBlank()) "$title — $city" else title
            live.localapp.local.platform.presentShareSheet(text, "https://localapp.live/p/$token")
        }
    }

    fun dismissPlanSuccess(navigateToPlans: Boolean) {
        _uiState.update { it.copy(showPlanSuccess = false) }
        if (navigateToPlans) {
            viewModelScope.launch {
                _navigateToPlans.send(Unit)
            }
        }
        resetChat()
    }


    // ── DB Resolution ──

    /**
     * Resolve a slot against the local DB.
     * Claude now uses real event IDs from search results, so we just look up by ID.
     * Returns null if the event_id doesn't resolve (Claude hallucinated it).
     */
    private suspend fun resolveSlotFromDb(slot: ItinerarySlot): ItinerarySlot? {
        // Custom items the user added — always keep
        if (slot.isCustom) return slot

        // No event_id — could be a venue from recommend_and_save_venue (real name) or filler
        if (slot.eventId == null) {
            val name = slot.name.lowercase().trim()
            val fillerNames = listOf("dinner", "lunch", "brunch", "drinks", "coffee", "custom dinner", "breakfast", "food", "meal")
            val isFillerName = fillerNames.any { name == it || name == "custom $it" }
            if (!isFillerName && name.length > 2) {
                // Real venue name from recommend_and_save_venue — keep it as-is
                println("resolveSlotFromDb: keeping venue slot '${slot.name}' (no event_id, real name)")
                return slot
            }
            // Filler name — drop it
            println("resolveSlotFromDb: dropping filler slot '${slot.name}' — no event_id, filler name")
            return null
        }

        val event = eventRepo.getEvent(slot.eventId)
        if (event == null) {
            println("resolveSlotFromDb: dropping slot '${slot.name}' — event_id '${slot.eventId}' not found in DB")
            return null
        }

        // For flexible/always-on/date-only events, keep Claude's chosen time.
        // For fixed-time events, use the real event time.
        val isMidnightUtc = event.startAt?.contains("T00:00:00") == true
        val isFlexible = event.category == "always_on" || event.startAt == null || isMidnightUtc || !event.timeKnown
        val realTime = if (isFlexible) {
            slot.time // Keep whatever time Claude picked — midnight UTC means no specific time
        } else {
            // Extract local time from the event's start_at
            try {
                val instant = kotlinx.datetime.Instant.parse(event.startAt!!)
                val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                val hour = local.hour
                val minute = local.minute
                val amPm = if (hour < 12) "AM" else "PM"
                val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
                if (minute == 0) "$displayHour $amPm" else "$displayHour:${minute.toString().padStart(2, '0')} $amPm"
            } catch (_: Exception) {
                event.dateDisplay.substringAfter(", ", "").ifBlank { slot.time }
            }
        }

        // Use DB truth for name/time/price, but keep Claude's detail (has useful context)
        val dbDetail = "${event.venueName ?: ""} · ${event.venueCity}"
        return slot.copy(
            eventId = event.id,
            name = event.title,
            time = realTime,
            detail = if (slot.detail.isNotBlank()) slot.detail else dbDetail,
            price = event.priceDisplay,
            imageUrl = event.imageUrl ?: slot.imageUrl,
            isFree = event.isFree,
            ticketUrl = event.ticketUrl,
        )
    }

    /**
     * Validate that each event slot is on the correct day.
     * If the day label says "Saturday, March 28" but the event's start_at is on a different date, drop it.
     */
    private suspend fun validatePlanDates(days: List<ItineraryDay>): List<ItineraryDay> {
        return days.map { day ->
            val validSlots = day.slots.filter { slot ->
                val eventId = slot.eventId ?: return@filter true // venues/custom always valid
                val event = eventRepo.getEvent(eventId) ?: return@filter false // event not found — drop
                val startAt = event.startAt ?: return@filter true // no date — flexible, keep
                if (event.category == "always_on") return@filter true // flexible event

                // Extract month+day from day label like "Saturday, March 28" or "Sat Mar 28"
                val dayDate = extractDateFromLabel(day.label)
                if (dayDate == null) return@filter true // can't parse label, keep

                // Extract date from event's start_at (ISO format like "2026-03-28T20:00:00Z")
                try {
                    val instant = kotlinx.datetime.Instant.parse(startAt)
                    val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                    val eventMonth = local.monthNumber
                    val eventDay = local.dayOfMonth
                    if (dayDate.first == eventMonth && dayDate.second == eventDay) {
                        true
                    } else {
                        println("validatePlanDates: REMOVING '${slot.name}' from '${day.label}' — event is on ${eventMonth}/${eventDay}, not ${dayDate.first}/${dayDate.second}")
                        false
                    }
                } catch (e: Exception) {
                    println("validatePlanDates: couldn't parse event date for '${slot.name}': ${e.message}")
                    true // keep if we can't parse
                }
            }
            day.copy(slots = validSlots)
        }
    }

    /**
     * Stamp day labels as sequential calendar days from day 1's date. Bo
     * routinely hallucinates the date for days 3+ in multi-day plans —
     * "Day 3 of 6: Friday, May 15" when day 1 was Thursday, May 15 (so
     * day 3 should be Saturday, May 17). Venue-only days (no event_id
     * anchor) bypass `validatePlanDates`'s event-based correction, so we
     * fix it here by treating the first day's date as authoritative and
     * assigning day index N → (firstDate + N days).
     *
     * If the first day's label can't be parsed (e.g. "Day 1" placeholder),
     * the plan is returned unchanged.
     */
    private fun enforceSequentialDayLabels(days: List<ItineraryDay>): List<ItineraryDay> {
        if (days.size <= 1) return days
        val first = days.firstOrNull() ?: return days
        val md = extractDateFromLabel(first.label) ?: return days

        val today = kotlin.time.Clock.System.now()
            .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
        val year = if (md.first < today.monthNumber) today.year + 1 else today.year
        val startDate: kotlinx.datetime.LocalDate = try {
            kotlinx.datetime.LocalDate(year, md.first, md.second)
        } catch (_: Exception) { return days }

        val monthFull = listOf("January", "February", "March", "April", "May", "June",
                                "July", "August", "September", "October", "November", "December")
        val dowFull = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

        return days.mapIndexed { i, day ->
            val expected: kotlinx.datetime.LocalDate =
                startDate.plus(i, kotlinx.datetime.DateTimeUnit.DAY)
            val expectedLabel = "${dowFull[expected.dayOfWeek.ordinal]}, ${monthFull[expected.monthNumber - 1]} ${expected.dayOfMonth}"
            val expectedShort = dowFull[expected.dayOfWeek.ordinal].take(3).uppercase()
            if (day.label != expectedLabel) {
                println("enforceSequentialDayLabels: day $i '${day.label}' → '$expectedLabel'")
                day.copy(label = expectedLabel, shortName = expectedShort)
            } else day
        }
    }

    /**
     * Strip duplicate slots across an entire plan. Claude's NO DUPLICATES prompt
     * rule gets ignored often enough on multi-day plans that we enforce it
     * in code. First occurrence wins; later duplicates are dropped (days can
     * end up shorter than what Claude returned).
     *
     * Matching keys:
     * - event_id (exact)
     * - venue name (trimmed + lowercased)
     */
    private fun removeDuplicateSlots(days: List<ItineraryDay>): List<ItineraryDay> {
        // Diagnostic: log the input plan so we can see what Claude actually
        // produced before any dedup runs.
        days.forEachIndexed { i, day ->
            println("removeDuplicateSlots[in]: day $i '${day.label}' slots=${day.slots.map { "${it.name}|eid=${it.eventId}" }}")
        }

        // Normalized identity key for a slot. event_id wins when present; else a
        // punctuation/suffix-stripped venue name so "Toro" / "toro" /
        // "Toro - South End" / "Toro, Boston" all collapse to one key.
        fun slotKey(slot: ItinerarySlot): String? {
            slot.eventId?.let { return "eid:$it" }
            val nameKey = slot.name
                .trim()
                .lowercase()
                .substringBefore(" - ")
                .substringBefore(" – ")
                .substringBefore(", ")
                .filter { it.isLetterOrDigit() || it == ' ' }
                .trim()
            return if (nameKey.isNotBlank()) "name:$nameKey" else null
        }

        // Pass 1 — WITHIN-DAY dedup only. The seen-set resets per day, so a
        // venue legitimately revisited on a DIFFERENT day of a single-
        // destination trip (e.g. Cisco Brewers on both Day 1 and Day 3 of a
        // Nantucket weekend) is KEPT. The previous implementation shared one
        // set across all days, which stripped Day 3's slots as "duplicates"
        // and then dropped the emptied day — silently collapsing a 3-day trip
        // to 2 ("DAY 1 OF 2"). A day is never emptied here unless it itself
        // listed the same venue twice.
        val withinDayDeduped = days.map { day ->
            val seen = mutableSetOf<String>()
            val kept = day.slots.filter { slot ->
                val key = slotKey(slot) ?: return@filter true
                if (key in seen) {
                    println("removeDuplicateSlots: dropping within-day duplicate '${slot.name}' from '${day.label}'")
                    false
                } else {
                    seen.add(key)
                    true
                }
            }
            day.copy(slots = kept)
        }

        // Pass 2 — CROSS-DAY near-duplicate detection. The only cross-day case
        // we still collapse is Claude lazily emitting a near-identical slate on
        // multiple days (the original reason this function existed). A later
        // day is dropped ONLY when it overlaps an earlier surviving day by
        // >= 80% in BOTH directions — i.e. they're essentially the same day.
        // Sharing one or two venues is no longer enough to nuke a day, and a
        // day the user explicitly asked for is never dropped just because some
        // of its slots were removed.
        val overlapThreshold = 0.8
        val result = mutableListOf<ItineraryDay>()
        val keptKeySets = mutableListOf<Set<String>>()
        withinDayDeduped.forEach { day ->
            val keys = day.slots.mapNotNull { slotKey(it) }.toSet()
            // Empty / unidentifiable day — keep it. Never silently drop a day
            // the user requested; surfacing an empty day is the caller's job.
            if (keys.isEmpty()) {
                result.add(day)
                keptKeySets.add(keys)
                return@forEach
            }
            val nearDup = keptKeySets.any { prior ->
                if (prior.isEmpty()) return@any false
                val overlap = keys.count { it in prior }
                overlap.toDouble() / keys.size >= overlapThreshold &&
                    overlap.toDouble() / prior.size >= overlapThreshold
            }
            if (nearDup) {
                println("removeDuplicateSlots[out]: dropping near-duplicate day '${day.label}' — >=${(overlapThreshold * 100).toInt()}% slot overlap with an earlier day (Claude repeated the same slate)")
            } else {
                result.add(day)
                keptKeySets.add(keys)
            }
        }
        return result
    }

    /** Fetch weather forecasts for each day in the current plan. */
    private fun fetchWeatherForPlan(days: List<ItineraryDay>) {
        viewModelScope.launch {
            val todayDate = kotlin.time.Clock.System.now().toString().take(10)
            val todayLocal = try {
                val now = kotlinx.datetime.Instant.fromEpochMilliseconds(kotlin.time.Clock.System.now().toEpochMilliseconds())
                now.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
            } catch (_: Exception) { return@launch }

            val weatherMap = mutableMapOf<Int, live.localapp.local.data.remote.WeatherForecast>()
            for ((index, day) in days.withIndex()) {
                // Get coordinates from first slot with lat/lng
                val slot = day.slots.firstOrNull { it.venueLat != null && it.venueLng != null } ?: continue
                val lat = slot.venueLat ?: continue
                val lng = slot.venueLng ?: continue

                // Derive ISO date from day label
                val dateMonthDay = extractDateFromLabel(day.label) ?: continue
                val year = if (dateMonthDay.first < todayLocal.monthNumber) todayLocal.year + 1 else todayLocal.year
                val isoDate = "$year-${dateMonthDay.first.toString().padStart(2, '0')}-${dateMonthDay.second.toString().padStart(2, '0')}"

                val forecast = try { weatherService.getDayForecast(lat, lng, isoDate) } catch (_: Throwable) { null }
                if (forecast != null) weatherMap[index] = forecast
            }
            if (weatherMap.isNotEmpty()) {
                _uiState.update { it.copy(weather = weatherMap) }
            }
        }
    }

    /** Extract month and day from a day label like "Saturday, March 28" -> Pair(3, 28) */
    private fun extractDateFromLabel(label: String): Pair<Int, Int>? {
        val monthNames = mapOf(
            "january" to 1, "february" to 2, "march" to 3, "april" to 4,
            "may" to 5, "june" to 6, "july" to 7, "august" to 8,
            "september" to 9, "october" to 10, "november" to 11, "december" to 12,
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4,
            "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9,
            "oct" to 10, "nov" to 11, "dec" to 12,
        )
        val lower = label.lowercase()
        for ((name, num) in monthNames) {
            if (lower.contains(name)) {
                // Find the day number after the month name
                val afterMonth = lower.substringAfter(name).trim().trimStart(',').trim()
                val dayNum = afterMonth.takeWhile { it.isDigit() }.toIntOrNull()
                if (dayNum != null) return Pair(num, dayNum)
            }
        }
        return null
    }

    private suspend fun resolveDayFromDb(day: ItineraryDay): ItineraryDay {
        val seenEventIds = mutableSetOf<String>()
        val resolvedSlots = day.slots.mapNotNull { resolveSlotFromDb(it) }
            .filter { slot ->
                // Deduplicate by eventId
                val eid = slot.eventId ?: return@filter true
                seenEventIds.add(eid) // returns false if already present
            }
            .map { it.copy(time = normalizeTimeDisplay(it.time)) }
        return day.copy(slots = sortSlotsByTime(resolvedSlots))
    }

    /** Normalize all time formats to "3:30 PM" style — consistent across events and venues */
    private fun normalizeTimeDisplay(time: String): String {
        if (time.isBlank()) return time
        val mins = parseTimeForSort(time)
        if (mins == Int.MAX_VALUE) return time
        val hour24 = mins / 60
        val minute = mins % 60
        val amPm = if (hour24 < 12) "AM" else "PM"
        val hour12 = if (hour24 == 0) 12 else if (hour24 > 12) hour24 - 12 else hour24
        return if (minute == 0) "$hour12 $amPm" else "$hour12:${minute.toString().padStart(2, '0')} $amPm"
    }

    /**
     * Sort slots chronologically, treating pre-dawn times (before 6 AM) as
     * late-night continuations of an evening plan rather than the start of the
     * day. A 12 AM "late-night spot" should land AFTER a 10:30 PM dinner, not
     * before a 7 PM cocktail.
     */
    private fun sortSlotsByTime(slots: List<ItinerarySlot>): List<ItinerarySlot> {
        if (slots.isEmpty()) return slots
        val sortable = slots.mapNotNull { s ->
            val m = parseTimeForSort(s.time)
            if (m == Int.MAX_VALUE) null else s to m
        }
        val eveningPresent = sortable.any { it.second >= 17 * 60 }
        return slots.sortedBy { s ->
            val m = parseTimeForSort(s.time)
            if (m == Int.MAX_VALUE) Int.MAX_VALUE
            else if (eveningPresent && m < 6 * 60) m + 24 * 60
            else m
        }
    }

    /** Parse time string like "7:00 PM" or "14:00" to minutes for sorting */
    private fun parseTimeForSort(time: String): Int {
        if (time.isBlank()) return Int.MAX_VALUE
        val clean = time.trim().uppercase()
        return try {
            if (clean.contains("AM") || clean.contains("PM")) {
                val parts = clean.replace("AM", "").replace("PM", "").trim().split(":")
                var hour = parts[0].trim().toInt()
                val min = parts.getOrNull(1)?.trim()?.toInt() ?: 0
                if (clean.contains("PM") && hour != 12) hour += 12
                if (clean.contains("AM") && hour == 12) hour = 0
                hour * 60 + min
            } else {
                val parts = clean.split(":")
                parts[0].toInt() * 60 + (parts.getOrNull(1)?.toInt() ?: 0)
            }
        } catch (_: Exception) {
            Int.MAX_VALUE
        }
    }

    // ── System Prompt ──

    @Suppress("unused")
    private var consecutiveQuestionCount = 0 // legacy, kept for field ordering

    private fun geocodeCity(city: String): Pair<Double, Double>? =
        live.localapp.local.data.bo.geocodeCity(city)

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

    /**
     * Classify the user's last message into "plan" vs "info" intent.
     * Plan = user wants a buildable itinerary; info = user wants
     * quick answers / discovery. The system prompt branches on this so
     * info queries don't carry the full plan-building scaffolding.
     */
    private fun detectIntent(userText: String): String {
        val lower = userText.lowercase()
        val planSignals = listOf(
            "plan", "build", "itinerary", "weekend", "vacation", "trip",
            "date night", "tonight", "tomorrow night", "this weekend",
            "for tomorrow", "schedule", "make a", "put together", "for us",
        )
        return if (planSignals.any { lower.contains(it) }) "plan" else "info"
    }

    /**
     * Detect a pure-INFORMATION request (vs an event search). Used only to
     * UNLOCK Anthropic web_search for the turn — it never forces a web call
     * (the system prompt's INFORMATION VS EVENTS carve-out decides that) and it
     * stays gated behind [fetchBoWebBudget]. Catches both the one-shot info
     * question ("Dorney Park hours", "top spots for tourists") and the user's
     * "just the info" reply to Bo's events-vs-info clarifier.
     */
    private fun isInfoOnlyIntent(userText: String): Boolean {
        val lower = userText.lowercase()
        // "just the hours" / "only the info" / "just the spots" — the info-branch reply.
        if (Regex("\\b(just|only)\\b.{0,16}\\b(info|information|hours|spots|places|facts|details)\\b").containsMatchIn(lower)) return true
        val infoSignals = listOf(
            "hours", "what time", "opening time", "open today", "is it open", "are they open",
            "top spots", "best places", "best spots", "tourist", "tourists",
            "hottest restaurant", "tell me about", "info about", "information about",
        )
        return infoSignals.any { lower.contains(it) }
    }

    /**
     * Dining/food-and-drink discovery intent ("good restaurants nearby",
     * "where can I eat", "brunch spots", "cool bars"). For these, web search is
     * ALWAYS unlocked — our DB may carry some restaurants, but it will never
     * have more than the open web, so a dining ask should be allowed to reach
     * web_search even when the local DB returns hits (unlike the generic
     * empty-DB gate). Kept loose on purpose: a false positive only adds the
     * web_search tool to the round (the prompt still decides whether to call
     * it), it never forces a search.
     */
    private fun isDiningIntent(userText: String): Boolean {
        val lower = userText.lowercase()
        val diningSignals = listOf(
            "restaurant", "dining", "where to eat", "where can i eat", "places to eat",
            "good eats", "food", "eat", "foodie", "brunch", "breakfast", "lunch", "dinner",
            "bar", "pub", "cafe", "café", "coffee", "bakery", "diner",
            "brewery", "breweries", "brewpub", "winery", "wineries", "distillery",
            "speakeasy", "cocktail", "rooftop", "sushi", "pizza", "tacos", "ramen",
            "steakhouse", "oyster",
        )
        return diningSignals.any { lower.contains(it) }
    }

    private fun buildSystemPrompt(): String {
        val state = _uiState.value

        // Anchor-event context: when the user just tapped a saved event card
        // ("build a plan around X"), the visible message stays clean and
        // human ("I'd like to build a plan around 'X'"). The event_id is
        // pinned here, in the system prompt, so Bo uses the exact id without
        // surfacing it in the chat transcript.
        val anchorContext = state.pendingAnchorEventId?.let { id ->
            val title = state.pendingAnchorEventTitle ?: ""
            """
ANCHOR EVENT (use this as the centerpiece of the plan you're about to build):
- event_id: evt:$id
- title: "$title"
- The user reached this chat by tapping their saved event. Use the literal event_id "$id" (no `evt:` prefix) in one of the present_plan slots — do NOT call search_events to find it again, do NOT substitute the venue. Build the rest of the day around this anchor (pre-event meal, post-event drinks, etc.) using your usual planning rules.
            """.trimIndent()
        } ?: ""

        val currentPlanContext = if (state.days.isNotEmpty()) {
            val dayDescriptions = state.days.mapIndexed { i, day ->
                val approved = if (day.isApproved) " [APPROVED]" else ""
                val slots = if (day.slots.isEmpty()) "  (no slots yet)"
                else day.slots.joinToString("\n") { s ->
                    val ref = when {
                        s.eventId != null -> " [event_id: ${s.eventId}]"
                        !s.isCustom -> " [venue: ${s.name}]"
                        else -> " [custom]"
                    }
                    "  ${s.time} ${s.name} — ${s.detail} (${s.price})$ref${if (s.isCustom) " [CUSTOM]" else ""}"
                }
                "Day ${i + 1} (index $i): ${day.label}$approved\n$slots"
            }.joinToString("\n\n")
            """
CURRENT PLAN (title: "${state.planTitle}"):
$dayDescriptions
Current day: Day ${state.currentDayIndex + 1}
Approved: ${state.approvedDayCount}/${state.days.size}
            """.trimIndent()
        } else ""

        // Session-goal steer from the intent sheet the user picked on open.
        // Shapes the framing copy + whether saving an event auto-drafts a plan
        // (CREATE_PLAN) or waits for the user to ask (FIND_EVENTS / BROWSING).
        val intentContext = when (state.boIntent) {
            BoIntent.FIND_EVENTS -> """
SESSION GOAL — FIND EVENTS:
The user opened Bo to find events, not (yet) to build a plan. When you present results, your intro should land like: "Here are the events you're looking for. If you'd like, I can create a plan for you to easily reference later and share with friends — just say the word!" Do NOT start drafting a plan on your own. Only build a plan if the user explicitly asks; when they do, first confirm which of the events they saved should go in.
            """.trimIndent()
            BoIntent.CREATE_PLAN -> """
SESSION GOAL — CREATE A PLAN:
The user opened Bo to build a plan. When you present results, frame it like: "Here are the events you're looking for. I'll start drafting a plan that includes any of the events you save." Treat saved events as picks for the plan you're actively assembling and proceed with your normal planning flow.
            """.trimIndent()
            BoIntent.BROWSING -> """
SESSION GOAL — JUST BROWSING:
The user is just browsing. Keep it light — present results with a simple intro like "Here are the events you're looking for." Do NOT push plan creation or offer it unprompted. Only build a plan if the user explicitly asks; when they do, confirm which saved events to include first.
            """.trimIndent()
            null -> ""
        }

        val prefsContext = _uiState.value.userPreferences?.let { prefs ->
            val cats = prefs.selectedCategories.joinToString(", ")
            val venues = prefs.selectedVenues.joinToString(", ")
            val neighborhoods = prefs.selectedNeighborhoods.joinToString(", ")
            """
USER PREFERENCES:
Favorite categories: ${cats.ifEmpty { "none set" }}
Favorite venues: ${venues.ifEmpty { "none set" }}
Favorite neighborhoods: ${neighborhoods.ifEmpty { "none set" }}
Prioritize events and venues matching these preferences.
            """.trimIndent()
        } ?: ""

        val tasteContext = tasteProfile?.let { taste ->
            if (taste.interactionCount < 3) return@let ""
            val topCuisines = taste.topN(taste.cuisineScores, 5).joinToString(", ")
            val topCategories = taste.topN(taste.categoryScores, 5).joinToString(", ")
            val topNeighborhoods = taste.topN(taste.neighborhoodScores, 3).joinToString(", ")
            val priceRange = "$${taste.priceComfort.avgMin.toInt()}-${taste.priceComfort.avgMax.toInt()}"
            val timePrefs = taste.topN(taste.timePreferences, 2).joinToString(", ")
            """
LEARNED TASTE (from ${taste.interactionCount} interactions):
Cuisines: ${topCuisines.ifEmpty { "exploring" }}
Categories: ${topCategories.ifEmpty { "varied" }}
Neighborhoods: ${topNeighborhoods.ifEmpty { "no preference" }}
Price comfort: $priceRange per person (${(taste.priceComfort.freeRatio * 100).toInt()}% free events)
Preferred times: ${timePrefs.ifEmpty { "flexible" }}
Weight these but still surprise them occasionally.
            """.trimIndent()
        } ?: ""

        return """
You are Bo, an AI assistant inside a local events and dining app called Local. Be warm, opinionated, decisive. If a user calls you "Yocal", just continue — that was the previous name.

ABSOLUTE RULES (highest priority — these override every other rule and example below):
0. NO EMOJI. ZERO emoji characters in any response — not in greetings, not in tool result `text` fields, not in headlines, not in slot names or details, NOWHERE. This includes faces (🙂 😊 🤖), gestures (👋 🙌 🙏), hearts (❤️ 💕), sparkles (✨), arrows (↕️ ➡️), symbols (✓ ✅ ⭐), and ALL ZWJ-sequence variants (🙂‍↕️). The iOS rendering layer cannot draw above-BMP code points and they show up as box-question-marks. Write plain text only. If you find yourself reaching for an emoji to soften a greeting, just don't — natural prose works fine.
1. STAY IN SCOPE. Your scope is LOCAL: (a) cities/states and the places in them, (b) venues/places, (c) events and things to do. You help users discover events/venues, build plans, AND answer factual questions about a place or venue when they ask (hours, "top spots", "best places", what a venue is, neighborhood info) — see INFORMATION VS EVENTS below for how. You do NOT engage with anything OUTSIDE those three buckets: pure trivia/biography ("when did the Beatles break up"), general history/science, math, code, recipes, debates, therapy, news, sports scores, jokes-on-demand. For a message outside all three buckets, call present_scope_redirect with one redirect chip — do not free-text an answer, do not "just this once." A question about a real city/state, a venue/place, or events/things to do is IN scope even if it's "just information" — handle it, don't redirect it.
2. NEVER PRESENT AN EVENT ON THE WRONG DAY. The day a user sees an event under (group label, plan slot's day, "Next: <date>" line, anything) MUST equal the calendar day of that event's `start_at` in the user's local timezone. If you cannot verify the day, do NOT include the event. Never bucket a Friday event under a Thursday or Saturday label. Never say "Next: May 9" for an event whose local start_at is May 8.
3. PROXIMITY FIRST. Default to events closest to the user. Search at the user's coords first; only widen the radius if the first pass returns < 3 results AND the user has NOT named a different city/town. If the user did name a location ("Cape Cod", "Concord"), search there — never silently fall back to the user's coords on a UNRESOLVED_LOCATION signal, never substitute a different city.
4. NEVER MISCATEGORIZE. An "outdoor events" group must contain only outdoor events; an "arts" group must contain only arts; etc. Before submitting present_suggestions, check every event in every group against its label and DROP any event whose `category`/`subcategory` doesn't fit. If that empties a group, drop the group. Better to return fewer accurate picks than to pad with unrelated events. The client also re-buckets by actual category/day, but mismatches in your JSON are a bug — fix it on your side too.
5. AFTER A SUCCESSFUL SEARCH, PRESENT — NEVER ASK A CLARIFYING QUESTION. If search_events (or search_venues) returned ≥ 3 results, your VERY NEXT response MUST be a structured tool call: present_suggestions for browse intents, present_event_brief / present_venue_brief / present_quick_fact for narrow factual ones. NEVER respond with `type: chat` and a vibe/category clarifier ("what kind of music?", "live or theatre?", "concert or comedy?") after a search returned results — the user picks by tapping the tray, not by answering you. The ONLY chat-with-question response permitted after a successful search is when the search returned 0-2 results AND you have already attempted ONE broadened retry that also returned < 3.
6. NEVER USE "Other" OR "Upcoming" AS A present_suggestions GROUP LABEL. The DB's `category="other"` and `category="upcoming"` are classifier residue, not real domains — the events inside are book talks, jazz nights, exhibits, lectures, drop-ins, community classes that just weren't classified. When you'd otherwise label a group "Other"/"Upcoming", instead inspect the events' subcategory/title and pick a concrete label: Music, Comedy, Theatre, Arts, Sports, Food, Outdoors, Family, Lectures & Book Talks, Exhibits, Community, Drop-ins, Tours, Workshops. If a bucket is genuinely heterogeneous, split it into 2-3 narrower groups by subcategory or DROP it entirely. Better 3 sharp groups than 4 with an "Other" filler.
7. PRESENT_SUGGESTIONS QUANTITY (hard rule, applies whenever you call present_suggestions). DEFAULT TO COMPREHENSIVENESS — the results render on a MAP where every event is a pin, so the user expects to see essentially ALL matching events, not a curated handful. For BROWSING-BROADLY queries — a date with no narrow filter ("things to do May 15", "what's happening Friday"), a vibe ("fun things tonight"), or any open-ended discovery — pack each group as densely as the results allow (10+ events per group is good) and use 4-8 groups total. For CATEGORY queries ("music this week", "comedy", "sports", "food & drink", "what concerts are happening") — INCLUDE EVERY matching event search_events returned for that category, grouped by day. Do NOT cap a category at 3-5 per day; if the DB has 25 music events tomorrow, the tray must show all 25. The user asked for that whole category and wants to see the full picture on the map. NEVER cherry-pick 2-3 per group when more exist. The ONLY time a small count (3-5) is acceptable is a genuinely NARROW ask — a single named venue ("what's at the Sinclair") or one specific artist/team. When in doubt, lean toward MORE events, not fewer.

BREVITY (hard rule, mobile chat):
- Non-plan responses: 1-3 sentences max. No multi-paragraph essays. No bullet lists in chat — surface choices via plan cards, not prose.
- One question at a time. Never stack two clarifying questions in the same response.
- If you can search and just deliver an answer, do that instead of asking another question.

SEARCH-FIRST (hard rule):
- When the user names ANY intent — an activity ("dancing", "outdoors", "free things"), a venue ("Sundancer's"), a category ("music"), a date ("Tuesday", "May 15", "this weekend"), a date range ("May 13-16", "parents are coming next weekend"), a vibe ("cozy") — your VERY NEXT action is a tool call. Do NOT ask "what kind of music?" or "live music or just outdoors?" or "want me to add meals?" before searching.
- Search broadly on partial info, deliver something real, let the user refine. Search is fast (<1s); conversation round-trips are 5-10s. Don't gatekeep.
- Do NOT ask any follow-up question before searching. Group size in particular is collected from the plan detail UI, not in chat — never ask "how many?".
- ONE CARVE-OUT: see LOCATION HANDLING / CASE C — when the user did NOT name a location at all, you MAY ask the strict-vs-flexible question once before searching. That is the only pre-search question allowed. After they answer, search immediately; don't ask anything else.
- DATE-RANGE QUERIES (critical, common failure mode): When the user names a date range without a category ("parents visiting May 13-16", "free this weekend", "next Saturday"), call search_events ONCE PER DAY in parallel — pass start_date and end_date set to that day's calendar date — then call present_suggestions ONCE with groupBy="day", merging the per-day results into one tray. Do NOT ask about vibe, meals, or anything else first. The user picked dates themselves; they want to see what's happening on those dates, then refine by tapping. The meal question (if needed) belongs in DRAFT TRAY HANDOFF, never before the tray.
- AFTER A SUCCESSFUL SEARCH, PRESENT — DON'T ASK, DON'T NARRATE (extension of search-first):
  - If search_events returned ≥ 3 results (anywhere — first call, retry, widened search, doesn't matter), your VERY NEXT action is present_suggestions with those results. Do NOT ask "what kind of vibe?" / "are you looking for X or Y?" / "concert or comedy?" / "want meals too?" as a follow-up turn. The user will refine by tapping or typing — you don't need a clarifier.
  - MULTI-SEARCH TURNS (critical, recurring failure mode): When you ran multiple search_events calls in one turn (e.g. you tried Brookline, Newton, then widened to Boston), the SAME RULE applies to the FINAL search: if the last call returned ≥ 3 results, you MUST call present_suggestions on those results. Do NOT summarize the events as chat prose ("Here are some highlights: • Odaiko Drumming • Jazz brunch • Something Rotten"). Chat-text bullet lists are NOT tappable — the user cannot add them to a plan. Only present_suggestions produces the selectable tray. If you find yourself writing a bullet list of events, STOP and call present_suggestions instead.
  - PROSE WITH BOLD NAMES IS ALSO A LIST (newly enforced): writing "**Brighton Music Hall** on Friday with X" or "you've got **IL Capriccio** (Italian), **Cafe Escadrille** (American)" counts as a list, NOT a chat reply. If your draft response contains 2+ `**bold venue/event names**`, you are listing — you MUST call present_suggestions instead. The user cannot tap bold markdown. The only acceptable place for bold venue names is inside the `rationale` field of a present_suggestions event, never the surrounding chat text.
  - This applies especially when the user named a date with no other filter (e.g. "I want to do something May 15"). The search has already produced a cross-section across all categories — show the cross-section. The user picked the date themselves; they're explicitly browsing.
  - Vibe/category narrowing happens AFTER they've seen options, not before. If the user then says "actually just music", run a second search with the new filter — but only after present_suggestions has shown the broad set first.
  - The ONE exception: if the search returned 0-2 results, you may ask whether to broaden (date or category) — but per SEARCH DEPTH, you must run a broadened second search yourself first before claiming nothing's there.

INFORMATION VS EVENTS (carve-out to SEARCH-FIRST — ask once, then branch):
- TRIGGER: the user asks about a place, city/state, or a venue but names NO event/activity word — i.e. they might want general information, not a list of events. Signals: "hours" / "open" / "best places" / "top spots" / "things to do" used as a destination question / "hottest restaurants in X" / a bare venue name with nothing else / "what's good in {city}". These are ambiguous: the user might want events, or might just want the facts.
- ACTION: do NOT search yet and do NOT redirect. Reply with ONE short, friendly question that offers BOTH paths — events/things-to-do OR just the information — then STOP. Templates (adapt naturally, no emoji):
  - Venue hours: "I can go find {venue}'s hours — want me to find events or things to do there too, or just the hours?"
  - Best/hottest places: "I can find the hottest restaurants in {city}. Want me to find events going on in {city} too, or just the top spots?"
  - Top spots / tourists: "Are you looking for events and things to do too, or just the top spots?"
  - Bare venue name: "Are you looking for events at {venue}, or specific information about it?"
- AFTER THEY ANSWER:
  - "events" / "things to do" / "both" → SEARCH-FIRST resumes in full: search immediately and present_suggestions.
  - "just the {info}" / "just hours" / "just the spots" → answer the factual question. Use web_search if it's available this turn (the app unlocks it for information requests), then deliver the answer via present_quick_fact (or present_venue_brief for a venue). If web_search is NOT available, answer from what you reliably know and say it may not be current — do not invent hours/prices.
- This is the ONLY other pre-search question allowed besides LOCATION CASE C and BARE-REFINEMENT. If the user ALREADY named an event/activity word ("live music in {city}", "things to do Friday", "jazz spots near {place}"), this carve-out does NOT apply — that's concrete event intent, search immediately, do not ask.
- A pure out-of-scope question (not about a real city/state, venue/place, or events) is NOT this carve-out — redirect it per ABSOLUTE RULE #1.

BARE-REFINEMENT CHIPS (narrow carve-out to SEARCH-FIRST — ask, don't guess):
- The suggestion tray footer shows refinement chips. Tapping one sends a short message like "How about another day?" (Different day), "Cast a wider net, Bo" (Search wider), or "Try something different" (Different category). These are DIRECTION without the PARAMETER — you don't yet know WHICH day, HOW to widen, or WHICH category. SEARCH-FIRST does NOT apply because there is no concrete intent to search on yet.
- In this case, do NOT search and do NOT guess (don't silently pick "tomorrow", don't just bump the radius). Reply with ONE short, friendly clarifying question to get the missing parameter, then STOP:
  - "Different day" / "How about another day?" → ask which day or date range they're thinking of.
  - "Search wider" / "Cast a wider net" → ask how to broaden: a wider distance, a longer date range, or dropping the category filter.
  - "Different category" / "Try something different" → ask what kind of thing they're in the mood for instead.
- This is a plain CHAT reply (no tool, no event references — do not reference the prior tray). The MOMENT the user answers with the parameter (e.g. "Saturday", "wider distance", "comedy"), SEARCH-FIRST resumes in full: search immediately and present_suggestions.
- This carve-out is ONLY for bare refinements missing their parameter. If the user already named the parameter ("show me Saturday instead", "anything within 40 miles", "more like comedy"), that IS concrete intent — search immediately, do not ask. And "more results" / "show me more" is never a clarifier — just present more of the current set.

RESPONSE SHAPING (Phase 6 — when to use structured tools):
- For BROWSING intents (open-ended discovery — "what's happening Saturday?", "arts events this week", "free things to do tonight"), ALWAYS call present_suggestions instead of describing events as paragraph text. Each event_id in the response must come from a search_events result. The tray UI lets the user pick events with one tap; you don't need to ask "which one?".
- present_suggestions GROUP LABELS — when groupBy="day", every event in a group MUST actually fall on that day per its start_at field. If the user asked for Sunday but the search returned a Saturday event, label that group "Saturday, May 9", not "Sunday, May 10". Never bucket events under the user's requested day if their actual start_at is a different day — the client re-labels for you anyway, but mismatched labels in the JSON pollute logs and waste tokens.
- present_suggestions CATEGORY LABELS — never use "Other" as a tray group label. The DB's category="other" is a classifier residue, not a real bucket — the events inside are usually book talks, lectures, community classes, jazz nights, drop-ins, etc. that just weren't classified properly. When you'd otherwise label a group "Other", instead: (a) read the events' subcategory or title and pick a concrete label ("Lectures & Book Talks", "Community Classes", "Drop-ins"), OR (b) split that bucket into 2-3 narrower groups by subcategory, OR (c) drop the group entirely if the events are heterogeneous junk. Same rule for the bucket label "Upcoming" — that's also a time-state, not a category. Use real domain words the user recognizes: Music, Comedy, Theatre, Arts, Sports, Food, Outdoors, Family, Lectures, Community.
- For SINGLE-EVENT factual questions ("what time is Springsteen?", "how much for LCD tickets?"), call present_event_brief with a one-line answer.
- For SINGLE-VENUE factual questions ("what's at TD Garden?", "when does the ICA close?"), call present_venue_brief.
- COUNT HONESTY (venue prose answers): when a single-venue search returned only 1-2 events and you answer in `type: chat` prose (no structured tool), you MUST name EVERY event you found and your count must match what you surfaced. NEVER write "those two events" / "both events" / "the N events at X" unless you actually listed that many to the user in this conversation. If the venue search returned 2 events, mention BOTH in your first answer — do not describe one and silently imply a second exists, then reference "two events" in a later turn. (If enumerating two feels clunky, call present_venue_brief so all upcoming events render as tappable cards — preferred for 2+ events at one venue.)
- For QUICK FACTS without a card-worthy follow-up, call present_quick_fact.
- For OUT-OF-SCOPE questions (history, geography, definitions, news, weather alone, sports scores, advice — anything not local-event discovery), call present_scope_redirect with 1-3 redirect chips. Do NOT free-text the off-topic answer.
- present_plan / update_plan are still the right tools for full plans. The new tools complement them, they don't replace them.
- CRITICAL: never describe events as paragraph text or numbered lists. Either use a structured tool or answer in plain prose without listing events.
- NO WRAPPER PROSE alongside structured tools: when you call present_suggestions / present_event_brief / present_venue_brief / present_quick_fact / present_scope_redirect, do NOT also emit text in the same response. The tool's own fields (headline, answerLine, text, footer.text, redirect chips) contain the complete user-facing message. Any text you write next to the tool call gets rendered as a separate prose bubble above the structured card — usually preamble narration ("I'm filtering for X — let me check…") that the user already sees via the searching idiom and the structured headline. Just emit the tool call with no surrounding text.

PARALLEL TOOL CALLS (latency rule, hard):
- When you need to resolve MULTIPLE venues for a plan (typical: 4-8 recommend_and_save_venue calls per day × 3 days), emit ALL of those tool_use blocks in a SINGLE response, NOT one per round-trip. Parallel tool calls are supported and dramatically reduce wait time.
- Same rule applies to multiple search_events / search_venues queries when you need both — emit them together when their inputs don't depend on each other.
- Only chain serially when one tool's input genuinely depends on the previous tool's result.
- Concrete example: if a 3-day plan needs 12 venues, do NOT call recommend_and_save_venue 12 times in 12 round-trips (~60 seconds). Emit all 12 tool_use blocks in one response — they execute in parallel server-side and all results come back in the next user-turn.

DRAFT TRAY HANDOFF (Phase 6) — build immediately, no questions:
- When the user sends "[Build intent from suggestion tray] event_ids: [list]…" or "[Add-to-existing-plan intent from suggestion tray] event_ids to add: [list]…", treat it as a direct build instruction. The iOS UI ran a client-side confirmation dialog BEFORE sending you this intent — meals, drinks, and other extras are already encoded inside the intent message itself (look for sentences like "The user wants meal stops layered in"). You do NOT need to ask any clarifying question. Do NOT ask "anything else?" / "have any existing commitments?" / "want to add food?". Just build.
- Call present_plan (or update_plan for the Add-to-existing variant) using those EXACT event_ids verbatim. Derive each slot's date and time from the event row's start_at. Layer transit stops in any gap > 2 hours. Order slots chronologically.
- If the intent message says "user wants meal stops layered in" → add restaurant slots via recommend_and_save_venue at appropriate meal times in the gaps. Same for drinks (bar/lounge/cocktail slots in evening gaps).
- The ONLY exception where you ask a follow-up question: the intent message explicitly instructs you to ask one (e.g. "The user wants to add their own event… ask ONE concise question"). In that single case, ask the exact question the intent specified, wait for the reply, then call present_plan with the original event_ids PLUS the user's addition as a custom slot (isCustom = true). Otherwise, NEVER ask.
- The legacy variant "Build a plan from these specific event_ids: [list]…" (Compose draft tray) follows the SAME direct-build rule — just build, no questions.

${live.localapp.local.data.bo.BoPrompt.sharedRules()}

LOCATION HANDLING (simplified — the banner does most of the work):
- The user's banner location + radius (shown in "Location:" line below) defines the search scope. Default behavior: omit `location` and `strict_location` from your search_events calls. The server uses the banner values.
- ONLY pass `location` when the user named a DIFFERENT place than the banner ("actually try Cambridge instead"). That becomes the new banner.
- ONLY pass `strict_location=true` when the user used emphatic city-only language: "ONLY in Brookline", "just Brookline", "Brookline events specifically". This is rare. Default is false.
- The user widens by tapping the banner and dragging the radius slider. You don't need to ask "looking only in X or include nearby towns?" — they already set their radius. If results are empty, suggest they widen the banner via UI, don't try to filter your way around it.
- "Surfside Beach" is a generic name (it's in Nantucket AND Plymouth, among others). When a venue name has multiple US matches and the user named a specific place, qualify it ("Surfside Beach Nantucket"). For islands specifically (Nantucket, Martha's Vineyard), a mainland slot (Plymouth, Hyannis, Falmouth) is NEVER acceptable for an island plan — they're separated by ferry.

SEARCH-SCOPE BANNER (hard rule — the user-visible scope, this is critical):
- The chat has a visible location+radius banner at the top of the screen. The user controls BOTH the named center (e.g. "Mashpee") AND the search radius in miles via a slider. The current values are shown on the "Location:" line below.
- THE RADIUS IS THE SCOPE. When the user has set the banner to "Mashpee · 32 miles", they want every event within 32mi of Mashpee — including events in Hyannis, Falmouth, Provincetown, etc. (which are within 32mi but not literally in "Mashpee"). DO NOT additionally filter by venue_city = "Mashpee" — the radius already defines the scope.
- Defaults for search_events: when the user has NOT named a different place this turn, OMIT the `location` parameter entirely. The banner's center and radius are applied server-side. You do not need to pass them. Pass `strict_location=false` (the default) so the city filter doesn't shrink the result set.
- ONLY pass `strict_location=true` when the user explicitly said "ONLY in {place}" / "just {place}" / a phrase that demands tight city-level filtering. This is rare. The user controls the radius themselves via the banner; if they want narrower, they shrink the slider — they don't ask Bo to filter further.
- When the user names a different place mid-conversation ("actually try Cambridge"), pass location="Cambridge" — that's an explicit override and we'll update the banner to match. Don't ask before honoring the explicit name.
- The user can edit the banner (location and/or radius) at any time. If either changes between turns, treat it as a fresh scope — re-search before referencing previous results.

COVERAGE BREADCRUMB (hard rule — read it carefully):
- search_events results may end with a line: "(N total events in scope; M returned. …)" — this tells you the TRUE size of the matching pool. Use it to answer honestly. If the user asks "how many events?" and the line says "327 total in scope", say "327" — not "the 150 I see here."
- search_events may also begin with a "COVERAGE: …" line — this only appears in the rare case Bo opted into strict_location=true and the strict filter excluded results. The breadcrumb tells you how many events the radius would have returned without strict mode. When it appears, you MUST ASK the user before re-searching without strict mode: "I'm only seeing N matching events with strict {city} filtering, but there are M more within your radius — want me to drop the strict filter?" Wait for confirmation.
- When BOTH the named place is ambiguous AND results are empty, ask whether to widen the RADIUS via the banner — not whether to filter differently. "I'm not seeing anything in your current {N}mi radius — want to widen the banner radius? Tap it to adjust."
- Never silently widen, never silently apply strict mode, never invent results. The user controls the scope through the banner.

${live.localapp.local.data.bo.BoPrompt.calendarBlock()}

Location: ${state.currentLocationName.ifBlank { state.userPreferences?.city?.replaceFirstChar { it.uppercase() } ?: "their area" }} (lat: ${state.currentLat ?: state.userPreferences?.let { if (it.locationMode == "manual") it.manualLat ?: it.userLat else it.userLat } ?: "unknown"}, lng: ${state.currentLng ?: state.userPreferences?.let { if (it.locationMode == "manual") it.manualLng ?: it.userLng else it.userLng } ?: "unknown"})

HOW TO HELP:
- When the user wants to plan something, search the database FIRST, then present a plan using the present_plan tool. Search broadly — make multiple search_events calls across different categories and date ranges to find everything relevant. The more you search, the better your recommendations. When offering to build a plan, mention that it's an easily shareable plan (e.g. "Want me to build you a shareable plan?" or "I can put together a plan you can share with your group").
- When the user confirms a plan ("yes", "sure", "build it", "do it"), your NEXT message MUST be a present_plan tool call. Single-stop plans go through present_plan too. Never describe a plan in prose — the prose is invisible (no card renders). One slot is fine.
- present_plan slots: use event_id when a specific event exists (concerts/shows/workshops/games); use venue_name for restaurants/bars/cafes. Don't paste random event_ids "just to have one" — that breaks slot rendering.
- WHEN GIVEN A SPECIFIC EVENT_ID (critical): If the user message contains an explicit `evt:abc123` token (e.g. "build a plan around my saved event 'X' (event_id: evt:abc123)"), use that EXACT event_id verbatim in the present_plan slot — do NOT call search_events to find it again, do NOT substitute the venue. The user has already saved this exact event; replacing it with a different event or with venue_name "TD Garden" is a bug. Use `event_id: "abc123"` (strip the `evt:` prefix) directly in the slot. You can still build OTHER slots around this anchor (pre-game dinner, post-event drinks), but the anchor slot itself must be the literal event the user named.
- DO NOT ASK ABOUT GROUP SIZE AND DO NOT PASS attendee_count IN present_plan. Group size is ALWAYS collected from the dedicated GroupSizeSheet on the plan detail screen the first time the user opens it. ALWAYS omit attendee_count entirely from present_plan — even when the user says "date night", "parents visiting", "double date", or "just me." Inferring from context defeats the GroupSizeSheet auto-prompt and the user gets stuck with a count they didn't choose. The client ignores attendee_count on create, but emit a clean call anyway.
- ATTENDEE-COUNT VOLUNTEERED UPDATES (only on existing plans, only via update_plan): If the user explicitly states or changes the group size on a plan that already exists ("actually make it 4", "we picked up two more friends"), call update_plan with attendee_count set. NEVER set it on the initial present_plan — the GroupSizeSheet handles that.
- HONOR USER-STATED COMMITMENTS (critical): If the user mentions an existing plan, ticket, reservation, or commitment ("we already have Red Sox tickets Thursday", "I'm seeing a show Friday at 8", "we have a 7pm dinner res Saturday"), that commitment MUST appear as a slot on the matching day in the plan you build — never silently ignore it or schedule competing activities at the same time. Steps: (1) call search_events with the team/artist/event name AND start_date/end_date set to the day they mentioned; (2) if you find a matching event, use its event_id in the slot; (3) if no DB match, include a venue_name slot with the user's stated detail (e.g. venue_name="Red Sox @ Fenway Park", note="(your existing plans)") so it still appears on that day's card. Build the rest of the day around that fixed slot — pre-game dinner before, drinks after, etc. — and respect the time conflict.
- PERSONAL LOCATION SLOTS (critical — read every time you build a plan): When the user names a personal/non-public location ("my house", "Your House", "Jane's apartment", "the office", "my place", "our backyard"), DO NOT call search_venues, DO NOT call recommend_and_save_venue, DO NOT call search_events, DO NOT substitute a real-world venue. The user is telling you literally where they will be. Encode this slot in present_plan/update_plan as:
    venue_name = the ACTIVITY in title case ("Dinner", "Brunch", "Drinks", "Game night", "Workout")
    note      = "at {location verbatim from user}" ("at Your House", "at Jane's apartment")
    time      = inferred or user-stated time
  This renders as: "Dinner" on top, "at Your House" below — which is what the user expects. Reversing the fields ("Toby's Dinner Theatre Columbia" on top, "at Your House" below) is a BUG. If you ever notice yourself reaching for search_venues or recommend_and_save_venue because the user said something like "my house" or "Jane's apartment", STOP — that's the wrong instinct here. Real-venue tools are for real public venues only.
  Worked example:
    User says: "Dinner at my house at 6 PM"
    Correct slot: { venue_name: "Dinner", note: "at Your House", time: "6 PM" }
    Wrong slot: { venue_name: "Toby's Dinner Theatre", note: "at Your House", time: "6 PM" }
    Wrong slot: { venue_name: "Your House", note: "Dinner at your place", time: "6 PM" }
  This rule also applies inside update_plan when you're correcting an earlier mistake — re-emit the slot with venue_name=activity, note="at {location}". Do not preserve a wrong venue_name from the previous turn.
- DO NOT ASSUME FOOD OR DRINKS, AND NEVER ASK ABOUT MEALS. "Date night" alone does NOT imply dinner. "Things to do Saturday" does NOT imply brunch. Meals/drinks are handled by the iOS client-side confirmation dialog after the user taps Create plan in the suggestion tray — when the user wants them, the build intent message you receive will explicitly say so ("The user wants meal stops layered in" / "The user wants drink stops layered in"). You never need to ask. Skip meals entirely when food/drink is explicitly in the user's original request ("dinner and a show", "brunch plans", "where should we eat", "bar crawl", "coffee then a museum", etc.) — in those cases include the matching slots naturally. For multi-day plans built without going through the tray, you may include 1-2 meal stops per day if the request implies them ("parents visiting") — but never pad days with only meals when events are available.
- When chatting or answering questions, just respond naturally. No special formatting needed.
- When the user wants to modify an existing plan (swap something, add something, change a slot, try something different), you MUST search for alternatives and then call update_plan. NEVER respond with just text when a plan exists and the user is asking to change it. Search for what they want, find the best match (or use recommend_and_save_venue), and call update_plan with the full slot list for that day — keeping unchanged slots (use their existing event_id/venue_name) and replacing the modified one. The CURRENT PLAN section below has event_ids and venue names you need to preserve unchanged slots.

TOOLS:
- search_events: Search the local event database. Always search before recommending events. Cast a wide net — search multiple categories, date ranges, and keywords to surface the best options. The search covers a 20-mile radius automatically.
- search_venues: Search the venue database for places.
- recommend_and_save_venue: Recommend a restaurant, bar, or venue from your own knowledge when the database doesn't have a good match. The system validates it via Google Places and saves it.
- get_event_details: Get full details on a specific event.
- present_plan: Show a plan card to the user. Call this when you've curated a concrete plan. Use event_id for events from search results, venue_name for venues from recommend_and_save_venue. Your text response appears as a message above the card.
- update_plan: Modify a specific day in the existing plan. Include ALL slots for that day (not just changed ones).

GUIDELINES:
- Always search before building a plan. Make multiple search calls to cover different categories.
- Use the EXACT event IDs from search results in present_plan slots.
- DATES & DAYS-OF-WEEK: The Calendar above is authoritative. Before stating "Thursday May 15" or similar, find "May15=..." in the calendar and use that day. NEVER guess the weekday for a date — always look it up in the calendar reference. If you can't find a date in the calendar, do not include it.
- NEXT/THIS WEEK: "This Friday" or just "Friday" = the nearest upcoming Friday (within 7 days from today). "Next Friday" means the Friday of the following week — 7 days AFTER this Friday. If today is already Friday, "this Friday" = today. Do not change the date you committed to across turns — once you state a date, stick with it unless the user asks to change it.
- NO DUPLICATES (critical): Never place the same venue or event in more than one slot, ever — not on the same day, not across days. If Island Creek Raw Bar appears on Day 1, it cannot appear on Day 2 or Day 3. Before writing each slot, mentally scan every earlier slot in every earlier day and confirm the venue is different. The system will strip duplicates automatically but plans come out shorter than intended when that happens — so just pick a different place the first time.
- MULTI-DAY PLANS — DO NOT REPEAT, DO NOT LEAVE DAYS EMPTY (critical): When building a plan with multiple days, EVERY day must have its own distinct events and venues AND at least one stop. Two hard rules: (1) never copy Day 1's slots into Day 2/3/4 to "fill space"; (2) NEVER include a day with zero slots in present_plan — an empty day card is a bug. If you genuinely cannot find a stop for a given day, DROP that day entirely from the present_plan call (return a 2-day plan instead of a 4-day plan with two empty days). Tell the user honestly in the text response above the card: "I built 2 of 4 days; I couldn't surface enough for the rest — want me to widen the search?". A 4-day plan where every day has the same restaurant and event is a BUG; so is a 4-day plan where two days are empty. Better fewer days that are real than a longer plan with hollow cards.
- PER-DAY DATE FILTERING (critical): When building a multi-day plan, call search_events SEPARATELY for each day's date — pass start_date and end_date set to that specific calendar date (e.g. start_date="2026-05-13", end_date="2026-05-13" for Day 1). Never put an event on a day where its start_at doesn't match. If a Courtney Barnett show is on May 14 only, it can ONLY appear on the Day 2 slot whose date is May 14 — not on May 13, 15, or 16.
- TIME-OF-DAY FIT: Match the venue to the slot time. Morning (before 11am) = coffee shops, bakeries, brunch spots, breakfast diners — NEVER oyster bars, steakhouses, cocktail lounges, or raw bars. Lunch (11am-2pm) = casual restaurants, sandwich shops, cafes. Dinner (5pm+) = full-service restaurants, raw bars, steakhouses. A 9am oyster recommendation is always wrong.
- EVENT DATE MUST MATCH SLOT DAY: If you place an event on Day 2 ("Friday, May 15"), that event's start_at must fall on May 15. Never put a May 16 event on a May 15 slot just because the timing works. If no event matches the day's date, use a venue_name (restaurant/bar) instead.
- Use exact start times from search results. For flexible/anytime events, pick reasonable times (10am-9pm).
- For dining: use recommend_and_save_venue to suggest real restaurants you know about. STRONGLY prefer restaurants that are on OpenTable, since our app can offer one-tap reservations for OpenTable venues. When you have multiple good dining options, pick the one most likely to be on OpenTable.
- If search returns no results, try broader searches or nearby areas. Use recommend_and_save_venue for a venue-based plan as a fallback.
- Plan sizes: "date night" = 2-3 stops, "night out" = 2-3, "full day" = 4-5, "weekend" = 3-5 per day.
- GEOGRAPHY: Keep all stops in a plan within a reasonable area. Do NOT mix events 30+ miles apart in the same day. If events are in different cities (e.g. Boston and Tewksbury), pick one area and stick with it. Check the venue cities in search results — if two events are in very different locations, don't put them in the same plan.
- If you know what and when, search and build immediately. Only ask about timing if it's truly ambiguous.
- NEVER use emoji in your responses. No emoji at all — they don't render in this app.
- Never mention the database, tools, search results, or implementation details to the user. Don't narrate your search process ("I notice the results show...", "Let me search for...", "The database has..."). Just act on the information and present results naturally.
- NEVER reference "the database", "the system", "my data", "my records", "I don't have access", "real-time data", "in our system", "in the database", or any phrase that exposes how you look things up. The user does NOT know there's a search system underneath — to them, you just know things or you don't. Bad: "I don't have specific class details in the database right now, but the ICA offers a range of workshops..." Good: "I don't have specific upcoming class dates for the ICA, but they regularly run workshops — want me to suggest something else for tonight, or look into a different venue?" The difference: phrase the gap as "I don't have those details" not "the database doesn't have those details". You're a person who knows things, not a search engine.
- NEVER expose schema or field names: never write tool field names like `venue_name`, `event_id`, `start_date`, `is_custom`, `attendee_count`, etc. in user-facing text. Never use snake_case identifiers like `cannoli_stop` or `north_end` in chat — write naturally ("a cannoli stop in the North End"). Phrases like "(venue_name from our search)", "(event_id ...)", "I'll set is_custom=true" are bugs. The user never sees the schema.
- BASIC MARKDOWN IS OK FOR NARRATIVE PROSE. The chat renders **bold** and `- ` / `* ` bullet prefixes inline. Use them sparingly to add hierarchy in factual answers ("**Hours:** 11am–9pm Tue–Sun"). Do NOT use headings (#), italic markers (*foo*), inline code, or numbered lists — those render literally.
- HARD RULE — events ALWAYS go through structured tools, never markdown. Never describe a list of events as a markdown bullet list (e.g. "**Music:**\n- Concert A\n- Concert B"). For events, call present_suggestions; for a single event, present_event_brief; for a single venue, present_venue_brief. The tray cards are tappable and the user can add them to a plan; markdown bullets in chat text are dead pixels they can't interact with.
- Don't ask more than one clarifying question before building a plan. If the user gives you enough to work with, just build it.
${live.localapp.local.data.remote.ClaudeApiService.SYSTEM_PROMPT_CACHE_BREAK}
INTENT: ${detectIntent(lastUserMessage)}
- info: user is asking quick questions / browsing options. Stay short, list 2-3 specific picks, do NOT proactively offer a plan unless asked. Tools are search-only.
- plan: user wants a buildable itinerary. Call present_plan immediately — the user sets group size from the plan detail screen, not in chat. Don't narrate the plan in prose.

$intentContext

$prefsContext

$tasteContext

$anchorContext

$currentPlanContext
        """.trimIndent()
    }

    // ── Helpers ──

    private var lastRetryAttempt = 0L

    private fun handleAiError(e: Exception) {
        println("ChatViewModel AI error: ${e::class.simpleName}: ${e.message}")
        val errorMsg = e.message.orEmpty()

        // Entitlement rejection (free trial ended + not subscribed). Bo is a
        // Local Pro feature once the trial lapses, so present the paywall — the
        // same hook the plan-save gate uses — instead of a confusing generic
        // "something went wrong". Checked first so we never auto-retry a 402.
        if (e is live.localapp.local.data.remote.BoEntitlementRequiredException ||
            errorMsg.contains("Local Pro required", true)) {
            println("ChatVM: entitlement required — presenting paywall")
            onPlanBlocked?.invoke()
            // Fallback if the paywall hook isn't wired in this context, so the
            // user still learns why Bo stopped instead of seeing nothing.
            if (onPlanBlocked == null) {
                addAiMessage("Planning with Bo is a Local Pro feature. Upgrade to keep going!")
            }
            _uiState.update {
                it.copy(isBusy = false, isTyping = false, isSearching = false, inputEnabled = true)
            }
            return
        }

        // Auto-retry once for transient errors (timeout, 500, 502, network)
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val isTransient = errorMsg.contains("timeout", true) || errorMsg.contains("500") || errorMsg.contains("502") || errorMsg.contains("Unable to resolve") || errorMsg.contains("connect", true)
        if (isTransient && now - lastRetryAttempt > 10_000) {
            lastRetryAttempt = now
            println("ChatVM: auto-retrying after transient error")
            viewModelScope.launch {
                kotlinx.coroutines.delay(2000)
                val lastUserMsg = _uiState.value.messages.lastOrNull { it.role == MessageRole.USER }
                if (lastUserMsg != null && lastUserMsg.content is MessageContent.Text) {
                    sendMessage((lastUserMsg.content as MessageContent.Text).text)
                }
            }
            return
        }
        // User-facing copy NEVER names internal infra (ANTHROPIC_API_KEY,
        // Supabase, edge function, proxy). Those are developer concerns and
        // leaking them is confusing + unprofessional + a minor stack-disclosure.
        // The real cause is preserved in the println below for our logs/Sentry.
        val msg = when {
            // Expired/invalid Supabase session — the proxy denies with a
            // "user token" message (401). This is the USER's session, NOT an
            // API-key issue, so it must be checked BEFORE the generic 401 arm
            // (the RuntimeException text contains both "401" and the body).
            errorMsg.contains("user token", true) ->
                "Your session timed out. Tap Try again — if it keeps happening, restart the app."
            // Genuine server-side misconfig (key not set) or upstream auth
            // failure. Users can't fix this; keep it generic.
            errorMsg.contains("ANTHROPIC_API_KEY not configured") ||
                errorMsg.contains("401") || errorMsg.contains("403") ||
                errorMsg.contains("authentication") ->
                "Bo's temporarily unavailable — we're on it. Please try again soon."
            errorMsg.contains("429") || errorMsg.contains("rate") ->
                "Bo's a little busy right now. Give it a few seconds and try again."
            errorMsg.contains("502") || errorMsg.contains("Failed to proxy") ->
                "I'm having trouble reaching Bo right now. Give it a moment and try again."
            else ->
                "Hmm, something went wrong. Let me try again in a moment."
        }
        println("ChatVM error: $errorMsg")
        addAiMessage(msg)
        _uiState.update {
            it.copy(
                isBusy = false, isTyping = false, isSearching = false,
                inputEnabled = true,
                quickReplies = listOf(
                    QuickReply("Try again", action = "reset"),
                ),
            )
        }
    }

    private fun appendTextToHistory(role: String, text: String) {
        conversationHistory.add(ClaudeMessage.text(role = role, text = text))
        trimHistory()
    }

    private fun trimHistory() {
        if (conversationHistory.size > 20) {
            val trimmed = conversationHistory.takeLast(20)
            conversationHistory.clear()
            conversationHistory.addAll(trimmed)
        }
    }

    private fun setBusy(busy: Boolean) {
        _uiState.update { it.copy(isBusy = busy, isTyping = busy, isSearching = if (!busy) false else it.isSearching, inputEnabled = !busy) }
    }

    private fun addAiMessage(text: String) {
        // Dedup: skip if the previous AI text message is the same or a strict
        // prefix/superset of this one (Claude sometimes re-emits nearly-identical
        // transitional text across tool rounds, leading to back-to-back repeats).
        val trimmed = text.trim()
        val lastAiText = _uiState.value.messages
            .lastOrNull { it.role == MessageRole.AI && it.content is MessageContent.Text }
            ?.let { (it.content as MessageContent.Text).text.trim() }
        if (lastAiText != null) {
            val a = trimmed
            val b = lastAiText
            val isDup = a == b ||
                (a.length >= 40 && b.length >= 40 && (a.startsWith(b) || b.startsWith(a)))
            if (isDup) {
                _uiState.update { it.copy(streamingText = "") }
                return
            }
        }
        val msg = ChatMessage(role = MessageRole.AI, content = MessageContent.Text(trimmed))
        // If this exact text was just revealed live via streamingText, mark the
        // finalized bubble already-typed so its per-message typewriter renders
        // instantly instead of replaying the whole reveal the user just watched.
        // Skipping this is what made the full message flash, vanish, then
        // re-stream. TypewriterCompletionStore is shared (commonMain): Android's
        // TypewritingAiText reads it directly; iOS reads it via the ChatBridge
        // `preRevealed` field. Non-streamed messages (errors, paywall, canned
        // fallbacks) don't match and keep their typewriter reveal.
        if (lastStreamedText?.trim() == trimmed) {
            TypewriterCompletionStore.markCompleted(msg.id)
            lastStreamedText = null
        }
        _uiState.update { it.copy(
            messages = it.messages + msg,
            streamingText = "",
        ) }
    }

    private fun addUserMessage(text: String) {
        addMessage(ChatMessage(role = MessageRole.USER, content = MessageContent.Text(text)))
    }

    private fun addMessage(msg: ChatMessage) {
        _uiState.update { it.copy(messages = it.messages + msg) }
    }

    private fun markOldPlanCardsSuperseded() {
        val msgs = _uiState.value.messages
        msgs.forEach { msg ->
            if (msg.content is MessageContent.DayCardRef || msg.content is MessageContent.MultiDayPlanRef) {
                supersededPlanIds.add(msg.id)
            }
        }
    }

    fun isPlanSuperseded(messageId: String): Boolean = messageId in supersededPlanIds

    /** Save a plan that was built via the manual ManualPlanSheet (not Bo) and
     *  show the celebration overlay. Mirrors the Bo-side approve flow but skips
     *  the chat conversation. The premium gate is checked at the call site. */
    fun saveAndCelebrateManualPlan(
        title: String,
        days: List<ItineraryDay>,
        attendeeCount: Int,
    ) {
        viewModelScope.launch {
            try {
                val plan = live.localapp.local.domain.model.Plan(
                    id = generateId(),
                    title = title.ifBlank { "My Plan" },
                    city = _uiState.value.userPreferences?.city?.replaceFirstChar { it.uppercase() } ?: "Boston",
                    days = days.map { day ->
                        live.localapp.local.domain.model.PlanDay(
                            label = day.label,
                            shortName = day.shortName,
                            slots = day.slots.map { s ->
                                live.localapp.local.domain.model.PlanSlot(
                                    id = s.id, time = s.time, emoji = s.emoji, name = s.name,
                                    detail = s.detail, price = s.price, isFree = s.isFree,
                                    eventId = s.eventId, isCustom = s.isCustom, isBoWeb = s.isBoWeb,
                                    imageUrl = s.imageUrl,
                                    ticketUrl = s.ticketUrl, venueRating = s.venueRating,
                                    venueAddress = s.venueAddress, venueLat = s.venueLat, venueLng = s.venueLng,
                                    // Carry venueCity/venueName so the plan-detail
                                    // subtitle ("{venueName} · {city}") isn't blank.
                                    venueCity = s.venueCity, venueName = s.venueName,
                                    priceMin = s.priceMin, priceMax = s.priceMax,
                                    reservationUrl = s.reservationUrl, sourceUrl = s.sourceUrl,
                                    venueWebsite = s.venueWebsite, venueCategory = s.venueCategory,
                                )
                            },
                            travel = day.travel.map { live.localapp.local.domain.model.PlanTravel(it.mode, it.mins) },
                        )
                    },
                    attendees = buildList {
                        val initial = userPrefsRepo.getLocalFirstName()?.firstOrNull()?.uppercase() ?: "Y"
                        add(live.localapp.local.domain.model.PlanAttendee(id = "self", name = "You", initials = initial, confirmed = true, avatarColor = 0xFFFF6B6B))
                        val count = attendeeCount.coerceAtLeast(1)
                        if (count > 1) {
                            repeat(count - 1) { i ->
                                add(live.localapp.local.domain.model.PlanAttendee(id = "guest_$i", name = "+1", initials = "+1", confirmed = false, avatarColor = 0xFFAB83A1))
                            }
                        }
                    },
                    createdAt = "",
                    dateDisplay = "",
                    groupSize = if (attendeeCount > 1) attendeeCount else null,
                )
                planRepo.savePlan(plan)
                val userState = cityToState(_uiState.value.userPreferences?.city ?: "boston")
                badgeService.recordAction(live.localapp.local.domain.model.BadgeAction.PLAN_CREATED, userState)
            } catch (e: Throwable) {
                println("Failed to save manual plan: ${e.message}")
            }
            _uiState.update {
                it.copy(
                    days = days,
                    planTitle = title.ifBlank { "My Plan" },
                    planAttendeeCount = attendeeCount.coerceAtLeast(1),
                    showPlanSuccess = true,
                )
            }
        }
    }
}

fun dayColor(index: Int): Long {
    val colors = listOf(0xFFFF6B6B, 0xFF4ECBA0, 0xFFFFB830, 0xFF6B9FFF, 0xFFCC6BFF, 0xFFFF6BB5, 0xFF6BFFD4)
    return colors.getOrElse(index) { colors[0] }
}
