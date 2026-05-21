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
    CHAT, PLAN, PLAN_UPDATE, APPROVE,
    // Phase 6 — structured-response tools.
    SUGGESTIONS, EVENT_BRIEF, VENUE_BRIEF, QUICK_FACT, SCOPE_REDIRECT
}

data class EventRef(
    val id: String,
    val time: String,
    val note: String,
    val isVenue: Boolean,
    /** True when [id] is a bo_web_events row id (Bo found via web_search). */
    val isBoWeb: Boolean = false,
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
    /**
     * Phase 6 — raw JSON of the structured-response tool input, when the
     * terminal tool is one of present_suggestions / present_event_brief /
     * present_venue_brief / present_quick_fact / present_scope_redirect.
     * The view-model converts this to the corresponding domain model.
     */
    val structuredJson: kotlinx.serialization.json.JsonElement? = null,
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
        put("description", "Search for upcoming events and activities. Use this when the user asks about things to do, events, activities, concerts, shows, etc. Returns JSON: { events: [{id, title, venue_name, venue_city, date, iso_date, time, category, subcategory, is_free, price}], coverage: {returned, total_in_scope, radius_miles, location?, strict?, total_in_radius?}, has_more?, widen_available?, guidance? }. Read events[*].id directly — never invent IDs.")
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("location") {
                    put("type", "string")
                    put("description", "City or neighborhood name (e.g. 'Concord', 'Boston', 'Cambridge')")
                }
                putJsonObject("strict_location") {
                    put("type", "boolean")
                    put("description", "DEFAULTS TO TRUE when `location` is set. Strict mode means only events whose venue_city matches `location` are returned. Pass FALSE explicitly when the user said 'near {place}' / 'around {place}' / 'close to {place}' to widen to a 20-mile radius around the location. For 'in {place}' / bare placename / no modifier, leave it at the default (true) or pass true.")
                }
                putJsonObject("category") {
                    put("type", "string")
                    put("description", "Event category: music, sports, arts, theatre, comedy, food, outdoors, museums, tours, libraries, for-kids")
                }
                putJsonObject("date_range") {
                    put("type", "string")
                    put("description", "Time filter for relative ranges only: today, this_weekend, next_weekend, this_week, next_week, this_month. 'next_weekend' = the weekend AFTER this one. For ANY specific calendar date or weekday name, use start_date + end_date instead.")
                }
                putJsonObject("start_date") {
                    put("type", "string")
                    put("description", "Start date in ISO format (YYYY-MM-DD). REQUIRED whenever the user names a specific date or weekday. Pair with end_date — for a single-day search, set end_date to the SAME value as start_date.")
                }
                putJsonObject("end_date") {
                    put("type", "string")
                    put("description", "End date in ISO format (YYYY-MM-DD). REQUIRED whenever start_date is set. For a single-day search, pass the SAME date as start_date — do not omit it. Without end_date, the search degrades to a wide window and the named date may be culled by rank-based truncation.")
                }
                putJsonObject("keyword") {
                    put("type", "string")
                    put("description", "Free-text search across the title, venue name, description, and category fields. Use this whenever the user names a specific venue ('what's on at Sundancer's?', 'anything at Trader Ed's?') or activity ('dance', 'jazz', 'trivia') — the keyword is matched broadly, so a generic music event whose description says 'DJ, dancing under the stars' WILL match keyword='dance'. Always try a venue-name keyword search before claiming the DB has no schedule for that venue.")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max results to return. OMIT this field for browse/discovery queries — the server returns a stratified cross-section (~60-150 events) that the response shaping then groups by date and category. Only pass an explicit limit when you genuinely want a small slice (e.g. limit=5 for a single-category 'best 5 jazz shows' followup). Default behavior (no limit) returns the full pool.")
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
                                            put("description", "Event ID from search_events results, OR a bo_web_events.id returned by save_web_finding. ALWAYS use this when an event exists.")
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
                                        putJsonObject("is_bo_web") {
                                            put("type", "boolean")
                                            put("description", "Set true ONLY when event_id is a bo_web_events.id returned by save_web_finding (i.e. you sourced it via web_search). Default false. The UI uses this to show a 'Bo found this — verifying' badge.")
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
                    put("description", "Optional. Only set this if the user volunteered the group size in chat ('date night'=2, 'double date'=4, 'just me'=1, '4 of us', etc.). Do NOT ask for it — the user sets group size from the plan detail screen. Omit when not stated.")
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
                                put("description", "Event ID from search_events results OR a bo_web_events.id from save_web_finding")
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
                            putJsonObject("is_bo_web") {
                                put("type", "boolean")
                                put("description", "Set true when event_id is a bo_web_events.id from save_web_finding.")
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

    // ── Structured-response tools (Phase 6) ──
    // These replace the prior "describe events as paragraph text" pattern.
    // Bo MUST use exactly one of these whenever surfacing events, venues, or
    // off-topic redirects to the user. Paragraph-list event descriptions are
    // forbidden by the system prompt — every event becomes a real object with
    // its event_id carried through, eliminating the "user retypes which one
    // they meant" failure that broke plan-build.

    addJsonObject {
        put("name", "present_suggestions")
        put("description", "Display a structured tray of event candidates for the user to browse and add to a draft plan. Use this WHENEVER the user is browsing events or considering options — never describe events as paragraph text or numbered lists. The tray is the only way the user can pick events explicitly. Each event must come from search_events results — never invent event_ids. If search_events returns nothing relevant, you may still call this with an empty groups array and footer.actions to let the user broaden the search with one tap. QUANTITY: when the user is BROWSING BROADLY (a date + no narrow filter, 'what's happening tonight', 'things to do May 15'), include 6-12 events per group across 4-8 groups — the user explicitly asked for a wide cross-section, give it to them. When the user named a NARROW filter ('jazz this week', 'free outdoor events'), 3-5 per group is right. Default to MORE rather than fewer when search_events returned 30+ rows.")
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("headline") {
                    put("type", "string")
                    put("description", "1-line title for the tray (e.g. 'Saturday, May 25', 'Arts events this week', 'Free tonight')")
                }
                putJsonObject("groupBy") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add(JsonPrimitive("day"))
                        add(JsonPrimitive("category"))
                        add(JsonPrimitive("none"))
                    }
                    put("description", "How to group events. 'day' when user asked about a date, 'category' when user asked about a type of thing, 'none' when results are too small to group.")
                }
                putJsonObject("groups") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("label") {
                                put("type", "string")
                                put("description", "Group header (category name when groupBy=day, date label when groupBy=category, empty when groupBy=none)")
                            }
                            putJsonObject("events") {
                                put("type", "array")
                                putJsonObject("items") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("event_id") {
                                            put("type", "string")
                                            put("description", "Real event_id from search_events. Never invent. Strip any 'evt:' prefix.")
                                        }
                                        putJsonObject("boRationale") {
                                            put("type", "string")
                                            put("description", "One short line (≤60 chars) explaining why this event fits the user's request. Not the event title — additional context.")
                                        }
                                    }
                                    putJsonArray("required") {
                                        add(JsonPrimitive("event_id"))
                                    }
                                }
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("label"))
                            add(JsonPrimitive("events"))
                        }
                    }
                }
                putJsonObject("footer") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("text") {
                            put("type", "string")
                            put("description", "Optional one-line prompt above the chips (e.g. 'Want more?')")
                        }
                        putJsonObject("actions") {
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "string")
                                putJsonArray("enum") {
                                    add(JsonPrimitive("search_wider"))
                                    add(JsonPrimitive("different_category"))
                                    add(JsonPrimitive("late_night"))
                                    add(JsonPrimitive("different_day"))
                                    add(JsonPrimitive("more_results"))
                                }
                            }
                        }
                    }
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("headline"))
                add(JsonPrimitive("groupBy"))
                add(JsonPrimitive("groups"))
            }
        }
    }

    addJsonObject {
        put("name", "present_event_brief")
        put("description", "Display a single event with a one-line answer to the user's question. Use when the user asks about a specific event by name (e.g. 'what time is Springsteen?', 'how much for LCD tickets?'). The answerLine should directly address the question.")
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("event_id") {
                    put("type", "string")
                    put("description", "Real event_id from search_events. Strip 'evt:' prefix.")
                }
                putJsonObject("answerLine") {
                    put("type", "string")
                    put("description", "Direct answer to the user's question (≤80 chars). E.g. 'Doors at 8 PM, show at 9.' or '$65+ for general admission, $120 for premium.'")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("event_id"))
                add(JsonPrimitive("answerLine"))
            }
        }
    }

    addJsonObject {
        put("name", "present_venue_brief")
        put("description", "Display a single venue with optional list of upcoming events. Use when the user asks about a specific place (e.g. 'what's at TD Garden?', 'when does the ICA close?').")
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("venue_name") {
                    put("type", "string")
                    put("description", "Venue name from search_venues result")
                }
                putJsonObject("answerLine") {
                    put("type", "string")
                    put("description", "Optional one-line direct answer (e.g. 'Open until 10 PM tonight')")
                }
                putJsonObject("upcomingEventIds") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Up to 6 event_ids of upcoming events at this venue. Pulled from search_events filtered to this venue.")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("venue_name")) }
        }
    }

    addJsonObject {
        put("name", "present_quick_fact")
        put("description", "One-line factual answer with optional tap-target to an event. Use for very focused questions where a full card would be overkill (e.g. 'how late is open?', 'is it 21+?'). Limit to 100 chars.")
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "The answer text, ≤100 chars.")
                }
                putJsonObject("ctaEventId") {
                    put("type", "string")
                    put("description", "Optional event_id the fact is about — provides a tap target.")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("text")) }
        }
    }

    addJsonObject {
        put("name", "present_scope_redirect")
        put("description", "Tell the user this question is out of scope, then offer 1-3 redirect chips. Use when the topic is general knowledge (history, geography, definitions, biology, news, weather alone, sports scores, advice, anything not local-event-discovery). Do NOT free-text answer the off-topic question — use this tool. Do NOT call web_search to research the topic.")
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("explanation") {
                    put("type", "string")
                    put("description", "One-sentence polite decline (≤120 chars). E.g. 'That's outside what I can help with — I'm here to plan local events.'")
                }
                putJsonObject("redirectChips") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("label") {
                                put("type", "string")
                                put("description", "Chip text (e.g. 'Ethiopian restaurants nearby', 'Something this weekend')")
                            }
                            putJsonObject("intent") {
                                put("type", "string")
                                put("description", "Pre-filled message Bo will receive when the user taps this chip")
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("label"))
                            add(JsonPrimitive("intent"))
                        }
                    }
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("explanation"))
                add(JsonPrimitive("redirectChips"))
            }
        }
    }
}

