package live.localapp.local.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import live.localapp.local.Config
import live.localapp.local.ui.chat.ItineraryDay
import live.localapp.local.ui.chat.ItinerarySlot
import live.localapp.local.ui.chat.TravelInfo

// ── Claude Messages API DTOs ──

@Serializable
data class ClaudeMessage(
    val role: String,   // "user" or "assistant"
    val content: JsonElement, // Can be a string or array of content blocks
) {
    companion object {
        fun text(role: String, text: String): ClaudeMessage = ClaudeMessage(
            role = role,
            content = JsonPrimitive(text),
        )

        fun blocks(role: String, blocks: JsonArray): ClaudeMessage = ClaudeMessage(
            role = role,
            content = blocks,
        )
    }
}

@Serializable
data class ClaudeResponse(
    val id: String = "",
    val content: List<ClaudeContentBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
)

@Serializable
data class ClaudeContentBlock(
    val type: String = "",
    val text: String? = null,
    val id: String? = null,         // tool_use block ID
    val name: String? = null,       // tool name
    val input: JsonObject? = null,  // tool input
)

// ── Streaming event types ──

sealed interface StreamEvent {
    /** A chunk of text from a content_block_delta */
    data class TextDelta(val text: String) : StreamEvent
    /** A tool_use block started — accumulate input JSON via InputDelta */
    data class ToolUseStart(val index: Int, val id: String, val name: String) : StreamEvent
    /** A chunk of tool input JSON */
    data class InputDelta(val partialJson: String) : StreamEvent
    /** The stream completed — carries the assembled ClaudeResponse */
    data class Done(val response: ClaudeResponse) : StreamEvent
}

// ── Structured AI response envelope (legacy, kept for backward compat) ──

@Serializable
data class AiChip(
    val label: String,
    val action: String? = null,
)

@Serializable
data class AiResponseEnvelope(
    val type: String,                              // "chat" | "plan" | "plan_update"
    val text: String? = null,
    val intro: String? = null,
    val days: List<AiItineraryDay>? = null,
    val day: AiItineraryDay? = null,
    @SerialName("plan_title") val planTitle: String? = null,
    @SerialName("attendee_count") val attendeeCount: Int? = null,
    val chips: List<AiChip>? = null,
    @SerialName("day_index") val dayIndex: Int? = null,
    @SerialName("approve_day") val approveDay: Int? = null,
)

@Serializable
data class AiItineraryDay(
    val label: String,
    @SerialName("short_name") val shortName: String,
    val slots: List<AiItinerarySlot>,
    val travel: List<AiTravelInfo> = emptyList(),
)

@Serializable
data class AiItinerarySlot(
    val time: String,
    val emoji: String,
    val name: String,
    val detail: String,
    val price: String,
    @SerialName("is_free") val isFree: Boolean = false,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("is_custom") val isCustom: Boolean = false,
)

@Serializable
data class AiTravelInfo(
    val mode: String,
    val mins: Int,
)

// ── Natural language parsed response (Phase 1 architecture) ──

enum class ParsedResponseType {
    CHAT, PLAN, PLAN_UPDATE, APPROVE
}

data class EventRef(
    val id: String,
    val time: String,
    val note: String,
    val isVenue: Boolean,
)

data class ParsedDay(
    val label: String,
    val events: List<EventRef>,
)

data class ParsedChatResponse(
    val type: ParsedResponseType,
    val text: String,
    val planTitle: String? = null,
    val attendeeCount: Int? = null,
    val days: List<ParsedDay> = emptyList(),
    val chips: List<String> = emptyList(),
    val dayIndex: Int? = null,
)

// ── Mappers to domain models ──

private var aiIdCounter = 0L
private fun nextId(): String = "ai_${++aiIdCounter}"

fun AiItinerarySlot.toDomain() = ItinerarySlot(
    id = nextId(), time = time, emoji = emoji,
    name = name, detail = detail, price = price,
    isFree = isFree, imageUrl = imageUrl, eventId = eventId, isCustom = isCustom,
)

fun AiTravelInfo.toDomain() = TravelInfo(mode, mins)

fun AiItineraryDay.toDomain() = ItineraryDay(
    label = label, shortName = shortName,
    slots = slots.map { it.toDomain() },
    travel = travel.map { it.toDomain() },
)

