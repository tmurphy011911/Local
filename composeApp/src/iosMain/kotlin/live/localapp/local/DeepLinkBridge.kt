package live.localapp.local

object DeepLinkBridge {
    private var pendingPath: String? = null
    private var callback: ((String) -> Unit)? = null

    fun registerCallback(cb: (String) -> Unit) {
        callback = cb
        pendingPath?.let {
            pendingPath = null
            cb.invoke(it)
        }
    }

    /** Called from Swift when a Universal Link arrives. Path includes prefix, e.g. /p/{token}, /e/{id}, /v/{id}. */
    fun receivePath(path: String) {
        val cb = callback
        if (cb != null) cb.invoke(path) else pendingPath = path
    }
}
