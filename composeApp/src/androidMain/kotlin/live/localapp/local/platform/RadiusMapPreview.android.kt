package live.localapp.local.platform

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import live.localapp.local.R
import kotlin.math.cos
import kotlin.math.ln

// Coral to match the app theme (F56B63). Fill is ~20% so the map reads through.
private val coralFill = Color(0x33F56B63)
private val coralStroke = Color(0xFFF56B63)

// Extra breathing room around the circle, matching iOS (MKCoordinateRegion pad):
// the circle occupies ~1/1.3 of the constraining half-axis, leaving a margin.
private const val PAD_FACTOR = 1.3

@Composable
actual fun RadiusMapPreview(
    lat: Double,
    lng: Double,
    radiusMiles: Double,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val center = remember(lat, lng) { LatLng(lat, lng) }
    val radiusMeters = radiusMiles * 1609.34

    val mapStyle = remember(context) {
        try {
            MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_no_poi)
        } catch (_: Throwable) {
            null
        }
    }

    val cameraPositionState = rememberCameraPositionState {
        // Placeholder until the map is measured; the bounds fit below reframes it.
        position = CameraPosition.fromLatLngZoom(center, zoomForRadius(radiusMiles))
    }
    // `newLatLngBounds` needs the map to be laid out — gate on onMapLoaded so it
    // doesn't throw "map size can't be 0" on first composition.
    var mapLoaded by remember { mutableStateOf(false) }

    // Fit the whole radius circle to the ACTUAL view — the preview box is short and
    // wide, so height is the constraining axis for a round circle. A fixed zoom
    // heuristic ignores that and over-zooms; bounds fitting respects it (matches iOS).
    LaunchedEffect(mapLoaded, center, radiusMiles) {
        if (!mapLoaded) return@LaunchedEffect
        try {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngBounds(boundsForRadius(center, radiusMeters), 0),
            )
        } catch (_: Throwable) {
            // Fallback to the zoom heuristic if bounds fitting fails.
            try {
                cameraPositionState.animate(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.fromLatLngZoom(center, zoomForRadius(radiusMiles)),
                    ),
                )
            } catch (_: Throwable) { /* non-critical */ }
        }
    }

    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        onMapLoaded = { mapLoaded = true },
        properties = MapProperties(mapType = MapType.NORMAL, mapStyleOptions = mapStyle),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false,
            compassEnabled = false,
            myLocationButtonEnabled = false,
            mapToolbarEnabled = false,
            rotationGesturesEnabled = false,
            tiltGesturesEnabled = false,
            scrollGesturesEnabled = false,
            zoomGesturesEnabled = false,
            scrollGesturesEnabledDuringRotateOrZoom = false,
        ),
    ) {
        Circle(
            center = center,
            radius = radiusMeters,
            fillColor = coralFill,
            strokeColor = coralStroke,
            strokeWidth = 4f,
        )
    }
}

// A square LatLngBounds enclosing the radius circle plus PAD_FACTOR margin. Google
// Maps fits this to the real view dimensions, so the circle is framed correctly
// whatever the box aspect ratio (here: short + wide). 1° lat ≈ 111,320 m; longitude
// degrees shrink by cos(lat).
private fun boundsForRadius(center: LatLng, radiusMeters: Double): LatLngBounds {
    val halfSpanMeters = radiusMeters * PAD_FACTOR
    val deltaLat = halfSpanMeters / 111_320.0
    val deltaLng = halfSpanMeters / (111_320.0 * cos(Math.toRadians(center.latitude)))
    return LatLngBounds(
        LatLng(center.latitude - deltaLat, center.longitude - deltaLng),
        LatLng(center.latitude + deltaLat, center.longitude + deltaLng),
    )
}

// Fallback-only: rough Google-Maps zoom used for the pre-layout placeholder camera
// and if bounds fitting fails. Each zoom level halves the visible span. This does
// NOT account for the view's aspect ratio — boundsForRadius is the real framing.
private fun zoomForRadius(radiusMiles: Double): Float {
    val r = radiusMiles.coerceAtLeast(1.0)
    return (13.0 - ln(r / 2.0) / ln(2.0)).coerceIn(6.0, 15.0).toFloat()
}
