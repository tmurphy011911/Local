package live.localapp.local.platform

actual fun presentShareSheet(text: String, url: String) {
    // No-op on Android
}

actual fun checkClipboardForDeepLink(): String? = null

actual fun clearClipboardDeepLink() {}

actual fun setDeepLinkCallback(onPlanToken: (String) -> Unit) {}

actual fun purchaseSubscription(isAnnual: Boolean, onResult: (Boolean) -> Unit) { onResult(false) }
actual fun restorePurchases(onResult: (Boolean) -> Unit) { onResult(false) }
actual fun checkSubscriptionStatus(onResult: (Boolean, String?) -> Unit) { onResult(false, null) }
actual fun requestAppReview() {
    val activity = live.localapp.local.ActivityProvider.activity ?: return
    val manager = com.google.android.play.core.review.ReviewManagerFactory.create(activity)
    manager.requestReviewFlow().addOnSuccessListener { reviewInfo ->
        manager.launchReviewFlow(activity, reviewInfo)
    }
}
actual fun openAppSettings() {}
actual fun requestNotificationPermission(onResult: (Boolean) -> Unit) { onResult(false) }
