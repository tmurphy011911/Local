package live.localapp.local.platform

import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import com.revenuecat.purchases.interfaces.ReceiveOfferingsCallback
import com.revenuecat.purchases.getOfferingsWith
import com.revenuecat.purchases.logInWith
import com.revenuecat.purchases.logOutWith
import com.revenuecat.purchases.purchaseWith
import com.revenuecat.purchases.restorePurchasesWith
import com.onesignal.OneSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

actual fun presentShareSheet(text: String, url: String) {
    val activity = live.localapp.local.ActivityProvider.activity ?: return
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, "$text\n$url")
    }
    // Attempt to attach the app icon for richer share previews (mirrors iOS UIActivityViewController).
    // Falls back to text-only share if FileProvider isn't configured or any step fails.
    try {
        val bitmap = android.graphics.BitmapFactory.decodeResource(
            activity.resources,
            live.localapp.local.R.mipmap.ic_launcher,
        )
        if (bitmap != null) {
            val cacheDir = java.io.File(activity.cacheDir, "share")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val iconFile = java.io.File(cacheDir, "app_icon.png")
            java.io.FileOutputStream(iconFile).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            val authority = "${activity.packageName}.fileprovider"
            val uri = androidx.core.content.FileProvider.getUriForFile(activity, authority, iconFile)
            intent.putExtra(android.content.Intent.EXTRA_STREAM, uri)
            intent.setType("image/*")
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } catch (t: Throwable) {
        // TODO: add FileProvider declaration to AndroidManifest.xml + res/xml/file_paths.xml
        // so we can attach the app icon to share previews like iOS does.
        println("Share: icon attachment failed, falling back to text-only share: $t")
        // Reset to text-only in case partial mutation occurred above.
        intent.removeExtra(android.content.Intent.EXTRA_STREAM)
        intent.type = "text/plain"
    }
    activity.startActivity(android.content.Intent.createChooser(intent, "Share"))
}

// One-shot gate; mirrors iOS. See Share.ios.kt for rationale. Android's clipboard
// read doesn't surface a system prompt, but the read is still only useful for
// deferred deep linking on first launch, so we gate for parity and to avoid
// repeated no-op work on every cold start.
private const val CLIPBOARD_GATE_KEY = "clipboard_deeplink_checked_v1"

actual suspend fun checkClipboardForDeepLink(): String? {
    val activity = live.localapp.local.ActivityProvider.activity ?: return null
    val prefs = activity.getSharedPreferences("local_app_prefs", android.content.Context.MODE_PRIVATE)
    if (prefs.getBoolean(CLIPBOARD_GATE_KEY, false)) return null
    prefs.edit().putBoolean(CLIPBOARD_GATE_KEY, true).apply()

    val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return null
    val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: return null
    if (!clip.contains("localapp.live/")) return null
    val regex = Regex("localapp\\.live(/[pev]/[A-Za-z0-9_-]+)")
    return regex.find(clip)?.groupValues?.get(1)
}

// One-shot gate, separate from the clipboard gate: the Play referrer string is
// returned on every launch, so without this we'd re-navigate to the shared plan
// on every cold start.
private const val INSTALL_REFERRER_GATE_KEY = "install_referrer_deeplink_checked_v1"

actual suspend fun checkInstallReferrerForDeepLink(): String? {
    val activity = live.localapp.local.ActivityProvider.activity ?: return null
    val prefs = activity.getSharedPreferences("local_app_prefs", android.content.Context.MODE_PRIVATE)
    if (prefs.getBoolean(INSTALL_REFERRER_GATE_KEY, false)) return null
    prefs.edit().putBoolean(INSTALL_REFERRER_GATE_KEY, true).apply()

    val referrer = try {
        readInstallReferrer(activity)
    } catch (t: Throwable) {
        println("InstallReferrer: read failed: $t")
        null
    } ?: return null

    // Acquisition attribution rides the same one-shot read: any Play link with
    // a `referrer` param passes utm_* pairs through (organic installs report
    // utm_source=google-play). Must happen before the dl= early-return below —
    // most campaign installs carry no deep link.
    val utmSource = referrer.split("&")
        .firstOrNull { it.startsWith("utm_source=") }
        ?.removePrefix("utm_source=")
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
    live.localapp.local.feature.attribution.SignupAttribution.record(utmSource, referrer)

    // Referrer carries our deep link as `dl=/p/{token}` (URL-decoded by Play).
    // Other utm_* pairs may be appended; split and find ours.
    val dl = referrer.split("&")
        .firstOrNull { it.startsWith("dl=") }
        ?.removePrefix("dl=")
        ?: return null
    return Regex("^(/[pev]/[A-Za-z0-9_-]+)$").find(dl)?.groupValues?.get(1)
}

