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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import live.localapp.local.data.remote.ClaudeApiService
import live.localapp.local.data.remote.ClaudeMessage
import live.localapp.local.data.remote.StreamEvent
import live.localapp.local.data.remote.ToolCall
import live.localapp.local.data.remote.claudeTools
import live.localapp.local.data.remote.toolsWithWeb
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
                    // Bo replies are credited to the triggering user's userId for
                    // RLS simplicity, so userId-only matching mis-classifies Bo's
                    // historical messages as "mine" on reload. Exclude isYocal.
                    isCurrentUser = m.userId == currentUserId && !m.isYocal,
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

            // V1: per-plan chat is Bo-only. Every user message routes to Claude
            // (respecting edit permissions). The legacy @Bo / @yocal mention check
            // was removed — this surface isn't a group chat anymore.
            handleYocalMention(trimmed)
        }
    }

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
                senderName = "Bo",
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

        // Phase 1: Bo web search/fetch budget gate (same flow as main chat).
        val budget = fetchBoWebBudget()
        val toolsForTurn = toolsWithWeb(
            base = claudeTools,
            allowWebSearch = budget?.allowWebSearch == true,
            allowWebFetch = budget?.allowWebFetch == true,
        )
        val webCallTally = mutableListOf<Triple<String, String?, Int?>>()

        try {
            yocalHistory.add(ClaudeMessage.text("user", userText))

            val baseSystemPrompt = buildPlanSystemPrompt()
            val systemPrompt = augmentSystemPromptWithBudget(baseSystemPrompt, budget)
            val maxRounds = 5
            var rounds = 0

            while (rounds < maxRounds) {
                // Stream Claude response
                var finalResponse: live.localapp.local.data.remote.ClaudeResponse? = null
                val streamText = StringBuilder()

                claudeApi.sendRawStreaming(
                    systemPrompt = systemPrompt,
                    messages = yocalHistory.toList(),
                    tools = toolsForTurn,
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
                tallyWebCalls(response, webCallTally)
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
                    } else {
                        // Defensive: never leave the user hanging with a silent end-of-turn.
                        postYocalMessage("Hmm — I'm not sure what to change. Can you give me a bit more detail?")
                    }
                    _uiState.update { it.copy(isYocalTyping = false, streamingText = "") }
                    return
                }

                // Non-terminal tools → execute + continue loop. Persist any text
                // Bo produced this round as a real chat message FIRST, otherwise
                // it only lives in streamingText and gets overwritten by the next
                // round's TextDelta events — leaving the user with just the final
                // confirmation message and no record of Bo's reasoning.
                if (text.isNotBlank()) {
                    postYocalMessage(text)
                    _uiState.update { it.copy(streamingText = "") }
                }

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
            if (tail.isNotBlank()) {
                postYocalMessage(tail)
            } else {
                postYocalMessage("Took me a few tries and I didn't quite land it — want to try again with a bit more detail?")
            }
        } catch (e: Throwable) {
            println("PlanChatVM.runYocalToolLoop error: ${e.message}")
            postYocalMessage("Hmm, I hit an error. Try again?")
        } finally {
            _uiState.update { it.copy(isYocalTyping = false, streamingText = "") }
            // Phase 1: log per-turn web tool usage. Fire-and-forget.
            if (webCallTally.isNotEmpty()) {
                viewModelScope.launch { logBoWebCalls(webCallTally.toList()) }
            }
        }
    }

    /** Persist + append a Yocal message. */
    private suspend fun postYocalMessage(text: String) {
        val userId = currentUserId ?: return
        val record = chatRepo.sendMessage(
            planId = plan.id,
            userId = userId,  // Bo messages credited to the triggering user for RLS simplicity
            senderName = "Bo",
            message = text,
            isYocal = true,
        )
        if (record != null) {
            val msg = PlanChatMessageUi(
                id = record.id,
                userId = record.userId,
                senderName = "Bo",
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

            // Push the updated plan into the native Plan Detail view so the
            // user sees the change without reopening, then signal the chat
            // sheet to auto-dismiss with a success state.
            try {
                live.localapp.local.platform.pushNativePlanState(updatedPlan)
            } catch (_: Throwable) {}
            try {
                live.localapp.local.platform.signalPlanEditApplied()
            } catch (_: Throwable) {}
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
You are Bo, an AI assistant helping a group edit an existing plan.
Today is $today.

$planContext

GUIDELINES:
- You are responding to a group chat message where someone tagged @Bo (or the legacy @yocal).
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

    // ── Bo web tools (Phase 1) ────────────────────────────────────────────

    private data class BoWebBudget(
        val allowWebSearch: Boolean,
        val allowWebFetch: Boolean,
        val reason: String,
        val userState: String?,
        val liveStatesList: String,
    )

    private val webHttpClient = io.ktor.client.HttpClient()

    private suspend fun fetchBoWebBudget(): BoWebBudget? {
        val userId = userPrefsRepo.getLocalUserId() ?: return null
        return try {
            val res = webHttpClient.get("${live.localapp.local.Config.SUPABASE_URL}/functions/v1/bo-web-budget?user_id=$userId") {
                header("Authorization", "Bearer ${live.localapp.local.Config.SUPABASE_ANON_KEY}")
            }
            if (res.status.value !in 200..299) return null
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(res.bodyAsText()).jsonObject
            BoWebBudget(
                allowWebSearch = obj["allow_web_search"]?.jsonPrimitive?.booleanOrNull == true,
                allowWebFetch = obj["allow_web_fetch"]?.jsonPrimitive?.booleanOrNull == true,
                reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                userState = obj["user_state"]?.jsonPrimitive?.contentOrNull,
                liveStatesList = obj["live_states_list"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        } catch (e: Throwable) {
            println("PlanChatVM fetchBoWebBudget error: ${e.message}")
            null
        }
    }

    private fun augmentSystemPromptWithBudget(systemPrompt: String, budget: BoWebBudget?): String {
        if (budget == null) return systemPrompt
        val lines = buildString {
            appendLine()
            appendLine("--- WEB SEARCH GATE ---")
            when (budget.reason) {
                "ok" -> {
                    appendLine("You have access to web_search and web_fetch tools as a fallback. PRIORITY ORDER:")
                    appendLine("  1. ALWAYS try search_events / search_venues against our database first.")
                    appendLine("  2. Use web_search ONLY when (a) DB returns 0 results, (b) the user asks something time-sensitive (open hours, weather, 'this weekend'), or (c) they reference a specific named event/venue not in DB.")
                    appendLine("  3. Use web_fetch when the user shares a URL — fetch it directly instead of searching.")
                    appendLine("Cap yourself to 3 web_search invocations per turn.")
                }
                "not_live_state" -> {
                    appendLine("USER IS IN A NON-LIVE STATE${budget.userState?.let { " ($it)" } ?: ""}. Web search is DISABLED.")
                    appendLine("If the user asks anything that would require web lookup, respond:")
                    appendLine("  \"Local is not yet live in your state. It is currently live in ${budget.liveStatesList}.\"")
                }
                "user_day_cap", "user_hour_cap" -> {
                    appendLine("USER HIT DAILY/HOURLY WEB-SEARCH QUOTA. Web search is DISABLED for this turn.")
                    appendLine("If they ask something requiring web search, respond: \"I've hit my lookup limit for now — try again later.\"")
                }
                "monthly_cap" -> {
                    appendLine("GLOBAL MONTHLY WEB-SEARCH BUDGET REACHED. Web search is DISABLED.")
                }
                else -> {
                    appendLine("Web search status: ${budget.reason}. Use database tools only.")
                }
            }
        }
        return systemPrompt + lines
    }

    private fun tallyWebCalls(
        response: live.localapp.local.data.remote.ClaudeResponse,
        tally: MutableList<Triple<String, String?, Int?>>,
    ) {
        for (block in response.content) {
            if (block.type == "server_tool_use" && (block.name == "web_search" || block.name == "web_fetch")) {
                val q = block.input?.get("query")?.jsonPrimitive?.contentOrNull
                    ?: block.input?.get("url")?.jsonPrimitive?.contentOrNull
                tally.add(Triple(block.name!!, q, null))
            }
        }
    }

    private suspend fun logBoWebCalls(calls: List<Triple<String, String?, Int?>>) {
        val userId = userPrefsRepo.getLocalUserId() ?: return
        try {
            val payload = buildJsonObject {
                put("user_id", JsonPrimitive(userId))
                put("caller", JsonPrimitive("bo_chat"))
                put("calls", buildJsonArray {
                    for ((tool, query, count) in calls) {
                        add(buildJsonObject {
                            put("tool", JsonPrimitive(tool))
                            if (query != null) put("query", JsonPrimitive(query))
                            if (count != null) put("result_count", JsonPrimitive(count))
                        })
                    }
                })
            }
            webHttpClient.post("${live.localapp.local.Config.SUPABASE_URL}/functions/v1/bo-web-budget") {
                header("Authorization", "Bearer ${live.localapp.local.Config.SUPABASE_ANON_KEY}")
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
        } catch (e: Throwable) {
            println("PlanChatVM logBoWebCalls error: ${e.message}")
        }
    }
}

/** Helper used by the payload builder. */
private inline fun kotlinx.serialization.json.JsonArrayBuilder.addJsonObject(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
    add(buildJsonObject(block))
}
