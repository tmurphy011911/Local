package live.localapp.local

object DeepLinkBridge {
    /** Swift calls this when a Universal Link with /p/{token} is opened. */
    var onPlanShareTokenReceived: ((String) -> Unit)? = null
}
