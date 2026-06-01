package live.localapp.local.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import live.localapp.local.Config
import live.localapp.local.decodeHtmlEntities
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.model.Venue
import live.localapp.local.domain.repository.EventPage
import live.localapp.local.domain.repository.ExploreRepository
import live.localapp.local.domain.repository.PlacePage
import live.localapp.local.domain.repository.VenueWithMeta

class HttpExploreRepository : ExploreRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(ContentEncoding) {
            gzip()
            deflate()
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }

    private val eventsUrl = "${Config.SUPABASE_URL}/functions/v1/explore-events"
    private val placesUrl = "${Config.SUPABASE_URL}/functions/v1/explore-places"

    override suspend fun fetchEvents(
        userLat: Double,
        userLng: Double,
        userId: String?,
        query: String?,
        category: String?,
        radiusMiles: Double?,
        dateFilter: String?,
        dateStart: String?,
        dateEnd: String?,
        tzOffsetMinutes: Int?,
        cursor: String?,
        limit: Int,
    ): EventPage {
        val res = httpClient.get(eventsUrl) {
            header("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
            parameter("lat", userLat)
            parameter("lng", userLng)
            userId?.let { parameter("user_id", it) }
            query?.takeIf { it.isNotBlank() }?.let { parameter("query", it) }
            category?.takeIf { it.isNotBlank() && it != "foryou" }?.let { parameter("category", it) }
            radiusMiles?.let { parameter("radius", it) }
            dateFilter?.takeIf { it.isNotBlank() }?.let { parameter("date_filter", it) }
            dateStart?.takeIf { it.isNotBlank() }?.let { parameter("date_start", it) }
            dateEnd?.takeIf { it.isNotBlank() }?.let { parameter("date_end", it) }
            tzOffsetMinutes?.let { parameter("tz_offset_min", it) }
            cursor?.let { parameter("cursor", it) }
            parameter("limit", limit)
        }
        val body = res.requireSuccess("explore-events")
        val parsed = json.decodeFromString<EventPageDto>(body)
        return EventPage(
            items = parsed.items.map { it.toDomain() },
            cursor = parsed.cursor,
            hasMore = parsed.hasMore,
            total = parsed.total,
        )
    }

    override suspend fun fetchPlaces(
        userLat: Double,
        userLng: Double,
        userId: String?,
        query: String?,
        category: String?,
        radiusMiles: Double?,
        hasEventsOnly: Boolean,
        sort: String?,
        cursor: String?,
        limit: Int,
    ): PlacePage {
        val res = httpClient.get(placesUrl) {
            header("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
            parameter("lat", userLat)
            parameter("lng", userLng)
            userId?.let { parameter("user_id", it) }
            query?.takeIf { it.isNotBlank() }?.let { parameter("query", it) }
            category?.takeIf { it.isNotBlank() && it != "foryou" }?.let { parameter("category", it) }
            radiusMiles?.let { parameter("radius", it) }
            if (hasEventsOnly) parameter("has_events", "true")
            sort?.takeIf { it.isNotBlank() }?.let { parameter("sort", it) }
            cursor?.let { parameter("cursor", it) }
            parameter("limit", limit)
        }
        val body = res.requireSuccess("explore-places")
        val parsed = json.decodeFromString<PlacePageDto>(body)
        return PlacePage(
            items = parsed.items.map { it.toDomain() },
            cursor = parsed.cursor,
            hasMore = parsed.hasMore,
            total = parsed.total,
        )
    }
}

/**
 * Read the response body, but only after asserting a 2xx status. Without
 * this guard, a non-2xx body (`{"message":"..."}`, an HTML error page,
 * etc.) decodes silently into a default-constructed `EventPageDto` /
 * `PlacePageDto` — every field defaulted to empty — and the UI renders
 * "Nothing here yet" instead of surfacing the real error. Throwing here
 * routes the failure through the existing `catch` in PlacesViewModel,
 * which flips `state.error` so the empty-state shows the "Couldn't load
 * this list" + Retry path.
 */
private suspend fun HttpResponse.requireSuccess(label: String): String {
    val body = bodyAsText()
    if (!status.isSuccess()) {
        val snippet = body.take(200)
        throw IllegalStateException("$label HTTP ${status.value}: $snippet")
    }
    return body
}

// ── DTOs ──

@Serializable
private data class EventPageDto(
    val items: List<ExploreEventDto> = emptyList(),
    val cursor: String? = null,
    val hasMore: Boolean = false,
    val total: Int = 0,
)

@Serializable
private data class ExploreEventDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val category: String = "",
    val subcategory: String? = null,
    val startAt: String? = null,
    val endAt: String? = null,
    val isFree: Boolean = false,
    val priceMin: Double? = null,
    val priceMax: Double? = null,
    val ticketUrl: String? = null,
    val imageUrl: String? = null,
    val venueName: String? = null,
    val venueCity: String = "",
    val venueLat: Double? = null,
    val venueLng: Double? = null,
    val eventCity: String? = null,
    val eventState: String? = null,
    val eventLat: Double? = null,
    val eventLng: Double? = null,
    val city: String = "",
    val sourceUrl: String? = null,
    val distanceMiles: Double? = null,
) {
    // Decode HTML entities at the DTO boundary — without this, raw `&#8211;`
    // / `&amp;` / `&hellip;` from municipal-feed and crawler-sourced titles
    // leak through to the iOS native event detail (which receives the
    // domain `Event` and renders `event.title` directly). EventDto.toDomain
    // has the same decode; this DTO is a separate explore-only path that
    // was missing it.
    fun toDomain() = Event(
        id = id,
        title = title.decodeHtmlEntities(),
        description = description?.decodeHtmlEntities(),
        category = category,
        subcategory = subcategory,
        subSubcategory = null,
        imageUrl = imageUrl,
        startAt = startAt,
        endAt = endAt,
        isFree = isFree,
        priceMin = priceMin,
        priceMax = priceMax,
        ticketUrl = ticketUrl,
        venueName = venueName?.decodeHtmlEntities(),
        venueAddress = null,
        venueCity = venueCity,
        venueState = null,
        venueLat = venueLat,
        venueLng = venueLng,
        eventCity = eventCity,
        eventState = eventState,
        eventLat = eventLat,
        eventLng = eventLng,
        city = city.ifEmpty { eventCity ?: venueCity },
        source = null,
        sourceUrl = sourceUrl,
        distanceMiles = distanceMiles,
        venueDescription = null,
    )
}

