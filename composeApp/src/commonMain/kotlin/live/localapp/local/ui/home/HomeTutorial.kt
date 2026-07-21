package live.localapp.local.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import live.localapp.local.feature.onboarding.OnboardingButton
import live.localapp.local.ui.common.CircleShape
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import live.localapp.local.ui.character.Bo
import live.localapp.local.ui.character.BoMotion
import live.localapp.local.ui.icons.LocalIcon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

private data class HomeTutorialStep(
    val headline: String,
    val styledWord: String,
    val subtext: String,
    // When true, headline + styledWord render on a single line ("Meet Bo")
    // instead of the default two stacked lines. Only used for short pairs.
    val inlineWord: Boolean = false,
)

private fun buildSteps(cityForCopy: String): List<HomeTutorialStep> = listOf(
    HomeTutorialStep(
        headline = "Welcome to",
        styledWord = "Local",
        subtext = "No more \"fun things to do in $cityForCopy\" Google searches. Local has every event in your city \u2014 big, small, weird. Finally, all in one place.",
    ),
    HomeTutorialStep(
        headline = "Meet",
        styledWord = "Bo",
        subtext = "Your local sidekick (and new best friend) who actually knows your city. Bo reads from Local's own top events and venues \u2014 not the internet's stale top 10 lists.",
        inlineWord = true,
    ),
    HomeTutorialStep(
        headline = "Plans without",
        styledWord = "spreadsheets",
        subtext = "Stop making itinerary docs nobody opens. Tap to plan, tap to share. That's it.",
    ),
    HomeTutorialStep(
        headline = "Show up,",
        styledWord = "level up",
        subtext = "Every event, plan, and place racks up FOMO. Climb the ranks for real rewards \u2014 Ticketmaster gift cards on us.",
    ),
)

@Composable
private fun StepIcon(page: Int, trigger: Int, size: Dp) {
    val mod = Modifier.size(size)
    when (page) {
        0 -> LocalIcon.ForYou(size = size, trigger = trigger, modifier = mod)
        // Page 1 is "Meet Bo" — render the actual Bo character. Pass level = null
        // so Bo draws the plain mascot (no level prop / flower pot) on this intro
        // screen only; size is bumped up by the caller.
        1 -> live.localapp.local.ui.character.Bo(
            size = size,
            level = null,
            trigger = trigger,
            modifier = mod,
        )
        2 -> LocalIcon.Events(size = size, trigger = trigger, modifier = mod)
        // The last page ("Show up, level up") is handled separately via
        // BoLevelUpAnimation and never reaches StepIcon.
        else -> LocalIcon.ForYou(size = size, trigger = trigger, modifier = mod)
    }
}

/**
 * "Level up" animation for the onboarding tutorial: Bo pops through every loyalty
 * level (0..9, New in Town → Mayor), gaining a new prop each step, behind a soft
 * glow + a ring that pulses on each level-up, with a 10-segment progress track
 * below and a haptic tap on every transition. Holds on the final (Mayor) level,
 * then loops. [restartKey] restarts the cycle whenever the page is (re)entered.
 */
