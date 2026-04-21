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
    val city: String? = null,
    @SerialName("public_interest_rank") val publicInterestRank: Int? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
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
        // Map user city to metro area (events.city is the metro, not the venue city)
        val metro = cityToMetro(city)
        println("OnboardingRepo: city='$city' metro='$metro' lat=$userLat lng=$userLng")

        // Use 75-mile geo-radius instead of metro city name
        val radiusMiles = 75.0
        val latDelta = radiusMiles / 69.0
        val lngDelta = radiusMiles / 50.0
        var offset = 0
        val pageSize = 1000
        while (true) {
            val page = supabaseClient.from("events")
                .select(columns = Columns.list("id", "venue_city", "venue_state", "venue_name", "venue_lat", "venue_lng", "category", "subcategory")) {
                    filter {
                        eq("is_active", true)
                        gte("venue_lat", userLat - latDelta)
                        lte("venue_lat", userLat + latDelta)
                        gte("venue_lng", userLng - lngDelta)
                        lte("venue_lng", userLng + lngDelta)
                    }
                    range(offset.toLong(), (offset + pageSize - 1).toLong())
                }
                .decodeList<EventSummaryDto>()
            events.addAll(page)
            if (page.size < pageSize) break
            offset += pageSize
        }
        println("OnboardingRepo: loaded ${events.size} events for metro='$metro'")

        // Get state codes from venues table (more reliable than events)
        // Geo-filter to match the event radius — no need for the full table
        val venueStates = try {
            supabaseClient.from("venues")
                .select(columns = Columns.list("city", "state")) {
                    filter {
                        eq("is_active", true)
                        gte("lat", userLat - latDelta)
                        lte("lat", userLat + latDelta)
                        gte("lng", userLng - lngDelta)
                        lte("lng", userLng + lngDelta)
                    }
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

        // Get venue ranks + photos — geo-filtered to match event radius
        val venueRanks = try {
            supabaseClient.from("venues")
                .select(columns = Columns.list("name", "city", "public_interest_rank", "photo_url")) {
                    filter {
                        eq("is_active", true)
                        gte("lat", userLat - latDelta)
                        lte("lat", userLat + latDelta)
                        gte("lng", userLng - lngDelta)
                        lte("lng", userLng + lngDelta)
                    }
                }
                .decodeList<VenueRankDto>()
                .associateBy { it.name.lowercase() }
        } catch (_: Exception) {
            emptyMap()
        }

        // Build city → photo map from top venue photos (most reliable, actual places in the app)
        val cityPhotoMap = mutableMapOf<String, String>()
        venueRanks.values
            .filter { it.city != null && it.photoUrl != null }
            .sortedByDescending { it.publicInterestRank ?: 0 }
            .forEach { v ->
                if (v.city!! !in cityPhotoMap) cityPhotoMap[v.city] = v.photoUrl!!
            }

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
                    photoUrl = cityPhotoMap[cityName],
                )
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
                val venueData = venueRanks[name.lowercase()]
                VenueSummary(
                    name = name,
                    city = rep.venueCity,
                    distanceMiles = dist,
                    publicInterestRank = venueData?.publicInterestRank ?: 0,
                    photoUrl = venueData?.photoUrl,
                )
            }
            .sortedBy { it.distanceMiles ?: Double.MAX_VALUE }

        // Merge category + subcategory into user-facing categories
        val validCategories = setOf("music", "food", "arts", "sports", "comedy", "outdoors", "nightlife", "theatre", "museums", "tours", "libraries", "for-kids", "film")
        val excludeRaw = setOf("always_on", "upcoming", "other", "no-public-interest", "null", "miscellaneous")
        val normalizeMap = mapOf(
            "bar_events" to "nightlife", "bar" to "nightlife",
            "museum" to "museums", "library" to "libraries",
            "rock" to "music", "pop" to "music", "jazz" to "music", "country" to "music",
            "metal" to "music", "alternative" to "music", "r&b" to "music", "hip-hop/rap" to "music",
            "baseball" to "sports", "football" to "sports", "basketball" to "sports",
            "hockey" to "sports", "soccer" to "sports",
            "park" to "outdoors", "fairs & festivals" to "outdoors",
            "brewery" to "food", "restaurant" to "food", "cafe" to "food", "winery" to "food",
            "community" to "outdoors",
        )

        fun resolveCategory(event: EventSummaryDto): String? {
            // Prefer subcategory if it maps to a valid category, otherwise use category
            val sub = (event.subcategory ?: "").lowercase()
            val cat = event.category.lowercase()
            if (sub in excludeRaw && cat in excludeRaw) return null
            val mappedSub = normalizeMap[sub] ?: sub
            if (mappedSub in validCategories) return mappedSub
            val mappedCat = normalizeMap[cat] ?: cat
            if (mappedCat in validCategories) return mappedCat
            return null
        }

        // Single pass: resolve categories and build venue-by-category map simultaneously
        val categoryCounts = mutableMapOf<String, Int>()
        val venuesByCategory = mutableMapOf<String, MutableList<VenueRankDto>>()
        for (event in events) {
            val resolved = resolveCategory(event) ?: continue
            categoryCounts[resolved] = (categoryCounts[resolved] ?: 0) + 1
            val venueName = event.venueName ?: continue
            val venueData = venueRanks[venueName.lowercase()] ?: continue
            if (venueData.photoUrl == null) continue
            venuesByCategory.getOrPut(resolved) { mutableListOf() }.add(venueData)
        }
        // Filter out categories with fewer than 3 events
        val filteredCategoryCounts = categoryCounts.filter { it.value >= 3 }
        val usedPhotos = mutableSetOf<String>()
        val categoryPhotos = mutableMapOf<String, String>()
        // Sort categories by count so popular ones get first pick of photos
        for (cat in filteredCategoryCounts.keys.sortedByDescending { filteredCategoryCounts[it] ?: 0 }) {
            val candidates = venuesByCategory[cat]
                ?.sortedByDescending { it.publicInterestRank ?: 0 }
                ?: continue
            val photo = candidates.firstOrNull { it.photoUrl!! !in usedPhotos }?.photoUrl
            if (photo != null) {
                categoryPhotos[cat] = photo
                usedPhotos.add(photo)
            }
        }

        return OnboardingData(
            eventCount = events.size,
            venueCount = venueNames.size,
            neighborhoods = neighborhoods,
            venueNames = venueNames,
            categoryCounts = filteredCategoryCounts,
            categoryPhotos = categoryPhotos,
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
        val metro = cityToMetro(city)
        val events = supabaseClient.from("events")
            .select {
                filter {
                    eq("city", metro)
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

/** Map a specific city name to its metro area key used in the events.city column. */
private fun cityToMetro(city: String): String {
    val bostonMetro = setOf(
        "boston", "cambridge", "somerville", "brookline", "waltham", "newton",
        "quincy", "medford", "malden", "everett", "revere", "chelsea",
        "arlington", "belmont", "watertown", "lexington", "concord",
        "needham", "wellesley", "natick", "framingham", "dedham",
        "milton", "braintree", "weymouth", "norwood", "canton",
        "stoneham", "woburn", "burlington", "bedford", "lincoln",
        "sudbury", "wayland", "weston", "dover", "westwood",
        "hingham", "cohasset", "scituate", "marshfield", "duxbury",
        "plymouth", "barnstable", "falmouth", "chatham", "provincetown",
        "salem", "marblehead", "gloucester", "rockport", "ipswich",
        "newburyport", "lowell", "lawrence", "haverhill", "andover",
        "worcester", "auburn", "shrewsbury", "westborough", "grafton",
        "sturbridge", "webster", "southbridge",
        "springfield", "northampton", "amherst", "holyoke", "chicopee",
        "west springfield", "westfield", "easthampton",
        "providence", "warwick", "cranston", "pawtucket", "woonsocket",
        "newport", "bristol",
        "portland", "south portland", "scarborough",
        "hartford", "new haven", "stamford", "bridgeport", "waterbury",
        "new london", "mystic", "norwich",
    )
    val troyMetro = setOf("troy", "albany", "schenectady", "saratoga springs")

    return when (city.lowercase().trim()) {
        in bostonMetro -> "boston"
        in troyMetro -> "troy"
        else -> city.lowercase().trim()
    }
}
