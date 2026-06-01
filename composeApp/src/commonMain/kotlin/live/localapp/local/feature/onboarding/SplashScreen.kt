package live.localapp.local.feature.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import local.composeapp.generated.resources.Res
import local.composeapp.generated.resources.bo_tiny
import org.jetbrains.compose.resources.painterResource
import live.localapp.local.platform.isReducedMotionEnabled
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

/** Playback mode for the splash. Animated = full editorial timeline.
 *  Static = kill-switch fallback. See docs/splash-spec-v1.1.md §7. */
enum class SplashVariant { Animated, Static }

/**
 * Reel items: leading + trailing bookend "event" (Coral) with unique
 * event-type words between, colors rotating Plum→Sun→Sage→Sky. Order +
 * colors per docs/splash-spec-v1.1.md §4. The decelerating bell-curve
 * timing (computeReelIntervals) scales to any count, so the reel always
 * starts slow, speeds up through the middle, and slows to land on "event".
 */
private data class ReelItem(val word: String, val color: Color)

private val REEL_ITEMS: List<ReelItem> = listOf(
    ReelItem("event", AppColors.Coral),
    ReelItem("workshop", AppColors.Plum),
    ReelItem("story time", AppColors.Sun),
    ReelItem("trivia night", AppColors.Sage),
    ReelItem("open mic", AppColors.Sky),
    ReelItem("festival", AppColors.Plum),
    ReelItem("showcase", AppColors.Sun),
    ReelItem("class", AppColors.Sage),
    ReelItem("show", AppColors.Sky),
    ReelItem("game", AppColors.Plum),
    ReelItem("tour", AppColors.Sun),
    ReelItem("fair", AppColors.Sage),
    ReelItem("mixer", AppColors.Sky),
    ReelItem("market", AppColors.Plum),
    ReelItem("comedy", AppColors.Sun),
    ReelItem("place", AppColors.Sage),
    ReelItem("brunch", AppColors.Sky),
    ReelItem("pop-up", AppColors.Plum),
    ReelItem("exhibit", AppColors.Sun),
    ReelItem("matinee", AppColors.Sage),
    ReelItem("concert", AppColors.Sky),
    ReelItem("tasting", AppColors.Plum),
    ReelItem("reading", AppColors.Sun),
    ReelItem("screening", AppColors.Sage),
    ReelItem("event", AppColors.Coral),
)

// Timing constants — see §3, §4, §5, §8 of the spec.
private const val WORDMARK_INITIAL_DELAY_MS = 120L
private const val WORDMARK_LETTER_STAGGER_MS = 70L
private const val WORDMARK_LETTER_TRANSLATE_MS = 520
private const val WORDMARK_LETTER_FADE_MS = 320

// Sequential beats — each fires *after* the previous one is visually settled.
// Total to tap-line ≈ 4.5s on the full Animated variant. Under Reduce Motion
// the same beats fire at the same offsets but each motion collapses to a fade.
private const val POST_WORDMARK_BREATH_MS = 180L
private const val EYEBROW_FLOAT_MS = 500            // "Every single" translateY + fade
private const val EYEBROW_TRAVEL_DP = 16            // dp it travels from below
private const val POST_EYEBROW_BREATH_MS = 200L     // before reel mounts
private const val REEL_READ_PAUSE_MS = 350L         // user reads "Every single event"
private const val REEL_MOUNT_FADE_MS = 280          // soft fade-in for the first reel item
// Fast cadence: a brief wind-up (MAX→MIN over the first few items) then hold
// MIN all the way to the final word — no deceleration at the tail. The reel
// stays fast and "event" slams to a stop instead of easing in.
private const val REEL_MIN_STEP_MS = 80             // sustained step (flat cadence)
private const val REEL_MAX_STEP_MS = 130            // wind-up starting step
private const val REEL_WINDUP_STEPS = 3             // items to ramp up to speed
private const val REEL_LANDING_DELAY_MS = 80
// Landing on "event": carry slightly PAST center, then slam back down hard.
private const val REEL_OVERSHOOT_DP = 14
private const val REEL_OVERSHOOT_MS = 120           // fast glide past resting spot
private const val REEL_SLAM_MS = 110                // sharp return — the "hit"

