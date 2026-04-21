package live.localapp.local.platform

import androidx.compose.runtime.Composable

/**
 * Platform-specific location permission request + GPS coordinate fetch.
 *
 * When [active] becomes true the platform will:
 *   1. Request location permission (system dialog)
 *   2. On grant → read GPS coordinates → call [onLocation]
 *   3. On deny  → call [onDenied]
 *
 * The effect is a no-op while [active] is false.
 */
@Composable
expect fun RequestLocationEffect(
    active: Boolean,
    onLocation: (lat: Double, lng: Double) -> Unit,
    onDenied: () -> Unit,
    onDeniedPermanently: (() -> Unit)? = null,
)

/** Synchronous check: is location permission currently granted? */
expect fun checkLocationPermissionGranted(): Boolean

/** Fires [onResume] when the app returns to the foreground. */
@Composable
expect fun OnAppResumeEffect(onResume: () -> Unit)
