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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import live.localapp.local.data.remote.ClaudeApiService
import live.localapp.local.data.remote.ClaudeMessage
import live.localapp.local.data.remote.ParsedChatResponse
import live.localapp.local.data.remote.ParsedResponseType
import live.localapp.local.data.remote.claudeTools
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
import live.localapp.local.domain.repository.PlanRepository
import live.localapp.local.domain.service.BadgeService
import live.localapp.local.domain.repository.UserPreferences
import live.localapp.local.domain.repository.UserPreferencesRepository
import live.localapp.local.domain.repository.VenueRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ── Message models ──

enum class MessageRole { AI, USER }

sealed interface MessageContent {
    data class Text(val text: String) : MessageContent
    data class DayCardRef(val dayIndex: Int) : MessageContent
    data class DayCardSnapshot(val day: ItineraryDay, val dayIndex: Int, val totalDays: Int) : MessageContent
    data class MultiDayPlanRef(val dayCount: Int) : MessageContent
    data class SummaryRef(val dummy: Unit = Unit) : MessageContent
    data class ProgressRef(val dummy: Unit = Unit) : MessageContent
    data class AlsoHappeningRef(val dummy: Unit = Unit) : MessageContent
}

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
    val ticketUrl: String? = null,
    val venueRating: Double? = null,
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
    // Itinerary state
    val days: List<ItineraryDay> = emptyList(),
    val approvedDayCount: Int = 0,
    val currentDayIndex: Int = 0,
    val planTitle: String = "",
    val planAttendeeCount: Int = 1,
    // Plan success overlay
    val showPlanSuccess: Boolean = false,
    // Auth required prompt
    val showAuthPrompt: Boolean = false,
    // "Also Happening" recommendations
    val recommendations: List<Event> = emptyList(),
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** Cache of all events returned by search_events calls in the current turn. */
    private val searchResultCache = mutableListOf<Event>()

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
            }
            showWelcome()
        }
    }

    // ── Welcome ──

    private fun showWelcome() {
        val userName = _uiState.value.userName
        val greetings = listOf(
            "Hey${if (userName.isNotBlank()) " $userName" else ""}! What are you looking to plan?",
            "What's on your mind? I can plan anything from a single event to a multi-day trip.",
            "${if (userName.isNotBlank()) "$userName — " else ""}Tell me what you're thinking and I'll find the best options.",
            "Ready to discover something new? Tell me what you're in the mood for.",
            "Hey! Date night, weekend plans, or something else entirely?",
        )
        addAiMessage(greetings.random())

        val prefs = _uiState.value.userPreferences
        val defaultChips = mutableListOf(
            QuickReply("Weekend plans"),
            QuickReply("Date night ideas"),
        )
        // Add preference-based chips
        prefs?.selectedCategories?.take(2)?.forEach { cat ->
            defaultChips.add(QuickReply("${categoryDisplayName(cat)} this week"))
        }
        prefs?.selectedNeighborhoods?.firstOrNull()?.let { hood ->
            defaultChips.add(QuickReply("Things to do in $hood"))
        }
        // Fallback chips if no preference-based ones were added
        if (defaultChips.size <= 2) {
            defaultChips.add(QuickReply("Free things to do"))
            defaultChips.add(QuickReply("Live music tonight"))
        }

        _uiState.update {
            it.copy(
                inputEnabled = true,
                inputPlaceholder = "What are you looking for?",
                quickReplies = defaultChips,
            )
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

    fun onQuickReply(reply: QuickReply) {
        if (_uiState.value.isBusy) return
        when {
            reply.action == "reset" -> resetChat()
            reply.action.startsWith("swap_recommendation:") -> handleSwapRecommendation(reply.action)
            else -> sendMessage(reply.label)
        }
    }

    fun removeSlot(dayIndex: Int, slotId: String) {
        val day = _uiState.value.days.getOrNull(dayIndex) ?: return
        val slotName = day.slots.firstOrNull { it.id == slotId }?.name ?: return

        // Remove the slot immediately for instant UI feedback (clear travel since it's stale)
        _uiState.update { state ->
            val days = state.days.toMutableList()
            val d = days[dayIndex]
            val newSlots = d.slots.filter { it.id != slotId }
            days[dayIndex] = d.copy(slots = newSlots, travel = emptyList())
            state.copy(days = days)
        }

        // Check if day is now empty → remove the day entirely and notify
        val updatedDay = _uiState.value.days.getOrNull(dayIndex)
        if (updatedDay != null && updatedDay.slots.isEmpty()) {
            val dayLabel = updatedDay.label
            _uiState.update { state ->
                val days = state.days.toMutableList()
                days.removeAt(dayIndex)
                state.copy(days = days)
            }
            // Add a brief AI message confirming day removal
            addAiMessage("Removed $dayLabel since it had no events left.")
            return
        }

        // Ask Claude to recalculate travel for the updated day
        requestTravelRecalc(dayIndex, "I removed \"$slotName\" from Day ${dayIndex + 1}. Update the travel times between the remaining events.")
    }

    fun moveSlot(dayIndex: Int, fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        _uiState.update { state ->
            val days = state.days.toMutableList()
            val day = days[dayIndex]
            val slots = day.slots.toMutableList()
            val item = slots.removeAt(fromIndex)
            slots.add(toIndex, item)
            days[dayIndex] = day.copy(slots = slots)
            state.copy(days = days)
        }
    }

    fun addCustomSlot(dayIndex: Int, name: String, time: String = "") {
        _uiState.update { state ->
            val days = state.days.toMutableList()
            val day = days[dayIndex]
            val newSlot = ItinerarySlot(
                id = generateId(),
                time = time.ifBlank { "" },
                emoji = "",
                name = name,
                detail = "Your custom event",
                price = "",
                isFree = true,
                isCustom = true,
            )
            // Clear travel since it's now stale
            days[dayIndex] = day.copy(slots = day.slots + newSlot, travel = emptyList())
            state.copy(days = days)
        }

        // Ask Claude to recalculate travel with the new slot
        requestTravelRecalc(dayIndex, "I added \"$name\" at $time to Day ${dayIndex + 1}. Reorder the slots chronologically and update travel times.")
    }

    /**
     * Ask Claude to return a plan_update with recalculated travel times.
     * Sends a silent message (no user bubble) so the plan card refreshes.
     */
    private fun requestTravelRecalc(dayIndex: Int, instruction: String) {
        viewModelScope.launch {
            try {
                appendTextToHistory("user", instruction)
                val systemPrompt = buildSystemPrompt()
                val parsed = sendWithToolLoop(systemPrompt)
                // Only process if Claude returned a plan_update for this day
                if (parsed.type == ParsedResponseType.PLAN_UPDATE && parsed.days.isNotEmpty()) {
                    val builtDays = buildPlanFromRefs(parsed)
                    val updatedDay = builtDays.firstOrNull()
                    if (updatedDay != null) {
                        _uiState.update { state ->
                            val days = state.days.toMutableList()
                            val idx = parsed.dayIndex ?: dayIndex
                            if (idx in days.indices) {
                                days[idx] = updatedDay
                            }
                            state.copy(days = days)
                        }
                    }
                    if (parsed.text.isNotBlank()) {
                        appendTextToHistory("assistant", parsed.text)
                    }
                }
            } catch (e: Throwable) {
                println("Travel recalc failed: ${e::class.simpleName}: ${e.message}")
                // Non-critical — the plan still works, just without updated travel
            }
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

            // Collect all event IDs (non-venue refs)
            val eventIds = parsedDay.events.filter { !it.isVenue }.map { it.id }
            val eventsById = if (eventIds.isNotEmpty()) {
                val fetched = eventRepo.getEventsByIds(eventIds)
                println("buildPlanFromRefs: fetched ${fetched.size}/${eventIds.size} events from DB")
                fetched.associateBy { it.id }
            } else emptyMap()

            // Also check searchResultCache for any events not found in batch query
            val cacheById = searchResultCache.associateBy { it.id }
            println("buildPlanFromRefs: cache has ${cacheById.size} events")

            val slots = parsedDay.events.mapNotNull { ref ->
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
                    )
                } else {
                    // Event ref — look up from DB
                    val event = eventsById[ref.id] ?: cacheById[ref.id]
                    if (event == null) {
                        println("buildPlanFromRefs: event '${ref.id}' not found in DB or cache, dropping")
                        return@mapNotNull null
                    }

                    // Use Claude's suggested time if provided, otherwise derive from event
                    val time = if (ref.time.isNotBlank()) ref.time else {
                        event.startAt?.let { iso ->
                            try {
                                val instant = kotlinx.datetime.Instant.parse(iso)
                                val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                                val hour = local.hour
                                val minute = local.minute
                                val amPm = if (hour < 12) "AM" else "PM"
                                val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
                                if (minute == 0) "$displayHour $amPm" else "$displayHour:${minute.toString().padStart(2, '0')} $amPm"
                            } catch (_: Exception) { "" }
                        } ?: ""
                    }

                    ItinerarySlot(
                        id = generateId(),
                        time = time,
                        emoji = "",
                        name = event.title,
                        detail = ref.note.ifBlank { "${event.venueName ?: ""} · ${event.venueCity}" },
                        price = event.priceDisplay,
                        isFree = event.isFree,
                        imageUrl = event.imageUrl,
                        eventId = event.id,
                        isCustom = false,
                        ticketUrl = event.ticketUrl,
                    )
                }
            }

            // Derive shortName from day label (e.g. "Saturday, April 5" -> "SAT")
            val shortName = parsedDay.label.split(",").firstOrNull()?.trim()?.take(3)?.uppercase() ?: "DAY"

            ItineraryDay(
                label = parsedDay.label,
                shortName = shortName,
                slots = slots.map { it.copy(time = normalizeTimeDisplay(it.time)) }
                    .sortedBy { parseTimeForSort(it.time) },
            )
        }
    }

    fun resetChat() {
        conversationHistory.clear()
        _uiState.value = ChatUiState()
        showWelcome()
    }

    // ── Core: Single unified message handler ──

    /** Stores the last user message for APPROVE detection in parseNaturalResponse. */
    private var lastUserMessage: String = ""

    private fun handleMessage(text: String) {
        _uiState.update { it.copy(quickReplies = emptyList()) }
        searchResultCache.clear()
        lastUserMessage = text
        viewModelScope.launch {
            setBusy(true)
            appendTextToHistory("user", text)

            try {
                val systemPrompt = buildSystemPrompt()
                val parsed = sendWithToolLoop(systemPrompt)
                println("Claude response: type=${parsed.type} text=${parsed.text.take(100)} days=${parsed.days.size} chips=${parsed.chips.size}")
                handleClaudeResponse(parsed)
            } catch (e: Throwable) {
                // Catch Throwable (not just Exception) to handle Kotlin/Native errors
                // that bypass Exception hierarchy (IrLinkageError, etc.)
                println("Claude error: ${e::class.simpleName}: ${e.message}")
                e.printStackTrace()
                try {
                    handleAiError(if (e is Exception) e else RuntimeException(e.message ?: "Unknown error"))
                } catch (_: Throwable) {
                    // Last resort — just show error and re-enable input
                    addAiMessage("Something went wrong. Please try again.")
                    setBusy(false)
                }
            }
        }
    }

    // ── Tool execution loop ──

    /**
     * Send the conversation to Claude and handle the tool-use loop:
     * send → if tool_use → execute tool → append tool_result → send again → repeat
     * until Claude gives a final text (stop_reason == "end_turn").
     *
     * Returns a ParsedChatResponse parsed from Claude's natural language with markers.
     */
    private suspend fun sendWithToolLoop(systemPrompt: String): ParsedChatResponse {
        val maxToolRounds = 5 // Cap tool calls to avoid runaway loops
        var toolRounds = 0

        while (true) {
            val response = claudeApi.sendRaw(
                systemPrompt = systemPrompt,
                messages = conversationHistory,
                tools = claudeTools,
            )

            val toolCalls = claudeApi.extractToolCalls(response)

            // Show searching indicator when Claude is about to use tools
            if (toolCalls.isNotEmpty() && toolRounds == 0) {
                _uiState.update { it.copy(isSearching = true) }
            }

            if (toolCalls.isEmpty() || response.stopReason == "end_turn") {
                val text = claudeApi.extractText(response)
                if (text == null) {
                    println("sendWithToolLoop: No text in Claude response, returning fallback")
                    stripToolMessagesFromHistory()
                    return ParsedChatResponse(
                        type = ParsedResponseType.CHAT,
                        text = "Sorry, I had trouble generating a response. Could you try again?",
                    )
                }

                stripToolMessagesFromHistory()
                appendTextToHistory("assistant", text)

                println("sendWithToolLoop: Parsing natural response, text length=${text.length}")
                println("sendWithToolLoop: First 300 chars: ${text.take(300)}")
                return claudeApi.parseNaturalResponse(text, lastUserMessage)
            }

            // Check if we've hit the tool round cap
            toolRounds++
            if (toolRounds >= maxToolRounds) {
                println("Tool loop capped at $maxToolRounds rounds — forcing completion")
                val assistantBlocks = claudeApi.buildAssistantContentBlocks(response)
                conversationHistory.add(ClaudeMessage.blocks("assistant", assistantBlocks))
                val toolResultBlocks = kotlinx.serialization.json.buildJsonArray {
                    for (call in toolCalls) {
                        val result = executeToolCall(call.name, call.input)
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("type", JsonPrimitive("tool_result"))
                            put("tool_use_id", JsonPrimitive(call.id))
                            put("content", JsonPrimitive(result))
                        })
                    }
                }
                conversationHistory.add(ClaudeMessage.blocks("user", toolResultBlocks))
                conversationHistory.add(ClaudeMessage.text("user", "You have enough information now. Respond with your plan using the natural language format (PLAN:/DAY:/CHIPS:). Do NOT call any more tools."))
                val finalResponse = claudeApi.sendRaw(
                    systemPrompt = systemPrompt,
                    messages = conversationHistory,
                    tools = null,
                )
                val text = claudeApi.extractText(finalResponse)
                if (text == null) {
                    println("sendWithToolLoop: No text after cap nudge, returning fallback")
                    stripToolMessagesFromHistory()
                    return ParsedChatResponse(
                        type = ParsedResponseType.CHAT,
                        text = "I found some great options but had trouble putting them together. Could you try asking again?",
                    )
                }
                stripToolMessagesFromHistory()
                appendTextToHistory("assistant", text)
                return claudeApi.parseNaturalResponse(text, lastUserMessage)
            }

            // Claude wants to use tools — add its full response to history
            val assistantBlocks = claudeApi.buildAssistantContentBlocks(response)
            conversationHistory.add(ClaudeMessage.blocks("assistant", assistantBlocks))

            // Execute each tool call and build tool_result blocks
            val toolResultBlocks = kotlinx.serialization.json.buildJsonArray {
                for (call in toolCalls) {
                    println("Executing tool: ${call.name} with input: ${call.input}")
                    val result = executeToolCall(call.name, call.input)
                    println("Tool result (${call.name}): ${result.take(200)}")
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("type", JsonPrimitive("tool_result"))
                        put("tool_use_id", JsonPrimitive(call.id))
                        put("content", JsonPrimitive(result))
                    })
                }
            }
            conversationHistory.add(ClaudeMessage.blocks("user", toolResultBlocks))
        }
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
        return try {
            when (name) {
                "search_events" -> searchEvents(input)
                "search_venues" -> searchVenues(input)
                "get_event_details" -> getEventDetails(input)
                "recommend_and_save_venue" -> recommendAndSaveVenue(input)
                else -> """{"error": "Unknown tool: $name"}"""
            }
        } catch (e: Exception) {
            println("Tool execution error ($name): ${e.message}")
            """{"error": "Failed to execute $name: ${e.message}"}"""
        }
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

        // Always use current location for geo-radius search
        val state = _uiState.value
        val userLat = state.currentLat ?: state.userPreferences?.userLat
        val userLng = state.currentLng ?: state.userPreferences?.userLng
        println("searchEvents: lat=$userLat lng=$userLng location=$location currentLocationName=${state.currentLocationName}")

        val events = eventRepo.searchEvents(
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

        // Cache full event objects
        searchResultCache.addAll(events)

        // Cap what Claude sees to keep token usage manageable
        val totalFound = events.size
        val displayEvents = events.take(100)

        if (displayEvents.isEmpty()) return "No events found matching your search."

        // Group events by date, then by category for compact display
        data class EventEntry(
            val id: String, val title: String, val venue: String,
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
                    val ampm = if (h < 12) "am" else "pm"
                    val dh = if (h == 0) 12 else if (h > 12) h - 12 else h
                    val timeStr = if (m == 0) "${dh}${ampm}" else "${dh}:${m.toString().padStart(2, '0')}${ampm}"
                    Triple(key, label, timeStr)
                } catch (_: Exception) {
                    Triple(e.startAt?.take(10) ?: "?", e.startAt?.take(10) ?: "?", "?")
                }
            }
            val price = if (e.isFree) "FREE" else e.priceDisplay.ifBlank { "" }
            val cat = (e.subcategory ?: e.category ?: "other").uppercase()
            EventEntry(e.id, e.title, venue, dateKey, dateLabel, time, cat, price)
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
                        "${e.title} at ${e.venue} ${e.time}$priceTag [evt:${e.id}]"
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
        val responseText = parsed.text

        when (parsed.type) {
            ParsedResponseType.CHAT -> {
                // Pure conversation — Claude is gathering info or answering questions
                consecutiveQuestionCount++
                if (responseText.isNotBlank()) {
                    addAiMessage(responseText)
                }
                // After 2+ consecutive questions, inject a "Just surprise me" chip
                val chips = parsed.chips.toMutableList()
                if (consecutiveQuestionCount >= 2 && chips.none { it.contains("surprise", ignoreCase = true) }) {
                    chips.add("Just surprise me")
                }
                finishTurn(chips)
            }

            ParsedResponseType.PLAN -> {
                consecutiveQuestionCount = 0
                // Claude is confident — delivering a full plan
                val title = parsed.planTitle ?: "Your Plan"
                val builtDays = buildPlanFromRefs(parsed)
                val validatedDays = validatePlanDates(builtDays)

                _uiState.update {
                    it.copy(
                        days = validatedDays,
                        planTitle = title,
                        planAttendeeCount = 1,
                        currentDayIndex = 0,
                        approvedDayCount = 0,
                    )
                }

                if (responseText.isNotBlank()) {
                    addAiMessage(responseText)
                }

                delay(350)

                if (validatedDays.size > 1) {
                    // Multi-day: show all days in a single horizontally scrollable card
                    addMessage(ChatMessage(role = MessageRole.AI, content = MessageContent.MultiDayPlanRef(validatedDays.size)))
                } else {
                    // Single day: show as a regular day card
                    addMessage(ChatMessage(role = MessageRole.AI, content = MessageContent.DayCardRef(0)))
                }

                finishTurn(withApproveChip(parsed.chips))
            }

            ParsedResponseType.PLAN_UPDATE -> {
                consecutiveQuestionCount = 0
                val dayIndex = parsed.dayIndex ?: _uiState.value.currentDayIndex
                val builtDays = buildPlanFromRefs(parsed)
                val updatedDay = builtDays.firstOrNull()

                if (updatedDay != null) {
                    val validatedDay = validatePlanDates(listOf(updatedDay)).first()
                    val previousDay = _uiState.value.days.getOrNull(dayIndex)
                    val previousSlotCount = previousDay?.slots?.size ?: 0
                    val newSlotCount = validatedDay.slots.size

                    // Validate: if text says "Added" but new plan has same or fewer slots, re-prompt
                    val textLower = responseText.lowercase()
                    val claimsAdded = textLower.contains("added") || textLower.contains("here's") || textLower.contains("included")
                    val slotsMissing = claimsAdded && newSlotCount <= previousSlotCount

                    if (slotsMissing) {
                        println("Plan update validation failed: text claims addition but slots didn't increase ($previousSlotCount -> $newSlotCount). Re-prompting.")
                        appendTextToHistory("user", "The plan you returned is missing the event you mentioned adding. Please include ALL existing slots plus the new one.")
                        try {
                            val retryParsed = sendWithToolLoop(buildSystemPrompt())
                            if (retryParsed.type == ParsedResponseType.PLAN_UPDATE && retryParsed.days.isNotEmpty()) {
                                val retryDays = buildPlanFromRefs(retryParsed)
                                val retryDay = retryDays.firstOrNull()
                                if (retryDay != null) {
                                    _uiState.update { state ->
                                        val days = state.days.toMutableList()
                                        if (dayIndex in days.indices) days[dayIndex] = retryDay
                                        state.copy(days = days, currentDayIndex = dayIndex)
                                    }
                                    val retryText = retryParsed.text.ifBlank { responseText }
                                    if (retryText.isNotBlank()) addAiMessage(retryText)
                                    addMessage(ChatMessage(role = MessageRole.AI, content = MessageContent.DayCardRef(dayIndex)))
                                    finishTurn(withApproveChip(retryParsed.chips))
                                    return
                                }
                            }
                        } catch (e: Throwable) {
                            println("Re-prompt failed: ${e.message}")
                        }
                    }

                    // Snapshot old plan cards so they show the previous version
                    if (previousDay != null) {
                        _uiState.update { state ->
                            val messages = state.messages.map { msg ->
                                if (msg.content is MessageContent.DayCardRef &&
                                    (msg.content as MessageContent.DayCardRef).dayIndex == dayIndex) {
                                    msg.copy(content = MessageContent.DayCardSnapshot(
                                        day = previousDay,
                                        dayIndex = dayIndex,
                                        totalDays = state.days.size,
                                    ))
                                } else msg
                            }
                            state.copy(messages = messages)
                        }
                    }

                    _uiState.update { state ->
                        val days = state.days.toMutableList()
                        if (dayIndex in days.indices) {
                            days[dayIndex] = validatedDay
                        }
                        state.copy(days = days, currentDayIndex = dayIndex)
                    }
                }

                if (responseText.isNotBlank()) {
                    addAiMessage(responseText)
                }

                // Add the new live plan card
                addMessage(ChatMessage(role = MessageRole.AI, content = MessageContent.DayCardRef(dayIndex)))

                finishTurn(withApproveChip(parsed.chips))
            }

            ParsedResponseType.APPROVE -> {
                // Claude recognized an approval — lock in the whole plan
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

    private fun approvePlan() {
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
                                )
                            },
                            travel = day.travel.map { PlanTravel(it.mode, it.mins) },
                        )
                    },
                    attendees = buildList {
                        add(PlanAttendee(id = "self", name = "You", initials = "Y", confirmed = true, avatarColor = 0xFFFF6B6B))
                        val count = state.planAttendeeCount.coerceAtLeast(1)
                        if (count > 1) {
                            repeat(count - 1) { i ->
                                add(PlanAttendee(id = "guest_$i", name = "+1", initials = "+1", confirmed = false, avatarColor = 0xFFAB83A1))
                            }
                        }
                    },
                    createdAt = "",
                    dateDisplay = "",
                )
                planRepo.savePlan(plan)
                // Badge: track plan created
                val userState = cityToState(state.userPreferences?.city ?: "boston")
                badgeService.recordAction(BadgeAction.PLAN_CREATED, userState)
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

        // For flexible/always-on events, keep Claude's chosen time
        // For fixed-time events, use the real event time
        val isMidnightUtc = event.startAt?.contains("T00:00:00") == true
        val isFlexible = event.category == "always_on" || event.startAt == null || isMidnightUtc
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
            .sortedBy { parseTimeForSort(it.time) }
        return day.copy(slots = resolvedSlots)
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

    // Track consecutive question (chat-type) responses for "surprise me" chip injection
    private var consecutiveQuestionCount = 0

    // ── "Also Happening" Recommendations ──

    private fun scoreEventUniqueness(event: Event): Double {
        var score = 0.0
        val titleLc = event.title.lowercase()

        // Ticketed/produced event
        if (event.ticketUrl != null) score += 3.0

        // Fixed-time (not always-on)
        if (event.category != "always_on" && event.startAt != null) score += 2.0

        // Special keywords in title
        val specialKeywords = listOf("premiere", "opening night", "farewell", "reunion",
            "one night only", "limited", "sold out", "presents:", "feat.", "vs.", "album release",
            "debut", "residency", "anniversary", "benefit", "gala", "festival")
        if (specialKeywords.any { titleLc.contains(it) }) score += 2.0

        // Has an image (better card presentation)
        if (event.imageUrl != null) score += 1.0

        // Free at what seems like a notable venue
        if (event.isFree && event.venueName != null) score += 1.5

        // Source is a major platform
        if (event.source == "ticketmaster" || event.source == "eventbrite") score += 1.0

        // Niche category (not the most common ones)
        val nicheCategories = listOf("comedy", "film", "food", "arts", "theater", "dance",
            "literary", "wellness", "experiences")
        if (nicheCategories.any { event.subcategory?.lowercase()?.contains(it) == true }) score += 1.0

        return score
    }

    private fun computeRecommendations(planDays: List<ItineraryDay>): List<Event> {
        val usedEventIds = planDays.flatMap { it.slots.mapNotNull { s -> s.eventId } }.toSet()

        return searchResultCache
            .filter { it.id !in usedEventIds }
            .distinctBy { it.id }
            .map { it to scoreEventUniqueness(it) }
            .filter { it.second >= 3.0 }
            .sortedByDescending { it.second }
            .take(8)
            .map { it.first }
    }

    fun addRecommendationToPlan(event: Event) {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.days.isEmpty()) return@launch

            // Parse event time
            val eventTime = event.startAt?.let { iso ->
                try {
                    val instant = kotlinx.datetime.Instant.parse(iso)
                    val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                    val hour = local.hour
                    val min = local.minute
                    val amPm = if (hour < 12) "AM" else "PM"
                    val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
                    if (min == 0) "${displayHour} ${amPm}" else "${displayHour}:${min.toString().padStart(2, '0')} ${amPm}"
                } catch (_: Exception) { "TBD" }
            } ?: "TBD"

            // Find the right day (match by date)
            val eventDate = event.startAt?.take(10) ?: ""
            var targetDayIndex = 0
            state.days.forEachIndexed { idx, day ->
                val dayDate = extractDateFromLabel(day.label)
                if (dayDate != null && eventDate.contains("${dayDate.second.toString().padStart(2, '0')}")) {
                    targetDayIndex = idx
                }
            }

            val targetDay = state.days.getOrNull(targetDayIndex) ?: return@launch

            // Check for time conflicts (within ~2 hours)
            val eventMinutes = parseTimeForSort(eventTime)
            val conflicting = targetDay.slots.firstOrNull { slot ->
                val slotMinutes = parseTimeForSort(slot.time)
                slotMinutes != Int.MAX_VALUE && eventMinutes != Int.MAX_VALUE &&
                    kotlin.math.abs(slotMinutes - eventMinutes) < 120
            }

            val newSlot = ItinerarySlot(
                id = generateId(),
                time = eventTime,
                emoji = "",
                name = event.title,
                detail = "${event.venueName ?: ""} · ${event.venueCity}",
                price = event.priceDisplay,
                isFree = event.isFree,
                imageUrl = event.imageUrl,
                eventId = event.id,
            )

            if (conflicting != null) {
                // Show conflict message with chips
                addAiMessage("\"${event.title}\" at $eventTime overlaps with \"${conflicting.name}\" at ${conflicting.time}. Want to swap it in?")
                _uiState.update {
                    it.copy(
                        quickReplies = listOf(
                            QuickReply("Swap it in", action = "swap_recommendation:${event.id}:${conflicting.id}:$targetDayIndex"),
                            QuickReply("Keep current"),
                        ),
                        inputEnabled = true,
                        isBusy = false,
                    )
                }
                // Store the new slot for the swap
                pendingRecommendationSlot = newSlot
            } else {
                // No conflict — add directly
                _uiState.update { s ->
                    val days = s.days.toMutableList()
                    val day = days[targetDayIndex]
                    val newSlots = (day.slots + newSlot).sortedBy { parseTimeForSort(it.time) }
                    days[targetDayIndex] = day.copy(slots = newSlots)
                    s.copy(days = days)
                }
                addAiMessage("Added \"${event.title}\" at $eventTime!")
            }
        }
    }

    private var pendingRecommendationSlot: ItinerarySlot? = null

    private fun handleSwapRecommendation(action: String) {
        val parts = action.removePrefix("swap_recommendation:").split(":")
        if (parts.size < 3) return
        val conflictSlotId = parts[1]
        val dayIndex = parts[2].toIntOrNull() ?: return
        val newSlot = pendingRecommendationSlot ?: return

        _uiState.update { s ->
            val days = s.days.toMutableList()
            val day = days[dayIndex]
            val newSlots = day.slots.filter { it.id != conflictSlotId } + newSlot
            days[dayIndex] = day.copy(slots = newSlots.sortedBy { parseTimeForSort(it.time) })
            s.copy(days = days)
        }
        pendingRecommendationSlot = null
        addAiMessage("Swapped in \"${newSlot.name}\"!")
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

        val currentPlanContext = if (state.days.isNotEmpty()) {
            val dayDescriptions = state.days.mapIndexed { i, day ->
                val approved = if (day.isApproved) " [APPROVED]" else ""
                val slots = if (day.slots.isEmpty()) "  (no slots yet)"
                else day.slots.joinToString("\n") { s ->
                    "  ${s.time} ${s.name} — ${s.detail} (${s.price})${if (s.isCustom) " [CUSTOM]" else ""}"
                }
                "Day ${i + 1}: ${day.label}$approved\n$slots"
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

        // Build a compact calendar reference for the next 90 days
        val calendarRef = try {
            val dowAbbrev = arrayOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
            val monthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            val baseEpochDay = todayLocal.date.toEpochDays()
            (0..89).joinToString(" ") { offset ->
                val d = kotlinx.datetime.LocalDate.fromEpochDays(baseEpochDay + offset)
                val dow = dowAbbrev[d.dayOfWeek.ordinal]
                val month = d.toString().substring(5, 7).toInt()
                val day = d.toString().substring(8, 10).toInt()
                "$dow${monthNames[month - 1]}$day"
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

        return """
You are Local, an events concierge in a mobile app.
Today: $todayDate ($dayOfWeek)
Calendar: $calendarRef

After searching, respond in this format:

For plans:
PLAN: "Title"
DAY: Saturday, April 5
- [evt:EVENT_ID] at TIME — Your reason for picking this
- [venue:VENUE_NAME] at TIME — Your reason
CHIPS: chip1 | chip2 | chip3

For updating an existing day:
UPDATE_DAY: 0
DAY: Saturday, April 5
- [evt:EVENT_ID] at TIME — Your reason
- [venue:VENUE_NAME] at TIME — Your reason
CHIPS: chip1 | chip2

For conversation (no plan):
Just write naturally. No PLAN/DAY markers.
CHIPS: chip1 | chip2

Rules:
- Reference events ONLY by [evt:ID] using IDs from search results (the [evt:xxx] tags after each event).
- Reference venues by [venue:NAME] using the name from recommend_and_save_venue results.
- Keep explanations to 1 sentence per slot.
- Include 2-3 chips at the end, separated by |.
- No emoji. No markdown. Plain text only for the natural language parts.
- 1 question per message max. Be direct.

DATE AND TIME ACCURACY:
- Check the calendar to confirm day-of-week for any date. Do NOT guess.
- Only include events whose date matches the day being planned.
- Use the EXACT start time from search results.
- Anytime events: schedule at reasonable times (10am-9pm).

TOOLS: Always search before recommending. Use search_events for events, search_venues for places, recommend_and_save_venue for restaurants/bars not in the DB, get_event_details for full info. Search broadly, then curate. Make MULTIPLE tool calls for plans with 2+ slots.

SPEED: If you know WHAT and WHEN, search and build immediately. Only ask about WHEN if not given. Default: area = current location, group = 2, vibe = fun/casual.

AMENDMENTS: When a plan exists and user wants changes WITHIN a day, use UPDATE_DAY format. For adding NEW days, use PLAN format with ALL days. Never duplicate events. When removing, keep everything else.

PLAN SIZE: "Date night" = 2-3 slots (dinner + activity + optional drinks). "Night out" = 2-3. "Full day" = 4-5. "Weekend" = 3-5 per day. Single event = 1 slot.

BROWSING: For "what's happening" / "live music this week" / etc, search first, then list results as natural text (no PLAN markers). Include event names, venues, times. Offer chips like "Plan around [event]".

DINING: For restaurant/bar requests, go straight to recommend_and_save_venue. When presenting multiple options for the SAME slot, list them in text (no plan), let user choose.

LOCATION: User is in ${state.currentLocationName.ifBlank { state.userPreferences?.city?.replaceFirstChar { it.uppercase() } ?: "their area" }} (lat: ${state.currentLat ?: state.userPreferences?.userLat ?: "unknown"}, lng: ${state.currentLng ?: state.userPreferences?.userLng ?: "unknown"}). Searches auto-use coordinates. Never ask location.

NO RESULTS: Try nearby areas. If still nothing, use recommend_and_save_venue for a venue-based plan. Never mention "database" or implementation details.

$prefsContext

$currentPlanContext
        """.trimIndent()
    }

    // ── Helpers ──

    private fun handleAiError(e: Exception) {
        println("ChatViewModel AI error: ${e::class.simpleName}: ${e.message}")
        val errorMsg = e.message.orEmpty()
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
        addMessage(ChatMessage(role = MessageRole.AI, content = MessageContent.Text(text)))
    }

    private fun addUserMessage(text: String) {
        addMessage(ChatMessage(role = MessageRole.USER, content = MessageContent.Text(text)))
    }

    private fun addMessage(msg: ChatMessage) {
        _uiState.update { it.copy(messages = it.messages + msg) }
    }
}

fun dayColor(index: Int): Long {
    val colors = listOf(0xFFFF6B6B, 0xFF4ECBA0, 0xFFFFB830, 0xFF6B9FFF, 0xFFCC6BFF, 0xFFFF6BB5, 0xFF6BFFD4)
    return colors.getOrElse(index) { colors[0] }
}
