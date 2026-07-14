package live.localapp.local.ui.plans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.datetime.toLocalDateTime
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
    private val mutationLog: live.localapp.local.data.sync.MutationLog,
    private val syncer: live.localapp.local.data.sync.PlanSyncer,
    private val boTools: live.localapp.local.data.bo.BoTools,
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

    /** Set by [beginAddEventFlow] when the chat is opened via "+ Add event".
     *  Holds the canned disambiguation question until messages have loaded so
     *  the async [loadMessages] doesn't clobber it. */
    private var pendingAddEventQuestion: String? = null

    /** Cache of events returned from search_events, keyed by event id. */
    private val searchResultCache: MutableMap<String, Event> get() = boTools.searchResultCache

    companion object {
        /** Control-char-delimited sentinel so a normal typed message can never
         *  collide with the "+ Add event" entry point. */
        private const val ADD_EVENT_TOKEN = "add_event"

        /** Builds the prefill string AppNavigation/iOS pass when "+ Add event" is tapped. */
        fun addEventPrefill(dayLabel: String): String = ADD_EVENT_TOKEN + dayLabel

        /** Returns the day label if [prefill] is an add-event sentinel, else null. */
        fun parseAddEventDay(prefill: String?): String? =
            if (prefill?.startsWith(ADD_EVENT_TOKEN) == true) prefill.removePrefix(ADD_EVENT_TOKEN) else null
    }

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
            flushPendingAddEventQuestion()
        }
    }

    /** Opens the "+ Add event" flow: posts a canned disambiguation question
     *  deterministically (not via the LLM) and seeds [yocalHistory] so Bo has
     *  the day + intent context when the user replies. */
    fun beginAddEventFlow(dayLabel: String) {
        val question = "Sure — what are you adding to $dayLabel? A restaurant for food " +
            "or drinks, a place to go, an actual event, or something you've planned on your own?"
        // Seed Claude history (must start with a user turn) so the reply has context.
        yocalHistory.add(ClaudeMessage.text("user", "I want to add something to $dayLabel. What are my options?"))
        yocalHistory.add(ClaudeMessage.text("assistant", question))
        pendingAddEventQuestion = question
        if (!_uiState.value.isLoading) flushPendingAddEventQuestion()
    }

    private fun flushPendingAddEventQuestion() {
        val q = pendingAddEventQuestion ?: return
        pendingAddEventQuestion = null
        val msg = PlanChatMessageUi(
            id = "local_${kotlin.random.Random.nextLong().toString(36)}",
            userId = "bo",
            senderName = "Bo",
            text = q,
            isYocal = true,
            isCurrentUser = false,
            createdAt = "",
            isLocal = true,
        )
        _uiState.update { it.copy(messages = it.messages + msg) }
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
        // Refresh the plan from the server, then replay any unsynced local
        // mutations on top so Bo sees exactly what the user sees. Without
        // the replay step Bo would (a) miss recent edits the syncer hasn't
        // flushed yet and (b) end up with an `update_plan` payload that
        // re-hydrates slots the user already removed.
        try {
            val fresh = planRepo.getPlan(plan.id)
            if (fresh != null) {
                val pending = mutationLog.pending.value.filter { it.mutation.planId == plan.id }
                plan = pending.fold(fresh) { p, record -> record.mutation.applyTo(p) }
            } else {
                println("PlanChatVM: getPlan(${plan.id}) returned null — falling back to local plan state, may be stale")
            }
        } catch (e: Throwable) {
            println("PlanChatVM: plan refresh failed (${e.message}) — falling back to local plan state")
        }

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
            // Enqueue a typed ReplaceDay mutation so the syncer is the single
            // owner of the network write. The reconciler picks the op up
            // immediately, so the user sees the change before the round-trip
            // completes — and a failed network call retries instead of being
            // silently dropped.
            val opId = "op_chat_" + kotlin.time.Clock.System.now().toEpochMilliseconds().toString(36) +
                "_" + kotlin.random.Random.nextLong().toString(36)
            val mutation = live.localapp.local.data.sync.PlanMutation.ReplaceDay(
                opId = opId,
                planId = plan.id,
                createdAt = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                dayIndex = pending.dayIndex,
                day = pending.updatedDay,
            )
            syncer.enqueue(mutation)

            // Apply locally so this VM's plan reference (which the system
            // prompt and follow-up turns read from) reflects the change.
            val updatedDays = plan.days.toMutableList()
            if (pending.dayIndex in updatedDays.indices) {
                updatedDays[pending.dayIndex] = pending.updatedDay
            } else {
                updatedDays.add(pending.updatedDay)
            }
            val updatedPlan = plan.copy(days = updatedDays)
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
            "save_web_finding" -> saveWebFinding(input)
            else -> """{"error": "Unsupported tool: $name"}"""
        }
    }

    private suspend fun saveWebFinding(input: JsonObject): String =
        boTools.saveWebFinding(input)

    /**
     * Geographic center of the plan's existing stops that carry coordinates.
     * Used to scope event search to the PLAN's location rather than the user's
     * current location or a brittle city-name string match. Null when no stop
     * has coords yet (e.g. a brand-new empty plan/day).
     */
    private fun planAnchorCoords(): Pair<Double, Double>? {
        val coords = plan.days.flatMap { it.slots }.mapNotNull { s ->
            val la = s.venueLat
            val ln = s.venueLng
            if (la != null && ln != null) la to ln else null
        }
        if (coords.isEmpty()) return null
        return (coords.sumOf { it.first } / coords.size) to (coords.sumOf { it.second } / coords.size)
    }

    private suspend fun searchEvents(input: JsonObject): String {
        val location = input["location"]?.jsonPrimitive?.contentOrNull
        val category = input["category"]?.jsonPrimitive?.contentOrNull
        val dateRange = input["date_range"]?.jsonPrimitive?.contentOrNull
        val keyword = input["keyword"]?.jsonPrimitive?.contentOrNull
        val limit = input["limit"]?.jsonPrimitive?.intOrNull ?: 30
        val startDate = input["start_date"]?.jsonPrimitive?.contentOrNull
        val endDate = input["end_date"]?.jsonPrimitive?.contentOrNull

        // LOCATION SCOPING: this is a plan-editing chat, so results must be
        // near the PLAN. When the plan already has stops with coordinates we
        // scope by a geo radius around their centroid — the proper "events
        // near this plan" query. Only fall back to the legacy city-name match
        // when the plan has no coords yet.
        val anchor = planAnchorCoords()
        val events = if (anchor != null) {
            eventRepo.searchEventsBroad(
                userLat = anchor.first,
                userLng = anchor.second,
                radiusMiles = 25.0,
                dateRange = dateRange,
                startDate = startDate,
                endDate = endDate,
                // searchEventsBroad has no category param; fold it into the
                // keyword so a category-only search still narrows results.
                keyword = keyword ?: category,
                limit = limit,
            )
        } else {
            // strict_location defaults to TRUE when a location is set. Bo must
            // explicitly pass false to widen.
            val strictLocation = input["strict_location"]?.jsonPrimitive?.booleanOrNull
                ?: !location.isNullOrBlank()
            val cityScoped = location ?: plan.city
            val rawEvents = eventRepo.searchEvents(
                location = cityScoped,
                category = category,
                dateRange = dateRange,
                keyword = keyword,
                limit = limit,
                startDate = startDate,
                endDate = endDate,
                radiusMiles = 25.0,
            )
            if (strictLocation && cityScoped.isNotBlank()) {
                val target = cityScoped.trim().lowercase()
                rawEvents.filter { e ->
                    val ec = e.venueCity.trim().lowercase()
                    if (ec.isBlank()) return@filter false
                    ec == target || ec.contains(target) || target.contains(ec)
                }
            } else rawEvents
        }.filter { e ->
            // Bo must never surface a result it can't place on the map — an
            // event with no coordinates can't be distance-ranked or located,
            // so drop any lacking BOTH a venue and an event lat/lng. Same
            // coordinate-integrity rule as the create-plan chat surface.
            (e.venueLat ?: e.eventLat) != null && (e.venueLng ?: e.eventLng) != null
        }

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
        ).filter { it.latitude != null && it.longitude != null }
        // ^ Bo must never surface a venue it can't place on the map — drop
        //   coordless venues, same rule as searchEvents.
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

    private suspend fun getEventDetails(input: JsonObject): String =
        boTools.getEventDetails(input)

    // ── System prompt ──

    /**
     * Expand "Thursday, May 15" to "Thursday, May 15, 2026 (ISO 2026-05-15)".
     * Bo's `search_events` calls need an unambiguous date — if the year
     * is missing it sometimes searches relative to today and reports
     * "I'm not finding events on May 15" when it actually queried with
     * the wrong year. Year is inferred: if the parsed month is already
     * past today's month, use next year; otherwise current year.
     */
    private fun expandLabelWithYearAndIso(label: String, today: kotlinx.datetime.LocalDate): String {
        val months = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )
        val abbrs = listOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
        val afterComma = label.substringAfter(',', label).trim()
        val tokens = afterComma.split(' ').filter { it.isNotBlank() }
        if (tokens.size < 2) return label
        val monthIdx = months.indexOfFirst { it.equals(tokens[0], ignoreCase = true) }
            .let { if (it >= 0) it else abbrs.indexOfFirst { a -> a.equals(tokens[0].take(3), ignoreCase = true) } }
        if (monthIdx < 0) return label
        val day = tokens[1].takeWhile { it.isDigit() }.toIntOrNull() ?: return label
        val year = if (monthIdx + 1 < today.monthNumber) today.year + 1 else today.year
        val iso = "$year-${(monthIdx + 1).toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
        return "$label, $year (ISO $iso)"
    }

    private fun buildPlanSystemPrompt(): String {
        val todayLocal = kotlin.time.Clock.System.now()
            .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date

        val planContext = buildString {
            appendLine("CURRENT PLAN (title: \"${plan.title}\") in ${plan.city}:")
            plan.days.forEachIndexed { i, day ->
                // Append explicit year + ISO date so Bo never has to guess
                // which "Thursday, May 15" we mean. Without this, Bo
                // confuses plan dates with today and asks the user to
                // disambiguate ("which Thursday?") even though the plan
                // is unambiguous from context.
                val dated = expandLabelWithYearAndIso(day.label, todayLocal)
                // Inline the exact search_events args needed to add an event
                // to this day. Bo has been dropping start_date / end_date
                // when adding to a specific plan day even with the rule in
                // the prompt — pinning the args literally beside the day
                // label makes them impossible to miss.
                val iso = Regex("""\(ISO (\d{4}-\d{2}-\d{2})\)""").find(dated)?.groupValues?.get(1)
                val pinHint = if (iso != null) {
                    " — to add or swap events here, call search_events with start_date=\"$iso\" end_date=\"$iso\""
                } else ""
                appendLine("Day $i ($dated)$pinHint:")
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

        return buildString {
            appendLine("You are Bo, an AI assistant helping a group edit an existing plan.")
            appendLine()
            // ABSOLUTE RULES — these come BEFORE any other content so they
            // can't get lost in a long prompt. Two recurring failures here:
            // (1) Bo forgets start_date / end_date when adding events to a
            //     specific day, then narrates the resulting wrong-date
            //     results to the user.
            // (2) Bo invents technical errors ("the database is timing
            //     out") when its own parameters are the cause.
            // The CURRENT PLAN block below repeats the per-day pin args
            // inline; the rules below hold Bo to using them.
            appendLine("ABSOLUTE RULES (highest priority — these override everything else below):")
            appendLine("1. ADDING SOMETHING TO A SPECIFIC DAY — DISAMBIGUATE FIRST:")
            appendLine("   1a. If the user wants to ADD to a day but has NOT said WHAT KIND of thing (no specific event/venue name and no clear search term — e.g. \"add something to Friday\"), your ENTIRE turn is exactly ONE question and NO tool call: \"Want to add a restaurant for food or drinks, a place to go, an actual event, or something you've planned on your own?\" Then stop and wait.")
            appendLine("   1b. Once you know the kind (or the user already named it):")
            appendLine("       • an actual event → your VERY FIRST tool call MUST be search_events with BOTH start_date AND end_date set to that day's ISO date. The CURRENT PLAN block below shows the exact args next to each day label — copy them literally. Never search 'broadly' first, never pass date_range='this_week', never omit either field.")
            appendLine("       • a restaurant (food/drinks) or a place to go → call search_venues. No date needed; do NOT call search_events for these.")
            appendLine("       • something they've planned on their own → do NOT search at all. Ask for the name and a time if you don't have them, then call update_plan adding it as a custom slot (a slot with neither event_id nor venue_name — just time + note).")
            appendLine("2. NEVER FABRICATE ERRORS. Phrases like 'the database is experiencing timeout issues', 'the system is down', 'there's an indexing problem', 'I'm having trouble connecting' are LIES — the system is fine. If a search came back wrong, it's because YOU forgot a parameter (almost always start_date). Fix the parameter and search again silently. NEVER tell the user the system is broken.")
            appendLine("3. NO PREAMBLE NARRATION. Never type 'I'll search…', 'Let me try a broader search…', 'I notice the results are showing today instead of May 14', or anything else that reports on what tools did or didn't do. The UI shows a searching indicator already — extra text becomes a chat bubble of noise. Just call the tool. When you respond AFTER the final tool, deliver the answer directly.")
            appendLine()
            appendLine(planContext)
            appendLine(live.localapp.local.data.bo.BoPrompt.calendarBlock())
            appendLine()
            appendLine(live.localapp.local.data.bo.BoPrompt.sharedRules())
            appendLine()
            appendLine("""
PLAN-EDIT GUIDELINES (specific to this surface):
- You are responding to a group chat message where someone tagged @Bo (or the legacy @yocal).
- Your job: suggest a concrete change to the plan above.
- DISAMBIGUATE BEFORE SEARCHING (HARD RULE): If the user wants to add to a day but hasn't said what KIND of thing (restaurant for food/drinks, a place to go, an actual event, or something they've planned on their own), ask that one question first and call NO tool that turn (see ABSOLUTE RULE 1). Only after you know the kind do the date-pinning / tool-routing rules below apply.
- DATE PINNING (HARD RULE — single biggest failure mode here): When the user asks to add or swap an EVENT on a specific day of THIS plan, your VERY FIRST tool call MUST be search_events with BOTH start_date AND end_date set to that day's calendar date in YYYY-MM-DD form. Read the date from the CURRENT PLAN day labels above (every day is shown with its ISO date in parens). Example: user says "add an event to Thursday, May 14, 2026 before dinner" → search_events(start_date="2026-05-14", end_date="2026-05-14", limit=30). DO NOT pass date_range="this_week", DO NOT omit start_date, DO NOT search "broadly" first. The search returns whatever is on May 14; if it's empty, say so honestly in ONE sentence — don't widen the date.
- LOCATION CONTEXT (HARD RULE): The plan exists in ${plan.city}; that's the default search location. BEFORE searching, look at the OTHER slots on the target day (shown in the CURRENT PLAN block above). If those slots have venue names you recognize as being in a specific neighborhood/town, pass that as `location` to search_events so the new event lands geographically near the rest of the day. Example: target day has "12:30 PM Cafe Escadrille" (Burlington, MA) and "6:30 PM Tempo" (Waltham, MA) — pass location="Waltham" so the new stop is within walking/short-drive distance of the other two. If the target day is empty (no other slots) and the plan's city is broad, default to the plan's city. NEVER suggest a stop that's >30 minutes away from the day's other slots without telling the user honestly: "The closest match I found is in {city} which is ~45 min from your other stops — want me to keep it or look closer?"
- NO PREAMBLE, NO STATUS NARRATION, NO FABRICATED ERRORS (HARD RULE): Never type "I'll search…", "Let me try a broader search…", "I notice the results are showing today instead of May 14", "The database is experiencing timeout issues", or any sentence that reports on what the tool did or didn't do. The UI shows a searching indicator — anything you write before the next tool call is invisible noise that gets shown as a chat bubble. If a search returns wrong results, FIX YOUR PARAMETERS (probably you forgot start_date) and search again silently — never narrate the retry. NEVER fabricate technical errors ("timeout", "database issues", "system problems"); if the search comes back empty for a reason you can't explain, just say "Nothing's surfacing for May 14 — want me to try a different day?" without referencing the system.
- Use search_events / search_venues / get_event_details tools to find alternatives.
- When ready, call `update_plan` with the full updated slot list for the target day.
- For slots: use event_id when the user wants a real ticketed event, venue_name for restaurants/bars/places, and neither (just time + note) for a custom thing the user planned on their own.
- Do NOT call present_plan — only update_plan is valid in this context (the plan already exists).
- Keep your text response brief (1-2 sentences). The confirmation UI will handle the details.
- Do NOT try to delete the plan or change fundamental things (title, dates) — only day slots.
- If the request is genuinely ambiguous in some OTHER way (e.g. the user didn't name a day), ask ONE clarifying question. (The what-kind-of-thing question is already handled by ABSOLUTE RULE 1 and takes precedence.)
""".trimIndent())
        }
    }

    // ── Bo web tools (Phase 1) ────────────────────────────────────────────

    private val webHttpClient = io.ktor.client.HttpClient()

    private suspend fun fetchBoWebBudget(): live.localapp.local.data.bo.BoWebBudget? {
        val userId = userPrefsRepo.getLocalUserId()
        if (userId == null) {
            println("Bo[web-gate plan]: NULL userId — skipping fetch")
            return null
        }
        return try {
            println("Bo[web-gate plan]: fetching for user=$userId")
            val res = webHttpClient.get("${live.localapp.local.Config.SUPABASE_URL}/functions/v1/bo-web-budget?user_id=$userId") {
                header("Authorization", "Bearer ${live.localapp.local.Config.SUPABASE_ANON_KEY}")
            }
            val body = res.bodyAsText()
            println("Bo[web-gate plan]: status=${res.status.value} body=${body.take(300)}")
            if (res.status.value !in 200..299) return null
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
            live.localapp.local.data.bo.BoWebBudget(
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

    private fun augmentSystemPromptWithBudget(
        systemPrompt: String,
        budget: live.localapp.local.data.bo.BoWebBudget?,
    ): String {
        if (budget == null) {
            println("Bo[web-gate plan]: NULL budget — web tools NOT included")
            return systemPrompt
        }
        println("Bo[web-gate plan]: reason=${budget.reason} allowSearch=${budget.allowWebSearch}")
        return systemPrompt + live.localapp.local.data.bo.BoPrompt.webBudgetBlock(budget)
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
