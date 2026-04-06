package live.localapp.local

object ShareBridge {
    /** Swift sets this. Compose calls it to present UIActivityViewController. */
    var presentShareSheet: ((text: String, url: String) -> Unit)? = null
}
