package live.localapp.local.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import live.localapp.local.platform.isReducedMotionEnabled
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

private val ALL_WORDS = listOf(
    "concert", "class", "game", "trivia night", "story time",
    "open mic", "screening", "festival", "pop-up", "show", "place",
    "event",
)

// 12 words landing on "event". Each tick: AnimatedContent transition
// (CYCLE_TRANSITION_MS or LANDING_TRANSITION_MS) then HOLD_MS stationary
// before the next swap. Final 3 words (show / place / event) use a slower
// transition so the punchline lands with weight.
private const val CYCLE_TRANSITION_MS = 220
private const val LANDING_TRANSITION_MS = 280
private const val HOLD_MS = 100L
private const val TICK_INTERVAL_MS = CYCLE_TRANSITION_MS + HOLD_MS         // 320ms
private const val SLOW_TICK_INTERVAL_MS = LANDING_TRANSITION_MS + HOLD_MS  // 380ms
private const val SLOW_TICK_FROM_INDEX = 9  // "show", "place", "event"

// Tagline style. 22sp keeps "Every single trivia night" within screen width
// even with the row pinned to the "event-centered" position (longer words
// extend rightward from a fixed anchor instead of recentering the line).
private val taglineFontSize = 22.sp

@Composable
fun SplashScreen(onComplete: () -> Unit) {
    var logoVisible by remember { mutableStateOf(false) }
    var taglineVisible by remember { mutableStateOf(false) }
    var kickerVisible by remember { mutableStateOf(false) }
    var tapHintVisible by remember { mutableStateOf(false) }
    var tapEnabled by remember { mutableStateOf(false) }
    var tickIndex by remember { mutableStateOf(0) }
    var logoSettling by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    // Platform reduce-motion. Word cycling is information and still runs in
    // both states; only decorative beats (logo settle, landing pulse, tap-hint
    // breathe) are suppressed.
    val reduceMotion = remember { isReducedMotionEnabled() }

    // Logo settle dip on the first ticker tick.
    val logoScale by animateFloatAsState(
        targetValue = if (logoSettling && !reduceMotion) 0.97f else 1.0f,
        animationSpec = tween(60, easing = FastOutSlowInEasing),
        finishedListener = { if (logoSettling) logoSettling = false },
        label = "logoSettle",
    )

    // Tap-hint alpha breathe. Static 0.70 under reduce-motion.
    val tapHintBreathe = rememberInfiniteTransition(label = "tapHintBreathe")
    val tapHintBreathAlpha by tapHintBreathe.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.70f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tapHintAlpha",
    )
    val tapHintAlpha = if (reduceMotion) 0.70f else tapHintBreathAlpha

    // Landing pulse on "Event". Delayed 300ms after the final tickIndex change
    // so the AnimatedContent slide-in completes before the scale beat — the
    // pulse should punctuate a settled word, not fight the entrance.
    var landingPulseActive by remember { mutableStateOf(false) }
    LaunchedEffect(tickIndex) {
        if (tickIndex == ALL_WORDS.lastIndex && !reduceMotion) {
            delay(300)
            landingPulseActive = true
        }
    }
    val landingScale by animateFloatAsState(
        targetValue = if (landingPulseActive) 1.08f else 1.0f,
        animationSpec = tween(175, easing = FastOutSlowInEasing),
        finishedListener = { if (landingPulseActive) landingPulseActive = false },
        label = "landingPulse",
    )

    val taglineStyle = taglineTextStyle()

    LaunchedEffect(Unit) {
        delay(200)
        logoVisible = true
        delay(300)
        taglineVisible = true
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

        for (i in 1 until ALL_WORDS.size) {
            if (i == 1) logoSettling = true
            val interval = if (i >= SLOW_TICK_FROM_INDEX) SLOW_TICK_INTERVAL_MS else TICK_INTERVAL_MS
            delay(interval)
            tickIndex = i
            val type = if (i == ALL_WORDS.lastIndex) {
                HapticFeedbackType.LongPress
            } else {
                HapticFeedbackType.TextHandleMove
            }
            haptic.performHapticFeedback(type)
        }

        // After the final tick, hold long enough for the slide-in (280ms) and
        // the landing pulse (300ms delay + 175ms peak) to play before the
        // kicker floats up beneath.
        delay(500)
        kickerVisible = true
        delay(350)
        tapHintVisible = true
        delay(150)
        tapEnabled = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Snow)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (tapEnabled) onComplete()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            // Brand mark — coral squircle with white serif "L".
            AnimatedVisibility(
                visible = logoVisible,
                enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 4 },
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .graphicsLayer {
                            scaleX = logoScale
                            scaleY = logoScale
                        }
                        .clip(RoundedCornerShape(28.dp))
                        .background(AppColors.Coral),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "L",
                        style = TextStyle(
                            fontFamily = AppFonts.Heading,
                            fontWeight = FontWeight.Black,
                            fontSize = 72.sp,
                            color = AppColors.White,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            AnimatedVisibility(
                visible = taglineVisible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 3 },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Pin the row's left edge where it would naturally sit
                    // when the cycling word is "event" — so "Every single"
                    // stops migrating left/right as word widths change. The
                    // word slides in from below at a fixed X, extending right
                    // as needed for longer words.
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val available = maxWidth
                        val measurer = rememberTextMeasurer()
                        val density = LocalDensity.current
                        val (everyWidthDp: Dp, eventWidthDp: Dp) = remember(taglineStyle, density) {
                            val everyPx = measurer.measure("Every single ", taglineStyle).size.width
                            val eventPx = measurer.measure("event", taglineStyle).size.width
                            with(density) { everyPx.toDp() to eventPx.toDp() }
                        }
                        val anchoredStart = ((available - everyWidthDp - eventWidthDp) / 2)
                            .coerceAtLeast(0.dp)

                        Row(
                            modifier = Modifier.padding(start = anchoredStart),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            Text(
                                "Every single ",
                                style = taglineStyle,
                                maxLines = 1,
                                softWrap = false,
                            )
                            AnimatedContent(
                                targetState = tickIndex,
                                transitionSpec = {
                                    val isLanding = targetState >= SLOW_TICK_FROM_INDEX
                                    val durMs = if (isLanding) LANDING_TRANSITION_MS else CYCLE_TRANSITION_MS
                                    val fadeMs = (durMs * 4) / 5
                                    (slideInVertically(tween(durMs)) { it / 2 } + fadeIn(tween(fadeMs)))
                                        .togetherWith(
                                            slideOutVertically(tween(durMs)) { -it / 2 } + fadeOut(tween(fadeMs))
                                        )
                                },
                                label = "wordCycle",
                            ) { idx ->
                                val isLanded = idx == ALL_WORDS.lastIndex
                                val scale = if (isLanded) landingScale else 1.0f
                                Text(
                                    ALL_WORDS[idx],
                                    style = taglineStyle,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    },
                                )
                            }
                        }
                    }

                    // "One single app." floats up directly beneath the row
                    // once the word has landed on "Event".
                    AnimatedVisibility(
                        visible = kickerVisible,
                        enter = fadeIn(tween(450)) + slideInVertically(tween(500)) { it / 2 },
                    ) {
                        Text(
                            "One single app.",
                            style = taglineStyle,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            Spacer(Modifier.height(40.dp))

            AnimatedVisibility(
                visible = tapHintVisible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(450)) { it / 2 },
            ) {
                Text(
                    "Tap anywhere to continue",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = AppColors.Ink3.copy(alpha = tapHintAlpha),
                    ),
                )
            }
        }
    }
}

@Composable
private fun taglineTextStyle(): TextStyle = TextStyle(
    fontFamily = AppFonts.Heading,
    fontWeight = FontWeight.Black,
    fontStyle = FontStyle.Italic,
    fontSize = taglineFontSize,
    color = AppColors.Coral,
)