private const val TAGLINE_DELAY_AFTER_REEL_MS = 250L
private const val TAGLINE_FADE_MS = 480
private const val TAGLINE_TRANSLATE_MS = 580
private const val ACCENT_DELAY_AFTER_TAGLINE_MS = 200L
private const val ACCENT_SCALE_MS = 600
private const val TAP_LINE_DELAY_AFTER_ACCENT_MS = 450L
private const val BO_SLIDE_UP_MS = 500
private const val BO_TRAVEL_DP = 30                  // dp Bo travels from below
private const val BO_TO_TEXT_DELAY_MS = 220L         // after Bo settles, before text streams
private const val TAP_LINE_CHAR_DELAY_MS = 55L       // per-character reveal interval
private const val TAP_LINE_TEXT = "Tap anywhere to continue..."

private const val WORDMARK_TRAVEL_DP = 53

private val WORDMARK_EASING = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1.15f)
private val REEL_CYCLE_EASING = CubicBezierEasing(0.4f, 0.05f, 0.55f, 0.95f)
private val REEL_LANDING_EASING = CubicBezierEasing(0.18f, 0.9f, 0.3f, 1.08f)
// Accelerate into the stop so the return reads as an impact, not a glide.
private val REEL_SLAM_EASING = CubicBezierEasing(0.6f, 0f, 1f, 1f)
private val TAGLINE_EASING = CubicBezierEasing(0.2f, 0.85f, 0.3f, 1.05f)
private val ACCENT_EASING = CubicBezierEasing(0.2f, 0.85f, 0.3f, 1.1f)

private const val WORDMARK = "local"

/**
 * Editorial splash — see docs/splash-spec-v1.1.md.
 *
 * @param onComplete fires when the user taps (any time after wordmark
 *  begins entering) or the auto-dismiss timer elapses.
 * @param appReady set to true by the host (App.kt) once the post-splash
 *  route has resolved. The tap line is gated on (a) the spec timeline
 *  AND (b) appReady — never reveals before either condition.
 * @param variant brand vs short vs kill-switch-off. See SplashVariant.
 */
@Composable
fun SplashScreen(
    onComplete: () -> Unit,
    appReady: Boolean = true,
    variant: SplashVariant = SplashVariant.Animated,
) {
    // Wrap the callback so a tap on a cached lambda resolves to the latest
    // state of onComplete — see feedback_compose_pointerinput_stale_capture.md.
    val onCompleteLatest by rememberUpdatedState(onComplete)
    val appReadyLatest by rememberUpdatedState(appReady)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Cream)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                // Ignore taps until the cold-start route has resolved. Without
                // this gate an eager tap (before the splash's "tap to continue"
                // line even appears) fires onComplete while postSplashRoute is
                // still null, and App.kt's `?: AUTH` fallback dumps a logged-in
                // user onto the sign-in screen. The router always resolves
                // within its 3s timeout, so this never locks anyone out.
                if (appReadyLatest) onCompleteLatest()
            }
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "Local — every single event, one single app. Tap to continue."
            },
        contentAlignment = Alignment.Center,
    ) {
        when (variant) {
            SplashVariant.Animated -> AnimatedSplash(
                onComplete = { onCompleteLatest() },
                appReadyProvider = { appReadyLatest },
            )
            SplashVariant.Static -> StaticSplash(
                onComplete = { onCompleteLatest() },
                appReadyProvider = { appReadyLatest },
            )
        }
    }
}

