package live.localapp.local.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlin.time.Duration.Companion.days
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import live.localapp.local.data.remote.dto.CarouselDto
import live.localapp.local.data.remote.dto.EventDto
import live.localapp.local.data.remote.dto.VenueDto
import live.localapp.local.data.remote.dto.toDomain
import live.localapp.local.domain.model.CarouselSection
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.model.distanceMiles
import live.localapp.local.Config
import live.localapp.local.domain.repository.CarouselRepository
import live.localapp.local.domain.repository.CategoryInfo
import live.localapp.local.domain.repository.SegmentMode
import live.localapp.local.domain.repository.UserPreferences

private sealed class EventFilter {
    data object None : EventFilter()
    data object Empty : EventFilter()
    data class Category(val value: String) : EventFilter()
    data class VenueLike(val pattern: String) : EventFilter()
    data class Subcategory(val value: String) : EventFilter()
    data object ExcludeKnown : EventFilter()
}

private data class VenueInfo(
    val rank: Int?,
    val photoUrl: String?,
    val description: String?,
)

class CarouselRepositoryImpl(
    private val supabaseClient: SupabaseClient,
) : CarouselRepository {

    /** Cached venue name → info (rank, photo, description) */
    private var venueCache: Map<String, VenueInfo> = emptyMap()

    /** Cached event pools per segment — fetched once, filtered in-memory */
    private var upcomingEventsCache: List<Event>? = null
    private var alwaysOnEventsCache: List<Event>? = null

    /** Raw sports events (no dedup/cleaning) for team carousels */
    private var rawSportsEventsCache: List<Event>? = null

    /** Cached carousels config from DB */
    private var carouselsConfigCache: List<CarouselDto>? = null

    override fun invalidateCaches() {
        upcomingEventsCache = null
        alwaysOnEventsCache = null
        rawSportsEventsCache = null
        carouselsConfigCache = null
        categoryCacheUpcoming = null
        categoryCacheAlwaysOn = null
        categoryOrder = null
        venueCache = emptyMap()
    }

    override suspend fun getCarousels(
        userLat: Double,
        userLng: Double,
        segment: SegmentMode,
        category: String?,
        userPreferences: UserPreferences?,
        locationName: String,
    ): List<CarouselSection> {
        val city = cityKeyFromLocation(userLat, userLng)

        // Load venue cache if empty
        if (venueCache.isEmpty()) {
            venueCache = fetchVenueCache()
        }

        // Derive the nearest city/town name for carousel titles
        val nearestCity = if (locationName.isNotBlank()) locationName
            else nearestCityName(userLat, userLng)

        return when (segment) {
            SegmentMode.UPCOMING -> getUpcomingCarousels(city, category, userLat, userLng, userPreferences, nearestCity)
            SegmentMode.ALWAYS_ON -> getAlwaysOnCarousels(city, category, userLat, userLng, userPreferences, nearestCity)
        }
    }

    /** Fetch and cache all upcoming events for this city */
    private suspend fun getUpcomingEventPool(city: String): List<Event> {
        upcomingEventsCache?.let { return it }
        val events = queryEvents(city, EventFilter.None, dateRange = "this_month", segment = "upcoming", limit = 1000)
            .let { cleanEvents(it) }
        upcomingEventsCache = events
        return events
    }

    /** Fetch and cache all always-on events for this city */
    private suspend fun getAlwaysOnEventPool(city: String): List<Event> {
        alwaysOnEventsCache?.let { return it }
        val events = queryEvents(city, EventFilter.None, segment = "always_on", limit = 1000)
            .let { cleanEvents(it) }
        alwaysOnEventsCache = events
        return events
    }

    /** Fetch raw sports events — no dedup or title cleaning, sorted by start date */
    private suspend fun getRawSportsPool(city: String): List<Event> {
        rawSportsEventsCache?.let { return it }
        val events = queryEvents(city, EventFilter.Category("sports"), dateRange = "this_month", segment = "upcoming", limit = 500)
            .sortedBy { it.startAt ?: "" }
        rawSportsEventsCache = events
        return events
    }

    /** Filter cached events in-memory instead of hitting the DB */
    private fun List<Event>.applyFilter(filter: EventFilter): List<Event> = when (filter) {
        is EventFilter.None -> this
        is EventFilter.Empty -> emptyList()
        is EventFilter.Category -> filter { it.subcategory?.lowercase() == filter.value }
        is EventFilter.VenueLike -> {
            val pattern = filter.pattern.replace("%", "").lowercase()
            filter { (it.venueName ?: "").lowercase().contains(pattern) }
        }
        is EventFilter.Subcategory -> filter { it.subSubcategory?.lowercase() == filter.value }
        is EventFilter.ExcludeKnown -> filter { (it.subcategory ?: "other").lowercase() !in knownSubcategories }
    }

    /** Fetch venue name → VenueInfo from venues table */
    private suspend fun fetchVenueCache(): Map<String, VenueInfo> {
        return try {
            supabaseClient.from("venues")
                .select {
                    filter {
                        eq("is_active", true)
                    }
                }
                .decodeList<VenueDto>()
                .associate {
                    it.name.lowercase() to VenueInfo(
                        rank = it.publicInterestRank,
                        photoUrl = it.photoUrl,
                        description = it.description,
                    )
                }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Map user lat/lng to the city key used in the database.
     *  All MA events currently use "boston" as the city key. */
    private fun cityKeyFromLocation(lat: Double, lng: Double): String {
        // All statewide events are stored under the "boston" city key
        return "boston"
    }

    /** Known cities/towns with lat/lng for nearest-city resolution */
    private data class CityCoord(val name: String, val lat: Double, val lng: Double)

    private val knownCities = listOf(
        CityCoord("Boston", 42.3601, -71.0589),
        CityCoord("Cambridge", 42.3736, -71.1097),
        CityCoord("Somerville", 42.3876, -71.0995),
        CityCoord("Brookline", 42.3318, -71.1212),
        CityCoord("Waltham", 42.3765, -71.2356),
        CityCoord("Worcester", 42.2626, -71.8023),
        CityCoord("Springfield", 42.1015, -72.5898),
        CityCoord("Lowell", 42.6334, -71.3162),
        CityCoord("New Bedford", 41.6362, -70.9342),
        CityCoord("Pittsfield", 42.4501, -73.2454),
        CityCoord("Northampton", 42.3251, -72.6412),
        CityCoord("Salem", 42.5195, -70.8967),
        CityCoord("Cape Cod", 41.6688, -70.2962),
        CityCoord("Providence", 41.8240, -71.4128),
        CityCoord("New York", 40.7128, -74.0060),
        CityCoord("Philadelphia", 39.9526, -75.1652),
        CityCoord("Natick", 42.2833, -71.3490),
        CityCoord("Framingham", 42.2793, -71.4162),
        CityCoord("Newton", 42.3370, -71.2092),
        CityCoord("Quincy", 42.2529, -71.0023),
    )

    /** Find the nearest known city name from coordinates */
    private fun nearestCityName(lat: Double, lng: Double): String {
        return knownCities.minByOrNull { city ->
            val dLat = city.lat - lat
            val dLng = city.lng - lng
            dLat * dLat + dLng * dLng
        }?.name ?: "Boston"
    }

    // ════════════════════════════════════════════════════════════════
    // UPCOMING — scheduled, one-time events (concerts, games, shows)
    // ════════════════════════════════════════════════════════════════

    private suspend fun getUpcomingCarousels(
        city: String,
        category: String?,
        userLat: Double,
        userLng: Double,
        prefs: UserPreferences?,
        nearestCity: String = "Boston",
    ): List<CarouselSection> {
        val filter = resolveFilter(category)
        if (filter is EventFilter.Empty) return emptyList()

        // Cache carousels config from DB
        if (carouselsConfigCache == null) {
            carouselsConfigCache = supabaseClient.from("carousels")
                .select {
                    filter {
                        eq("city", city)
                        eq("is_active", true)
                    }
                    order("position", Order.ASCENDING)
                }
                .decodeList<CarouselDto>()
        }
        val carousels = carouselsConfigCache!!

        // Get cached event pool and apply filter in-memory
        val allEvents = getUpcomingEventPool(city)
        val filtered = allEvents.applyFilter(filter)
            .map { it.withDistance(userLat, userLng) }
            .sortedBy { scoreForSort(it, prefs) }

        val seenEventIds = mutableSetOf<String>()
        val result = mutableListOf<CarouselSection>()

        // ── Personalized carousels (only on "For You" tab) ──
        if (filter is EventFilter.None) {
            // Position 1: Events for You This Week
            val weekCutoff = daysFromNowIso(7)
            val weekPool = filtered.filter { (it.startAt ?: "") <= weekCutoff }
            val ranked = rankForColdStart(weekPool, prefs, maxResults = carouselDisplayLimit)
            val backfilled = backfillToMinimum(ranked, seenEventIds, filtered)
            if (backfilled.isNotEmpty()) {
                backfilled.forEach { seenEventIds.add(it.id) }
                result.add(
                    CarouselSection(
                        slug = "top-events-personalized",
                        title = "Events for You This Week",
                        subtitle = null,
                        filterType = "personalized",
                        events = backfilled,
                    ),
                )
            }

            // Position 2: Plan Your Weekend (always Fri-Sun)
            val weekendStart = startOfNextFridayIso()
            val weekendEnd = endOfNextSundayIso()
            val weekendEvents = filtered
                .filter { val s = it.startAt ?: ""; s >= weekendStart && s < weekendEnd }
                .filter { it.id !in seenEventIds }
                .take(carouselDisplayLimit)
            if (weekendEvents.isNotEmpty()) {
                weekendEvents.forEach { seenEventIds.add(it.id) }
                result.add(
                    CarouselSection(
                        slug = "plan-your-weekend",
                        title = "Plan Your Weekend",
                        subtitle = null,
                        filterType = "weekend",
                        events = weekendEvents,
                    ),
                )
            }

            if (prefs != null) {
                // Favorite category carousels
                for (cat in prefs.selectedCategories) {
                    val catEvents = allEvents
                        .filter { it.subcategory?.lowercase() == cat.lowercase() }
                        .map { it.withDistance(userLat, userLng) }
                        .sortedBy { scoreForSort(it, prefs) }
                        .filter { it.id !in seenEventIds }
                        .take(carouselDisplayLimit)
                    if (catEvents.isEmpty()) continue
                    catEvents.forEach { seenEventIds.add(it.id) }
                    result.add(
                        CarouselSection(
                            slug = "fav-cat-${cat.lowercase()}",
                            title = "Top ${categoryDisplayName(cat)} Events This Month",
                            subtitle = null,
                            filterType = "favorite_category",
                            events = catEvents,
                        ),
                    )
                }

                // Favorite venue carousels
                for (venue in prefs.selectedVenues) {
                    val venueEvents = allEvents
                        .filter { (it.venueName ?: "").contains(venue, ignoreCase = true) }
                        .map { it.withDistance(userLat, userLng) }
                        .sortedBy { scoreForSort(it, prefs) }
                        .filter { it.id !in seenEventIds }
                        .take(carouselDisplayLimit)
                    if (venueEvents.isEmpty()) continue
                    venueEvents.forEach { seenEventIds.add(it.id) }
                    result.add(
                        CarouselSection(
                            slug = "fav-venue-${venue.lowercase().replace(" ", "-")}",
                            title = "Happening at $venue",
                            subtitle = null,
                            filterType = "favorite_venue",
                            events = venueEvents,
                        ),
                    )
                }
            }

            // Neighborhood carousels — always shown, based on current location
            val neighborhoods = buildList {
                add(nearestCity)
                prefs?.selectedNeighborhoods?.forEach { n ->
                    if (!n.equals(nearestCity, ignoreCase = true)) add(n)
                }
            }
            for (neighborhood in neighborhoods) {
                val neighborhoodEvents = allEvents
                    .filter { it.venueCity.contains(neighborhood, ignoreCase = true) }
                    .map { it.withDistance(userLat, userLng) }
                    .sortedBy { scoreForSort(it, prefs) }
                    .filter { it.id !in seenEventIds }
                    .take(carouselDisplayLimit)
                if (neighborhoodEvents.isEmpty()) continue
                neighborhoodEvents.forEach { seenEventIds.add(it.id) }
                result.add(
                    CarouselSection(
                        slug = "neighborhood-${neighborhood.lowercase().replace(" ", "-")}",
                        title = "Happening in $neighborhood",
                        subtitle = null,
                        filterType = "neighborhood",
                        events = neighborhoodEvents,
                    ),
                )
            }

            // Free events
            val freeEvents = filtered
                .filter { it.isFree && it.id !in seenEventIds }
                .take(carouselDisplayLimit)
            if (freeEvents.isNotEmpty()) {
                freeEvents.forEach { seenEventIds.add(it.id) }
                result.add(
                    CarouselSection(
                        slug = "free-events",
                        title = "Free Events Near You",
                        subtitle = null,
                        filterType = "free",
                        events = freeEvents,
                    ),
                )
            }
        }

        // ── Sports-specific carousels (pro teams + by sport) ──
        // Use raw (uncleaned) events so team games aren't lost to dedup/title cleaning
        if (filter is EventFilter.Category && filter.value == "sports") {
            val rawSports = getRawSportsPool(city)
            val sportsSeen = mutableSetOf<String>()
            val sportsCarousels = buildSportsCarouselsFromPool(rawSports, prefs, sportsSeen)
            result.addAll(sportsCarousels)
        }

        // ── Standard DB-driven carousels ──
        val standardCarousels = carousels.mapNotNull { carousel ->
            if (carousel.filterType == "category" && filter !is EventFilter.None && filter !is EventFilter.Category) {
                return@mapNotNull null
            }
            // On subcategory tabs, skip time-based DB carousels — dynamic sub-carousels
            // already provide better subcategory-scoped versions (e.g. "Coming Up: Comedy")
            if (carousel.filterType == "date_range" && filter is EventFilter.Category) {
                return@mapNotNull null
            }

            val events = resolveCarouselFromPool(filtered, carousel, filter, prefs)
                .filter { it.id !in seenEventIds }
                .take(carouselDisplayLimit)

            if (events.isEmpty()) return@mapNotNull null
            events.forEach { seenEventIds.add(it.id) }

            val title = if (filter is EventFilter.Category) {
                val catName = categoryDisplayName(filter.value)
                carousel.title
                    .replace("Top 10 This Week", "Top $catName Events This Week")
                    .replace("This Weekend", "$catName This Weekend")
                    .replace("Trending Now", "Trending $catName")
            } else {
                carousel.title
            }

            CarouselSection(
                slug = carousel.slug,
                title = title,
                subtitle = carousel.subtitle,
                filterType = carousel.filterType,
                events = events,
            )
        }

        result.addAll(standardCarousels)

        // ── Dynamic sub-carousels for any subcategory tab ──
        // These get the FULL filtered pool (not minus seenEventIds) so events
        // can appear across different carousel dimensions (time, type, venue, price).
        if (filter is EventFilter.Category && filter.value != "sports") {
            val dynamicCarousels = buildDynamicSubcarousels(
                filtered, filter.value, categoryDisplayName(filter.value),
            )
            result.addAll(dynamicCarousels)
        }

        // ── Subcategory "This Month" carousel (positioned after dynamic for depth) ──
        if (filter is EventFilter.Category) {
            val displayName = categoryDisplayName(filter.value)
            val cutoff = kotlin.time.Clock.System.now().plus(30.days).toString()
            val topEvents = filtered
                .filter { (it.startAt ?: "") <= cutoff && it.id !in seenEventIds }
                .take(carouselDisplayLimit)

            if (topEvents.isNotEmpty()) {
                topEvents.forEach { seenEventIds.add(it.id) }
                result.add(
                    CarouselSection(
                        slug = "top-${filter.value}-month",
                        title = "$displayName Events This Month",
                        subtitle = null,
                        filterType = "top_category_monthly",
                        events = topEvents,
                    ),
                )
            }
        }
        return result
    }

    /** Resolve a DB-driven carousel from the in-memory pool instead of hitting DB */
    private fun resolveCarouselFromPool(
        pool: List<Event>,
        carousel: CarouselDto,
        userFilter: EventFilter,
        prefs: UserPreferences?,
    ): List<Event> {
        return when (carousel.filterType) {
            "category" -> {
                val carouselCat = carousel.filterValue ?: return emptyList()
                if (userFilter is EventFilter.Category && userFilter.value != carouselCat) return emptyList()
                if (userFilter is EventFilter.None) {
                    pool.filter { it.subcategory?.lowercase() == carouselCat }
                } else {
                    pool // already filtered by subcategory
                }
            }
            "date_range" -> when (carousel.filterValue) {
                "this_weekend" -> {
                    val start = startOfNextFridayIso()
                    val end = endOfNextSundayIso()
                    pool.filter { val s = it.startAt ?: ""; s >= start && s <= end }
                }
                else -> {
                    val days = when (carousel.filterValue) {
                        "this_week" -> 7
                        "next_2_weeks" -> 14
                        "this_month" -> 30
                        else -> 7
                    }
                    val cutoff = kotlin.time.Clock.System.now().plus(days.days).toString()
                    pool.filter { (it.startAt ?: "") <= cutoff }
                }
            }
            "tag" -> when (carousel.filterValue) {
                "free" -> pool.filter { it.isFree }
                else -> pool
            }
            "top10_weekly" -> {
                val cutoff = kotlin.time.Clock.System.now().plus(7.days).toString()
                val weekPool = pool.filter { (it.startAt ?: "") <= cutoff }
                rankForColdStart(weekPool, prefs, maxResults = carouselDisplayLimit)
            }
            "trending" -> pool
            else -> emptyList()
        }
    }

    // ── Minimum carousel size ──

    private val minCarouselSize = 15

    /**
     * If [events] has fewer than [minCarouselSize] items, backfill with
     * filler events that haven't been used yet. This ensures every displayed
     * carousel has at least 15 events — but never reduces the number of
     * carousels shown.
     */
    private fun backfillToMinimum(
        events: List<Event>,
        seenEventIds: Set<String>,
        fillerPool: List<Event>,
    ): List<Event> {
        if (events.size >= minCarouselSize) return events
        if (events.isEmpty()) return emptyList()

        val result = events.toMutableList()
        val usedIds = seenEventIds.toMutableSet()
        result.forEach { usedIds.add(it.id) }

        for (filler in fillerPool) {
            if (result.size >= minCarouselSize) break
            if (filler.id !in usedIds) {
                result.add(filler)
                usedIds.add(filler.id)
            }
        }
        return result
    }

    /**
     * Dynamically build additional carousels for a subcategory tab by grouping
     * remaining events by sub_subcategory, popular venues, and time windows.
     */
    /**
     * Build dynamic sub-carousels for a subcategory tab by slicing events across
     * independent dimensions: time window, sub_subcategory, venue, and price.
     *
     * Events CAN appear in multiple carousels across different dimensions (e.g.
     * a standup show this weekend appears in both "Comedy This Weekend" AND
     * "Standup"). Within each dimension, events are deduped.
     */
    private fun buildDynamicSubcarousels(
        events: List<Event>,
        subcategory: String,
        displayName: String,
    ): List<CarouselSection> {
        val result = mutableListOf<CarouselSection>()

        fun addCarousel(slug: String, title: String, items: List<Event>) {
            val capped = items.take(carouselDisplayLimit)
            if (capped.size < 2) return
            result.add(CarouselSection(slug, title, null, "dynamic_sub", capped))
        }

        // ── Time-based dimension ──
        val weekendStart = startOfNextFridayIso()
        val weekendEnd = endOfNextSundayIso()
        val comingSoonCutoff = daysFromNowIso(10)

        // Coming Up first — next 10 days
        val comingSoonEvents = events.filter {
            val s = it.startAt ?: ""; s <= comingSoonCutoff
        }
        addCarousel("$subcategory-coming-up", "Coming Up: $displayName", comingSoonEvents)

        // This Weekend
        val weekendEvents = events.filter {
            val s = it.startAt ?: ""; s >= weekendStart && s < weekendEnd
        }
        addCarousel("$subcategory-weekend", "$displayName This Weekend", weekendEvents)

        // ── Sub-subcategory dimension ──
        val bySubSub = events
            .groupBy { (it.subSubcategory ?: "").lowercase() }
            .filter { it.key.isNotEmpty() }
            .entries
            .sortedByDescending { it.value.size }

        for ((subSub, subSubEvents) in bySubSub) {
            val subSubDisplay = subSubcategoryDisplayName(subSub)
            addCarousel("$subcategory-$subSub", subSubDisplay, subSubEvents)
        }

        // ── Venue dimension (venues with 2+ events) ──
        val byVenue = events
            .filter { !it.venueName.isNullOrBlank() }
            .groupBy { it.venueName!!.lowercase() }
            .filter { it.value.size >= 2 }
            .entries
            .sortedByDescending { it.value.size }
            .take(6)

        for ((_, venueEvents) in byVenue) {
            val venueName = venueEvents.first().venueName ?: continue
            addCarousel(
                "$subcategory-venue-${venueName.lowercase().replace(" ", "-").take(30)}",
                "At $venueName",
                venueEvents,
            )
        }

        // ── Price dimension ──
        val freeEvents = events.filter { it.isFree }
        addCarousel("$subcategory-free", "Free $displayName Events", freeEvents)

        return result
    }

    /** Map subcategory key to display name for carousel titles */
    private fun categoryDisplayName(key: String): String = when (key.lowercase()) {
        "music" -> "Music"
        "food" -> "Food & Drink"
        "arts" -> "Arts"
        "theatre" -> "Theatre"
        "sports" -> "Sports"
        "comedy" -> "Comedy"
        "outdoors" -> "Outdoors"
        "museums" -> "Museum"
        "tours" -> "Tour"
        "libraries" -> "Library"
        "for-kids" -> "Kids"
        else -> key.replaceFirstChar { it.uppercase() }
    }

    // ── Sports-specific carousels: pro teams first, then by sport ──

    private data class SportsCarouselDef(val slug: String, val title: String, val subSubcategory: String?)

    private data class ProTeamDef(val slug: String, val title: String, val keyword: String, val excludeKeywords: List<String> = emptyList())

    private val bostonProTeams = listOf(
        ProTeamDef("sports-bruins", "Boston Bruins", "bruins", listOf("providence")),
        ProTeamDef("sports-celtics", "Boston Celtics", "celtics"),
        ProTeamDef("sports-red-sox", "Boston Red Sox", "red sox", listOf("worcester", "woosox", "portland", "salem", "greenville")),
        ProTeamDef("sports-patriots", "New England Patriots", "new england patriots"),
        ProTeamDef("sports-revolution", "New England Revolution", "new england revolution"),
    )

    /** Minor league / affiliate teams get their own carousel */
    private val minorLeagueTeams = listOf(
        ProTeamDef("sports-woosox", "Worcester Red Sox", "worcester red sox"),
        ProTeamDef("sports-woosox2", "Worcester Red Sox", "woosox"),
    )

    /** Area colleges — matched by school name or mascot in title/venue */
    private val areaColleges = listOf(
        ProTeamDef("college-northeastern", "Northeastern Huskies", "northeastern"),
        ProTeamDef("college-bc", "Boston College Eagles", "boston college"),
        ProTeamDef("college-bu", "Boston University Terriers", "boston university"),
        ProTeamDef("college-bu2", "Boston University Terriers", "bu terriers"),
        ProTeamDef("college-harvard", "Harvard Crimson", "harvard"),
        ProTeamDef("college-mit", "MIT Engineers", "mit engineers"),
        ProTeamDef("college-tufts", "Tufts Jumbos", "tufts"),
        ProTeamDef("college-umass", "UMass Minutemen", "umass"),
        ProTeamDef("college-holycross", "Holy Cross Crusaders", "holy cross"),
        ProTeamDef("college-providence", "Providence Friars", "providence friars"),
        ProTeamDef("college-uconn", "UConn Huskies", "uconn"),
        ProTeamDef("college-merrimack", "Merrimack Warriors", "merrimack"),
        ProTeamDef("college-bentley", "Bentley Falcons", "bentley"),
        ProTeamDef("college-wpi", "WPI Engineers", "wpi"),
        ProTeamDef("college-brandeis", "Brandeis Judges", "brandeis"),
        ProTeamDef("college-brown", "Brown Bears", "brown bears"),
    )

    private val sportTypes = listOf(
        SportsCarouselDef("sports-hockey", "Hockey", "hockey"),
        SportsCarouselDef("sports-basketball", "Basketball", "basketball"),
        SportsCarouselDef("sports-baseball", "Baseball", "baseball"),
        SportsCarouselDef("sports-football", "Football", "football"),
        SportsCarouselDef("sports-soccer", "Soccer", "soccer"),
        SportsCarouselDef("sports-college", "College Sports", "college-sports"),
        SportsCarouselDef("sports-minor-league", "Minor League", "minor-league"),
    )

    /** Build sports carousels from the already-cached event pool */
    private fun buildSportsCarouselsFromPool(
        allSports: List<Event>,
        prefs: UserPreferences?,
        seenEventIds: MutableSet<String>,
    ): List<CarouselSection> {
        val result = mutableListOf<CarouselSection>()

        fun matchesTeam(event: Event, team: ProTeamDef): Boolean {
            val titleLc = event.title.lowercase()
            // Match keyword against title only with word boundary check
            // so "patriot" doesn't match "patriotic" or venue names
            val keywordRegex = Regex("\\b${Regex.escape(team.keyword)}\\b")
            if (!keywordRegex.containsMatchIn(titleLc)) return false
            // Exclude false positives (parking, holidays, etc.)
            val fullText = "$titleLc ${(event.venueName ?: "").lowercase()}"
            return team.excludeKeywords.none { fullText.contains(it) }
        }

        // 1. Pro team carousels — match by keyword, exclude minor league
        //    No seenEventIds filter — team carousels should show all games
        for (team in bostonProTeams) {
            val events = allSports
                .filter { matchesTeam(it, team) }
                .take(carouselDisplayLimit)

            if (events.isEmpty()) continue
            events.forEach { seenEventIds.add(it.id) }

            result.add(
                CarouselSection(
                    slug = team.slug,
                    title = team.title,
                    subtitle = null,
                    filterType = "sports_team",
                    events = events,
                ),
            )
        }

        // 1b. Minor league team carousels
        // Merge events from all keyword variants into a single carousel per team
        val minorLeagueEvents = mutableListOf<Event>()
        val minorLeagueIds = mutableSetOf<String>()
        for (team in minorLeagueTeams) {
            allSports
                .filter { event ->
                    val text = "${event.title} ${event.venueName ?: ""}".lowercase()
                    text.contains(team.keyword)
                }
                .forEach { if (minorLeagueIds.add(it.id)) minorLeagueEvents.add(it) }
        }
        if (minorLeagueEvents.isNotEmpty()) {
            val capped = minorLeagueEvents.take(carouselDisplayLimit)
            capped.forEach { seenEventIds.add(it.id) }
            result.add(
                CarouselSection(
                    slug = "sports-woosox",
                    title = minorLeagueTeams.first().title,
                    subtitle = null,
                    filterType = "sports_team",
                    events = capped,
                ),
            )
        }

        // 1c. College team carousels
        val collegeSlugsSeen = mutableSetOf<String>()
        for (college in areaColleges) {
            // Dedupe alternate keyword entries (e.g. "bu terriers" and "boston university")
            val baseSlug = college.slug.substringBefore("2") // college-bu2 → college-bu
            if (baseSlug in collegeSlugsSeen) {
                // Just claim the events for the already-created carousel
                allSports
                    .filter { event ->
                        val text = "${event.title} ${event.venueName ?: ""}".lowercase()
                        text.contains(college.keyword)
                    }
                    .forEach { seenEventIds.add(it.id) }
                continue
            }

            val events = allSports
                .filter { event ->
                    val text = "${event.title} ${event.venueName ?: ""}".lowercase()
                    text.contains(college.keyword)
                }
                .take(carouselDisplayLimit)

            if (events.isEmpty()) continue
            events.forEach { seenEventIds.add(it.id) }
            collegeSlugsSeen.add(baseSlug)

            result.add(
                CarouselSection(
                    slug = baseSlug,
                    title = college.title,
                    subtitle = null,
                    filterType = "sports_college",
                    events = events,
                ),
            )
        }

        // 2. By sport type — group remaining events by sub_subcategory
        for (sport in sportTypes) {
            val events = allSports
                .filter { it.subSubcategory == sport.subSubcategory }
                .filter { it.id !in seenEventIds }
                .take(carouselDisplayLimit)

            if (events.isEmpty()) continue
            events.forEach { seenEventIds.add(it.id) }

            result.add(
                CarouselSection(
                    slug = sport.slug,
                    title = sport.title,
                    subtitle = null,
                    filterType = "sports_type",
                    events = events,
                ),
            )
        }

        return result
    }

    // ════════════════════════════════════════════════════════════════
    // ALWAYS ON — permanent attractions, recurring events, ongoing things
    // ════════════════════════════════════════════════════════════════

    private suspend fun getAlwaysOnCarousels(
        city: String,
        category: String?,
        userLat: Double,
        userLng: Double,
        prefs: UserPreferences?,
        nearestCity: String = "Boston",
    ): List<CarouselSection> {
        val filter = resolveAlwaysOnFilter(category)
        if (filter is EventFilter.Empty) return emptyList()

        val allEvents = getAlwaysOnEventPool(city)
        val filtered = allEvents.applyFilter(filter)
            .map { it.withDistance(userLat, userLng) }
            .sortedBy { scoreForSort(it, prefs) }

        if (filtered.isEmpty()) return emptyList()

        // Subcategory tab — just build dynamic sections
        if (filter !is EventFilter.None) {
            return buildAlwaysOnSections(filtered, isForYou = false)
        }

        // ── For You tab — personalized carousels, mirroring Upcoming ──
        val seenEventIds = mutableSetOf<String>()
        val result = mutableListOf<CarouselSection>()

        // 1. Top Always On Picks for You
        val ranked = rankForColdStart(filtered, prefs, maxResults = carouselDisplayLimit)
        val backfilled = backfillToMinimum(ranked, seenEventIds, filtered)
        if (backfilled.isNotEmpty()) {
            backfilled.forEach { seenEventIds.add(it.id) }
            result.add(
                CarouselSection(
                    slug = "ao-top-personalized",
                    title = "Top Picks for You",
                    subtitle = null,
                    filterType = "personalized",
                    events = backfilled,
                ),
            )
        }

        // 2. Favorite category carousels
        if (prefs != null) {
            for (cat in prefs.selectedCategories) {
                val catEvents = allEvents
                    .filter { it.subcategory?.lowercase() == cat.lowercase() }
                    .map { it.withDistance(userLat, userLng) }
                    .sortedBy { scoreForSort(it, prefs) }
                    .filter { it.id !in seenEventIds }
                    .take(carouselDisplayLimit)
                if (catEvents.isEmpty()) continue
                catEvents.forEach { seenEventIds.add(it.id) }
                result.add(
                    CarouselSection(
                        slug = "ao-fav-cat-${cat.lowercase()}",
                        title = "Top ${categoryDisplayName(cat)}",
                        subtitle = null,
                        filterType = "favorite_category",
                        events = catEvents,
                    ),
                )
            }

            // 3. Favorite venue carousels
            for (venue in prefs.selectedVenues) {
                val venueEvents = allEvents
                    .filter { (it.venueName ?: "").contains(venue, ignoreCase = true) }
                    .map { it.withDistance(userLat, userLng) }
                    .sortedBy { scoreForSort(it, prefs) }
                    .filter { it.id !in seenEventIds }
                    .take(carouselDisplayLimit)
                if (venueEvents.isEmpty()) continue
                venueEvents.forEach { seenEventIds.add(it.id) }
                result.add(
                    CarouselSection(
                        slug = "ao-fav-venue-${venue.lowercase().replace(" ", "-")}",
                        title = "Happening at $venue",
                        subtitle = null,
                        filterType = "favorite_venue",
                        events = venueEvents,
                    ),
                )
            }
        }

        // 4. Neighborhood carousels — always shown, based on current location
        val aoNeighborhoods = buildList {
            add(nearestCity)
            prefs?.selectedNeighborhoods?.forEach { n ->
                if (!n.equals(nearestCity, ignoreCase = true)) add(n)
            }
        }
        for (neighborhood in aoNeighborhoods) {
            val neighborhoodEvents = allEvents
                .filter { it.venueCity.contains(neighborhood, ignoreCase = true) }
                .map { it.withDistance(userLat, userLng) }
                .sortedBy { scoreForSort(it, prefs) }
                .filter { it.id !in seenEventIds }
                .take(carouselDisplayLimit)
            if (neighborhoodEvents.isEmpty()) continue
            neighborhoodEvents.forEach { seenEventIds.add(it.id) }
            result.add(
                CarouselSection(
                    slug = "ao-neighborhood-${neighborhood.lowercase().replace(" ", "-")}",
                    title = "Happening in $neighborhood",
                    subtitle = null,
                    filterType = "neighborhood",
                    events = neighborhoodEvents,
                ),
            )
        }

        // 5. Free events
        val freeEvents = filtered
            .filter { it.isFree && it.id !in seenEventIds }
            .take(carouselDisplayLimit)
        if (freeEvents.isNotEmpty()) {
            freeEvents.forEach { seenEventIds.add(it.id) }
            result.add(
                CarouselSection(
                    slug = "ao-free-events",
                    title = "Free Things to Do",
                    subtitle = null,
                    filterType = "free",
                    events = freeEvents,
                ),
            )
        }

        // 6. Fill in with dynamic sub_subcategory carousels from remaining events
        val remaining = filtered.filter { it.id !in seenEventIds }
        if (remaining.isNotEmpty()) {
            val dynamicSections = buildAlwaysOnSections(remaining, isForYou = true)
            result.addAll(dynamicSections)
        }

        return result
    }

    private fun resolveAlwaysOnFilter(category: String?): EventFilter {
        return when (category?.lowercase()) {
            null, "foryou" -> EventFilter.None
            else -> EventFilter.Category(category.lowercase())
        }
    }

    /**
     * Dynamically builds Always On carousels by grouping events by sub_subcategory
     * within each subcategory. No hardcoded sections — scales to any city/content.
     *
     * When [isForYou] is true, limits carousels per subcategory and interleaves
     * them so no single subcategory dominates the feed.
     */
    private fun buildAlwaysOnSections(events: List<Event>, isForYou: Boolean = false): List<CarouselSection> {
        val used = mutableSetOf<String>()

        fun buildItems(items: List<Event>): List<Event>? {
            val unique = items.filter { it.id !in used }
            if (unique.isEmpty()) return null
            unique.forEach { used.add(it.id) }
            return unique
        }

        // Group all events by subcategory, sorted by event count (most popular first)
        val bySubcategory = events
            .groupBy { (it.subcategory ?: "other").lowercase() }
            .entries
            .sortedByDescending { it.value.size }

        // Build carousels per subcategory
        val perSubcategory = mutableMapOf<String, MutableList<CarouselSection>>()

        for ((subcategory, subcatEvents) in bySubcategory) {
            val displaySubcat = categoryDisplayName(subcategory)
            val carousels = mutableListOf<CarouselSection>()

            // 1. Group by sub_subcategory first — these are the interesting themed carousels
            val bySubSub = subcatEvents
                .groupBy { (it.subSubcategory ?: "").lowercase() }
                .filter { it.key.isNotEmpty() }
                .entries
                .sortedByDescending { it.value.size }

            for ((subSub, subSubEvents) in bySubSub) {
                if (subSubEvents.size < 2) continue
                val displayName = subSubcategoryDisplayName(subSub)
                buildItems(subSubEvents)?.let {
                    carousels.add(CarouselSection("$subcategory-$subSub", displayName, null, "always_on", it))
                }
            }

            // 2. Free events carousel (after themed carousels so it doesn't steal their events)
            val freeEvents = subcatEvents.filter { it.isFree && it.id !in used }
            if (freeEvents.size >= 3) {
                buildItems(freeEvents)?.let {
                    carousels.add(CarouselSection("$subcategory-free", "Free $displaySubcat", null, "always_on", it))
                }
            }

            // 3. Catch-all for remaining events
            val remaining = subcatEvents.filter { it.id !in used }
            if (remaining.isNotEmpty()) {
                buildItems(remaining)?.let {
                    carousels.add(CarouselSection("$subcategory-more", "More $displaySubcat", null, "always_on", it))
                }
            }

            if (carousels.isNotEmpty()) {
                perSubcategory[subcategory] = carousels
            }
        }

        if (!isForYou) {
            // Single subcategory selected — return all carousels, but also add
            // venue-based carousels for richer content
            val subcatResult = perSubcategory.values.flatten().toMutableList()

            // Add venue carousels (events can overlap with sub_subcategory carousels)
            for ((_, subcatEvents) in bySubcategory) {
                val byVenue = subcatEvents
                    .filter { !it.venueName.isNullOrBlank() }
                    .groupBy { it.venueName!!.lowercase() }
                    .filter { it.value.size >= 2 }
                    .entries
                    .sortedByDescending { it.value.size }
                    .take(6)

                for ((_, venueEvents) in byVenue) {
                    val venueName = venueEvents.first().venueName ?: continue
                    val subcat = venueEvents.first().subcategory ?: "other"
                    val capped = venueEvents.take(carouselDisplayLimit)
                    if (capped.size >= 2) {
                        subcatResult.add(CarouselSection(
                            "$subcat-venue-${venueName.lowercase().replace(" ", "-").take(30)}",
                            "At $venueName", null, "always_on", capped,
                        ))
                    }
                }
            }
            return subcatResult
        }

        // For You: interleave carousels from different subcategories
        // so no single subcategory dominates the feed
        val result = mutableListOf<CarouselSection>()
        val iterators = perSubcategory.entries
            .sortedByDescending { it.value.size }
            .map { it.value.iterator() }
            .toMutableList()

        while (iterators.isNotEmpty()) {
            val iter = iterators.iterator()
            while (iter.hasNext()) {
                val subcatIter = iter.next()
                if (subcatIter.hasNext()) {
                    result.add(subcatIter.next())
                } else {
                    iter.remove()
                }
            }
        }

        return result
    }

    /** Convert a sub_subcategory or subcategory key to a display title */
    private fun subSubcategoryDisplayName(key: String): String {
        return key.split("-")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    // ════════════════════════════════════════════════════════════════
    // Shared logic
    // ════════════════════════════════════════════════════════════════

    private fun resolveFilter(category: String?): EventFilter {
        return when (category?.lowercase()) {
            null, "foryou" -> EventFilter.None
            "music", "sports", "arts", "theatre", "comedy", "food",
            "museums", "tours", "libraries", "outdoors", "for-kids" -> EventFilter.Category(category.lowercase())
            "locations" -> EventFilter.None
            "other" -> EventFilter.ExcludeKnown
            else -> EventFilter.Category(category.lowercase())
        }
    }

    /** How many events to show per carousel after dedup */
    private val carouselDisplayLimit = 30

    private fun nowIso(): String = kotlin.time.Clock.System.now().toString()

    private fun daysFromNowIso(n: Int): String =
        kotlin.time.Clock.System.now().plus(n.days).toString()

    /**
     * Returns the ISO timestamp for Monday 00:00:00 after the next Sunday.
     * "This weekend" = events with start_at < this value.
     */
    private fun endOfNextSundayIso(): String {
        val tz = TimeZone.currentSystemDefault()
        val todayDate = kotlin.time.Clock.System.now().toLocalDateTime(tz).date
        val today = todayDate.dayOfWeek
        val daysUntilSunday = when (today) {
            DayOfWeek.SUNDAY -> 0
            DayOfWeek.SATURDAY -> 1
            DayOfWeek.FRIDAY -> 2
            DayOfWeek.THURSDAY -> 3
            DayOfWeek.WEDNESDAY -> 4
            DayOfWeek.TUESDAY -> 5
            DayOfWeek.MONDAY -> 6
            else -> 6
        }
        // Monday midnight = start of the day after Sunday
        val mondayDate = todayDate.plus(daysUntilSunday + 1, DateTimeUnit.DAY)
        return mondayDate.atStartOfDayIn(tz).toString()
    }

    /**
     * Returns the ISO timestamp for Friday 00:00:00.
     * If today is already Fri/Sat/Sun, returns today at midnight.
     */
    private fun startOfNextFridayIso(): String {
        val tz = TimeZone.currentSystemDefault()
        val todayDate = kotlin.time.Clock.System.now().toLocalDateTime(tz).date
        val today = todayDate.dayOfWeek
        val daysUntilFriday = when (today) {
            DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> 0
            DayOfWeek.MONDAY -> 4
            DayOfWeek.TUESDAY -> 3
            DayOfWeek.WEDNESDAY -> 2
            DayOfWeek.THURSDAY -> 1
            else -> 0
        }
        val fridayDate = todayDate.plus(daysUntilFriday, DateTimeUnit.DAY)
        return fridayDate.atStartOfDayIn(tz).toString()
    }


    private val knownSubcategories = listOf("music", "sports", "arts", "theatre", "comedy", "food", "outdoors", "museums", "tours", "libraries", "for-kids")

    private suspend fun queryEvents(
        city: String,
        filter: EventFilter,
        dateRange: String? = null,
        tag: String? = null,
        segment: String? = null,
        limit: Int = 45,
    ): List<Event> {
        val now = nowIso()
        return supabaseClient.from("events")
            .select {
                filter {
                    eq("city", city)
                    eq("is_active", true)
                    gte("start_at", now)

                    // Filter by parent category (upcoming / always_on)
                    if (segment != null) {
                        eq("category", segment)
                    }

                    when (dateRange) {
                        "this_weekend" -> {
                            gte("start_at", startOfNextFridayIso())
                            lte("start_at", endOfNextSundayIso())
                        }
                        "this_week" -> lte("start_at", daysFromNowIso(7))
                        "next_2_weeks" -> lte("start_at", daysFromNowIso(14))
                        "this_month" -> lte("start_at", daysFromNowIso(30))
                        else -> {}
                    }

                    when (tag) {
                        "free" -> eq("is_free", true)
                        else -> {}
                    }

                    when (filter) {
                        is EventFilter.None -> {}
                        is EventFilter.Empty -> {}
                        is EventFilter.Category -> eq("subcategory", filter.value)
                        is EventFilter.VenueLike -> ilike("venue_name", filter.pattern)
                        is EventFilter.Subcategory -> eq("sub_subcategory", filter.value)
                        is EventFilter.ExcludeKnown -> {
                            knownSubcategories.forEach { cat -> neq("subcategory", cat) }
                        }
                    }
                }
                order("start_at", Order.ASCENDING)
                limit(limit.toLong())
            }
            .decodeList<EventDto>()
            .map { it.toDomain() }
            .deduplicateCrossSource()
    }

    /**
     * When the same real-world event exists from both Ticketmaster and a crawl,
     * keep only the Ticketmaster version. Uses fuzzy matching: same venue + date,
     * and titles share significant words (handles "Boston Bruins vs. San Jose Sharks"
     * vs "Bruins vs Sharks").
     */
    private fun List<Event>.deduplicateCrossSource(): List<Event> {
        data class DedupeKey(val venue: String, val date: String)

        fun Event.dedupeKey() = DedupeKey(
            venue = (venueName ?: "").lowercase().trim(),
            date = startAt?.take(10) ?: "",  // YYYY-MM-DD
        )

        /** Extract significant words (3+ chars, no filler) for fuzzy title matching */
        fun titleWords(title: String): Set<String> {
            val filler = setOf("the", "at", "in", "of", "and", "vs", "vs.", "for", "a", "an")
            return title.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length >= 3 && it !in filler }
                .toSet()
        }

        fun titlesMatch(a: String, b: String): Boolean {
            val wordsA = titleWords(a)
            val wordsB = titleWords(b)
            if (wordsA.isEmpty() || wordsB.isEmpty()) return false
            val overlap = wordsA.intersect(wordsB).size
            val smaller = minOf(wordsA.size, wordsB.size)
            return overlap.toDouble() / smaller >= 0.5
        }

        val grouped = groupBy { it.dedupeKey() }
        val result = mutableListOf<Event>()

        for ((_, events) in grouped) {
            if (events.size == 1) {
                result.add(events.first())
                continue
            }
            // Within same venue+date, cluster by fuzzy title match
            val used = BooleanArray(events.size)
            for (i in events.indices) {
                if (used[i]) continue
                var best = events[i]
                for (j in i + 1 until events.size) {
                    if (used[j]) continue
                    if (titlesMatch(events[i].title, events[j].title)) {
                        used[j] = true
                        // Prefer ticketmaster
                        if (events[j].source == "ticketmaster" && best.source != "ticketmaster") {
                            best = events[j]
                        }
                    }
                }
                result.add(best)
            }
        }
        return result
    }

    // ── Reclassification ──
    // Ticketmaster assigns categories at the venue level, which causes gross
    // miscategorization. We fix the worst offenders client-side.

    /** Venues where Ticketmaster blanket-assigns a wrong category to everything */
    private val miscategorizedVenues = mapOf(
        "the center for arts in natick" to "comedy",  // everything is comedy/comedy
        "cabot theatre" to "music",                    // everything is music/null
    )

    /** Title patterns that indicate a film screening, not music/comedy */
    private val filmTitlePatterns = listOf(
        "screening", "matinee", "oscar nominated", "film screening",
        "movie night", "movie club", "cinematics",
    )

    /** Known film titles that Ticketmaster miscategorizes */
    private val knownFilmTitles = listOf(
        "sinners", "marty supreme", "life of pi", "blue velvet",
        "annie laurie", "the shining", "pink panther", "happyend",
        "all we imagine as light", "the river", "the mission",
        "the last waltz",
    )

    /** Title patterns for music events (used to fix venues like TCAN) */
    private val musicTitlePatterns = listOf(
        "guitar", "trio", "quartet", "quintet", "symphony", "orchestra",
        "band", "performs", "unplugged", "concert", "tenors", "singers",
        "jazz", "blues", "country", "folk", "rock", "punk", "hip hop",
        "dj ", "live music",
    )

    /** Known music artist names at miscategorized venues */
    private val knownMusicActs = listOf(
        "california guitar trio", "jake shimabukuro", "kathy mattea",
        "quinn sullivan", "chuck prophet", "tinsley ellis", "davy knowles",
        "mary fahl", "altan", "stick men", "antje duvekot", "david berkeley",
        "international guitar night", "celtic tenors", "masters of the telecaster",
        "shane guerrette", "chambers deslauriers",
    )

    /** Government/administrative events that pollute outdoor results */
    private val governmentPatterns = listOf(
        "abutters meeting", "community meeting", "office hours",
        "berdo", "virtual legal clinic", "mock class",
        "mejora tu credito", "restore boston",
    )

    /** Reclassify an event if Ticketmaster got it wrong */
    private fun Event.reclassified(): Event {
        val titleLc = title.lowercase()
        val venueLc = (venueName ?: "").lowercase()

        // Filter out government/admin events masquerading as outdoor recreation
        if (governmentPatterns.any { titleLc.contains(it) }) {
            return copy(subcategory = "other", subSubcategory = "administrative")
        }

        // Check if this venue is known to be miscategorized
        val venueWrongCat = miscategorizedVenues.entries.find { venueLc.contains(it.key) }

        // Film detection — works for any venue
        val isFilm = filmTitlePatterns.any { titleLc.contains(it) } ||
            knownFilmTitles.any { titleLc.contains(it) } ||
            Regex("\\(\\d{4}\\)").containsMatchIn(title) // year in parens like (1986)
        if (isFilm) return copy(subcategory = "arts", subSubcategory = "film")

        // Only reclassify further if this is a known-bad venue
        if (venueWrongCat != null && subcategory == venueWrongCat.value) {
            // Music detection
            if (musicTitlePatterns.any { titleLc.contains(it) } ||
                knownMusicActs.any { titleLc.contains(it) }
            ) return copy(subcategory = "music")

            // Open mic → cafe
            if (titleLc.contains("open mic")) return copy(subcategory = "music", subSubcategory = "open-mic")

            // Science/kids shows → for-kids
            if (titleLc.contains("science") || titleLc.contains("wacky")) {
                return copy(subcategory = "for-kids", subSubcategory = "science-kids")
            }

            // "Boston Comics" or actual comedy acts stay as comedy
            if (titleLc.contains("comics") || titleLc.contains("comedy") ||
                titleLc.contains("improv") || titleLc.contains("stand-up") ||
                titleLc.contains("standup")
            ) return this

            // Default: reclassify as arts for an arts center
            if (venueLc.contains("arts") || venueLc.contains("theatre") || venueLc.contains("theater")) {
                return copy(subcategory = "arts")
            }
        }

        return this
    }

    // ── Post-processing ──

    private fun cleanEvents(events: List<Event>): List<Event> {
        return events
            .map { it.reclassified().withCleanedTitle().withCleanedVenue() }
            .filterNot { isGovernmentNoise(it) }
            .filterNot { it.subcategory == "no-public-interest" }
            .filterNot { isLivestreamDuplicate(it, events) }
            .distinctByBaseTitle()
            .map { it.withVenuePhotoFallback().withVenueDescription() }
    }

    /** If an event has no image, fall back to the venue's Google Places photo */
    private fun Event.withVenuePhotoFallback(): Event {
        if (imageUrl != null) return this
        val venueLc = (venueName ?: "").lowercase()
        val photo = venueCache[venueLc]?.photoUrl ?: return this
        return copy(imageUrl = photo)
    }

    /** Attach venue description from Google Places if available */
    private fun Event.withVenueDescription(): Event {
        val venueLc = (venueName ?: "").lowercase()
        val desc = venueCache[venueLc]?.description ?: return this
        return copy(venueDescription = desc)
    }

    private fun isGovernmentNoise(event: Event): Boolean {
        return event.subcategory == "other" && event.subSubcategory == "administrative"
    }

    private fun Event.withCleanedTitle(): Event {
        var cleaned = title

        val pipeIdx = cleaned.indexOf(" | ")
        if (pipeIdx > 10) cleaned = cleaned.substring(0, pipeIdx)

        cleaned = cleaned.replace("Livestream Event from ", "", ignoreCase = true)
        cleaned = cleaned.replace("Livestream Event ", "", ignoreCase = true)
        cleaned = cleaned.replace(" Livestream Event", "", ignoreCase = true)

        if (venueName != null && venueName.length >= 4) {
            val withoutVenue = cleaned.replace(venueName, "", ignoreCase = true)
                .replace("  ", " ")
                .replace(" - ", "")
                .replace(" at ", "")
                .replace(" from ", "")
                .trim()
            if (withoutVenue.length >= 8) {
                cleaned = withoutVenue
            }
        }

        cleaned = cleaned.trim()
        return if (cleaned != title) copy(title = cleaned) else this
    }

    private fun Event.withCleanedVenue(): Event {
        val vn = venueName ?: return this
        var cleaned = vn
        cleaned = cleaned.replace(Regex(" presented by .*", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex(" Resort Casino.*", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace("-NH", "").replace("-CT", "").trim()
        return if (cleaned != vn) copy(venueName = cleaned) else this
    }

    private fun isLivestreamDuplicate(event: Event, allEvents: List<Event>): Boolean {
        if (!event.title.contains("livestream", ignoreCase = true)) return false
        val baseName = event.title
            .replace("Livestream Event from ", "", ignoreCase = true)
            .replace("Livestream Event ", "", ignoreCase = true)
            .replace(" Livestream Event", "", ignoreCase = true)
            .replace("Livestream", "", ignoreCase = true)
            .trim()
        return allEvents.any { other ->
            other.id != event.id &&
                !other.title.contains("livestream", ignoreCase = true) &&
                other.title.contains(baseName, ignoreCase = true)
        }
    }

    private fun List<Event>.distinctByBaseTitle(): List<Event> {
        val seen = mutableSetOf<String>()
        return filter { event ->
            val key = baseTitle(event.title)
            seen.add(key)
        }
    }

    private fun baseTitle(title: String): String {
        return title.lowercase()
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ── Distance + relevance sorting ──

    /** Attach distance from the user to each event */
    private fun Event.withDistance(userLat: Double, userLng: Double): Event {
        val lat = venueLat ?: return this
        val lng = venueLng ?: return this
        return copy(distanceMiles = distanceMiles(userLat, userLng, lat, lng))
    }

    /**
     * Lower = better. Combines relevance, venue rank, date proximity, and
     * geographic proximity into a single sort key.
     *
     * Weights:
     *   40% — relevance (cold-start signals + personalization)
     *   25% — venue public interest rank (higher-ranked venues surface first)
     *   20% — geographic proximity (closer is better)
     *   15% — start date proximity (sooner events get a boost)
     */
    private fun scoreForSort(event: Event, prefs: UserPreferences? = null): Double {
        val relevance = coldStartScore(event, prefs)  // 0..~1.3
        val distance = event.distanceMiles ?: 50.0
        val proximity = 1.0 / (1.0 + distance / 5.0) // 1.0 at 0mi, decays

        // Venue rank: normalize 0-100 → 0-1
        val venueLc = (event.venueName ?: "").lowercase()
        val venueRank = venueCache[venueLc]?.rank
        val rankScore = (venueRank ?: 30).toDouble() / 100.0  // default 30 for unranked

        // Date proximity: events happening sooner score higher
        val dateScore = startDateProximityScore(event.startAt)

        // Combined: higher is better, negate for ascending sort
        return -(relevance * 0.40 + rankScore * 0.25 + proximity * 0.20 + dateScore * 0.15)
    }

    /**
     * Score based on how soon the event starts.
     * Today = 1.0, decays toward 0 over 30 days.
     */
    private fun startDateProximityScore(startAt: String?): Double {
        if (startAt == null) return 0.3 // default for events with no date
        return try {
            val eventInstant = Instant.parse(startAt)
            val now = kotlin.time.Clock.System.now()
            val daysAway = (eventInstant - now).inWholeDays.toDouble().coerceAtLeast(0.0)
            // Exponential decay: 1.0 today, ~0.5 at 7 days, ~0.1 at 30 days
            1.0 / (1.0 + daysAway / 7.0)
        } catch (_: Exception) {
            0.3
        }
    }

    // ── Cold-start relevance ranking ──

    private fun rankForColdStart(events: List<Event>, prefs: UserPreferences?, maxResults: Int): List<Event> {
        val scored = events.map { it to coldStartScore(it, prefs) }
            .sortedByDescending { it.second }

        val result = mutableListOf<Event>()
        val deferred = mutableListOf<Pair<Event, Double>>()
        val categoryCounts = mutableMapOf<String, Int>()

        for (pair in scored) {
            val (event, score) = pair
            if (result.size >= maxResults) break
            val subcat = event.subcategory ?: "other"
            val catCount = categoryCounts.getOrElse(subcat) { 0 }
            if (catCount >= 3) continue

            // Avoid back-to-back similar events (e.g. multiple Celtics games)
            if (result.isNotEmpty() && isSimilarEvent(event, result.last())) {
                deferred.add(pair)
                continue
            }

            result.add(event)
            categoryCounts[subcat] = catCount + 1
        }

        // Insert deferred events into the next non-similar slot
        for ((event, _) in deferred) {
            if (result.size >= maxResults) break
            val subcat = event.subcategory ?: "other"
            val catCount = categoryCounts.getOrElse(subcat) { 0 }
            if (catCount >= 3) continue

            // Find the first position where it wouldn't be adjacent to a similar event
            var inserted = false
            for (i in 1..result.lastIndex) {
                val prevSimilar = isSimilarEvent(event, result[i - 1])
                val nextSimilar = isSimilarEvent(event, result[i])
                if (!prevSimilar && !nextSimilar) {
                    result.add(i, event)
                    categoryCounts[subcat] = catCount + 1
                    inserted = true
                    break
                }
            }
            // If no good slot, append at end if not similar to last
            if (!inserted && (result.isEmpty() || !isSimilarEvent(event, result.last()))) {
                result.add(event)
                categoryCounts[subcat] = catCount + 1
            }
        }

        return result
    }

    /**
     * Two events are "similar" if they are the same kind of thing —
     * e.g. multiple Celtics games, multiple Bruins games, etc.
     * Checks: same venue, OR a shared "anchor" word (team name, artist, etc.)
     * in the title with the same subcategory.
     */
    private fun isSimilarEvent(a: Event, b: Event): Boolean {
        // Different subcategories are never similar
        if ((a.subcategory ?: "") != (b.subcategory ?: "")) return false

        // Same venue = similar (e.g. 3 Celtics games all at TD Garden)
        val venueA = (a.venueName ?: "").lowercase().trim()
        val venueB = (b.venueName ?: "").lowercase().trim()
        if (venueA.isNotEmpty() && venueA == venueB) return true

        // Shared anchor words in title (team names, artist names, series names)
        val filler = setOf("the", "at", "in", "of", "and", "vs", "vs.", "for", "a", "an", "to")
        fun words(title: String): Set<String> = title.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 && it !in filler }
            .toSet()

        val wordsA = words(a.title)
        val wordsB = words(b.title)
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false
        val overlap = wordsA.intersect(wordsB).size
        return overlap >= 2
    }

    private val proTeams = listOf("celtics", "bruins", "red sox", "patriots", "revolution")

    private val premiumVenues = listOf(
        "td garden", "fenway park", "gillette stadium",
        "house of blues", "boch center", "wang theatre", "shubert theatre",
        "orpheum theatre", "emerson colonial", "wilbur",
        "big night live", "leader bank pavilion", "mgm music hall",
    )

    private val bostonArea = setOf(
        "boston", "cambridge", "somerville", "brookline", "medford",
        "everett", "chelsea", "allston", "brighton", "jamaica plain",
        "charlestown", "dorchester", "roxbury", "south boston",
        "chestnut hill", "newton",
    )

    private fun coldStartScore(event: Event, prefs: UserPreferences? = null): Double {
        var score = 0.5
        val titleLc = event.title.lowercase()
        val venueLc = (event.venueName ?: "").lowercase()
        val cityLc = event.venueCity.lowercase()

        if (proTeams.any { titleLc.contains(it) }) score += 0.35
        if (premiumVenues.any { venueLc.contains(it) }) score += 0.2

        score += when {
            cityLc in bostonArea -> 0.15
            else -> -0.15
        }

        if (titleLc.contains("livestream")) score -= 0.6

        val minorSportsPatterns = listOf(
            "river hawks", "wildcats", "minutemen", "eagles hockey",
            "mountain kings", "nordiques", "mariners vs", "thunder vs",
            "big east", "ncaa", "college",
        )
        if (minorSportsPatterns.any { titleLc.contains(it) }) score -= 0.2

        if (event.isFree) score += 0.05
        if (event.imageUrl != null) score += 0.05

        // ── Personalization boosts ──
        if (prefs != null) {
            // Boost events matching user's selected categories (subcategory in new schema)
            if (event.subcategory in prefs.selectedCategories) score += 0.25

            // Boost events at user's favorite venues
            if (prefs.selectedVenues.any { venueLc.contains(it.lowercase()) }) score += 0.30

            // Boost events in user's selected neighborhoods
            if (prefs.selectedNeighborhoods.any { cityLc.contains(it.lowercase()) }) score += 0.10
        }

        return score
    }

    // ════════════════════════════════════════════════════════════════
    // Dynamic category discovery
    // ════════════════════════════════════════════════════════════════

    private val categoryDisplayNames = mapOf(
        "music" to "Music",
        "arts" to "Arts",
        "theatre" to "Theatre",
        "food" to "Food",
        "comedy" to "Comedy",
        "sports" to "Sports",
        "museums" to "Museums",
        "tours" to "Tours",
        "libraries" to "Libraries",
        "outdoors" to "Outdoors",
        "for-kids" to "For Kids",
        "other" to "Other",
    )

    private fun iconUrlFor(subcategory: String): String =
        "${Config.SUPABASE_URL}/storage/v1/object/public/category-icons/${subcategory}.png"

    /** Cached category lists per segment */
    private var categoryCacheUpcoming: List<CategoryInfo>? = null
    private var categoryCacheAlwaysOn: List<CategoryInfo>? = null
    /** Stable ordering shared across both segments, computed once */
    private var categoryOrder: List<String>? = null

    override suspend fun getAvailableCategories(
        segment: SegmentMode,
        userPreferences: UserPreferences?,
    ): List<CategoryInfo> {
        // Return cached if available
        val cached = when (segment) {
            SegmentMode.UPCOMING -> categoryCacheUpcoming
            SegmentMode.ALWAYS_ON -> categoryCacheAlwaysOn
        }
        if (cached != null) return cached

        val city = cityKeyFromLocation(
            userPreferences?.userLat ?: 42.3897,
            userPreferences?.userLng ?: -71.2358,
        )
        val now = nowIso()
        val userFavorites = userPreferences?.selectedCategories?.map { it.lowercase() } ?: emptyList()

        // On first call, fetch both segments and compute a stable order
        if (categoryOrder == null) {
            val allEvents = supabaseClient.from("events")
                .select(columns = io.github.jan.supabase.postgrest.query.Columns.list("subcategory")) {
                    filter {
                        eq("city", city)
                        eq("is_active", true)
                        gte("start_at", now)
                    }
                }
                .decodeList<SubcategoryDto>()

            val combinedCounts = allEvents
                .mapNotNull { it.subcategory?.lowercase() }
                .filter { it != "no-public-interest" && it != "cafe" }
                .groupingBy { it }
                .eachCount()

            categoryOrder = combinedCounts.entries
                .sortedWith(
                    compareBy<Map.Entry<String, Int>> { it.key == "other" }
                        .thenByDescending { it.key in userFavorites }
                        .thenByDescending { it.value },
                )
                .map { it.key }
        }

        val order = categoryOrder!!

        // Now fetch counts for the requested segment only
        val parentCategory = when (segment) {
            SegmentMode.UPCOMING -> "upcoming"
            SegmentMode.ALWAYS_ON -> "always_on"
        }

        val segmentEvents = supabaseClient.from("events")
            .select(columns = io.github.jan.supabase.postgrest.query.Columns.list("subcategory")) {
                filter {
                    eq("city", city)
                    eq("is_active", true)
                    eq("category", parentCategory)
                    gte("start_at", now)
                }
            }
            .decodeList<SubcategoryDto>()

        val counts = segmentEvents
            .mapNotNull { it.subcategory?.lowercase() }
            .filter { it != "no-public-interest" && it != "cafe" }
            .groupingBy { it }
            .eachCount()

        if (counts.isEmpty()) return emptyList()

        // Use the stable order, only including subcategories that have events in this segment
        val result = order
            .filter { it in counts }
            .map { subcat ->
                CategoryInfo(
                    id = subcat,
                    label = categoryDisplayNames[subcat] ?: subcat.replaceFirstChar { it.uppercase() },
                    iconUrl = iconUrlFor(subcat),
                    eventCount = counts[subcat] ?: 0,
                )
            }

        when (segment) {
            SegmentMode.UPCOMING -> categoryCacheUpcoming = result
            SegmentMode.ALWAYS_ON -> categoryCacheAlwaysOn = result
        }
        return result
    }
}

@kotlinx.serialization.Serializable
private data class SubcategoryDto(
    val subcategory: String? = null,
)
