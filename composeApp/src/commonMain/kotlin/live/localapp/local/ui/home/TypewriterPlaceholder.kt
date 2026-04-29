package live.localapp.local.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Placeholder that animates a typed prompt:
 * 1. Blinking cursor alone for [startDelayMs] so the user registers the cursor.
 * 2. Cursor continues to blink while each character is appended.
 * 3. After the last character, cursor stops blinking and fades out.
 *
 * Keyed on [fullText]: pass a new string to re-run the animation (useful when
 * the user returns to the home tab and we want a fresh prompt).
 */
@Composable
fun TypewriterPlaceholder(
    fullText: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    cursorColor: Color = style.color,
    cursorHeight: Dp = 15.dp,
    startDelayMs: Long = 650,
    charDelayMs: Long = 38,
    holdAfterMs: Long = 400,
) {
    var typed by remember(fullText) { mutableStateOf("") }
    var blinking by remember(fullText) { mutableStateOf(true) }
    val cursorAlpha = remember(fullText) { Animatable(1f) }

    // Blinking while [blinking] is true.
    LaunchedEffect(fullText, blinking) {
        if (!blinking) return@LaunchedEffect
        while (blinking) {
            cursorAlpha.animateTo(0f, tween(durationMillis = 420))
            cursorAlpha.animateTo(1f, tween(durationMillis = 420))
        }
    }

    // Typewriter sequence.
    LaunchedEffect(fullText) {
        typed = ""
        blinking = true
        delay(startDelayMs)
        for (i in 1..fullText.length) {
            typed = fullText.substring(0, i)
            delay(charDelayMs)
        }
        delay(holdAfterMs)
        blinking = false
        cursorAlpha.animateTo(0f, tween(durationMillis = 260))
    }

    // Auto-scroll the row so the cursor (the *end* of what's been typed) is
    // always pinned to the right side. When the prompt is longer than the
    // input bar, the user sees the trailing characters of what's currently
    // being typed instead of the head being chopped at the right edge.
    val scrollState = rememberScrollState()
    LaunchedEffect(typed) {
        // Animate to maxValue each time a new character lands. maxValue
        // updates after layout, so a single scrollTo is enough; we don't
        // need a frame delay because horizontalScroll re-measures on the
        // same recomposition pass.
        scrollState.scrollTo(scrollState.maxValue)
    }
    Row(
        modifier = modifier.horizontalScroll(scrollState, enabled = false),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(typed, style = style, maxLines = 1, softWrap = false)
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(cursorHeight)
                .alpha(cursorAlpha.value)
                .background(cursorColor, RoundedCornerShape(1.dp)),
        )
    }
}

/**
 * V1 fallback prompts. Used before the edge function returns, or if it fails.
 * Mix of generic intents + meal/activity cues. When the edge function lands
 * these will be replaced by a metro-specific 200-item list.
 */
val FALLBACK_YOCAL_PROMPTS = listOf(
    "A day in the city",
    "Free events for kids this weekend",
    "Upcoming food festivals",
    "Date night Friday",
    "Where to grab a great cocktail",
    "Fun things to do Saturday",
    "Anything free tonight",
    "Live music this week",
    "Best brunch spots nearby",
    "Outdoor things this weekend",
    "A romantic night out",
    "What's happening tomorrow",
    "Weekend plans for two",
    "Visitors in town — what to do",
    "Last-minute plans for tonight",
)