/* ─────────────────────────────────────────────────────────────────────── */
/*  Animated variant — full 3.4s editorial timeline                        */
/*                                                                          */
/*  Reduce Motion: still runs the timeline (same beats, same spacing) but   */
/*  collapses each motion to a fade — no per-letter stagger, reel jumps     */
/*  straight to the final "event", no translateY on tagline, no scaleX on   */
/*  accent. Users who turn motion off still get the editorial content,      */
/*  just without anything moving.                                           */
/* ─────────────────────────────────────────────────────────────────────── */

@Composable
private fun AnimatedSplash(
    onComplete: () -> Unit,
    appReadyProvider: () -> Boolean,
) {
    val haptic = LocalHapticFeedback.current
    val reduceMotion = remember { isReducedMotionEnabled() }

    // ── State per spec timeline ──
    var letterEntryStarted by remember { mutableStateOf(BooleanArray(WORDMARK.length)) }
    var wordmarkFaded by remember { mutableStateOf(false) }
    var eyebrowVisible by remember { mutableStateOf(false) }
    var reelMounted by remember { mutableStateOf(false) }
    var reelIndex by remember { mutableStateOf(0) }
    var reelLanded by remember { mutableStateOf(false) }
    var taglineVisible by remember { mutableStateOf(false) }
    var accentVisible by remember { mutableStateOf(false) }
    var tapLineRevealable by remember { mutableStateOf(false) }
    val tapLineVisible = tapLineRevealable && appReadyProvider()

    val intervals = remember { computeReelIntervals() }

    LaunchedEffect(Unit) {
        // Sequential timeline — each beat waits for the previous to settle.
        //
        // 1. Wordmark "local" — per-letter stagger (or fade under reduce-motion)
        delay(WORDMARK_INITIAL_DELAY_MS)
        if (reduceMotion) {
            wordmarkFaded = true
            delay(240L)
        } else {
            for (i in WORDMARK.indices) {
                val started = letterEntryStarted.copyOf()
                started[i] = true
                letterEntryStarted = started
                if (i < WORDMARK.length - 1) delay(WORDMARK_LETTER_STAGGER_MS)
            }
            // Last letter started its 520ms travel — wait for it to land
            // before kicking off the next beat.
            delay(WORDMARK_LETTER_TRANSLATE_MS.toLong())
        }

        // 2. Breath, then "Every single" floats up
        delay(POST_WORDMARK_BREATH_MS)
        eyebrowVisible = true
        if (!reduceMotion) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        delay(EYEBROW_FLOAT_MS.toLong())

        // 3. Breath, then reel mounts showing "event"
        delay(POST_EYEBROW_BREATH_MS)
        reelMounted = true
        delay(REEL_READ_PAUSE_MS)  // user reads "Every single event"

        // 4. Reel cycles fast through all items (or jumps to last under reduce-motion)
        if (reduceMotion) {
            reelIndex = reelItemCount() - 1
        } else {
            for (i in 1 until reelItemCount()) {
                delay(intervals[i - 1].toLong())
                reelIndex = i
                // Light tick per word. The hard "slam" haptic on the final
                // "event" is fired by Reel at the moment of impact, not here.
                if (i < reelItemCount() - 1) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
        }
        reelLanded = true

        // 5. Tagline, accent, tap line
        delay(TAGLINE_DELAY_AFTER_REEL_MS)
        taglineVisible = true
        delay(ACCENT_DELAY_AFTER_TAGLINE_MS)
        accentVisible = true
        delay(TAP_LINE_DELAY_AFTER_ACCENT_MS)
        tapLineRevealable = true
    }

    // No auto-dismiss — user must tap to continue. Tap anywhere on the
    // splash root dismisses (the outer Box.clickable handler in SplashScreen).
    // The Static (kill-switch) variant still auto-dismisses on app-ready
    // since it has no tap affordance to wait for.

    SplashCanvas(
        wordmarkContent = {
            if (reduceMotion) WordmarkFade(wordmarkFaded)
            else Wordmark(letterEntryStarted)
        },
        eyebrowVisible = eyebrowVisible,
        reelContent = if (reelMounted) {
            { Reel(reelIndex, reelLanded, reduceMotion) }
        } else null,
        taglineVisible = taglineVisible,
        accentVisible = accentVisible,
        tapLineContent = { BoTapLine(visible = tapLineVisible, reduceMotion = reduceMotion) },
    )
}

/* ─────────────────────────────────────────────────────────────────────── */
/*  Static variant — no animation; dismiss as soon as app-ready             */
/* ─────────────────────────────────────────────────────────────────────── */

@Composable
private fun StaticSplash(
    onComplete: () -> Unit,
    appReadyProvider: () -> Boolean,
) {
    SplashCanvas(
        wordmarkContent = { WordmarkFade(visible = true) },
        eyebrowVisible = false,
        reelContent = null,
        taglineVisible = false,
        accentVisible = false,
        tapLineContent = { /* no tap-line in static; dismisses on app-ready */ },
    )

    // Kill-switch path: leave as soon as the app finishes its cold-start.
    LaunchedEffect(appReadyProvider()) {
        if (appReadyProvider()) {
            delay(180L)  // a hair so the cream + wordmark register, not a flash
            onComplete()
        }
    }
}

/* ─────────────────────────────────────────────────────────────────────── */
/*  Canvas — shared layout for all three variants                          */
/* ─────────────────────────────────────────────────────────────────────── */

@Composable
private fun SplashCanvas(
    wordmarkContent: @Composable () -> Unit,
    eyebrowVisible: Boolean,
    reelContent: (@Composable () -> Unit)?,
    taglineVisible: Boolean,
    accentVisible: Boolean,
    tapLineContent: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 180.dp)
            .clearAndSetSemantics { },
    ) {
        wordmarkContent()

        Spacer(Modifier.height(72.dp))

        Eyebrow(visible = eyebrowVisible)

        Spacer(Modifier.height(4.dp))

        if (reelContent != null) {
            reelContent()
        } else {
            Spacer(Modifier.height(56.dp))
        }

        Spacer(Modifier.height(24.dp))

        Tagline(visible = taglineVisible)

        Spacer(Modifier.height(42.dp))

        AccentUnderline(visible = accentVisible)
    }

    // Tap line is bottom-anchored, hence a separate Box overlay.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 64.dp)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.BottomCenter,
    ) {
        tapLineContent()
    }
}