@Composable
private fun BoLevelUpAnimation(boSize: Dp, restartKey: Int, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    var level by remember { mutableStateOf(0) }
    var popTrigger by remember { mutableStateOf(0) }
    val maxLevel = 9          // 0..9 → 10 levels (New in Town … Mayor)
    val totalLevels = maxLevel + 1

    LaunchedEffect(restartKey) {
        // Run once through every level, then hold on the finale (Mayor) — no loop.
        for (i in 0..maxLevel) {
            level = i
            popTrigger++
            // Haptic on each level-up transition.
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            if (i < maxLevel) delay(720L)
        }
    }

    // Ring expands + fades on each level-up pop.
    val ringPulse = remember { Animatable(0f) }
    LaunchedEffect(popTrigger) {
        if (popTrigger > 0) {
            ringPulse.snapTo(0f)
            ringPulse.animateTo(1f, tween(durationMillis = 620, easing = LinearEasing))
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            modifier = Modifier.size(boSize * 1.4f),
            contentAlignment = Alignment.Center,
        ) {
            // Soft radial glow (coral → sun → transparent), always on.
            Box(
                modifier = Modifier
                    .size(boSize * 1.4f)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                AppColors.Coral.copy(alpha = 0.22f),
                                AppColors.Sun.copy(alpha = 0.08f),
                                Color.Transparent,
                            ),
                        ),
                        CircleShape,
                    ),
            )
            // Pulsing ring on each level-up.
            Box(
                modifier = Modifier
                    .size(boSize)
                    .graphicsLayer {
                        val s = 0.6f + ringPulse.value * 1.1f
                        scaleX = s
                        scaleY = s
                        alpha = (1f - ringPulse.value) * 0.7f
                    }
                    .border(3.dp, AppColors.Coral, CircleShape),
            )
            Bo(
                size = boSize,
                level = level,
                motion = BoMotion.PopIn,
                trigger = popTrigger,
            )
        }

        Spacer(Modifier.height(20.dp))

        // 10-segment progress track: filled levels coral, current level taller + sun.
        Row(
            modifier = Modifier.width(188.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(totalLevels) { i ->
                val color = when {
                    i < level -> AppColors.Coral
                    i == level -> AppColors.Sun
                    else -> AppColors.Pebble
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(if (i == level) 9.dp else 6.dp)
                        .clip(CircleShape)
                        .background(color),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeTutorial(cityName: String = "", onComplete: () -> Unit) {
    val cityForCopy = cityName.substringBefore(",").trim().ifBlank { "your city" }
    val steps = remember(cityForCopy) { buildSteps(cityForCopy) }
    val pagerState = rememberPagerState(pageCount = { steps.size })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == steps.lastIndex

    val triggers = remember { mutableStateListOf<Int>().apply { repeat(steps.size) { add(0) } } }
    LaunchedEffect(pagerState.currentPage) {
        triggers[pagerState.currentPage] = triggers[pagerState.currentPage] + 1
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Snow),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                val step = steps[page]
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // Bo ("Meet Bo", page 1) renders larger than the other icons.
                    val iconSize: Dp = if (page == 1) 190.dp else 140.dp
                    if (page == steps.lastIndex) {
                        // "Show up, level up" (the final page) — full level-up
                        // animation (Bo + glow ring + progress track) in place of
                        // the static trophy. Tracks lastIndex so it stays correct
                        // if the step list changes.
                        BoLevelUpAnimation(boSize = 150.dp, restartKey = triggers[page])
                    } else {
                        Box(
                            modifier = Modifier.size(iconSize),
                            contentAlignment = Alignment.Center,
                        ) {
                            StepIcon(page = page, trigger = triggers[page], size = iconSize)
                        }
                    }

                    Spacer(Modifier.height(40.dp))

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (step.inlineWord) {
                            // Short pairs ("Meet Bo") render on one line, with the
                            // styled word kept italic + coral via a span.
                            Text(
                                buildAnnotatedString {
                                    append(step.headline)
                                    append(" ")
                                    withStyle(
                                        SpanStyle(
                                            fontStyle = FontStyle.Italic,
                                            color = AppColors.Coral,
                                        ),
                                    ) {
                                        append(step.styledWord)
                                    }
                                },
                                style = TextStyle(
                                    fontFamily = AppFonts.Heading,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 32.sp,
                                    color = AppColors.Ink,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 38.sp,
                                ),
                            )
                        } else {
                            Text(
                                step.headline,
                                style = TextStyle(
                                    fontFamily = AppFonts.Heading,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 32.sp,
                                    color = AppColors.Ink,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 38.sp,
                                ),
                            )
                            Text(
                                step.styledWord,
                                style = TextStyle(
                                    fontFamily = AppFonts.Heading,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 32.sp,
                                    fontStyle = FontStyle.Italic,
                                    color = AppColors.Coral,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 38.sp,
                                ),
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        step.subtext,
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = AppColors.Ink3,
                            textAlign = TextAlign.Center,
                            lineHeight = 22.sp,
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }

            // Page dots
            Row(
                modifier = Modifier.padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(steps.size) { i ->
                    val selected = pagerState.currentPage == i
                    val width by animateFloatAsState(
                        targetValue = if (selected) 24f else 8f,
                        animationSpec = tween(200),
                    )
                    Box(
                        modifier = Modifier
                            .size(width.dp, 8.dp)
                            .clip(CircleShape)
                            .background(if (selected) AppColors.Coral else AppColors.Pebble),
                    )
                }
            }

            // CTA button — shared Duolingo-style 3D button.
            // OnboardingButton uses a plain clickable (not `pressable`), which
            // avoids the iOS tap-dropping bug this stack hit previously.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 32.dp)
                    .padding(bottom = 24.dp),
            ) {
                OnboardingButton(
                    text = if (isLastPage) "Get Started" else "Next",
                    color = if (isLastPage) AppColors.Coral else AppColors.Ink,
                    shadowColor = if (isLastPage) AppColors.CoralDk else Color(0xFF0D0B12),
                    onClick = {
                        if (isLastPage) {
                            onComplete()
                        } else {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                )
            }
        }

        // Skip button (top-right, not on last page)
        if (!isLastPage) {
            Text(
                "Skip →",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 24.dp)
                    // Plain clickable, not `pressable` — same iOS tap-dropping
                    // bug that forced the Next CTA off `pressable` (see comment
                    // above) also silently swallowed Skip taps, leaving users
                    // stranded on page 1 with no way out but swiping through all
                    // five pages. Plain clickable commits the tap reliably.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onComplete,
                    ),
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    color = AppColors.Ink3,
                ),
            )
        }
    }
}
