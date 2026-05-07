package live.localapp.local.domain.repository

import live.localapp.local.domain.model.Event
import live.localapp.local.domain.model.Venue

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
    suspend fun getUpcomingEventsForVenue(venueName: String, city: String): List<Event>
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
