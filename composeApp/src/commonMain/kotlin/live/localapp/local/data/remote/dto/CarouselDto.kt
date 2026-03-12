package live.localapp.local.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CarouselDto(
    val id: String,
    val city: String,
    val slug: String,
    val title: String,
    val subtitle: String? = null,
    @SerialName("filter_type") val filterType: String,
    @SerialName("filter_value") val filterValue: String? = null,
    @SerialName("sort_order") val sortOrder: String? = "start_at",
    val position: Int = 0,
    @SerialName("is_active") val isActive: Boolean = true,
)
