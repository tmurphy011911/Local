package live.localapp.local.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import live.localapp.local.data.remote.dto.BadgeMilestoneDto
import live.localapp.local.data.remote.dto.toDomain
import live.localapp.local.domain.model.BadgeMilestone
import live.localapp.local.domain.repository.BadgeRepository
import live.localapp.local.domain.repository.CreatorAcceptSnapshot

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
        // Reads the lifetime monotonic counters from `user_action_counts`.
        // Counts never decrement when the user unsaves an event, deletes a
        // plan, etc. — see the user_action_counts migration for the rationale.
        //
        // The legacy queries against community_interactions / plans /
        // plan_attendees that used to live here were the source of the
        // "counts drop when you undo" bug. They're replaced by a single read
        // from the counter table; writes happen via incrementActionCount /
        // bumpActionCountTo from BadgeService.
        @Serializable
        data class ActionCountRow(
            @SerialName("action_type") val actionType: String,
            val count: Int,
        )

        return try {
            val rows = supabaseClient.from("user_action_counts")
                .select {
                    filter { eq("user_id", userId) }
                }
                .decodeList<ActionCountRow>()
            // Counts are tracked globally; preserve the outer
            // state -> action -> count shape for backward compat.
            mapOf("global" to rows.associate { it.actionType to it.count })
        } catch (e: Throwable) {
            println("BadgeRepo.getActionCounts error: ${e.message}")
            emptyMap()
        }
    }

    override suspend fun incrementActionCount(userId: String, actionType: String): Int {
        return try {
            val params = buildJsonObject {
                put("p_user_id", JsonPrimitive(userId))
                put("p_action_type", JsonPrimitive(actionType))
            }
            val result = supabaseClient.postgrest.rpc("increment_action_count", params)
            result.data.trim().trim('"').toIntOrNull() ?: 0
        } catch (e: Throwable) {
            println("BadgeRepo.incrementActionCount($actionType) error: ${e.message}")
            0
        }
    }

    override suspend fun bumpActionCountTo(userId: String, actionType: String, snapshot: Int): Int {
        return try {
            val params = buildJsonObject {
                put("p_user_id", JsonPrimitive(userId))
                put("p_action_type", JsonPrimitive(actionType))
                put("p_snapshot", JsonPrimitive(snapshot))
            }
            val result = supabaseClient.postgrest.rpc("bump_action_count_to", params)
            result.data.trim().trim('"').toIntOrNull() ?: 0
        } catch (e: Throwable) {
            println("BadgeRepo.bumpActionCountTo($actionType, $snapshot) error: ${e.message}")
            0
        }
    }

    override suspend fun getCreatorAcceptSnapshot(userId: String): CreatorAcceptSnapshot {
        // Live computation from `plan_attendees`, scoped to plans the user
        // owns. Used by BadgeService.claimCreatorBadges to compute the value
        // it then passes to bumpActionCountTo — so the lifetime counters
        // ratchet up from the all-time-high snapshot, never down.
        @Serializable
        data class PlanIdRow(val id: String)
        @Serializable
        data class CreatorAttendeeRow(
            @SerialName("plan_id") val planId: String,
        )

        return try {
            val plans = supabaseClient.from("plans")
                .select {
                    filter { eq("user_id", userId) }
                }
                .decodeList<PlanIdRow>()
            val ownedPlanIds = plans.map { it.id }
            if (ownedPlanIds.isEmpty()) {
                return CreatorAcceptSnapshot(0, qualifiesForGroup5 = false, qualifiesForGroup10 = false)
            }

            val attendees = ownedPlanIds.chunked(100).flatMap { chunk ->
                supabaseClient.from("plan_attendees")
                    .select {
                        filter {
                            isIn("plan_id", chunk)
                            eq("is_creator", false)
                            eq("confirmed", true)
                        }
                    }
                    .decodeList<CreatorAttendeeRow>()
            }
            val maxPerPlan = attendees.groupingBy { it.planId }
                .eachCount()
                .values
                .maxOrNull() ?: 0

            CreatorAcceptSnapshot(
                totalAccepts = attendees.size,
                qualifiesForGroup5 = maxPerPlan >= 5,
                qualifiesForGroup10 = maxPerPlan >= 10,
            )
        } catch (e: Throwable) {
            println("BadgeRepo.getCreatorAcceptSnapshot error: ${e.message}")
            CreatorAcceptSnapshot(0, qualifiesForGroup5 = false, qualifiesForGroup10 = false)
        }
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
