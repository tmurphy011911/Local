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
