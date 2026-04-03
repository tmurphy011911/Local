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
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    val description: String? = null,
    @SerialName("public_interest_rank") val publicInterestRank: Int? = null,
    val category: String? = null,
    @SerialName("place_type") val placeType: String? = null,
    @SerialName("google_rating") val googleRating: Double? = null,
    val website: String? = null,
    @SerialName("reservation_url") val reservationUrl: String? = null,
)

fun VenueDto.toDomain(): Venue = Venue(
    id = id,
    name = name,
    address = address,
    city = city,
    state = state,
    country = null,
    latitude = lat,
    longitude = lng,
    imageUrl = photoUrl,
    photoUrl = photoUrl,
    description = description,
    publicInterestRank = publicInterestRank,
    category = category,
    placeType = placeType,
    googleRating = googleRating,
    website = website,
    reservationUrl = reservationUrl,
)
