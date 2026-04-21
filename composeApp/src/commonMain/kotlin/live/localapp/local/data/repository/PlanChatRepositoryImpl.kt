package live.localapp.local.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import live.localapp.local.domain.repository.PlanChatMessageRecord
import live.localapp.local.domain.repository.PlanChatReactionRecord
import live.localapp.local.domain.repository.PlanChatRepository

@Serializable
private data class PlanChatMessageDto(
    val id: String = "",
    @SerialName("plan_id") val planId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("sender_name") val senderName: String,
    val message: String,
    @SerialName("is_yocal") val isYocal: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
private data class PlanChatReactionDto(
    val id: String = "",
    @SerialName("message_id") val messageId: String,
    @SerialName("user_id") val userId: String,
    val emoji: String,
)

@Serializable
private data class PlanChatReactionInsertDto(
    @SerialName("message_id") val messageId: String,
    @SerialName("user_id") val userId: String,
    val emoji: String,
)

class PlanChatRepositoryImpl(
    private val supabaseClient: SupabaseClient,
) : PlanChatRepository {

    override suspend fun getMessages(planId: String): List<PlanChatMessageRecord> {
        return try {
            supabaseClient.from("plan_chat_messages")
                .select {
                    filter { eq("plan_id", planId) }
                    order("created_at", Order.ASCENDING)
                }
                .decodeList<PlanChatMessageDto>()
                .map { it.toRecord() }
        } catch (e: Throwable) {
            println("PlanChatRepository.getMessages error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun sendMessage(
        planId: String,
        userId: String,
        senderName: String,
        message: String,
        isYocal: Boolean,
    ): PlanChatMessageRecord? {
        return try {
            val dto = PlanChatMessageDto(
                planId = planId,
                userId = userId,
                senderName = senderName,
                message = message,
                isYocal = isYocal,
            )
            val result = supabaseClient.from("plan_chat_messages")
                .insert(dto) { select() }
                .decodeList<PlanChatMessageDto>()
            result.firstOrNull()?.toRecord()
        } catch (e: Throwable) {
            println("PlanChatRepository.sendMessage error: ${e.message}")
            null
        }
    }

    override suspend fun getReactions(planId: String): List<PlanChatReactionRecord> {
        return try {
            // Fetch messages first to get their IDs, then reactions for those messages
            val messageIds = supabaseClient.from("plan_chat_messages")
                .select(columns = io.github.jan.supabase.postgrest.query.Columns.list("id")) {
                    filter { eq("plan_id", planId) }
                }
                .decodeList<IdOnlyDto>()
                .map { it.id }
            if (messageIds.isEmpty()) return emptyList()
            supabaseClient.from("plan_chat_reactions")
                .select {
                    filter { isIn("message_id", messageIds) }
                }
                .decodeList<PlanChatReactionDto>()
                .map { PlanChatReactionRecord(it.id, it.messageId, it.userId, it.emoji) }
        } catch (e: Throwable) {
            println("PlanChatRepository.getReactions error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun addReaction(messageId: String, userId: String, emoji: String) {
        try {
            supabaseClient.from("plan_chat_reactions")
                .insert(PlanChatReactionInsertDto(messageId, userId, emoji))
        } catch (_: Throwable) {
            // Unique constraint violation is fine — already reacted
        }
    }

    override suspend fun removeReaction(messageId: String, userId: String, emoji: String) {
        try {
            supabaseClient.from("plan_chat_reactions").delete {
                filter {
                    eq("message_id", messageId)
                    eq("user_id", userId)
                    eq("emoji", emoji)
                }
            }
        } catch (e: Throwable) {
            println("PlanChatRepository.removeReaction error: ${e.message}")
        }
    }

    @Serializable
    private data class IdOnlyDto(val id: String)

    private fun PlanChatMessageDto.toRecord() = PlanChatMessageRecord(
        id = id,
        planId = planId,
        userId = userId,
        senderName = senderName,
        message = message,
        isYocal = isYocal,
        createdAt = createdAt,
    )
}