// ── Tool definitions for Claude ──

data class ToolCall(
    val id: String,
    val name: String,
    val input: JsonObject,
)

val claudeTools: JsonArray = buildJsonArray {
    addJsonObject {
        put("name", "search_events")
        put("description", "Search for upcoming events and activities. Use this when the user asks about things to do, events, activities, concerts, shows, etc.")
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("location") {
                    put("type", "string")
                    put("description", "City or neighborhood name (e.g. 'Concord', 'Boston', 'Cambridge')")
                }
                putJsonObject("category") {
                    put("type", "string")
                    put("description", "Event category: music, sports, arts, theatre, comedy, food, outdoors, museums, tours, libraries, for-kids")
                }
                putJsonObject("date_range") {
                    put("type", "string")
                    put("description", "Time filter: today, this_weekend, next_weekend, this_week, next_week, this_month. Use 'next_weekend' for the FOLLOWING weekend, 'this_weekend' for the CURRENT/upcoming weekend. For specific dates, use start_date/end_date instead.")
                }
                putJsonObject("start_date") {
                    put("type", "string")
                    put("description", "Start date in ISO format (YYYY-MM-DD). Use this for specific date searches instead of date_range.")
                }
                putJsonObject("end_date") {
                    put("type", "string")
                    put("description", "End date in ISO format (YYYY-MM-DD). Use with start_date for date range searches.")
                }
                putJsonObject("keyword") {
                    put("type", "string")
                    put("description", "Search keyword for event title or venue name")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max results to return (default 20)")
                }
            }
        }
    }
    addJsonObject {
        put("name", "search_venues")
        put("description", "Search for venues, restaurants, bars, and places. Use this when the user asks about places to go, venues, restaurants, etc.")
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("location") {
                    put("type", "string")
                    put("description", "City or neighborhood name")
                }
                putJsonObject("category") {
                    put("type", "string")
                    put("description", "Venue category: music_venue, theater, museum, library, comedy_club, sports_venue, bar, brewery, restaurant, arts_center")
                }
                putJsonObject("keyword") {
                    put("type", "string")
                    put("description", "Search keyword for venue name")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max results to return (default 20)")
                }
            }
        }
    }
    addJsonObject {
        put("name", "get_event_details")
        put("description", "Get full details of a specific event including description, pricing, and ticket URL")
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("event_id") {
                    put("type", "string")
                    put("description", "The event ID")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("event_id")) }
        }
    }
    addJsonObject {
        put("name", "recommend_and_save_venue")
        put("description", "Recommend a specific restaurant, bar, cafe, or venue that you know about. Use this when the user needs a dining/drinking spot and search_venues doesn't have a good match. You provide the venue name and location, and the system will validate it via Google Places, save it to the database, and return the details for use in the plan. Only use this for real, existing establishments — not made-up ones.")
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "The venue name (e.g. 'Myers + Chang', 'The Salty Pig')")
                }
                putJsonObject("city") {
                    put("type", "string")
                    put("description", "The city (e.g. 'Boston', 'Cambridge')")
                }
                putJsonObject("cuisine_or_type") {
                    put("type", "string")
                    put("description", "Type of venue (e.g. 'Thai restaurant', 'cocktail bar', 'brewery', 'Italian')")
                }
                putJsonObject("price_level") {
                    put("type", "string")
                    put("description", "Price level: $ (under $15), $$ ($15-30), $$$ ($30-60), $$$$ ($60-100), $$$$$ ($100+)")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("name"))
                add(JsonPrimitive("city"))
            }
        }
    }
    addJsonObject {
        put("name", "present_plan")
        put("description", "Display a plan card to the user with organized event/venue slots. Call this when you have enough information to present a concrete plan. Your text response will appear as a conversational message above the plan card.")
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "Short plan title (e.g. 'Date Night in Boston', 'Weekend in Cambridge')")
                }
                putJsonObject("days") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("label") {
                                put("type", "string")
                                put("description", "Day label (e.g. 'Saturday, April 5')")
                            }
                            putJsonObject("slots") {
                                put("type", "array")
                                putJsonObject("items") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("event_id") {
                                            put("type", "string")
                                            put("description", "Event ID from search_events results. ALWAYS use this when an event exists — even if it's at a venue you recommended. Never use venue_name for events that have an event_id in search results.")
                                        }
                                        putJsonObject("venue_name") {
                                            put("type", "string")
                                            put("description", "Venue name from recommend_and_save_venue. ONLY use this for restaurants, bars, and cafes where there is no specific event — just a dining/drinks recommendation. Never use this for music venues, theaters, or any place where a specific event is happening.")
                                        }
                                        putJsonObject("time") {
                                            put("type", "string")
                                            put("description", "Time slot (e.g. '7 PM', '6:30 PM')")
                                        }
                                        putJsonObject("note") {
                                            put("type", "string")
                                            put("description", "Brief reason for picking this (1 sentence)")
                                        }
                                    }
                                    putJsonArray("required") {
                                        add(JsonPrimitive("time"))
                                        add(JsonPrimitive("note"))
                                    }
                                }
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("label"))
                            add(JsonPrimitive("slots"))
                        }
                    }
                }
                putJsonObject("attendee_count") {
                    put("type", "integer")
                    put("description", "Number of people attending. Infer from context: 'date night'=2, 'double date'=4, 'just me'=1. If unknown, you MUST ask before presenting the plan.")
                }
                putJsonObject("chips") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "2-3 short follow-up suggestions for the user (e.g. 'Add drinks after', 'Make it a full day', 'Swap to Saturday')")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("title"))
                add(JsonPrimitive("days"))
                add(JsonPrimitive("attendee_count"))
            }
        }
    }
    addJsonObject {
        put("name", "update_plan")
        put("description", "Update a specific day in the user's existing plan. Use when the user wants to swap, add, or remove events from a day.")
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("day_index") {
                    put("type", "integer")
                    put("description", "Zero-based index of the day to update")
                }
                putJsonObject("label") {
                    put("type", "string")
                    put("description", "Day label (e.g. 'Saturday, April 5')")
                }
                putJsonObject("slots") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("event_id") {
                                put("type", "string")
                                put("description", "Event ID from search_events results")
                            }
                            putJsonObject("venue_name") {
                                put("type", "string")
                                put("description", "Venue name from recommend_and_save_venue")
                            }
                            putJsonObject("time") {
                                put("type", "string")
                                put("description", "Time slot (e.g. '7 PM')")
                            }
                            putJsonObject("note") {
                                put("type", "string")
                                put("description", "Brief reason for picking this")
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("time"))
                            add(JsonPrimitive("note"))
                        }
                    }
                }
                putJsonObject("chips") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "2-3 short follow-up suggestions for the user")
                }
                putJsonObject("attendee_count") {
                    put("type", "integer")
                    put("description", "Updated number of people attending. Include this whenever the user changes the group size at any point in the conversation, even if no slots are changing on this turn.")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("day_index"))
                add(JsonPrimitive("label"))
                add(JsonPrimitive("slots"))
            }
        }
    }
}

