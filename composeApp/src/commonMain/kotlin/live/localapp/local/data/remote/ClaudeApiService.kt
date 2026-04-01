package live.localapp.local.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
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
    ): ClaudeResponse {
        val body = buildJsonObject {
            put("model", "claude-sonnet-4-5-20250929")
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
     * Extract the final text from a Claude response.
     */
    fun extractText(response: ClaudeResponse): String? {
        return response.content.firstOrNull { it.type == "text" }?.text
    }

    /**
     * Build the assistant message content blocks from a Claude response,
     * preserving both text and tool_use blocks for conversation history.
     */
    fun buildAssistantContentBlocks(response: ClaudeResponse): JsonArray {
        return buildJsonArray {
            for (block in response.content) {
                addJsonObject {
                    put("type", block.type)
                    if (block.type == "text") {
                        put("text", block.text ?: "")
                    } else if (block.type == "tool_use") {
                        put("id", block.id ?: "")
                        put("name", block.name ?: "")
                        put("input", block.input ?: JsonObject(emptyMap()))
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
     * Parse Claude's natural language response with embedded markers.
     *
     * Format:
     * PLAN: "Title"
     * DAY: Saturday, April 5
     * - [evt:abc123] at 6:30 PM — Note
     * - [venue:Name] at 8 PM — Note
     * CHIPS: chip1 | chip2
     *
     * Or for plan updates:
     * UPDATE_DAY: 0
     * DAY: Saturday, April 5
     * - [evt:abc123] at 6:30 PM — Note
     * CHIPS: chip1 | chip2
     */
    fun parseNaturalResponse(text: String, userMessage: String = ""): ParsedChatResponse {
        val lines = text.lines()

        // Detect type
        val type = when {
            lines.any { it.trimStart().startsWith("PLAN:") } -> ParsedResponseType.PLAN
            lines.any { it.trimStart().startsWith("UPDATE_DAY:") } -> ParsedResponseType.PLAN_UPDATE
            userMessage.lowercase().let { msg ->
                msg.contains("looks good") || msg.contains("approve") ||
                    msg.contains("save") || msg.contains("add to my plans")
            } -> ParsedResponseType.APPROVE
            else -> ParsedResponseType.CHAT
        }

        // Extract plan title
        val planTitle = lines.firstOrNull { it.trimStart().startsWith("PLAN:") }
            ?.substringAfter("PLAN:")?.trim()?.removeSurrounding("\"")

        // Extract day index for updates
        val dayIndex = lines.firstOrNull { it.trimStart().startsWith("UPDATE_DAY:") }
            ?.substringAfter("UPDATE_DAY:")?.trim()?.toIntOrNull()

        // Extract chips
        val chipsLine = lines.lastOrNull { it.trimStart().startsWith("CHIPS:") }
        val chips = chipsLine?.substringAfter("CHIPS:")?.split("|")
            ?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

        // Extract days with events
        val days = mutableListOf<ParsedDay>()
        var currentDayLabel: String? = null
        var currentEvents = mutableListOf<EventRef>()

        // Regex patterns for event/venue refs
        val evtPattern = Regex("""\[evt:([^\]]+)\]\s*at\s+([^—\n]+?)(?:\s*—\s*(.*))?$""")
        val venuePattern = Regex("""\[venue:([^\]]+)\]\s*at\s+([^—\n]+?)(?:\s*—\s*(.*))?$""")

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.startsWith("DAY:")) {
                // Flush previous day
                if (currentDayLabel != null && currentEvents.isNotEmpty()) {
                    days.add(ParsedDay(label = currentDayLabel!!, events = currentEvents.toList()))
                }
                currentDayLabel = trimmed.substringAfter("DAY:").trim()
                currentEvents = mutableListOf()
                continue
            }

            if (trimmed.startsWith("- [evt:") || trimmed.startsWith("- [venue:")) {
                val lineContent = trimmed.removePrefix("- ").trim()

                val evtMatch = evtPattern.find(lineContent)
                if (evtMatch != null) {
                    currentEvents.add(EventRef(
                        id = evtMatch.groupValues[1].trim(),
                        time = evtMatch.groupValues[2].trim(),
                        note = evtMatch.groupValues[3].trim(),
                        isVenue = false,
                    ))
                    continue
                }

                val venueMatch = venuePattern.find(lineContent)
                if (venueMatch != null) {
                    currentEvents.add(EventRef(
                        id = venueMatch.groupValues[1].trim(),
                        time = venueMatch.groupValues[2].trim(),
                        note = venueMatch.groupValues[3].trim(),
                        isVenue = true,
                    ))
                    continue
                }
            }
        }
        // Flush last day
        if (currentDayLabel != null && currentEvents.isNotEmpty()) {
            days.add(ParsedDay(label = currentDayLabel!!, events = currentEvents.toList()))
        }

        // Build the natural language text (everything that's not a marker line)
        val markerPrefixes = listOf("PLAN:", "DAY:", "UPDATE_DAY:", "CHIPS:", "- [evt:", "- [venue:")
        val textParts = lines.filter { line ->
            val trimmed = line.trim()
            markerPrefixes.none { trimmed.startsWith(it) } && trimmed.isNotBlank()
        }
        val naturalText = textParts.joinToString("\n").trim()

        return ParsedChatResponse(
            type = type,
            text = naturalText,
            planTitle = planTitle,
            days = days,
            chips = chips,
            dayIndex = dayIndex,
        )
    }
}
