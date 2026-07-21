@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package live.localapp.local.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
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
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

sealed interface LocationResult {
    data class Success(val lat: Double, val lng: Double) : LocationResult
    data object Denied : LocationResult
}

@Composable
actual fun RequestLocationEffect(
    active: Boolean,
    onLocation: (lat: Double, lng: Double) -> Unit,
    onDenied: () -> Unit,
    onDeniedPermanently: (() -> Unit)?,
) {
    if (!active) return

    LaunchedEffect(Unit) {
        println("LocationEffect: starting...")
        val result = suspendCancellableCoroutine<LocationResult> { continuation ->
            dispatch_async(dispatch_get_main_queue()) {
                val manager = CLLocationManager()
                manager.desiredAccuracy = kCLLocationAccuracyHundredMeters

                val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
                    private var delivered = false

                    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
                        if (delivered) return
                        val status = CLLocationManager.authorizationStatus()
                        println("LocationEffect: auth changed to $status")
                        when (status) {
                            kCLAuthorizationStatusAuthorizedWhenInUse,
                            kCLAuthorizationStatusAuthorizedAlways -> {
                                manager.requestLocation()
                            }
                            kCLAuthorizationStatusDenied,
                            kCLAuthorizationStatusRestricted -> {
                                delivered = true
                                continuation.resume(LocationResult.Denied)
                            }
                        }
                    }

                    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
                        if (delivered) return
                        val loc = didUpdateLocations.lastOrNull() as? CLLocation ?: return
                        delivered = true
                        manager.stopUpdatingLocation()
                        val lat = loc.coordinate.useContents { latitude }
                        val lng = loc.coordinate.useContents { longitude }
                        println("LocationEffect: got location $lat, $lng")
                        continuation.resume(LocationResult.Success(lat, lng))
                    }

                    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
                        if (delivered) return
                        println("LocationEffect: error ${didFailWithError.localizedDescription}")
                        delivered = true
                        continuation.resume(LocationResult.Denied)
                    }
                }

                // Retain the manager + delegate for the entire lifetime of the suspend.
                // CLLocationManager keeps only a WEAK reference to its delegate, and the app
                // must hold a strong reference to the manager itself. Both are locals to this
                // dispatch block, so once it returns — right after the permission prompt is
                // shown, while we're suspended waiting for the user — they become eligible for
                // deallocation. When the user then taps "Allow", iOS calls back into a freed
                // delegate and the app crashes (EXC_BAD_ACCESS). Pinning both to the
                // continuation keeps them alive until it resumes or is cancelled.
                continuation.invokeOnCancellation {
                    // The string template references `delegate` so the closure captures a
                    // strong reference to it (and to `manager`) — that is what keeps both
                    // alive for the suspend's duration.
                    println("LocationEffect: cancelled — releasing manager + delegate $delegate")
                    manager.delegate = null
                    manager.stopUpdatingLocation()
                }

                // Check status BEFORE setting delegate to avoid double-resume
                val status = CLLocationManager.authorizationStatus()
                println("LocationEffect: initial status = $status")

                if (status == kCLAuthorizationStatusNotDetermined) {
                    // Setting delegate will trigger didChangeAuthorization, but status is still "not determined"
                    // so the delegate won't resume yet — it'll wait for the user's response
                    manager.delegate = delegate
                    println("LocationEffect: requesting authorization...")
                    manager.requestWhenInUseAuthorization()
                } else {
                    // Status is already determined — handle directly, don't set delegate (avoids double-fire)
                    when (status) {
                        kCLAuthorizationStatusAuthorizedWhenInUse,
                        kCLAuthorizationStatusAuthorizedAlways -> {
                            println("LocationEffect: already authorized, requesting location...")
                            manager.delegate = delegate
                            manager.requestLocation()
                        }
                        else -> {
                            println("LocationEffect: already denied")
                            continuation.resume(LocationResult.Denied)
                        }
                    }
                }
            }
        }

        when (result) {
            is LocationResult.Success -> onLocation(result.lat, result.lng)
            is LocationResult.Denied -> onDenied()
        }
    }
}

actual fun checkLocationPermissionGranted(): Boolean {
    val status = CLLocationManager.authorizationStatus()
    return status == kCLAuthorizationStatusAuthorizedWhenInUse || status == kCLAuthorizationStatusAuthorizedAlways
}

@Composable
actual fun OnAppResumeEffect(onResume: () -> Unit) {
    val callback = androidx.compose.runtime.rememberUpdatedState(onResume)
    androidx.compose.runtime.DisposableEffect(Unit) {
        val observer = platform.Foundation.NSNotificationCenter.defaultCenter.addObserverForName(
            name = platform.UIKit.UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = platform.Foundation.NSOperationQueue.mainQueue,
        ) { _ -> callback.value() }
        onDispose {
            platform.Foundation.NSNotificationCenter.defaultCenter.removeObserver(observer)
        }
    }
}
