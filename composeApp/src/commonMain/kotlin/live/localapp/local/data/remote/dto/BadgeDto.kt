package live.localapp.local.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import live.localapp.local.domain.model.BadgeMilestone

@Serializable
data class BadgeMilestoneDto(
    val id: String = "",
    @SerialName("user_id") val userId: String,
    val state: String,
    @SerialName("action_type") val actionType: String,
    val milestone: Int,
    val points: Int,
    @SerialName("earned_at") val earnedAt: String = "",
)

fun BadgeMilestoneDto.toDomain(): BadgeMilestone = BadgeMilestone(
    id = id,
    userId = userId,
    state = state,
    actionType = actionType,
    milestone = milestone,
    points = points,
    earnedAt = earnedAt,
)

@Serializable
data class ActionCountRow(
    @SerialName("item_type") val itemType: String = "",
    @SerialName("interaction_type") val interactionType: String = "",
    val state: String = "",
)
