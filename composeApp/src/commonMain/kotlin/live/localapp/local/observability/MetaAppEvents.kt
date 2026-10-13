package live.localapp.local.observability

/**
 * Meta (Facebook) app events — install/registration attribution for Meta Ads.
 *
 * Deliberately kept OUT of [Analytics]: that object is the Sentry + PostHog
 * product-analytics surface, and this is an ad-network conversion pixel with a
 * fixed, Meta-defined event vocabulary. Mixing them would mean every PostHog
 * event silently became an ad-network event too.
 *
 * The SDK is only initialized when both the app id and the client token are
 * present (see the platform actuals). Until then every call here is a no-op, so
 * the app builds and runs on a clone with no `META_CLIENT_TOKEN` set.
 *
 * Never pass PII (email, name, phone) in any parameter.
 */
expect object MetaAppEvents {

    /**
     * Boot the SDK and log `fb_mobile_activate_app`.
     *
     * Call once at app start, after [Analytics.initialize] so any failure in
     * here is captured. Safe to call multiple times; only the first initializes.
     */
    fun activateApp()

    /**
     * Log `fb_mobile_complete_registration` — fired once, when a user finishes
     * creating an account (NOT on sign-in of an existing account).
     *
     * @param method how they registered, e.g. `"phone_otp"`. Maps to Meta's
     *   `fb_registration_method` parameter.
     */
    fun completeRegistration(method: String)

    /** True once the SDK has actually been initialized (both keys present). */
    val isEnabled: Boolean
}
