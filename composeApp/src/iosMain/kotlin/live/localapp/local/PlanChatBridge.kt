package live.localapp.local

import live.localapp.local.ui.plans.PlanChatMessageUi
import live.localapp.local.ui.plans.PlanChatUiState

/**
 * Bridge between Kotlin PlanChatViewModel and SwiftUI PlanChatView.
 *
 * Kotlin pushes state → Swift observes.
 * Swift fires actions → Kotlin executes.
 *
 * Unlike [ChatBridge] (singleton), this is tied to a specific plan — each open
 * wires a fresh VM and replaces the callbacks.
 */
object PlanChatBridge {
    private var lastSnapshot: PlanChatStateSnapshot? = null
    private var _onStateUpdate: ((PlanChatStateSnapshot) -> Unit)? = null
    var onStateUpdate: ((PlanChatStateSnapshot) -> Unit)?
        get() = _onStateUpdate
        set(value) {
            _onStateUpdate = value
            if (value != null) lastSnapshot?.let(value)
        }

    // ── Plan metadata (title shown in chat header) ──
    var planTitle: String = ""
    var planId: String = ""
    /** Optional pre-filled message — set when user tapped "Edit plan". */
    var prePopulatedMessage: String? = null
    /** Current user ID — used to mark reactedByMe on reactions. */
    var currentUserId: String? = null

    // ── Actions from Swift ──
    var sendMessage: ((String) -> Unit)? = null
    var toggleReaction: ((String, String) -> Unit)? = null  // messageId, emoji
    var confirmChange: (() -> Unit)? = null
    var cancelChange: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    /** Called by Kotlin when PlanChatViewModel state changes. */
    fun pushState(state: PlanChatUiState) {
        val uid = currentUserId
        val messages = state.messages.map { it.toSnapshot(uid) }
        val snap = PlanChatStateSnapshot(
            messages = messages,
            isLoading = state.isLoading,
            isYocalTyping = state.isYocalTyping,
            streamingText = state.streamingText,
            pendingConfirmationSummary = state.pendingConfirmation?.summary,
        )
        lastSnapshot = snap
        _onStateUpdate?.invoke(snap)
    }

    /** Clears state + callbacks when the sheet closes. */
    fun reset() {
        lastSnapshot = null
        _onStateUpdate = null
        sendMessage = null
        toggleReaction = null
        confirmChange = null
        cancelChange = null
        onClose = null
        planTitle = ""
        planId = ""
        prePopulatedMessage = null
        currentUserId = null
    }
}

// ── Snapshot data classes (Swift-visible) ──

data class PlanChatStateSnapshot(
    val messages: List<PlanChatMessageSnapshot>,
    val isLoading: Boolean,
    val isYocalTyping: Boolean,
    val streamingText: String,
    val pendingConfirmationSummary: String?,
)

data class PlanChatMessageSnapshot(
    val id: String,
    val userId: String,
    val senderName: String,
    val text: String,
    val isYocal: Boolean,
    val isCurrentUser: Boolean,
    val createdAt: String,
    val reactions: List<PlanChatReactionGroupSnapshot>,
)

data class PlanChatReactionGroupSnapshot(
    val emoji: String,
    val count: Int,
    val reactedByMe: Boolean,
)

/** Lifecycle events for the plan chat sheet. Mirrors [ChatSheetBridge]. */
object PlanChatSheetBridge {
    var onOpenSheet: (() -> Unit)? = null
    var onCloseSheet: (() -> Unit)? = null
    var onOpenComplete: (() -> Unit)? = null
    var onCloseComplete: (() -> Unit)? = null
}

private fun PlanChatMessageUi.toSnapshot(currentUserId: String?): PlanChatMessageSnapshot {
    return PlanChatMessageSnapshot(
        id = id,
        userId = userId,
        senderName = senderName,
        text = text,
        isYocal = isYocal,
        isCurrentUser = isCurrentUser,
        createdAt = createdAt,
        reactions = reactions.map { (emoji, users) ->
            PlanChatReactionGroupSnapshot(
                emoji = emoji,
                count = users.size,
                reactedByMe = currentUserId != null && currentUserId in users,
            )
        },
    )
}
