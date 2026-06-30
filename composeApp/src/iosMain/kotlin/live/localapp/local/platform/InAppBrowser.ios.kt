package live.localapp.local.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberInAppBrowser(): InAppBrowser = remember {
    InAppBrowser { url -> InAppBrowserBridge.open?.invoke(url) }
}

/**
 * Set once at app start by `InAppBrowserBridgeSetup.register()` (Swift) to
 * present an SFSafariViewController from the top view controller. See
 * iosApp/iosApp/InAppBrowserBridge.swift.
 */
object InAppBrowserBridge {
    var open: ((url: String) -> Unit)? = null
}
