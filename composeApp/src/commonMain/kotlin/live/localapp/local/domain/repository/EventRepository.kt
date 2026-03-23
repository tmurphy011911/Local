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
    ): List<Event>
}