/**
 * save_web_finding tool — Bo calls this AFTER web_search returns a usable
 * event/venue. The dispatcher (ChatViewModel.executeToolCall /
 * PlanChatViewModel.executeToolCall) hits the save-bo-web-finding edge
 * function which writes to bo_web_events / bo_web_venues with state=
 * 'unverified' and returns the new uuid. Bo then uses that uuid as the
 * `event_id` in present_plan / update_plan slots, and sets `is_bo_web: true`
 * on the slot so the UI shows the "Bo found this — verifying" badge.
 *
 * Phase 3 will add a server-side verifier that flips state to 'verified' or
 * 'rejected' and (on verify) promotes the row into the canonical events /
 * venues tables. Until then rows just sit at unverified — they're visible
 * in the user's own plan but never surface in carousels/search/other users.
 */
val saveBoWebFindingTool: JsonObject = buildJsonObject {
    put("name", "save_web_finding")
    put("description", "Save an event or venue you found via web_search to the bo_web_events / bo_web_venues staging tables. Call this BEFORE present_plan/update_plan when you want to include a web-discovered item in a plan slot. Returns an id you must use as the slot's event_id (for events) along with is_bo_web: true. Also save the venue separately if it's not already in our database. Strict: start_at must be in the future for events.")
    putJsonObject("input_schema") {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("kind") {
                put("type", "string")
                put("description", "event | venue")
            }
            putJsonObject("title") {
                put("type", "string")
                put("description", "Event title (required when kind=event).")
            }
            putJsonObject("name") {
                put("type", "string")
                put("description", "Venue name (required when kind=venue).")
            }
            putJsonObject("start_at") {
                put("type", "string")
                put("description", "ISO 8601 datetime, e.g. '2026-05-08T19:00:00-04:00'. Required for events. Must be in the future.")
            }
            putJsonObject("end_at") { put("type", "string"); put("description", "ISO 8601 end datetime (optional)") }
            putJsonObject("description") { put("type", "string") }
            putJsonObject("category") { put("type", "string"); put("description", "music | comedy | arts | sports | food | community | family | outdoors | nightlife") }
            putJsonObject("ticket_url") { put("type", "string") }
            putJsonObject("source_url") { put("type", "string"); put("description", "The page you found this on.") }
            putJsonObject("image_url") { put("type", "string") }
            putJsonObject("is_free") { put("type", "boolean") }
            putJsonObject("price_min") { put("type", "number") }
            putJsonObject("price_max") { put("type", "number") }
            putJsonObject("is_recurring") { put("type", "boolean") }
            putJsonObject("recurrence_rule") { put("type", "string"); put("description", "Optional RFC 5545 RRULE string for recurring events.") }
            putJsonObject("venue_id") { put("type", "string"); put("description", "If you matched the event to an existing venue from search_venues, pass its id here.") }
            putJsonObject("bo_web_venue_id") { put("type", "string"); put("description", "If you previously saved a Bo-Web venue and want to link this event to it, pass that id.") }
            putJsonObject("venue_name") { put("type", "string"); put("description", "Inline venue name when not matched to an existing or saved Bo-Web venue.") }
            putJsonObject("venue_address") { put("type", "string") }
            putJsonObject("venue_city") { put("type", "string") }
            putJsonObject("venue_state") { put("type", "string"); put("description", "Two-letter state code") }
            putJsonObject("venue_lat") { put("type", "number") }
            putJsonObject("venue_lng") { put("type", "number") }
            putJsonObject("address") { put("type", "string"); put("description", "Venue street address (when kind=venue).") }
            putJsonObject("city") { put("type", "string"); put("description", "Venue city (when kind=venue).") }
            putJsonObject("state_code") { put("type", "string"); put("description", "Two-letter venue state code (when kind=venue).") }
            putJsonObject("lat") { put("type", "number") }
            putJsonObject("lng") { put("type", "number") }
            putJsonObject("website") { put("type", "string"); put("description", "Venue official website (when kind=venue).") }
            putJsonObject("phone") { put("type", "string") }
            putJsonObject("source_domains") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", "Domains your web_search corroborated this from. Pass at least 1.")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("kind"))
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
    // save_web_finding is a regular client-side tool — only include it when
    // web access is allowed (otherwise Bo has no way to find anything to save).
    if (allowWebSearch || allowWebFetch) {
        add(saveBoWebFindingTool)
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
        /** Tool-orchestration model — search loops, venue resolution, plan
         *  synthesis. Haiku 4.5 is dramatically faster than Sonnet (TTFT
         *  ~1-2s vs 3-5s) and more than capable for tool-call orchestration.
         *  Sonnet 4.5 was a 60s-on-3-day-plan latency bottleneck; Haiku
         *  brings that into the 15-25s range. If plan-quality regressions
         *  surface, the next step is to keep Haiku for non-terminal rounds
         *  but switch to Sonnet on the present_plan emit only. */
        const val MODEL_PLAN = "claude-haiku-4-5-20251001"
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
     * Wrap a string system prompt in the structured array form Anthropic
     * accepts, with `cache_control: ephemeral` on the (single) text block.
     * Anthropic caches the prefix for ~5 minutes; subsequent requests with
     * the same system prompt + tools hit the cache, dropping TTFT and input
     * cost by ~80-90%. Bo's system prompt is multi-thousand tokens and
     * rarely changes within a session, so caching is high-ROI here.
     * Min cacheable block size on Haiku is 2048 tokens — Bo's prompt
     * comfortably exceeds that.
     */
    private fun cacheableSystem(systemPrompt: String): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive(systemPrompt))
            put("cache_control", buildJsonObject { put("type", JsonPrimitive("ephemeral")) })
        })
    }

    /**
     * Add `cache_control: ephemeral` to the LAST tool definition. This
     * marks the entire tools array as part of the cached prefix — when
     * unchanged across requests, the model's tool-loading cost drops to
     * near zero. Tools rarely change within a session.
     */
    private fun cacheableTools(tools: JsonArray): JsonArray = buildJsonArray {
        if (tools.isEmpty()) return@buildJsonArray
        for (i in 0 until tools.size - 1) add(tools[i])
        val last = tools[tools.size - 1].jsonObject
        add(buildJsonObject {
            for ((k, v) in last) put(k, v)
            put("cache_control", buildJsonObject { put("type", JsonPrimitive("ephemeral")) })
        })
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
        toolChoice: JsonObject? = null,
    ): ClaudeResponse {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 16384)
            put("system", cacheableSystem(systemPrompt))
            put("messages", json.encodeToJsonElement(
                kotlinx.serialization.serializer<List<ClaudeMessage>>(),
                messages,
            ))
            if (tools != null && tools.isNotEmpty()) {
                put("tools", cacheableTools(tools))
                if (toolChoice != null) put("tool_choice", toolChoice)
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
        toolChoice: JsonObject? = null,
    ): Flow<StreamEvent> = callbackFlow {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 16384)
            put("stream", true)
            put("system", cacheableSystem(systemPrompt))
            put("messages", json.encodeToJsonElement(
                kotlinx.serialization.serializer<List<ClaudeMessage>>(),
                messages,
            ))
            if (tools != null && tools.isNotEmpty()) {
                put("tools", cacheableTools(tools))
                if (toolChoice != null) put("tool_choice", toolChoice)
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
        val raw = response.content
            .filter { it.type == "text" }
            .mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }
            .lastOrNull() ?: return null
        // Anthropic splits a single sentence into multiple text blocks
        // when a server_tool_use lands mid-sentence — the post-tool block
        // often starts with an orphan comma/period ("., which is after
        // your dates."). Strip leading punctuation + whitespace and
        // capitalize the first remaining letter so the persisted bubble
        // reads cleanly. If the model behaved (full sentence in last
        // block), this is a no-op.
        val trimmed = raw.trimStart()
        val cleaned = trimmed.replace(Regex("^[,.;:\\s]+"), "")
        return cleaned.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    /**
     * Build the assistant message content blocks for conversation history.
     * Preserves text + regular tool_use only. server_tool_use and
     * web_search_tool_result / web_fetch_tool_result blocks are STRIPPED —
     * Anthropic requires tool_use_id + full content on echoed tool_result
     * blocks, and reconstructing those is wasteful when Bo's synthesized
     * text already carries the answer. If a follow-up turn needs the data,
     * Bo will re-search; results are usually identical and cached.
     */
    fun buildAssistantContentBlocks(response: ClaudeResponse): JsonArray {
        return buildJsonArray {
            for (block in response.content) {
                when (block.type) {
                    "text" -> addJsonObject {
                        put("type", "text")
                        put("text", block.text ?: "")
                    }
                    "tool_use" -> addJsonObject {
                        put("type", "tool_use")
                        put("id", block.id ?: "")
                        put("name", block.name ?: "")
                        put("input", block.input ?: JsonObject(emptyMap()))
                    }
                    // server_tool_use, web_search_tool_result, web_fetch_tool_result
                    // → omitted from history. See note above.
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
