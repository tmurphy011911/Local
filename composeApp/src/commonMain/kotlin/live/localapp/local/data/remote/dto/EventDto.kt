package live.localapp.local.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import live.localapp.local.decodeHtmlEntities
import live.localapp.local.domain.model.Event

@Serializable
data class EventDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val category: String,
    val subcategory: String? = null,
    @SerialName("sub_subcategory") val subSubcategory: String? = null,
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
    // Default "" so a null from a municipal at-large row (no venue_city/city)
    // coerces to empty via coerceInputValues instead of throwing and zeroing
    // the ENTIRE decoded page — which left Bo with no events. See the search_
    // events_stratified RPC (no venue_city coalesce) + the explore null-city fix.
    @SerialName("venue_city") val venueCity: String = "",
    @SerialName("venue_state") val venueState: String? = null,
    @SerialName("venue_lat") val venueLat: Double? = null,
    @SerialName("venue_lng") val venueLng: Double? = null,
    val source: String = "",
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val city: String = "",
    @SerialName("venue_description") val venueDescription: String? = null,
    // True when start_at is a real source-provided time; false when start_at
    // was inferred from a date-only crawl source (defaulted to midnight).
    @SerialName("time_known") val timeKnown: Boolean = true,
)

fun EventDto.toDomain(): Event = Event(
    id = id,
    title = title.decodeHtmlEntities(),
    description = description?.decodeHtmlEntities(),
    category = category,
    subcategory = subcategory,
    subSubcategory = subSubcategory,
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
    venueState = venueState,
    venueLat = venueLat,
    venueLng = venueLng,
    city = city,
    source = source,
    sourceUrl = sourceUrl,
    venueDescription = venueDescription,
    timeKnown = timeKnown,
)
