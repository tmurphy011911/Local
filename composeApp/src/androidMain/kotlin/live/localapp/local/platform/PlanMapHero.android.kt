package live.localapp.local.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.cos
import kotlin.math.sqrt
import live.localapp.local.BuildConfig

// Switch from walking to driving once the straight-line distance between two
// stops exceeds this threshold (~1 mile). Mirrors the iOS rule.
private const val WALK_DRIVE_THRESHOLD_M = 1_609.0

// Coral palette mirroring iOS pdCoral / app theme.
private val coral = Color(red = 0.961f, green = 0.42f, blue = 0.388f)

@Composable
actual fun PlanMapHero(
    stops: List<MapHeroStop>,
    modifier: Modifier,
    onTotalsChanged: (MapHeroTotals?) -> Unit,
): Boolean {
    if (stops.isEmpty()) return false

    val context = LocalContext.current
    val coords = remember(stops) { stops.map { LatLng(it.lat, it.lng) } }

    // Camera fits the bounding box with bottom bias so pins clear the
    // floating title card overlapping the lower portion of the hero.
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(coords.first(), 13f)
    }

    LaunchedEffect(coords) {
        cameraPositionState.position = computeCameraPosition(coords)
        // Animate after the next frame so the move is visible during day swaps.
        try {
            cameraPositionState.animate(CameraUpdateFactory.newCameraPosition(computeCameraPosition(coords)))
        } catch (_: Throwable) { /* ignore — non-critical */ }
    }

    var legs by remember(coords) { mutableStateOf<List<Leg>>(emptyList()) }

    LaunchedEffect(coords) {
        if (coords.size < 2) {
            legs = emptyList()
            onTotalsChanged(null)
            return@LaunchedEffect
        }
        val computed = withContext(Dispatchers.IO) { fetchLegs(coords) }
        legs = computed
        val totalMeters = computed.sumOf { it.meters }
        val totalMinutes = computed.sumOf { it.minutes }
        val walkMin = computed.filter { it.mode == LegMode.Walking }.sumOf { it.minutes }
        val driveMin = computed.filter { it.mode == LegMode.Driving }.sumOf { it.minutes }
        val dominant = if (driveMin > walkMin) "drive" else "walk"
        onTotalsChanged(
            MapHeroTotals(
                miles = totalMeters / 1609.34,
                minutes = totalMinutes,
                dominantMode = dominant,
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(0.dp))
            .background(Color(0xFFEEEEEE)),
    ) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = MapType.NORMAL),
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
            // Numbered coral pins, one per stop.
            coords.forEachIndexed { idx, coord ->
                val icon = remember(idx) { numberedPinDescriptor(context, idx + 1) }
                Marker(
                    state = MarkerState(position = coord),
                    icon = icon,
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                )
            }
            // Dashed coral route between consecutive stops.
            if (coords.size >= 2) {
                Polyline(
                    points = coords,
                    color = coral,
                    width = 8f,
                    pattern = listOf(
                        com.google.android.gms.maps.model.Dash(20f),
                        com.google.android.gms.maps.model.Gap(12f),
                    ),
                )
            }
            // Per-leg walk/drive pill at each midpoint.
            legs.forEach { leg ->
                val labelIcon = remember(leg.midpoint, leg.minutes, leg.mode) {
                    legPillDescriptor(leg.minutes, leg.mode)
                }
                Marker(
                    state = MarkerState(position = leg.midpoint),
                    icon = labelIcon,
                )
            }
        }
    }
    return true
}

private fun computeCameraPosition(coords: List<LatLng>): CameraPosition {
    if (coords.size == 1) {
        return CameraPosition.fromLatLngZoom(coords.first(), 13.5f)
    }
    val builder = LatLngBounds.builder()
    coords.forEach { builder.include(it) }
    val bounds = builder.build()
    val ne = bounds.northeast
    val sw = bounds.southwest
    val centerLat = (ne.latitude + sw.latitude) / 2 - (ne.latitude - sw.latitude) * 0.18
    val centerLng = (ne.longitude + sw.longitude) / 2
    // Zoom estimate from the longitude span — wider span = lower zoom.
    val lngSpan = (ne.longitude - sw.longitude).coerceAtLeast(0.005)
    val zoom = (13.0 - kotlin.math.log2(lngSpan / 0.02)).coerceIn(8.0, 16.0)
    return CameraPosition.fromLatLngZoom(LatLng(centerLat, centerLng), zoom.toFloat())
}

// ─── Marker bitmaps ───────────────────────────────────────────────────────

private fun numberedPinDescriptor(context: android.content.Context, idx: Int): BitmapDescriptor {
    val density = context.resources.displayMetrics.density
    val sizePx = (32f * density).toInt()
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = sizePx / 2f
    val cy = sizePx / 2f
    val radius = sizePx / 2f - 2f * density

    // Outer shadow disc
    Paint().apply {
        color = android.graphics.Color.argb(40, 0, 0, 0)
        isAntiAlias = true
    }.also { canvas.drawCircle(cx, cy + 1f * density, radius + 1f, it) }

    // White fill
    Paint().apply {
        color = android.graphics.Color.WHITE
        isAntiAlias = true
    }.also { canvas.drawCircle(cx, cy, radius, it) }

    // Coral ring
    Paint().apply {
        color = android.graphics.Color.rgb(245, 107, 99)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        isAntiAlias = true
    }.also { canvas.drawCircle(cx, cy, radius, it) }

    // Number
    Paint().apply {
        color = android.graphics.Color.rgb(245, 107, 99)
        textAlign = Paint.Align.CENTER
        textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }.also {
        val baseline = cy - (it.descent() + it.ascent()) / 2f
        canvas.drawText(idx.toString(), cx, baseline, it)
    }
    return BitmapDescriptorFactory.fromBitmap(bmp)
}

