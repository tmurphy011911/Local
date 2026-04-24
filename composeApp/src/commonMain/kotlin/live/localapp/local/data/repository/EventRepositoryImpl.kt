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

        val rawEvents = supabaseClient.from("events")
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
                limit(limit.toLong().coerceAtMost(10000))
            }
            .decodeList<EventDto>()
            .map { it.toDomain() }

        // Backfill missing event coords from the venues table BEFORE geo
        // filtering — many crawled events have null venue_lat/venue_lng on
        // the event row, but the venue itself has coords. Without this
        // hydration the geo filter silently drops events whose venue is
        // perfectly within radius (e.g. Yocal asking "what's on at Musikfest
        // Café" returned nothing because Musikfest event rows had null
        // coords even though the venue is 17 mi away in Bethlehem).
        val coordHydrated = hydrateVenueCoords(rawEvents)

        val geoFiltered = if (userLat != null && userLng != null) {
            val radius = radiusMiles ?: 20.0
            coordHydrated.filter { e ->
                val lat = e.venueLat
                val lng = e.venueLng
                // If we still have no coords for the venue (truly missing in
                // both events + venues tables) and the caller supplied a
                // keyword, keep the event — the keyword is a strong signal
                // the user wants this specific venue/title regardless of
                // geo. For pure geo browsing (no keyword) we still drop
                // unknown-coord events to avoid noise.
                if (lat == null || lng == null) {
                    return@filter !keyword.isNullOrBlank()
                }
                live.localapp.local.domain.model.distanceMiles(userLat, userLng, lat, lng) <= radius
            }
        } else coordHydrated

        return hydrateVenueImages(geoFiltered)
    }

    /**
     * Backfills missing event.venueLat/venueLng from the venues table by
     * looking up the matching venue's coordinates by name. Mirrors the
     * one-batch-query pattern used by [hydrateVenueImages].
     */
    private suspend fun hydrateVenueCoords(events: List<Event>): List<Event> {
        val needsCoords = events.filter { (it.venueLat == null || it.venueLng == null) && !it.venueName.isNullOrBlank() }
        if (needsCoords.isEmpty()) return events

        val venueNames = needsCoords.mapNotNull { it.venueName?.trim() }.distinct()
        if (venueNames.isEmpty()) return events

        val coordsByName = try {
            val map = mutableMapOf<String, Pair<Double, Double>>()
            venueNames.chunked(200).forEach { batch ->
                val rows = supabaseClient.from("venues")
                    .select(io.github.jan.supabase.postgrest.query.Columns.list("name", "latitude", "longitude")) {
                        filter {
                            isIn("name", batch)
                        }
                    }
                    .decodeList<VenueCoordRow>()
                for (r in rows) {
                    val lat = r.latitude ?: continue
                    val lng = r.longitude ?: continue
                    map[r.name.lowercase().trim()] = lat to lng
                }
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }

        if (coordsByName.isEmpty()) return events

        return events.map { e ->
            if (e.venueLat != null && e.venueLng != null) e
            else {
                val key = e.venueName?.lowercase()?.trim() ?: return@map e
                val coords = coordsByName[key] ?: return@map e
                e.copy(venueLat = coords.first, venueLng = coords.second)
            }
        }
    }

    /**
     * Fills in missing event.imageUrl by looking up the matching venue's photo_url.
     * Events from structured crawls (Ticketmaster, Eventbrite) usually have images;
     * events from HTML-scraped venues often don't — and those are the ones that show
     * as placeholder tiles on the Explore grid. Venues almost always have Google
     * Places photos, so falling back produces a real image in 90%+ of cases.
     *
     * Executes at most ONE venues query regardless of how many events need hydrating.
     */
    private suspend fun hydrateVenueImages(events: List<Event>): List<Event> {
        val needsImage = events.filter { it.imageUrl.isNullOrBlank() && !it.venueName.isNullOrBlank() }
        if (needsImage.isEmpty()) return events

        val venueNames = needsImage.mapNotNull { it.venueName?.trim() }.distinct()
        if (venueNames.isEmpty()) return events

        val venueRows = try {
            // PostgREST `in.()` filter. Batch to cap URL length at ~200 names/req.
            val photosByName = mutableMapOf<String, String>()
            venueNames.chunked(200).forEach { batch ->
                val rows = supabaseClient.from("venues")
                    .select(io.github.jan.supabase.postgrest.query.Columns.list("name", "photo_url")) {
                        filter {
                            isIn("name", batch)
                        }
                    }
                    .decodeList<VenuePhotoRow>()
                for (r in rows) {
                    val photo = r.photoUrl ?: continue
                    if (photo.isNotBlank()) photosByName[r.name.lowercase().trim()] = photo
                }
            }
            photosByName
        } catch (_: Exception) {
            emptyMap()
        }

        if (venueRows.isEmpty()) return events

        return events.map { e ->
            if (!e.imageUrl.isNullOrBlank()) e
            else {
                val key = e.venueName?.lowercase()?.trim() ?: return@map e
                val fallback = venueRows[key] ?: return@map e
                e.copy(imageUrl = fallback)
            }
        }
    }
}

@kotlinx.serialization.Serializable
private data class VenuePhotoRow(
    val name: String,
    @kotlinx.serialization.SerialName("photo_url") val photoUrl: String? = null,
)

@kotlinx.serialization.Serializable
private data class VenueCoordRow(
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
)
