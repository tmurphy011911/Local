package live.localapp.local.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import live.localapp.local.data.remote.dto.PriceComfortDto
import live.localapp.local.data.remote.dto.TasteProfileDto
import live.localapp.local.data.remote.dto.TasteProfileInsertDto
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.model.PriceComfort
import live.localapp.local.domain.model.TasteProfile
import live.localapp.local.domain.repository.InterestSummary
import live.localapp.local.domain.repository.TasteRepository
import live.localapp.local.ui.chat.ItinerarySlot

@Serializable
private data class TasteSummaryDto(
    @SerialName("interest_1") val interest1: String? = null,
    @SerialName("interest_2") val interest2: String? = null,
    @SerialName("interest_3") val interest3: String? = null,
    @SerialName("interest_4") val interest4: String? = null,
    @SerialName("interest_5") val interest5: String? = null,
    @SerialName("top_entities") val topEntities: List<String> = emptyList(),
    @SerialName("travel_propensity") val travelPropensity: Double? = null,
    @SerialName("interaction_count") val interactionCount: Int = 0,
)

class TasteRepositoryImpl(
    private val supabaseClient: SupabaseClient,
) : TasteRepository {

    override suspend fun getTasteProfile(userId: String): TasteProfile? {
        return try {
            val dto = supabaseClient.from("user_taste_profiles")
                .select { filter { eq("user_id", userId) } }
                .decodeSingleOrNull<TasteProfileDto>()
            dto?.toDomain()
        } catch (e: Exception) {
            println("TasteRepo: failed to load profile: ${e.message}")
            null
        }
    }

    override suspend fun getInterestSummary(userId: String): InterestSummary? {
        return try {
            val dto = supabaseClient.from("user_taste_summary")
                .select { filter { eq("user_id", userId) } }
                .decodeSingleOrNull<TasteSummaryDto>()
            dto?.let {
                InterestSummary(
                    interests = listOfNotNull(it.interest1, it.interest2, it.interest3, it.interest4, it.interest5),
                    topEntities = it.topEntities,
                    travelPropensity = it.travelPropensity,
                    interactionCount = it.interactionCount,
                )
            }
        } catch (_: Exception) {
            // Table may not exist yet / no row — Bo just falls back to its other signals.
            null
        }
    }

    override suspend fun saveTasteProfile(profile: TasteProfile) {
        try {
            supabaseClient.from("user_taste_profiles")
                .upsert(profile.toInsertDto()) {
                    onConflict = "user_id"
                }
        } catch (e: Exception) {
            println("TasteRepo: failed to save profile: ${e.message}")
        }
    }

    override suspend fun recordPlanApproval(
        userId: String,
        slots: List<ItinerarySlot>,
        events: List<Event>,
    ) {
        val profile = getTasteProfile(userId) ?: TasteProfile(userId = userId)
        val eventsById = events.associateBy { it.id }

        val newCuisine = profile.cuisineScores.toMutableMap()
        val newCategory = profile.categoryScores.toMutableMap()
        val newVenueType = profile.venueTypeScores.toMutableMap()
        val newNeighborhood = profile.neighborhoodScores.toMutableMap()
        val newTime = profile.timePreferences.toMutableMap()
        var priceMinSum = profile.priceComfort.avgMin * profile.interactionCount
        var priceMaxSum = profile.priceComfort.avgMax * profile.interactionCount
        var freeCount = (profile.priceComfort.freeRatio * profile.interactionCount).toInt()
        var priceEvents = profile.interactionCount

        for (slot in slots) {
            val event = slot.eventId?.let { eventsById[it] }

            // Category signals
            event?.category?.let { increment(newCategory, it, 2) }
            event?.subcategory?.let { increment(newCategory, it, 2) }

            // Cuisine/venue type from venue category or event subcategory
            event?.subcategory?.let { sub ->
                if (sub.lowercase().let { it.contains("restaurant") || it.contains("food") || it.contains("dining") }) {
                    increment(newCuisine, sub, 2)
                }
            }

            // Neighborhood
            val neighborhood = event?.venueCity ?: slot.detail.substringBefore(" ·").trim()
            if (neighborhood.isNotBlank()) increment(newNeighborhood, neighborhood, 2)

            // Time of day
            val timeBucket = timeBucket(slot.time)
            if (timeBucket.isNotBlank()) increment(newTime, timeBucket, 2)

            // Price tracking
            if (slot.isFree) {
                freeCount++
            }
            event?.priceMin?.let { priceMinSum += it }
            event?.priceMax?.let { priceMaxSum += it }
            priceEvents++
        }

        val newCount = profile.interactionCount + slots.size
        val newProfile = profile.copy(
            cuisineScores = cap(newCuisine),
            categoryScores = cap(newCategory),
            venueTypeScores = cap(newVenueType),
            neighborhoodScores = cap(newNeighborhood),
            timePreferences = cap(newTime),
            priceComfort = PriceComfort(
                avgMin = if (priceEvents > 0) priceMinSum / priceEvents else 0.0,
                avgMax = if (priceEvents > 0) priceMaxSum / priceEvents else 0.0,
                freeRatio = if (newCount > 0) freeCount.toDouble() / newCount else 0.0,
            ),
            interactionCount = newCount,
        )
        saveTasteProfile(newProfile)
    }

    override suspend fun recordEventTap(userId: String, event: Event) {
        val profile = getTasteProfile(userId) ?: TasteProfile(userId = userId)

        val newCategory = profile.categoryScores.toMutableMap()
        val newNeighborhood = profile.neighborhoodScores.toMutableMap()
        val newCuisine = profile.cuisineScores.toMutableMap()

        event.category.let { increment(newCategory, it, 1) }
        event.subcategory?.let { increment(newCategory, it, 1) }
        event.venueCity.let { increment(newNeighborhood, it, 1) }
        event.subcategory?.let { sub ->
            if (sub.lowercase().let { it.contains("restaurant") || it.contains("food") }) {
                increment(newCuisine, sub, 1)
            }
        }

        saveTasteProfile(profile.copy(
            categoryScores = cap(newCategory),
            neighborhoodScores = cap(newNeighborhood),
            cuisineScores = cap(newCuisine),
            interactionCount = profile.interactionCount + 1,
        ))
    }

    private fun increment(map: MutableMap<String, Int>, key: String, amount: Int) {
        if (key.isBlank()) return
        map[key] = (map[key] ?: 0) + amount
    }

    private fun cap(map: MutableMap<String, Int>, max: Int = 20): Map<String, Int> =
        map.mapValues { it.value.coerceAtMost(max) }

    private fun timeBucket(time: String): String {
        val clean = time.trim().uppercase()
        val hour = try {
            val parts = clean.replace("AM", "").replace("PM", "").trim().split(":")
            var h = parts[0].trim().toInt()
            if (clean.contains("PM") && h != 12) h += 12
            if (clean.contains("AM") && h == 12) h = 0
            h
        } catch (_: Exception) { return "" }

        return when (hour) {
            in 6..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..20 -> "evening"
            else -> "night"
        }
    }
}

// ── Mappers ──

private fun TasteProfileDto.toDomain() = TasteProfile(
    userId = userId,
    cuisineScores = cuisineScores,
    categoryScores = categoryScores,
    venueTypeScores = venueTypeScores,
    priceComfort = PriceComfort(priceComfort.avgMin, priceComfort.avgMax, priceComfort.freeRatio),
    neighborhoodScores = neighborhoodScores,
    timePreferences = timePreferences,
    interactionCount = interactionCount,
)

private fun TasteProfile.toInsertDto() = TasteProfileInsertDto(
    userId = userId,
    cuisineScores = cuisineScores,
    categoryScores = categoryScores,
    venueTypeScores = venueTypeScores,
    priceComfort = PriceComfortDto(priceComfort.avgMin, priceComfort.avgMax, priceComfort.freeRatio),
    neighborhoodScores = neighborhoodScores,
    timePreferences = timePreferences,
    interactionCount = interactionCount,
)
