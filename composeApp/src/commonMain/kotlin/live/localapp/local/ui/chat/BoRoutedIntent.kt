package live.localapp.local.ui.chat

/**
 * The reasoning layer's classification of a single Bo turn — *what action the
 * user is actually asking for* — which the router maps deterministically to a
 * terminal tool / surface. This is distinct from [BoIntent], the coarse goal the
 * user picks from the sheet when Bo opens; [BoRoutedIntent] is computed per-turn.
 *
 * See `docs/specs/bo-reasoning-layer.md` §5.2 (taxonomy) and §15.1 (classifier
 * schema). The classifier emits [wireName] in its structured output; the router
 * reads [defaultTarget] and [terminalTool] to force `tool_choice`.
 *
 * NOTE: adding or renaming a case here changes the classifier's `input_schema`
 * (an enum constraint) and the labeled eval set (`bo-reasoning-eval-seed.md`) —
 * keep all three in sync.
 */
enum class BoRoutedIntent(
    val wireName: String,
    val defaultTarget: BoRouteTarget,
    /**
     * The terminal tool this intent routes to, or null when the intent produces
     * no structured surface: [INFO] → plain prose, [CLARIFY] → one question.
     */
    val terminalTool: String?,
) {
    /** Fresh events/activities query → new event tray. */
    EVENTS_SEARCH("events_search", BoRouteTarget.NEW, "present_suggestions"),

    /** Delta on the open tray ("only free", "drop outdoor", "more") → re-filter/re-search in place. */
    EVENTS_MODIFY("events_modify", BoRouteTarget.CURRENT, "present_suggestions"),

    /** "what's going on at fenway?" → single venue page (may embed its events). */
    VENUE_SINGLE("venue_single", BoRouteTarget.NEW, "present_venue_brief"),

    /** "is the celtics game sold out?" → single event brief. */
    EVENT_SINGLE("event_single", BoRouteTarget.NEW, "present_event_brief"),

    /** "how much is the aquarium?" → one-line fact pill. */
    QUICK_FACT("quick_fact", BoRouteTarget.NEW, "present_quick_fact"),

    /** "what's new in town?" → prose answer, no structured surface. */
    INFO("info", BoRouteTarget.NEW, null),

    /** "plan my saturday" → new multi-day plan card. */
    PLAN_BUILD("plan_build", BoRouteTarget.NEW, "present_plan"),

    /** "swap the 7pm" / "add fenway" → edit the open plan. */
    PLAN_MODIFY("plan_modify", BoRouteTarget.CURRENT, "update_plan"),

    /** "write me a poem" → decline + redirect chips. */
    OUT_OF_SCOPE("out_of_scope", BoRouteTarget.NEW, "present_scope_redirect"),

    /** Genuinely underspecified and not defaultable → ask one question (prefer presenting). */
    CLARIFY("clarify", BoRouteTarget.NA, null);

    /** Whether this intent operates on the currently-open surface rather than producing a new one. */
    val operatesOnCurrentSurface: Boolean get() = defaultTarget == BoRouteTarget.CURRENT

    companion object {
        /** Resolve a [BoRoutedIntent] from the classifier's [wireName]. Null for unknown/empty. */
        fun fromWire(name: String?): BoRoutedIntent? =
            entries.firstOrNull { it.wireName == name }
    }
}

/**
 * Whether a classified turn targets a brand-new surface or mutates the one that's
 * already on screen (spec §5.3). [NA] is for intents that render no surface
 * (e.g. [BoRoutedIntent.CLARIFY]).
 */
enum class BoRouteTarget(val wireName: String) {
    NEW("new"),
    CURRENT("current"),
    NA("na");

    companion object {
        fun fromWire(name: String?): BoRouteTarget =
            entries.firstOrNull { it.wireName == name } ?: NA
    }
}
