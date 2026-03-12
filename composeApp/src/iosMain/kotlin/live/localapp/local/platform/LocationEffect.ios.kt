@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package live.localapp.local.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.Foundation.NSError
import platform.darwin.NSObject

@Composable
actual fun RequestLocationEffect(
    active: Boolean,
    onLocation: (lat: Double, lng: Double) -> Unit,
    onDenied: () -> Unit,
) {
    if (!active) return

    // Mock: 43 Upland Rd, Waltham, MA 02451
    DisposableEffect(active) {
        onLocation(42.3765, -71.2356)
        onDispose { }
    }
}

private class LocationDelegate(
    private val onLocation: (Double, Double) -> Unit,
    private val onDenied: () -> Unit,
    private val manager: CLLocationManager,
) : NSObject(), CLLocationManagerDelegateProtocol {

    private var delivered = false

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        if (delivered) return
        val status = CLLocationManager.authorizationStatus()
        when (status) {
            kCLAuthorizationStatusAuthorizedWhenInUse,
            kCLAuthorizationStatusAuthorizedAlways -> {
                manager.requestLocation()
            }
            kCLAuthorizationStatusDenied,
            kCLAuthorizationStatusRestricted -> {
                delivered = true
                onDenied()
            }
        }
    }

    override fun locationManager(
        manager: CLLocationManager,
        didUpdateLocations: List<*>,
    ) {
        if (delivered) return
        val loc = didUpdateLocations.lastOrNull() as? CLLocation ?: return
        delivered = true
        manager.stopUpdatingLocation()
        val lat = loc.coordinate.useContents { latitude }
        val lng = loc.coordinate.useContents { longitude }
        onLocation(lat, lng)
    }

    override fun locationManager(
        manager: CLLocationManager,
        didFailWithError: NSError,
    ) {
        if (delivered) return
        delivered = true
        onDenied()
    }
}
