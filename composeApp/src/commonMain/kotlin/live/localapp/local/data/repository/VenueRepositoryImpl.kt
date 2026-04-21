package live.localapp.local.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import live.localapp.local.data.remote.dto.EventDto
import live.localapp.local.data.remote.dto.VenueDto
import live.localapp.local.data.remote.dto.toDomain
import live.localapp.local.domain.model.Event
import live.localapp.local.snakeToTitleCase
import live.localapp.local.domain.model.Venue
import live.localapp.local.domain.model.distanceMiles
import live.localapp.local.domain.repository.VenueRepository
import live.localapp.local.domain.repository.VenueWithMeta

class VenueRepositoryImpl(
    private val supabaseClient: SupabaseClient,
) : VenueRepository {

    override suspend fun getVenues(
        city: String,
        userLat: Double,
        userLng: Double,
    ): List<VenueWithMeta> {
        // 1. Fetch active venues within geo-radius of user.
        // 100 miles is the scrollable-list cap; search uses a wider fetch.
        val radiusMiles = 100.0
        val latDelta = radiusMiles / 69.0
        val lngDelta = radiusMiles / 50.0
        val venues = supabaseClient.from("venues")
            .select {
                filter {
                    eq("is_active", true)
                    gte("lat", userLat - latDelta)
                    lte("lat", userLat + latDelta)
                    gte("lng", userLng - lngDelta)
                    lte("lng", userLng + lngDelta)
                }
            }
            .decodeList<VenueDto>()
            .map { it.toDomain() }

        // Events are NOT fetched during grid load — loading the page used to pull
        // ~1000 events in a bbox query just to compute counts + derive tags, which
        // bloated first paint. Event lists + counts now load lazily on venue detail
        // open (see PlacesViewModel.selectVenue + getUpcomingEventsForVenue).

        val topRank = venues.maxByOrNull { it.publicInterestRank ?: 0 }

        return venues.mapNotNull { venue ->
            val lat = venue.latitude ?: return@mapNotNull null
            val lng = venue.longitude ?: return@mapNotNull null
            val dist = distanceMiles(userLat, userLng, lat, lng)

            val primaryCat = venue.category
            val tags = venue.category?.let { listOf(categoryDisplayName(it)) } ?: emptyList()

            VenueWithMeta(
                venue = venue,
                distanceMiles = dist,
                upcomingEventCount = 0,
                upcomingEvents = emptyList(),
                primaryCategory = primaryCat,
                tags = tags,
                isFeatured = venue.id == topRank?.id,
                googleRating = venue.googleRating,
            )
        }.let { deduplicateVenues(it) }
    }

    /** Merge duplicate venues (e.g. "The Wilbur Theatre" and "Wilbur Theatre"). */
    private fun deduplicateVenues(venues: List<VenueWithMeta>): List<VenueWithMeta> {
        val groups = venues.groupBy { normalizeVenueName(it.venue.name) }
        return groups.values.map { dupes ->
            if (dupes.size == 1) return@map dupes.first()
            // Keep the best venue: prefer higher rank, then more events, then has photo
            val best = dupes.sortedWith(
                compareByDescending<VenueWithMeta> { it.venue.publicInterestRank ?: 0 }
                    .thenByDescending { it.upcomingEventCount }
                    .thenByDescending { it.venue.photoUrl != null }
            ).first()
            // Events aren't populated on grid load (see getVenues comment) — leave
            // both fields untouched; PlacesViewModel.selectVenue hydrates on click.
            best
        }
    }

    private fun normalizeVenueName(name: String): String =
        name.lowercase().trim()
            .removePrefix("the ")
            .replace(Regex("[^a-z0-9\\s]"), "")
            .trim()

    override suspend fun getUpcomingEventsForVenue(
        venueName: String,
        city: String,
    ): List<Event> {
        val nowIso = kotlin.time.Clock.System.now().toString()
        // Strip common prefixes for fuzzy matching (e.g. "The Wilbur Theatre" -> "Wilbur Theatre")
        val coreName = venueName.trim()
            .removePrefix("The ").removePrefix("the ")
            .trim()
        // City filter dropped on purpose — venue.city vs event.city casing mismatches
        // were silently returning zero rows. Venue name is unique enough in practice.
        return try {
            supabaseClient.from("events")
                .select {
                    filter {
                        eq("is_active", true)
                        ilike("venue_name", "%${coreName}%")
                        gte("start_at", nowIso)
                    }
                    order("start_at", Order.ASCENDING)
                    limit(50)
                }
                .decodeList<EventDto>()
                .map { it.toDomain() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun searchVenues(
        location: String?,
        category: String?,
        keyword: String?,
        limit: Int,
    ): List<Venue> {
        return supabaseClient.from("venues")
            .select {
                filter {
                    eq("is_active", true)
                    // Global minimum-quality floor — hide rank <= 10 venues
                    // (community centers, K-12 fields, junk listings) from
                    // every discovery surface including Yocal chat results.
                    gt("public_interest_rank", 10)
                    if (!location.isNullOrBlank()) {
                        ilike("city", "%${location}%")
                    }
                    if (!category.isNullOrBlank()) {
                        or {
                            ilike("category", "%${category}%")
                            ilike("place_type", "%${category}%")
                        }
                    }
                    if (!keyword.isNullOrBlank()) {
                        ilike("name", "%${keyword}%")
                    }
                }
                limit(limit.toLong())
            }
            .decodeList<VenueDto>()
            .map { it.toDomain() }
    }

    /**
     * Cache for the life of the repository singleton — low-rank venue set
     * barely changes (3-5 rows) and is needed on every chat-tool call, so
     * caching here avoids per-turn round-trips.
     */
    private var lowRankVenueCache: Set<String>? = null

    override suspend fun getLowRankVenueNames(): Set<String> {
        lowRankVenueCache?.let { return it }
        val rows = try {
            supabaseClient.from("venues")
                .select(io.github.jan.supabase.postgrest.query.Columns.list("name")) {
                    filter {
                        lte("public_interest_rank", 10)
                        eq("is_active", true)
                    }
                }
                .decodeList<LowRankVenueRow>()
        } catch (_: Exception) {
            emptyList()
        }
        val set = buildSet {
            for (r in rows) {
                val raw = r.name.trim()
                if (raw.isNotEmpty()) {
                    add(raw.lowercase())
                    // Normalized form: strip "the " and non-alphanumerics — aligns
                    // with how edge functions build their venue-name keys.
                    add(raw.lowercase().removePrefix("the ").replace(Regex("[^a-z0-9\\s]"), "").trim())
                }
            }
        }
        lowRankVenueCache = set
        return set
    }

    override suspend fun findVenueByNameAndCity(name: String, city: String): Venue? {
        return try {
            supabaseClient.from("venues")
                .select {
                    filter {
                        ilike("name", name)
                        ilike("city", city)
                    }
                    limit(1)
                }
                .decodeList<VenueDto>()
                .firstOrNull()
                ?.toDomain()
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getVenuesByIds(ids: List<String>): List<Venue> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(100).flatMap { chunk ->
            supabaseClient.from("venues")
                .select {
                    filter { isIn("id", chunk) }
                }
                .decodeList<VenueDto>()
                .map { it.toDomain() }
        }
    }

    override suspend fun searchByName(name: String): Venue? {
        return try {
            supabaseClient.from("venues")
                .select {
                    filter { ilike("name", "%${name}%") }
                    limit(1)
                }
                .decodeList<VenueDto>()
                .firstOrNull()
                ?.toDomain()
        } catch (_: Throwable) { null }
    }

    override suspend fun searchByNameWide(
        query: String,
        userLat: Double,
        userLng: Double,
        limit: Int,
    ): List<VenueWithMeta> {
        if (query.isBlank()) return emptyList()
        // Cap to 100mi around the user — same as getVenues. Prevents
        // far-away venues from leaking into the Places list via search.
        val radiusMiles = 100.0
        val latDelta = radiusMiles / 69.0
        val lngDelta = radiusMiles / 50.0
        return try {
            val raw = supabaseClient.from("venues")
                .select {
                    filter {
                        eq("is_active", true)
                        ilike("name", "%${query}%")
                        gte("lat", userLat - latDelta)
                        lte("lat", userLat + latDelta)
                        gte("lng", userLng - lngDelta)
                        lte("lng", userLng + lngDelta)
                    }
                    limit(limit.toLong())
                }
                .decodeList<VenueDto>()
                .map { it.toDomain() }
            raw.mapNotNull { venue ->
                val lat = venue.latitude ?: return@mapNotNull null
                val lng = venue.longitude ?: return@mapNotNull null
                val dist = distanceMiles(userLat, userLng, lat, lng)
                // Belt-and-suspenders: drop anything >100mi in case the
                // bbox filter isn't tight enough near high latitudes.
                if (dist > radiusMiles) return@mapNotNull null
                VenueWithMeta(
                    venue = venue,
                    distanceMiles = dist,
                    upcomingEventCount = 0,
                    upcomingEvents = emptyList(),
                    primaryCategory = venue.category,
                    tags = venue.category?.let { listOf(categoryDisplayName(it)) } ?: emptyList(),
                    googleRating = venue.googleRating,
                )
            }
        } catch (_: Throwable) { emptyList() }
    }

    private fun categoryDisplayName(cat: String): String = when (cat) {
        "music" -> "Music"
        "sports" -> "Sports"
        "arts" -> "Arts"
        "theatre" -> "Theatre"
        "comedy" -> "Comedy"
        "food" -> "Food"
        "nightlife" -> "Nightlife"
        "festivals" -> "Festivals"
        "family" -> "Family"
        else -> cat.snakeToTitleCase()
    }
}

@kotlinx.serialization.Serializable
private data class LowRankVenueRow(val name: String)
