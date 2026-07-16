package live.localapp.local.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import live.localapp.local.data.remote.dto.LeaderboardDto
import live.localapp.local.domain.repository.ChangeHomeCityResult
import live.localapp.local.domain.repository.LeaderboardRepository
import live.localapp.local.domain.repository.LeaderboardScope
import live.localapp.local.domain.repository.UserPreferencesRepository

/**
 * Calls the `get_leaderboard` Postgres RPC directly (same transport as
 * search_events_stratified / accept_plan_invite). The RPC returns a single
 * jsonb object — top 10 + the caller's row + flags — so one round trip drives a
 * whole scope view. No edge function needed.
 */
class LeaderboardRepositoryImpl(
    private val supabaseClient: SupabaseClient,
    private val userPrefsRepo: UserPreferencesRepository,
) : LeaderboardRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getLeaderboard(scope: LeaderboardScope): LeaderboardDto? {
        val userId = userPrefsRepo.getLocalUserId() ?: return null
        return try {
            val params = buildJsonObject {
                put("p_user_id", JsonPrimitive(userId))
                put("p_scope", JsonPrimitive(scope.wire))
            }
            val result = supabaseClient.postgrest.rpc("get_leaderboard", params)
            json.decodeFromString(LeaderboardDto.serializer(), result.data)
        } catch (e: Throwable) {
            println("LeaderboardRepository.getLeaderboard(${scope.wire}) error: ${e.message}")
            null
        }
    }

    override suspend fun changeHomeCity(name: String, lat: Double, lng: Double, placeId: String?): ChangeHomeCityResult {
        val userId = userPrefsRepo.getLocalUserId() ?: return ChangeHomeCityResult.Error
        return try {
            val params = buildJsonObject {
                put("p_user_id", JsonPrimitive(userId))
                put("p_name", JsonPrimitive(name))
                put("p_lat", JsonPrimitive(lat))
                put("p_lng", JsonPrimitive(lng))
                put("p_place_id", placeId?.let { JsonPrimitive(it) } ?: JsonNull)
            }
            val result = supabaseClient.postgrest.rpc("change_home_city", params)
            val obj = json.parseToJsonElement(result.data).jsonObject
            val nextAllowed = obj["next_allowed_at"]?.jsonPrimitive?.contentOrNull
            when {
                obj["ok"]?.jsonPrimitive?.contentOrNull == "true" -> ChangeHomeCityResult.Success(nextAllowed)
                obj["error"]?.jsonPrimitive?.contentOrNull == "rate_limited" -> ChangeHomeCityResult.RateLimited(nextAllowed)
                else -> ChangeHomeCityResult.Error
            }
        } catch (e: Throwable) {
            println("LeaderboardRepository.changeHomeCity error: ${e.message}")
            ChangeHomeCityResult.Error
        }
    }
}
