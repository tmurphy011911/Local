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
)
