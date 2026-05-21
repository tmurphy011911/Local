import Foundation
import ComposeApp

#if canImport(Sentry)
import Sentry
#endif

#if canImport(PostHog)
import PostHog
#endif

/// Wires sentry-cocoa + posthog-ios into the shared Kotlin `IosAnalyticsBridge`
/// so common code can call `Analytics.track(...)` without knowing it's on iOS.
///
/// SDK setup steps (one-time, in Xcode):
///   1. File → Add Package Dependencies… → https://github.com/getsentry/sentry-cocoa
///      pick the latest 8.x, target `iosApp`.
///   2. File → Add Package Dependencies… → https://github.com/PostHog/posthog-ios
///      pick the latest, target `iosApp`.
///
/// Until both SPM packages are added the `canImport` guards below short-circuit
/// to no-ops, so the app still builds and runs.
enum AnalyticsBridge {

    /// Read keys from the Kotlin side, start the SDKs, then install the
    /// bridge closures. Call this as the *first* line of `iOSApp.init()`.
    static func installAndStart() {
        let dsn = ObservabilityConfig.shared.SENTRY_DSN_IOS
        let postHogKey = ObservabilityConfig.shared.POSTHOG_KEY
        let postHogHost = ObservabilityConfig.shared.POSTHOG_HOST

        // ---- Sentry ----
        #if canImport(Sentry)
        if !dsn.isEmpty {
            SentrySDK.start { options in
                options.dsn = dsn
                options.tracesSampleRate = 0.0      // crash + errors only
                options.enableAutoSessionTracking = true
                #if DEBUG
                options.environment = "dev"
                #else
                options.environment = "prod"
                #endif
            }
        }
        #endif

        // ---- PostHog ----
        #if canImport(PostHog)
        if !postHogKey.isEmpty {
            let config = PostHogConfig(apiKey: postHogKey, host: postHogHost)
            config.captureApplicationLifecycleEvents = false
            config.captureScreenViews = false
            PostHogSDK.shared.setup(config)
        }
        #endif

        installBridgeClosures()
    }

    // MARK: - Bridge wiring

    private static func installBridgeClosures() {
        let bridge = IosAnalyticsBridge.shared

        bridge.initialize = { environment, appVersion, buildNumber, platform in
            #if canImport(PostHog)
            PostHogSDK.shared.register([
                "platform": platform,
                "app_version": appVersion,
                "build_number": buildNumber,
                "environment": environment,
            ])
            #endif
            #if canImport(Sentry)
            SentrySDK.configureScope { scope in
                scope.setTag(value: platform, key: "platform")
                scope.setTag(value: environment, key: "environment")
            }
            #endif
        }

        bridge.identify = { userId, set, setOnce in
            #if canImport(PostHog)
            let setProps = sanitize(set)
            let setOnceProps = sanitize(setOnce)
            PostHogSDK.shared.identify(userId, userProperties: setProps, userPropertiesSetOnce: setOnceProps)
            #endif
            #if canImport(Sentry)
            let user = User()
            user.userId = userId
            SentrySDK.setUser(user)
            #endif
        }

        bridge.reset = {
            #if canImport(PostHog)
            PostHogSDK.shared.reset()
            #endif
            #if canImport(Sentry)
            SentrySDK.setUser(nil)
            #endif
        }

        bridge.capture = { eventName, properties in
            #if canImport(PostHog)
            PostHogSDK.shared.capture(eventName, properties: sanitize(properties))
            #endif
            #if canImport(Sentry)
            let crumb = Breadcrumb(level: .info, category: "analytics")
            crumb.message = eventName
            SentrySDK.addBreadcrumb(crumb)
            #endif
        }

        bridge.setUserProperty = { key, value in
            #if canImport(PostHog)
            if let v = value {
                PostHogSDK.shared.capture("$set", properties: ["$set": [key: v]])
            }
            #endif
        }

        bridge.recordError = { description, message in
            #if canImport(Sentry)
            let err = NSError(
                domain: "live.localapp.local",
                code: 0,
                userInfo: [NSLocalizedDescriptionKey: description]
            )
            if let message = message {
                SentrySDK.capture(message: message)
            }
            SentrySDK.capture(error: err)
            #endif
        }

        bridge.breadcrumb = { category, message, data in
            #if canImport(Sentry)
            let crumb = Breadcrumb(level: .info, category: category)
            crumb.message = message
            crumb.data = sanitize(data)
            SentrySDK.addBreadcrumb(crumb)
            #endif
        }

        bridge.flush = {
            #if canImport(PostHog)
            PostHogSDK.shared.flush()
            #endif
            #if canImport(Sentry)
            SentrySDK.flush(timeout: 2.0)
            #endif
        }
    }

    /// Drop nulls and convert Kotlin Map<String, Any?> values to Swift-friendly types.
    private static func sanitize(_ input: [String: Any?]) -> [String: Any] {
        var out: [String: Any] = [:]
        for (k, v) in input {
            if let v = v { out[k] = v }
        }
        return out
    }
}
