package live.localapp.local.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
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

    override suspend fun getUpcomingEvents(city: String, fromIso: String, limit: Int): List<Event> {
        return supabaseClient.from("events")
            .select {
                filter {
                    eq("city", city)
                    gte("start_at", fromIso)
                }
                order("start_at", Order.ASCENDING)
                limit(limit.toLong())
            }
            .decodeList<EventDto>()
            .map { it.toDomain() }
    }

    override suspend fun getEventsByIds(ids: List<String>): List<Event> {
        if (ids.isEmpty()) return emptyList()
        // Supabase `isIn` has practical limits; paginate in chunks of 100.
        return ids.chunked(100).flatMap { chunk ->
            supabaseClient.from("events")
                .select {
                    filter { isIn("id", chunk) }
                }
                .decodeList<EventDto>()
                .map { it.toDomain() }
        }
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

    override suspend fun searchEvents(
        location: String?,
        category: String?,
        dateRange: String?,
        keyword: String?,
        limit: Int,
        startDate: String?,
        endDate: String?,
        userLat: Double?,
        userLng: Double?,
        radiusMiles: Double?,
    ): List<Event> {
        val now = kotlin.time.Clock.System.now()
        val nowIso = now.toString()
        val tz = TimeZone.currentSystemDefault()

        val todayLocal = now.toLocalDateTime(tz)
        val todayDow = todayLocal.date.dayOfWeek

        // Helper: find the start of next Friday from today
        fun daysUntilFriday(): Int = when (todayDow) {
            DayOfWeek.FRIDAY -> 7
            DayOfWeek.SATURDAY -> 6
            DayOfWeek.SUNDAY -> 5
            DayOfWeek.MONDAY -> 4
            DayOfWeek.TUESDAY -> 3
            DayOfWeek.WEDNESDAY -> 2
            DayOfWeek.THURSDAY -> 1
            else -> 7
        }

        // Helper: find end of this coming Sunday/Monday
        fun daysUntilEndOfWeekend(): Int = when (todayDow) {
            DayOfWeek.FRIDAY -> 3
            DayOfWeek.SATURDAY -> 2
            DayOfWeek.SUNDAY -> 1
            DayOfWeek.MONDAY -> 7
            DayOfWeek.TUESDAY -> 6
            DayOfWeek.WEDNESDAY -> 5
            DayOfWeek.THURSDAY -> 4
            else -> 7
        }

        // Calculate start and end of date range
        data class DateWindow(val start: String, val end: String)

        val dateWindow: DateWindow? = when (dateRange) {
            "today" -> {
                val tomorrow = todayLocal.date.plus(1, DateTimeUnit.DAY)
                DateWindow(nowIso, "${tomorrow}T00:00:00Z")
            }
            "this_weekend" -> {
                val fridayDays = if (todayDow == DayOfWeek.FRIDAY || todayDow == DayOfWeek.SATURDAY || todayDow == DayOfWeek.SUNDAY) 0
                    else daysUntilFriday()
                val friday = todayLocal.date.plus(if (fridayDays == 0) 0 else fridayDays, DateTimeUnit.DAY)
                val monday = todayLocal.date.plus(daysUntilEndOfWeekend(), DateTimeUnit.DAY)
                DateWindow("${friday}T00:00:00Z", "${monday}T00:00:00Z")
            }
            "next_weekend" -> {
                val nextFriday = todayLocal.date.plus(daysUntilFriday(), DateTimeUnit.DAY)
                val nextMonday = nextFriday.plus(3, DateTimeUnit.DAY)
                DateWindow("${nextFriday}T00:00:00Z", "${nextMonday}T00:00:00Z")
            }
            "this_week" -> {
                val daysUntilSunday = 7 - todayDow.ordinal
                val endDate = todayLocal.date.plus(daysUntilSunday + 1, DateTimeUnit.DAY)
                DateWindow(nowIso, "${endDate}T00:00:00Z")
            }
            "next_week" -> {
                val daysUntilNextMonday = (DayOfWeek.MONDAY.ordinal - todayDow.ordinal + 7) % 7
                val nextMonday = todayLocal.date.plus(if (daysUntilNextMonday == 0) 7 else daysUntilNextMonday, DateTimeUnit.DAY)
                val nextSunday = nextMonday.plus(7, DateTimeUnit.DAY)
                DateWindow("${nextMonday}T00:00:00Z", "${nextSunday}T00:00:00Z")
            }
            "this_month" -> {
                val endDt = todayLocal.date.plus(30, DateTimeUnit.DAY)
                DateWindow(nowIso, "${endDt}T00:00:00Z")
            }
            "custom" -> {
                if (startDate != null) {
                    val end = endDate ?: startDate.take(8) + (startDate.takeLast(2).toIntOrNull()?.plus(1)?.toString()?.padStart(2, '0') ?: "01")
                    DateWindow("${startDate}T00:00:00Z", "${end}T23:59:59Z")
                } else null
            }
            else -> null
        }

        return supabaseClient.from("events")
            .select {
                filter {
                    eq("is_active", true)
                    gte("start_at", dateWindow?.start ?: nowIso)
                    if (dateWindow != null) {
                        lt("start_at", dateWindow.end)
                    }
                    // If geo search, skip city filter — we'll filter by distance after
                    if (userLat == null && !location.isNullOrBlank()) {
                        ilike("venue_city", "%${location}%")
                    }
                    if (!category.isNullOrBlank()) {
                        or {
                            ilike("subcategory", "%${category}%")
                            ilike("category", "%${category}%")
                        }
                    }
                    if (!keyword.isNullOrBlank()) {
                        or {
                            ilike("title", "%${keyword}%")
                            ilike("venue_name", "%${keyword}%")
                        }
                    }
                }
                order("start_at", Order.ASCENDING)
                limit(if (userLat != null) 200L else limit.toLong())
            }
            .decodeList<EventDto>()
            .map { it.toDomain() }
            .let { events ->
                if (userLat != null && userLng != null) {
                    val radius = radiusMiles ?: 15.0
                    events.filter { e ->
                        val lat = e.venueLat ?: return@filter false
                        val lng = e.venueLng ?: return@filter false
                        live.localapp.local.domain.model.distanceMiles(userLat, userLng, lat, lng) <= radius
                    }.take(limit)
                } else events
            }
    }
}
