# iOS observability setup

The Kotlin + Swift code for Sentry + PostHog is already wired. You only need
to add two SPM packages in Xcode before the next build:

1. Open `iosApp/iosApp.xcodeproj`.
2. **File → Add Package Dependencies…**
3. Add `https://github.com/getsentry/sentry-cocoa` — pick the latest 8.x,
   target the `iosApp` app target.
4. **File → Add Package Dependencies…** again
5. Add `https://github.com/PostHog/posthog-ios` — pick the latest, target
   the `iosApp` app target.
6. Build. The `#if canImport(Sentry) / canImport(PostHog)` guards in
   `AnalyticsBridge.swift` flip on automatically.

Until both packages are added the bridge compiles but every call is a no-op,
so the app still runs.

## DSN / keys

Sourced from `local.properties` at the repo root via the Gradle-generated
`BuildKeys.kt`. They're exposed to Swift as
`ObservabilityConfig.shared.SENTRY_DSN_IOS`, `.POSTHOG_KEY`, `.POSTHOG_HOST`.
Rotating: update `local.properties` and rebuild.
