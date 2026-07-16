package live.localapp.local.ui.chat

/**
 * The user's chosen goal for a Bo session, picked from the intent sheet that
 * comes up when Bo opens. Drives (a) how Bo frames its results copy and
 * (b) whether saving an event auto-drafts a plan vs. waits for the user to
 * ask. See [ChatViewModel.setBoIntent].
 *
 * - [FIND_EVENTS]  — "Find events": present results, offer to build a plan
 *                    only if the user says so.
 * - [CREATE_PLAN]  — "Create a plan": today's behavior — saving events feeds
 *                    a plan Bo is actively drafting.
 * - [BROWSING]     — "I'm just browsing": present results, don't push planning.
 */
enum class BoIntent(val wireName: String) {
    FIND_EVENTS("find_events"),
    CREATE_PLAN("create_plan"),
    BROWSING("browsing");

    companion object {
        /** Resolve a [BoIntent] from its [wireName] (the token Swift/Android
         *  send back through the bridge). Null for unknown/empty. */
        fun fromWire(name: String?): BoIntent? =
            entries.firstOrNull { it.wireName == name }
    }
}
