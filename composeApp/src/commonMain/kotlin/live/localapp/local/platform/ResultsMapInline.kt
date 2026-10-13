package live.localapp.local.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Inline map surface for the Events/Places results map — rendered in the content
 * area BELOW the persistent header + filters (not a separate screen). Android
 * uses maps-compose (BoResultsMapView); iOS uses a native MKMapView via UIKitView.
 * One pin per item; [focusedPinId] is emphasised where supported; a pin tap fires
 * [onPinTap] with the pin's id (== the item id).
 */
@Composable
expect fun ResultsMapInline(
    pins: List<MapPin>,
    focusedPinId: String?,
    onPinTap: (String) -> Unit,
    modifier: Modifier,
)
