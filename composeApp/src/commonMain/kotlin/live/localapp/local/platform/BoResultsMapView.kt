package live.localapp.local.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import live.localapp.local.domain.model.Event

/**
 * One map pin = one venue (a distinct rounded coordinate). Multiple events at
 * the same venue collapse into a single pin whose [eventIds] the carousel
 * disambiguates — mirrors the iOS `pinGroups` behaviour in ChatView.swift.
 */
data class MapPin(
    /** Stable id = the rounded "lat,lng" coordinate key. */
    val id: String,
    val lat: Double,
    val lng: Double,
    /** All events (in tray order) that live at this venue. */
    val eventIds: List<String>,
)

/**
 * Group a day/category group's hydrated events into one pin per venue.
 * Events without a usable coordinate are dropped (they can't be placed on the
 * map — matching the Bo search hard-radius guard that already excludes
 * null-coordinate rows). Insertion order is preserved so pins stay stable
 * across recompositions.
 */
fun venuePins(events: List<Event>): List<MapPin> {
    fun round4(v: Double): Double = kotlin.math.round(v * 10_000.0) / 10_000.0
    val byCoord = LinkedHashMap<String, MutableList<Event>>()
    for (ev in events) {
        val lat = ev.venueLat ?: ev.eventLat ?: continue
        val lng = ev.venueLng ?: ev.eventLng ?: continue
        val key = "${round4(lat)},${round4(lng)}"
        byCoord.getOrPut(key) { mutableListOf() }.add(ev)
    }
    return byCoord.map { (key, evs) ->
        val first = evs.first()
        MapPin(
            id = key,
            lat = first.venueLat ?: first.eventLat ?: 0.0,
            lng = first.venueLng ?: first.eventLng ?: 0.0,
            eventIds = evs.map { it.id },
        )
    }
}

/**
 * Platform map surface for the Bo results component. Android renders a Google
 * Map (maps-compose); iOS is a no-op — the iOS chat is native SwiftUI
 * (ChatView.swift) with its own MapKit results map, so this Compose path never
 * runs there (it exists only to satisfy the expect/actual contract). Mirrors
 * the [PlanMapHero] split.
 *
 * The camera frames [focusedPinId] plus its nearest neighbours; tapping a pin
 * calls [onPinTap] with that pin's id. Focus/selection state lives in the
 * shared caller (BoResultsMap) so the carousel and map stay in lock-step.
 */
@Composable
expect fun BoResultsMapView(
    pins: List<MapPin>,
    focusedPinId: String?,
    onPinTap: (pinId: String) -> Unit,
    modifier: Modifier,
)
