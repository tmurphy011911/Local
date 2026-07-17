package live.localapp.local.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import live.localapp.local.domain.repository.CommunityInterestRepository
import live.localapp.local.domain.repository.InteractionType
import live.localapp.local.domain.repository.UserPreferencesRepository

@Serializable
private data class CommunityInteractionDto(
    @SerialName("user_id") val userId: String,
    @SerialName("item_id") val itemId: String,
    @SerialName("item_type") val itemType: String,
    @SerialName("interaction_type") val interactionType: String,
    @SerialName("category") val category: String? = null,
)

// Minimal shape to read back the inserted interaction's id.
@Serializable
private data class InsertedInteraction(
    @SerialName("id") val id: String,
)

// Actor location lives in the own-RLS interaction_context table (spec §5.4),
// NEVER on the broadly-readable community_interactions row.
@Serializable
private data class InteractionContextDto(
    @SerialName("community_interaction_id") val communityInteractionId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("actor_lat") val actorLat: Double? = null,
    @SerialName("actor_lng") val actorLng: Double? = null,
)

class CommunityInterestRepositoryImpl(
    private val supabaseClient: SupabaseClient,
    private val userPreferencesRepository: UserPreferencesRepository,
) : CommunityInterestRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // User's active/set location (GPS or manual), pushed in by the location
    // chokepoint (HomeViewModel.setUserLocation) and stamped onto each
    // interaction. Plain vars (no @Volatile to stay KMP-portable) — location
    // changes slowly so a rare stale read across threads is harmless.
    private var activeLat: Double? = null
    private var activeLng: Double? = null

    // Personal interaction maps (current user only)
    private val personalClicks = mutableMapOf<String, Int>()
    private val personalFavorites = mutableMapOf<String, Int>()
    private val personalShares = mutableMapOf<String, Int>()
    private val personalPlanAdds = mutableMapOf<String, Int>()

    // Community interaction maps (everyone else)
    private val communityClicks = mutableMapOf<String, Int>()
    private val communityFavorites = mutableMapOf<String, Int>()
    private val communityShares = mutableMapOf<String, Int>()
    private val communityPlanAdds = mutableMapOf<String, Int>()

    // Category affinity cache
    private var cachedAffinity: Map<String, Double> = emptyMap()
    private var affinityDirty = true

    // Track personal category interactions for affinity: category -> weighted count
    private val personalCategoryWeights = mutableMapOf<String, Double>()

    override fun clearCaches() {
        personalClicks.clear()
        personalFavorites.clear()
        personalShares.clear()
        personalPlanAdds.clear()
        communityClicks.clear()
        communityFavorites.clear()
        communityShares.clear()
        communityPlanAdds.clear()
        personalCategoryWeights.clear()
        cachedAffinity = emptyMap()
        affinityDirty = true
    }

    private var historyLoaded = false

    // Scoring is now server-side; no need to load 10k rows on startup.
    // init { scope.launch { loadHistory() } }

    private suspend fun loadHistory() {
        try {
            val currentUserId = userPreferencesRepository.getLocalUserId()

            // Load recent community interactions (capped for performance)
            val rows = supabaseClient.from("community_interactions")
                .select {
                    order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                    limit(10000)
                }
                .decodeList<CommunityInteractionDto>()

            rows.forEach { row ->
                val isPersonal = currentUserId != null && row.userId == currentUserId
                when (row.interactionType) {
                    "click" -> {
                        if (isPersonal) {
                            personalClicks[row.itemId] = (personalClicks[row.itemId] ?: 0) + 1
                        } else {
                            communityClicks[row.itemId] = (communityClicks[row.itemId] ?: 0) + 1
                        }
                    }
                    "favorite" -> {
                        if (isPersonal) {
                            personalFavorites[row.itemId] = (personalFavorites[row.itemId] ?: 0) + 1
                        } else {
                            communityFavorites[row.itemId] = (communityFavorites[row.itemId] ?: 0) + 1
                        }
                    }
                    "share" -> {
                        if (isPersonal) {
                            personalShares[row.itemId] = (personalShares[row.itemId] ?: 0) + 1
                        } else {
                            communityShares[row.itemId] = (communityShares[row.itemId] ?: 0) + 1
                        }
                    }
                    "plan_add" -> {
                        if (isPersonal) {
                            personalPlanAdds[row.itemId] = (personalPlanAdds[row.itemId] ?: 0) + 1
                        } else {
                            communityPlanAdds[row.itemId] = (communityPlanAdds[row.itemId] ?: 0) + 1
                        }
                    }
                }

                // Track personal category weights for affinity
                if (isPersonal && row.category != null) {
                    val weight = interactionWeight(row.interactionType)
                    val cat = row.category.lowercase()
                    personalCategoryWeights[cat] = (personalCategoryWeights[cat] ?: 0.0) + weight
                }
            }
            affinityDirty = true
            historyLoaded = true
        } catch (_: Exception) {
            // Non-fatal: scores will just start from zero this session
        }
    }

    private fun interactionWeight(interactionType: String): Double = when (interactionType) {
        "click" -> 1.0
        "favorite" -> 2.0
        "share" -> 4.0
        "plan_add" -> 6.0
        "not_interested" -> -3.0
        else -> 1.0
    }

    override fun recordClick(id: String, type: InteractionType, category: String?) {
        personalClicks[id] = (personalClicks[id] ?: 0) + 1
        trackCategoryWeight(category, 1.0)
        persistInteraction(id, type, "click", category)
    }

    override fun recordShare(id: String, type: InteractionType, category: String?) {
        personalShares[id] = (personalShares[id] ?: 0) + 1
        trackCategoryWeight(category, 4.0)
        persistInteraction(id, type, "share", category)
    }

    override fun recordFavorite(id: String, type: InteractionType, category: String?) {
        if (personalFavorites.containsKey(id)) return // already favorited
        personalFavorites[id] = 1
        trackCategoryWeight(category, 2.0)
        persistInteraction(id, type, "favorite", category)
    }

    override fun removeFavorite(id: String, type: InteractionType) {
        personalFavorites.remove(id)
        val userId = userPreferencesRepository.getLocalUserId() ?: return
        scope.launch {
            try {
                supabaseClient.from("community_interactions").delete {
                    filter {
                        eq("user_id", userId)
                        eq("item_id", id)
                        eq("item_type", when (type) {
                            InteractionType.EVENT -> "event"
                            InteractionType.PLACE -> "place"
                        })
                        eq("interaction_type", "favorite")
                    }
                }
            } catch (_: Exception) { }
        }
    }

    override fun recordPlanAdd(id: String, type: InteractionType, category: String?) {
        personalPlanAdds[id] = (personalPlanAdds[id] ?: 0) + 1
        trackCategoryWeight(category, 6.0)
        persistInteraction(id, type, "plan_add", category)
    }

    override fun recordNotInterested(id: String, type: InteractionType, category: String?) {
        // Soft negative: pull the category weight down and record server-side.
        trackCategoryWeight(category, -3.0)
        persistInteraction(id, type, "not_interested", category)
    }

    override fun setActiveLocation(lat: Double?, lng: Double?) {
        activeLat = lat
        activeLng = lng
    }

    private fun trackCategoryWeight(category: String?, weight: Double) {
        if (category != null) {
            val cat = category.lowercase()
            personalCategoryWeights[cat] = (personalCategoryWeights[cat] ?: 0.0) + weight
            affinityDirty = true
        }
    }

    // Scoring is now computed server-side in the carousels edge function.
    // These methods return 0 / empty since no local history is loaded.
    override fun personalRawScore(id: String): Double = 0.0
    override fun communityRawScore(id: String): Double = 0.0
    override fun rawScore(id: String): Double = 0.0
    override fun normalizedScore(id: String, peerIds: List<String>): Double = 0.0
    override fun totalSaves(id: String): Int = 0
    override fun categoryAffinity(): Map<String, Double> = emptyMap()

    override suspend fun getFavoriteEventIds(userId: String): List<String> {
        return try {
            supabaseClient.from("community_interactions")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("interaction_type", "favorite")
                        eq("item_type", "event")
                    }
                }
                .decodeList<CommunityInteractionDto>()
                .map { it.itemId }
                .distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getFavoriteVenueIds(userId: String): List<String> {
        return try {
            supabaseClient.from("community_interactions")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("interaction_type", "favorite")
                        eq("item_type", "place")
                    }
                }
                .decodeList<CommunityInteractionDto>()
                .map { it.itemId }
                .distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistInteraction(itemId: String, type: InteractionType, interaction: String, category: String?) {
        val userId = userPreferencesRepository.getLocalUserId()
        if (userId == null) {
            println("persistInteraction: SKIPPED — no userId")
            return
        }
        val lat = activeLat
        val lng = activeLng
        scope.launch {
            try {
                val inserted = supabaseClient.from("community_interactions").insert(
                    CommunityInteractionDto(
                        userId = userId,
                        itemId = itemId,
                        itemType = when (type) {
                            InteractionType.EVENT -> "event"
                            InteractionType.PLACE -> "place"
                        },
                        interactionType = interaction,
                        category = category,
                    )
                ) {
                    select()
                }.decodeSingle<InsertedInteraction>()

                // Actor location goes to the own-RLS interaction_context table so
                // it never lands on the broadly-readable community_interactions row.
                if (lat != null && lng != null) {
                    try {
                        supabaseClient.from("interaction_context").insert(
                            InteractionContextDto(
                                communityInteractionId = inserted.id,
                                userId = userId,
                                actorLat = lat,
                                actorLng = lng,
                            )
                        )
                    } catch (_: Exception) {
                        // Location capture is best-effort — never fail the interaction.
                    }
                }
                println("persistInteraction: SUCCESS $interaction $itemId for user $userId")
            } catch (e: Exception) {
                println("persistInteraction: FAILED $interaction $itemId — ${e.message}")
            }
        }
    }
}
