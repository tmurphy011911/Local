package live.localapp.local.data.bo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The reasoning layer's **classifier** (spec `docs/specs/bo-reasoning-layer.md`
 * §5.1 tier 2, §15.1). A single-purpose, persona-free prompt whose only job is to
 * map one Bo turn to a [live.localapp.local.ui.chat.BoRoutedIntent] (+ params +
 * confidence), emitted through the forced [TOOL_NAME] tool so the output is
 * schema-validated by the API rather than parsed from prose. The NEW/CURRENT
 * target is DERIVED from the intent (see [BoRoutedIntent.defaultTarget]) — the
 * eval showed asking the model for it was redundant and error-prone.
 *
 * Deliberately NOT the 300-line main system prompt — reliability comes from one
 * job and a tiny, cacheable prompt. Runs on the same Haiku model as the main
 * turn, as a separate proxied call.
 *
 * KEEP IN SYNC: the `intent` enum below mirrors [BoRoutedIntent.wireName]; the
 * decision rules mirror spec §5.3 (tiebreak), §5.6 (compound), §15.3
 * (plan precondition). The labeled eval set is `bo-reasoning-eval-seed.md`.
 */
object BoClassifierPrompt {

    const val TOOL_NAME = "classify"

    /**
     * The classifier system prompt. [surfaceStateJson] is the compact snapshot of
     * what's currently on screen (spec §5.3) — pass "none" when no surface is open.
     * Recent conversation turns are supplied as the message history of the call,
     * not inlined here.
     */
    fun systemPrompt(surfaceStateJson: String): String = """
You are an intent classifier for a local events & venues chat assistant ("Bo").
Your ONLY job: read the user's latest turn (with recent context) and call the
`classify` tool exactly once. Do not answer the user. Do not call any other tool.

INTENTS (pick the single best `intent`):
- events_search  — a fresh request for things to do / events / activities. ("live music this week", "free stuff tonight")
- events_modify  — a change to the event list ALREADY on screen. ("only free", "cheaper", "drop the outdoor ones", "show more", "sort by soonest", "what about next weekend")
- venue_single   — about ONE named venue. ("what's going on at Fenway?", "tell me about the Sinclair")
- event_single   — about ONE specific event. ("is the Celtics game sold out?", "when does the show start")
- quick_fact     — a short factual question about a place/event. ("how much is the aquarium?", "is the ICA open Mondays?")
- info           — general local info / opinion with no single subject. ("what's new in town?", "what's the vibe in Somerville?")
- plan_build     — build a NEW itinerary/plan. ("plan my Saturday", "date night plan")
- plan_modify    — change the plan ALREADY on screen. ("swap the 7pm", "add Fenway", "move dinner earlier")
- out_of_scope   — not about local events/venues/experiences. ("write me a poem", "weather in Tokyo", sports scores)
- clarify        — genuinely underspecified AND not answerable by presenting a sensible default. ("what should I do", bare "help")

CURRENT SURFACE (what's on screen right now):
$surfaceStateJson

DECISION RULES:
- Fresh-vs-modify (only when a compatible surface is open): a DELTA phrase
  (only/cheaper/drop/sooner/more/more like/swap/move/add/less) → *_modify on the
  current surface. A phrase that names a NEW subject → fresh search, even with a
  surface open. ("just jazz" with a rock tray open = events_modify; "live jazz" = events_search.)
- A follow-up like "what about Sunday" is events_modify when a tray is open, else events_search.
- Prefer PRESENTING over asking: "surprise me" / "I'm bored" / "what should I do" → events_search (not clarify).
  Only use clarify when there is truly nothing to default to (e.g. a bare "help").
- SCOPE vs sports: a ticketed game at a local venue ("is the Celtics game sold out?", "Bruins tickets") IS a
  local event → event_single / events_search, NOT out_of_scope. out_of_scope is only for topics with no
  local event/venue/place angle at all (weather elsewhere, taxes, poems, general trivia, "who won the game").
- INFO vs events: general "feel of a place" questions — "what's new in town", "what's the vibe in {area}",
  "what should I know about {neighborhood}" — are info (no single subject, no concrete search). Reserve
  events_search for a concrete ask for things to do.
- QUICK_FACT vs venue: a question about a venue's hours / price / closing time ("what time does the MFA close",
  "how much is the aquarium") is quick_fact even though it names a venue. Use venue_single for
  "tell me about / what's going on at {venue}".
- Plan precondition (plan_build): if there are no seed picks on screen AND no day/date
  AND no vibe/occasion in the turn, still classify plan_build but set confidence LOW —
  the app decides whether to ask. Location is usually known from device; don't treat a
  missing city as a blocker on its own.
- Compound: if the turn clearly contains two asks, set the primary as `intent` and the
  other as `secondary_intent` (e.g. "what's at Fenway this weekend" → venue_single + events_search).

CONFIDENCE: set `confidence` in [0,1] honestly. Use < 0.5 when the turn is ambiguous or
you are guessing — the app falls back to its default handling on low confidence, so a low
score is safe and correct when you are unsure. Never inflate it.

Fill `params` only with slots the turn actually states; leave the rest null.
Put a <=120 char `rationale` explaining the pick (for logs; the user never sees it).
""".trim()

