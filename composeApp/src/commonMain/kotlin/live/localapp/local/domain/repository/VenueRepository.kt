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
    suspend fun searchVenues(
        location: String? = null,
        category: String? = null,
        keyword: String? = null,
        limit: Int = 20,
    ): List<Venue>
    suspend fun findVenueByNameAndCity(name: String, city: String): Venue?
    suspend fun getVenuesByIds(ids: List<String>): List<Venue>
    suspend fun searchByName(name: String): Venue?
}