/* ─────────────────────────────────────────────────────────────────────── */
/*  Wordmark                                                                */
/* ─────────────────────────────────────────────────────────────────────── */

@Composable
private fun Wordmark(letterEntryStarted: BooleanArray) {
    val density = LocalDensity.current
    val travelPx = with(density) { WORDMARK_TRAVEL_DP.dp.toPx() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        WORDMARK.forEachIndexed { index, char ->
            val color = if (index == 0 || index == WORDMARK.length - 1) {
                AppColors.Coral
            } else {
                AppColors.Ink
            }
            WordmarkLetter(
                char = char.toString(),
                color = color,
                entered = letterEntryStarted.getOrNull(index) == true,
                travelPx = travelPx,
            )
        }
    }
}

@Composable
private fun WordmarkLetter(
    char: String,
    color: Color,
    entered: Boolean,
    travelPx: Float,
) {
    val translate = remember { Animatable(travelPx) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(entered) {
        if (entered) {
            launch {
                translate.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(WORDMARK_LETTER_TRANSLATE_MS, easing = WORDMARK_EASING),
                )
            }
            launch {
                alpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(WORDMARK_LETTER_FADE_MS, easing = LinearEasing),
                )
            }
        }
    }

    Text(
        text = char,
        style = TextStyle(
            fontFamily = AppFonts.Heading,
            fontWeight = FontWeight.Black,
            fontSize = 88.sp,
            letterSpacing = (-3.5).sp,
            color = color,
        ),
        modifier = Modifier.graphicsLayer {
            translationY = translate.value
            this.alpha = alpha.value
        },
    )
}

