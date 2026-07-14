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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
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
 * Markdown-aware: [fullText] is parsed once into a styled [AnnotatedString]
 * (via [boldAnnotatedString]) and the reveal slices a prefix with
 * [AnnotatedString.subSequence], which preserves span styling. This means
 * bold (`**…**`), headers, and bullets render LIVE throughout the reveal
 * instead of leaking literal `**` until the message completes.
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
    // Parse once into the styled string. Note: boldAnnotatedString also
    // normalizes headers/bullets and strips emoji, so its length may differ
    // from fullText.length — reveal against the annotated length.
    val annotated = remember(fullText, color) { boldAnnotatedString(fullText, color) }
    val totalChars = annotated.length

    val alreadyDone = TypewriterCompletionStore.isCompleted(messageId)
    var visibleChars by remember(messageId) {
        mutableStateOf(if (alreadyDone) totalChars else 0)
    }

    LaunchedEffect(messageId, totalChars) {
        if (TypewriterCompletionStore.isCompleted(messageId)) {
            visibleChars = totalChars
            return@LaunchedEffect
        }
        visibleChars = 0
        while (visibleChars < totalChars) {
            delay(charDelayMs)
            visibleChars = (visibleChars + 1).coerceAtMost(totalChars)
        }
        TypewriterCompletionStore.markCompleted(messageId)
    }

    // Reveal without reflow: always lay out the FULL styled string (so line
    // breaks + height are fixed from the first frame) and hide the not-yet-typed
    // tail with a transparent span. Slicing a shorter prefix instead would
    // re-wrap every frame, making a word visibly jump to the next line the
    // instant it grows past the line boundary — the "hitch on wrap".
    val shownText = remember(annotated, visibleChars) {
        val n = visibleChars.coerceIn(0, totalChars)
        if (n >= totalChars) annotated
        else buildAnnotatedString {
            append(annotated)
            addStyle(SpanStyle(color = Color.Transparent), n, totalChars)
        }
    }
    Text(
        text = shownText,
        style = style.copy(fontSize = fontSize, color = color),
        modifier = modifier,
    )
}
