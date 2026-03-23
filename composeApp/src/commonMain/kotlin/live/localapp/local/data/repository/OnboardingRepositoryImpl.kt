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
    @SerialName("venue_state") val venueState: String? = null,
    @SerialName("venue_name") val venueName: String? = null,
    @SerialName("venue_lat") val venueLat: Double? = null,
    @SerialName("venue_lng") val venueLng: Double? = null,
    val category: String,
    val subcategory: String? = null,
)

@Serializable
private data class VenueRankDto(
    val name: String,
    @SerialName("public_interest_rank") val publicInterestRank: Int? = null,
)

@Serializable
private data class VenueCityStateDto(
    val city: String,
    val state: String? = null,
)

class OnboardingRepositoryImpl(
    private val supabaseClient: SupabaseClient,
) : OnboardingRepository {

    override suspend fun loadOnboardingData(
        city: String,
        userLat: Double,
        userLng: Double,
    ): OnboardingData {
        // Paginate to get all events (Supabase caps at 1000 per request)
        val events = mutableListOf<EventSummaryDto>()
        var offset = 0
        val pageSize = 1000
        while (true) {
            val page = supabaseClient.from("events")
                .select(columns = Columns.list("id", "venue_city", "venue_state", "venue_name", "venue_lat", "venue_lng", "category", "subcategory")) {
                    filter { eq("city", city) }
                    range(offset.toLong(), (offset + pageSize - 1).toLong())
                }
                .decodeList<EventSummaryDto>()
            events.addAll(page)
            if (page.size < pageSize) break
            offset += pageSize
        }

        // Get state codes from venues table (more reliable than events)
        val venueStates = try {
            supabaseClient.from("venues")
                .select(columns = Columns.list("city", "state")) {
                    filter { eq("is_active", true) }
                }
                .decodeList<VenueCityStateDto>()
                .groupBy { it.city }
                .mapValues { (_, venues) ->
                    venues.mapNotNull { it.state }
                        .groupingBy { it }.eachCount()
                        .maxByOrNull { it.value }?.key
                }
        } catch (_: Exception) {
            emptyMap()
        }

        // Only count meaningful upcoming events per city (exclude always_on, no-public-interest)
        val upcomingByCity = events
            .filter { it.category != "always_on" && it.subcategory != "no-public-interest" }
            .groupBy { it.venueCity }
            .mapValues { it.value.size }

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
                NeighborhoodInfo(
                    name = cityName,
                    distanceMiles = dist,
                    stateCode = venueStates[cityName],
                    eventCount = upcomingByCity[cityName] ?: 0,
                )
            }
            .sortedBy { it.distanceMiles ?: Double.MAX_VALUE }

        // Get venue ranks from venues table
        val venueRanks = try {
            supabaseClient.from("venues")
                .select(columns = Columns.list("name", "public_interest_rank")) {
                    filter { eq("is_active", true) }
                }
                .decodeList<VenueRankDto>()
                .associate { it.name.lowercase() to (it.publicInterestRank ?: 0) }
        } catch (_: Exception) {
            emptyMap()
        }

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
                    publicInterestRank = venueRanks[name.lowercase()] ?: 0,
                )
            }
            .sortedBy { it.distanceMiles ?: Double.MAX_VALUE }

        val normalizeSubcategory = mapOf(
            "museum" to "museums",
            "library" to "libraries",
            "tour" to "tours",
            "brewery" to "food",
            "restaurant" to "food",
            "cafe" to "food",
            "bakery" to "food",
            "park" to "outdoors",
            "stadium" to "sports",
            "community" to "other",
        )

        val categoryCounts = events
            .groupBy { normalizeSubcategory[(it.subcategory ?: "other").lowercase()] ?: (it.subcategory ?: "other").lowercase() }
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
        val now = kotlin.time.Clock.System.now().toString()
        val events = supabaseClient.from("events")
            .select {
                filter {
                    eq("city", city)
                    eq("is_active", true)
                    gte("start_at", now)
                    neq("subcategory", "no-public-interest")
                    neq("category", "always_on")
                }
                order("start_at", Order.ASCENDING)
                limit(500)
            }
            .decodeList<EventDto>()
            .map { it.toDomain() }

        // Filter by user's selected categories if any
        val catFiltered = if (categories.isNullOrEmpty()) {
            events
        } else {
            events.filter { (it.subcategory ?: "other").lowercase() in categories }
        }

        return catFiltered
            .map { event ->
                val dist = if (event.venueLat != null && event.venueLng != null) {
                    distanceMiles(userLat, userLng, event.venueLat, event.venueLng)
                } else null
                event.copy(distanceMiles = dist)
            }
            .sortedWith(
                compareByDescending<Event> {
                    // Prioritize: user's neighborhoods/venues, has image, proximity
                    var score = 0
                    if (!neighborhoods.isNullOrEmpty() && it.venueCity in neighborhoods) score += 3
                    if (!venueNames.isNullOrEmpty() && it.venueName in venueNames) score += 3
                    if (it.imageUrl != null) score += 2
                    if (it.subcategory != "other") score += 1
                    score
                }.thenBy { it.distanceMiles ?: Double.MAX_VALUE }
            )
            .take(limit)
    }
}
