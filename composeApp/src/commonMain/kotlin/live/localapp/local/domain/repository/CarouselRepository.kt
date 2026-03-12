package live.localapp.local.domain.repository

import live.localapp.local.domain.model.CarouselSection

enum class SegmentMode { UPCOMING, ALWAYS_ON }

interface CarouselRepository {
    suspend fun getCarousels(
        userLat: Double,
        userLng: Double,
        segment: SegmentMode = SegmentMode.UPCOMING,
        category: String? = null,
    ): List<CarouselSection>
}
