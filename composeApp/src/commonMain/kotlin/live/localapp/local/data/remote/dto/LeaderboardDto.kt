package live.localapp.local.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One ranked row in the leaderboard, OR the caller's own row. Shape mirrors the
 * jsonb returned by the `get_leaderboard` RPC. `inTop100` is only meaningful on
 * the `me` object (drives the home CTA copy variant); it's absent/false on the
 * top-10 rows.
 */
@Serializable
data class LeaderboardEntryDto(
    val rank: Int = 0,
    // Only the caller's own `me` row carries user_id (their own id). Ranked rows
    // omit it — they identify the caller via [isMe] instead, so other users'
    // auth UUIDs are never exposed cross-user.
    @SerialName("user_id") val userId: String? = null,
    val username: String? = null,
    @SerialName("joined_at") val joinedAt: String? = null,
    @SerialName("fomo_total") val fomoTotal: Int = 0,
    @SerialName("in_top_100") val inTop100: Boolean = false,
    @SerialName("is_me") val isMe: Boolean = false,
    // Member's home city (hometown). Shown as row subtext on State/Country boards.
    val city: String? = null,
)

/**
 * Full payload for one scope view. `location` is a display-ready label
 * ("Massachusetts" / "Boston" / "United States"); `locationCode` is the raw
 * filter key (state code / city name / "US"). `me` is null when the caller
 * isn't in the scope (e.g. no home_state resolved).
 */
@Serializable
data class LeaderboardDto(
    val scope: String = "state",
    val location: String? = null,
    @SerialName("location_code") val locationCode: String? = null,
    @SerialName("total_users") val totalUsers: Int = 0,
    val rows: List<LeaderboardEntryDto> = emptyList(),
    val me: LeaderboardEntryDto? = null,
)
