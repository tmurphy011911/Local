package live.localapp.local.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    // Reading the pasteboard string is a SYNCHRONOUS XPC to the pasteboard
    // daemon that can stall for seconds (first launch / large clipboard / privacy
    // subsystem). This runs on Dispatchers.Main (DeepLinkController's scope), so
    // doing it inline froze the main thread > 2s and the watchdog killed the app
    // (Fatal App Hang). Hop off-main for just the blocking read.
    val clip = withContext(Dispatchers.Default) { pasteboard.string } ?: return null
    if (!clip.contains("localapp.live/")) return null
    val regex = Regex("localapp\\.live(/[pev]/[A-Za-z0-9_-]+)")
    val match = regex.find(clip) ?: return null
    return match.groupValues[1]
}

// Android-only mechanism; iOS uses the clipboard path above.
actual suspend fun checkInstallReferrerForDeepLink(): String? = null

actual fun clearClipboardDeepLink() {
    // Same synchronous-pasteboard hazard as the read in checkClipboardForDeepLink:
    // both the read and the write can stall on the pasteboard daemon. This is
    // called from DeepLinkController's Main scope, so dispatch the access onto a
    // global queue to keep it off the main thread.
    platform.darwin.dispatch_async(platform.darwin.dispatch_get_global_queue(0L, 0UL)) {
        val clip = UIPasteboard.generalPasteboard.string ?: return@dispatch_async
        if (clip.contains("localapp.live/")) {
            UIPasteboard.generalPasteboard.string = ""
        }
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

actual fun identifyRevenueCatUser(appUserId: String, onResult: (Boolean, String?) -> Unit) {
    live.localapp.local.RevenueCatBridge.logIn?.invoke(appUserId, onResult) ?: onResult(false, null)
}

actual fun logOutRevenueCatUser() {
    live.localapp.local.RevenueCatBridge.logOut?.invoke()
}

actual fun identifyPushUser(appUserId: String) {
    live.localapp.local.PushBridge.login?.invoke(appUserId)
}

actual fun clearPushUser() {
    live.localapp.local.PushBridge.logout?.invoke()
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

actual fun openWriteReview() {
    // ?action=write-review opens the App Store straight to the review composer.
    val url = platform.Foundation.NSURL(string = "https://apps.apple.com/app/local/id6761425705?action=write-review")!!
    platform.UIKit.UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any>()) { _ -> }
}

actual fun openAppSettings() {
    val url = platform.Foundation.NSURL(string = platform.UIKit.UIApplicationOpenSettingsURLString)!!
    platform.UIKit.UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any>()) { _ -> }
}

actual fun composeSupportEmail(recipient: String) {
    val url = platform.Foundation.NSURL(string = "mailto:$recipient") ?: return
    platform.UIKit.UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any>()) { _ -> }
}

actual fun areNotificationsEnabled(onResult: (Boolean) -> Unit) {
    val center = platform.UserNotifications.UNUserNotificationCenter.currentNotificationCenter()
    center.getNotificationSettingsWithCompletionHandler { settings ->
        val status = settings?.authorizationStatus
        val enabled = status == platform.UserNotifications.UNAuthorizationStatusAuthorized ||
            status == platform.UserNotifications.UNAuthorizationStatusProvisional ||
            status == platform.UserNotifications.UNAuthorizationStatusEphemeral
        platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) {
            onResult(enabled)
        }
    }
}

actual fun requestNotificationPermission(onResult: (Boolean) -> Unit) {
    // Prefer OneSignal's prompt (Swift, via the bridge) so a grant registers the
    // device for remote notifications and mints an APNs/OneSignal token. Fall
    // back to raw UNUserNotificationCenter only if the bridge isn't wired yet.
    val viaOneSignal = live.localapp.local.PushBridge.requestPermission
    if (viaOneSignal != null) {
        viaOneSignal(onResult)
        return
    }
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

actual fun flushPersistentStorage() {
    // Force NSUserDefaults (the auth session's backing store) to write to disk
    // synchronously, so a force-kill right after logout can't lose the session
    // deletion. synchronize() is deprecated but remains the correct escape hatch
    // for "must be on disk before an imminent kill."
    NSUserDefaults.standardUserDefaults.synchronize()
}
