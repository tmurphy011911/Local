package live.localapp.local.domain.repository

import live.localapp.local.domain.model.Event

data class NeighborhoodInfo(
    val name: String,
    val distanceMiles: Double? = null,
    val stateCode: String? = null,
    val eventCount: Int = 0,
) {
    val distanceDisplay: String
        get() = when {
            distanceMiles == null -> ""
            distanceMiles < 0.1 -> "Here"
            distanceMiles < 10 -> {
                val whole = distanceMiles.toInt()
                val frac = ((distanceMiles - whole) * 10).toInt()
                "$whole.$frac mi"
            }
            else -> "${distanceMiles.toInt()} mi"
        }
}

data class OnboardingData(
    val eventCount: Int,
    val venueCount: Int,
    val neighborhoods: List<NeighborhoodInfo>,
    val venueNames: List<VenueSummary>,
    val categoryCounts: Map<String, Int>,
)

data class VenueSummary(
    val name: String,
    val city: String,
    val distanceMiles: Double? = null,
    val publicInterestRank: Int? = null,
    val photoUrl: String? = null,
) {
    val distanceDisplay: String
        get() = when {
            distanceMiles == null -> ""
            distanceMiles < 0.1 -> "Here"
            distanceMiles < 10 -> {
                val whole = distanceMiles.toInt()
                val frac = ((distanceMiles - whole) * 10).toInt()
                "$whole.$frac mi"
            }
            else -> "${distanceMiles.toInt()} mi"
        }
}

interface OnboardingRepository {
    suspend fun loadOnboardingData(city: String, userLat: Double, userLng: Double): OnboardingData
    suspend fun getTopEvents(
        city: String,
        neighborhoods: Set<String>? = null,
        venueNames: Set<String>? = null,
        categories: Set<String>?,
        userLat: Double,
        userLng: Double,
        limit: Int = 5,
    ): List<Event>
}