@Composable
private fun WordmarkFade(visible: Boolean) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        alpha.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(240, easing = LinearEasing),
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.graphicsLayer { this.alpha = alpha.value },
    ) {
        WORDMARK.forEachIndexed { index, char ->
            val color = if (index == 0 || index == WORDMARK.length - 1) {
                AppColors.Coral
            } else {
                AppColors.Ink
            }
            Text(
                text = char.toString(),
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Black,
                    fontSize = 88.sp,
                    letterSpacing = (-3.5).sp,
                    color = color,
                ),
            )
        }
    }
}

/* ─────────────────────────────────────────────────────────────────────── */
/*  Eyebrow / Reel / Tagline / Accent / Tap line                           */
/* ─────────────────────────────────────────────────────────────────────── */

@Composable
private fun Eyebrow(visible: Boolean) {
    val density = LocalDensity.current
    val travelPx = with(density) { EYEBROW_TRAVEL_DP.dp.toPx() }
    val translate = remember { Animatable(travelPx) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(visible) {
        if (visible) {
            launch {
                translate.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(EYEBROW_FLOAT_MS, easing = TAGLINE_EASING),
                )
            }
            launch {
                alpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween((EYEBROW_FLOAT_MS * 0.8f).toInt(), easing = LinearEasing),
                )
            }
        }
    }
    Text(
        text = "Every single",
        style = TextStyle(
            fontFamily = AppFonts.Heading,
            fontWeight = FontWeight.Medium,
            fontStyle = FontStyle.Italic,
            fontSize = 30.sp,
            color = AppColors.Ink2,
        ),
        modifier = Modifier.graphicsLayer {
            translationY = translate.value
            this.alpha = alpha.value
        },
    )
}

@Composable
private fun Reel(index: Int, landed: Boolean, reduceMotion: Boolean) {
    val items = REEL_ITEMS
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val rowHeightDp = 56.dp
    val rowHeightPx = with(density) { rowHeightDp.toPx() }

    val translate = remember { Animatable(0f) }
    val landingScale = remember { Animatable(1f) }
    // Soft fade-in the first time the reel mounts so the "event" word
    // doesn't pop into existence after the eyebrow lands.
    val mountAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        mountAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                if (reduceMotion) 200 else REEL_MOUNT_FADE_MS,
                easing = LinearEasing,
            ),
        )
    }

    LaunchedEffect(index) {
        val target = -index * rowHeightPx
        if (reduceMotion) {
            translate.snapTo(target)
            return@LaunchedEffect
        }
        val isLanding = index == items.lastIndex
        if (isLanding) {
            // Stay fast into the final word: carry slightly PAST the resting
            // spot, then slam back down and fire a hard haptic on impact.
            val overshootPx = with(density) { REEL_OVERSHOOT_DP.dp.toPx() }
            translate.animateTo(
                targetValue = target - overshootPx,
                animationSpec = tween(REEL_OVERSHOOT_MS, easing = LinearEasing),
            )
            translate.animateTo(
                targetValue = target,
                animationSpec = tween(REEL_SLAM_MS, easing = REEL_SLAM_EASING),
            )
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            // Impact squash — quick pop on the hit, then settle.
            landingScale.snapTo(1.12f)
            landingScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(260, easing = REEL_LANDING_EASING),
            )
        } else {
            val nextGap = if (index == 0) {
                REEL_MIN_STEP_MS
            } else {
                computeReelIntervals().getOrNull(index - 1) ?: REEL_MIN_STEP_MS
            }
            val duration = (nextGap * 0.9f).toInt().coerceIn(60, 200)
            translate.animateTo(
                targetValue = target,
                animationSpec = tween(duration, easing = REEL_CYCLE_EASING),
            )
        }
    }

    // Cream fade overlay alpha — needed during scroll to mask adjacent words,
    // but creates a "shadow" under the final word once landed. Fade it out
    // after the landing scale settles.
    val creamFadeAlpha = remember { Animatable(1f) }
    LaunchedEffect(landed) {
        if (landed) {
            delay((REEL_LANDING_DELAY_MS + 200).toLong())
            creamFadeAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(if (reduceMotion) 120 else 360, easing = LinearEasing),
            )
        }
    }

    Box(
        modifier = Modifier
            .width(320.dp)
            .height(rowHeightDp)
            .clipToBounds()
            .graphicsLayer { alpha = mountAlpha.value },
    ) {
        // Each item is its own absolute-positioned Box at the same z-level.
        // translationY = (whole-reel offset) + (i × row height) — so item 0
        // sits at y=0 initially, item 1 at y=56dp (below viewport, clipped),
        // and animating translate.value to -i×rowHeight slides item i into
        // view. No inner Column, so no parent-height constraint clamp.
        items.forEachIndexed { i, item ->
            val isLanded = i == items.lastIndex && landed
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        translationY = translate.value + i * rowHeightPx
                        if (isLanded) {
                            scaleX = landingScale.value
                            scaleY = landingScale.value
                            transformOrigin = TransformOrigin.Center
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.word,
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Bold,
                        fontStyle = FontStyle.Italic,
                        fontSize = 44.sp,
                        letterSpacing = (-1.1).sp,
                        color = item.color,
                    ),
                )
            }
        }

        // Cream fade overlay on top of the words, drawn last so it z-orders
        // above the items. Cheap alternative to offscreen-compositing a mask.
        // Fades out once the reel lands (no adjacent words to mask anymore).
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = creamFadeAlpha.value }
                .background(
                    // Bands kept to the outer ~10% of the row so they mask
                    // words sliding in/out at the edges without intruding on
                    // the centered word's ascenders/descenders (the wider
                    // 0.22/0.78 bands clipped glyph bottoms → "white shadow").
                    Brush.verticalGradient(
                        0f to AppColors.Cream,
                        0.1f to Color.Transparent,
                        0.9f to Color.Transparent,
                        1f to AppColors.Cream,
                    )
                )
        )
    }
}

