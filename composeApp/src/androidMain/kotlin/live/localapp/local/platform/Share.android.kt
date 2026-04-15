package live.localapp.local.platform

actual fun presentShareSheet(text: String, url: String) {
    val activity = live.localapp.local.ActivityProvider.activity ?: return
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, "$text\n$url")
    }
    activity.startActivity(android.content.Intent.createChooser(intent, "Share"))
}

actual fun checkClipboardForDeepLink(): String? {
    val activity = live.localapp.local.ActivityProvider.activity ?: return null
    val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return null
    val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: return null
    if (!clip.contains("localapp.live/")) return null
    val regex = Regex("localapp\\.live(/[pev]/[A-Za-z0-9_-]+)")
    return regex.find(clip)?.groupValues?.get(1)
}

actual fun clearClipboardDeepLink() {
    val activity = live.localapp.local.ActivityProvider.activity ?: return
    val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
    val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: return
    if (clip.contains("localapp.live/")) {
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
    }
}

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
actual fun openAppSettings() {
    val activity = live.localapp.local.ActivityProvider.activity ?: return
    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = android.net.Uri.fromParts("package", activity.packageName, null)
    }
    activity.startActivity(intent)
}
actual fun requestNotificationPermission(onResult: (Boolean) -> Unit) {
    val activity = live.localapp.local.ActivityProvider.activity
    if (activity == null) { onResult(false); return }
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            activity, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) { onResult(true); return }
        // Can't request from here without ActivityResult — just report current state
        onResult(false)
    } else {
        onResult(true) // Pre-Android 13 doesn't need runtime permission
    }
}
