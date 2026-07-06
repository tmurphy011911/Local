package live.localapp.local.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import live.localapp.local.R
import kotlin.math.abs
import kotlin.math.log2

@Composable
actual fun BoResultsMapView(
    pins: List<MapPin>,
    focusedPinId: String?,
    onPinTap: (pinId: String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val mapStyle = remember(context) {
        try { MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_no_poi) } catch (_: Throwable) { null }
    }
    // One shared bitmap per style — cheap and avoids re-rasterising per marker.
    val focusedIcon = remember(context) { boPinDescriptor(context, focused = true) }
    val plainIcon = remember(context) { boPinDescriptor(context, focused = false) }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            pins.firstOrNull()?.let { LatLng(it.lat, it.lng) } ?: LatLng(42.36, -71.06),
            13f,
        )
    }

    // Re-frame whenever the focused pin (or the pin set) changes: centre on the
    // focused venue, zoom to fit its ~6 nearest neighbours (mirrors iOS
    // focusRegion). Non-animated set first so a brand-new tray lands framed,
    // then animate subsequent focus changes.
    LaunchedEffect(focusedPinId, pins) {
        val target = computeBoCamera(pins, focusedPinId) ?: return@LaunchedEffect
        try {
            cameraPositionState.animate(CameraUpdateFactory.newCameraPosition(target))
        } catch (_: Throwable) {
            cameraPositionState.position = target
        }
    }

    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        properties = MapProperties(mapType = MapType.NORMAL, mapStyleOptions = mapStyle),
        uiSettings = MapUiSettings(
            // Interactive pan/zoom (the results map is explorable, unlike the
            // static plan hero); no rotate/tilt to keep it flat + legible.
            zoomControlsEnabled = false,
            compassEnabled = false,
            myLocationButtonEnabled = false,
            mapToolbarEnabled = false,
            rotationGesturesEnabled = false,
            tiltGesturesEnabled = false,
            scrollGesturesEnabled = true,
            zoomGesturesEnabled = true,
        ),
    ) {
        pins.forEach { pin ->
            val isFocused = pin.id == focusedPinId
            Marker(
                state = MarkerState(position = LatLng(pin.lat, pin.lng)),
                icon = if (isFocused) focusedIcon else plainIcon,
                anchor = Offset(0.5f, 0.5f),
                // Focused pin draws on top of overlapping neighbours.
                zIndex = if (isFocused) 1f else 0f,
                onClick = {
                    onPinTap(pin.id)
                    true // consume — don't let the map recentre/show a callout
                },
            )
        }
    }
}

/** Centre on the focused venue; span to include its ~6 nearest neighbours,
 *  clamped so dense clusters read tight and sparse ones don't over-zoom. */
private fun computeBoCamera(pins: List<MapPin>, focusedPinId: String?): CameraPosition? {
    if (pins.isEmpty()) return null
    val focused = pins.firstOrNull { it.id == focusedPinId } ?: pins.first()
    val nearest = pins.asSequence()
        .filter { it.id != focused.id }
        .sortedBy { p -> sq(p.lat - focused.lat) + sq(p.lng - focused.lng) }
        .take(6)
        .toList()
    val cluster = listOf(focused) + nearest
    val maxLng = cluster.maxOf { abs(it.lng - focused.lng) }
    // 2.6x breathing room around the cluster; clamp ~1mi..~12mi span.
    val lngSpan = (maxLng * 2.6).coerceIn(0.02, 0.22)
    val zoom = (13.0 - log2(lngSpan / 0.02)).coerceIn(10.5, 15.5)
    return CameraPosition.fromLatLngZoom(LatLng(focused.lat, focused.lng), zoom.toFloat())
}

private fun sq(x: Double) = x * x

/** Focused = filled coral disc; unfocused = white disc with coral ring.
 *  Mirrors the iOS filled-vs-outlined pin treatment. */
private fun boPinDescriptor(context: Context, focused: Boolean): BitmapDescriptor {
    val density = context.resources.displayMetrics.density
    val sizeDp = if (focused) 30f else 22f
    val sizePx = (sizeDp * density).toInt()
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = sizePx / 2f
    val cy = sizePx / 2f
    val radius = sizePx / 2f - 2f * density
    val coralInt = android.graphics.Color.rgb(255, 107, 107)

    // Soft shadow disc.
    Paint().apply {
        color = android.graphics.Color.argb(45, 0, 0, 0)
        isAntiAlias = true
    }.also { canvas.drawCircle(cx, cy + 1f * density, radius + 1f, it) }

    if (focused) {
        // Coral fill + white centre dot.
        Paint().apply { color = coralInt; isAntiAlias = true }
            .also { canvas.drawCircle(cx, cy, radius, it) }
        Paint().apply { color = android.graphics.Color.WHITE; isAntiAlias = true }
            .also { canvas.drawCircle(cx, cy, radius * 0.34f, it) }
    } else {
        // White fill + coral ring + coral centre dot.
        Paint().apply { color = android.graphics.Color.WHITE; isAntiAlias = true }
            .also { canvas.drawCircle(cx, cy, radius, it) }
        Paint().apply {
            color = coralInt
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            isAntiAlias = true
        }.also { canvas.drawCircle(cx, cy, radius, it) }
        Paint().apply { color = coralInt; isAntiAlias = true }
            .also { canvas.drawCircle(cx, cy, radius * 0.34f, it) }
    }
    return BitmapDescriptorFactory.fromBitmap(bmp)
}