@Composable
private fun Tagline(visible: Boolean) {
    val density = LocalDensity.current
    val translatePx = with(density) { 12.dp.toPx() }
    val translate = remember { Animatable(translatePx) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(visible) {
        if (visible) {
            launch {
                translate.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(TAGLINE_TRANSLATE_MS, easing = TAGLINE_EASING),
                )
            }
            launch {
                alpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(TAGLINE_FADE_MS, easing = LinearEasing),
                )
            }
        }
    }

    Text(
        text = "One single app.",
        style = TextStyle(
            fontFamily = AppFonts.Heading,
            fontWeight = FontWeight.Medium,
            fontStyle = FontStyle.Italic,
            fontSize = 26.sp,
            color = AppColors.Ink,
        ),
        modifier = Modifier.graphicsLayer {
            translationY = translate.value
            this.alpha = alpha.value
        },
    )
}

@Composable
private fun AccentUnderline(visible: Boolean) {
    val scale: Animatable<Float, AnimationVector1D> = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) {
            scale.animateTo(
                targetValue = 1f,
                animationSpec = tween(ACCENT_SCALE_MS, easing = ACCENT_EASING),
            )
        }
    }
    Box(
        modifier = Modifier
            .width(36.dp)
            .height(2.dp)
            .graphicsLayer {
                scaleX = scale.value
                transformOrigin = TransformOrigin.Center
            }
            .clip(RoundedCornerShape(1.dp))
            .background(AppColors.Coral),
    )
}

/**
 * Mini Bo slides up from below, then "Tap anywhere to continue…" streams
 * character-by-character with a soft per-letter haptic. Coral cursor block
 * fades in once Bo has settled (never before), stays at the end while
 * streaming, and remains as a subtle "still here" indicator after the
 * text completes.
 *
 * Under Reduce Motion: Bo fades in (no slideup), text reveals at 2× speed
 * with no haptics.
 */
