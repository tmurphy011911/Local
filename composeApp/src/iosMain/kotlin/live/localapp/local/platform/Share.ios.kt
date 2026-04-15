package live.localapp.local.platform

import live.localapp.local.ShareBridge
import platform.UIKit.UIPasteboard

actual fun presentShareSheet(text: String, url: String) {
    ShareBridge.presentShareSheet?.invoke(text, url)
}

actual fun checkClipboardForDeepLink(): String? {
    val clip = UIPasteboard.generalPasteboard.string ?: return null
    if (!clip.contains("localapp.live/")) return null
    // Extract path: /p/token, /e/id, /v/id
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

actual fun setDeepLinkCallback(onPlanToken: (String) -> Unit) {
    live.localapp.local.DeepLinkBridge.onPlanShareTokenReceived = onPlanToken
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
    // TODO: replace with real App Store ID
    val url = platform.Foundation.NSURL(string = "https://apps.apple.com/app/local/id0000000000")!!
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
