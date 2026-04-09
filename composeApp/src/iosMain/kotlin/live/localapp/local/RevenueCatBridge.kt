package live.localapp.local

/**
 * Bridge for RevenueCat subscription management.
 * Swift sets the callbacks; Compose calls them.
 */
object RevenueCatBridge {
    /** Purchase a package. isAnnual=true for annual, false for monthly. Returns success. */
    var purchase: ((isAnnual: Boolean, onResult: (Boolean) -> Unit) -> Unit)? = null

    /** Restore purchases. Returns whether "pro" entitlement is active. */
    var restorePurchases: ((onResult: (Boolean) -> Unit) -> Unit)? = null

    /** Check current subscription status. Returns whether "pro" entitlement is active. */
    var checkSubscriptionStatus: ((onResult: (Boolean, String?) -> Unit) -> Unit)? = null
}
