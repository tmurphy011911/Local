package live.localapp.local.domain.model

data class TasteProfile(
    val userId: String,
    val cuisineScores: Map<String, Int> = emptyMap(),
    val categoryScores: Map<String, Int> = emptyMap(),
    val venueTypeScores: Map<String, Int> = emptyMap(),
    val priceComfort: PriceComfort = PriceComfort(),
    val neighborhoodScores: Map<String, Int> = emptyMap(),
    val timePreferences: Map<String, Int> = emptyMap(),
    val interactionCount: Int = 0,
) {
    fun topN(scores: Map<String, Int>, n: Int): List<String> =
        scores.entries.sortedByDescending { it.value }.take(n).map { it.key }
}

data class PriceComfort(
    val avgMin: Double = 0.0,
    val avgMax: Double = 0.0,
    val freeRatio: Double = 0.0,
)
