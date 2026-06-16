package live.localapp.local.domain.repository

import live.localapp.local.data.remote.dto.LeaderboardDto

/** The three geo scopes the leaderboard can be viewed at. `wire` = RPC param. */
enum class LeaderboardScope(val wire: String) {
    CITY("city"),
    STATE("state"),
    COUNTRY("country"),
}

/** Outcome of a home-city change (rate-limited 1x/365d, enforced server-side). */
sealed interface ChangeHomeCityResult {
    data class Success(val nextAllowedAt: String?) : ChangeHomeCityResult
    data class RateLimited(val nextAllowedAt: String?) : ChangeHomeCityResult
    data object Error : ChangeHomeCityResult
}

interface LeaderboardRepository {
    /**
     * Fetch the leaderboard for [scope], scoped to the CURRENT user's own home
     * location (resolved server-side from their profile). Returns null on no
     * logged-in user or any network/RPC error — callers fall back to cache or a
     * quiet empty state.
     */
    suspend fun getLeaderboard(scope: LeaderboardScope): LeaderboardDto?

    /** Change the current user's home city via the rate-limited change_home_city RPC. */
    suspend fun changeHomeCity(name: String, lat: Double, lng: Double, placeId: String?): ChangeHomeCityResult
}
