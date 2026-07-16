package live.localapp.local.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Composable
actual fun rememberPlacesAutocomplete(): PlacesAutocompleteController {
    return remember { IosPlacesAutocomplete() }
}

private class IosPlacesAutocomplete : PlacesAutocompleteController {
    override suspend fun query(input: String, lat: Double?, lng: Double?): List<PlaceSuggestion> {
        val swiftQuery = PlacesAutocompleteBridge.query ?: return emptyList()
        val json = suspendCancellableCoroutine { cont ->
            swiftQuery(input, lat, lng) { result -> cont.resume(result) }
        }
        return parseSuggestions(json)
    }

    override suspend fun resolve(suggestion: PlaceSuggestion): PlaceDetails? {
        val swiftResolve = PlacesAutocompleteBridge.resolve ?: return null
        val json = suspendCancellableCoroutine { cont ->
            swiftResolve(suggestion.id, suggestion.primaryText, suggestion.secondaryText) { result -> cont.resume(result) }
        }
        return parseDetails(json) ?: PlaceDetails(
            name = suggestion.primaryText,
            formattedAddress = listOf(suggestion.primaryText, suggestion.secondaryText).filter { it.isNotBlank() }.joinToString(", "),
            lat = null,
            lng = null,
        )
    }

    private fun parseSuggestions(json: String): List<PlaceSuggestion> = try {
        Json { ignoreUnknownKeys = true }.decodeFromString<List<SuggestionDto>>(json).map {
            PlaceSuggestion(it.id, it.primaryText, it.secondaryText)
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun parseDetails(json: String): PlaceDetails? = try {
        if (json.isBlank()) null
        else Json { ignoreUnknownKeys = true }.decodeFromString<DetailsDto>(json).let {
            PlaceDetails(it.name, it.formattedAddress, it.lat, it.lng, it.locality?.takeIf { l -> l.isNotBlank() })
        }
    } catch (_: Throwable) {
        null
    }
}

@Serializable
private data class SuggestionDto(
    val id: String,
    val primaryText: String,
    val secondaryText: String = "",
)

@Serializable
private data class DetailsDto(
    val name: String,
    val formattedAddress: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val locality: String? = null,
)

object PlacesAutocompleteBridge {
    var query: ((input: String, lat: Double?, lng: Double?, onResultJson: (String) -> Unit) -> Unit)? = null
    var resolve: ((id: String, primaryText: String, secondaryText: String, onResultJson: (String) -> Unit) -> Unit)? = null
}
