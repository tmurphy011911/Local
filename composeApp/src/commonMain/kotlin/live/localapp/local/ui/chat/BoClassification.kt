package live.localapp.local.ui.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsed result of the reasoning-layer classifier (spec §15.1) — built by
 * [parse] from the `classify` tool's JSON input (which
 * [live.localapp.local.data.remote.ClaudeApiService.classifyTurn] returns raw,
 * keeping the data layer free of ui types).
 *
 * [target] is DERIVED from [intent], never asked of the model — the Phase-0 eval
 * showed asking for it was redundant and error-prone (jumped target accuracy from
 * 64% to parity with intent accuracy).
 */
data class BoClassification(
    val intent: BoRoutedIntent,
    val confidence: Double,
    val params: JsonObject?,
    val modify: JsonObject?,
    val secondaryIntent: BoRoutedIntent?,
    val rationale: String?,
) {
    val target: BoRouteTarget get() = intent.defaultTarget

    /**
     * Whether the router should FORCE this classification's surface. Below the
     * threshold it falls through to today's inline routing (spec §5.1 tier 3) —
     * the core "never worse than today" safety property. Phase-0 calibration:
     * correct answers averaged 0.90, wrong 0.45, so a mid threshold cleanly
     * separates most errors. Provisional — tune as the eval set grows.
     */
    fun isConfident(threshold: Double = CONFIDENCE_THRESHOLD): Boolean = confidence >= threshold

    companion object {
        const val CONFIDENCE_THRESHOLD = 0.6

        /**
         * Parse the `classify` tool input. Returns null (→ caller falls through to
         * default routing) when the input is missing or the intent is unknown.
         */
        fun parse(input: JsonObject?): BoClassification? {
            input ?: return null
            val intent = BoRoutedIntent.fromWire(input["intent"]?.jsonPrimitive?.contentOrNull) ?: return null
            return BoClassification(
                intent = intent,
                confidence = input["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                params = input["params"] as? JsonObject,
                modify = input["modify"] as? JsonObject,
                secondaryIntent = BoRoutedIntent.fromWire(input["secondary_intent"]?.jsonPrimitive?.contentOrNull),
                rationale = input["rationale"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }
}
