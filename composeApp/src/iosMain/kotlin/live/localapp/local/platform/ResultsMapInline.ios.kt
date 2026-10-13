package live.localapp.local.platform

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.MapKit.MKAnnotationProtocol
import platform.MapKit.MKAnnotationView
import platform.MapKit.MKCoordinateRegionMakeWithDistance
import platform.MapKit.MKMapView
import platform.MapKit.MKMapViewDelegateProtocol
import platform.MapKit.MKPointAnnotation
import platform.UIKit.UIBezierPath
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIImage
import platform.UIKit.UIImageRenderingMode
import platform.UIKit.UIImageSymbolConfiguration
import platform.darwin.NSObject
import kotlin.math.abs
import kotlin.math.hypot

private val coralColor = UIColor.colorWithRed(1.0, green = 0.42, blue = 0.42, alpha = 1.0)

/** Bo-style pin: a coral/white circle with a mappin glyph; focused = filled coral
 *  and larger. Mirrors ChatView.swift `BoMapView.pinImage`. */
@OptIn(ExperimentalForeignApi::class)
private fun dotImage(focused: Boolean): UIImage {
    val size = if (focused) 34.0 else 26.0
    val renderer = UIGraphicsImageRenderer(size = CGSizeMake(size, size))
    return renderer.imageWithActions {
        val rect = CGRectMake(1.5, 1.5, size - 3.0, size - 3.0)
        val circle = UIBezierPath.bezierPathWithOvalInRect(rect)
        (if (focused) coralColor else UIColor.whiteColor).setFill()
        circle.fill()
        coralColor.setStroke()
        circle.setLineWidth(2.0)
        circle.stroke()
        val cfg = UIImageSymbolConfiguration.configurationWithPointSize(if (focused) 15.0 else 12.0)
        UIImage.systemImageNamed("mappin", withConfiguration = cfg)
            ?.imageWithTintColor(
                if (focused) UIColor.whiteColor else coralColor,
                renderingMode = UIImageRenderingMode.UIImageRenderingModeAlwaysOriginal,
            )
            ?.drawInRect(CGRectMake(size * 0.30, size * 0.18, size * 0.40, size * 0.56))
    }
}

/**
 * iOS inline results map — a native [MKMapView] via [UIKitView] with Bo-style dot
 * pins and Bo-style focus zoom: when [focusedPinId] changes the map recenters on
 * that pin and zooms to fit its ~6 nearest neighbours (clamped ~1–12mi). Pinch
 * zoom stays available. A pin tap forwards its id via [onPinTap]. The delegate is
 * `remember`ed so it isn't freed while the map holds only a weak reference
 * (feedback_kmp_ios_delegate_local_lifetime_crash).
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun ResultsMapInline(
    pins: List<MapPin>,
    focusedPinId: String?,
    onPinTap: (String) -> Unit,
    modifier: Modifier,
) {
    val onTap = rememberUpdatedState(onPinTap)
    val focused = rememberUpdatedState(focusedPinId)
    val delegate = remember {
        object : NSObject(), MKMapViewDelegateProtocol {
            override fun mapView(mapView: MKMapView, viewForAnnotation: MKAnnotationProtocol): MKAnnotationView? {
                val ann = viewForAnnotation as? MKPointAnnotation ?: return null
                val reuse = "dot"
                val v = mapView.dequeueReusableAnnotationViewWithIdentifier(reuse)
                    ?: MKAnnotationView(annotation = viewForAnnotation, reuseIdentifier = reuse)
                v.annotation = viewForAnnotation
                v.setCanShowCallout(false)
                v.setImage(dotImage(ann.title == focused.value))
                return v
            }

            override fun mapView(mapView: MKMapView, didSelectAnnotationView: MKAnnotationView) {
                val id = (didSelectAnnotationView.annotation as? MKPointAnnotation)?.title
                mapView.deselectAnnotation(didSelectAnnotationView.annotation, animated = false)
                if (id != null) onTap.value(id)
            }
        }
    }
    var framed by remember { mutableStateOf(false) }
    var lastFocused by remember { mutableStateOf<String?>(null) }

    UIKitView(
        factory = {
            MKMapView().apply {
                setDelegate(delegate)
                setRotateEnabled(false)
                setPitchEnabled(false)
            }
        },
        modifier = modifier.fillMaxSize(),
        update = { map ->
            // Reconcile annotations by title (== pin id).
            val existing = map.annotations.filterIsInstance<MKPointAnnotation>()
            val desired = pins.map { it.id }.toSet()
            existing.filter { it.title !in desired }.forEach { map.removeAnnotation(it) }
            val have = existing.mapNotNull { it.title }.toSet()
            pins.filter { it.id !in have }.forEach { p ->
                val a = MKPointAnnotation()
                a.setCoordinate(CLLocationCoordinate2DMake(p.lat, p.lng))
                a.setTitle(p.id)
                map.addAnnotation(a)
            }
            if (!framed && pins.isNotEmpty()) {
                map.showAnnotations(map.annotations, animated = false)
                framed = true
            }
            // Restyle + recenter/zoom when the focused pin changes (Bo focusRegion).
            if (focusedPinId != lastFocused) {
                lastFocused = focusedPinId
                map.annotations.filterIsInstance<MKPointAnnotation>().forEach { a ->
                    map.viewForAnnotation(a)?.setImage(dotImage(a.title == focusedPinId))
                }
                val target = pins.firstOrNull { it.id == focusedPinId }
                if (target != null) {
                    val nearest = pins
                        .map { it to hypot(it.lat - target.lat, it.lng - target.lng) }
                        .sortedBy { it.second }
                        .take(6)
                        .map { it.first }
                    val maxD = nearest.maxOfOrNull { maxOf(abs(it.lat - target.lat), abs(it.lng - target.lng)) } ?: 0.0
                    val meters = (maxD * 111_000.0 * 2.6).coerceIn(1600.0, 20_000.0)
                    map.setRegion(
                        MKCoordinateRegionMakeWithDistance(
                            CLLocationCoordinate2DMake(target.lat, target.lng),
                            meters,
                            meters,
                        ),
                        animated = true,
                    )
                }
            }
        },
    )
}
