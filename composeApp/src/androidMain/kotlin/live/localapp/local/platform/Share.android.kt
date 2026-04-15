package live.localapp.local.platform

import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import com.revenuecat.purchases.interfaces.ReceiveOfferingsCallback
import com.revenuecat.purchases.getOfferingsWith
import com.revenuecat.purchases.purchaseWith
import com.revenuecat.purchases.restorePurchasesWith

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

actual fun setDeepLinkCallback(onPlanToken: (String) -> Unit) {
    live.localapp.local.DeepLinkHandler.callback = onPlanToken
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

actual fun requestNotificationPermission(onResult: (Boolean) -> Unit) {
    val activity = live.localapp.local.ActivityProvider.activity
    if (activity == null) { onResult(false); return }
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            activity, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) { onResult(true); return }
        androidx.core.app.ActivityCompat.requestPermissions(
            activity, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001,
        )
        onResult(false)
    } else {
        onResult(true)
    }
}
