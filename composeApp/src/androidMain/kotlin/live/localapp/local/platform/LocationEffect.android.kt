package live.localapp.local.platform

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

@Composable
actual fun RequestLocationEffect(
    active: Boolean,
    onLocation: (lat: Double, lng: Double) -> Unit,
    onDenied: () -> Unit,
    onDeniedPermanently: (() -> Unit)?,
) {
    if (!active) return

    val context = LocalContext.current
    val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            try {
                fusedClient.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    CancellationTokenSource().token,
                ).addOnSuccessListener { location ->
                    if (location != null) {
                        onLocation(location.latitude, location.longitude)
                    } else {
                        onDenied()
                    }
                }.addOnFailureListener {
                    onDenied()
                }
            } catch (_: SecurityException) {
                onDenied()
            }
        } else {
            onDenied()
        }
    }

    LaunchedEffect(active) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            try {
                fusedClient.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    CancellationTokenSource().token,
                ).addOnSuccessListener { location ->
                    if (location != null) {
                        onLocation(location.latitude, location.longitude)
                    } else {
                        onDenied()
                    }
                }.addOnFailureListener {
                    onDenied()
                }
            } catch (_: SecurityException) {
                onDenied()
            }
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}

actual fun checkLocationPermissionGranted(): Boolean = false // Android not primary platform yet
