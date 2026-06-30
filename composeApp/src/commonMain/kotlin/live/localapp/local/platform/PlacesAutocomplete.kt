package live.localapp.local.platform

import androidx.compose.runtime.Composable

data class PlaceSuggestion(
    val id: String,
    val primaryText: String,
    val secondaryText: String,
)

data class PlaceDetails(
    val name: String,
    val formattedAddress: String,
    val lat: Double?,
    val lng: Double?,
    /** City/town component (e.g. "Perkasie"), when the place resolver provides
     *  it. Use this for hometown/home-city display — `name` is often the full
     *  street address for a precise pick. */
    val locality: String? = null,
)

interface PlacesAutocompleteController {
    suspend fun query(input: String, lat: Double?, lng: Double?): List<PlaceSuggestion>
    suspend fun resolve(suggestion: PlaceSuggestion): PlaceDetails?
}

@Composable
expect fun rememberPlacesAutocomplete(): PlacesAutocompleteController
