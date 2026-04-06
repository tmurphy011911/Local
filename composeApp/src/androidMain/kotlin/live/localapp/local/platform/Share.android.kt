package live.localapp.local.platform

actual fun presentShareSheet(text: String, url: String) {
    // No-op on Android
}

actual fun checkClipboardForDeepLink(): String? = null

actual fun clearClipboardDeepLink() {}

actual fun setDeepLinkCallback(onPlanToken: (String) -> Unit) {}
