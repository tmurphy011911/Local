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
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import live.localapp.local.platform.hapticSelection

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
    pending: Boolean = false,
    animate: Boolean = true,
    haptics: Boolean = true,
    onAnimated: (String) -> Unit = {},
) {
    // [pending]: no prompt has been chosen yet (e.g. foryou is still waiting on
    // the hyperlocal prompt fetch within its grace window). Show just a blinking
    // cursor so the bar isn't blank; when a prompt commits we switch to typing it.
    if (pending) {
        val pendingAlpha = remember { Animatable(1f) }
        LaunchedEffect(Unit) {
            while (true) {
                pendingAlpha.animateTo(0f, tween(durationMillis = 420))
                pendingAlpha.animateTo(1f, tween(durationMillis = 420))
            }
        }
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(cursorHeight)
                    .alpha(pendingAlpha.value)
                    .background(cursorColor, RoundedCornerShape(1.dp)),
            )
        }
        return
    }

    // When [animate] is false the prompt has already been typed once (e.g. the
    // card scrolled off and back, which disposes and recreates this composable).
    // Render the finished text immediately — no replay, no cursor — so scrolling
    // never re-triggers the typewriter. Only a genuinely new prompt animates.
    if (!animate) {
        val staticScroll = rememberScrollState()
        Row(
            modifier = modifier.horizontalScroll(staticScroll, enabled = false),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(fullText, style = style, maxLines = 1, softWrap = false)
        }
        return
    }

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

    // Typewriter sequence. Fires a light selection haptic on each non-whitespace
    // character so it feels like typing on a real keyboard. Whitespace is skipped
    // to keep the rhythm tight (otherwise long phrases feel buzzy).
    LaunchedEffect(fullText) {
        typed = ""
        blinking = true
        delay(startDelayMs)
        for (i in 1..fullText.length) {
            typed = fullText.substring(0, i)
            if (haptics && !fullText[i - 1].isWhitespace()) hapticSelection()
            delay(charDelayMs)
        }
        delay(holdAfterMs)
        blinking = false
        cursorAlpha.animateTo(0f, tween(durationMillis = 260))
        onAnimated(fullText)
    }

    // If the card is scrolled off-screen (disposed) mid-animation, the typewriter
    // LaunchedEffect above is cancelled and never reaches onAnimated — so without
    // this the prompt would be considered "not yet shown" and would replay from
    // scratch when scrolled back. Marking it shown on dispose makes scroll-away
    // count as completed: returning to the card renders the finished text (via the
    // !animate branch) instead of re-typing. Only a genuine category switch (which
    // changes the committed prompt) animates again.
    DisposableEffect(fullText) {
        onDispose { onAnimated(fullText) }
    }

    // Auto-scroll the row so the cursor (the *end* of what's been typed) is
    // always pinned to the right side. When the prompt is longer than the
    // input bar, the user sees the trailing characters of what's currently
    // being typed instead of the head being chopped at the right edge.
    //
    // Driven off snapshotFlow(maxValue) rather than a LaunchedEffect(typed):
    // keying on `typed` cancelled+relaunched a coroutine — and ran a full
    // scroll-mutex acquisition + scrollTo — on *every* character, including
    // the common case where the prompt still fits (maxValue == 0) and there
    // is nothing to scroll. That per-character churn produced a visible hitch
    // mid-type. Here we only react when maxValue actually changes (the content
    // grew past the edge), so short prompts do zero scroll work and long ones
    // snap to the new end exactly once per growth.
    val scrollState = rememberScrollState()
    LaunchedEffect(fullText) {
        snapshotFlow { scrollState.maxValue }
            .collect { max -> scrollState.scrollTo(max) }
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

/**
 * Category-specific prompt pools used when a non-"foryou" category is selected
 * on the home screen. Returning null means "use the generic For-You list".
 *
 * Keep prompts short enough that the typewriter fits on a phone screen without
 * scrolling far (≤ ~45 chars).
 */
fun categoryPromptsFor(categoryId: String): List<String>? = when (categoryId.lowercase()) {
    "music" -> listOf(
        "Live music this week",
        "Concerts tonight",
        "Best venues for indie shows",
        "Free outdoor concerts",
        "Jazz spots nearby",
        "Open mic nights this week",
        "Rooftop DJ sets",
        "Where's the best live blues",
    )
    "arts" -> listOf(
        "Gallery openings this week",
        "Museums to visit on a rainy day",
        "First Friday gallery night",
        "Theater shows nearby",
        "Live painting events",
        "Art walks this weekend",
        "Best independent galleries",
        "Performing arts this weekend",
    )
    "food", "food-drink" -> listOf(
        "Best brunch spots nearby",
        "Food festivals this weekend",
        "Where to grab a great cocktail",
        "New restaurants to try",
        "Late-night eats",
        "Outdoor dining nearby",
        "Best taco spots in town",
        "Hidden-gem dinner spots",
    )
    "experiences", "experience" -> listOf(
        "Hidden gems nearby",
        "Most unique date ideas",
        "Things to do tonight",
        "Adventures within an hour",
        "Indoor activities this weekend",
        "Last-minute plans for tonight",
        "Something I haven't done before",
        "Bucket-list activities nearby",
    )
    "for-kids", "kids" -> listOf(
        "Free events for kids this weekend",
        "Family-friendly things today",
        "Best playgrounds nearby",
        "Indoor kid activities",
        "Story times this week",
        "Kid-friendly restaurants",
        "Toddler-friendly outings",
        "Rainy-day plans with kids",
    )
    "sports" -> listOf(
        "Games this week",
        "Where to watch the game",
        "Pickup games nearby",
        "Best sports bars",
    )
    "outdoors" -> listOf(
        "Outdoor things this weekend",
        "Hikes within an hour",
        "Best parks for a picnic",
        "Beach days this weekend",
    )
    "comedy" -> listOf(
        "Comedy shows this week",
        "Open mic comedy nearby",
        "Stand-up tonight",
    )
    "nightlife" -> listOf(
        "Where to go out tonight",
        "Best rooftop bars",
        "Late-night spots nearby",
        "Where to dance this weekend",
    )
    "museums" -> listOf(
        "Museums to visit this weekend",
        "Free museum days nearby",
        "New exhibits this month",
    )
    else -> null
}
