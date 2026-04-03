package live.localapp.local.domain.model

data class Venue(
    val id: String,
    val name: String,
    val address: String?,
    val city: String,
    val state: String?,
    val country: String?,
    val latitude: Double?,
    val longitude: Double?,
    val imageUrl: String?,
    val photoUrl: String? = null,
    val description: String? = null,
    val publicInterestRank: Int? = null,
    val category: String? = null,
    val placeType: String? = null,
    val googleRating: Double? = null,
    val website: String? = null,
    val reservationUrl: String? = null,
)
