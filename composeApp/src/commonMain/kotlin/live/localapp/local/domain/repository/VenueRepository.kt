package live.localapp.local.domain.repository

import live.localapp.local.domain.model.Event
import live.localapp.local.domain.model.Venue

/**
 * Max number of upcoming events [VenueRepository.getUpcomingEventsForVenue]
 * returns. Counts that hit this cap should be rendered as "N+" since the true
 * total may be higher (see [formatVenueEventCount]).
 */
const val VENUE_UPCOMING_EVENTS_CAP = 50

/**
 * Formats an upcoming-event count for a venue chip, e.g. "1 event", "12 events",
 * or "50+ events" when the count is capped. Returns null for zero/negative.
 */
fun formatVenueEventCount(count: Int): String? {
    if (count <= 0) return null
    val suffix = if (count == 1) "event" else "events"
    return if (count >= VENUE_UPCOMING_EVENTS_CAP) "$VENUE_UPCOMING_EVENTS_CAP+ $suffix"
    else "$count $suffix"
}

data class VenueWithMeta(
    val venue: Venue,
    val distanceMiles: Double? = null,
    val upcomingEventCount: Int = 0,
    val upcomingEvents: List<Event> = emptyList(),
    val primaryCategory: String? = null,
    val tags: List<String> = emptyList(),
    val isFeatured: Boolean = false,
    val googleRating: Double? = null,
)

interface VenueRepository {
    suspend fun getVenues(city: String, userLat: Double, userLng: Double): List<VenueWithMeta>
    /**
     * Upcoming events for a venue, matched by fuzzy name. When [venueLat]/[venueLng]
     * are supplied, results are also constrained to a small geo-bbox around the
     * venue so same-named venues in other cities (e.g. "Institute of Contemporary
     * Art" in Boston vs Philadelphia) don't bleed into each other. Coordinates are
     * optional for back-compat; omit them only when the caller has no location.
     */
    suspend fun getUpcomingEventsForVenue(
        venueName: String,
        city: String,
        venueLat: Double? = null,
        venueLng: Double? = null,
    ): List<Event>
    /**
     * Names of venues with public_interest_rank <= 10 — the global minimum-
     * quality floor. Returned in original (DB) casing plus normalized forms
     * so callers can filter events whose venue_name matches. Cached by impl.
     */
    suspend fun getLowRankVenueNames(): Set<String>
    suspend fun searchVenues(
        location: String? = null,
        category: String? = null,
        keyword: String? = null,
        limit: Int = 20,
        // When coords + radius are supplied (e.g. Bo's chat search scope), filter
        // by a geo bounding box instead of the city-name substring — so the scope
        // the user set in chat is actually honored. Falls back to `location` ILIKE
        // when coords are absent.
        userLat: Double? = null,
        userLng: Double? = null,
        radiusMiles: Double? = null,
    ): List<Venue>
    suspend fun findVenueByNameAndCity(name: String, city: String): Venue?
    suspend fun getVenuesByIds(ids: List<String>): List<Venue>
    suspend fun searchByName(name: String): Venue?
    /** Persist a generated description on a venue row. */
    suspend fun updateVenueDescription(id: String, description: String)
    /**
     * Wide-scope name search that ignores the user's geo-bbox. Used by the Places
     * page when the user types a search query — lets us surface venues further
     * out than the default 100mi scrollable list.
     */
    suspend fun searchByNameWide(query: String, userLat: Double, userLng: Double, limit: Int = 60): List<VenueWithMeta>
}
