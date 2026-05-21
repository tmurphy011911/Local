package live.localapp.local.observability

/**
 * iOS Analytics impl. Forwards each call into the closures held in
 * [IosAnalyticsBridge], which Swift populates at app startup with the
 * sentry-cocoa + posthog-ios SDK calls.
 *
 * If a closure isn't set (e.g. tests, or Swift hasn't installed the bridge
 * yet) the call is a silent no-op rather than a crash.
 */
actual object Analytics {

    actual fun initialize(
        environment: String,
        appVersion: String,
        buildNumber: String,
        platform: String,
    ) {
        IosAnalyticsBridge.initialize?.invoke(environment, appVersion, buildNumber, platform)
    }

    actual fun identify(userId: String, set: Map<String, Any?>, setOnce: Map<String, Any?>) {
        IosAnalyticsBridge.identify?.invoke(userId, set, setOnce)
    }

    actual fun reset() {
        IosAnalyticsBridge.reset?.invoke()
    }

    actual fun track(event: AnalyticsEvent) {
        IosAnalyticsBridge.capture?.invoke(event.name, event.properties)
    }

    actual fun setUserProperty(key: String, value: Any?) {
        IosAnalyticsBridge.setUserProperty?.invoke(key, value)
    }

    actual fun recordError(throwable: Throwable, message: String?) {
        // Swift can't see a Kotlin Throwable's stack natively, so we flatten
        // to a description string. sentry-cocoa captures the Swift call site
        // as well, giving a hybrid stack on the dashboard.
        IosAnalyticsBridge.recordError?.invoke(
            "${throwable::class.simpleName ?: "Throwable"}: ${throwable.message ?: "(no message)"}",
            message,
        )
    }

    actual fun breadcrumb(category: String, message: String, data: Map<String, Any?>) {
        IosAnalyticsBridge.breadcrumb?.invoke(category, message, data)
    }

    actual fun flush() {
        IosAnalyticsBridge.flush?.invoke()
    }
}