    /**
     * The `classify` tool schema (spec §15.1). `tool_choice` is forced to this tool
     * on the classifier call. The `intent` enum mirrors [BoRoutedIntent.wireName].
     */
    fun classifyTool(): JsonObject = buildJsonObject {
        put("name", TOOL_NAME)
        put("description", "Classify the user's latest turn into a routing intent. Call exactly once.")
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonArray("required") {
                add("intent"); add("confidence")
            }
            putJsonObject("properties") {
                putJsonObject("intent") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("events_search"); add("events_modify"); add("venue_single")
                        add("event_single"); add("quick_fact"); add("info")
                        add("plan_build"); add("plan_modify"); add("out_of_scope"); add("clarify")
                    }
                    put("description", "The single best routing intent for this turn. The router derives the NEW/CURRENT target from this.")
                }
                putJsonObject("confidence") {
                    put("type", "number")
                    put("description", "0..1. Below ~0.5 the app falls back to default handling, so score honestly — low is safe when unsure.")
                }
                putJsonObject("params") {
                    put("type", "object")
                    put("description", "Only slots the turn actually states; leave others out.")
                    putJsonObject("properties") {
                        putJsonObject("location") { put("type", "string") }
                        putJsonObject("category") { put("type", "string") }
                        putJsonObject("keyword") { put("type", "string") }
                        putJsonObject("time_window") {
                            put("type", "string")
                            putJsonArray("enum") {
                                add("today"); add("tonight"); add("this_weekend"); add("next_weekend")
                                add("this_week"); add("next_week"); add("this_month")
                            }
                        }
                        putJsonObject("start_date") { put("type", "string"); put("description", "YYYY-MM-DD") }
                        putJsonObject("end_date") { put("type", "string"); put("description", "YYYY-MM-DD") }
                        putJsonObject("is_free") { put("type", "boolean") }
                        putJsonObject("price_ceiling") { put("type", "string") }
                        putJsonObject("venue_ref") { put("type", "string"); put("description", "Named venue (venue_single).") }
                        putJsonObject("event_ref") { put("type", "string"); put("description", "Named/referenced event (event_single, quick_fact).") }
                        putJsonObject("occasion") { put("type", "string"); put("description", "e.g. 'date night', 'with kids' — feeds plan vibe.") }
                    }
                }
                putJsonObject("modify") {
                    put("type", "object")
                    put("description", "Present only for events_modify / plan_modify.")
                    putJsonObject("properties") {
                        putJsonObject("operation") {
                            put("type", "string")
                            putJsonArray("enum") {
                                add("FILTER"); add("EXPAND"); add("SORT"); add("RESET")
                                add("ADD"); add("REMOVE"); add("RETIME"); add("SWAP")
                            }
                        }
                        putJsonObject("delta") { put("type", "object"); put("description", "Field-level changes, same keys as params.") }
                        putJsonObject("execution_hint") {
                            put("type", "string")
                            putJsonArray("enum") { add("LOCAL"); add("RESEARCH") }
                            put("description", "Guess only; the app overrides if the delta needs data the open surface lacks.")
                        }
                    }
                }
                putJsonObject("secondary_intent") {
                    put("type", "string")
                    put("description", "For compound turns — the second ask, same enum as intent. Omit if single-intent.")
                }
                putJsonObject("rationale") {
                    put("type", "string")
                    put("description", "<=120 chars, why this pick. Logged for eval/shadow; never shown to the user.")
                }
            }
        }
    }
}
