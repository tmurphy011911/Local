package live.localapp.local.domain.repository

import live.localapp.local.domain.model.CarouselSection

/** Bump when category-icon assets in Supabase Storage are replaced — busts CDN + on-device caches. */
const val CATEGORY_ICONS_VERSION = 2

data class CategoryInfo(
    val id: String,
    val label: String,
    val iconUrl: String,
    val eventCount: Int,
)

interface CarouselRepository {
    suspend fun getCarousels(
        userLat: Double,
        userLng: Double,
        category: String? = null,
        userPreferences: UserPreferences? = null,
        locationName: String = "",
        radiusMiles: Int = 50,
    ): List<CarouselSection>

    suspend fun getAvailableCategories(
        userPreferences: UserPreferences? = null,
        userLat: Double = 42.3601,
        userLng: Double = -71.0589,
    ): List<CategoryInfo>

    fun invalidateCaches()

    /** Full reset including category order — call on location change. */
    fun invalidateAll() { invalidateCaches() }

    /**
     * Disk-cached previous response, used by HomeViewModel to paint
     * stale-while-revalidate. Returns null if no cache, or the cache is for
     * a different location. Default no-op so non-server implementations
     * (test fakes, in-memory mocks) don't have to implement it.
     */
    suspend fun getCachedCarouselsFromDisk(
        category: String? = null,
        userPreferences: UserPreferences? = null,
        locationName: String = "",
    ): List<CarouselSection>? = null

    /**
     * Non-suspending snapshot of the in-memory categories cache. Used by the
     * SWR disk-paint path so the tab strip can render alongside the carousels
     * without waiting for the (potentially slow/failing) fresh fetch. Returns
     * empty when nothing is cached yet.
     */
    fun peekCachedCategories(): List<CategoryInfo> = emptyList()
}
