package live.localapp.local.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    @SerialName("event_city") val eventCity: String? = null,
    @SerialName("event_state") val eventState: String? = null,
    @SerialName("event_lat") val eventLat: Double? = null,
    @SerialName("event_lng") val eventLng: Double? = null,
    val source: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val city: String = "",
    @SerialName("distance_miles") val distanceMiles: Double? = null,
    @SerialName("venue_description") val venueDescription: String? = null,
    @SerialName("venue_photo_url") val venuePhotoUrl: String? = null,
    @SerialName("time_known") val timeKnown: Boolean = true,
)

@Serializable
private data class CategoryInfoDto(
    val id: String,
    val label: String,
    @SerialName("icon_url") val iconUrl: String,
    @SerialName("event_count") val eventCount: Int,
)

class ServerCarouselRepository(
    private val supabaseClient: SupabaseClient,
) : CarouselRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        // Latency sprint Day 5: advertise gzip/deflate, decode transparently.
        // Carousels payload ~50 KB raw → ~10 KB gzip; saves ~250 ms of LTE
        // transfer time per response.
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

    private val edgeFunctionUrl = "${Config.SUPABASE_URL}/functions/v1/carousels"

    /** Cached categories from the last server response */
    private var cachedCategories: List<CategoryInfo>? = null

    /** Cached carousels per category tab — avoids re-fetching on tab switch */
    private val carouselCache = mutableMapOf<String, List<CarouselSection>>()

    /**
     * Persistent disk cache. Lets cold-open paint the previous session's
     * carousels in <100ms while the fresh fetch runs in the background.
     * Without this every launch hits the 5-8s edge function for first paint.
     *
     * Key: same `tab:uid` as the in-memory cache, plus a "carousels-disk:"
     * prefix to namespace from other Settings consumers (tutorial seen,
     * paywall counters, etc.).
     */
    private val settings = com.russhwolf.settings.Settings()

    private fun diskKey(tab: String, uid: String) = "carousels-disk:$tab:$uid"
    private fun diskKeyMeta(tab: String, uid: String) = "carousels-disk-meta:$tab:$uid"

    @Serializable
    private data class CacheMeta(
        val locationName: String,
        val fetchedAtMs: Long,
    )

    /**
     * Fetch the disk-cached carousels for a (tab, user, location) combo. The
     * ViewModel calls this BEFORE issuing the fresh network fetch, so the
     * Home tab can render instantly on cold open. Returns null when there's
     * no cache OR when location changed (cache shouldn't carry across cities).
     *
     * No age-based TTL gate — even a multi-day-old cache paints faster than
     * the 3-8s edge function. Past events ARE filtered out on read though, so
     * if the fresh fetch fails the user still doesn't see yesterday's games.
     */
    override suspend fun getCachedCarouselsFromDisk(
        category: String?,
        userPreferences: UserPreferences?,
        locationName: String,
    ): List<CarouselSection>? {
        val tab = category ?: "foryou"
        val uid = userPreferences?.id?.takeIf { it.isNotBlank() } ?: "anon"
        val payload = settings.getStringOrNull(diskKey(tab, uid)) ?: return null
        val metaJson = settings.getStringOrNull(diskKeyMeta(tab, uid)) ?: return null
        val meta = try { json.decodeFromString<CacheMeta>(metaJson) } catch (_: Throwable) { return null }
        // Bail if user has changed locations since this cache was written —
        // we'd be showing Boston cards to someone now in Philly.
        if (meta.locationName.isNotBlank() && locationName.isNotBlank() && meta.locationName != locationName) {
            return null
        }
        // Drop events whose start_at has passed. Mirrors the server's
        // `gte("start_at", now)` filter so a stale cache can't paint events
        // from last week. Null start_at = always-on, always kept. Carousels
        // that empty out get dropped entirely.
        val nowIso = kotlin.time.Clock.System.now().toString()
        // Decode + filter + map off the main thread — this is the cold-start
        // first-paint path and the same heavy CPU work (JSON parse +
        // per-event decodeHtmlEntities) that hung the UI when run inline on Main.
        val decoded = withContext(Dispatchers.Default) {
            val parsed = try {
                json.decodeFromString<CarouselResponse>(payload)
            } catch (_: Throwable) {
                return@withContext null
            }
            val carousels = parsed.carousels
                .map { section ->
                    section.copy(events = section.events.filter { e ->
                        e.startAt == null || e.startAt >= nowIso
                    })
                }
                .filter { it.events.isNotEmpty() }
                .map { it.toDomain() }
            if (carousels.isEmpty()) return@withContext null
            carousels to parsed.categories.map { it.toDomain() }
        } ?: return null
        // Hydrate categories so the tab strip can paint instantly from disk;
        // getCarousels()'s fresh fetch will overwrite it shortly. Do NOT
        // hydrate `carouselCache` here — getCarousels() short-circuits on
        // any in-memory entry, so hydrating from disk would skip the fresh
        // fetch entirely and the cache would never refresh (manifests as
        // missing weekend rails + shrinking carousels as their events age
        // past `now`).
        if (cachedCategories == null) cachedCategories = decoded.second
        return decoded.first
    }

    private fun writeDiskCache(
        tab: String,
        uid: String,
        rawJson: String,
        locationName: String,
    ) {
        try {
            settings.putString(diskKey(tab, uid), rawJson)
            val meta = json.encodeToString(CacheMeta.serializer(), CacheMeta(locationName, kotlin.time.Clock.System.now().toEpochMilliseconds()))
            settings.putString(diskKeyMeta(tab, uid), meta)
        } catch (_: Throwable) { /* disk full / serialization error — fail silently, fresh fetch still works */ }
    }

    override suspend fun getCarousels(
        userLat: Double,
        userLng: Double,
        category: String?,
        userPreferences: UserPreferences?,
        locationName: String,
        radiusMiles: Int,
    ): List<CarouselSection> {
        // Include the user_id in the cache key so an anon-fetch (which
        // ran before prefs hydrated) doesn't satisfy a later authed
        // request. Without this, post-onboarding users would never see
        // their personalized carousels (favCat / favVenue / neighborhood)
        // until pull-to-refresh, because the unpersonalized response
        // cached at cold start kept hitting on every tab open.
        //
        // Also include radius — the edge function returns a different
        // event pool for different radii, so toggling 25 mi → 100 mi
        // → 25 mi needs to refetch on the way back to 25 (not serve
        // the 100 mi cached response).
        val tab = category ?: "foryou"
        val uid = userPreferences?.id?.takeIf { it.isNotBlank() } ?: "anon"
        val cacheKey = "$tab:$uid:r$radiusMiles"

        carouselCache[cacheKey]?.let { return it }

        // Authorization stays the anon key so the edge-function gateway is
        // always satisfied. The user's identity travels in X-User-Token, which
        // the server verifies and trusts over the (spoofable) user_id param.
        // Phase 1 of the carousels IDOR fix.
        val userToken = supabaseClient.auth.currentSessionOrNull()?.accessToken
        val response = httpClient.get(edgeFunctionUrl) {
            header("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
            userToken?.let { header("X-User-Token", it) }
            parameter("lat", userLat)
            parameter("lng", userLng)
            parameter("category", tab)
            parameter("location_name", locationName)
            parameter("radius", radiusMiles)
            userPreferences?.let { parameter("user_id", it.id) }
        }

        val body = response.bodyAsText()

        // Decode + map off the main thread: JSON parse and the per-event
        // decodeHtmlEntities() over a full carousel payload is heavy CPU work,
        // and getCarousels runs on the caller's (Main) dispatcher — doing it
        // inline hung the UI for seconds. Mutate the shared caches back on the
        // caller's context after this returns, so we don't race main-thread
        // cache readers (peekCachedCategories / invalidate).
        val (freshCategories, carousels) = withContext(Dispatchers.Default) {
            val parsed = json.decodeFromString<CarouselResponse>(body)
            parsed.categories.map { it.toDomain() } to parsed.carousels.map { it.toDomain() }
        }

        // Cache categories from the response — but don't poison a previously
        // good list with an empty one. A single bad response (precompute row
        // briefly empty, server hiccup, etc.) shouldn't blank the tab strip
        // for the rest of the session.
        if (freshCategories.isNotEmpty() || cachedCategories.isNullOrEmpty()) {
            cachedCategories = freshCategories
        }

        carouselCache[cacheKey] = carousels
        // Persist the raw JSON to disk so the next cold open can paint
        // immediately. Done after the in-memory cache update so a crash
        // mid-write doesn't poison the in-process cache.
        writeDiskCache(tab, uid, body, locationName)
        return carousels
    }

    override suspend fun getAvailableCategories(
        userPreferences: UserPreferences?,
        userLat: Double,
        userLng: Double,
    ): List<CategoryInfo> {
        // Return cached categories only if non-empty. An empty list here means
        // a prior fetch returned no categories (transient server issue, stale
        // precompute row) — re-fetching is cheap (in-memory cache short-circuits
        // when carousels are warm) and avoids leaving the tab strip blank for
        // the whole session.
        cachedCategories?.takeIf { it.isNotEmpty() }?.let { return it }

        // If no cache, fetch carousels which populates categories
        getCarousels(
            userLat = userLat,
            userLng = userLng,
            category = null,
            userPreferences = userPreferences,
        )
        return cachedCategories ?: emptyList()
    }

    override fun peekCachedCategories(): List<CategoryInfo> = cachedCategories.orEmpty()

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
        eventCity = eventCity,
        eventState = eventState,
        eventLat = eventLat,
        eventLng = eventLng,
        city = city,
        source = source,
        sourceUrl = sourceUrl,
        distanceMiles = distanceMiles,
        venueDescription = venueDescription,
        venueImageUrl = venuePhotoUrl,
        timeKnown = timeKnown,
    )

    private fun CategoryInfoDto.toDomain(): CategoryInfo = CategoryInfo(
        id = id,
        label = label,
        iconUrl = "$iconUrl?v=${live.localapp.local.domain.repository.CATEGORY_ICONS_VERSION}",
        eventCount = eventCount,
    )
}
