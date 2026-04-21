package live.localapp.local

object DeepLinkBridge {
    private var pendingToken: String? = null
    private var callback: ((String) -> Unit)? = null

    /**
     * Register a callback for Universal Link /p/{token} deliveries.
     * If a token was already received before the callback was set (e.g. cold-start
     * race where onOpenURL fires before Compose mounts), it's delivered immediately.
     */
    fun registerCallback(cb: (String) -> Unit) {
        callback = cb
        pendingToken?.let {
            pendingToken = null
            cb.invoke(it)
        }
    }

    /** Called from Swift when a Universal Link arrives. */
    fun receiveToken(token: String) {
        val cb = callback
        if (cb != null) cb.invoke(token) else pendingToken = token
    }

    /**
     * Legacy Swift setter — Swift still writes directly to this property.
     * Delegates to registerCallback so pending-token buffering works.
     */
    var onPlanShareTokenReceived: ((String) -> Unit)?
        get() = callback
        set(value) { if (value != null) registerCallback(value) else callback = null }
}
