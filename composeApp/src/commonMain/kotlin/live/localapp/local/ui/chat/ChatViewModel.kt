package live.localapp.local.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    }

    fun resetChat() {
        conversationHistory.clear()
        _uiState.value = ChatUiState()
        showWelcome()
    }

    // ── Core: Single unified message handler ──

    /** Stores the last user message for APPROVE detection. */
    private var lastUserMessage: String = ""

    private fun handleMessage(text: String) {
        _uiState.update { it.copy(quickReplies = emptyList()) }
        searchResultCache.clear()
        lastUserMessage = text
        viewModelScope.launch {
            setBusy(true)
            appendTextToHistory("user", text)

            var attempts = 0
            val maxAttempts = 2
            while (attempts < maxAttempts) {
                try {
                    val systemPrompt = buildSystemPrompt()
                    val parsed = sendWithToolLoop(systemPrompt)
                    println("Claude response: type=${parsed.type} text=${parsed.text.take(100)} days=${parsed.days.size} chips=${parsed.chips.size}")
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
                    // If Claude already streamed partial text in this round, commit
                    // it as a real message before entering the search state — otherwise
                    // the partial response disappears and the UI reverts to the idiom.
                    if (!isToolRound && streamBuffer.isNotEmpty()) {
                        val pending = streamBuffer.toString().trim()
                        if (pending.isNotEmpty()) addAiMessage(pending)
                    }
                    _uiState.update { it.copy(isSearching = true, streamingText = "") }
                    streamBuffer.clear()
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
    private val terminalTools = setOf("present_plan", "update_plan")

    /**
     * Build a ParsedChatResponse from a present_plan or update_plan tool call.
     */
    private fun parsePlanToolCall(
        toolCall: live.localapp.local.data.remote.ToolCall,
        text: String,
    ): ParsedChatResponse {
        val input = toolCall.input
        val chips = input["chips"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

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
    private suspend fun sendWithToolLoop(systemPrompt: String): ParsedChatResponse {
        val maxToolRounds = 5
        var toolRounds = 0
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
            // Always stream text to UI for a conversational feel.
            // If Claude calls tools on round 0, we'll clear the streaming text.
            val response = sendStreaming(augmentedSystemPrompt, toolsForTurn, isToolRound = false, model = currentModel)
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
                    val toolResultBlocks = kotlinx.serialization.json.buildJsonArray {
                        for (call in searchCalls) {
                            val result = executeToolCall(call.name, call.input)
                            add(kotlinx.serialization.json.buildJsonObject {
                                put("type", JsonPrimitive("tool_result"))
                                put("tool_use_id", JsonPrimitive(call.id))
                                put("content", JsonPrimitive(result))
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

                // Check for approval based on user message
                val isApproval = _uiState.value.days.isNotEmpty() && lastUserMessage.lowercase().let { msg ->
                    msg.contains("looks good") || msg.contains("approve") ||
                        msg.contains("save") || msg.contains("add to my plans")
                }

                return ParsedChatResponse(
                    type = if (isApproval) ParsedResponseType.APPROVE else ParsedResponseType.CHAT,
                    text = text.ifEmpty { "Sorry, I had trouble generating a response. Could you try again?" },
                )
            }

            // Show searching indicator on first tool call round and switch to Opus
            if (toolRounds == 0) {
                _uiState.update { it.copy(isSearching = true, streamingText = "") }
                currentModel = live.localapp.local.data.remote.ClaudeApiService.MODEL_PLAN
                println("ChatVM: switching to Opus for tool use")
            }

            // Check tool round cap
            toolRounds++
            if (toolRounds >= maxToolRounds) {
                println("Tool loop capped at $maxToolRounds rounds — forcing completion")
                val assistantBlocks = claudeApi.buildAssistantContentBlocks(response)
                conversationHistory.add(ClaudeMessage.blocks("assistant", assistantBlocks))
                val anchor = dateAnchorText()
                val toolResultBlocks = kotlinx.serialization.json.buildJsonArray {
                    for (call in toolCalls) {
                        val result = executeToolCall(call.name, call.input)
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("type", JsonPrimitive("tool_result"))
                            put("tool_use_id", JsonPrimitive(call.id))
                            put("content", JsonPrimitive(anchor + result))
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

            // Regular tool execution — add response to history and continue loop
            val assistantBlocks = claudeApi.buildAssistantContentBlocks(response)
            conversationHistory.add(ClaudeMessage.blocks("assistant", assistantBlocks))

            val anchor = dateAnchorText()
            val toolResultBlocks = kotlinx.serialization.json.buildJsonArray {
                for (call in toolCalls) {
                    println("Executing tool: ${call.name} with input: ${call.input}")
                    val result = executeToolCall(call.name, call.input)
                    println("Tool result (${call.name}): ${result.take(200)}")
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("type", JsonPrimitive("tool_result"))
                        put("tool_use_id", JsonPrimitive(call.id))
                        put("content", JsonPrimitive(anchor + result))
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

        // If specific dates provided, use custom date range instead of keyword
        if (startDate != null && dateRange == null) {
            dateRange = "custom"
        }

        // If Claude passes a specific location different from user's current,
        // use that location's coordinates for the geo search
        val state = _uiState.value
        val locationCoords = if (!location.isNullOrBlank()) geocodeCity(location) else null
        val prefs = state.userPreferences
        val prefsEffectiveLat = prefs?.let { if (it.locationMode == "manual") it.manualLat ?: it.userLat else it.userLat }
        val prefsEffectiveLng = prefs?.let { if (it.locationMode == "manual") it.manualLng ?: it.userLng else it.userLng }
        val userLat = locationCoords?.first ?: state.currentLat ?: prefsEffectiveLat
        val userLng = locationCoords?.second ?: state.currentLng ?: prefsEffectiveLng
        println("searchEvents: lat=$userLat lng=$userLng location=$location currentLocationName=${state.currentLocationName} geocoded=${locationCoords != null}")

        val rawEvents = eventRepo.searchEvents(
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

        // Cap what Claude sees to keep token usage manageable
        val totalFound = events.size
        val displayEvents = events.take(100)

        if (displayEvents.isEmpty()) return "No events found matching your search."

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
                    appendLine("You have TWO Anthropic-managed tools available right now: web_search and web_fetch.")
                    appendLine("")
                    appendLine("MANDATORY use cases — you MUST call web_search (no exceptions, do NOT respond from training data):")
                    appendLine("  • Open hours, closing times, schedules of any specific business")
                    appendLine("  • Today/tomorrow/this-weekend weather")
                    appendLine("  • Current sports scores, recent news, anything 'this week' or 'tonight'")
                    appendLine("  • Any specific named event/venue/restaurant where the user wants real, current info")
                    appendLine("  • When search_events / search_venues returns 0 results")
                    appendLine("")
                    appendLine("CRITICAL: Do NOT say 'I don't have real-time data', 'I'd recommend checking weather.com', or anything")
                    appendLine("similar. You DO have web_search. USE IT. If you've been told elsewhere not to admit lacking real-time")
                    appendLine("info, that's because we now give you the tool — call it instead.")
                    appendLine("")
                    appendLine("Use web_fetch when the user pastes a URL. Cap yourself at 3 search invocations per turn.")
                    appendLine("Always try database tools (search_events, search_venues) first when the question is about")
                    appendLine("local events/venues we might already have indexed.")
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
                // Cache the input so the slot builder can render without re-fetching.
                val id = kotlinx.serialization.json.Json.parseToJsonElement(body)
                    .jsonObject["id"]?.jsonPrimitive?.contentOrNull
                if (id != null) boWebFindingsCache[id] = input
                body  // {"id":"<uuid>"}
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

    // ── Process Claude's parsed natural language response ──

    private suspend fun handleClaudeResponse(parsed: ParsedChatResponse) {
        // Clear streaming text immediately — stream is done, permanent messages take over
        _uiState.update { it.copy(streamingText = "") }

        val responseText = parsed.text

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
                val validatedDays = removeDuplicateSlots(datedDays)

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

    private fun finishTurn(chips: List<String>) {
        val quickReplies = chips.map { label ->
            QuickReply(
                label = label,
                action = if (label.contains("Looks good", ignoreCase = true)) "save_plan" else label,
                isGreen = label.contains("Looks good", ignoreCase = true),
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
You are Bo, an AI assistant inside a local events and dining app called Local. Talk naturally — be warm, opinionated, and helpful, just like you would in any conversation. Keep responses concise since this is a mobile chat. If a user calls you "Yocal", just continue — that was the previous name; you go by Bo now.

Today: $todayDate ($dayOfWeek)
Current time: $currentTimeLabel (user's local time)

NEXT 14 DAYS (use this as your ONLY source of truth for which day falls on which date):
$next14Days

DATE HANDLING RULES (strict):
- When referencing a specific event from search_events results, quote its date and time EXACTLY as returned. Do not paraphrase ("next Saturday"), approximate ("around 7 PM"), or shift by a day — copy the values verbatim from the tool result.
- TBD-TIME EVENTS: when a search_events result shows a time of "TBD", that means the database has the event's date but no source-confirmed start time (most often recurring activities, walking tours, mall walks, etc.). For these: in present_plan slots, pick a sensible time-of-day yourself based on the activity type ("9 AM", "11 AM", "2 PM", etc.) and pass it in the slot's `time` field — the system trusts your time when the DB time is TBD. Don't pass "TBD" as the slot time; pick a real one.
- Never invent, infer, or guess an event date. If search_events didn't return a date, say so; don't fill one in.
- When the user mentions a day by name ("Saturday"), resolve it against the NEXT 14 DAYS table above, not from memory.
- "Tonight", "now", "right now", and "later today" mean AFTER Current time. Every slot in a same-day plan must start at least 30 minutes after Current time. If the user asks for a same-day plan and you can't find anything later tonight, tell them honestly rather than proposing a slot in the past. Default dinner/date times like "6:30 PM" are NOT acceptable if Current time is already past them.

Extended calendar (day 15+): $calendarRef
Location: ${state.currentLocationName.ifBlank { state.userPreferences?.city?.replaceFirstChar { it.uppercase() } ?: "their area" }} (lat: ${state.currentLat ?: state.userPreferences?.let { if (it.locationMode == "manual") it.manualLat ?: it.userLat else it.userLat } ?: "unknown"}, lng: ${state.currentLng ?: state.userPreferences?.let { if (it.locationMode == "manual") it.manualLng ?: it.userLng else it.userLng } ?: "unknown"})

HOW TO HELP:
- When the user wants to plan something, search the database FIRST, then present a plan using the present_plan tool. Search broadly — make multiple search_events calls across different categories and date ranges to find everything relevant. The more you search, the better your recommendations. When offering to build a plan, mention that it's an easily shareable plan (e.g. "Want me to build you a shareable plan?" or "I can put together a plan you can share with your group").
- CRITICAL: Once the user confirms they want a plan ("yes", "sure", "full night plan", "build it", "let's do it", "no thanks" to extras, etc.) and you have enough info (main activity + attendee count), your VERY NEXT message MUST be a present_plan tool call — even for a single-stop plan. NEVER describe a plan in plain text. NEVER write phrases like "There's your plan!", "Here's your plan", "I've added X to your plans", or "Grab dinner at X, then Y at 8pm" — that renders as chat text and the user sees no plan card, which means no plan was actually saved. Single-stop plans (just the concert, just the show, just the dinner) STILL go through present_plan with one slot — there is no minimum stop count. If your reply is going to confirm or describe a plan in any way, STOP, drop the text, and call present_plan instead. A plain-text description or confirmation of a plan is a BUG.
- IMPORTANT: In present_plan slots, ALWAYS use event_id when a specific event exists (concerts, shows, workshops, tours, exhibitions, games). Only use venue_name for restaurants, bars, and cafes where there is no specific event — just a dining/drinks recommendation. If a concert is at The Sinclair, use the event_id from search_events, NOT venue_name "The Sinclair". The event_id ensures the event title, ticket URL, and source URL are preserved.
- WHEN GIVEN A SPECIFIC EVENT_ID (critical): If the user message contains an explicit `evt:abc123` token (e.g. "build a plan around my saved event 'X' (event_id: evt:abc123)"), use that EXACT event_id verbatim in the present_plan slot — do NOT call search_events to find it again, do NOT substitute the venue. The user has already saved this exact event; replacing it with a different event or with venue_name "TD Garden" is a bug. Use `event_id: "abc123"` (strip the `evt:` prefix) directly in the slot. You can still build OTHER slots around this anchor (pre-game dinner, post-event drinks), but the anchor slot itself must be the literal event the user named.
- ALWAYS gather the number of people attending before presenting a plan. You can infer from context: "date night" = 2, "double date" = 4, "just me" / solo = 1, "family" = ask how many, "friends" = ask how many. If unclear, ask "How many people will be joining?" before calling present_plan. Include the attendee_count in the present_plan call — this is required. Tailor recommendations to group size (e.g. larger groups need venues with more seating, reservations become more important).
- ATTENDEE-COUNT PERSISTENCE (critical): The attendee_count is part of the plan and gets saved when the user taps Save. If the user states or changes the group size at ANY point — even after the plan card is already showing ("actually make it 4", "we picked up two more friends", "it'll just be me now") — you MUST reflect it. Two ways: (a) if you're already calling update_plan to change slots, include attendee_count in that same call; (b) if no slots are changing, still call update_plan with the current day_index, the existing day's slots unchanged, and the new attendee_count. NEVER respond in text alone to a group-size change — without an update_plan call the count won't be saved. Do this even when the count is the only thing changing.
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
