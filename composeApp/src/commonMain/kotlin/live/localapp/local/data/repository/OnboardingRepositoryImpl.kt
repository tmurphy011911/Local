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
    @SerialName("start_at") val startAt: String? = null,
)

@Serializable
private data class VenueRankDto(
    val id: String? = null,
    val name: String,
    val city: String? = null,
    @SerialName("public_interest_rank") val publicInterestRank: Int? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
)

@Serializable
private data class VenueFullDto(
    val id: String? = null,
    val name: String,
    val city: String? = null,
    val state: String? = null,
    @SerialName("public_interest_rank") val publicInterestRank: Int? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
)

class OnboardingRepositoryImpl(
    private val supabaseClient: SupabaseClient,
) : OnboardingRepository {

    override suspend fun loadOnboardingData(
        city: String,
        userLat: Double,
        userLng: Double,
    ): OnboardingData {
        println("OnboardingRepo: city='$city' lat=$userLat lng=$userLng")

        // Tightened from 75 → 30 miles. 75mi bbox was 150x150mi (~22,500 sq mi)
        // of unindexed lat/lng scan, reliably hitting the 8s Postgres statement
        // timeout cold. 30mi covers all the Boston-metro neighborhoods that
        // actually surface on the screen (Waltham→Boston is ~10mi) and shrinks
        // the scan ~6×, keeping queries under 2s cold.
        val radiusMiles = 30.0
        val latDelta = radiusMiles / 69.0
        val lngDelta = radiusMiles / 50.0

        // Single consolidated venues fetch in the 75mi radius — used for state codes,
        // ranks/photos, and (in Kotlin) to filter the metro's events to those near us.
        val venuesInRadius: List<VenueFullDto> = try {
            val all = mutableListOf<VenueFullDto>()
            var vOffset = 0
            val vPageSize = 1000
            while (true) {
                val page = supabaseClient.from("venues")
                    .select(columns = Columns.list("id", "name", "city", "state", "public_interest_rank", "photo_url")) {
                        filter {
                            eq("is_active", true)
                            gte("lat", userLat - latDelta)
                            lte("lat", userLat + latDelta)
                            gte("lng", userLng - lngDelta)
                            lte("lng", userLng + lngDelta)
                        }
                        range(vOffset.toLong(), (vOffset + vPageSize - 1).toLong())
                    }
                    .decodeList<VenueFullDto>()
                all.addAll(page)
                if (page.size < vPageSize) break
                vOffset += vPageSize
            }
            all
        } catch (e: Exception) {
            println("OnboardingRepo: venues query failed: ${e.message}")
            emptyList()
        }
        println("OnboardingRepo: loaded ${venuesInRadius.size} venues in ${radiusMiles}mi radius")

        // Events: single query with server-side lat/lng bbox. Empirically 1.9s
        // cold / 0.4s warm against the Waltham 75mi bbox returning ~39K rows
        // worth of `id` count, capped to the 5000 soonest by start_at.
        //
        // Why no events.city filter: the crawler now writes town-level cities
        // (waltham/cambridge/medford/…) where the original onboarding code
        // assumed metro-level ("boston"). Chunked IN-list fan-outs over the
        // metro towns ran afoul of Supabase's connection pool — individual
        // queries that were 3s in isolation queued past the 8s Postgres
        // statement_timeout when 35 fired in parallel. The bbox filter
        // bypasses the city question entirely: pick up everything that
        // geographically belongs to the user's area, then let the venue.name
        // → venue.city lookup decide which curated neighborhood each event
        // counts toward.
        val nowIso = kotlin.time.Clock.System.now().toString()
        // Keyset pagination: PostgREST caps responses at 1000 rows. OFFSET
        // pagination times out cold for deeper pages because each page
        // re-scans from start. Cursor-based pagination on start_at runs the
        // index from where the previous page left off — each page is ~1s
        // warm regardless of depth. Sequential 5 pages ≈ 5s total wall-clock,
        // fits comfortably inside the loading-screen progress.
        val allMetroEvents: List<EventSummaryDto> = run {
            val acc = mutableListOf<EventSummaryDto>()
            var cursor = nowIso
            var pageNum = 0
            while (pageNum < EVENTS_PAGE_COUNT) {
                val page: List<EventSummaryDto> = try {
                    supabaseClient.from("events")
                        .select(columns = Columns.list("id", "venue_city", "venue_state", "venue_name", "venue_lat", "venue_lng", "category", "subcategory", "start_at")) {
                            filter {
                                eq("is_active", true)
                                gt("start_at", cursor)
                                gte("venue_lat", userLat - latDelta)
                                lte("venue_lat", userLat + latDelta)
                                gte("venue_lng", userLng - lngDelta)
                                lte("venue_lng", userLng + lngDelta)
                            }
                            order("start_at", Order.ASCENDING)
                            limit(EVENTS_PAGE_SIZE.toLong())
                        }
                        .decodeList<EventSummaryDto>()
                } catch (e: Exception) {
                    println("OnboardingRepo: events page $pageNum failed: ${e.message?.take(200)}")
                    emptyList()
                }
                if (page.isEmpty()) break
                acc.addAll(page)
                cursor = page.last().startAt ?: break
                pageNum++
                if (page.size < EVENTS_PAGE_SIZE) break
            }
            acc
        }
        println("OnboardingRepo: loaded ${allMetroEvents.size} upcoming events in 75mi bbox (keyset)")

        // Narrow to events near the user: keep if venue_lat/lng on the row is in
        // radius, OR if the event's venue_name is one we found in the radius
        // (catches the common "event row has null coords but venue has coords" case).
        val venueNamesInRadius = venuesInRadius.map { it.name.lowercase().trim() }.toHashSet()
        val events = allMetroEvents.filter { e ->
            val lat = e.venueLat
            val lng = e.venueLng
            val inGeoBox = lat != null && lng != null &&
                lat in (userLat - latDelta)..(userLat + latDelta) &&
                lng in (userLng - lngDelta)..(userLng + lngDelta)
            if (inGeoBox) return@filter true
            val name = e.venueName?.lowercase()?.trim() ?: return@filter false
            name in venueNamesInRadius
        }
        println("OnboardingRepo: ${events.size} events in ${radiusMiles}mi radius after Kotlin filter")

        // venue_name (lowercased) → curated venue row (city, state, photo, rank).
        // This is the single source of truth for "which neighborhood is this venue in"
        // — events.venue_city is heterogeneous (sometimes "Boston" for Waltham venues,
        // sometimes "Lexington (chamber)" qualifier-tagged) and can't be trusted for
        // grouping. The venues table is curated.
        val venuesByLowerName: Map<String, VenueFullDto> = venuesInRadius
            .associateBy { it.name.lowercase().trim() }

        // Skip any city value that has a parenthesized qualifier like
        // "Lexington (chamber)" or "Berkshires (visitor)" — those are tagging
        // conventions for satellite/touring venues, not real neighborhoods.
        fun cleanCity(c: String?): String? =
            c?.trim()?.takeIf { it.isNotBlank() && '(' !in it && ')' !in it }

        val venueRanks: Map<String, VenueRankDto> = venuesInRadius
            .associateBy({ it.name.lowercase() }, {
                VenueRankDto(
                    id = it.id,
                    name = it.name,
                    city = cleanCity(it.city),
                    publicInterestRank = it.publicInterestRank,
                    photoUrl = it.photoUrl,
                )
            })

        val venueStates: Map<String, String?> = venuesInRadius
            .mapNotNull { v -> cleanCity(v.city)?.let { it to v.state } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, states) ->
                states.filterNotNull()
                    .groupingBy { it }.eachCount()
                    .maxByOrNull { it.value }?.key
            }

        // Each event's neighborhood = its venue's curated city (not event.venueCity).
        // Events with no matching venue in the radius get dropped from per-city counts.
        fun eventCity(e: EventSummaryDto): String? {
            val key = e.venueName?.lowercase()?.trim() ?: return null
            return cleanCity(venuesByLowerName[key]?.city)
        }

        // Only count meaningful upcoming events per city (exclude always_on, no-public-interest)
        val upcomingByCity: Map<String, Int> = events
            .filter { it.category != "always_on" && it.subcategory != "no-public-interest" }
            .mapNotNull { eventCity(it) }
            .groupingBy { it }
            .eachCount()

        // Build city → photo map from top venue photos (most reliable, actual places in the app)
        val cityPhotoMap = mutableMapOf<String, String>()
        venueRanks.values
            .filter { it.city != null && it.photoUrl != null }
            .sortedByDescending { it.publicInterestRank ?: 0 }
            .forEach { v ->
                if (v.city!! !in cityPhotoMap) cityPhotoMap[v.city] = v.photoUrl!!
            }

        // Neighborhoods: keyed on venue.city, distance = centroid of matching venues.
        val venuesByCity: Map<String, List<VenueFullDto>> = venuesInRadius
            .mapNotNull { v -> cleanCity(v.city)?.let { it to v } }
            .groupBy({ it.first }, { it.second })

        val neighborhoods = venuesByCity
            .map { (cityName, group) ->
                // Need venue coords for distance; we already filtered venues by lat/lng
                // upstream, so the underlying rows exist in the DB even if we didn't
                // hydrate them on VenueFullDto. Estimate distance from any event with
                // coords whose venue is in this city, falling back to null.
                val sampleEvents = events.filter { eventCity(it) == cityName }
                val withCoords = sampleEvents.filter { it.venueLat != null && it.venueLng != null }
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
            .filter { it.eventCount > 0 }
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
                    id = venueData?.id,
                    name = name,
                    city = venueData?.city ?: cleanCity(rep.venueCity) ?: "",
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
        // Tightened from 75 → 30 miles. 75mi bbox was 150x150mi (~22,500 sq mi)
        // of unindexed lat/lng scan, reliably hitting the 8s Postgres statement
        // timeout cold. 30mi covers all the Boston-metro neighborhoods that
        // actually surface on the screen (Waltham→Boston is ~10mi) and shrinks
        // the scan ~6×, keeping queries under 2s cold.
        val radiusMiles = 30.0
        val latDelta = radiusMiles / 69.0
        val lngDelta = radiusMiles / 50.0
        // Server-side lat/lng bbox instead of events.city filter — see note
        // in loadOnboardingData. Single fast query, no fan-out.
        // Keyset pagination — see note in loadOnboardingData.
        val events = run {
            val acc = mutableListOf<Event>()
            var cursor = now
            var pageNum = 0
            while (pageNum < EVENTS_PAGE_COUNT) {
                val page: List<Event> = try {
                    supabaseClient.from("events")
                        .select {
                            filter {
                                eq("is_active", true)
                                gt("start_at", cursor)
                                gte("venue_lat", userLat - latDelta)
                                lte("venue_lat", userLat + latDelta)
                                gte("venue_lng", userLng - lngDelta)
                                lte("venue_lng", userLng + lngDelta)
                                neq("subcategory", "no-public-interest")
                                neq("category", "always_on")
                            }
                            order("start_at", Order.ASCENDING)
                            limit(EVENTS_PAGE_SIZE.toLong())
                        }
                        .decodeList<EventDto>()
                        .map { it.toDomain() }
                } catch (e: Exception) {
                    println("OnboardingRepo.getTopEvents: page $pageNum failed: ${e.message?.take(200)}")
                    emptyList()
                }
                if (page.isEmpty()) break
                acc.addAll(page)
                val lastStartAt = page.lastOrNull()?.startAt?.toString() ?: break
                cursor = lastStartAt
                pageNum++
                if (page.size < EVENTS_PAGE_SIZE) break
            }
            acc
        }

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

/**
 * Sequential keyset pagination for the bbox-filtered events query.
 *
 * PostgREST caps each response at 1000 rows (Supabase's `max-rows`). To
 * get the ~5K rows onboarding wants for accurate per-neighborhood counts
 * across the Boston metro, we use cursor-based pagination on start_at:
 * each page is `start_at > <previous-page's-last-start_at>` LIMIT 1000.
 *
 * Empirically:
 *   - Single page (cold cache): ~2-3s
 *   - Single page (warm): ~400ms-1s
 *   - 5 sequential keyset pages: ~5-9s wall-clock, ~1s per page after warmup
 *
 * Why not OFFSET-based parallel pagination: deeper OFFSET pages (e.g.
 * range 3000-3999) reliably hit Postgres' 8s statement_timeout cold,
 * because each page re-scans the bbox from the beginning. Keyset starts
 * exactly where the last page left off via the (start_at) index.
 *
 * History:
 * - Original: filter events by `events.city = "boston"`. Silently dropped
 *   town-keyed crawler rows (Waltham showed 1 event).
 * - Attempt 1: chunked IN-list of metro towns (~35 parallel queries).
 *   Hit statement timeouts under connection-pool pressure.
 * - Attempt 2: single query with server-side lat/lng bbox + limit 5000.
 *   PostgREST capped response at 1000.
 * - Attempt 3: 5 parallel pages via Range header. Deeper OFFSET pages
 *   timed out cold (5 hitting at once amplified the slow-OFFSET issue).
 * - Current: keyset pagination, sequential.
 */
private const val EVENTS_PAGE_SIZE = 1000
private const val EVENTS_PAGE_COUNT = 5
