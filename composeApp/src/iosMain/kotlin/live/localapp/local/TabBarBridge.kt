package live.localapp.local

object TabBarBridge {
    /** Swift sets this. Compose calls it to notify Swift of tab changes. */
    var onTabChanged: ((Int) -> Unit)? = null

    /** Compose sets this. Swift calls it when user taps a native tab. */
    var onTabSelected: ((Int) -> Unit)? = null

    /** Compose sets this. Swift calls it when user taps the AI FAB. */
    var onChatTapped: (() -> Unit)? = null

    /** Compose calls this to update the user initial on the profile tab. */
    var onUserInitialChanged: ((String) -> Unit)? = null
}
