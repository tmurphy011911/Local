package live.localapp.local.domain.repository

enum class InteractionType { EVENT, PLACE }

interface CommunityInterestRepository {
    /** Call when a user taps an event or place card. */
    fun recordClick(id: String, type: InteractionType, category: String? = null)

    /** Call when a user shares an event or place. */
    fun recordShare(id: String, type: InteractionType, category: String? = null)

    /** Call when a user favorites an event or place. */
    fun recordFavorite(id: String, type: InteractionType, category: String? = null)

    /** Remove a favorite. */
    fun removeFavorite(id: String, type: InteractionType)

    /** Call when a user adds an event or place to their plan. */
    fun recordPlanAdd(id: String, type: InteractionType, category: String? = null)

    /** Current user's weighted score for an item. */
    fun personalRawScore(id: String): Double

    /** Community (everyone else) weighted score for an item. */
    fun communityRawScore(id: String): Double

    /**
     * Raw total score for a single item: personal + community.
     * Kept for backwards compatibility.
     */
    fun rawScore(id: String): Double

    /**
     * Score normalized 0–1 relative to the highest-scoring item in [peerIds].
     * Returns 0 if no interactions have been recorded for any peer.
     */
    fun normalizedScore(id: String, peerIds: List<String>): Double

    /**
     * User's category preferences derived from personal interaction history.
     * Returns a map of category string -> affinity score (0–1).
     */
    fun categoryAffinity(): Map<String, Double>

    /**
     * Total saves (favorites + plan_adds) across all users for a given item.
     * Used for "Popular" carousel badges.
     */
    fun totalSaves(id: String): Int

    /** Return all event IDs the user has favorited. */
    suspend fun getFavoriteEventIds(userId: String): List<String>

    /** Return all venue/place IDs the user has favorited. */
    suspend fun getFavoriteVenueIds(userId: String): List<String>
}
