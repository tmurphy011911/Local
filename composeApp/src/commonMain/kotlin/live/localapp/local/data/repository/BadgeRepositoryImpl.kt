package live.localapp.local.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import live.localapp.local.data.remote.dto.BadgeMilestoneDto
import live.localapp.local.data.remote.dto.toDomain
import live.localapp.local.domain.model.BadgeMilestone
import live.localapp.local.domain.repository.BadgeRepository

class BadgeRepositoryImpl(
    private val supabaseClient: SupabaseClient,
) : BadgeRepository {

    override suspend fun getMilestones(userId: String): List<BadgeMilestone> {
        return supabaseClient.from("user_badges")
            .select {
                filter { eq("user_id", userId) }
            }
            .decodeList<BadgeMilestoneDto>()
            .map { it.toDomain() }
    }

    override suspend fun getActionCounts(userId: String): Map<String, Map<String, Int>> {
        // Returns: state -> (actionType -> count)
        // We count from community_interactions joined with events for state info
        // For now, count from user_badges milestones as proxy
        // Actual counts should come from community_interactions + plans tables
        @Serializable
        data class InteractionRow(
            @SerialName("interaction_type") val interactionType: String,
            @SerialName("item_id") val itemId: String,
        )

        @Serializable
        data class EventStateRow(
            val id: String,
            @SerialName("venue_state") val venueState: String? = null,
        )

        @Serializable
        data class PlanStateRow(
            val id: String,
            val city: String? = null,
        )

        val result = mutableMapOf<String, MutableMap<String, Int>>()

        try {
            // Get favorites (event_saved)
            val favorites = supabaseClient.from("community_interactions")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("interaction_type", "favorite")
                        eq("item_type", "event")
                    }
                }
                .decodeList<InteractionRow>()

            // Get shares (event_shared)
            val shares = supabaseClient.from("community_interactions")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("interaction_type", "share")
                        eq("item_type", "event")
                    }
                }
                .decodeList<InteractionRow>()

            // Get event states for all relevant event IDs
            val eventIds = (favorites.map { it.itemId } + shares.map { it.itemId }).distinct()
            val eventStates = if (eventIds.isNotEmpty()) {
                eventIds.chunked(100).flatMap { chunk ->
                    supabaseClient.from("events")
                        .select {
                            filter { isIn("id", chunk) }
                        }
                        .decodeList<EventStateRow>()
                }
                    .associate { it.id to (it.venueState ?: "MA") }
            } else emptyMap()

            // Count favorites by state
            favorites.forEach { fav ->
                val state = eventStates[fav.itemId] ?: "MA"
                val stateMap = result.getOrPut(state) { mutableMapOf() }
                stateMap["event_saved"] = (stateMap["event_saved"] ?: 0) + 1
            }

            // Count shares by state
            shares.forEach { share ->
                val state = eventStates[share.itemId] ?: "MA"
                val stateMap = result.getOrPut(state) { mutableMapOf() }
                stateMap["event_shared"] = (stateMap["event_shared"] ?: 0) + 1
            }

            // Count plans created by state (derive state from city)
            val plans = supabaseClient.from("plans")
                .select {
                    filter { eq("user_id", userId) }
                }
                .decodeList<PlanStateRow>()

            plans.forEach { plan ->
                val state = cityToState(plan.city ?: "boston")
                val stateMap = result.getOrPut(state) { mutableMapOf() }
                stateMap["plan_created"] = (stateMap["plan_created"] ?: 0) + 1
            }

            // Plan shares — count from community_interactions
            val planShares = supabaseClient.from("community_interactions")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("interaction_type", "share")
                        eq("item_type", "plan")
                    }
                }
                .decodeList<InteractionRow>()

            planShares.forEach { share ->
                // Default to user's primary state since plans don't have venue_state
                val state = "MA" // TODO: derive from plan city
                val stateMap = result.getOrPut(state) { mutableMapOf() }
                stateMap["plan_shared"] = (stateMap["plan_shared"] ?: 0) + 1
            }
        } catch (e: Throwable) {
            println("BadgeRepo.getActionCounts error: ${e.message}")
        }

        return result
    }

    override suspend fun awardMilestone(
        userId: String,
        state: String,
        actionType: String,
        milestone: Int,
        points: Int,
    ): BadgeMilestone? {
        return try {
            val dto = BadgeMilestoneDto(
                userId = userId,
                state = state,
                actionType = actionType,
                milestone = milestone,
                points = points,
            )
            supabaseClient.from("user_badges")
                .upsert(dto) {
                    onConflict = "user_id,state,action_type,milestone"
                    ignoreDuplicates = true
                }
            dto.toDomain()
        } catch (e: Throwable) {
            println("BadgeRepo.awardMilestone error: ${e.message}")
            null
        }
    }

    override suspend fun getTotalPoints(userId: String): Int {
        return try {
            getMilestones(userId).sumOf { it.points }
        } catch (e: Throwable) {
            println("BadgeRepo.getTotalPoints error: ${e.message}")
            0
        }
    }

    /** Simple city-to-state mapping. Expand as cities are added. */
    private fun cityToState(city: String): String {
        return when (city.lowercase().trim()) {
            "boston", "cambridge", "somerville", "newton", "waltham",
            "brookline", "worcester", "springfield", "lowell" -> "MA"
            "portland", "south portland", "scarborough" -> "ME"
            "new york", "brooklyn", "manhattan", "queens" -> "NY"
            "providence", "warwick", "cranston" -> "RI"
            "burlington", "montpelier" -> "VT"
            "portsmouth", "concord", "nashua", "manchester" -> "NH"
            "hartford", "new haven", "stamford" -> "CT"
            else -> "MA"
        }
    }
}
