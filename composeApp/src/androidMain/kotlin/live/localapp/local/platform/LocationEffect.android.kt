package live.localapp.local.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
actual fun RequestLocationEffect(
    active: Boolean,
    onLocation: (lat: Double, lng: Double) -> Unit,
    onDenied: () -> Unit,
) {
    // Mock: 43 Upland Rd, Waltham, MA 02451
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        onLocation(42.3765, -71.2356)
    }
}
