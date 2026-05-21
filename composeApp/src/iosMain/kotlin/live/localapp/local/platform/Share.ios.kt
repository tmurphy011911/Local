package live.localapp.local.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import live.localapp.local.ShareBridge
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIPasteboard
import platform.UIKit.UIPasteboardDetectionPatternProbableWebURL

// One-shot gate: after first launch, never read the pasteboard again. The read
// is only useful for deferred deep linking (App Store install path), which by
// definition is first-launch-only. Universal Links route via DeepLinkBridge
// without touching the pasteboard. Bump the suffix to re-arm.
private const val CLIPBOARD_GATE_KEY = "clipboard_deeplink_checked_v1"

@OptIn(ExperimentalForeignApi::class)
actual fun presentShareSheet(text: String, url: String) {
    ShareBridge.presentShareSheet?.invoke(text, url)
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun checkClipboardForDeepLink(): String? {
    val defaults = NSUserDefaults.standardUserDefaults
    if (defaults.boolForKey(CLIPBOARD_GATE_KEY)) {
        println("DeepLink: clipboard gate set, skipping pasteboard read")
        return null
    }
    // Set the flag before reading so we can never re-prompt on this install
    // even if the read below crashes or the user dismisses.
    defaults.setBool(true, forKey = CLIPBOARD_GATE_KEY)

    val pasteboard = UIPasteboard.generalPasteboard
    val hasUrlPattern = suspendCoroutine<Boolean> { cont ->
        pasteboard.detectPatternsForPatterns(
            patterns = setOf(UIPasteboardDetectionPatternProbableWebURL),
            completionHandler = { found, _ ->
                cont.resume(found?.contains(UIPasteboardDetectionPatternProbableWebURL) == true)
            },
        )
    }
    if (!hasUrlPattern) return null

    val clip = pasteboard.string ?: return null
    if (!clip.contains("localapp.live/")) return null
    val regex = Regex("localapp\\.live(/[pev]/[A-Za-z0-9_-]+)")
    val match = regex.find(clip) ?: return null
    return match.groupValues[1]
}

actual fun clearClipboardDeepLink() {
    val clip = UIPasteboard.generalPasteboard.string ?: return
    if (clip.contains("localapp.live/")) {
        UIPasteboard.generalPasteboard.string = ""
    }
}

actual fun setDeepLinkCallback(onDeepLinkPath: (String) -> Unit) {
    live.localapp.local.DeepLinkBridge.registerCallback(onDeepLinkPath)
}

actual fun purchaseSubscription(isAnnual: Boolean, onResult: (Boolean) -> Unit) {
    live.localapp.local.RevenueCatBridge.purchase?.invoke(isAnnual, onResult) ?: onResult(false)
}

actual fun restorePurchases(onResult: (Boolean) -> Unit) {
    live.localapp.local.RevenueCatBridge.restorePurchases?.invoke(onResult) ?: onResult(false)
}

actual fun checkSubscriptionStatus(onResult: (Boolean, String?) -> Unit) {
    live.localapp.local.RevenueCatBridge.checkSubscriptionStatus?.invoke(onResult) ?: onResult(false, null)
}

actual fun fetchSubscriptionPricing(onResult: (String?, String?, String?) -> Unit) {
    live.localapp.local.RevenueCatBridge.fetchPricing?.invoke(onResult) ?: onResult(null, null, null)
}

actual fun requestAppReview() {
    platform.StoreKit.SKStoreReviewController.requestReview()
}

actual fun openAppStoreListing() {
    val url = platform.Foundation.NSURL(string = "https://apps.apple.com/app/local/id6761425705")!!
    platform.UIKit.UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any>()) { _ -> }
}

actual fun openAppSettings() {
    val url = platform.Foundation.NSURL(string = platform.UIKit.UIApplicationOpenSettingsURLString)!!
    platform.UIKit.UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any>()) { _ -> }
}

actual fun requestNotificationPermission(onResult: (Boolean) -> Unit) {
    val center = platform.UserNotifications.UNUserNotificationCenter.currentNotificationCenter()
    center.requestAuthorizationWithOptions(
        platform.UserNotifications.UNAuthorizationOptionAlert or
            platform.UserNotifications.UNAuthorizationOptionBadge or
            platform.UserNotifications.UNAuthorizationOptionSound,
    ) { granted, _ ->
        platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) {
            onResult(granted)
        }
    }
}