@Serializable
private data class PlacePageDto(
    val items: List<ExplorePlaceDto> = emptyList(),
    val cursor: String? = null,
    val hasMore: Boolean = false,
    val total: Int = 0,
)

@Serializable
private data class ExplorePlaceDto(
    val id: String,
    val name: String,
    val address: String? = null,
    val city: String = "",
    val state: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val photoUrl: String? = null,
    val description: String? = null,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val distanceMiles: Double? = null,
    val googleRating: Double? = null,
    val website: String? = null,
    val reservationUrl: String? = null,
    val upcomingEventCount: Int = 0,
    val publicInterestRank: Int? = null,
    val isFeatured: Boolean = false,
    /** Inline events from the same server query that produced upcomingEventCount,
     *  so the venue detail uses the exact same source as the card. Capped
     *  server-side at MAX_EVENTS_PER_VENUE. */
    val upcomingEvents: List<ExploreEventDto> = emptyList(),
) {
    fun toDomain(): VenueWithMeta = VenueWithMeta(
        venue = Venue(
            id = id,
            // Decode HTML entities at the DTO boundary. VenueDto.toDomain
            // already does this; the explore-tab DTO was missing it, so raw
            // `&amp;` / `&#8211;` etc. leaked into the Places UI and into
            // event detail screens that read venue.name from this path.
            name = name.decodeHtmlEntities(),
            address = address,
            city = city,
            state = state,
            country = null,
            latitude = lat,
            longitude = lng,
            imageUrl = photoUrl,
            photoUrl = photoUrl,
            description = description?.decodeHtmlEntities(),
            publicInterestRank = publicInterestRank,
            category = category,
            placeType = null,
            googleRating = googleRating,
            website = website,
            reservationUrl = reservationUrl,
        ),
        distanceMiles = distanceMiles,
        upcomingEventCount = upcomingEventCount,
        upcomingEvents = upcomingEvents.map { it.toDomain() },
        primaryCategory = category,
        tags = tags,
        isFeatured = isFeatured,
        googleRating = googleRating,
    )
}
