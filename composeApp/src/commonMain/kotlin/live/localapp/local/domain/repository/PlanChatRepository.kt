package live.localapp.local.domain.repository

data class PlanChatMessageRecord(
    val id: String,
    val planId: String,
    val userId: String,
    val senderName: String,
    val message: String,
    val isYocal: Boolean,
    val createdAt: String,
)

data class PlanChatReactionRecord(
    val id: String,
    val messageId: String,
    val userId: String,
    val emoji: String,
)

interface PlanChatRepository {
    /** Fetch all messages for a plan, ordered oldest → newest. */
    suspend fun getMessages(planId: String): List<PlanChatMessageRecord>

    /** Insert a new message. Returns the inserted record, or null on failure. */
    suspend fun sendMessage(
        planId: String,
        userId: String,
        senderName: String,
        message: String,
        isYocal: Boolean = false,
    ): PlanChatMessageRecord?

    /** Fetch all reactions for messages in a plan. */
    suspend fun getReactions(planId: String): List<PlanChatReactionRecord>

    /** Add a reaction. No-op if already exists (unique constraint). */
    suspend fun addReaction(messageId: String, userId: String, emoji: String)

    /** Remove a reaction. */
    suspend fun removeReaction(messageId: String, userId: String, emoji: String)
}
