package live.localapp.local.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Android reuses the maps-compose BoResultsMapView.
@Composable
actual fun ResultsMapInline(
    pins: List<MapPin>,
    focusedPinId: String?,
    onPinTap: (String) -> Unit,
    modifier: Modifier,
) {
    BoResultsMapView(
        pins = pins,
        focusedPinId = focusedPinId,
        onPinTap = onPinTap,
        modifier = modifier,
    )
}