/**
 * Build a tools array with the optional Anthropic-managed web tools appended.
 * web_search and web_fetch are server-side tools — Anthropic executes them and
 * returns the results in the response. We just gate them by including/omitting
 * the tool definitions per turn based on the bo-web-budget edge function.
 *
 * Caps:
 *   - max_uses on web_search caps Anthropic's invocations within ONE assistant
 *     turn (defense in depth alongside our own per-user/hour/day counters).
 *   - max_uses on web_fetch caps direct URL fetches per turn.
 *
 * No allowed_domains list — we want long-tail coverage. Verifier-side rules
 * (tiered domain rubric, 2 independent sources) are where hallucination
 * defense lives, not at the search layer.
 */
fun toolsWithWeb(
    base: JsonArray = claudeTools,
    allowWebSearch: Boolean,
    allowWebFetch: Boolean,
    webSearchMaxUses: Int = 3,
    webFetchMaxUses: Int = 5,
): JsonArray = buildJsonArray {
    base.forEach { add(it) }
    if (allowWebSearch) {
        addJsonObject {
            put("type", "web_search_20250305")
            put("name", "web_search")
            put("max_uses", webSearchMaxUses)
        }
    }
    if (allowWebFetch) {
        addJsonObject {
            put("type", "web_fetch_20250910")
            put("name", "web_fetch")
            put("max_uses", webFetchMaxUses)
        }
    }
}

