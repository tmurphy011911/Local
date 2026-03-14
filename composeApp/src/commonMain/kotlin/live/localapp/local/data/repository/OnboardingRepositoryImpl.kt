package live.localapp.local.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import live.localapp.local.data.remote.dto.EventDto
import live.localapp.local.data.remote.dto.toDomain
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.model.distanceMiles
import live.localapp.local.domain.repository.NeighborhoodInfo
import live.localapp.local.domain.repository.OnboardingData
import live.localapp.local.domain.repository.OnboardingRepository
import live.localapp.local.domain.repository.VenueSummary

@Serializable
private data class EventSummaryDto(
    val id: String,
    @SerialName("venue_city") val venueCity: String,
    @SerialName("venue_name") val venueName: String? = null,
    @SerialName("venue_lat") val venueLat: Double? = null,
    @SerialName("venue_lng") val venueLng: Double? = null,
    val category: String,
    val subcategory: String? = null,
)

class OnboardingRepositoryImpl(
    private val supabaseClient: SupabaseClient,
) : OnboardingRepository {

    override suspend fun loadOnboardingData(
        city: String,
        userLat: Double,
        userLng: Double,
    ): OnboardingData {
        val events = supabaseClient.from("events")
            .select(columns = Columns.list("id", "venue_city", "venue_name", "venue_lat", "venue_lng", "category", "subcategory")) {
                filter { eq("city", city) }
            }
            .decodeList<EventSummaryDto>()

        // Sort neighborhoods by proximity: compute centroid lat/lng per city
        val neighborhoods = events
            .groupBy { it.venueCity }
            .map { (cityName, group) ->
                val withCoords = group.filter { it.venueLat != null && it.venueLng != null }
                val dist = if (withCoords.isNotEmpty()) {
                    val avgLat = withCoords.sumOf { it.venueLat!! } / withCoords.size
                    val avgLng = withCoords.sumOf { it.venueLng!! } / withCoords.size
                    distanceMiles(userLat, userLng, avgLat, avgLng)
                } else null
                NeighborhoodInfo(name = cityName, distanceMiles = dist)
            }
            .sortedBy { it.distanceMiles ?: Double.MAX_VALUE }

        // Group by venue name, take first event with coordinates for location
        val venueNames = events
            .filter { it.venueName != null }
            .groupBy { it.venueName!! }
            .map { (name, group) ->
                val rep = group.firstOrNull { it.venueLat != null && it.venueLng != null } ?: group.first()
                val dist = if (rep.venueLat != null && rep.venueLng != null) {
                    distanceMiles(userLat, userLng, rep.venueLat, rep.venueLng)
                } else null
                VenueSummary(
                    name = name,
                    city = rep.venueCity,
                    distanceMiles = dist,
                )
            }
            .sortedBy { it.distanceMiles ?: Double.MAX_VALUE }

        val categoryCounts = events
            .groupBy { (it.subcategory ?: "other").lowercase() }
            .mapValues { it.value.size }

        return OnboardingData(
            eventCount = events.size,
            venueCount = venueNames.size,
            neighborhoods = neighborhoods,
            venueNames = venueNames,
            categoryCounts = categoryCounts,
        )
    }

    override suspend fun getTopEvents(
        city: String,
        neighborhoods: Set<String>?,
        venueNames: Set<String>?,
        categories: Set<String>?,
        userLat: Double,
        userLng: Double,
        limit: Int,
    ): List<Event> {
        val events = supabaseClient.from("events")
            .select {
                filter {
                    eq("city", city)
                }
                order("start_at", Order.ASCENDING)
                limit(200)
            }
            .decodeList<EventDto>()
            .map { it.toDomain() }

        val filtered = if (categories.isNullOrEmpty()) {
            events
        } else {
            events.filter { (it.subcategory ?: "other").lowercase() in categories }
        }

        return filtered
            .map { event ->
                val dist = if (event.venueLat != null && event.venueLng != null) {
                    distanceMiles(userLat, userLng, event.venueLat, event.venueLng)
                } else null
                event.copy(distanceMiles = dist)
            }
            .sortedWith(
                compareByDescending<Event> {
                    val inNeighborhood = !neighborhoods.isNullOrEmpty() && it.venueCity in neighborhoods
                    val atVenue = !venueNames.isNullOrEmpty() && it.venueName in venueNames
                    if (inNeighborhood || atVenue) 1 else 0
                }.thenBy { it.distanceMiles ?: Double.MAX_VALUE }
            )
            .take(limit)
    }
}
