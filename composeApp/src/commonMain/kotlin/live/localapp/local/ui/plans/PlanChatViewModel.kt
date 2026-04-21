package live.localapp.local.ui.plans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import live.localapp.local.data.remote.ClaudeApiService
import live.localapp.local.data.remote.ClaudeMessage
import live.localapp.local.data.remote.StreamEvent
import live.localapp.local.data.remote.ToolCall
import live.localapp.local.data.remote.claudeTools
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.model.PlanDay
import live.localapp.local.domain.model.PlanSlot
import live.localapp.local.domain.repository.EventRepository
import live.localapp.local.domain.repository.PlanChatRepository
import live.localapp.local.domain.repository.PlanRepository
import live.localapp.local.domain.repository.UserPreferencesRepository
import live.localapp.local.domain.repository.VenueRepository

data class PlanChatMessageUi(
    val id: String,
    val userId: String,
    val senderName: String,
    val text: String,
    val isYocal: Boolean,
    val isCurrentUser: Boolean,
    val createdAt: String,
    val reactions: Map<String, List<String>> = emptyMap(),
    val isLocal: Boolean = false,
)

data class PlanChatUiState(
    val messages: List<PlanChatMessageUi> = emptyList(),
    val isLoading: Boolean = false,
    val isYocalTyping: Boolean = false,
    val streamingText: String = "",
    val pendingConfirmation: YocalConfirmation? = null,
)

/** Pending update from Yocal awaiting user confirmation. */
data class YocalConfirmation(
    val summary: String,
    val dayIndex: Int,
    val updatedDay: PlanDay,
)