private fun legPillDescriptor(minutes: Int, mode: LegMode): BitmapDescriptor {
    val density = android.content.res.Resources.getSystem().displayMetrics.density
    val text = "${minutes} MIN ${mode.label}"
    val textPaint = Paint().apply {
        color = android.graphics.Color.rgb(60, 56, 51)
        textSize = 9f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
        letterSpacing = 0.04f
    }
    val textWidth = textPaint.measureText(text)
    val padH = 7f * density
    val padV = 3f * density
    val widthPx = (textWidth + padH * 2).toInt()
    val heightPx = (textPaint.textSize + padV * 2).toInt()
    val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    // Drop shadow
    Paint().apply {
        color = android.graphics.Color.argb(45, 0, 0, 0)
        isAntiAlias = true
    }.also {
        canvas.drawRoundRect(
            RectF(0f, 1f * density, widthPx.toFloat(), heightPx.toFloat()),
            heightPx / 2f, heightPx / 2f, it,
        )
    }
    // White pill
    Paint().apply {
        color = android.graphics.Color.WHITE
        isAntiAlias = true
    }.also {
        canvas.drawRoundRect(
            RectF(0f, 0f, widthPx.toFloat(), heightPx - 1f * density),
            heightPx / 2f, heightPx / 2f, it,
        )
    }
    // Hairline border
    Paint().apply {
        color = android.graphics.Color.argb(30, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 0.5f * density
        isAntiAlias = true
    }.also {
        canvas.drawRoundRect(
            RectF(0f, 0f, widthPx.toFloat(), heightPx - 1f * density),
            heightPx / 2f, heightPx / 2f, it,
        )
    }
    // Text
    val baseline = (heightPx - 1f * density + textPaint.textSize) / 2f - textPaint.descent() / 2f
    canvas.drawText(text, padH, baseline, textPaint)
    return BitmapDescriptorFactory.fromBitmap(bmp)
}

// ─── Directions API client ────────────────────────────────────────────────

private enum class LegMode(val label: String, val apiMode: String) {
    Walking("WALK", "walking"),
    Driving("DRIVE", "driving"),
}

private data class Leg(
    val midpoint: LatLng,
    val minutes: Int,
    val meters: Double,
    val mode: LegMode,
)

@Serializable
private data class DirectionsResponse(
    val routes: List<DirectionsRoute> = emptyList(),
    val status: String = "",
)

@Serializable
private data class DirectionsRoute(val legs: List<DirectionsLeg> = emptyList())

@Serializable
private data class DirectionsLeg(
    val distance: ValueText? = null,
    val duration: ValueText? = null,
)

@Serializable
private data class ValueText(val value: Long = 0)

private val ktor by lazy {
    HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }
}

private suspend fun fetchLegs(coords: List<LatLng>): List<Leg> {
    val key = BuildConfig.MAPS_API_KEY
    val out = mutableListOf<Leg>()
    for (i in 0 until coords.lastIndex) {
        val a = coords[i]
        val b = coords[i + 1]
        val mid = LatLng((a.latitude + b.latitude) / 2, (a.longitude + b.longitude) / 2)
        val straightMeters = haversine(a, b)
        val mode = if (straightMeters > WALK_DRIVE_THRESHOLD_M) LegMode.Driving else LegMode.Walking

        if (key.isBlank()) {
            // No API key configured — fall back to straight-line estimate.
            out += straightLineLeg(mid, straightMeters, mode)
            continue
        }

        try {
            val url = "https://maps.googleapis.com/maps/api/directions/json" +
                "?origin=${a.latitude},${a.longitude}" +
                "&destination=${b.latitude},${b.longitude}" +
                "&mode=${mode.apiMode}" +
                "&key=$key"
            val resp = ktor.get(url)
            val body = resp.bodyAsText()
            val parsed = Json { ignoreUnknownKeys = true; isLenient = true }
                .decodeFromString(DirectionsResponse.serializer(), body)
            val leg0 = parsed.routes.firstOrNull()?.legs?.firstOrNull()
            val secs = leg0?.duration?.value
            val dist = leg0?.distance?.value
            if (secs != null && dist != null && secs > 0 && dist > 0) {
                val mins = ((secs.toDouble() / 60.0).coerceAtLeast(1.0)).toInt()
                out += Leg(mid, mins, dist.toDouble(), mode)
                continue
            }
        } catch (e: Throwable) {
            println("PlanMapHero: directions fetch failed (${e.message})")
        }
        out += straightLineLeg(mid, straightMeters, mode)
    }
    return out
}

private fun straightLineLeg(mid: LatLng, meters: Double, mode: LegMode): Leg {
    val pacePerMin = if (mode == LegMode.Driving) 600.0 else 80.0
    val mins = (meters / pacePerMin).coerceAtLeast(1.0).toInt()
    return Leg(mid, mins, meters, mode)
}

private fun haversine(a: LatLng, b: LatLng): Double {
    // Quick equirectangular approximation; accurate enough for the
    // walk-vs-drive threshold check at urban distances.
    val rad = Math.PI / 180.0
    val x = (b.longitude - a.longitude) * rad * cos(((a.latitude + b.latitude) / 2.0) * rad)
    val y = (b.latitude - a.latitude) * rad
    return sqrt(x * x + y * y) * 6_371_000.0
}
