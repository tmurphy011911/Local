package live.localapp.local

import live.localapp.local.ui.chat.ChatMessage
import live.localapp.local.ui.chat.ChatUiState
import live.localapp.local.ui.chat.MessageContent
import live.localapp.local.ui.chat.MessageRole
import live.localapp.local.ui.chat.QuickReply
import live.localapp.local.ui.chat.ItineraryDay
import live.localapp.local.ui.chat.ItinerarySlot

/**
 * Bridge between Kotlin ChatViewModel and SwiftUI ChatView.
 *
 * Kotlin pushes state updates → Swift observes.
 * Swift sends actions → Kotlin executes.
 */
/**
 * Drop glyphs that SwiftUI's Nunito font can't render (emoji, misc symbols).
 * Keeps ASCII, Latin, Cyrillic, CJK, arrows, and standard punctuation; strips
 * anything in the Miscellaneous Symbols / Emoji / Dingbat ranges.
 */
private fun stripUnsupportedGlyphs(text: String): String {
    val filtered = text.filter { c ->
        val cp = c.code
        cp < 0x2600 || (cp in 0x2800..0xFDFF) || (cp in 0xFE10..0xFE1F) || (cp in 0xFF00..0xFFEF)
    }
    // Collapse whitespace that was left by stripped glyphs.
    return filtered.replace(Regex("[ \\t]{2,}"), " ")
}

object ChatBridge {
    // ── State pushed from Kotlin to Swift ──
    // Keep the latest snapshot so we can replay it when SwiftUI attaches its
    // observer after the VM has already fired (e.g. welcome greeting emitted
    // before ChatView.onAppear wires up onStateUpdate).
    private var lastSnapshot: ChatStateSnapshot? = null
    private var _onStateUpdate: ((ChatStateSnapshot) -> Unit)? = null
    var onStateUpdate: ((ChatStateSnapshot) -> Unit)?
        get() = _onStateUpdate
        set(value) {
            _onStateUpdate = value
            if (value != null) {
                lastSnapshot?.let(value)
            }
        }

    // ── Actions from Swift to Kotlin ──
    var sendMessage: ((String) -> Unit)? = null
    var resetChat: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null
    // Quick reply: pass both label (display text) and action (command token)
    // so the VM can route specials like "reset" / "save_plan" correctly.
    var onQuickReply: ((String, String) -> Unit)? = null
    // Fires when PremiumGate blocks a plan save — Swift presents the paywall.
    var onPlanBlocked: (() -> Unit)? = null
    // Clear the plan-saved celebration and reset chat.
    var clearSuccessState: (() -> Unit)? = null

    // ── Event/venue taps from Swift ──
    var onEventTap: ((String, String?) -> Unit)? = null  // eventId, imageUrl
    var onVenueTap: ((String) -> Unit)? = null            // venueName

    /** Push a state snapshot to Swift */
    fun pushState(state: ChatUiState) {
        val messages = state.messages.map { msg ->
            val rawText = when (msg.content) {
                is MessageContent.Text -> (msg.content as MessageContent.Text).text
                is MessageContent.DayCardRef -> "[plan_card]"
                is MessageContent.MultiDayPlanRef -> "[multi_day_plan]"
            }
            ChatMessageSnapshot(
                id = msg.id,
                isUser = msg.role == MessageRole.USER,
                // SwiftUI's chat renderer uses Nunito, which doesn't ship emoji
                // glyphs — anything outside the common Unicode planes shows as
                // tofu. Strip before crossing the bridge. User text is passed
                // through untouched.
                text = if (msg.role == MessageRole.USER) rawText else stripUnsupportedGlyphs(rawText),
                isPlanCard = msg.content is MessageContent.DayCardRef || msg.content is MessageContent.MultiDayPlanRef,
                planSnapshot = when (msg.content) {
                    is MessageContent.DayCardRef -> (msg.content as MessageContent.DayCardRef).snapshot?.map { it.toSnapshot() }
                    is MessageContent.MultiDayPlanRef -> (msg.content as MessageContent.MultiDayPlanRef).snapshot?.map { it.toSnapshot() }
                    else -> null
                },
                dayIndex = if (msg.content is MessageContent.DayCardRef) (msg.content as MessageContent.DayCardRef).dayIndex else 0,
                isSuperseded = msg.id in supersededIds,
            )
        }

        val snapshot = ChatStateSnapshot(
            messages = messages,
            quickReplies = state.quickReplies.map { QuickReplySnapshot(it.label, it.isGreen, it.action) },
            streamingText = stripUnsupportedGlyphs(state.streamingText),
            isTyping = state.isTyping,
            isSearching = state.isSearching,
            isBusy = state.isBusy,
            inputEnabled = state.inputEnabled,
            showPlanSuccess = state.showPlanSuccess,
            days = state.days.map { it.toSnapshot() },
            weather = state.weather.mapValues { (_, w) -> WeatherSnapshot(w.tempHigh, w.description) },
        )

        lastSnapshot = snapshot
        _onStateUpdate?.invoke(snapshot)
    }

    var supersededIds: Set<String> = emptySet()
}

// ── Snapshot data classes (simple, no Kotlin-specific types) ──

data class ChatStateSnapshot(
    val messages: List<ChatMessageSnapshot>,
    val quickReplies: List<QuickReplySnapshot>,
    val streamingText: String,
    val isTyping: Boolean,
    val isSearching: Boolean,
    val isBusy: Boolean,
    val inputEnabled: Boolean,
    val showPlanSuccess: Boolean,
    val days: List<DaySnapshot>,
    val weather: Map<Int, WeatherSnapshot> = emptyMap(),
)

data class WeatherSnapshot(
    val tempHigh: Int,
    val description: String,
)

data class ChatMessageSnapshot(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val isPlanCard: Boolean,
    val planSnapshot: List<DaySnapshot>?,
    val dayIndex: Int,
    val isSuperseded: Boolean,
)

data class QuickReplySnapshot(
    val label: String,
    val isGreen: Boolean,
    val action: String,
)

data class DaySnapshot(
    val label: String,
    val shortName: String,
    val slots: List<SlotSnapshot>,
    val isApproved: Boolean,
)

data class SlotSnapshot(
    val id: String,
    val time: String,
    val name: String,
    val detail: String,
    val price: String,
    val isFree: Boolean,
    val imageUrl: String?,
    val eventId: String?,
    val venueRating: Double?,
)

private fun ItineraryDay.toSnapshot() = DaySnapshot(
    label = label,
    shortName = shortName,
    slots = slots.map { it.toSnapshot() },
    isApproved = isApproved,
)

private fun ItinerarySlot.toSnapshot() = SlotSnapshot(
    id = id,
    time = time,
    name = name,
    detail = detail,
    price = price,
    isFree = isFree,
    imageUrl = imageUrl,
    eventId = eventId,
    venueRating = venueRating,
)
