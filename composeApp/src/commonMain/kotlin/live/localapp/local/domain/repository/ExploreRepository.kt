package live.localapp.local.domain.repository

import live.localapp.local.domain.model.Event

/**
 * Fetches pre-ranked Explore-tab feeds from the `explore-events` and
 * `explore-places` Supabase edge functions. Replaces client-side scoring,
 * filtering, and the chatty in-process bbox queries that lived on the
 * venue grid — all of that now runs server-side with CDN caching.
 */
interface ExploreRepository {
    suspend fun fetchEvents(
        userLat: Double,
        userLng: Double,
        userId: String? = null,
        query: String? = null,
        category: String? = null,
        radiusMiles: Double? = null,
        /** Slug like "today", "this_weekend", "next_7_days". Null = server default. */
        dateFilter: String? = null,
        cursor: String? = null,
        limit: Int = 50,
    ): EventPage

    suspend fun fetchPlaces(
        userLat: Double,
        userLng: Double,
        userId: String? = null,
        query: String? = null,
        category: String? = null,
        radiusMiles: Double? = null,
        /** If true, server excludes venues with zero upcoming events. */
        hasEventsOnly: Boolean = false,
        cursor: String? = null,
        limit: Int = 50,
    ): PlacePage
}

data class EventPage(
    val items: List<Event>,
    val cursor: String?,
    val hasMore: Boolean,
    val total: Int,
)

data class PlacePage(
    val items: List<VenueWithMeta>,
    val cursor: String?,
    val hasMore: Boolean,
    val total: Int,
)
