package live.localapp.local.feature.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import live.localapp.local.ui.common.RoundedCornerShape
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import live.localapp.local.domain.service.SplashGreetingService
import org.koin.mp.KoinPlatform
import local.composeapp.generated.resources.Res
import local.composeapp.generated.resources.bo_tiny
import org.jetbrains.compose.resources.painterResource
import live.localapp.local.platform.isReducedMotionEnabled
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

/** Playback mode for the splash. Animated = full editorial timeline (shown on
 *  the first-ever launch). Returning = Bo fades in, blinks, shrinks, then
 *  typewriters a personalized line to his right (every later fresh open). Static =
 *  kill-switch fallback. See docs/splash-spec-v1.1.md §7. */
enum class SplashVariant { Animated, Returning, Static }

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
    ReelItem("concert", AppColors.Plum),
    ReelItem("show", AppColors.Sun),
    ReelItem("exhibit", AppColors.Sage),
    ReelItem("comedy", AppColors.Sky),
    ReelItem("market", AppColors.Plum),
    ReelItem("workshop", AppColors.Sun),
    ReelItem("story time", AppColors.Sage),
    ReelItem("trivia night", AppColors.Sky),
    ReelItem("open mic", AppColors.Plum),
    ReelItem("festival", AppColors.Sun),
    ReelItem("showcase", AppColors.Sage),
    ReelItem("class", AppColors.Sky),
    ReelItem("game", AppColors.Plum),
    ReelItem("tour", AppColors.Sun),
    ReelItem("fair", AppColors.Sage),
    ReelItem("mixer", AppColors.Sky),
    ReelItem("place", AppColors.Plum),
    ReelItem("brunch", AppColors.Sun),
    ReelItem("pop-up", AppColors.Sage),
    ReelItem("matinee", AppColors.Sky),
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
// Two-gear cadence: the first REEL_SLOW_STEPS words hold a slow, readable
// beat (all the same speed), then every remaining word flips to very fast
// through the final word. No ramp between the gears — it's a hard shift.
private const val REEL_SLOW_STEP_MS = 300           // first slow words (deliberate)
private const val REEL_FAST_STEP_MS = 45            // top speed (very fast)
private const val REEL_SLOW_STEPS = 5               // slow words held up front
private const val REEL_RAMP_STEPS = 6               // steps to accelerate slow → top speed
private const val REEL_LANDING_DELAY_MS = 80
// Landing on "event": glide just slightly PAST center, then gently fall back
// into place. Soft single tick + a faint scale settle — no hard impact.
private const val REEL_OVERSHOOT_DP = 10
private const val REEL_OVERSHOOT_MS = 200           // ease softly past the resting spot
private const val REEL_SLAM_MS = 380                // gentle fall back into place

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
private val REEL_LANDING_EASING = CubicBezierEasing(0.18f, 0.9f, 0.3f, 1.0f)
// Glide past, then decelerate softly into the resting spot — a gentle fall, not a hit.
private val REEL_OVERSHOOT_EASING = CubicBezierEasing(0.25f, 0.6f, 0.35f, 1f)
private val REEL_SLAM_EASING = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
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
    specialLine: String? = null,
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
                    "Local — every event, all in one place. Tap to continue."
            },
        contentAlignment = Alignment.Center,
    ) {
        when (variant) {
            SplashVariant.Animated -> AnimatedSplash(
                onComplete = { onCompleteLatest() },
                appReadyProvider = { appReadyLatest },
            )
            SplashVariant.Returning -> ReturningSplash(
                onComplete = { onCompleteLatest() },
                appReadyProvider = { appReadyLatest },
                specialLine = specialLine,
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
/*  Returning variant — Bo fades in, blinks twice, shrinks, then            */
/*  typewriters a personalized line to his right. Shown on every fresh      */
/*  open after the first-ever launch.                                       */
/*                                                                          */
/*  Routing safety: this NEVER gates onComplete on the greeting fetch. The  */
/*  greeting is awaited only to pick what text to type; dismissal fires     */
/*  after the line finishes AND appReady is true — identical gating to the  */
/*  Static variant. A tap (outer Box) still dismisses once appReady.        */
/* ─────────────────────────────────────────────────────────────────────── */

private const val RET_BO_BIG_DP = 200
// Bo's resting size — small. Bo enters big + dead-centered (a graphicsLayer
// scale), blinks, then shrinks in place. When the greeting resolves, the
// Bo+text pair is centered as a GROUP: Bo glides a short horizontal distance
// left (to the group's left edge) while the line fades/types in to his right,
// vertically centered on the text block. Centering the group — rather than
// pinning Bo to screen center — gives the line the full content width, so it
// fits on one row for typical greetings and only rarely wraps.
private const val RET_BO_REST_DP = 26
private const val RET_BO_GAP_DP = 12
private const val RET_APPEAR_MS = 320
private const val RET_SHRINK_MS = 520
// Horizontal screen padding for the whole splash content.
private const val RET_H_PAD_DP = 18
// The horizontal "settle" — the Bo+text pair drifting left together from a
// Bo-centered start to the group-centered rest, in lockstep with the
// typewriter so it reads as a gentle drift, not a dart.
private const val RET_SETTLE_MS = 620
// The greeting ALWAYS renders on a single line (it just looks better). We start
// at the base size and auto-shrink toward the min until the whole line fits the
// width beside Bo — short lines stay big, long ones scale down. With the 64-char
// sanitize cap, the longest line lands well above the floor, so it never wraps.
private const val RET_FONT_BASE_SP = 15
// Floor for the auto-fit shrink. The greeting is hard-capped at ~42 chars
// upstream (SplashGreetingService.MAX_CHARS) so it fits at base font in almost
// every case; this floor only nudges down the rare wide-glyph line and never
// lets it become unreadable.
private const val RET_FONT_MIN_SP = 13
private const val RET_LINE_HEIGHT_FACTOR = 1.3f
// Budget for context-gathering + the Claude call. Sized to overlap the appear +
// blink + shrink (~1.6s) so the line is usually ready when the typewriter
// starts. On timeout the service returns a generic fallback. Bumped 2200→3200
// (2026-06-08) to cut the fallback rate — at 2200 over half of location turns
// timed out to static copy, so the personalized/nearby-town lines rarely showed.
// Worst case this adds ~1.6s of waiting at the shrunk state before typing; the
// common (fast-Claude) case is unchanged. Does NOT affect dismissal, which
// stays gated on appReady — routing safety holds.
private const val RET_GREETING_TIMEOUT_MS = 3200L
private const val RET_CHAR_DELAY_MS = 38L
// Cursor blink half-period (on for this long, then off for this long). The
// cursor blinks continuously the whole time the line is on screen.
private const val RET_CURSOR_BLINK_MS = 450L
// Once the line has finished AND the route has resolved, let the cursor blink
// this many full times before dismissing.
private const val RET_BLINKS_BEFORE_DISMISS = 3

private val RET_SHRINK_EASING = CubicBezierEasing(0.2f, 0.85f, 0.3f, 1.05f)

@Composable
private fun ReturningSplash(
    onComplete: () -> Unit,
    appReadyProvider: () -> Boolean,
    specialLine: String? = null,
) {
    val haptic = LocalHapticFeedback.current
    val reduceMotion = remember { isReducedMotionEnabled() }
    val greetingService = remember { KoinPlatform.getKoin().get<SplashGreetingService>() }

    // Bo enters big + dead-centered (graphicsLayer scale), then shrinks in place.
    // boSettle drives the short horizontal glide from screen center (0f) to the
    // group's left edge (1f) once the greeting resolves and the text appears.
    val boScale = remember {
        Animatable(if (reduceMotion) 1f else RET_BO_BIG_DP.toFloat() / RET_BO_REST_DP.toFloat())
    }
    val boAlpha = remember { Animatable(0f) }
    val eyeOpen = remember { Animatable(1f) }
    val textAlpha = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val boSettle = remember { Animatable(if (reduceMotion) 1f else 0f) }

    var line by remember { mutableStateOf("") }
    var revealedChars by remember { mutableStateOf(0) }
    var textComplete by remember { mutableStateOf(false) }

    // Cursor blinks continuously for the whole life of the splash — never a
    // static block. cursorOn toggles on a fixed half-period.
    var cursorOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            cursorOn = true
            delay(RET_CURSOR_BLINK_MS)
            cursorOn = false
            delay(RET_CURSOR_BLINK_MS)
        }
    }

    LaunchedEffect(Unit) {
        // Kick off the greeting immediately so it resolves during the entrance.
        // Bounded internally by RET_GREETING_TIMEOUT_MS — await never hangs. A
        // recency/streak special line (welcome-back, milestone) overrides it.
        val greetingDeferred = async {
            specialLine ?: greetingService.greeting(RET_GREETING_TIMEOUT_MS)
        }

        // Appear: a quick fade-in (no flip), then blink twice.
        boAlpha.animateTo(1f, tween(if (reduceMotion) 240 else RET_APPEAR_MS, easing = LinearEasing))
        if (!reduceMotion) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            delay(160)
            repeat(2) {
                // A tiny tick synced to each blink's eye-close.
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                eyeOpen.animateTo(0.05f, tween(90, easing = LinearEasing))
                eyeOpen.animateTo(1f, tween(120, easing = LinearEasing))
                delay(130)
            }
        } else {
            delay(160)
        }

        // Shrink — Bo scales back to 1 in place at screen center.
        boScale.animateTo(1f, tween(if (reduceMotion) 200 else RET_SHRINK_MS, easing = RET_SHRINK_EASING))

        // Resolve the line (already bounded) BEFORE the settle so its measured
        // width is known — that width fixes the group geometry, so the glide
        // lands exactly and typing never reflows anything.
        line = greetingDeferred.await()

        // Drift the whole Bo+text group left (boSettle) and fade the text in,
        // both running CONCURRENTLY with the typewriter below — so Bo visibly
        // glides left in lockstep with the line appearing, never a dart.
        if (reduceMotion) {
            boSettle.snapTo(1f)
            textAlpha.snapTo(1f)
        } else {
            launch { boSettle.animateTo(1f, tween(RET_SETTLE_MS, easing = FastOutSlowInEasing)) }
            launch { textAlpha.animateTo(1f, tween(280, easing = LinearEasing)) }
        }

        // Typewriter the line out (concurrent with the drift above).
        val charDelay = if (reduceMotion) RET_CHAR_DELAY_MS / 2 else RET_CHAR_DELAY_MS
        while (revealedChars < line.length) {
            delay(charDelay)
            revealedChars++
            if (!reduceMotion && !line[revealedChars - 1].isWhitespace()) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
        textComplete = true
    }

    // Dismiss only once the line has finished AND the cold-start route has
    // resolved — same appReady gate the Static variant uses, so logged-in /
    // logged-out next-page logic is never raced.
    val ready = appReadyProvider()
    LaunchedEffect(textComplete, ready) {
        if (textComplete && ready) {
            // Let the cursor blink a few full times before leaving.
            delay(RET_CURSOR_BLINK_MS * 2 * RET_BLINKS_BEFORE_DISMISS)
            onComplete()
        }
    }

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val baseGreetingStyle = TextStyle(
        fontFamily = AppFonts.Body,
        fontWeight = FontWeight.SemiBold,
        fontSize = RET_FONT_BASE_SP.sp,
        lineHeight = (RET_FONT_BASE_SP * RET_LINE_HEIGHT_FACTOR).sp,
        letterSpacing = 0.1.sp,
        color = AppColors.Ink3,
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = RET_H_PAD_DP.dp),
    ) {
        // Bo + the greeting are centered on screen as a single GROUP. We measure
        // the resolved line to know the group's exact size up front; the group's
        // left edge then anchors Bo, and Bo glides there from screen center via
        // boSettle. Because the geometry is fixed by the FULL line, the per-glyph
        // typewriter never reflows anything.
        val boRestPx = with(density) { RET_BO_REST_DP.dp.toPx() }
        val gapPx = with(density) { RET_BO_GAP_DP.dp.toPx() }
        val containerPx = with(density) { maxWidth.toPx() }
        val maxTextWidthPx = (containerPx - boRestPx - gapPx).coerceAtLeast(0f)

        // Auto-fit to ONE line: measure the line's natural single-line width at
        // the base font, then scale the font down (to the floor) just enough to
        // fit beside Bo. Short lines stay at base size; long lines shrink.
        val measured = if (line.isNotEmpty()) {
            val natural = textMeasurer.measure(
                text = AnnotatedString("$line▏"),
                style = baseGreetingStyle,
                softWrap = false,
                maxLines = 1,
            )
            natural.size.width.toFloat()
        } else {
            null
        }
        val naturalWidthPx = measured ?: 0f
        val minScale = RET_FONT_MIN_SP.toFloat() / RET_FONT_BASE_SP.toFloat()
        val fontScale = if (naturalWidthPx > 0f) {
            (maxTextWidthPx / naturalWidthPx).coerceIn(minScale, 1f)
        } else {
            1f
        }
        val fittedFontSp = RET_FONT_BASE_SP * fontScale
        val greetingStyle = baseGreetingStyle.copy(
            fontSize = fittedFontSp.sp,
            lineHeight = (fittedFontSp * RET_LINE_HEIGHT_FACTOR).sp,
        )
        // The fitted line's width never exceeds the available space → one line.
        val textWidthPx = (naturalWidthPx * fontScale).coerceAtMost(maxTextWidthPx)
        val groupWidthPx = boRestPx + gapPx + textWidthPx
        val groupLeftPx = ((containerPx - groupWidthPx) / 2f).coerceAtLeast(0f)

        // Bo's grouped center vs. screen center → the glide distance (negative).
        val boShiftPx = (groupLeftPx + boRestPx / 2f) - containerPx / 2f
        val textLeftDp = with(density) { (groupLeftPx + boRestPx + gapPx).toDp() }
        val textWidthDp = with(density) { textWidthPx.toDp() }

        val typed = line.take(revealedChars)
        if (measured != null) {
            Text(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = textLeftDp)
                    .width(textWidthDp)
                    // Base offset is the grouped (settle=1) position; while
                    // settling, ride the SAME leftward shift as Bo so the pair
                    // moves as one unit (at settle=0 the text sits just right of
                    // a screen-centered Bo). No reflow — pure layer translation.
                    .graphicsLayer { translationX = boShiftPx * (boSettle.value - 1f) },
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = AppColors.Ink3.copy(alpha = textAlpha.value))) {
                        append(typed)
                    }
                    // Cursor is always in the string (width never jitters) but
                    // only painted coral on the "on" half of the blink.
                    withStyle(
                        SpanStyle(
                            color = if (cursorOn) {
                                AppColors.Coral.copy(alpha = textAlpha.value)
                            } else {
                                Color.Transparent
                            },
                        ),
                    ) { append("▏") }
                },
                textAlign = TextAlign.Start,
                style = greetingStyle,
                maxLines = 1,
                softWrap = false,
            )
        }

        // Bo: enters big + screen-centered (scale + fade), then glides left by
        // boShiftPx * boSettle to land at the group's left edge.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(RET_BO_REST_DP.dp)
                .graphicsLayer {
                    scaleX = boScale.value
                    scaleY = boScale.value
                    alpha = boAlpha.value
                    translationX = boShiftPx * boSettle.value
                },
        ) {
            BoCharacter(
                modifier = Modifier.fillMaxSize(),
                expression = BoExpression.Default,
                eyeOpen = eyeOpen.value,
                showShadow = false,
                level = live.localapp.local.ui.character.cachedBoLevel(),
            )
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

        Spacer(Modifier.height(8.dp))

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
        text = "Every",
        style = TextStyle(
            fontFamily = AppFonts.Body,
            fontWeight = FontWeight.ExtraBold,
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
            // Carry just slightly PAST the resting spot, decelerating as it goes,
            // then gently fall back into place — a soft settle, not a slam.
            val overshootPx = with(density) { REEL_OVERSHOOT_DP.dp.toPx() }
            translate.animateTo(
                targetValue = target - overshootPx,
                animationSpec = tween(REEL_OVERSHOOT_MS, easing = REEL_OVERSHOOT_EASING),
            )
            translate.animateTo(
                targetValue = target,
                animationSpec = tween(REEL_SLAM_MS, easing = REEL_SLAM_EASING),
            )
            // One soft tick as it settles — gentle, not a heavy thud.
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            // Faint scale settle as it falls into place.
            launch {
                landingScale.snapTo(1.05f)
                landingScale.animateTo(1f, animationSpec = tween(REEL_SLAM_MS, easing = REEL_LANDING_EASING))
            }
        } else {
            val nextGap = if (index == 0) {
                REEL_FAST_STEP_MS
            } else {
                computeReelIntervals().getOrNull(index - 1) ?: REEL_FAST_STEP_MS
            }
            // Slide each word over ~90% of its gap (capped) so slow words read
            // deliberately and fast words blur continuously into the next.
            val duration = (nextGap * 0.9f).toInt().coerceIn(60, 240)
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
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Black,
                        fontSize = 44.sp,
                        letterSpacing = (-0.5).sp,
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
        text = "all in one place",
        style = TextStyle(
            fontFamily = AppFonts.Body,
            fontWeight = FontWeight.Bold,
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
 * Intervals between reel items: the first REEL_SLOW_STEPS words hold
 * REEL_SLOW_STEP_MS (a slow, even, readable beat), then the cadence ramps
 * smoothly down over REEL_RAMP_STEPS words from slow → REEL_FAST_STEP_MS (top
 * speed) and holds top speed through the last word. The landing on "event" is
 * handled separately as an overshoot-then-slam. Returns ms.
 */
private fun computeReelIntervals(): IntArray {
    val n = reelItemCount()
    val out = IntArray(n - 1)
    for (k in 1 until n) {
        val idx = k - 1
        out[idx] = when {
            idx < REEL_SLOW_STEPS -> REEL_SLOW_STEP_MS
            idx < REEL_SLOW_STEPS + REEL_RAMP_STEPS -> {
                // Accelerate: interpolate slow → fast across the ramp window.
                val t = (idx - REEL_SLOW_STEPS + 1).toFloat() / REEL_RAMP_STEPS.toFloat()
                (REEL_SLOW_STEP_MS + (REEL_FAST_STEP_MS - REEL_SLOW_STEP_MS) * t).toInt()
            }
            else -> REEL_FAST_STEP_MS
        }
    }
    return out
}
