package live.localapp.local.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Mirrors iOS [ChatView.swift] typewriter behavior: AI text reveals
 * character-by-character at ~28ms/char, and completed message IDs are cached
 * in a process-wide singleton so reopening the chat doesn't replay finished
 * messages.
 */
object TypewriterCompletionStore {
    val completed: MutableSet<String> = mutableSetOf()

    fun markCompleted(messageId: String) {
        completed.add(messageId)
    }

    fun isCompleted(messageId: String): Boolean = completed.contains(messageId)
}

/**
 * Renders [fullText] with a typewriter reveal. If [messageId] has already
 * been marked complete in [TypewriterCompletionStore], renders the full
 * text instantly (so reopens don't replay). Otherwise increments a visible
 * character count once per [charDelayMs] until the full string is shown,
 * then marks the message complete.
 *
 * Note: this intentionally renders raw text (no markdown). The caller is
 * responsible for choosing typewriter vs. rich rendering — typewriter is
 * used for short conversational replies; rich text is used elsewhere.
 */
@Composable
fun TypewritingAiText(
    messageId: String,
    fullText: String,
    color: Color,
    style: TextStyle,
    fontSize: TextUnit = 15.sp,
    charDelayMs: Long = 28,
    modifier: Modifier = Modifier,
) {
    val alreadyDone = TypewriterCompletionStore.isCompleted(messageId)
    var visibleChars by remember(messageId) {
        mutableStateOf(if (alreadyDone) fullText.length else 0)
    }

    LaunchedEffect(messageId, fullText) {
        if (TypewriterCompletionStore.isCompleted(messageId)) {
            visibleChars = fullText.length
            return@LaunchedEffect
        }
        visibleChars = 0
        while (visibleChars < fullText.length) {
            delay(charDelayMs)
            visibleChars = (visibleChars + 1).coerceAtMost(fullText.length)
        }
        TypewriterCompletionStore.markCompleted(messageId)
    }

    Text(
        text = fullText.take(visibleChars),
        style = style.copy(fontSize = fontSize, color = color),
        modifier = modifier,
    )
}
