package live.localapp.local

/**
 * Bridge for the OneSignal iOS SDK, which is Swift/ObjC and can't be called from
 * Kotlin/Native directly. The Swift side (ContentView.setupPushBridge) populates
 * these callbacks at startup once OneSignal is initialized; the iosMain `actual`
 * push functions invoke them. Until Swift wires them, they're null → the actuals
 * are safe no-ops. Mirrors [RevenueCatBridge].
 */
object PushBridge {
    /** Associate the OneSignal device with the authenticated user (external id = Supabase uid). */
    var login: ((appUserId: String) -> Unit)? = null

    /** Reset OneSignal to an anonymous device on sign-out. */
    var logout: (() -> Unit)? = null

    /**
     * Request notification permission THROUGH OneSignal (not raw
     * UNUserNotificationCenter) so a grant also registers the device for remote
     * notifications and yields an APNs/OneSignal token. Callback = granted.
     */
    var requestPermission: ((onResult: (Boolean) -> Unit) -> Unit)? = null
}
