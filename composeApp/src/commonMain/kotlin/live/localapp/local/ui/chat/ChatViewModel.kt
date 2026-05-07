package live.localapp.local.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

    /** Cache of all events returned by search_events calls in the current turn. */
    private val searchResultCache = mutableListOf<Event>()
    /** Cache of search_events queries that returned zero results within the
     *  current chat session. Bo has a habit of re-querying the same region
     *  with a slightly different filter ("nantucket outdoors", "nantucket
     *  music", etc.) when the first query was empty — each re-query costs a
     *  full SQL round-trip. Hitting this cache short-circuits to "No events
     *  found" immediately. Cleared on resetChat() like searchResultCache. */
    private val searchEmptyMisses = mutableSetOf<String>()

    /** Cached taste profile — loaded at init, refreshed after plan approvals. */
    private var tasteProfile: TasteProfile? = null

    private val weatherService = WeatherService()

    /** Update current location (called from AppNavigation when location changes). */
    fun setCurrentLocation(lat: Double, lng: Double, name: String) {
        _uiState.update { it.copy(currentLat = lat, currentLng = lng, currentLocationName = name) }
    }

    // One-shot navigation events
    private val _navigateToPlans = Channel<Unit>(Channel.BUFFERED)
    val navigateToPlans = _navigateToPlans.receiveAsFlow()

    // Claude conversation history — supports both text and tool_use/tool_result blocks
    private val conversationHistory = mutableListOf<ClaudeMessage>()

    init {
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
            }
            showWelcome()
        }
    }

    // ── Welcome ──

    private fun showWelcome() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTyping = true) }

            try {
                val systemPrompt = buildSystemPrompt()
                // Send a welcome prompt to Claude — it will greet the user naturally
                conversationHistory.add(ClaudeMessage.text("user", "[System: The user just opened the chat. Greet them warmly in 1-2 short sentences. Ask what they'd like to do. Keep it casual and brief. Do NOT use tools. Do NOT use any special formatting.]"))
                val response = sendStreaming(systemPrompt, null, isToolRound = false, model = live.localapp.local.data.remote.ClaudeApiService.MODEL_CHAT)
                val text = claudeApi.extractText(response) ?: "Hey! What are you looking to plan?"
                // Remove the synthetic user message from history — replace with clean start
                conversationHistory.removeLastOrNull()

                _uiState.update { it.copy(streamingText = "") }
                addAiMessage(text)
            } catch (_: Exception) {
                _uiState.update { it.copy(streamingText = "") }
                addAiMessage("Hey! What are you looking to plan?")
            }

            val prefs = _uiState.value.userPreferences
            val defaultChips = mutableListOf(
                QuickReply("Weekend plans"),
                QuickReply("Date night ideas"),
            )
            prefs?.selectedCategories?.take(2)?.forEach { cat ->
                defaultChips.add(QuickReply("${categoryDisplayName(cat)} this week"))
            }
            prefs?.selectedNeighborhoods?.firstOrNull()?.let { hood ->
                defaultChips.add(QuickReply("Things to do in $hood"))
            }
            if (defaultChips.size <= 2) {
                defaultChips.add(QuickReply("Free things to do"))
                defaultChips.add(QuickReply("Live music tonight"))
            }

            _uiState.update {
                it.copy(
                    isTyping = false,
                    inputEnabled = true,
                    inputPlaceholder = "What are you looking for?",
                    quickReplies = defaultChips,
                )
            }
        }
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

    // ── Public actions ──

    fun sendMessage(text: String) {
        if (text.isBlank() || _uiState.value.isBusy) return
        addUserMessage(text.trim())
        handleMessage(text.trim())
    }

    /**
     * Build-plan-from-selections CTA (iOS suggestion-tray Create Plan button).
     * Adds a clean user-visible bubble while sending Bo the verbose technical
     * instruction (event_ids + handoff rule) via conversation history only.
     * Bo's prompt rule (DRAFT TRAY HANDOFF) directs Bo to ask one follow-up
     * question before calling present_plan, so the user can mention extras
     * or commitments to fold into the plan.
     */
    fun buildPlanFromSelections(eventIds: List<String>) {
        if (eventIds.isEmpty() || _uiState.value.isBusy) return
        val n = eventIds.size
        val display = "Build me a plan with my $n pick${if (n == 1) "" else "s"}"
        val technical = "[Build intent from suggestion tray] " +
            "event_ids: ${eventIds.joinToString(", ")}. " +
            "Use these EXACT event_ids in present_plan slots — derive each slot's date and time from the event row's start_at, " +
            "and layer transit + meal stops in any gap > 2 hours. " +
            "Before calling present_plan, ask the user ONE concise question (1 sentence): " +
            "whether they want to add anything else, or whether they already have something planned for the day(s) " +
            "these events fall on. Wait for their response before building."
        addUserMessage(display)
        handleMessage(text = display, historyText = technical)
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

            // The SQL function's date window is in UTC, but events have local
            // start_ats. For a user in Eastern (UTC-4), asking for "May 7"
            // and using start_date=2026-05-07 + end_date=2026-05-07 yields a
            // UTC window of May 7 00:00 → May 7 23:59 — which in Eastern is
            // May 6 8 PM through May 7 8 PM. Evening events on May 7 are
            // missed (their UTC time is May 8) and evening events on May 6
            // are wrongly included (their UTC time is May 7). Widen the
            // window by one day on each side, then filter results to those
            // whose LOCAL day matches the requested dayKey.
            val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
            val (searchStart, searchEnd) = if (dayKey != null) {
                try {
                    val day = kotlinx.datetime.LocalDate.parse(dayKey)
                    val before = kotlinx.datetime.LocalDate.fromEpochDays(day.toEpochDays() - 1).toString()
                    val after = kotlinx.datetime.LocalDate.fromEpochDays(day.toEpochDays() + 1).toString()
                    before to after
                } catch (_: Throwable) {
                    dayKey to dayKey
                }
            } else null to null

            val moreRaw = try {
                eventRepo.searchEventsBroad(
                    userLat = userLat,
                    userLng = userLng,
                    radiusMiles = 20.0,
                    dateRange = if (dayKey != null) "custom" else null,
                    startDate = searchStart,
                    endDate = searchEnd,
                    keyword = categoryKey,
                    limit = 80,
                )
            } catch (_: Throwable) { emptyList() }

            val onTargetDay: (live.localapp.local.domain.model.Event) -> Boolean = filter@{ ev ->
                if (dayKey == null) return@filter true
                val s = ev.startAt ?: return@filter false
                try {
                    kotlinx.datetime.Instant.parse(s).toLocalDateTime(tz).date.toString() == dayKey
                } catch (_: Throwable) { false }
            }

            val newEvents = moreRaw
                .asSequence()
                .filter { it.id !in existingIds }
                .filter(onTargetDay)
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
            val targetGroupLabel = suggestions.groups.firstOrNull { g ->
                if (dayKey != null) {
                    // Match by inspecting the first hydrated event's local date
                    val first = g.events.firstOrNull()?.let { content.hydratedEvents[it.eventId] }
                    first?.startAt?.let {
                        try {
                            val d = kotlinx.datetime.Instant.parse(it)
                                .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                                .date.toString()
                            d == dayKey
                        } catch (_: Throwable) { false }
                    } == true
                } else {
                    g.label.equals(categoryKey, ignoreCase = true)
                }
            }?.label
            val updatedGroups = if (targetGroupLabel != null) {
                suggestions.groups.map { g ->
                    if (g.label == targetGroupLabel) g.copy(events = g.events + newRefs) else g
                }
            } else {
                suggestions.groups + live.localapp.local.domain.model.BoStructuredResponse.Suggestions.Group(
                    label = dayKey ?: categoryKey.orEmpty(),
                    events = newRefs,
                )
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

            // Collect all event IDs (non-venue refs), strip any "evt:" prefix Claude may add
            val eventIds = parsedDay.events.filter { !it.isVenue }.map { it.id.removePrefix("evt:") }
            val eventsById = if (eventIds.isNotEmpty()) {
                val fetched = eventRepo.getEventsByIds(eventIds)
                println("buildPlanFromRefs: fetched ${fetched.size}/${eventIds.size} events from DB")
                fetched.associateBy { it.id }
            } else emptyMap()

            // Also check searchResultCache for any events not found in batch query
            val cacheById = searchResultCache.associateBy { it.id }
            println("buildPlanFromRefs: cache has ${cacheById.size} events")

            val slots = parsedDay.events.mapNotNull { ref ->
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
                    // Venue ref — look up by name in the venue repo or use the name directly
                    val venue = try { venueRepo.searchByName(ref.id) } catch (_: Exception) { null }
                    ItinerarySlot(
                        id = generateId(),
                        time = ref.time,
                        emoji = "",
                        name = venue?.name ?: ref.id,
                        detail = ref.note.ifBlank {
                            buildString {
                                if (venue != null) {
                                    append(venue.city ?: "")
                                    if (venue.googleRating != null) append(" · ${venue.googleRating}")
                                }
                            }.ifBlank { ref.id }
                        },
                        price = "",
                        isFree = false,
                        imageUrl = venue?.photoUrl,
                        eventId = null,
                        isCustom = false,
                        venueRating = venue?.googleRating,
                        venueAddress = venue?.address,
                        venueCity = venue?.city,
                        venueName = venue?.name,
                        venueLat = venue?.latitude,
                        venueLng = venue?.longitude,
                        reservationUrl = venue?.reservationUrl,
                        venueWebsite = venue?.website,
                        venueCategory = venue?.category,
                    )
                    .also { println("  slot: name='${it.name}' reservationUrl=${venue?.reservationUrl} venueWebsite=${venue?.website} venueCategory=${venue?.category}") }
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
                    val noteMentionsDifferentVenue = eventVenueLower.isNotBlank() &&
                        noteLower.isNotBlank() &&
                        !noteLower.contains(eventVenueLower) &&
                        // Look for any "at <Capitalized Venue>" or "at <venue>" pattern
                        // in Bo's note that doesn't match the DB event's venue.
                        Regex("\\bat\\s+([A-Z][\\w'&.\\- ]{2,})", RegexOption.IGNORE_CASE)
                            .findAll(ref.note)
                            .map { it.groupValues[1].trim().lowercase() }
                            .any { mentioned ->
                                mentioned.isNotBlank() &&
                                    !mentioned.contains(eventVenueLower) &&
                                    !eventVenueLower.contains(mentioned)
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

    fun resetChat() {
        searchEmptyMisses.clear()
        conversationHistory.clear()
        searchResultCache.clear()  // wipe cache only on explicit reset
        _uiState.value = ChatUiState()
        showWelcome()
    }

    // ── Core: Single unified message handler ──

    /** Stores the last user message for APPROVE detection. */
    private var lastUserMessage: String = ""

    private fun handleMessage(text: String, historyText: String = text) {
        _uiState.update { it.copy(quickReplies = emptyList()) }
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
            _uiState.update { it.copy(isSearching = true, isTyping = false) }
            // historyText may differ from `text` when a clean user-visible
            // bubble is paired with a verbose technical instruction for Bo
            // (e.g. the Build-plan-from-tray flow).
            appendTextToHistory("user", historyText)

            var attempts = 0
            val maxAttempts = 2
            while (attempts < maxAttempts) {
                try {
                    val systemPrompt = buildSystemPrompt()
                    val parsed = sendWithToolLoop(systemPrompt)
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
                    handleClaudeResponse(parsed)
                    break
                } catch (e: Throwable) {
                    attempts++
                    println("Claude error (attempt $attempts/$maxAttempts): ${e::class.simpleName}: ${e.message}")
                    e.printStackTrace()
                    if (attempts < maxAttempts) {
                        println("ChatVM: retrying in 2s...")
                        _uiState.update { it.copy(streamingText = "") }
                        kotlinx.coroutines.delay(2000)
                        continue
                    }
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
    ): ClaudeResponse {
        var response: ClaudeResponse? = null
        val streamBuffer = StringBuilder()
        var lastEmitTime = 0L
        val emitIntervalMs = 40L // Smooth ~25fps updates

        claudeApi.sendRawStreaming(
            systemPrompt = systemPrompt,
            messages = conversationHistory,
            tools = tools,
            model = model,
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
                        _uiState.update { it.copy(streamingText = streamBuffer.toString()) }
                    }
                    response = event.response
                }
            }
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

    private suspend fun sendWithToolLoop(systemPrompt: String): ParsedChatResponse {
        val maxToolRounds = 5
        var toolRounds = 0
        // One-shot retry guard for the empty-response path. Anthropic occasionally
        // returns end_turn with no text content after very short user messages
        // (a single digit, "yes", "no"). Letting that surface as "trouble
        // generating" made Bo look broken; instead we retry once with a
        // continuation nudge before showing the user anything.
        var emptyResponseRetried = false
        // Start with Haiku for fast clarifying questions. Switch to Opus when tools are needed.
        var currentModel = live.localapp.local.data.remote.ClaudeApiService.MODEL_CHAT

        // Phase 1: Bo's web search/fetch gate. Per-turn check against:
        //  - Live-state rule (user must be in a state with is_live=true)
        //  - Per-user 30/day + 10/hour caps
        //  - Global $500/month cap
        // The system prompt is augmented with the gate verdict so Bo delivers
        // the right user-facing message when blocked.
        val budget = fetchBoWebBudget()
        val toolsForTurn = toolsWithWeb(
            base = claudeTools,
            allowWebSearch = budget?.allowWebSearch == true,
            allowWebFetch = budget?.allowWebFetch == true,
        )
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
            val response = sendStreaming(augmentedSystemPrompt, toolsForTurn, isToolRound = true, model = currentModel)
            tallyWebCalls(response, webCallTally)

            val toolCalls = claudeApi.extractToolCalls(response)
            val text = claudeApi.extractText(response) ?: ""

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
                val chips: List<String> = if (showBuildChip) listOf("Build this as a plan") else emptyList()

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
                val toolResultBlocks = kotlinx.serialization.json.buildJsonArray {
                    toolCalls.forEachIndexed { i, call ->
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("type", JsonPrimitive("tool_result"))
                            put("tool_use_id", JsonPrimitive(call.id))
                            put("content", JsonPrimitive(anchor + finalRoundResults[i]))
                        })
                    }
                }
                conversationHistory.add(ClaudeMessage.blocks("user", toolResultBlocks))
                conversationHistory.add(ClaudeMessage.text("user", "You have enough information now. Please present a plan using the present_plan tool, or just respond naturally."))

                // Final response — stream with text visible
                val finalResponse = sendStreaming(augmentedSystemPrompt, toolsForTurn, isToolRound = false, model = currentModel)
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
            val toolResultBlocks = kotlinx.serialization.json.buildJsonArray {
                toolCalls.forEachIndexed { i, call ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("type", JsonPrimitive("tool_result"))
                        put("tool_use_id", JsonPrimitive(call.id))
                        put("content", JsonPrimitive(anchor + results[i]))
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
                    else -> return """{"error": "Unknown tool: $name"}"""
                }
                return result
            } catch (e: Exception) {
                println("Tool execution error ($name, attempt ${attempt + 1}): ${e.message}")
                if (attempt == 0) kotlinx.coroutines.delay(1000)
            }
        }
        return """{"error": "Failed to execute $name after retries"}"""
    }

    private suspend fun searchEvents(input: JsonObject): String {
        val location = input["location"]?.jsonPrimitive?.contentOrNull
        val category = input["category"]?.jsonPrimitive?.contentOrNull
        var dateRange = input["date_range"]?.jsonPrimitive?.contentOrNull
        val keyword = input["keyword"]?.jsonPrimitive?.contentOrNull
        val limit = input["limit"]?.jsonPrimitive?.intOrNull ?: 10000
        val startDate = input["start_date"]?.jsonPrimitive?.contentOrNull
        val endDate = input["end_date"]?.jsonPrimitive?.contentOrNull

        // Empty-result short circuit. If the same region+date narrowly
        // returned zero this session, Bo will sometimes re-query with a
        // different category or keyword on the same region — every retry
        // is another full SQL round-trip. Cache the location+date key
        // (NOT category/keyword) so any repeat for that region returns
        // immediately. We DO key by date; widening the date legitimately
        // changes the result.
        val emptyKey = buildString {
            append(location?.lowercase()?.trim().orEmpty()); append("|")
            append(dateRange.orEmpty()); append("|")
            append(startDate.orEmpty()); append("|")
            append(endDate.orEmpty())
        }
        if (emptyKey != "|||" && emptyKey in searchEmptyMisses) {
            println("searchEvents: short-circuit (empty-cache hit for $emptyKey)")
            return "No events found matching your search."
        }

        // If specific dates provided, use custom date range instead of keyword
        if (startDate != null && dateRange == null) {
            dateRange = "custom"
        }

        // If Bo passed a specific location, resolve it. CRITICAL: if Bo
        // explicitly named a location and we can't resolve it, do NOT fall
        // back to user coords — that silently runs the search around
        // somewhere unrelated (e.g. Bo asks for "Dennis" → we use the
        // user's Boston coords → Bo gets Boston events back and presents
        // them as Dennis-area, violating the geo-integrity rule). When
        // unresolved, return a structured signal so Bo can correct course.
        val state = _uiState.value
        val locationRequested = !location.isNullOrBlank()
        val locationCoords = if (locationRequested) geocodeCity(location!!) else null
        if (locationRequested && locationCoords == null) {
            println("searchEvents: location='$location' did not geocode — returning unresolved-location signal to Bo")
            return "UNRESOLVED_LOCATION: I couldn't geocode '$location' to coordinates, so I did NOT search. " +
                "Do not fall back to the user's current location — that would surface results from somewhere else " +
                "and violate the geographic integrity rule. Either: (a) ask the user to clarify the city, " +
                "(b) try a nearby town you DO know (Hyannis, Cape Cod, Boston, etc.), or " +
                "(c) explicitly tell the user that '$location' isn't a city we have indexed yet. " +
                "Do NOT silently search elsewhere."
        }
        val prefs = state.userPreferences
        val prefsEffectiveLat = prefs?.let { if (it.locationMode == "manual") it.manualLat ?: it.userLat else it.userLat }
        val prefsEffectiveLng = prefs?.let { if (it.locationMode == "manual") it.manualLng ?: it.userLng else it.userLng }
        val userLat = locationCoords?.first ?: state.currentLat ?: prefsEffectiveLat
        val userLng = locationCoords?.second ?: state.currentLng ?: prefsEffectiveLng
        println("searchEvents: lat=$userLat lng=$userLng location=$location currentLocationName=${state.currentLocationName} geocoded=${locationCoords != null}")

        // Bo gets the three-lane stratified pool (breadth + niche + keyword).
        // Bo's `category` and `keyword` collapse into a single keyword hint —
        // the RPC's keyword lane already ilikes across all five surfaces, so
        // either one is matched the same way. Prefer keyword when both are
        // set (more specific signal).
        val effectiveKeyword = listOf(keyword, category).firstOrNull { !it.isNullOrBlank() }
        val rawEvents = if (userLat != null && userLng != null) {
            eventRepo.searchEventsBroad(
                userLat = userLat,
                userLng = userLng,
                radiusMiles = 20.0,
                dateRange = dateRange,
                startDate = startDate,
                endDate = endDate,
                keyword = effectiveKeyword,
                limit = limit.coerceAtMost(150), // halved from 300 — bigger context wasn't worth the latency
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
                radiusMiles = 20.0,
            )
        }

        // Drop events at rank <= 10 venues (community centers, K-12 fields,
        // junk listings). Matches the same global floor the Explore and
        // carousels surfaces enforce — Yocal should never recommend venues
        // the user can't actually discover elsewhere in the app.
        val lowRankNames = venueRepo.getLowRankVenueNames()
        val events = if (lowRankNames.isEmpty()) rawEvents else rawEvents.filter { e ->
            val vn = e.venueName?.trim()?.lowercase() ?: return@filter true
            val normalized = vn.removePrefix("the ").replace(Regex("[^a-z0-9\\s]"), "").trim()
            vn !in lowRankNames && normalized !in lowRankNames
        }

        // Cache full event objects
        searchResultCache.addAll(events)

        // Cap what Claude sees to keep token usage manageable. Lower cap (60)
        // is more aggressive — Bo can re-search with different params if it
        // needs more, but most planning queries use the top results.
        val totalFound = events.size
        val displayEvents = events.take(60)

        if (displayEvents.isEmpty()) {
            if (emptyKey != "|||") searchEmptyMisses += emptyKey
            return "No events found matching your search."
        }

        // Group events by date, then by category for compact display
        data class EventEntry(
            val id: String, val title: String, val venue: String, val venueCity: String,
            val dateKey: String, val dateLabel: String, val time: String,
            val category: String, val price: String,
        )

        val entries = displayEvents.map { e ->
            val venue = e.venueName ?: "TBD"
            val isFlexible = e.category == "always_on" || e.startAt == null
            val (dateKey, dateLabel, time) = if (isFlexible) {
                Triple("Flexible", "Flexible", "anytime")
            } else {
                try {
                    val instant = kotlinx.datetime.Instant.parse(e.startAt!!)
                    val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                    val dowFull = local.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
                    val monthNames = arrayOf("January","February","March","April","May","June","July","August","September","October","November","December")
                    val monthName = monthNames[local.monthNumber - 1]
                    val label = "$dowFull, $monthName ${local.dayOfMonth}"
                    val key = "${local.date}"
                    val h = local.hour
                    val m = local.minute
                    // Date-only events (timeKnown=false) show "TBD" so Bo doesn't
                    // mistake the midnight default for a real start time. Bo can
                    // still slot them by passing its own ref.time in present_plan.
                    val timeStr = if (!e.timeKnown) {
                        "TBD"
                    } else {
                        val ampm = if (h < 12) "am" else "pm"
                        val dh = if (h == 0) 12 else if (h > 12) h - 12 else h
                        if (m == 0) "${dh}${ampm}" else "${dh}:${m.toString().padStart(2, '0')}${ampm}"
                    }
                    Triple(key, label, timeStr)
                } catch (_: Exception) {
                    Triple(e.startAt?.take(10) ?: "?", e.startAt?.take(10) ?: "?", "?")
                }
            }
            val price = if (e.isFree) "FREE" else e.priceDisplay.ifBlank { "" }
            val cat = (e.subcategory ?: e.category ?: "other").uppercase()
            EventEntry(e.id, e.title, venue, e.venueCity, dateKey, dateLabel, time, cat, price)
        }

        // Build compact grouped output
        val grouped = entries.groupBy { it.dateKey }
        return buildString {
            for ((_, dateEntries) in grouped) {
                val dateLabel = dateEntries.first().dateLabel
                val count = dateEntries.size
                appendLine("=== $dateLabel ($count events) ===")
                val byCategory = dateEntries.groupBy { it.category }
                for ((cat, catEntries) in byCategory) {
                    val items = catEntries.joinToString(", ") { e ->
                        val priceTag = if (e.price.isNotBlank()) " ${e.price}" else ""
                        "${e.title} at ${e.venue} (${e.venueCity}) ${e.time}$priceTag [evt:${e.id}]"
                    }
                    appendLine("$cat: $items")
                }
                appendLine()
            }
            if (totalFound > 100) {
                appendLine("($totalFound total events, showing top 100)")
            }
        }.trim()
    }

    private suspend fun searchVenues(input: JsonObject): String {
        val location = input["location"]?.jsonPrimitive?.contentOrNull
        val category = input["category"]?.jsonPrimitive?.contentOrNull
        val keyword = input["keyword"]?.jsonPrimitive?.contentOrNull
        val limit = input["limit"]?.jsonPrimitive?.intOrNull ?: 10000

        val venues = venueRepo.searchVenues(location, category, keyword, limit)

        return venues.joinToString("\n") { v ->
            val cat = v.category ?: v.placeType ?: ""
            val rating = v.googleRating?.let { " | Rating: $it" } ?: ""
            "[${v.id}] ${v.name} | ${v.city}${if (v.address != null) " (${v.address})" else ""} | $cat$rating"
        }.ifEmpty { "No venues found matching your search." }
    }

    private suspend fun getEventDetails(input: JsonObject): String {
        val eventId = input["event_id"]?.jsonPrimitive?.contentOrNull
            ?: return """{"error": "event_id is required"}"""

        val event = eventRepo.getEvent(eventId)
            ?: return """{"error": "Event not found with id: $eventId"}"""

        return buildString {
            appendLine("ID: ${event.id}")
            appendLine("Title: ${event.title}")
            appendLine("Venue: ${event.venueName ?: "TBD"} (${event.venueCity})")
            if (event.venueAddress != null) appendLine("Address: ${event.venueAddress}")
            appendLine("Date: ${event.dateDisplay}")
            appendLine("Price: ${event.priceDisplay.ifBlank { "TBD" }}")
            if (event.description != null) appendLine("Description: ${event.description}")
            if (event.ticketUrl != null) appendLine("Tickets: ${event.ticketUrl}")
            if (event.imageUrl != null) appendLine("Image: ${event.imageUrl}")
            appendLine("Category: ${event.category}${if (event.subcategory != null) " / ${event.subcategory}" else ""}")
            appendLine("Free: ${event.isFree}")
        }
    }

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
                    appendLine("  • Run search_events FIRST. If it returns 5+ results, present 3-4 as options.")
                    appendLine("  • In parallel or as a follow-up, run web_search for 'long-tail' or unique events not")
                    appendLine("    in our DB (workshops, lectures, smaller venues, library/community events). Include")
                    appendLine("    these as additional options in the same response — don't make the user ask twice.")
                    appendLine("  • Surface 3-6 distinct options when the user is in discovery mode. Don't pick ONE")
                    appendLine("    without showing alternatives unless the user explicitly asked for a specific thing.")
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
    private val boWebFindingsCache = mutableMapOf<String, JsonObject>()

    private suspend fun saveWebFinding(input: JsonObject): String {
        val userId = userPrefsRepo.getLocalUserId()
            ?: return """{"error": "no_user"}"""
        val kind = input["kind"]?.jsonPrimitive?.contentOrNull
            ?: return """{"error": "kind required (event|venue)"}"""

        val payload = buildJsonObject {
            put("user_id", JsonPrimitive(userId))
            put("kind", JsonPrimitive(kind))
            put("payload", input)
        }
        return try {
            val res = httpClient.post("${Config.SUPABASE_URL}/functions/v1/save-bo-web-finding") {
                header("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
            val body = res.bodyAsText()
            if (res.status.value !in 200..299) {
                println("save_web_finding error: status=${res.status.value} body=${body.take(300)}")
                """{"error": "save failed (${res.status.value})", "details": ${kotlinx.serialization.json.Json.parseToJsonElement(body)}}"""
            } else {
                // Cache the input merged with the enriched fields the
                // edge function returned (Google Places address, lat/lng,
                // image_url). Bo's slot builder uses this for the map pin
                // and thumbnail without waiting on the verifier.
                val parsed = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
                val id = parsed["id"]?.jsonPrimitive?.contentOrNull
                if (id != null) {
                    val merged = buildJsonObject {
                        for ((k, v) in input) put(k, v)
                        // Enriched fields override null inputs.
                        parsed["venue_address"]?.let { if (input["venue_address"] == null) put("venue_address", it) }
                        parsed["venue_lat"]?.let { if (input["venue_lat"] == null) put("venue_lat", it) }
                        parsed["venue_lng"]?.let { if (input["venue_lng"] == null) put("venue_lng", it) }
                        parsed["image_url"]?.let { if (input["image_url"] == null) put("image_url", it) }
                    }
                    boWebFindingsCache[id] = merged
                }
                body  // {"id":"<uuid>", ...enriched fields...}
            }
        } catch (e: Throwable) {
            println("save_web_finding exception: ${e.message}")
            """{"error": "network: ${e.message}"}"""
        }
    }

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
                val validatedDays = removeDuplicateSlots(sequencedDays)

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
                        planAttendeeCount = parsed.attendeeCount ?: 1,
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
                finishTurn(withApproveChip(parsed.chips))
            }

            ParsedResponseType.PLAN_UPDATE -> {
                val dayIndex = parsed.dayIndex ?: _uiState.value.currentDayIndex
                val builtDays = buildPlanFromRefs(parsed)
                val updatedDay = builtDays.firstOrNull()

                if (updatedDay != null) {
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
                finishTurn(withApproveChip(parsed.chips))
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
                    finishTurn(withApproveChip(emptyList()))
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

    private fun withApproveChip(chips: List<String>): List<String> {
        // Only show approve chip if there are actual events in the plan
        val hasEvents = _uiState.value.days.any { it.slots.isNotEmpty() }
        if (!hasEvents) return chips

        val approveLabel = "Looks good, add to My Plans"
        val rest = chips.filter {
            !it.contains("Looks good", ignoreCase = true) &&
                !it.contains("add to My Plans", ignoreCase = true)
        }
        return listOf(approveLabel) + rest
    }

    // ── Phase 6 — structured-response handling + draft-tray actions ──

    private suspend fun handleStructuredResponse(parsed: ParsedChatResponse) {
        val json = parsed.structuredJson?.jsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())

        // Bo may emit a short prose intro alongside the terminal tool — show
        // it ONLY when it's substantive (not a "Let me check…" preamble and
        // not duplicated by the structured tool's own answerLine). Empirical
        // guard: ≤ 240 chars, no leading "Let me / I'll / Searching / etc."
        // patterns. Longer prose gets dropped (Bo broke BREVITY) since the
        // structured payload already conveys the answer.
        val wrapperProse = parsed.text.trim()
        if (wrapperProse.isNotBlank() && isAcceptableWrapperProse(wrapperProse)) {
            addAiMessage(wrapperProse)
        }

        val structured = parseStructuredResponse(parsed.type, json) ?: run {
            // Malformed payload — at minimum we already showed wrapper prose
            // above (if acceptable). End the turn.
            finishTurn(emptyList())
            return
        }

        // Hydrate event_ids referenced by the response so the UI can show
        // titles/venues/times without an additional fetch on render.
        val eventIds = collectEventIdsFor(structured)
        val hydrated = if (eventIds.isNotEmpty()) {
            try { eventRepo.getEventsByIds(eventIds.toList()).associateBy { it.id } } catch (_: Throwable) { emptyMap() }
        } else emptyMap()

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

    /** Add an event to the draft tray. Idempotent (no-op if already present). */
    fun addToDraftTray(event: live.localapp.local.domain.model.Event) {
        val current = _uiState.value.draftTray
        if (current.any { it.eventId == event.id }) return
        val draft = live.localapp.local.domain.model.DraftTrayEvent(
            eventId = event.id,
            title = event.title,
            startAt = event.startAt,
            timeLabel = event.dateDisplay.substringAfter(",").trim().ifBlank { "Time TBD" },
            dateLabel = event.dateDisplay.substringBefore(",").ifBlank { "" },
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

    /** Tap a chip / footer action — treat as a structured user message to Bo. */
    fun sendDraftTrayChip(intent: String) {
        if (intent.isBlank()) return
        sendMessage(intent)
    }

    /** Build CTA — ship the whole tray to Bo. */
    fun buildFromDraftTray() {
        val tray = _uiState.value.draftTray
        if (tray.isEmpty()) return
        val intent = "Build a plan from these specific event_ids: " +
            tray.joinToString(", ") { it.eventId } +
            ". Use exactly these events, derive each slot's date and time from the event row's start_at, and add transit + meal stops in any gaps over 2 hours."
        sendMessage(intent)
    }

    private fun finishTurn(chips: List<String>) {
        val quickReplies = chips.map { label ->
            val isApprove = label.contains("Looks good", ignoreCase = true)
            val isBuildFromNarration = label.equals("Build this as a plan", ignoreCase = true)
            QuickReply(
                label = label,
                action = when {
                    isApprove -> "save_plan"
                    isBuildFromNarration -> "build_plan_from_narration"
                    else -> label
                },
                isGreen = isApprove || isBuildFromNarration,
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
            finishTurn(withApproveChip(emptyList()))
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
                    attendees = buildList {
                        val initial = userPrefsRepo.getLocalFirstName()?.firstOrNull()?.uppercase() ?: "Y"
                        add(PlanAttendee(id = "self", name = "You", initials = initial, confirmed = true, avatarColor = 0xFFFF6B6B))
                        val count = state.planAttendeeCount.coerceAtLeast(1)
                        if (count > 1) {
                            repeat(count - 1) { i ->
                                add(PlanAttendee(id = "guest_$i", name = "+1", initials = "+1", confirmed = false, avatarColor = 0xFFAB83A1))
                            }
                        }
                    },
                    createdAt = "",
                    dateDisplay = "",
                    groupSize = if (state.planAttendeeCount > 1) state.planAttendeeCount else null,
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
        val seenEventIds = mutableSetOf<String>()
        val seenNames = mutableSetOf<String>()
        // Diagnostic: log the input plan so we can see what Claude actually
        // produced before any dedup runs. Helps catch cases where Claude
        // emits the same slots on every day of a multi-day plan.
        days.forEachIndexed { i, day ->
            println("removeDuplicateSlots[in]: day $i '${day.label}' slots=${day.slots.map { "${it.name}|eid=${it.eventId}" }}")
        }
        return days.map { day ->
            val kept = day.slots.filter { slot ->
                // Normalize: lowercase, strip punctuation/whitespace so
                // "Toro" / "toro" / "Toro - South End" / "Toro Boston" all
                // collapse to the same key for dedup purposes.
                val nameKey = slot.name
                    .trim()
                    .lowercase()
                    .substringBefore(" - ")
                    .substringBefore(" – ")
                    .substringBefore(", ")
                    .filter { it.isLetterOrDigit() || it == ' ' }
                    .trim()
                val eventId = slot.eventId
                when {
                    eventId != null && eventId in seenEventIds -> {
                        println("removeDuplicateSlots: dropping duplicate event '${slot.name}' from '${day.label}'")
                        false
                    }
                    nameKey.isNotBlank() && nameKey in seenNames -> {
                        println("removeDuplicateSlots: dropping duplicate venue '${slot.name}' (key='$nameKey') from '${day.label}'")
                        false
                    }
                    else -> {
                        if (eventId != null) seenEventIds.add(eventId)
                        if (nameKey.isNotBlank()) seenNames.add(nameKey)
                        true
                    }
                }
            }
            day.copy(slots = kept)
        }
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

    /** Simple geocoder for known cities so search works when user asks for a different location. */
    private fun geocodeCity(city: String): Pair<Double, Double>? {
        val known = mapOf(
            "boston" to (42.3601 to -71.0589),
            "cambridge" to (42.3736 to -71.1097),
            "somerville" to (42.3876 to -71.0995),
            "waltham" to (42.3765 to -71.2356),
            "brookline" to (42.3318 to -71.1212),
            "newton" to (42.3370 to -71.2092),
            "worcester" to (42.2626 to -71.8023),
            "springfield" to (42.1015 to -72.5898),
            "lowell" to (42.6334 to -71.3162),
            "providence" to (41.8240 to -71.4128),
            "newport" to (41.4901 to -71.3128),
            "new york" to (40.7128 to -74.0060),
            "nyc" to (40.7128 to -74.0060),
            "manhattan" to (40.7831 to -73.9712),
            "brooklyn" to (40.6782 to -73.9442),
            "portland" to (43.6591 to -70.2568),
            "portsmouth" to (43.0718 to -70.7626),
            "burlington" to (44.4759 to -73.2121),
            "hartford" to (41.7658 to -72.6734),
            "new haven" to (41.3083 to -72.9279),
            "northampton" to (42.3251 to -72.6412),
            "salem" to (42.5195 to -70.8967),
            "cape cod" to (41.6688 to -70.2962),
            "pittsfield" to (42.4501 to -73.2454),
            "philadelphia" to (39.9526 to -75.1652),
            // Cape Cod towns and villages — Bo asks for these by name
            // ("dancing in Dennis", "dinner in Hyannis"). Without these
            // entries the search silently falls back to user coords,
            // which surfaces Boston results 90+ mi away.
            "dennis" to (41.7370 to -70.1939),
            "west dennis" to (41.6628 to -70.1736),
            "south dennis" to (41.7228 to -70.1700),
            "east dennis" to (41.7484 to -70.1631),
            "dennis port" to (41.6603 to -70.1289),
            "hyannis" to (41.6529 to -70.2829),
            "barnstable" to (41.7003 to -70.2994),
            "yarmouth" to (41.7029 to -70.2280),
            "south yarmouth" to (41.6717 to -70.1989),
            "west yarmouth" to (41.6451 to -70.2417),
            "harwich" to (41.6817 to -70.0739),
            "harwich port" to (41.6726 to -70.0742),
            "chatham" to (41.6815 to -69.9597),
            "orleans" to (41.7898 to -69.9897),
            "brewster" to (41.7587 to -70.0820),
            "eastham" to (41.8298 to -69.9747),
            "wellfleet" to (41.9337 to -69.9658),
            "truro" to (41.9959 to -70.0508),
            "north truro" to (42.0354 to -70.0858),
            "provincetown" to (42.0584 to -70.1786),
            "falmouth" to (41.5512 to -70.6151),
            "woods hole" to (41.5240 to -70.6717),
            "mashpee" to (41.6487 to -70.4795),
            "sandwich" to (41.7590 to -70.4937),
            "bourne" to (41.7406 to -70.5969),
            "buzzards bay" to (41.7460 to -70.6184),
            // Islands
            "nantucket" to (41.2835 to -70.0995),
            "martha's vineyard" to (41.4537 to -70.6035),
            "marthas vineyard" to (41.4537 to -70.6035),
            "vineyard haven" to (41.4537 to -70.6035),
            "edgartown" to (41.3893 to -70.5131),
            "oak bluffs" to (41.4543 to -70.5570),
        )
        val key = city.lowercase().trim()
        return known[key] ?: known.entries.firstOrNull { key.contains(it.key) }?.value
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

        val todayDate = kotlin.time.Clock.System.now().toString().take(10)
        val now = kotlinx.datetime.Instant.fromEpochMilliseconds(kotlin.time.Clock.System.now().toEpochMilliseconds())
        val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
        val todayLocal = now.toLocalDateTime(tz)
        val dayOfWeek = try {
            todayLocal.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        } catch (_: Exception) { "" }
        val currentTimeLabel = try {
            val hour = todayLocal.hour
            val minute = todayLocal.minute
            val amPm = if (hour < 12) "AM" else "PM"
            val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
            "$displayHour:${minute.toString().padStart(2, '0')} $amPm"
        } catch (_: Exception) { "" }

        // Two-tier calendar: a spelled-out 14-day window (impossible to misread)
        // plus the compact 90-day reference for further-out lookups.
        val dowFull = arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        val dowAbbrev = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val monthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val monthFull = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        val baseEpochDay = try { todayLocal.date.toEpochDays() } catch (_: Exception) { 0 }

        val next14Days = try {
            (0..13).joinToString("\n") { offset ->
                val d = kotlinx.datetime.LocalDate.fromEpochDays(baseEpochDay + offset)
                val dow = dowFull[d.dayOfWeek.ordinal]
                val month = d.toString().substring(5, 7).toInt()
                val day = d.toString().substring(8, 10).toInt()
                val label = when (offset) { 0 -> " (TODAY)"; 1 -> " (TOMORROW)"; else -> "" }
                "  $dow, ${monthFull[month - 1]} $day$label"
            }
        } catch (_: Exception) { "" }

        val calendarRef = try {
            (14..89).joinToString(" ") { offset ->
                val d = kotlinx.datetime.LocalDate.fromEpochDays(baseEpochDay + offset)
                val dow = dowAbbrev[d.dayOfWeek.ordinal]
                val month = d.toString().substring(5, 7).toInt()
                val day = d.toString().substring(8, 10).toInt()
                "${monthNames[month - 1]}$day=$dow"
            }
        } catch (_: Exception) { "" }

        // Pre-computed "next [weekday]" mapping so Bo never has to do day
        // arithmetic itself. "Next Tuesday" means the next Tuesday at
        // least 1 day from today (so when today IS a Tuesday, "next
        // Tuesday" = 7 days out, not today). Bo was off-by-one on this
        // ("next Tuesday, May 6" when today was Apr 30 / next Tue = May 5).
        val nextWeekdayAnchor = try {
            val todayDowOrdinal = todayLocal.date.dayOfWeek.ordinal
            (0..6).joinToString("\n") { i ->
                val daysAhead = (i - todayDowOrdinal + 7) % 7
                val effective = if (daysAhead == 0) 7 else daysAhead
                val d = kotlinx.datetime.LocalDate.fromEpochDays(baseEpochDay + effective)
                "  next ${dowFull[i]} = ${monthFull[d.monthNumber - 1]} ${d.dayOfMonth}"
            }
        } catch (_: Exception) { "" }

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
1. STAY IN SCOPE. You ONLY help users discover local events/venues and build plans. You do not engage with off-topic chat (history, geography, jokes, advice, biography, definitions, news, weather alone, sports scores, math, code, recipes, debates, therapy, current events, etc.). For ANY off-topic message, call present_scope_redirect with one redirect chip — do not free-text an answer, do not "just this once," do not be helpful in another domain.
2. NEVER PRESENT AN EVENT ON THE WRONG DAY. The day a user sees an event under (group label, plan slot's day, "Next: <date>" line, anything) MUST equal the calendar day of that event's `start_at` in the user's local timezone. If you cannot verify the day, do NOT include the event. Never bucket a Friday event under a Thursday or Saturday label. Never say "Next: May 9" for an event whose local start_at is May 8.
3. PROXIMITY FIRST. Default to events closest to the user. Search at the user's coords first; only widen the radius if the first pass returns < 3 results AND the user has NOT named a different city/town. If the user did name a location ("Cape Cod", "Concord"), search there — never silently fall back to the user's coords on a UNRESOLVED_LOCATION signal, never substitute a different city.
4. NEVER MISCATEGORIZE. An "outdoor events" group must contain only outdoor events; an "arts" group must contain only arts; etc. Before submitting present_suggestions, check every event in every group against its label and DROP any event whose `category`/`subcategory` doesn't fit. If that empties a group, drop the group. Better to return fewer accurate picks than to pad with unrelated events. The client also re-buckets by actual category/day, but mismatches in your JSON are a bug — fix it on your side too.

INTENT: ${detectIntent(lastUserMessage)}
- info: user is asking quick questions / browsing options. Stay short, list 2-3 specific picks, do NOT proactively offer a plan unless asked. Tools are search-only.
- plan: user wants a buildable itinerary. Call present_plan immediately — the user sets group size from the plan detail screen, not in chat. Don't narrate the plan in prose.

BREVITY (hard rule, mobile chat):
- Non-plan responses: 1-3 sentences max. No multi-paragraph essays. No bullet lists in chat — surface choices via plan cards, not prose.
- One question at a time. Never stack two clarifying questions in the same response.
- If you can search and just deliver an answer, do that instead of asking another question.

SEARCH-FIRST (hard rule):
- When the user names ANY intent — an activity ("dancing", "outdoors", "free things"), a venue ("Sundancer's"), a category ("music"), a date ("Tuesday"), a vibe ("cozy") — your VERY NEXT action is a tool call. Do NOT ask "what kind of music?" or "live music or just outdoors?" before searching.
- Search broadly on partial info, deliver something real, let the user refine. Search is fast (<1s); conversation round-trips are 5-10s. Don't gatekeep.
- Do NOT ask any follow-up question before searching. Group size in particular is collected from the plan detail UI, not in chat — never ask "how many?".

RESPONSE SHAPING (Phase 6 — when to use structured tools):
- For BROWSING intents (open-ended discovery — "what's happening Saturday?", "arts events this week", "free things to do tonight"), ALWAYS call present_suggestions instead of describing events as paragraph text. Each event_id in the response must come from a search_events result. The tray UI lets the user pick events with one tap; you don't need to ask "which one?".
- present_suggestions GROUP LABELS — when groupBy="day", every event in a group MUST actually fall on that day per its start_at field. If the user asked for Sunday but the search returned a Saturday event, label that group "Saturday, May 9", not "Sunday, May 10". Never bucket events under the user's requested day if their actual start_at is a different day — the client re-labels for you anyway, but mismatched labels in the JSON pollute logs and waste tokens.
- For SINGLE-EVENT factual questions ("what time is Springsteen?", "how much for LCD tickets?"), call present_event_brief with a one-line answer.
- For SINGLE-VENUE factual questions ("what's at TD Garden?", "when does the ICA close?"), call present_venue_brief.
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

DRAFT TRAY HANDOFF (Phase 6):
- When the user sends "[Build intent from suggestion tray] event_ids: [list]…", that is the structured Build CTA from the iOS Suggestion-tray "Create plan" button. Do NOT call present_plan immediately. First, in ONE concise sentence (≤ 20 words), ask whether they want to add anything else to the plan, or whether they already have something planned for the day(s) those events fall on. Wait for their reply.
- On the user's reply: call present_plan using the original event_ids PLUS any extras the user named. Slots must use those EXACT event_ids — never re-resolve from titles, never substitute. Slot start_at/time MUST come from each event's stored start_at field. Layer transit and meal stops in any gap > 2 hours.
- The legacy variant "Build a plan from these specific event_ids: [list]…" (Compose draft tray) follows the SAME pre-build question rule — ask once, then build on the user's reply.

SCOPE — what you are and aren't (highest-priority rule, single biggest failure mode):
- You help users plan local events, venues, and experiences in your coverage area. That is your only job.
- You do NOT answer general-knowledge questions: history, geography, biography, current news, sports scores, recipes, homework, advice, biology, etc. Even if you "know" the answer, you don't share it.
- When the user mentions ANY noun (e.g. "Lalibela", "Roxbury", "Symphony"), do NOT assume it's a topic to research. Treat it as a possible local event/venue/neighborhood: run search_events AND search_venues with that token. If both return nothing, reply ONE sentence saying you didn't find a local match, and offer ONE concrete redirect ("Did you mean a restaurant nearby?" / "Want me to search for events with that vibe?"). DO NOT volunteer history, definitions, or trivia about the noun.
- Do NOT use web_search to "explain" or "describe" something. web_search is for facts ABOUT events/venues we already have (hours, prices, weather), not a research engine.

SEARCH DEPTH — keep digging before giving up (hard rule):
- If search_events returns < 3 results AND the user is browsing/open-ended, you MUST run a SECOND search_events with broadened terms before responding. Try in order: drop the keyword, drop the category filter, widen the radius.
- EXCEPTION: when the user named a specific calendar date, the DATE PRECISION rule applies — keep the date window pinned. Do not widen dates as a depth fallback; that silently answers a different question than what was asked.
- Same for search_venues.
- Never say "I couldn't find anything" after a single search. The user will assume our database is empty when actually you searched too narrowly.

EXCLUSIONS — when the user says "not X":
- Phrases like "not Red Sox", "something other than X", "no Y", "anything but Z" are EXCLUSIONS. The next response MUST contain ZERO results matching the excluded item.
- Re-run search_events / search_venues with the excluded term explicitly filtered. Do NOT recycle prior results — they may include the excluded thing.
- Track exclusions across the whole conversation. If the user said "not Red Sox" three turns ago, do not surface a Red Sox event on this turn either.

DATE GROUNDING — never derive dates from text (hard rule):
- When you build or edit a slot in present_plan / update_plan, the slot's `time` and the day it lands in MUST come from the event row's actual `start_at` field returned by search_events or get_event_details.
- Never compute a date from words like "tomorrow" or "the show on the 24th" without confirming against the event row. If you can't see the event's start_at, call get_event_details before placing it.
- An event dated 2026-05-24 belongs in the day labeled "Saturday, May 24, 2026", not the next day. Slot dates are derived from event start_at, not from your reasoning.

Today: $todayDate ($dayOfWeek)
Current time: $currentTimeLabel (user's local time)

NEXT 14 DAYS (use this as your ONLY source of truth for which day falls on which date):
$next14Days

"NEXT [WEEKDAY]" — pre-computed (use these directly, never recompute):
$nextWeekdayAnchor

DATE HANDLING RULES (strict):
- When the user says "next Tuesday" / "next Friday" / etc., use the date from the "NEXT [WEEKDAY]" table above verbatim. Do NOT count days yourself — you have repeatedly miscounted by one. The table is the single source of truth.
- If the user gives an explicit date, the explicit date wins. Otherwise the table wins.
- When referencing a specific event from search_events results, quote its date and time EXACTLY as returned. Do not paraphrase ("next Saturday"), approximate ("around 7 PM"), or shift by a day — copy the values verbatim from the tool result.
- TBD-TIME EVENTS: when a search_events result shows a time of "TBD", that means the database has the event's date but no source-confirmed start time (most often recurring activities, walking tours, mall walks, etc.). For these: in present_plan slots, pick a sensible time-of-day yourself based on the activity type ("9 AM", "11 AM", "2 PM", etc.) and pass it in the slot's `time` field — the system trusts your time when the DB time is TBD. Don't pass "TBD" as the slot time; pick a real one.
- Never invent, infer, or guess an event date. If search_events didn't return a date, say so; don't fill one in.
- When the user mentions a day by name ("Saturday"), resolve it against the NEXT 14 DAYS table above, not from memory.
- "Tonight", "now", "right now", and "later today" mean AFTER Current time. Every slot in a same-day plan must start at least 30 minutes after Current time. If the user asks for a same-day plan and you can't find anything later tonight, tell them honestly rather than proposing a slot in the past. Default dinner/date times like "6:30 PM" are NOT acceptable if Current time is already past them.

DATE PRECISION ON SEARCH (strict):
- When the user names a specific calendar date (e.g. "May 15", "the 15th", "next Friday"), call search_events with BOTH start_date AND end_date set to that exact date in YYYY-MM-DD form. Resolve weekday names against the NEXT 14 DAYS table first.
- Example: user says "May 15" → search_events(start_date="2026-05-15", end_date="2026-05-15"). Do NOT pass date_range="this_weekend" or "next_weekend" as a substitute, and do NOT omit end_date — both fields are required for single-date queries.
- If a single-day search returns zero results, your single retry may broaden by ±1 day (start_date one day earlier, end_date one day later). Do NOT widen further without explicit user permission — silently shifting the search to a different day misleads the user.
- If even the ±1 retry returns nothing, say so honestly in ONE sentence ("Nothing's surfacing for that Friday — want me to try the day before or after?"). Never claim a specific date is "a gap"; that exposes the data layer.

NO PREAMBLE BEFORE OR BETWEEN SEARCHES + NO SCHEMA LEAKAGE (strict):
- Do NOT say "Let me search…", "Let me check…", "I'll look that up…", "Let me search the web…", "Let me try a broader search…", "I'm not finding X, let me try Y…", or any similar narration before OR between tool calls. The UI shows a searching indicator the whole time — anything you write between a search_events result and your next search_events call is invisible noise. Discard it; just emit the next tool call.
- This applies to multi-search turns: when you decide to retry with broader terms, do NOT explain why first. Run the next search silently. The user sees only the final answer.
- Do NOT reference internals: "the database", "our database", "the system", "I don't have any specific events in the database", "the database shows X but Y is a gap", "based on my research", "I'll search the web", "checking online sources", "I have access to". The user does not know these exist. From their perspective you just know things, the way a smart concierge would.
- Bad: "I don't have any specific events in the database for June 1-3 in Nantucket, but based on my research…" — exposes both the DB and the search.
- Bad: "I'm not finding events specifically on Friday, May 15 in the current results. Let me try a broader search to see what's available that weekend:" — three sins (preamble narration, schema leakage, mid-turn explanation).
- Good: "Early June is a wonderful time to visit — the weather's nice, fewer crowds than peak summer, and most spots are open. Want me to put together a plan?"
- Just call the tool. When you respond AFTER the final tool finishes, deliver the answer directly without disclaimer about how you got it.
- Bad: "Let me search for Nantucket events…" → tool call → "Here's what I found…"
- Good: tool call → "There are three things on June 1: …"

GEOGRAPHIC INTEGRITY (no exceptions, hard rule):
- When the user is planning for a specific destination (a city, town, island, or area like "Nantucket", "Cape Cod", "Boston"), EVERY slot in the plan must be in or immediately near that destination. "Immediately near" = same town or directly adjacent (≤ 15 miles), NOT a 2-hour drive away.
- "Surfside Beach" is a generic name. There is a Surfside Beach in Nantucket AND one in Plymouth, MA (and others). When you pass a venue to recommend_and_save_venue with city="Nantucket" but the system resolves to Plymouth, that's a wrong match — DO NOT include it in the plan. Instead, pass a more specific name like "Surfside Beach Nantucket" or use a venue Bo can verify is actually on the island (e.g. "Cisco Brewers Nantucket", "Brant Point Lighthouse").
- Before adding a slot to a plan, sanity-check: would the user have to drive >30 minutes from the destination's center to get there? If yes, drop it. The whole point of a "Nantucket vacation" is to stay on Nantucket.
- For islands specifically: Nantucket and Martha's Vineyard are reachable only by ferry/plane. A slot on the mainland (Plymouth, Hyannis, Falmouth) is NEVER acceptable for an island vacation plan unless the user explicitly asked to spend a day on the mainland.

DATE OVERLAP INTEGRITY (no exceptions, hard rule):
- An event whose date is OUTSIDE the user's stated trip/plan window does NOT "overlap" with their dates. Compute the comparison literally: window = [user_start, user_end] (inclusive). Event date must be within that range.
- Example: User says "June 1-3". You find "Nantucket Book Festival, June 11-14". June 11 > June 3. THIS DOES NOT OVERLAP. Saying "great, the Book Festival overlaps with your dates" is a HALLUCINATION — never do this.
- When you find an event outside the window, you have exactly two acceptable moves:
  1. Drop it silently and keep searching for events that ARE inside the window.
  2. Flag it honestly and offer to shift: "I also found the Nantucket Book Festival on June 11-14, which is after your dates. Want to extend your trip, or stick with June 1-3?"
- Never fudge dates to make an event fit. Never say "around your dates", "close to your dates", "during your trip" when the dates don't actually fall in the window.

Extended calendar (day 15+): $calendarRef
Location: ${state.currentLocationName.ifBlank { state.userPreferences?.city?.replaceFirstChar { it.uppercase() } ?: "their area" }} (lat: ${state.currentLat ?: state.userPreferences?.let { if (it.locationMode == "manual") it.manualLat ?: it.userLat else it.userLat } ?: "unknown"}, lng: ${state.currentLng ?: state.userPreferences?.let { if (it.locationMode == "manual") it.manualLng ?: it.userLng else it.userLng } ?: "unknown"})

HOW TO HELP:
- When the user wants to plan something, search the database FIRST, then present a plan using the present_plan tool. Search broadly — make multiple search_events calls across different categories and date ranges to find everything relevant. The more you search, the better your recommendations. When offering to build a plan, mention that it's an easily shareable plan (e.g. "Want me to build you a shareable plan?" or "I can put together a plan you can share with your group").
- When the user confirms a plan ("yes", "sure", "build it", "do it"), your NEXT message MUST be a present_plan tool call. Single-stop plans go through present_plan too. Never describe a plan in prose — the prose is invisible (no card renders). One slot is fine.
- present_plan slots: use event_id when a specific event exists (concerts/shows/workshops/games); use venue_name for restaurants/bars/cafes. Don't paste random event_ids "just to have one" — that breaks slot rendering.
- WHEN GIVEN A SPECIFIC EVENT_ID (critical): If the user message contains an explicit `evt:abc123` token (e.g. "build a plan around my saved event 'X' (event_id: evt:abc123)"), use that EXACT event_id verbatim in the present_plan slot — do NOT call search_events to find it again, do NOT substitute the venue. The user has already saved this exact event; replacing it with a different event or with venue_name "TD Garden" is a bug. Use `event_id: "abc123"` (strip the `evt:` prefix) directly in the slot. You can still build OTHER slots around this anchor (pre-game dinner, post-event drinks), but the anchor slot itself must be the literal event the user named.
- DO NOT ask "How many people?" or any other group-size question. Group size is collected from the plan detail screen via a dedicated UI, not in chat. When calling present_plan, you may infer attendee_count from context if the user volunteered it ("date night" = 2, "double date" = 4, "just me" / solo = 1) — otherwise omit it (the client defaults to 1) and the user will set it from the plan UI.
- ATTENDEE-COUNT VOLUNTEERED UPDATES (when user proactively says it): If the user states or changes the group size at ANY point — "actually make it 4", "we picked up two more friends", "it'll just be me now" — you MUST reflect it. Two ways: (a) if you're already calling update_plan to change slots, include attendee_count in that same call; (b) if no slots are changing, still call update_plan with the current day_index, the existing day's slots unchanged, and the new attendee_count. NEVER respond in text alone to a group-size change — without an update_plan call the count won't be saved. This rule fires only when the user volunteers the count; do NOT ask for it.
- HONOR USER-STATED COMMITMENTS (critical): If the user mentions an existing plan, ticket, reservation, or commitment ("we already have Red Sox tickets Thursday", "I'm seeing a show Friday at 8", "we have a 7pm dinner res Saturday"), that commitment MUST appear as a slot on the matching day in the plan you build — never silently ignore it or schedule competing activities at the same time. Steps: (1) call search_events with the team/artist/event name AND start_date/end_date set to the day they mentioned; (2) if you find a matching event, use its event_id in the slot; (3) if no DB match, include a venue_name slot with the user's stated detail (e.g. venue_name="Red Sox @ Fenway Park", note="(your existing plans)") so it still appears on that day's card. Build the rest of the day around that fixed slot — pre-game dinner before, drinks after, etc. — and respect the time conflict.
- DO NOT ASSUME FOOD OR DRINKS. Never auto-add a dinner, brunch, coffee, bar, or cocktail stop unless the user has signaled they want food/drinks. "Date night" alone does NOT imply dinner. "Things to do Saturday" does NOT imply brunch. If the user hasn't mentioned eating/drinking, ask once before building the plan: "Want me to include a meal or a drink stop, or just activities?" Only skip the question when food/drink is explicitly in the request ("dinner and a show", "brunch plans", "where should we eat", "bar crawl", "coffee then a museum", etc.).
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
- MULTI-DAY PLANS — DO NOT REPEAT (critical): When building a plan with multiple days, EVERY day must have its own distinct events and venues. Never copy Day 1's slots into Day 2/3/4 to "fill space." If you genuinely cannot find enough distinct, time-appropriate, date-appropriate options for a given day, that day should have FEWER slots — even just one — rather than recycled slots from another day. A 4-day plan where every day has the same restaurant and event is a BUG; it's better to give the user a 1-day plan and explain you didn't have enough distinct coverage for the others.
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
- NO MARKDOWN FORMATTING: this chat does NOT render markdown. Do not use **bold**, *italics*, `code`, headings (#), bullet lists (- or *), or numbered lists. Asterisks and pound signs render as literal characters. When you want to present options, write them as plain prose sentences or short newline-separated lines without any leading symbols. Example — instead of "**Option 1: Boston Children's Museum** - Perfect for families", write "Option 1 — Boston Children's Museum: perfect for families." (em-dashes and colons are fine; markdown syntax is not).
- Don't ask more than one clarifying question before building a plan. If the user gives you enough to work with, just build it.

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
        val msg = when {
            errorMsg.contains("ANTHROPIC_API_KEY not configured") ->
                "The API key isn't set up in Supabase secrets yet. Add ANTHROPIC_API_KEY to your project secrets."
            errorMsg.contains("401") || errorMsg.contains("403") || errorMsg.contains("authentication") ->
                "I'm having trouble connecting — check that ANTHROPIC_API_KEY is set in Supabase secrets."
            errorMsg.contains("429") || errorMsg.contains("rate") ->
                "I'm getting rate-limited right now. Give it a few seconds and try again."
            errorMsg.contains("502") || errorMsg.contains("Failed to proxy") ->
                "Couldn't reach Claude through the proxy. Check your Supabase edge function."
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
        _uiState.update { it.copy(
            messages = it.messages + ChatMessage(role = MessageRole.AI, content = MessageContent.Text(trimmed)),
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
