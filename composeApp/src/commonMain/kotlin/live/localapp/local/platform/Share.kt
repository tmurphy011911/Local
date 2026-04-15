package live.localapp.local.platform

/** Present the platform's native share sheet with text and a URL. */
expect fun presentShareSheet(text: String, url: String)

/** Check clipboard for a localapp.live deep link and return the path, or null. */
expect fun checkClipboardForDeepLink(): String?

/** Clear any deep link from the clipboard after handling. */
expect fun clearClipboardDeepLink()

/** Register a callback for when a Universal Link deep link arrives while app is running. */
expect fun setDeepLinkCallback(onPlanToken: (String) -> Unit)

/** Purchase a subscription. isAnnual=true for annual. Callback receives success boolean. */
expect fun purchaseSubscription(isAnnual: Boolean, onResult: (Boolean) -> Unit)

/** Restore purchases. Callback receives whether pro is active. */
expect fun restorePurchases(onResult: (Boolean) -> Unit)

/** Check current subscription status. Callback receives (isPro, expiryDate?). */
expect fun checkSubscriptionStatus(onResult: (Boolean, String?) -> Unit)

/** Request OS-level push notification permission. Callback receives whether granted. */
expect fun requestNotificationPermission(onResult: (Boolean) -> Unit)

/** Open the OS settings page for this app (e.g. to enable location permission). */
expect fun openAppSettings()

/** Request the native App Store / Play Store review prompt. */
expect fun requestAppReview()
