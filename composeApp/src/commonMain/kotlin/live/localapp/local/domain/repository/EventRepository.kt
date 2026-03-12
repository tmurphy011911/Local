package live.localapp.local.domain.repository

import live.localapp.local.domain.model.Event

interface EventRepository {
    suspend fun getEvents(city: String): List<Event>
    suspend fun getEvent(id: String): Event?
    suspend fun getEventsByCategory(city: String, category: String): List<Event>
}
