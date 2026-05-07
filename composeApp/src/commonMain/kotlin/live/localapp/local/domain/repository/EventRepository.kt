package live.localapp.local.domain.repository

import live.localapp.local.domain.model.Event

interface EventRepository {
    suspend fun getEvents(city: String): List<Event>
    suspend fun getEvent(id: String): Event?
    suspend fun getEventsByCategory(city: String, category: String): List<Event>
    suspend fun getUpcomingEvents(city: String, fromIso: String, limit: Int = 10000): List<Event>
    suspend fun getEventsByIds(ids: List<String>): List<Event>
    suspend fun searchEvents(
        location: String? = null,
        category: String? = null,
        dateRange: String? = null,
        keyword: String? = null,
        limit: Int = 20,
        startDate: String? = null,
        endDate: String? = null,
        userLat: Double? = null,
        userLng: Double? = null,
        radiusMiles: Double? = null,
    ): List<Event>

    /**
     * Three-lane stratified search (breadth + niche + keyword) used by the
     * Yocal/Bo chat to give the model a diverse cross-section of upcoming
     * events without forcing it to fan out across multiple narrow searches.
     * Distinct from [searchEvents] which is a strict filter — this one
     * intentionally returns events outside the keyword/category to ensure
     * coverage. Backed by `search_events_stratified` Postgres function.
     */
    suspend fun searchEventsBroad(
        userLat: Double,
        userLng: Double,
        radiusMiles: Double = 20.0,
        dateRange: String? = null,
        startDate: String? = null,
        endDate: String? = null,
        keyword: String? = null,
        limit: Int = 300,
    ): List<Event>
}
