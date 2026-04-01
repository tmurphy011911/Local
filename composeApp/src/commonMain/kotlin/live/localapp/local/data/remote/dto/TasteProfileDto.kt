package live.localapp.local.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TasteProfileDto(
    val id: String = "",
    @SerialName("user_id") val userId: String,
    @SerialName("cuisine_scores") val cuisineScores: Map<String, Int> = emptyMap(),
    @SerialName("category_scores") val categoryScores: Map<String, Int> = emptyMap(),
    @SerialName("venue_type_scores") val venueTypeScores: Map<String, Int> = emptyMap(),
    @SerialName("price_comfort") val priceComfort: PriceComfortDto = PriceComfortDto(),
    @SerialName("neighborhood_scores") val neighborhoodScores: Map<String, Int> = emptyMap(),
    @SerialName("time_preferences") val timePreferences: Map<String, Int> = emptyMap(),
    @SerialName("interaction_count") val interactionCount: Int = 0,
)

@Serializable
data class PriceComfortDto(
    @SerialName("avg_min") val avgMin: Double = 0.0,
    @SerialName("avg_max") val avgMax: Double = 0.0,
    @SerialName("free_ratio") val freeRatio: Double = 0.0,
)

@Serializable
data class TasteProfileInsertDto(
    @SerialName("user_id") val userId: String,
    @SerialName("cuisine_scores") val cuisineScores: Map<String, Int> = emptyMap(),
    @SerialName("category_scores") val categoryScores: Map<String, Int> = emptyMap(),
    @SerialName("venue_type_scores") val venueTypeScores: Map<String, Int> = emptyMap(),
    @SerialName("price_comfort") val priceComfort: PriceComfortDto = PriceComfortDto(),
    @SerialName("neighborhood_scores") val neighborhoodScores: Map<String, Int> = emptyMap(),
    @SerialName("time_preferences") val timePreferences: Map<String, Int> = emptyMap(),
    @SerialName("interaction_count") val interactionCount: Int = 0,
)
