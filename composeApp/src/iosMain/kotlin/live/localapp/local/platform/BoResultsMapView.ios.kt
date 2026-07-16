package live.localapp.local.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * No-op on iOS: the iOS chat is native SwiftUI (`ChatView.swift`) with its own
 * MapKit results map, so this Compose path never renders on iOS. Present only
 * to satisfy the expect/actual contract — mirrors `PlanMapHero.ios.kt`.
 */
@Composable
actual fun BoResultsMapView(
    pins: List<MapPin>,
    focusedPinId: String?,
    onPinTap: (pinId: String) -> Unit,
    modifier: Modifier,
) {
    // intentionally empty
}