class PlanChatViewModel(
    private val initialPlan: Plan,
    private val chatRepo: PlanChatRepository,
    private val userPrefsRepo: UserPreferencesRepository,
    private val planRepo: PlanRepository,
    private val eventRepo: EventRepository,
    private val venueRepo: VenueRepository,
    private val claudeApi: ClaudeApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlanChatUiState(isLoading = true))
    val uiState: StateFlow<PlanChatUiState> = _uiState.asStateFlow()

    /** Exposed for UI so it can visually highlight the current user's reactions. */
    val myUserId: String? get() = currentUserId

    /** Current plan state — updated when Yocal applies changes. */
    private var plan: Plan = initialPlan

    private val currentUserId: String? = userPrefsRepo.getLocalUserId()
    private val currentUserName: String = plan.attendees.firstOrNull { it.id == currentUserId }?.name
        ?: plan.creatorName?.takeIf { plan.creatorId == currentUserId }
        ?: "You"

    /** Running Claude conversation history (only @yocal exchanges). */
    private val yocalHistory: MutableList<ClaudeMessage> = mutableListOf()

    /** Cache of events returned from search_events, keyed by event id. */
    private val searchResultCache: MutableMap<String, Event> = mutableMapOf()

    init {
        loadMessages()
    }

    fun refresh() = loadMessages()

    private fun loadMessages() {
        viewModelScope.launch {
            val messages = chatRepo.getMessages(plan.id)
            val reactions = chatRepo.getReactions(plan.id)
            val reactionsByMessage = reactions.groupBy { it.messageId }
                .mapValues { entry ->
                    entry.value.groupBy { it.emoji }.mapValues { it.value.map { r -> r.userId } }
                }
            val mapped = messages.map { m ->
                PlanChatMessageUi(
                    id = m.id,
                    userId = m.userId,
                    senderName = m.senderName,
                    text = m.message,
                    isYocal = m.isYocal,
                    isCurrentUser = m.userId == currentUserId,
                    createdAt = m.createdAt,
                    reactions = reactionsByMessage[m.id] ?: emptyMap(),
                )
            }
            _uiState.update { it.copy(messages = mapped, isLoading = false) }
        }
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val userId = currentUserId ?: return

        viewModelScope.launch {
            val record = chatRepo.sendMessage(
                planId = plan.id,
                userId = userId,
                senderName = currentUserName,
                message = trimmed,
                isYocal = false,
            )
            if (record != null) {
                val msg = PlanChatMessageUi(
                    id = record.id,
                    userId = record.userId,
                    senderName = record.senderName,
                    text = record.message,
                    isYocal = false,
                    isCurrentUser = true,
                    createdAt = record.createdAt,
                )
                _uiState.update { it.copy(messages = it.messages + msg) }
            }

            // If the user tagged @yocal, route to Claude (respecting edit permissions)
            if (mentionsYocal(trimmed)) {
                handleYocalMention(trimmed)
            }
        }
    }

    private fun mentionsYocal(text: String): Boolean =
        text.contains("@yocal", ignoreCase = true)

    /** Route to Claude if the current user is allowed to edit, else post a private hint. */
    private suspend fun handleYocalMention(userText: String) {
        val isCreator = plan.creatorId != null && plan.creatorId == currentUserId
        val canEdit = isCreator || plan.attendeesCanEdit

        if (!canEdit) {
            // Local-only message; not persisted to DB
            val hint = "Only ${plan.creatorName ?: "the creator"} can make edits to this plan."
            val localMsg = PlanChatMessageUi(
                id = "local_${kotlin.random.Random.nextLong().toString(36)}",
                userId = "yocal",
                senderName = "Yocal",
                text = hint,
                isYocal = true,
                isCurrentUser = false,
                createdAt = "",
                isLocal = true,
            )
            _uiState.update { it.copy(messages = it.messages + localMsg) }
            return
        }

        // Check for a pending confirmation — user might be responding "yes"/"no"
        val pending = _uiState.value.pendingConfirmation
        if (pending != null) {
            val lower = userText.lowercase()
            if (lower.contains("yes") || lower.contains("confirm") || lower.contains("sure") || lower.contains("do it")) {
                confirmChange()
                return
            }
            if (lower.contains("no") || lower.contains("cancel") || lower.contains("nvm") || lower.contains("nope")) {
                cancelChange()
                return
            }
        }

        runYocalToolLoop(userText)
    }

    /** Runs the Claude tool loop for an @yocal mention. */
    private suspend fun runYocalToolLoop(userText: String) {
        _uiState.update { it.copy(isYocalTyping = true, streamingText = "") }

        try {
            yocalHistory.add(ClaudeMessage.text("user", userText))

            val systemPrompt = buildPlanSystemPrompt()
            val maxRounds = 5
            var rounds = 0

            while (rounds < maxRounds) {
                // Stream Claude response
                var finalResponse: live.localapp.local.data.remote.ClaudeResponse? = null
                val streamText = StringBuilder()

                claudeApi.sendRawStreaming(
                    systemPrompt = systemPrompt,
                    messages = yocalHistory.toList(),
                    tools = claudeTools,
                    model = ClaudeApiService.MODEL_PLAN,
                ).collect { evt ->
                    when (evt) {
                        is StreamEvent.TextDelta -> {
                            streamText.append(evt.text)
                            _uiState.update { it.copy(streamingText = streamText.toString()) }
                        }
                        is StreamEvent.Done -> { finalResponse = evt.response }
                        else -> { /* ignore ToolUseStart, InputDelta */ }
                    }
                }

                val response = finalResponse ?: break
                val toolCalls = claudeApi.extractToolCalls(response)
                val text = claudeApi.extractText(response) ?: ""

                // Terminal: update_plan → build confirmation, don't apply yet
                val updateCall = toolCalls.firstOrNull { it.name == "update_plan" }
                if (updateCall != null) {
                    // Add assistant + tool-result rounds to history so next calls see it
                    val assistantBlocks = claudeApi.buildAssistantContentBlocks(response)
                    yocalHistory.add(ClaudeMessage.blocks("assistant", assistantBlocks))
                    val toolResultBlocks = buildJsonArray {
                        addJsonObject {
                            put("type", JsonPrimitive("tool_result"))
                            put("tool_use_id", JsonPrimitive(updateCall.id))
                            put("content", JsonPrimitive("ok"))
                        }
                    }
                    yocalHistory.add(ClaudeMessage.blocks("user", toolResultBlocks))

                    buildConfirmationFromUpdateCall(updateCall, text)
                    _uiState.update { it.copy(isYocalTyping = false, streamingText = "") }
                    return
                }

                // If this response has text but no tool calls, Yocal is just answering/clarifying
                if (toolCalls.isEmpty()) {
                    if (text.isNotBlank()) {
                        postYocalMessage(text)
                    }
                    _uiState.update { it.copy(isYocalTyping = false, streamingText = "") }
                    return
                }

                // Non-terminal tools → execute + continue loop
                val assistantBlocks = claudeApi.buildAssistantContentBlocks(response)
                yocalHistory.add(ClaudeMessage.blocks("assistant", assistantBlocks))

                val toolResultBlocks = buildJsonArray {
                    for (call in toolCalls) {
                        val result = try { executeToolCall(call.name, call.input) }
                        catch (e: Throwable) { """{"error": "${e.message}"}""" }
                        addJsonObject {
                            put("type", JsonPrimitive("tool_result"))
                            put("tool_use_id", JsonPrimitive(call.id))
                            put("content", JsonPrimitive(result))
                        }
                    }
                }
                yocalHistory.add(ClaudeMessage.blocks("user", toolResultBlocks))
                rounds++
            }

            // If we exhaust rounds without a terminal, just post whatever streaming text we got
            val tail = _uiState.value.streamingText
            if (tail.isNotBlank()) postYocalMessage(tail)
        } catch (e: Throwable) {
            println("PlanChatVM.runYocalToolLoop error: ${e.message}")
            postYocalMessage("Hmm, I hit an error. Try again?")
        } finally {
            _uiState.update { it.copy(isYocalTyping = false, streamingText = "") }
        }
    }

    /** Persist + append a Yocal message. */
    private suspend fun postYocalMessage(text: String) {
        val userId = currentUserId ?: return
        val record = chatRepo.sendMessage(
            planId = plan.id,
            userId = userId,  // Yocal messages credited to the triggering user for RLS simplicity
            senderName = "Yocal",
            message = text,
            isYocal = true,
        )
        if (record != null) {
            val msg = PlanChatMessageUi(
                id = record.id,
                userId = record.userId,
                senderName = "Yocal",
                text = record.message,
                isYocal = true,
                isCurrentUser = false,
                createdAt = record.createdAt,
            )
            _uiState.update { it.copy(messages = it.messages + msg) }
        }
    }

    /** Build confirmation state from update_plan tool call. */
    private suspend fun buildConfirmationFromUpdateCall(call: ToolCall, text: String) {
        val input = call.input
        val dayIndex = input["day_index"]?.jsonPrimitive?.intOrNull ?: 0
        val label = input["label"]?.jsonPrimitive?.contentOrNull
            ?: plan.days.getOrNull(dayIndex)?.label ?: "Day ${dayIndex + 1}"
        val slotsArray = input["slots"]?.jsonArray ?: buildJsonArray {}

        // Materialize slots by looking up event details / venue details
        val newSlots = slotsArray.mapIndexed { i, el ->
            val obj = el.jsonObject
            val time = obj["time"]?.jsonPrimitive?.contentOrNull ?: ""
            val note = obj["note"]?.jsonPrimitive?.contentOrNull ?: ""
            val eventId = obj["event_id"]?.jsonPrimitive?.contentOrNull
            val venueName = obj["venue_name"]?.jsonPrimitive?.contentOrNull

            val slotId = "slot_${kotlin.random.Random.nextLong().toString(36)}_$i"

            if (eventId != null) {
                val event = searchResultCache[eventId] ?: try {
                    eventRepo.getEvent(eventId)
                } catch (_: Throwable) { null }
                if (event != null) {
                    PlanSlot(
                        id = slotId,
                        time = time,
                        emoji = "",
                        name = event.title,
                        detail = note.ifBlank { event.venueName ?: "" },
                        price = event.priceDisplay,
                        isFree = event.isFree,
                        eventId = event.id,
                        imageUrl = event.imageUrl,
                        ticketUrl = event.ticketUrl,
                        venueAddress = event.venueAddress,
                        venueLat = event.venueLat,
                        venueLng = event.venueLng,
                        priceMin = event.priceMin,
                        priceMax = event.priceMax,
                        sourceUrl = event.sourceUrl,
                    )
                } else {
                    // Fallback — use the note as the name if we can't resolve
                    PlanSlot(id = slotId, time = time, emoji = "", name = note.ifBlank { "Event" }, detail = "", price = "", isFree = false, eventId = eventId)
                }
            } else if (venueName != null) {
                val venue = try { venueRepo.searchByName(venueName) } catch (_: Throwable) { null }
                PlanSlot(
                    id = slotId,
                    time = time,
                    emoji = "",
                    name = venueName,
                    detail = note,
                    price = "",
                    isFree = false,
                    venueAddress = venue?.address,
                    venueLat = venue?.latitude,
                    venueLng = venue?.longitude,
                    venueWebsite = venue?.website,
                    venueRating = venue?.googleRating,
                    venueCategory = venue?.category,
                    reservationUrl = venue?.reservationUrl,
                )
            } else {
                PlanSlot(id = slotId, time = time, emoji = "", name = note.ifBlank { "Activity" }, detail = "", price = "", isFree = false, isCustom = true)
            }
        }

        val updatedDay = PlanDay(
            label = label,
            shortName = plan.days.getOrNull(dayIndex)?.shortName ?: label.take(3),
            slots = newSlots,
        )

        val summary = buildChangeSummary(dayIndex, updatedDay, text)
        _uiState.update { state ->
            state.copy(
                pendingConfirmation = YocalConfirmation(summary = summary, dayIndex = dayIndex, updatedDay = updatedDay),
            )
        }

        // Also post a Yocal message with the summary + explicit ask
        val askText = if (text.isNotBlank()) "$text\n\n$summary\n\nSound good? Reply yes to apply." else "$summary\n\nSound good? Reply yes to apply."
        postYocalMessage(askText)
    }

    private fun buildChangeSummary(dayIndex: Int, updatedDay: PlanDay, context: String): String {
        val oldDay = plan.days.getOrNull(dayIndex)
        val oldNames = oldDay?.slots?.map { it.name }?.toSet() ?: emptySet()
        val newNames = updatedDay.slots.map { it.name }.toSet()
        val added = newNames - oldNames
        val removed = oldNames - newNames
        val kept = oldNames intersect newNames

        val parts = mutableListOf<String>()
        if (added.isNotEmpty()) parts.add("add ${added.joinToString(", ")}")
        if (removed.isNotEmpty()) parts.add("remove ${removed.joinToString(", ")}")
        if (parts.isEmpty() && kept.size == newNames.size) parts.add("adjust the order/times")
        return "Here's what I'd change on ${updatedDay.label}: ${parts.joinToString("; ")}."
    }

    fun confirmChange() {
        val pending = _uiState.value.pendingConfirmation ?: return
        viewModelScope.launch {
            val updatedDays = plan.days.toMutableList()
            if (pending.dayIndex in updatedDays.indices) {
                updatedDays[pending.dayIndex] = pending.updatedDay
            } else {
                updatedDays.add(pending.updatedDay)
            }
            val updatedPlan = plan.copy(days = updatedDays)
            planRepo.updatePlan(updatedPlan)
            plan = updatedPlan
            _uiState.update { it.copy(pendingConfirmation = null) }
            postYocalMessage("Done! I've updated the plan.")
        }
    }

    fun cancelChange() {
        val pending = _uiState.value.pendingConfirmation ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(pendingConfirmation = null) }
            postYocalMessage("No worries — keeping the plan as is.")
        }
    }

    fun toggleReaction(messageId: String, emoji: String) {
        val userId = currentUserId ?: return
        val current = _uiState.value.messages.find { it.id == messageId } ?: return
        val usersForEmoji = current.reactions[emoji].orEmpty()
        val alreadyReacted = userId in usersForEmoji

        _uiState.update { state ->
            state.copy(messages = state.messages.map { m ->
                if (m.id != messageId) m else {
                    val newUsers = if (alreadyReacted) usersForEmoji - userId else usersForEmoji + userId
                    val newReactions = m.reactions.toMutableMap()
                    if (newUsers.isEmpty()) newReactions.remove(emoji) else newReactions[emoji] = newUsers
                    m.copy(reactions = newReactions)
                }
            })
        }

        viewModelScope.launch {
            if (alreadyReacted) chatRepo.removeReaction(messageId, userId, emoji)
            else chatRepo.addReaction(messageId, userId, emoji)
        }
    }

    // ── Tool execution ──

    private suspend fun executeToolCall(name: String, input: JsonObject): String {
        return when (name) {
            "search_events" -> searchEvents(input)
            "search_venues" -> searchVenues(input)
            "get_event_details" -> getEventDetails(input)
            else -> """{"error": "Unsupported tool: $name"}"""
        }
    }

    private suspend fun searchEvents(input: JsonObject): String {
        val location = input["location"]?.jsonPrimitive?.contentOrNull
        val category = input["category"]?.jsonPrimitive?.contentOrNull
        val dateRange = input["date_range"]?.jsonPrimitive?.contentOrNull
        val keyword = input["keyword"]?.jsonPrimitive?.contentOrNull
        val limit = input["limit"]?.jsonPrimitive?.intOrNull ?: 30
        val startDate = input["start_date"]?.jsonPrimitive?.contentOrNull
        val endDate = input["end_date"]?.jsonPrimitive?.contentOrNull

        val events = eventRepo.searchEvents(
            location = location ?: plan.city,
            category = category,
            dateRange = dateRange,
            keyword = keyword,
            limit = limit,
            startDate = startDate,
            endDate = endDate,
            radiusMiles = 25.0,
        )

        events.forEach { searchResultCache[it.id] = it }

        if (events.isEmpty()) return "No events found."
        return buildString {
            events.take(limit).forEach { e ->
                val price = when {
                    e.isFree -> "FREE"
                    e.priceMin != null -> "$${e.priceMin.toInt()}"
                    else -> ""
                }
                append("[evt:${e.id}] ${e.title} — ${e.venueName ?: "?"} (${e.venueCity})")
                if (e.startAt != null) append(" ${e.dateDisplay}")
                if (price.isNotBlank()) append(" $price")
                append("\n")
            }
        }
    }

    private suspend fun searchVenues(input: JsonObject): String {
        val location = input["location"]?.jsonPrimitive?.contentOrNull
        val category = input["category"]?.jsonPrimitive?.contentOrNull
        val keyword = input["keyword"]?.jsonPrimitive?.contentOrNull
        val limit = input["limit"]?.jsonPrimitive?.intOrNull ?: 20
        val venues = venueRepo.searchVenues(
            location = location ?: plan.city,
            category = category,
            keyword = keyword,
            limit = limit,
        )
        if (venues.isEmpty()) return "No venues found."
        return buildString {
            venues.forEach { v ->
                append("[${v.id}] ${v.name} | ${v.city}")
                v.category?.let { append(" | $it") }
                v.googleRating?.let { append(" | ★ $it") }
                append("\n")
            }
        }
    }

    private suspend fun getEventDetails(input: JsonObject): String {
        val eventId = input["event_id"]?.jsonPrimitive?.contentOrNull ?: return "missing event_id"
        val event = searchResultCache[eventId] ?: eventRepo.getEvent(eventId) ?: return "Event not found: $eventId"
        searchResultCache[eventId] = event
        return buildString {
            append("ID: ${event.id}\n")
            append("Title: ${event.title}\n")
            append("Venue: ${event.venueName ?: "?"} (${event.venueCity})\n")
            event.venueAddress?.let { append("Address: $it\n") }
            event.startAt?.let { append("Date: ${event.dateDisplay}\n") }
            append("Free: ${event.isFree}\n")
            if (event.priceDisplay.isNotBlank()) append("Price: ${event.priceDisplay}\n")
            event.description?.let { append("Description: ${it.take(400)}\n") }
            event.ticketUrl?.let { append("Tickets: $it\n") }
        }
    }

    // ── System prompt ──

    private fun buildPlanSystemPrompt(): String {
        val today = kotlin.time.Clock.System.now()
            .toString().take(10)

        val planContext = buildString {
            appendLine("CURRENT PLAN (title: \"${plan.title}\") in ${plan.city}:")
            plan.days.forEachIndexed { i, day ->
                appendLine("Day $i (${day.label}):")
                if (day.slots.isEmpty()) appendLine("  (empty)")
                day.slots.forEach { s ->
                    val tag = when {
                        s.eventId != null -> "[event_id: ${s.eventId}]"
                        s.isCustom -> "[custom]"
                        else -> "[venue: ${s.name}]"
                    }
                    appendLine("  ${s.time} ${s.name} — ${s.detail} $tag")
                }
            }
        }

        return """
You are Yocal, an AI assistant helping a group edit an existing plan.
Today is $today.

$planContext

GUIDELINES:
- You are responding to a group chat message where someone tagged @yocal.
- Your job: suggest a concrete change to the plan.
- Use search_events / search_venues / get_event_details tools to find alternatives.
- When ready, call `update_plan` with the full updated slot list for the target day.
- For slots: use event_id when the user wants a real ticketed event, venue_name for restaurants/bars.
- Do NOT call present_plan — only update_plan is valid in this context (the plan already exists).
- Keep your text response brief (1-2 sentences). The confirmation UI will handle the details.
- Do NOT try to delete the plan or change fundamental things (title, dates) — only day slots.
- If the request is ambiguous, ask a clarifying question.
""".trimIndent()
    }
}

/** Helper used by the payload builder. */
private inline fun kotlinx.serialization.json.JsonArrayBuilder.addJsonObject(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
    add(buildJsonObject(block))
}
