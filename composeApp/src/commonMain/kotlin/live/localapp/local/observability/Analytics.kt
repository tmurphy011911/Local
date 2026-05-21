package live.localapp.local.observability

/**
 * Shared observability surface. One object on each platform delegates to:
 * - **Sentry** for crash/error reporting
 * - **PostHog** for product analytics
 *
 * Init order at app start: call [initialize] exactly once with the platform's
 * environment + app version super-properties before any track/identify call.
 * Anonymous events captured before [identify] auto-merge once the user signs
 * in.
 *
 * Never pass PII (email, name, phone) in event properties.
 */
expect object Analytics {

    /**
     * Boot both SDKs and seed PostHog super-properties. Safe to call multiple
     * times; only the first call has an effect.
     */
    fun initialize(
        environment: String,
        appVersion: String,
        buildNumber: String,
        platform: String,
    )

    /**
     * Bind anonymous events to a known user. Pass the Supabase `auth.users.id`.
     * `setOnce` fires once per property per user (signup_date, etc.).
     */
    fun identify(
        userId: String,
        set: Map<String, Any?> = emptyMap(),
        setOnce: Map<String, Any?> = emptyMap(),
    )

    /** Drop the identified user. Call on sign-out. */
    fun reset()

    /** Send an event to PostHog. */
    fun track(event: AnalyticsEvent)

    /** Update a single user property. */
    fun setUserProperty(key: String, value: Any?)

    /** Report a non-fatal error to Sentry. */
    fun recordError(throwable: Throwable, message: String? = null)

    /**
     * Add a Sentry breadcrumb. Use for "trail of context leading to a crash"
     * (route changes, key taps, network responses). PostHog events double as
     * breadcrumbs on Sentry-side via [track].
     */
    fun breadcrumb(category: String, message: String, data: Map<String, Any?> = emptyMap())

    /** Force a flush of pending PostHog + Sentry events. Useful before app death. */
    fun flush()
}
