package live.localapp.local.domain.repository

import live.localapp.local.data.remote.dto.LeaderboardDto

/** The three geo scopes the leaderboard can be viewed at. `wire` = RPC param. */
enum class LeaderboardScope(val wire: String) {
    CITY("city"),
    STATE("state"),
    COUNTRY("country"),
}

interface LeaderboardRepository {
    /**
     * Fetch the leaderboard for [scope], scoped to the CURRENT user's own home
     * location (resolved server-side from their profile). Returns null on no
     * logged-in user or any network/RPC error — callers fall back to cache or a
     * quiet empty state.
     */
    suspend fun getLeaderboard(scope: LeaderboardScope): LeaderboardDto?
}
