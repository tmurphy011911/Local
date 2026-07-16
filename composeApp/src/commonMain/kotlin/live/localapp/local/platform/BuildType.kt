package live.localapp.local.platform

/**
 * True for a debug/development binary, false for a release (App Store /
 * TestFlight / Play) build. Backed by the platform's native debug flag:
 * `BuildConfig.DEBUG` on Android and `Platform.isDebugBinary` on iOS.
 *
 * Used to gate dev-only behavior (e.g. [live.localapp.local.Config.TEST_AUTH_BYPASS])
 * so it can never execute in a shipped binary, regardless of the source-level
 * constant. The release toolchain compiles this to a constant `false`, letting
 * dead-code elimination drop the guarded path entirely.
 */
expect val isDebugBuild: Boolean

/**
 * True for the STAGING build — the separate `.dev` app (Android `staging` build
 * type / iOS `Staging` configuration) that installs alongside the real app and is
 * locked to the dev backend, for side-by-side testing and TestFlight/Play internal
 * distribution. False for normal debug and release builds.
 *
 * Android: `BuildConfig.IS_STAGING`. iOS: the `IS_STAGING` Info.plist key (set to
 * `YES` only by the Staging configuration's xcconfig).
 */
expect val isStagingBuild: Boolean
