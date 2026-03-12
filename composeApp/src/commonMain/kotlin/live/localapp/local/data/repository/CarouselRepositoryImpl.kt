package live.localapp.local.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import live.localapp.local.data.remote.dto.CarouselDto
import live.localapp.local.data.remote.dto.EventDto
import live.localapp.local.data.remote.dto.toDomain
import live.localapp.local.domain.model.CarouselSection
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.model.distanceMiles
import live.localapp.local.domain.repository.CarouselRepository
import live.localapp.local.domain.repository.SegmentMode

private sealed class EventFilter {
    data object None : EventFilter()
    data object Empty : EventFilter()
    data class Category(val value: String) : EventFilter()
    data class VenueLike(val pattern: String) : EventFilter()
    data class Subcategory(val value: String) : EventFilter()
    data object ExcludeKnown : EventFilter()
}

class CarouselRepositoryImpl(
    private val supabaseClient: SupabaseClient,
) : CarouselRepository {

    override suspend fun getCarousels(
        userLat: Double,
        userLng: Double,
        segment: SegmentMode,
        category: String?,
    ): List<CarouselSection> {
        // For now, determine city from user location for DB queries
        val city = cityKeyFromLocation(userLat, userLng)
        return when (segment) {
            SegmentMode.UPCOMING -> getUpcomingCarousels(city, category, userLat, userLng)
            SegmentMode.ALWAYS_ON -> getAlwaysOnCarousels(city, category, userLat, userLng)
        }
    }

    /** Map user lat/lng to the city key used in the database */
    private fun cityKeyFromLocation(lat: Double, lng: Double): String {
        // TODO: expand to support multiple metros
        return "boston"
    }

    // ════════════════════════════════════════════════════════════════
    // UPCOMING — scheduled, one-time events (concerts, games, shows)
    // ════════════════════════════════════════════════════════════════

    private suspend fun getUpcomingCarousels(city: String, category: String?, userLat: Double, userLng: Double): List<CarouselSection> {
        val filter = resolveFilter(category)
        if (filter is EventFilter.Empty) return emptyList()

        val carousels = supabaseClient.from("carousels")
            .select {
                filter {
                    eq("city", city)
                    eq("is_active", true)
                }
                order("position", Order.ASCENDING)
            }
            .decodeList<CarouselDto>()

        val seenEventIds = mutableSetOf<String>()

        return carousels.mapNotNull { carousel ->
            if (carousel.filterType == "category" && filter !is EventFilter.None && filter !is EventFilter.Category) {
                return@mapNotNull null
            }

            val events = fetchEventsForCarousel(city, carousel, filter)
                .filter { !isAlwaysOnEvent(it) }
                .let { cleanEvents(it) }
                .map { it.withDistance(userLat, userLng) }
                .sortedBy { scoreForSort(it) }
                .filter { it.id !in seenEventIds }
                .take(carouselDisplayLimit)

            if (events.isEmpty()) return@mapNotNull null
            events.forEach { seenEventIds.add(it.id) }

            CarouselSection(
                slug = carousel.slug,
                title = carousel.title,
                subtitle = carousel.subtitle,
                filterType = carousel.filterType,
                events = events,
            )
        }
    }

    // ════════════════════════════════════════════════════════════════
    // ALWAYS ON — permanent attractions, recurring events, ongoing things
    // ════════════════════════════════════════════════════════════════

    /** Always-on subcategories that represent permanent/recurring things */
    private val alwaysOnSubcategories = setOf("museum", "library", "gallery", "tour", "outdoor")

    /** Keywords in titles that indicate recurring/permanent events */
    private val alwaysOnTitlePatterns = listOf(
        "weekly", "farmers market", "open market", "collection",
        "ongoing", "walking tour", "guided tour", "ghost tour",
        "food tour", "boat tour", "brewery tour", "history tour",
        "trivia night", "open mic", "board game", "workout",
        "beer release", "jazz at",
    )

    /** Returns true if this event belongs in "Always On" rather than "Upcoming" */
    private fun isAlwaysOnEvent(event: Event): Boolean {
        // Subcategory match — museums, libraries, galleries, tours
        if (event.subcategory != null && event.subcategory.lowercase() in alwaysOnSubcategories) return true

        // Title-pattern match — recurring events
        val titleLc = event.title.lowercase()
        if (alwaysOnTitlePatterns.any { titleLc.contains(it) }) return true

        return false
    }

    private suspend fun getAlwaysOnCarousels(city: String, category: String?, userLat: Double, userLng: Double): List<CarouselSection> {
        val filter = resolveAlwaysOnFilter(category)
        if (filter is EventFilter.Empty) return emptyList()

        // Fetch a pool of always-on events
        val allEvents = queryAlwaysOnEvents(city, filter)
            .let { cleanEvents(it) }
            .map { it.withDistance(userLat, userLng) }
            .sortedBy { scoreForSort(it) }

        if (allEvents.isEmpty()) return emptyList()

        // Group into curated sections
        return buildAlwaysOnSections(allEvents)
    }

    private fun resolveAlwaysOnFilter(category: String?): EventFilter {
        return when (category?.lowercase()) {
            null, "foryou" -> EventFilter.None
            "museums" -> EventFilter.VenueLike("%museum%")
            "libraries" -> EventFilter.VenueLike("%library%")
            "tours" -> EventFilter.Subcategory("tour")
            "food" -> EventFilter.Empty
            "locations" -> EventFilter.None
            else -> EventFilter.None
        }
    }

    private suspend fun queryAlwaysOnEvents(city: String, filter: EventFilter): List<Event> {
        val now = nowIso()

        val museumLibrary = supabaseClient.from("events")
            .select {
                filter {
                    eq("city", city)
                    gte("start_at", now)
                    or {
                        eq("subcategory", "museum")
                        eq("subcategory", "library")
                        eq("subcategory", "gallery")
                        eq("subcategory", "tour")
                    }
                    when (filter) {
                        is EventFilter.VenueLike -> ilike("venue_name", filter.pattern)
                        is EventFilter.Subcategory -> eq("subcategory", filter.value)
                        else -> {}
                    }
                }
                order("start_at", Order.ASCENDING)
                limit(200)
            }
            .decodeList<EventDto>()
            .map { it.toDomain() }

        val recurring = supabaseClient.from("events")
            .select {
                filter {
                    eq("city", city)
                    gte("start_at", now)
                    or {
                        eq("category", "outdoors")
                        eq("category", "nightlife")
                    }
                }
                order("start_at", Order.ASCENDING)
                limit(100)
            }
            .decodeList<EventDto>()
            .map { it.toDomain() }
            .filter { isAlwaysOnEvent(it) }

        return (museumLibrary + recurring).distinctBy { it.id }
    }

    private fun buildAlwaysOnSections(events: List<Event>): List<CarouselSection> {
        val sections = mutableListOf<CarouselSection>()
        val used = mutableSetOf<String>()

        fun addSection(slug: String, title: String, subtitle: String?, items: List<Event>) {
            if (items.isEmpty()) return
            val unique = items.filter { it.id !in used }
            if (unique.isEmpty()) return
            unique.forEach { used.add(it.id) }
            sections.add(CarouselSection(slug, title, subtitle, "always_on", unique))
        }

        // ── Museum & Gallery carousels ──

        val museumEvents = events.filter { it.subcategory?.lowercase() in setOf("museum", "gallery") }

        // Free days — everyone loves free museum days
        addSection(
            "free-museum", "Free Days at the Museum", "No ticket needed",
            museumEvents.filter { it.isFree },
        )

        // Family-friendly museum events
        addSection(
            "museum-families", "Little Explorers", "Family-friendly museum fun",
            museumEvents.filter { ev ->
                val t = ev.title.lowercase()
                t.contains("family") || t.contains("kids") || t.contains("children") ||
                    t.contains("tots") || t.contains("baby") || t.contains("story time") ||
                    t.contains("playroom") || t.contains("sketch") || t.contains("hands-on") ||
                    t.contains("playful") || t.contains("professor gizmo") ||
                    t.contains("open studio") || t.contains("kindergarten")
            },
        )

        // Concert series at museums (Gardner, etc.)
        addSection(
            "museum-concerts", "Live at the Gallery", "Concerts in stunning museum spaces",
            museumEvents.filter { ev ->
                val t = ev.title.lowercase()
                t.contains("concert") || t.contains("quartet") || t.contains("lute") ||
                    t.contains("sound performance") || t.contains("jazz")
            },
        )

        // Film screenings at museums
        addSection(
            "museum-film", "Screenings & Cinema", "Films in gallery settings",
            museumEvents.filter { ev ->
                val t = ev.title.lowercase()
                t.contains("screening") || t.contains("film") || t.contains("movie") ||
                    Regex("\\(\\d{4}\\)").containsMatchIn(ev.title)
            },
        )

        // Artist talks, lectures, panels
        addSection(
            "museum-talks", "Art Talks & Conversations", "Hear from artists and curators",
            museumEvents.filter { ev ->
                val t = ev.title.lowercase()
                t.contains("talk") || t.contains("lecture") || t.contains("panel") ||
                    t.contains("forum") || t.contains("discussion") || t.contains("conversation") ||
                    t.contains("an evening with")
            },
        )

        // Ongoing exhibitions
        addSection(
            "exhibitions", "Exhibitions On View", "Currently on display",
            museumEvents.filter { ev ->
                val t = ev.title.lowercase()
                t.contains("exhibition") || t.contains("ongoing") || t.contains("collection") ||
                    t.contains("on view") || t.contains("viewing day")
            },
        )

        // Workshops at museums
        addSection(
            "museum-workshops", "Make Something", "Hands-on workshops and classes",
            museumEvents.filter { ev ->
                val t = ev.title.lowercase()
                t.contains("workshop") || t.contains("open studio") || t.contains("collage") ||
                    t.contains("writing") || t.contains("poetry")
            },
        )

        // Remaining museum events that didn't fit a specific bucket
        addSection(
            "museum-more", "More at the Museum", null,
            museumEvents.filter { it.id !in used },
        )

        // ── Library carousels ──

        val libraryEvents = events.filter { it.subcategory?.lowercase() == "library" }

        // Storytime & kids at the library
        addSection(
            "library-kids", "Storytime & Kids", "Free programs for little ones",
            libraryEvents.filter { ev ->
                val t = ev.title.lowercase()
                t.contains("story") || t.contains("baby") || t.contains("toddler") ||
                    t.contains("kids") || t.contains("children") || t.contains("family") ||
                    t.contains("kindergarten") || t.contains("playgroup") || t.contains("playroom") ||
                    t.contains("singalong") || t.contains("ages 0")
            },
        )

        // Adult library programs
        addSection(
            "library-adult", "Library After Hours", "Trivia, book clubs, and more",
            libraryEvents.filter { ev ->
                val t = ev.title.lowercase()
                t.contains("trivia") || t.contains("book club") || t.contains("board game") ||
                    t.contains("knitting") || t.contains("open mic") || t.contains("d&d") ||
                    t.contains("teen") || t.contains("jazz") || t.contains("lecture") ||
                    t.contains("revolution")
            },
        )

        // Remaining library events
        addSection(
            "library-more", "At the Library", "Free things to do",
            libraryEvents.filter { it.id !in used },
        )

        // ── Tours ──

        val tourEvents = events.filter { ev ->
            val t = ev.title.lowercase()
            ev.subcategory?.lowercase() == "tour" ||
                t.contains("walking tour") || t.contains("guided tour") ||
                t.contains("ghost tour") || t.contains("food tour") ||
                t.contains("boat tour") || t.contains("brewery tour") ||
                t.contains("history tour") || t.contains("freedom trail")
        }

        addSection(
            "tours", "Explore on Foot", "Walking tours, food tours, and adventures",
            tourEvents,
        )

        // ── Farmers Markets ──

        addSection(
            "markets", "Farmers Markets", "Fresh and local, every week",
            events.filter { ev ->
                val t = ev.title.lowercase()
                t.contains("farmers market") || t.contains("public market")
            },
        )

        // ── Trivia nights ──

        addSection(
            "trivia", "Test Your Knowledge", "Trivia nights across the city",
            events.filter { ev ->
                ev.title.lowercase().contains("trivia") && ev.id !in used
            },
        )

        // ── Open mic & live jams ──

        addSection(
            "open-mic", "Grab the Mic", "Open mics and jam sessions",
            events.filter { ev ->
                val t = ev.title.lowercase()
                (t.contains("open mic") || t.contains("jam session") || t.contains("jazz at")) &&
                    ev.id !in used
            },
        )

        // ── Outdoor fitness & wellness ──

        addSection(
            "outdoor-fitness", "Sweat Outside", "Free outdoor workouts and fitness",
            events.filter { ev ->
                val t = ev.title.lowercase()
                (t.contains("workout") || t.contains("fitness") || t.contains("yoga") ||
                    t.contains("strength") || t.contains("dance fitness")) && ev.id !in used
            },
        )

        // ── Everything else ──

        addSection(
            "more", "More to Do Anytime", null,
            events.filter { it.id !in used },
        )

        return sections
    }

    // ════════════════════════════════════════════════════════════════
    // Shared logic
    // ════════════════════════════════════════════════════════════════

    private fun resolveFilter(category: String?): EventFilter {
        return when (category?.lowercase()) {
            null, "foryou" -> EventFilter.None
            "music", "sports", "arts", "comedy", "nightlife", "outdoors" -> EventFilter.Category(category.lowercase())
            "theatre" -> EventFilter.Subcategory("theatre")
            "museums" -> EventFilter.VenueLike("%museum%")
            "libraries" -> EventFilter.VenueLike("%library%")
            "tours" -> EventFilter.Subcategory("tour")
            "food" -> EventFilter.Empty
            "locations" -> EventFilter.None
            "other" -> EventFilter.ExcludeKnown
            else -> EventFilter.Category(category.lowercase())
        }
    }

    /** How many events to show per carousel after dedup */
    private val carouselDisplayLimit = 30

    /** Fetch extra so dedup + cleaning doesn't leave carousels empty */
    private val fetchMultiplier = 3

    private suspend fun fetchEventsForCarousel(
        city: String,
        carousel: CarouselDto,
        userFilter: EventFilter,
    ): List<Event> {
        val fetchLimit = carouselDisplayLimit * fetchMultiplier
        return when (carousel.filterType) {
            "category" -> {
                val carouselCat = carousel.filterValue ?: return emptyList()
                if (userFilter is EventFilter.Category && userFilter.value != carouselCat) return emptyList()
                queryEvents(city, userFilter.takeIf { it !is EventFilter.None } ?: EventFilter.Category(carouselCat), limit = fetchLimit)
            }
            "date_range" -> queryEvents(city, userFilter, dateRange = carousel.filterValue ?: "this_weekend", limit = fetchLimit)
            "tag" -> queryEvents(city, userFilter, tag = carousel.filterValue, limit = fetchLimit)
            "top10_weekly" -> {
                val pool = queryEvents(city, userFilter, dateRange = "this_week", limit = 80)
                rankForColdStart(pool, maxResults = carouselDisplayLimit)
            }
            "trending" -> queryEvents(city, userFilter, limit = fetchLimit)
            else -> emptyList()
        }
    }

    private fun nowIso(): String = Clock.System.now().toString()

    private fun daysFromNowIso(n: Int): String =
        Clock.System.now().plus(n.days).toString()

    private val knownCategories = listOf("music", "sports", "arts", "comedy", "food", "outdoors", "nightlife")

    private suspend fun queryEvents(
        city: String,
        filter: EventFilter,
        dateRange: String? = null,
        tag: String? = null,
        limit: Int = 45,
    ): List<Event> {
        val now = nowIso()
        return supabaseClient.from("events")
            .select {
                filter {
                    eq("city", city)
                    gte("start_at", now)

                    when (dateRange) {
                        "this_weekend" -> lte("start_at", daysFromNowIso(3))
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
                        is EventFilter.Category -> eq("category", filter.value)
                        is EventFilter.VenueLike -> ilike("venue_name", filter.pattern)
                        is EventFilter.Subcategory -> eq("subcategory", filter.value)
                        is EventFilter.ExcludeKnown -> {
                            knownCategories.forEach { cat -> neq("category", cat) }
                        }
                    }
                }
                order("start_at", Order.ASCENDING)
                limit(limit.toLong())
            }
            .decodeList<EventDto>()
            .map { it.toDomain() }
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
            return copy(category = "other", subcategory = "administrative")
        }

        // Check if this venue is known to be miscategorized
        val venueWrongCat = miscategorizedVenues.entries.find { venueLc.contains(it.key) }

        // Film detection — works for any venue
        val isFilm = filmTitlePatterns.any { titleLc.contains(it) } ||
            knownFilmTitles.any { titleLc.contains(it) } ||
            Regex("\\(\\d{4}\\)").containsMatchIn(title) // year in parens like (1986)
        if (isFilm) return copy(category = "arts", subcategory = "film")

        // Only reclassify further if this is a known-bad venue
        if (venueWrongCat != null && category == venueWrongCat.value) {
            // Music detection
            if (musicTitlePatterns.any { titleLc.contains(it) } ||
                knownMusicActs.any { titleLc.contains(it) }
            ) return copy(category = "music", subcategory = null)

            // Open mic → nightlife
            if (titleLc.contains("open mic")) return copy(category = "nightlife", subcategory = null)

            // Science/kids shows → arts
            if (titleLc.contains("science") || titleLc.contains("wacky")) {
                return copy(category = "arts", subcategory = "family")
            }

            // "Boston Comics" or actual comedy acts stay as comedy
            if (titleLc.contains("comics") || titleLc.contains("comedy") ||
                titleLc.contains("improv") || titleLc.contains("stand-up") ||
                titleLc.contains("standup")
            ) return this

            // Default: reclassify as arts for an arts center
            if (venueLc.contains("arts") || venueLc.contains("theatre") || venueLc.contains("theater")) {
                return copy(category = "arts", subcategory = null)
            }
        }

        return this
    }

    // ── Post-processing ──

    private fun cleanEvents(events: List<Event>): List<Event> {
        return events
            .map { it.reclassified().withCleanedTitle().withCleanedVenue() }
            .filterNot { isGovernmentNoise(it) }
            .filterNot { isLivestreamDuplicate(it, events) }
            .distinctByBaseTitle()
    }

    private fun isGovernmentNoise(event: Event): Boolean {
        return event.category == "other" && event.subcategory == "administrative"
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
     * Lower = better. Combines relevance (higher is better) and proximity
     * into a single sort key so closer, more relevant events come first.
     */
    private fun scoreForSort(event: Event): Double {
        val relevance = coldStartScore(event) // 0..~1.3
        val distance = event.distanceMiles ?: 50.0
        // Proximity score: 1.0 at 0 mi, decays toward 0
        val proximity = 1.0 / (1.0 + distance / 5.0)
        // Combined: higher is better, negate for ascending sort
        return -(relevance * 0.6 + proximity * 0.4)
    }

    // ── Cold-start relevance ranking ──

    private fun rankForColdStart(events: List<Event>, maxResults: Int): List<Event> {
        val scored = events.map { it to coldStartScore(it) }
            .sortedByDescending { it.second }

        val result = mutableListOf<Event>()
        val categoryCounts = mutableMapOf<String, Int>()
        for ((event, _) in scored) {
            if (result.size >= maxResults) break
            val catCount = categoryCounts.getOrElse(event.category) { 0 }
            if (catCount >= 3) continue
            result.add(event)
            categoryCounts[event.category] = catCount + 1
        }
        return result
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

    private fun coldStartScore(event: Event): Double {
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

        return score
    }
}
