package live.localapp.local.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A non-interactive map centered on [lat]/[lng] with a translucent coral circle
 * of [radiusMiles] radius, framed so the circle roughly fills the view. Mirrors
 * the map+circle scope preview used in Bo's "Where should Bo search?" editor so
 * the Location Settings radius picker matches it.
 *
 * - Android: Google Maps Compose (`GoogleMap` + `Circle`).
 * - iOS: native `MKMapView` embedded via `UIKitView`, with an `MKCircle`
 *   overlay — the codebase's first Compose↔MapKit interop, so verify on device.
 */
@Composable
expect fun RadiusMapPreview(
    lat: Double,
    lng: Double,
    radiusMiles: Double,
    modifier: Modifier = Modifier,
)
