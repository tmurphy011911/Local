package live.localapp.local.domain.repository

import live.localapp.local.domain.model.Event
import live.localapp.local.domain.model.TasteProfile
import live.localapp.local.ui.chat.ItinerarySlot

/**
 * Server-built taste summary (user_taste_summary) — the richer taste vector the
 * nightly builder produces. Read-only on the client; used to personalize Bo.
 */
data class InterestSummary(
    val interests: List<String>,      // interest_1..5, human-readable
    val topEntities: List<String>,    // performer/brand slugs
    val travelPropensity: Double?,    // 0-1, or null if not enough signal
    val interactionCount: Int,
)

interface TasteRepository {
    suspend fun getTasteProfile(userId: String): TasteProfile?

    /** Server-built interest summary (user_taste_summary); null if none/absent. */
    suspend fun getInterestSummary(userId: String): InterestSummary?
    suspend fun saveTasteProfile(profile: TasteProfile)
    suspend fun recordPlanApproval(userId: String, slots: List<ItinerarySlot>, events: List<Event>)
    suspend fun recordEventTap(userId: String, event: Event)
}
