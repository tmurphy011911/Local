package live.localapp.local.domain.repository

/**
 * Returns 4 personalized starter-chip labels for the Bo chat welcome
 * screen. Backed by the `bo-chips` edge function (Claude Haiku). Falls
 * back to an empty list on any failure so the viewmodel can substitute
 * its rules-based fallback.
 */
interface BoChipsRepository {
    /**
     * Fetch personalized chips. The edge function caches results per
     * user + day-period (morning/afternoon/evening/late) so re-opens
     * within the same period return the same chips.
     *
     * @param userId       Local user_profiles row id.
     * @param localHour    User's local hour, 0–23.
     * @param dayOfWeek    ISO day-of-week, 1=Monday..7=Sunday.
     * @param lat          Optional user lat for location flavor.
     * @param lng          Optional user lng for location flavor.
     * @return up to 4 chip labels; empty list on any failure.
     */
    suspend fun getChips(
        userId: String,
        localHour: Int,
        dayOfWeek: Int,
        lat: Double? = null,
        lng: Double? = null,
    ): List<String>
}
