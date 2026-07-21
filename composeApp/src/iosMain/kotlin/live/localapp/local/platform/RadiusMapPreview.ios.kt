package live.localapp.local.platform

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.MapKit.MKCoordinateRegionMakeWithDistance
import platform.MapKit.MKMapView

// Region shows radius*2*PAD_FACTOR meters on the constraining axis, so a circle
// of the true radius occupies 1/PAD_FACTOR of the half-view — the coral overlay
// is drawn to match. Keep this in sync between the framing and the Canvas.
private const val PAD_FACTOR = 1.3

// Coral to match the app theme (F56B63) — 20% fill so the map reads through.
private val coralFill = Color(0x33F56B63)
private val coralStroke = Color(0xFFF56B63)

/**
 * iOS renders a native [MKMapView] (via [UIKitView]) centered on the scope, with
 * the coral radius circle drawn in Compose ON TOP — the Kotlin/Native MapKit
 * binding here doesn't expose the `MKCircle`/overlay methods, and a Compose
 * circle also keeps the look identical to Android. The map is framed so the
 * drawn circle lines up with the true geographic radius; verify on device.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun RadiusMapPreview(
    lat: Double,
    lng: Double,
    radiusMiles: Double,
    modifier: Modifier,
) {
    val radiusMeters = radiusMiles * 1609.34
    Box(modifier.fillMaxSize()) {
        UIKitView(
            factory = {
                MKMapView().apply {
                    setScrollEnabled(false)
                    setZoomEnabled(false)
                    setRotateEnabled(false)
                    setPitchEnabled(false)
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { mapView ->
                val center = CLLocationCoordinate2DMake(lat, lng)
                val span = radiusMeters * 2.0 * PAD_FACTOR
                mapView.setRegion(
                    MKCoordinateRegionMakeWithDistance(center, span, span),
                    animated = false,
                )
            },
        )
        Canvas(Modifier.fillMaxSize()) {
            val r = (minOf(size.width, size.height) / 2f) / PAD_FACTOR.toFloat()
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = coralFill, radius = r, center = c)
            drawCircle(color = coralStroke, radius = r, center = c, style = Stroke(width = 4f))
        }
    }
}
