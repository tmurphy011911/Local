package live.localapp.local.domain.repository

import live.localapp.local.domain.model.CarouselSection

enum class SegmentMode { UPCOMING, ALWAYS_ON }

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
        segment: SegmentMode = SegmentMode.UPCOMING,
        category: String? = null,
        userPreferences: UserPreferences? = null,
        locationName: String = "",
    ): List<CarouselSection>

    suspend fun getAvailableCategories(
        segment: SegmentMode,
        userPreferences: UserPreferences? = null,
    ): List<CategoryInfo>

    fun invalidateCaches()
}
