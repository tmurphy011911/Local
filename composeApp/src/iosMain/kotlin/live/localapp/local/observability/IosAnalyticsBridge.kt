package live.localapp.local.observability

/**
 * Bridge between common Kotlin Analytics calls and the iOS native SDKs
 * (sentry-cocoa, posthog-ios). Swift fills in the closures from
 * `AnalyticsBridge.swift` during app startup, before [Analytics.initialize].
 *
 * Same pattern as ChatBridge / EventDetailBridge / DeepLinkBridge.
 */
object IosAnalyticsBridge {

    /** Swift sets. Kotlin calls with (env, appVersion, buildNumber, platform). */
    var initialize: ((String, String, String, String) -> Unit)? = null

    /** Swift sets. Kotlin calls with (userId, set, setOnce). */
    var identify: ((String, Map<String, Any?>, Map<String, Any?>) -> Unit)? = null

    /** Swift sets. Kotlin calls to clear identified user. */
    var reset: (() -> Unit)? = null

    /** Swift sets. Kotlin calls with (eventName, properties). */
    var capture: ((String, Map<String, Any?>) -> Unit)? = null

    /** Swift sets. Kotlin calls with (key, value). */
    var setUserProperty: ((String, Any?) -> Unit)? = null

    /** Swift sets. Kotlin calls with (errorDescription, message). Throwable is unwrapped on the Kotlin side. */
    var recordError: ((String, String?) -> Unit)? = null

    /** Swift sets. Kotlin calls with (category, message, data). */
    var breadcrumb: ((String, String, Map<String, Any?>) -> Unit)? = null

    /** Swift sets. Kotlin calls to force-flush both SDKs. */
    var flush: (() -> Unit)? = null
}

/**
 * Public, Swift-readable accessor for the observability keys.
 * Exposed in the ObjC framework header as `ObservabilityConfigKt.SENTRY_DSN_IOS`
 * etc. Swift uses these to start sentry-cocoa and posthog-ios SDKs before any
 * Kotlin code runs that could throw.
 */
@Suppress("unused") // referenced from Swift via the ComposeApp framework
object ObservabilityConfig {
    val SENTRY_DSN_IOS: String get() = BuildKeys.SENTRY_DSN_IOS
    val POSTHOG_KEY: String get() = BuildKeys.POSTHOG_KEY
    val POSTHOG_HOST: String get() = BuildKeys.POSTHOG_HOST
}