private suspend fun readInstallReferrer(context: android.content.Context): String? =
    kotlin.coroutines.suspendCoroutine { cont ->
        val client = com.android.installreferrer.api.InstallReferrerClient.newBuilder(context).build()
        val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish(value: String?) {
            if (resumed.compareAndSet(false, true)) {
                try { client.endConnection() } catch (_: Throwable) {}
                cont.resumeWith(Result.success(value))
            }
        }
        client.startConnection(object : com.android.installreferrer.api.InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                if (responseCode == com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse.OK) {
                    finish(client.installReferrer.installReferrer)
                } else {
                    finish(null)
                }
            }
            override fun onInstallReferrerServiceDisconnected() {
                finish(null)
            }
        })
    }

actual fun clearClipboardDeepLink() {
    val activity = live.localapp.local.ActivityProvider.activity ?: return
    val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
    val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: return
    if (clip.contains("localapp.live/")) {
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
    }
}

actual fun setDeepLinkCallback(onDeepLinkPath: (String) -> Unit) {
    live.localapp.local.DeepLinkHandler.callback = onDeepLinkPath
}

actual fun purchaseSubscription(isAnnual: Boolean, onResult: (Boolean) -> Unit) {
    val activity = live.localapp.local.ActivityProvider.activity ?: run { onResult(false); return }
    Purchases.sharedInstance.getOfferingsWith(
        onError = { error -> println("RevenueCat: Offerings error: $error"); onResult(false) },
    ) { offerings ->
        val offering = offerings.current ?: run { onResult(false); return@getOfferingsWith }
        val pkg = if (isAnnual) {
            offering.annual ?: offering.availablePackages.firstOrNull { it.product.id == "local_pro_annual" }
        } else {
            offering.monthly ?: offering.availablePackages.firstOrNull { it.product.id == "local_pro_monthly" }
        }
        if (pkg == null) { onResult(false); return@getOfferingsWith }
        Purchases.sharedInstance.purchaseWith(
            PurchaseParams.Builder(activity, pkg).build(),
            onError = { error, _ -> println("RevenueCat: Purchase error: $error"); onResult(false) },
            onSuccess = { _, customerInfo ->
                val isPro = customerInfo.entitlements.active.containsKey("Local Pro")
                println("RevenueCat: Purchase complete, pro=$isPro")
                onResult(isPro)
            },
        )
    }
}

actual fun restorePurchases(onResult: (Boolean) -> Unit) {
    Purchases.sharedInstance.restorePurchasesWith(
        onError = { error -> println("RevenueCat: Restore error: $error"); onResult(false) },
        onSuccess = { customerInfo ->
            val isPro = customerInfo.entitlements.active.containsKey("Local Pro")
            println("RevenueCat: Restore complete, pro=$isPro")
            onResult(isPro)
        },
    )
}

actual fun checkSubscriptionStatus(onResult: (Boolean, String?) -> Unit) {
    Purchases.sharedInstance.getCustomerInfo(object : ReceiveCustomerInfoCallback {
        override fun onReceived(customerInfo: CustomerInfo) {
            val isPro = customerInfo.entitlements.active.containsKey("Local Pro")
            val expiry = customerInfo.entitlements.active["Local Pro"]?.expirationDate?.toString()
            println("RevenueCat: Status check, pro=$isPro, expiry=$expiry")
            onResult(isPro, expiry)
        }
        override fun onError(error: PurchasesError) {
            println("RevenueCat: Status error: $error")
            onResult(false, null)
        }
    })
}

