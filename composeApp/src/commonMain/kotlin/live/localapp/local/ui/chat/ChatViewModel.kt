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
import live.localapp.local.data.remote.AiChip
import live.localapp.local.data.remote.AiResponseEnvelope
import live.localapp.local.data.remote.ClaudeApiService
import live.localapp.local.data.remote.ClaudeMessage
import live.localapp.local.data.remote.ClaudeResponse
import live.localapp.local.data.remote.claudeTools
import live.localapp.local.data.remote.toDomain
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
                val envelope = sendWithToolLoop(systemPrompt)
                // Only process if Claude returned a plan_update for this day
                if (envelope.type == "plan_update" && envelope.day != null) {
                    val enrichedDay = resolveDayFromDb(envelope.day.toDomain())
                    _uiState.update { state ->
                        val days = state.days.toMutableList()
                        val idx = envelope.dayIndex ?: dayIndex
                        if (idx in days.indices) {
                            days[idx] = enrichedDay
                        }
                        state.copy(days = days)
                    }
                    if (!envelope.text.isNullOrBlank()) {
                        appendTextToHistory("assistant", envelope.text)
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

    fun resetChat() {
        conversationHistory.clear()
        _uiState.value = ChatUiState()
        showWelcome()
    }

    // ── Core: Single unified message handler ──

    private fun handleMessage(text: String) {
        _uiState.update { it.copy(quickReplies = emptyList()) }
        searchResultCache.clear()
        viewModelScope.launch {
            setBusy(true)
            appendTextToHistory("user", text)

            try {
                val systemPrompt = buildSystemPrompt()
                val envelope = sendWithToolLoop(systemPrompt)
                println("Claude response: type=${envelope.type} text=${envelope.text?.take(100)} days=${envelope.days?.size} day=${envelope.day != null} chips=${envelope.chips?.size}")
                handleClaudeResponse(envelope)
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
     */
    private suspend fun sendWithToolLoop(systemPrompt: String): AiResponseEnvelope {
        val maxToolRounds = 5 // Cap tool calls to avoid runaway loops
        var toolRounds = 0
        var lastResponse: ClaudeResponse? = null

        while (true) {
            val response = claudeApi.sendRaw(
                systemPrompt = systemPrompt,
                messages = conversationHistory,
                tools = claudeTools,
            )
            lastResponse = response

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
                    return AiResponseEnvelope(type = "chat", text = "Sorry, I had trouble generating a response. Could you try again?")
                }

                // If response was truncated (max_tokens), ask Claude to generate a shorter version
                if (response.stopReason == "max_tokens") {
                    println("sendWithToolLoop: Response truncated (max_tokens), requesting shorter version")
                    stripToolMessagesFromHistory()
                    appendTextToHistory("assistant", text)
                    appendTextToHistory("user", "Your response was cut off. Please give me the same plan but more concise — shorter detail fields, fewer words. Respond with ONLY the JSON object, no other text.")
                    try {
                        val retryResponse = claudeApi.sendRaw(systemPrompt, conversationHistory, null)
                        val retryText = claudeApi.extractText(retryResponse)
                        if (retryText != null) {
                            appendTextToHistory("assistant", retryText)
                            return claudeApi.parseStructured(retryText)
                        }
                    } catch (e: Exception) {
                        println("sendWithToolLoop: Truncation retry failed: ${e.message}")
                    }
                    return AiResponseEnvelope(type = "chat", text = "I put together a plan but it was too large to display. Could you try a simpler request?")
                }

                stripToolMessagesFromHistory()
                appendTextToHistory("assistant", text)
                return try {
                    println("sendWithToolLoop: Attempting parse, text length=${text.length}, stopReason=${response.stopReason}")
                    println("sendWithToolLoop: First 200 chars: ${text.take(200)}")
                    println("sendWithToolLoop: Last 200 chars: ${text.takeLast(200)}")
                    claudeApi.parseStructured(text)
                } catch (e: Exception) {
                    println("sendWithToolLoop: JSON parse failed: ${e.message}")
                    println("sendWithToolLoop: Full raw text (${text.length} chars): $text")
                    // Auto-retry once: ask Claude to reformat as valid JSON (no tools needed)
                    try {
                        appendTextToHistory("user", "Your response had a JSON formatting issue. Please resend the EXACT same plan as valid JSON — no markdown fences, no text before or after. Just the JSON object.")
                        val retryResponse = claudeApi.sendRaw(systemPrompt, conversationHistory, null)
                        val retryText = claudeApi.extractText(retryResponse)
                        if (retryText != null) {
                            appendTextToHistory("assistant", retryText)
                            claudeApi.parseStructured(retryText)
                        } else {
                            throw RuntimeException("No text in retry response")
                        }
                    } catch (retryEx: Exception) {
                        println("sendWithToolLoop: Retry also failed: ${retryEx.message}")
                        println("sendWithToolLoop: Retry stopReason may be max_tokens — check logs above")
                        val cleanText = text.substringBefore("{").substringBefore("```").trim()
                        val displayText = if (cleanText.length > 20) cleanText
                            else "I put together a plan but had trouble formatting it. Let me try again — what were you looking for?"
                        AiResponseEnvelope(type = "chat", text = displayText)
                    }
                }
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
                conversationHistory.add(ClaudeMessage.text("user", "You have enough information now. Respond ONLY with valid JSON (plan, plan_update, or chat type). No other text. Do NOT call any more tools."))
                val finalResponse = claudeApi.sendRaw(
                    systemPrompt = systemPrompt,
                    messages = conversationHistory,
                    tools = null,
                )
                val text = claudeApi.extractText(finalResponse)
                if (text == null) {
                    println("sendWithToolLoop: No text after cap nudge, returning fallback")
                    stripToolMessagesFromHistory()
                    return AiResponseEnvelope(type = "chat", text = "I found some great options but had trouble putting them together. Could you try asking again?")
                }
                stripToolMessagesFromHistory()
                appendTextToHistory("assistant", text)
                return try {
                    claudeApi.parseStructured(text)
                } catch (e: Exception) {
                    println("sendWithToolLoop: JSON parse failed after cap: ${e.message}")
                    val cleanText = text.substringBefore("{").substringBefore("```").trim()
                    val displayText = if (cleanText.length > 20) cleanText
                        else "I put together a plan but had trouble formatting it. Let me try again."
                    AiResponseEnvelope(type = "chat", text = displayText)
                }
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
        val limit = input["limit"]?.jsonPrimitive?.intOrNull ?: 200
        val startDate = input["start_date"]?.jsonPrimitive?.contentOrNull
        val endDate = input["end_date"]?.jsonPrimitive?.contentOrNull

        // If specific dates provided, use custom date range instead of keyword
        if (startDate != null && dateRange == null) {
            dateRange = "custom"
        }

        // Use current location for geo-radius search (falls back to prefs)
        val state = _uiState.value
        val userLat = state.currentLat ?: state.userPreferences?.userLat
        val userLng = state.currentLng ?: state.userPreferences?.userLng

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
            radiusMiles = 30.0,
        )

        // Cache full event objects for "Also Happening" recommendations
        searchResultCache.addAll(events)

        val today = kotlin.time.Clock.System.now().toEpochMilliseconds()
        return events.joinToString("\n") { e ->
            val price = e.priceDisplay.ifBlank { "Price TBD" }
            val venue = e.venueName ?: "TBD"
            val img = e.imageUrl ?: ""
            val isFlexible = e.category == "always_on" || e.startAt == null
            val typeTag = if (isFlexible) "FLEXIBLE" else "FIXED-TIME"
            // For fixed-time events, show the full ISO date + day of week so Claude can match days
            val dateInfo = if (isFlexible) {
                "Anytime (no fixed date)"
            } else {
                val isoDate = e.startAt ?: ""
                try {
                    val instant = kotlinx.datetime.Instant.parse(isoDate)
                    val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                    val dow = local.dayOfWeek.name.uppercase()
                    val monthNames = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
                    val monthName = monthNames[local.monthNumber - 1]
                    val dayNum = local.dayOfMonth
                    val hour = local.hour
                    val minute = local.minute
                    val amPm = if (hour < 12) "am" else "pm"
                    val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
                    val timeStr = if (minute == 0) "${displayHour}${amPm}" else "${displayHour}:${minute.toString().padStart(2, '0')}${amPm}"
                    "\u26A0\uFE0F $dow $monthName $dayNum — $timeStr ($isoDate)"
                } catch (_: Exception) {
                    val dateStr = e.dateDisplay.ifBlank { isoDate.take(10) }
                    dateStr
                }
            }
            val flexNote = if (isFlexible) " | Schedule anytime during venue hours" else ""
            "[$typeTag] [${e.id}] ${e.title} | $venue (${e.venueCity}) | DATE: $dateInfo | $price | ${e.category}${if (e.isFree) " | FREE" else ""}$flexNote${if (img.isNotBlank()) " | img:$img" else ""}"
        }.ifEmpty { "No events found matching your search." }
    }

    private suspend fun searchVenues(input: JsonObject): String {
        val location = input["location"]?.jsonPrimitive?.contentOrNull
        val category = input["category"]?.jsonPrimitive?.contentOrNull
        val keyword = input["keyword"]?.jsonPrimitive?.contentOrNull
        val limit = input["limit"]?.jsonPrimitive?.intOrNull ?: 200

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

    // ── Process Claude's structured response ──

    private suspend fun handleClaudeResponse(envelope: AiResponseEnvelope) {
        val responseText = envelope.text ?: envelope.intro ?: ""

        when (envelope.type) {
            "chat" -> {
                // Pure conversation — Claude is gathering info or answering questions
                consecutiveQuestionCount++
                if (responseText.isNotBlank()) {
                    addAiMessage(responseText)
                }
                // After 2+ consecutive questions, inject a "Just surprise me" chip
                val chips = envelope.chips?.toMutableList() ?: mutableListOf()
                if (consecutiveQuestionCount >= 2 && chips.none { it.label.contains("surprise", ignoreCase = true) }) {
                    chips.add(AiChip(label = "Just surprise me"))
                }
                finishTurn(chips)
            }

            "plan" -> {
                consecutiveQuestionCount = 0
                // Claude is confident — delivering a full plan
                val title = envelope.planTitle ?: "Your Plan"
                val days = envelope.days?.map { it.toDomain() } ?: emptyList()
                val validatedDays = validatePlanDates(days)
                val enrichedDays = validatedDays.map { resolveDayFromDb(it) }

                _uiState.update {
                    it.copy(
                        days = enrichedDays,
                        planTitle = title,
                        planAttendeeCount = envelope.attendeeCount ?: 1,
                        currentDayIndex = 0,
                        approvedDayCount = 0,
                    )
                }

                if (responseText.isNotBlank()) {
                    addAiMessage(responseText)
                }

                delay(350)

                if (enrichedDays.size > 1) {
                    // Multi-day: show all days in a single horizontally scrollable card
                    addMessage(ChatMessage(role = MessageRole.AI, content = MessageContent.MultiDayPlanRef(enrichedDays.size)))
                } else {
                    // Single day: show as a regular day card
                    addMessage(ChatMessage(role = MessageRole.AI, content = MessageContent.DayCardRef(0)))
                }

                finishTurn(withApproveChip(envelope.chips))
            }

            "plan_update" -> {
                consecutiveQuestionCount = 0
                val dayIndex = envelope.dayIndex ?: _uiState.value.currentDayIndex
                val updatedDay = envelope.day?.toDomain()

                if (updatedDay != null) {
                    val validatedDay = validatePlanDates(listOf(updatedDay)).first()
                    val enrichedDay = resolveDayFromDb(validatedDay)
                    val previousDay = _uiState.value.days.getOrNull(dayIndex)
                    val previousSlotCount = previousDay?.slots?.size ?: 0
                    val newSlotCount = enrichedDay.slots.size

                    // Validate: if text says "Added" but new plan has same or fewer slots, re-prompt
                    val textLower = responseText.lowercase()
                    val claimsAdded = textLower.contains("added") || textLower.contains("here's") || textLower.contains("included")
                    val slotsMissing = claimsAdded && newSlotCount <= previousSlotCount

                    if (slotsMissing) {
                        // Claude said it added something but didn't — re-prompt
                        println("Plan update validation failed: text claims addition but slots didn't increase ($previousSlotCount → $newSlotCount). Re-prompting.")
                        appendTextToHistory("user", "The plan you returned is missing the event you mentioned adding. Please include ALL existing slots plus the new one in the slots array.")
                        try {
                            val retryEnvelope = sendWithToolLoop(buildSystemPrompt())
                            if (retryEnvelope.type == "plan_update" && retryEnvelope.day != null) {
                                val retryDay = resolveDayFromDb(retryEnvelope.day.toDomain())
                                _uiState.update { state ->
                                    val days = state.days.toMutableList()
                                    if (dayIndex in days.indices) days[dayIndex] = retryDay
                                    state.copy(days = days, currentDayIndex = dayIndex)
                                }
                                val retryText = retryEnvelope.text ?: responseText
                                if (retryText.isNotBlank()) addAiMessage(retryText)
                                addMessage(ChatMessage(role = MessageRole.AI, content = MessageContent.DayCardRef(dayIndex)))
                                finishTurn(withApproveChip(retryEnvelope.chips))
                                return
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
                            days[dayIndex] = enrichedDay
                        }
                        state.copy(days = days, currentDayIndex = dayIndex)
                    }
                }

                if (responseText.isNotBlank()) {
                    addAiMessage(responseText)
                }

                // Add the new live plan card
                addMessage(ChatMessage(role = MessageRole.AI, content = MessageContent.DayCardRef(dayIndex)))

                finishTurn(withApproveChip(envelope.chips))
            }

            "approve" -> {
                // Claude recognized an approval — lock in the whole plan
                if (responseText.isNotBlank()) {
                    addAiMessage(responseText)
                }
                approvePlan()
            }

            else -> {
                // Fallback — treat as chat
                if (responseText.isNotBlank()) {
                    addAiMessage(responseText)
                }
                finishTurn(envelope.chips)
            }
        }
    }

    private fun withApproveChip(aiChips: List<AiChip>?): List<AiChip> {
        val approve = AiChip(label = "Looks good, add to My Plans", action = "save_plan")
        val rest = (aiChips ?: emptyList()).filter {
            !it.label.contains("Looks good", ignoreCase = true) &&
                it.action != "save_plan"
        }
        return listOf(approve) + rest
    }

    private fun finishTurn(aiChips: List<AiChip>?) {
        val quickReplies = aiChips?.map { chip ->
            QuickReply(
                label = chip.label,
                action = chip.action ?: chip.label,
                isGreen = chip.action == "save_plan" ||
                        chip.label.contains("Looks good", ignoreCase = true),
            )
        } ?: emptyList()

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

######## ABSOLUTE RULE — ONE QUESTION PER MESSAGE ########
You MUST ask exactly ONE question per message. Not two. Not "X or Y, and also Z?"
If your message contains more than one question mark, REWRITE IT with only one.
BAD: "When are you thinking? And what area?" — TWO questions, VIOLATION.
BAD: "Do you have tickets, or would you like me to find some?" — this is OK, it's one question with options.
GOOD: "When are you thinking?" — ONE question.
GOOD: "What area?" — ONE question.
########################################################

ABSOLUTE RULE — DATE AND TIME ACCURACY:
- ALWAYS check the calendar above to confirm which day of the week a date falls on. Do NOT guess.
- Each search result includes the day of week (e.g. "Saturday"). ONLY include an event in a plan if its day of week EXACTLY matches the day being planned.
- A Monday event CANNOT go on a Saturday plan. A Friday event CANNOT go on a Sunday plan. ALWAYS verify.
- If a search returns events for the wrong day, skip them. Only use events whose date matches.
- Use the EXACT start time from search results. Never change it.
- Anytime events (tagged [FLEXIBLE]): can go on any day at any time you choose.
- Anytime events: schedule at reasonable times (10am-9pm) during typical venue hours.
- If you cannot find scheduled events for the requested date, say so honestly and suggest anytime alternatives.
- Never use terms like "fixed-time" or "flexible" when talking to the user. Just say the event name and time.

TOOLS: Always search before recommending. Use search_events for events, search_venues for places, recommend_and_save_venue for restaurants/bars not in the DB, get_event_details for full info. Search broadly first, then narrow down. Pass the user's location and category as tool parameters.

RULES:
- Events: only from search_events results (must have event_id).
- Restaurants/bars: search_venues first, then recommend_and_save_venue if needed. Restaurant/venue slots use event_id: null, is_custom: false. NEVER use filler like "Dinner" or "Custom dinner" — always a real restaurant name.
- No emoji. No markdown (no **bold**, no *italic*, no bullet points). Plain text only. 1-3 sentences. Warm and concise.
- NEVER set is_custom: true — that is only for user-added items via "Add my own".
- 2-3 real results beat 5 slots with filler.

RESTAURANT DETAILS: When recommending restaurants via recommend_and_save_venue, also indicate in the plan slot:
- Price level: $ (under $15), $$ ($15-30), $$$ ($30-60), $$$$ ($60-100), $$$$$ ($100+)
- Whether reservations are recommended
- Whether it's hard to get reservations (e.g., "Book 2+ weeks ahead")
Include these in the slot's "detail" field, e.g.: "North End · $$ · Reservations recommended"

SPEED IS EVERYTHING: Build the plan as fast as possible. Do NOT ask about existing commitments. Do NOT ask about group size unless it's a group activity. If you have enough to search (what + when), SEARCH AND BUILD IMMEDIATELY. The user can always modify after.

AMENDMENTS — THIS IS THE #1 RULE:
When a plan exists and the user asks to change WITHIN a day (add/remove/move/replace/swap slots, "more events", "less expensive", "add brunch"), respond with type "plan_update" with the modified day. NEVER use "plan" for changes to existing days — always "plan_update".
When the user asks to ADD NEW DAYS to a plan (e.g., "add Friday and Saturday"), respond with type "plan" that includes ALL days (existing + new).
DUPLICATES: NEVER add the same event twice in one day. Check event_id — if it already appears, skip it.
Examples: "No music" = remove music events, keep everything else. "Less food" = remove some food slots. "Add drinks" = add a drinks slot to the existing plan.
RELATIVE REFERENCES: "before the concert" means BEFORE that event's start time, not after. "after dinner" means AFTER dinner's time.
DUPLICATES: Never add the same venue twice in one day unless the user explicitly asks for it.
AMENDMENT ACCURACY: When adding a slot, include it exactly once. When removing, remove only the specified slots and keep everything else.
CRITICAL: The slots in your JSON response MUST match what you say in the text. If you say "Added X" then X MUST appear in the slots array. If you say "Removed Y" then Y must NOT be in the slots. Never say you added something but leave it out of the slots.

CONFLICTS: If adding at a time already taken, flag it and offer options via chips.

CUSTOM EVENTS ("Add my own"):
When the user says "Add my own" or wants to add their own event/activity:
1. Ask: what is it and what time? If the plan is only 1 day, do NOT ask which day — it's obvious.
2. Return plan_update with ALL existing slots PLUS the custom slot
3. Custom slot MUST have: is_custom: true, event_id: null
4. Use the user's exact name and time for the custom slot

ALTERNATIVE PLANS:
When user asks for "something different", "cheaper", "more active", "option B", etc:
- Respond with type "plan" (a complete new plan), NOT "chat"
- Search for different events than the current plan
- Make it genuinely different — different venues, different vibe

SINGLE vs MULTI-EVENT — CRITICAL:
- If the user asks for a specific event ("Matt Berninger Monday", "Soul Rebels tonight") → 1 event plan. ONLY that event. Do NOT add dinner, drinks, or anything else. The user will ask if they want more.
- "Plan a night out" → 2-3 events including food/drinks is fine.
- "Full day trip" → 4-5 events with meals.
- "Date night" → MUST include at least 2 slots: dinner (use recommend_and_save_venue) AND an activity/show/event (use search_events). A single restaurant is NOT a date night plan.
- "Weekend plans", "day out" → include restaurants/bars as part of the experience. Use recommend_and_save_venue for dining, search_events for activities.
- Only skip restaurants when the user explicitly asks for a single specific event.
- A plan with only 1 slot is almost never correct unless the user asked for a specific single event. Plans should feel complete.

DISCOVERY/BROWSING QUERIES:
When the user is browsing ("what's happening", "live music this week", "shows tonight", "free things to do", "events this weekend") — FIRST call search_events to find matching events, THEN respond with type "chat" (NOT "plan") listing the results. You MUST search and include actual events. Format each event on its own line with a bullet:

• Event Name — Venue · Time · Price

Use a blank line between the intro and the list, and between day headers. Group by day if spanning multiple days. Keep it scannable. Then offer chips like "Plan around [event]", "Tell me more". NEVER respond with just a summary sentence — always list the actual events.

WHEN TO BUILD A PLAN — CRITICAL:
Once you know WHAT and WHEN, BUILD THE PLAN immediately. Do NOT respond with type "chat" saying "I found great options" — that's useless without the actual plan card. Search for events, find venues, and return type "plan" with real slots.
- "Date night next weekend" → BUILD IT. Don't ask about area, group size, or vibe.
- "Things to do Saturday" → BUILD IT. Use their current location.
- "Live music" with no date → ask ONLY "When are you thinking?" — nothing else.
- "Surprise me" → BUILD IT immediately with this weekend's best events.
- The ONLY question worth asking is WHEN if no date is given. Everything else, just build and let them modify.
GATHERING INFO: The only thing you might need to ask is WHEN. Default everything else: area = user's current location, group size = 2, vibe = fun/casual. If the user says "surprise me" or gives you a date, skip ALL questions and build immediately.
LOCATION: The user is currently in ${state.currentLocationName.ifBlank { state.userPreferences?.city ?: "the Boston area" }}. If they say "near me" or don't specify a location, search their current area. Never ask "where are you located" — we already know. Instead say "What area are you thinking?" or "Anywhere specific, or should I search broadly?"
NO RESULTS: If search returns no matching events, don't just say "not finding events". Instead: 1) Try searching nearby areas automatically. 2) If still nothing, use recommend_and_save_venue to build a venue-based plan (restaurants, bars, activities). Always offer a path forward. Do NOT say "those dates are still being added" — the events may genuinely not exist.
NEVER mention "database", "my database", "system", or any implementation details to the user. You are a concierge, not a search engine. Speak naturally.
RESTAURANT/DINING REQUESTS: When the user asks for dinner, restaurants, food, brunch, drinks, bars, or any dining — go STRAIGHT to recommend_and_save_venue. Do NOT search events first. Dining is not an event.
PRESENTING OPTIONS: When recommending multiple options for the SAME slot (e.g. 3 restaurant choices for dinner, 3 bar options for drinks), respond with type "chat" as a bulleted text list, NOT a plan. The user hasn't chosen yet. You MUST include the actual venue details in your text response. Format:
Here are some great spots:
• Restaurant Name — Neighborhood · Cuisine · $$ · "Known for X"
• Restaurant Name — Neighborhood · Cuisine · $$$ · "Known for Y"
• Restaurant Name — Neighborhood · Cuisine · $$ · "Known for Z"
CRITICAL: The bullet points with venue names MUST appear in the "text" field of your JSON response. Do NOT leave the text empty or just include an intro sentence. The venues must be listed.
Then offer chips like "Let's go with [Name]", "Tell me more about [Name]", "Something else" so the user picks one. ONLY build a plan card after the user chooses.
PRICING: Use "Free" tag for free events. For paid events, show the price (e.g. "$25-40"). Never use the phrase "admission fee" — just show the price.
BREVITY: Keep the text before a plan to 1 sentence max. The plan cards speak for themselves. Don't restate what the user asked for.
DATE NIGHT RULE: If the user mentions "date night", "date", or "couple", automatically assume 2 people. Do NOT ask about group size — just proceed with planning for 2.
TONE FOR QUESTIONS: Be direct. Don't say "Great, I can help with that! First, let me ask..." — just ask the question. Example: "When are you thinking — this weekend or a specific date?" not "That sounds fun! I'd love to help plan that. To get started, could you tell me when you're thinking?"

$prefsContext

$currentPlanContext

RESPONSE FORMAT (JSON only, no markdown fences, keep detail fields SHORT to avoid truncation):
chat: {"type":"chat","text":"...","chips":[{"label":"..."}]}
plan: {"type":"plan","text":"...","plan_title":"...","attendee_count":N,"days":[{"label":"...","short_name":"...","slots":[{"time":"...","emoji":"","name":"...","detail":"...","price":"...","is_free":false,"image_url":"...","event_id":"..."}],"travel":[{"mode":"...","mins":N}]}],"chips":[...]}
plan_update: {"type":"plan_update","text":"...","day_index":N,"day":{same day structure},"chips":[...]}
approve: {"type":"approve","text":"..."}
Keep "detail" fields concise. Omit image_url if null.

CHIPS: 2-4 contextual suggestions. After plans: approve + modify options + "Add my own". After questions: common answers.
SLOTS: Match count to request. Every slot needs event_id from search, EXCEPT venue slots from recommend_and_save_venue (event_id: null, is_custom: false) and user custom items (is_custom: true, event_id: null). Sort chronologically. Copy image URLs exactly.
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
