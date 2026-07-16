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
        /** Slug like "today", "this_weekend", "custom". Null = server default. */
        dateFilter: String? = null,
        /** ISO date (YYYY-MM-DD), inclusive. Used when dateFilter == "custom". */
        dateStart: String? = null,
        /** ISO date (YYYY-MM-DD), inclusive. Used when dateFilter == "custom". */
        dateEnd: String? = null,
        /** (local − UTC) in minutes. Server uses this to snap today/tomorrow/
         *  weekend boundaries to the user's local midnight instead of UTC. */
        tzOffsetMinutes: Int? = null,
        cursor: String? = null,
        limit: Int = 50,
    ): EventPage

    suspend fun fetchPlaces(
        userLat: Double,
        userLng: Double,
        userId: String? = null,
        query: String? = null,
        category: String? = null,
        /** Top-level city filter (raw venue.city). Null = all cities in radius. */
        city: String? = null,
        radiusMiles: Double? = null,
        /** If true, server excludes venues with zero upcoming events. */
        hasEventsOnly: Boolean = false,
        /** "popular" | "closest" | "most_events". Null defaults to "popular". */
        sort: String? = null,
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
    /** Two-tier filter facets. [name] is the raw value to echo back as the
     *  city/category filter; [count] is how many venues match in the current
     *  scope. Category names are raw slugs ("music_venue") — title-case for UI. */
    val cityFacets: List<PlaceFacet> = emptyList(),
    val categoryFacets: List<PlaceFacet> = emptyList(),
)

data class PlaceFacet(
    val name: String,
    val count: Int,
)