actual fun identifyRevenueCatUser(appUserId: String, onResult: (Boolean, String?) -> Unit) {
    Purchases.sharedInstance.logInWith(
        appUserId,
        onError = { error -> println("RevenueCat: logIn error: $error"); onResult(false, null) },
        onSuccess = { customerInfo, _ ->
            val isPro = customerInfo.entitlements.active.containsKey("Local Pro")
            val expiry = customerInfo.entitlements.active["Local Pro"]?.expirationDate?.toString()
            println("RevenueCat: logIn complete user=$appUserId pro=$isPro expiry=$expiry")
            onResult(isPro, expiry)
        },
    )
}

actual fun logOutRevenueCatUser() {
    Purchases.sharedInstance.logOutWith(
        onError = { error -> println("RevenueCat: logOut error: $error") },
        onSuccess = { println("RevenueCat: logOut complete") },
    )
}

actual fun fetchSubscriptionPricing(onResult: (String?, String?, String?) -> Unit) {
    Purchases.sharedInstance.getOfferingsWith(
        onError = { _ -> onResult(null, null, null) },
    ) { offerings ->
        val offering = offerings.current ?: run { onResult(null, null, null); return@getOfferingsWith }
        val monthlyPkg = offering.monthly ?: offering.availablePackages.firstOrNull { it.product.id == "local_pro_monthly" }
        val annualPkg = offering.annual ?: offering.availablePackages.firstOrNull { it.product.id == "local_pro_annual" }
        val monthlyPrice = monthlyPkg?.product?.price?.formatted
        val annualPrice = annualPkg?.product?.price?.formatted
        // Android intro pricing
        val introPrice = monthlyPkg?.product?.subscriptionOptions
            ?.firstOrNull { it.freePhase != null || it.introPhase != null }
            ?.introPhase?.price?.formatted
        onResult(monthlyPrice, annualPrice, introPrice)
    }
}

actual fun requestAppReview() {
    val activity = live.localapp.local.ActivityProvider.activity ?: return
    val manager = com.google.android.play.core.review.ReviewManagerFactory.create(activity)
    manager.requestReviewFlow().addOnSuccessListener { reviewInfo ->
        manager.launchReviewFlow(activity, reviewInfo)
    }
}

actual fun openAppStoreListing() {
    val activity = live.localapp.local.ActivityProvider.activity ?: return
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=live.localapp.local"))
    activity.startActivity(intent)
}

actual fun openAppSettings() {
    val activity = live.localapp.local.ActivityProvider.activity ?: return
    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = android.net.Uri.fromParts("package", activity.packageName, null)
    }
    activity.startActivity(intent)
}

actual fun composeSupportEmail(recipient: String) {
    val activity = live.localapp.local.ActivityProvider.activity ?: return
    // ACTION_SENDTO with a mailto: URI targets only email apps (not the full share sheet).
    val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
        data = android.net.Uri.parse("mailto:$recipient")
    }
    try {
        activity.startActivity(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        // No email app installed — nothing to open.
    }
}

actual fun areNotificationsEnabled(onResult: (Boolean) -> Unit) {
    val activity = live.localapp.local.ActivityProvider.activity
    if (activity == null) { onResult(false); return }
    onResult(androidx.core.app.NotificationManagerCompat.from(activity).areNotificationsEnabled())
}

actual fun identifyPushUser(appUserId: String) {
    OneSignal.login(appUserId)
}

actual fun clearPushUser() {
    OneSignal.logout()
}

actual fun requestNotificationPermission(onResult: (Boolean) -> Unit) {
    // Route through OneSignal instead of the raw POST_NOTIFICATIONS request so
    // that granting also subscribes the device with OneSignal (registers a push
    // token) — a bare OS grant wouldn't. requestPermission() is a suspend fn
    // that shows the OS prompt and returns whether it was granted;
    // fallbackToSettings=true sends a previously-denied user to Settings.
    // OneSignal is initialized at launch (MainActivity), so it's ready here.
    CoroutineScope(Dispatchers.Main).launch {
        val granted = OneSignal.Notifications.requestPermission(true)
        onResult(granted)
    }
}

actual fun flushPersistentStorage() {
    // No-op: SharedPreferences apply()-writes are flushed by QueuedWork during
    // process teardown, so logout survives a swipe-kill without an explicit sync.
}
