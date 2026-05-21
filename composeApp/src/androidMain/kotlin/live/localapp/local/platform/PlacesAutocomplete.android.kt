package live.localapp.local.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.URLBuilder
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import live.localapp.local.BuildConfig

@Composable
actual fun rememberPlacesAutocomplete(): PlacesAutocompleteController {
    return remember {
        AndroidPlacesAutocomplete(BuildConfig.MAPS_API_KEY)
    }
}

private class AndroidPlacesAutocomplete(private val apiKey: String) : PlacesAutocompleteController {

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    override suspend fun query(input: String, lat: Double?, lng: Double?): List<PlaceSuggestion> {
        if (input.length < 3) return emptyList()
        if (apiKey.isBlank()) return emptyList()
        return try {
            val url = URLBuilder("https://maps.googleapis.com/maps/api/place/autocomplete/json").apply {
                parameters.append("input", input)
                parameters.append("key", apiKey)
                if (lat != null && lng != null) {
                    parameters.append("location", "$lat,$lng")
                    parameters.append("radius", "50000")
                }
            }.build()
            val response = client.get(url).body<AutocompleteResponse>()
            response.predictions.take(3).map {
                PlaceSuggestion(
                    id = it.placeId,
                    primaryText = it.structuredFormatting?.mainText ?: it.description,
                    secondaryText = it.structuredFormatting?.secondaryText ?: "",
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun resolve(suggestion: PlaceSuggestion): PlaceDetails? {
        if (apiKey.isBlank()) return null
        return try {
            val url = URLBuilder("https://maps.googleapis.com/maps/api/place/details/json").apply {
                parameters.append("place_id", suggestion.id)
                parameters.append("fields", "geometry,formatted_address,name")
                parameters.append("key", apiKey)
            }.build()
            val response = client.get(url).body<DetailsResponse>()
            val r = response.result ?: return null
            PlaceDetails(
                name = r.name ?: suggestion.primaryText,
                formattedAddress = r.formattedAddress ?: "${suggestion.primaryText}, ${suggestion.secondaryText}".trim(','),
                lat = r.geometry?.location?.lat,
                lng = r.geometry?.location?.lng,
            )
        } catch (_: Throwable) {
            null
        }
    }
}

@Serializable
private data class AutocompleteResponse(val predictions: List<Prediction> = emptyList())

@Serializable
private data class Prediction(
    val description: String = "",
    @kotlinx.serialization.SerialName("place_id") val placeId: String = "",
    @kotlinx.serialization.SerialName("structured_formatting") val structuredFormatting: StructuredFormatting? = null,
)

@Serializable
private data class StructuredFormatting(
    @kotlinx.serialization.SerialName("main_text") val mainText: String? = null,
    @kotlinx.serialization.SerialName("secondary_text") val secondaryText: String? = null,
)

@Serializable
private data class DetailsResponse(val result: DetailsResult? = null)

@Serializable
private data class DetailsResult(
    val name: String? = null,
    @kotlinx.serialization.SerialName("formatted_address") val formattedAddress: String? = null,
    val geometry: Geometry? = null,
)

@Serializable
private data class Geometry(val location: LatLng? = null)

@Serializable
private data class LatLng(val lat: Double, val lng: Double)
