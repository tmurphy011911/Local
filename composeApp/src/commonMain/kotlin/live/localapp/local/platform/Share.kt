package live.localapp.local.platform

/** Present the platform's native share sheet with text and a URL. */
expect fun presentShareSheet(text: String, url: String)

/** Check clipboard for a localapp.live deep link and return the path, or null.
 *  iOS implementation probes patterns silently before reading the string, so the
 *  system paste prompt only appears when the clipboard plausibly contains a URL. */
expect suspend fun checkClipboardForDeepLink(): String?

/** Clear any deep link from the clipboard after handling. */
expect fun clearClipboardDeepLink()

/** Check the Play Install Referrer for a deferred deep link path (e.g. /p/{token}),
 *  set when an uninstalled Android user opened a share link and installed from Play.
 *  One-shot: consumed on first launch only. Returns null on iOS. */
expect suspend fun checkInstallReferrerForDeepLink(): String?

/** Register a callback for when a Universal Link arrives. The callback receives the
 *  full path with prefix (e.g. /p/{token}, /e/{id}, /v/{id}); dispatch on prefix. */
expect fun setDeepLinkCallback(onDeepLinkPath: (String) -> Unit)

/** Purchase a subscription. isAnnual=true for annual. Callback receives success boolean. */
expect fun purchaseSubscription(isAnnual: Boolean, onResult: (Boolean) -> Unit)

/** Restore purchases. Callback receives whether pro is active. */
expect fun restorePurchases(onResult: (Boolean) -> Unit)

/** Check current subscription status. Callback receives (isPro, expiryDate?). */
expect fun checkSubscriptionStatus(onResult: (Boolean, String?) -> Unit)

/**
 * Bind RevenueCat's identity to the Supabase auth uid so the "Local Pro"
 * entitlement follows the *user* across devices/reinstalls instead of the
 * anonymous per-device id. Callback receives the now-identified user's
 * (isPro, expiryDate?) so the gate can refresh without a second round-trip.
 */
expect fun identifyRevenueCatUser(appUserId: String, onResult: (Boolean, String?) -> Unit)

/** Reset RevenueCat back to an anonymous id (call on sign-out). */
expect fun logOutRevenueCatUser()

/** Request OS-level push notification permission. Callback receives whether granted. */
expect fun requestNotificationPermission(onResult: (Boolean) -> Unit)

/** Query whether OS-level notifications are currently enabled for this app. */
expect fun areNotificationsEnabled(onResult: (Boolean) -> Unit)

/** Open the OS settings page for this app (e.g. to enable location permission). */
expect fun openAppSettings()

/** Open the user's email app with the given recipient pre-filled in the To field. */
expect fun composeSupportEmail(recipient: String)

/** Open the app's App Store / Play Store page for updating. */
expect fun openAppStoreListing()

/** Request the native App Store / Play Store review prompt. */
expect fun requestAppReview()

/** Fetch subscription pricing from RevenueCat. Returns monthly price, annual price, and intro price (if eligible). */
expect fun fetchSubscriptionPricing(onResult: (monthlyPrice: String?, annualPrice: String?, introPrice: String?) -> Unit)
