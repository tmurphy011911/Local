package live.localapp.local.platform

/**
 * Returns a formatted app version string for display, e.g.
 * "Local v1.0.2 (Build 3)". Reads from the platform's native
 * package metadata so the value bumps automatically with each release.
 */
expect fun getAppVersion(): String