// ── Helper builder functions for JSON DSL ──

private inline fun kotlinx.serialization.json.JsonArrayBuilder.addJsonObject(
    block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
) {
    add(buildJsonObject(block))
}

private inline fun kotlinx.serialization.json.JsonObjectBuilder.putJsonObject(
    key: String,
    block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
) {
    put(key, buildJsonObject(block))
}

private inline fun kotlinx.serialization.json.JsonObjectBuilder.putJsonArray(
    key: String,
    block: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit,
) {
    put(key, buildJsonArray(block))
}

// ── Service ──

class ClaudeApiService {

    companion object {
        /** Fast model for conversational turns (clarifying questions, greetings) */
        const val MODEL_CHAT = "claude-haiku-4-5-20251001"
        /** Full model for plan building, event search, and tool use */
        const val MODEL_PLAN = "claude-sonnet-4-5-20250929"
    }

    private val apiUrl = "https://api.anthropic.com/v1/messages"

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 180_000
        }
    }

    /**
     * Send a message to Claude via the Supabase Edge Function proxy.
     * Returns the raw ClaudeResponse so callers can inspect tool_use blocks.
     */
    suspend fun sendRaw(
        systemPrompt: String,
        messages: List<ClaudeMessage>,
        tools: JsonArray? = null,
        model: String = MODEL_PLAN,
    ): ClaudeResponse {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 16384)
            put("system", systemPrompt)
            put("messages", json.encodeToJsonElement(
                kotlinx.serialization.serializer<List<ClaudeMessage>>(),
                messages,
            ))
            if (tools != null && tools.isNotEmpty()) {
                put("tools", tools)
            }
        }

        val httpResponse = client.post(apiUrl) {
            header("x-api-key", Config.CLAUDE_API_KEY)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        val rawBody = httpResponse.bodyAsText()
        println("Claude proxy status=${httpResponse.status.value} body=${rawBody.take(500)}")

        if (httpResponse.status.value !in 200..299) {
            throw RuntimeException("Proxy error ${httpResponse.status.value}: $rawBody")
        }

        return json.decodeFromString<ClaudeResponse>(rawBody)
    }

    /**
     * Send a streaming request to Claude. Returns a Flow that emits:
     * - TextDelta: each chunk of text as it arrives
     * - ToolUseStart: when Claude begins a tool call
     * - InputDelta: chunks of tool input JSON
     * - Done: final assembled ClaudeResponse when stream completes
     */
    fun sendRawStreaming(
        systemPrompt: String,
        messages: List<ClaudeMessage>,
        tools: JsonArray? = null,
        model: String = MODEL_PLAN,
    ): Flow<StreamEvent> = callbackFlow {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 16384)
            put("stream", true)
            put("system", systemPrompt)
            put("messages", json.encodeToJsonElement(
                kotlinx.serialization.serializer<List<ClaudeMessage>>(),
                messages,
            ))
            if (tools != null && tools.isNotEmpty()) {
                put("tools", tools)
            }
        }

        val statement = client.preparePost(apiUrl) {
            header("x-api-key", Config.CLAUDE_API_KEY)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        statement.execute { httpResponse ->
            if (httpResponse.status.value !in 200..299) {
                val errorBody = httpResponse.bodyAsText()
                throw RuntimeException("Claude streaming error ${httpResponse.status.value}: $errorBody")
            }

            val channel: ByteReadChannel = httpResponse.bodyAsChannel()

            // Accumulators for building the final ClaudeResponse
            val contentBlocks = mutableListOf<ClaudeContentBlock>()
            val inputBuffers = mutableMapOf<Int, StringBuilder>() // index -> partial JSON
            var stopReason: String? = null
            var responseId = ""
            var currentTextBuffer = StringBuilder()
            var currentTextIndex = -1

            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data.isEmpty() || data == "[DONE]") continue

                try {
                    val event = json.parseToJsonElement(data).jsonObject
                    val type = event["type"]?.jsonPrimitive?.content ?: continue

                    when (type) {
                        "message_start" -> {
                            val message = event["message"]?.jsonObject
                            responseId = message?.get("id")?.jsonPrimitive?.content ?: ""
                        }

                        "content_block_start" -> {
                            val index = event["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
                            val block = event["content_block"]?.jsonObject ?: continue
                            val blockType = block["type"]?.jsonPrimitive?.content ?: continue

                            when (blockType) {
                                "text" -> {
                                    // Flush previous text block if any
                                    if (currentTextIndex >= 0) {
                                        while (contentBlocks.size <= currentTextIndex) contentBlocks.add(ClaudeContentBlock())
                                        contentBlocks[currentTextIndex] = ClaudeContentBlock(type = "text", text = currentTextBuffer.toString())
                                    }
                                    currentTextIndex = index
                                    currentTextBuffer = StringBuilder()
                                }
                                "tool_use" -> {
                                    val id = block["id"]?.jsonPrimitive?.content ?: ""
                                    val name = block["name"]?.jsonPrimitive?.content ?: ""
                                    inputBuffers[index] = StringBuilder()
                                    // Ensure contentBlocks list is big enough
                                    while (contentBlocks.size <= index) contentBlocks.add(ClaudeContentBlock())
                                    contentBlocks[index] = ClaudeContentBlock(type = "tool_use", id = id, name = name)
                                    trySend(StreamEvent.ToolUseStart(index, id, name))
                                }
                                // Anthropic server-side tools (web_search_20250305, web_fetch_20250910)
                                // emit blocks of type server_tool_use (the model's request) and
                                // web_search_tool_result / web_fetch_tool_result (Anthropic's reply
                                // injected into the same response). We track the request blocks so
                                // tallyWebCalls can count them; results are accepted and skipped —
                                // the model already integrated them into its text by the Done event.
                                "server_tool_use" -> {
                                    val id = block["id"]?.jsonPrimitive?.content ?: ""
                                    val name = block["name"]?.jsonPrimitive?.content ?: ""
                                    inputBuffers[index] = StringBuilder()
                                    while (contentBlocks.size <= index) contentBlocks.add(ClaudeContentBlock())
                                    contentBlocks[index] = ClaudeContentBlock(type = "server_tool_use", id = id, name = name)
                                    // Emit ToolUseStart so the chat UI commits any in-progress
                                    // streaming text and shows the searching indicator. Without
                                    // this, the partial text Bo wrote before invoking web_search
                                    // gets clobbered by the post-search final response.
                                    trySend(StreamEvent.ToolUseStart(index, id, name))
                                }
                                "web_search_tool_result", "web_fetch_tool_result" -> {
                                    while (contentBlocks.size <= index) contentBlocks.add(ClaudeContentBlock())
                                    contentBlocks[index] = ClaudeContentBlock(type = blockType)
                                }
                            }
                        }

                        "content_block_delta" -> {
                            val index = event["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
                            val delta = event["delta"]?.jsonObject ?: continue
                            val deltaType = delta["type"]?.jsonPrimitive?.content ?: continue

                            when (deltaType) {
                                "text_delta" -> {
                                    val text = delta["text"]?.jsonPrimitive?.content ?: continue
                                    currentTextBuffer.append(text)
                                    trySend(StreamEvent.TextDelta(text))
                                }
                                "input_json_delta" -> {
                                    val partial = delta["partial_json"]?.jsonPrimitive?.content ?: continue
                                    inputBuffers[index]?.append(partial)
                                    trySend(StreamEvent.InputDelta(partial))
                                }
                            }
                        }

                        "content_block_stop" -> {
                            val index = event["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
                            // Finalize text block
                            if (index == currentTextIndex) {
                                while (contentBlocks.size <= index) contentBlocks.add(ClaudeContentBlock())
                                contentBlocks[index] = ClaudeContentBlock(type = "text", text = currentTextBuffer.toString())
                                currentTextIndex = -1
                            }
                            // Finalize tool_use / server_tool_use blocks — parse accumulated JSON input
                            val inputJson = inputBuffers[index]?.toString()
                            if (inputJson != null) {
                                val existing = contentBlocks.getOrNull(index)
                                if (existing != null && (existing.type == "tool_use" || existing.type == "server_tool_use")) {
                                    val parsedInput = try {
                                        json.parseToJsonElement(inputJson).jsonObject
                                    } catch (_: Exception) {
                                        JsonObject(emptyMap())
                                    }
                                    contentBlocks[index] = existing.copy(input = parsedInput)
                                }
                                inputBuffers.remove(index)
                            }
                        }

                        "message_delta" -> {
                            stopReason = event["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.content
                        }

                        "message_stop" -> {
                            // Stream complete
                        }
                    }
                } catch (e: Exception) {
                    println("SSE parse error: ${e.message} for data: ${data.take(200)}")
                }
            }

            // Build final ClaudeResponse
            val response = ClaudeResponse(
                id = responseId,
                content = contentBlocks.filter { it.type.isNotEmpty() },
                stopReason = stopReason,
            )
            trySend(StreamEvent.Done(response))
        }

        close()
        awaitClose()
    }

    /**
     * Extract tool calls from a Claude response.
     */
    fun extractToolCalls(response: ClaudeResponse): List<ToolCall> {
        return response.content
            .filter { it.type == "tool_use" }
            .mapNotNull { block ->
                val id = block.id ?: return@mapNotNull null
                val name = block.name ?: return@mapNotNull null
                val input = block.input ?: JsonObject(emptyMap())
                ToolCall(id = id, name = name, input = input)
            }
    }

    /**
     * Extract the final user-facing text from a Claude response.
     *
     * Anthropic's server-side tools (web_search, web_fetch) split a turn into
     * multiple text blocks: text-before-search ("I'll check the weather…"),
     * server_tool_use, tool_result, text-after-search (the actual answer).
     *
     * We return only the LAST text block — the post-search answer is what
     * matters as Bo's persisted reply. The preamble lives transiently in
     * streamingText during the search phase, then disappears when the real
     * answer renders. Joining both produced duplicated "I'll check…" prefix
     * stuck on top of the answer in the saved message.
     *
     * For non-search responses (only one text block), last == first.
     */
    fun extractText(response: ClaudeResponse): String? {
        return response.content
            .filter { it.type == "text" }
            .mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }
            .lastOrNull()
    }

    /**
     * Build the assistant message content blocks for conversation history.
     * Preserves text + tool_use, plus server_tool_use and the corresponding
     * tool_result blocks so subsequent rounds see Bo's web-search activity
     * with full fidelity.
     */
    fun buildAssistantContentBlocks(response: ClaudeResponse): JsonArray {
        return buildJsonArray {
            for (block in response.content) {
                addJsonObject {
                    put("type", block.type)
                    when (block.type) {
                        "text" -> put("text", block.text ?: "")
                        "tool_use", "server_tool_use" -> {
                            put("id", block.id ?: "")
                            put("name", block.name ?: "")
                            put("input", block.input ?: JsonObject(emptyMap()))
                        }
                        // web_search_tool_result / web_fetch_tool_result blocks
                        // are passed through type-only — Anthropic sends them
                        // back attached server-side; we don't need to recreate
                        // their content for our history (it'd bloat the prompt).
                    }
                }
            }
        }
    }

    /**
     * Build a tool_result message to send back to Claude.
     */
    fun buildToolResultMessage(toolCallId: String, result: String): ClaudeMessage {
        val blocks = buildJsonArray {
            addJsonObject {
                put("type", "tool_result")
                put("tool_use_id", toolCallId)
                put("content", result)
            }
        }
        return ClaudeMessage.blocks("user", blocks)
    }

    private fun cleanJson(raw: String): String {
        // Claude sometimes returns text before/after the JSON block,
        // or wraps it in ```json ... ``` fences. Extract just the JSON.
        val fencePattern = Regex("```(?:json)?\\s*\\n?(\\{[\\s\\S]*\\})\\s*\\n?```")
        val match = fencePattern.find(raw)
        if (match != null) return match.groupValues[1].trim()

        // No fences — find the first { to last }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) return raw.substring(start, end + 1).trim()

        return raw.trim()
    }

    /**
     * Parse a text response as a structured AiResponseEnvelope.
     */
    fun parseStructured(rawText: String): AiResponseEnvelope {
        return json.decodeFromString<AiResponseEnvelope>(cleanJson(rawText))
    }

    /**
     * Parse Claude's natural language text response.
     * Plans now come from present_plan/update_plan tool calls, not text markers.
     * This just returns the text as a CHAT response.
     */
    fun parseNaturalResponse(text: String, userMessage: String = ""): ParsedChatResponse {
        return ParsedChatResponse(
            type = ParsedResponseType.CHAT,
            text = text.trim(),
        )
    }
}
