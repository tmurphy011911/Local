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
}
