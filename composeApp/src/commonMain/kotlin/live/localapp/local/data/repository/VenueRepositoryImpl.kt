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
        // 1. Fetch all active venues
        val venues = supabaseClient.from("venues")
            .select {
                filter {
                    eq("is_active", true)
                }
            }
            .decodeList<VenueDto>()
            .map { it.toDomain() }

        // 2. Fetch upcoming events using geo-radius (not city name)
        val nowIso = kotlin.time.Clock.System.now().toString()
        val radiusMiles = 30.0
        val latDelta = radiusMiles / 69.0
        val lngDelta = radiusMiles / 50.0
        val events = try {
            val upcoming = mutableListOf<EventDto>()
            for (page in 0 until 5) {
                val batch = supabaseClient.from("events")
                    .select {
                        filter {
                            eq("is_active", true)
                            gte("start_at", nowIso)
                            gte("venue_lat", userLat - latDelta)
                            lte("venue_lat", userLat + latDelta)
                            gte("venue_lng", userLng - lngDelta)
                            lte("venue_lng", userLng + lngDelta)
                        }
                        order("start_at", Order.ASCENDING)
                        limit(1000)
                        range((page * 1000).toLong(), ((page + 1) * 1000 - 1).toLong())
                    }
                    .decodeList<EventDto>()
                upcoming.addAll(batch)
                if (batch.size < 1000) break
            }
            upcoming
                .distinctBy { it.id }
                .map { it.toDomain() }
        } catch (_: Exception) {
            emptyList()
        }

        // 3. Group events by venue name (lowercase)
        val eventsByVenue = events.groupBy { (it.venueName ?: "").lowercase().trim() }

        // 4. Determine the highest-ranked venue to feature
        val topRank = venues.maxByOrNull { it.publicInterestRank ?: 0 }

        // 5. Enrich venues with distance, event counts, categories
        return venues.mapNotNull { venue ->
            val lat = venue.latitude ?: return@mapNotNull null
            val lng = venue.longitude ?: return@mapNotNull null
            val dist = distanceMiles(userLat, userLng, lat, lng)
            val venueKey = venue.name.lowercase().trim()
            // Match events by exact name, or fuzzy containment for slight naming differences
            val venueEvents = eventsByVenue[venueKey]
                ?: eventsByVenue.entries
                    .filter { (eventVenue, _) ->
                        eventVenue.isNotEmpty() && (eventVenue.contains(venueKey) || venueKey.contains(eventVenue))
                    }
                    .flatMap { it.value }
                    .ifEmpty { null }
                ?: emptyList()

            // Use the venue's own category first, fall back to event-derived categories
            val eventCategories = venueEvents.mapNotNull { it.subcategory?.lowercase() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
            val primaryCat = venue.category ?: eventCategories.firstOrNull()?.key
            val tags = buildList {
                // Lead with the venue's own category if present
                venue.category?.let { add(categoryDisplayName(it)) }
                // Add event-derived categories
                eventCategories.take(3).forEach { (cat, _) ->
                    val display = categoryDisplayName(cat)
                    if (display !in this) add(display)
                }
            }.take(3)

            VenueWithMeta(
                venue = venue,
                distanceMiles = dist,
                upcomingEventCount = venueEvents.size,
                upcomingEvents = venueEvents,
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
            // Merge events from all duplicates
            val allEvents = dupes.flatMap { it.upcomingEvents }.distinctBy { it.id }
            best.copy(
                upcomingEventCount = allEvents.size,
                upcomingEvents = allEvents,
            )
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
        return try {
            supabaseClient.from("events")
                .select {
                    filter {
                        eq("city", city)
                        eq("is_active", true)
                        ilike("venue_name", "%${coreName}%")
                        gte("start_at", nowIso)
                    }
                    order("start_at", Order.ASCENDING)
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
