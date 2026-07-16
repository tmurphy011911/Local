package live.localapp.local.domain.repository

/** One ranked item that was shown to the user in a surface. */
data class ImpressionItem(
    val itemId: String,
    val type: InteractionType,
    val position: Int,
)

/**
 * Captures "shown but not acted on" — the impression denominator the recommender
 * needs to distinguish "disliked" from "never saw" (spec §4.2). High volume, so
 * the impl logs each (surface,item) at most once per session and batches inserts.
 *
 * Item location/features are intentionally NOT stored here: an impression is
 * always on a live event, so those are recoverable via a server-side join. Only
 * the unrecoverable context lives on the row (surface, position, actor location).
 */
interface ImpressionRepository {
    /**
     * Record that [items] were shown on [surface] (e.g. "explore_events",
     * "explore_places", "for_you", "bo"). [actorLat]/[actorLng] = the user's
     * active/set location when shown (spec §5.4).
     */
    fun recordImpressions(
        items: List<ImpressionItem>,
        surface: String,
        actorLat: Double? = null,
        actorLng: Double? = null,
    )

    /** Flush any buffered impressions immediately (e.g. on backgrounding). */
    fun flush()

    /** Clear session dedup + buffer (call on logout). */
    fun clearSession()
}
