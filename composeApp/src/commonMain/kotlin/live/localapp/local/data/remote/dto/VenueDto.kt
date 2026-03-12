package live.localapp.local.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import live.localapp.local.domain.model.Venue

@Serializable
data class VenueDto(
    val id: String,
    val name: String,
    val address: String? = null,
    val city: String,
    val state: String? = null,
    val country: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("image_url") val imageUrl: String? = null,
)

fun VenueDto.toDomain(): Venue = Venue(
    id = id,
    name = name,
    address = address,
    city = city,
    state = state,
    country = country,
    latitude = latitude,
    longitude = longitude,
    imageUrl = imageUrl,
)
