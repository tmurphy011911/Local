package live.localapp.local.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import live.localapp.local.data.remote.dto.EventDto
import live.localapp.local.data.remote.dto.toDomain
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.repository.EventRepository

class EventRepositoryImpl(
    private val supabaseClient: SupabaseClient,
) : EventRepository {

    override suspend fun getEvents(city: String): List<Event> {
        return supabaseClient.from("events")
            .select {
                filter { eq("city", city) }
                order("start_at", Order.ASCENDING)
            }
            .decodeList<EventDto>()
            .map { it.toDomain() }
    }

    override suspend fun getEvent(id: String): Event? {
        return supabaseClient.from("events")
            .select {
                filter { eq("id", id) }
                limit(1)
            }
            .decodeList<EventDto>()
            .firstOrNull()
            ?.toDomain()
    }

    override suspend fun getEventsByCategory(city: String, category: String): List<Event> {
        return supabaseClient.from("events")
            .select {
                filter {
                    eq("city", city)
                    eq("category", category)
                }
                order("start_at", Order.ASCENDING)
            }
            .decodeList<EventDto>()
            .map { it.toDomain() }
    }
}
