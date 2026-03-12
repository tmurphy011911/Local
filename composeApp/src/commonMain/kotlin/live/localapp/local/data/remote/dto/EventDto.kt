package live.localapp.local.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import live.localapp.local.domain.model.Event

@Serializable
data class EventDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val category: String,
    val subcategory: String? = null,
    @SerialName("start_at") val startAt: String? = null,
    @SerialName("end_at") val endAt: String? = null,
    @SerialName("is_free") val isFree: Boolean = false,
    @SerialName("price_min") val priceMin: Double? = null,
    @SerialName("price_max") val priceMax: Double? = null,
    @SerialName("ticket_url") val ticketUrl: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val status: String? = "active",
    @SerialName("venue_name") val venueName: String? = null,
    @SerialName("venue_address") val venueAddress: String? = null,
    @SerialName("venue_city") val venueCity: String,
    @SerialName("venue_lat") val venueLat: Double? = null,
    @SerialName("venue_lng") val venueLng: Double? = null,
    val source: String,
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val city: String,
)

fun EventDto.toDomain(): Event = Event(
    id = id,
    title = title,
    description = description,
    category = category,
    subcategory = subcategory,
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
    venueLat = venueLat,
    venueLng = venueLng,
    city = city,
    source = source,
)
