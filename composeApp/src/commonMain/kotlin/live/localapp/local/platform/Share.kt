package live.localapp.local.platform

/** Present the platform's native share sheet with text and a URL. */
expect fun presentShareSheet(text: String, url: String)

/** Check clipboard for a localapp.live deep link and return the path, or null. */
expect fun checkClipboardForDeepLink(): String?

/** Clear any deep link from the clipboard after handling. */
expect fun clearClipboardDeepLink()

/** Register a callback for when a Universal Link deep link arrives while app is running. */
expect fun setDeepLinkCallback(onPlanToken: (String) -> Unit)
