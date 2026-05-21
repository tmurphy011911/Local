package live.localapp.local.observability

import android.content.Context
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.User

/**
 * Android Analytics impl. Call [bind] once from MainActivity with the
 * Application context BEFORE [initialize] (we need a Context for the SDKs).
 */
actual object Analytics {

    private var appContext: Context? = null
    private var initialized = false

    /** Hand the SDK an Application context. Called from MainActivity. */
    fun bind(context: Context) {
        appContext = context.applicationContext
    }

    actual fun initialize(
        environment: String,
        appVersion: String,
        buildNumber: String,
        platform: String,
    ) {
        if (initialized) return
        val ctx = appContext ?: error("Analytics.bind(context) must be called before initialize()")

        SentryAndroid.init(ctx) { options ->
            options.dsn = BuildKeys.SENTRY_DSN_ANDROID
            options.environment = environment
            options.release = "$appVersion+$buildNumber"
            // Crash + error only for now (no performance/replay).
            options.tracesSampleRate = 0.0
            options.isEnableAutoSessionTracking = true
        }

        if (BuildKeys.POSTHOG_KEY.isNotBlank()) {
            val config = PostHogAndroidConfig(
                apiKey = BuildKeys.POSTHOG_KEY,
                host = BuildKeys.POSTHOG_HOST,
            ).apply {
                // Native autocapture is JS-only — and we explicitly disabled
                // the equivalent web toggles in the PostHog dashboard. The
                // settings below keep capture explicit.
                captureScreenViews = false
                captureDeepLinks = false
                captureApplicationLifecycleEvents = false
            }
            PostHogAndroid.setup(ctx, config)
            PostHog.register("platform", platform)
            PostHog.register("app_version", appVersion)
            PostHog.register("build_number", buildNumber)
            PostHog.register("environment", environment)
        }

        initialized = true
    }

    actual fun identify(userId: String, set: Map<String, Any?>, setOnce: Map<String, Any?>) {
        PostHog.identify(
            distinctId = userId,
            userProperties = set.toNonNullStringMap(),
            userPropertiesSetOnce = setOnce.toNonNullStringMap(),
        )
        Sentry.setUser(User().apply { id = userId })
    }

    actual fun reset() {
        PostHog.reset()
        Sentry.setUser(null)
    }

    actual fun track(event: AnalyticsEvent) {
        PostHog.capture(event = event.name, properties = event.properties.toNonNullStringMap())
        Sentry.addBreadcrumb(event.name).also {
            Sentry.configureScope { scope ->
                scope.setExtra("last_event", event.name)
            }
        }
    }

    actual fun setUserProperty(key: String, value: Any?) {
        PostHog.capture(
            event = "\$set",
            properties = mapOf("\$set" to mapOf(key to value)),
        )
    }

    actual fun recordError(throwable: Throwable, message: String?) {
        if (message != null) {
            Sentry.captureMessage(message, SentryLevel.ERROR)
        }
        Sentry.captureException(throwable)
    }

    actual fun breadcrumb(category: String, message: String, data: Map<String, Any?>) {
        val crumb = io.sentry.Breadcrumb().apply {
            this.category = category
            this.message = message
            data.forEach { (k, v) -> if (v != null) setData(k, v) }
        }
        Sentry.addBreadcrumb(crumb)
    }

    actual fun flush() {
        PostHog.flush()
        // Sentry.flush is blocking; cap at 2s so we never hang shutdown.
        Sentry.flush(2_000)
    }

    private fun Map<String, Any?>.toNonNullStringMap(): Map<String, Any> =
        filterValues { it != null }.mapValues { it.value as Any }
}
