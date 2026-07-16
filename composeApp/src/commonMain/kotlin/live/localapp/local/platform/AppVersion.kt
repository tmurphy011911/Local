package live.localapp.local.platform

/**
 * Returns a formatted app version string for display, e.g.
 * "Local v1.0.2 (Build 3)". Reads from the platform's native
 * package metadata so the value bumps automatically with each release.
 */
expect fun getAppVersion(): String

/**
 * Returns the bare marketing version only, e.g. "1.1.0" — iOS
 * CFBundleShortVersionString / Android versionName (NOT the build number).
 * Used by VersionCheckService to compare against the server's min_version so the
 * check tracks the real shipped version instead of a hand-edited constant.
 * Returns "" when it can't be read, so callers can fall back safely.
 */
expect fun getMarketingVersion(): String