@Composable
private fun BoTapLine(visible: Boolean, reduceMotion: Boolean) {
    val density = LocalDensity.current
    val travelPx = with(density) { BO_TRAVEL_DP.dp.toPx() }
    val haptic = LocalHapticFeedback.current

    val boTranslate = remember { Animatable(if (reduceMotion) 0f else travelPx) }
    val boAlpha = remember { Animatable(0f) }
    // Cursor has its own alpha (not boAlpha) so it only appears once Bo has
    // settled — otherwise the stationary cursor pops in at its final spot
    // while Bo is still sliding up, reading as "cursor before Bo."
    val cursorAlpha = remember { Animatable(0f) }
    var revealedChars by remember { mutableStateOf(0) }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        // Bo entrance
        coroutineScope {
            launch {
                boTranslate.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(BO_SLIDE_UP_MS, easing = TAGLINE_EASING),
                )
            }
            launch {
                boAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween((BO_SLIDE_UP_MS * 0.7f).toInt(), easing = LinearEasing),
                )
            }
        }
        // Small breath, then stream the text char-by-char with a haptic
        // tick per glyph (only for non-whitespace under reduce-motion off).
        delay(BO_TO_TEXT_DELAY_MS)
        // Cursor fades in now (Bo has settled), just before the text streams.
        cursorAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(if (reduceMotion) 120 else 200, easing = LinearEasing),
        )
        val charDelay = if (reduceMotion) TAP_LINE_CHAR_DELAY_MS / 2 else TAP_LINE_CHAR_DELAY_MS
        while (revealedChars < TAP_LINE_TEXT.length) {
            delay(charDelay)
            revealedChars++
            if (!reduceMotion) {
                val ch = TAP_LINE_TEXT[revealedChars - 1]
                if (!ch.isWhitespace()) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(Res.drawable.bo_tiny),
            contentDescription = null,
            modifier = Modifier
                .size(28.dp)
                .graphicsLayer {
                    translationY = boTranslate.value
                    alpha = boAlpha.value
                },
        )
        Spacer(Modifier.width(10.dp))
        // Streaming text + trailing coral cursor block (matches the chat
        // typewriter aesthetic in ChatScreen).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = TAP_LINE_TEXT.take(revealedChars),
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    letterSpacing = 0.2.sp,
                    color = AppColors.Ink3,
                ),
            )
            Spacer(Modifier.width(3.dp))
            // Cursor block — coral, ~3×14dp, fades in only after Bo settles
            // (driven by cursorAlpha, not boAlpha).
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(14.dp)
                    .background(AppColors.Coral)
                    .graphicsLayer { alpha = cursorAlpha.value },
            )
        }
    }
}

/* ─────────────────────────────────────────────────────────────────────── */
/*  Fast reel interval table                                                */
/* ─────────────────────────────────────────────────────────────────────── */

private fun reelItemCount(): Int = REEL_ITEMS.size

/**
 * Intervals between reel items: a brief wind-up that ramps MAX→MIN over the
 * first REEL_WINDUP_STEPS items, then holds MIN (top speed) for every step
 * through the last word. No tail deceleration — the reel stays fast and the
 * landing on "event" is handled separately as an overshoot-then-slam. Returns ms.
 */
private fun computeReelIntervals(): IntArray {
    val n = reelItemCount()
    val out = IntArray(n - 1)
    for (k in 1 until n) {
        val idx = k - 1
        val ms = if (idx < REEL_WINDUP_STEPS) {
            val t = idx.toFloat() / REEL_WINDUP_STEPS.toFloat()
            REEL_MAX_STEP_MS - (REEL_MAX_STEP_MS - REEL_MIN_STEP_MS) * t
        } else {
            REEL_MIN_STEP_MS.toFloat()
        }
        out[idx] = ms.toInt()
    }
    return out
}
