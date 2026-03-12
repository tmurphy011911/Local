package live.localapp.local.domain.model

import kotlin.math.*

data class UserLocation(
    val lat: Double,
    val lng: Double,
    val displayName: String,
)

/** Haversine distance in miles between two lat/lng points */
fun distanceMiles(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val earthRadiusMiles = 3958.8
    val dLat = (lat2 - lat1) * PI / 180.0
    val dLng = (lng2 - lng1) * PI / 180.0
    val rLat1 = lat1 * PI / 180.0
    val rLat2 = lat2 * PI / 180.0
    val a = sin(dLat / 2).pow(2) +
        cos(rLat1) * cos(rLat2) * sin(dLng / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return earthRadiusMiles * c
}
