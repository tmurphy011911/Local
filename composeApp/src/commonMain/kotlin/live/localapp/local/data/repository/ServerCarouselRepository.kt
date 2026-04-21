package live.localapp.local.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import live.localapp.local.Config
import live.localapp.local.decodeHtmlEntities
import live.localapp.local.domain.model.CarouselSection
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.repository.CarouselRepository
import live.localapp.local.domain.repository.CategoryInfo
import live.localapp.local.domain.repository.UserPreferences

@Serializable
private data class CarouselResponse(
    val carousels: List<CarouselSectionDto>,
    val categories: List<CategoryInfoDto>,
    @SerialName("cached_at") val cachedAt: String,
)

@Serializable
private data class CarouselSectionDto(
    val slug: String,
    val title: String,
    val subtitle: String? = null,
    @SerialName("filter_type") val filterType: String,
    val events: List<EventDto>,
    @SerialName("show_saves") val showSaves: Boolean = false,
)

@Serializable
private data class EventDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val category: String,
    val subcategory: String? = null,
    @SerialName("sub_subcategory") val subSubcategory: String? = null,
    @SerialName("start_at") val startAt: String? = null,
    @SerialName("end_at") val endAt: String? = null,
    @SerialName("is_free") val isFree: Boolean = false,
    @SerialName("price_min") val priceMin: Double? = null,
    @SerialName("price_max") val priceMax: Double? = null,
    @SerialName("ticket_url") val ticketUrl: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("venue_name") val venueName: String? = null,
    @SerialName("venue_address") val venueAddress: String? = null,
    @SerialName("venue_city") val venueCity: String = "",
    @SerialName("venue_state") val venueState: String? = null,
    @SerialName("venue_lat") val venueLat: Double? = null,
    @SerialName("venue_lng") val venueLng: Double? = null,
    val source: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val city: String = "",
    @SerialName("distance_miles") val distanceMiles: Double? = null,
    @SerialName("venue_description") val venueDescription: String? = null,
)

@Serializable
private data class CategoryInfoDto(
    val id: String,
    val label: String,
    @SerialName("icon_url") val iconUrl: String,
    @SerialName("event_count") val eventCount: Int,
)

class ServerCarouselRepository : CarouselRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }

    private val edgeFunctionUrl = "${Config.SUPABASE_URL}/functions/v1/carousels"

    /** Cached categories from the last server response */
    private var cachedCategories: List<CategoryInfo>? = null

    /** Cached carousels per category tab — avoids re-fetching on tab switch */
    private val carouselCache = mutableMapOf<String, List<CarouselSection>>()

    override suspend fun getCarousels(
        userLat: Double,
        userLng: Double,
        category: String?,
        userPreferences: UserPreferences?,
        locationName: String,
        radiusMiles: Int,
    ): List<CarouselSection> {
        val cacheKey = category ?: "foryou"

        // Return cached if available
        carouselCache[cacheKey]?.let { return it }

        val response = httpClient.get(edgeFunctionUrl) {
            header("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
            parameter("lat", userLat)
            parameter("lng", userLng)
            parameter("category", cacheKey)
            parameter("location_name", locationName)
            parameter("radius", radiusMiles)
            userPreferences?.let { parameter("user_id", it.id) }
        }

        val body = response.bodyAsText()
        val parsed = json.decodeFromString<CarouselResponse>(body)

        // Cache categories from the response
        cachedCategories = parsed.categories.map { it.toDomain() }

        val carousels = parsed.carousels.map { it.toDomain() }
        carouselCache[cacheKey] = carousels
        return carousels
    }

    override suspend fun getAvailableCategories(
        userPreferences: UserPreferences?,
        userLat: Double,
        userLng: Double,
    ): List<CategoryInfo> {
        // Return cached categories if available
        cachedCategories?.let { return it }

        // If no cache, fetch carousels which populates categories
        getCarousels(
            userLat = userLat,
            userLng = userLng,
            category = null,
            userPreferences = userPreferences,
        )
        return cachedCategories ?: emptyList()
    }

    override fun invalidateCaches() {
        cachedCategories = null
        carouselCache.clear()
    }

    override fun invalidateAll() {
        cachedCategories = null
        carouselCache.clear()
    }

    private fun CarouselSectionDto.toDomain(): CarouselSection = CarouselSection(
        slug = slug,
        title = title,
        subtitle = subtitle,
        filterType = filterType,
        events = events.map { it.toDomain() },
        showSaves = showSaves,
    )

    private fun EventDto.toDomain(): Event = Event(
        id = id,
        title = title.decodeHtmlEntities(),
        description = description?.decodeHtmlEntities(),
        category = category,
        subcategory = subcategory,
        subSubcategory = subSubcategory,
        imageUrl = imageUrl,
        startAt = startAt,
        endAt = endAt,
        isFree = isFree,
        priceMin = priceMin,
        priceMax = priceMax,
        ticketUrl = ticketUrl,
        venueName = venueName,
        venueAddress = venueAddress,
        venueCity = venueCity,
        venueState = venueState,
        venueLat = venueLat,
        venueLng = venueLng,
        city = city,
        source = source,
        sourceUrl = sourceUrl,
        distanceMiles = distanceMiles,
        venueDescription = venueDescription,
    )

    private fun CategoryInfoDto.toDomain(): CategoryInfo = CategoryInfo(
        id = id,
        label = label,
        iconUrl = "$iconUrl?v=${live.localapp.local.domain.repository.CATEGORY_ICONS_VERSION}",
        eventCount = eventCount,
    )
}
