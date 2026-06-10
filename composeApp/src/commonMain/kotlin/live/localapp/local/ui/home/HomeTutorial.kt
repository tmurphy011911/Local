package live.localapp.local.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import live.localapp.local.ui.common.CircleShape
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import live.localapp.local.ui.icons.LocalIcon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

private data class HomeTutorialStep(
    val headline: String,
    val styledWord: String,
    val subtext: String,
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
        subtext = "Like a generic AI chatbot, except Bo actually knows your city. Bo works from Local's own events and venues \u2014 not the internet's stale top-10 lists.",
    ),
    HomeTutorialStep(
        headline = "Plans without",
        styledWord = "spreadsheets",
        subtext = "Stop making itinerary docs nobody opens. Tap to plan, tap to share. That's it.",
    ),
    HomeTutorialStep(
        headline = "Built for",
        styledWord = "you, by you",
        subtext = "The more you save, plan, and show up, the smarter Local gets. Almost suspicious.",
    ),
    HomeTutorialStep(
        headline = "Show up,",
        styledWord = "level up",
        subtext = "Every event, plan, and place racks up FOMO. Climb the ranks for real rewards \u2014 Ticketmaster gift cards on us.",
    ),
)

@Composable
private fun StepIcon(page: Int, trigger: Int) {
    val size = 140.dp
    val mod = Modifier.size(size)
    when (page) {
        0 -> LocalIcon.ForYou(size = size, trigger = trigger, modifier = mod)
        // Page 1 is "Meet Bo" — render the actual Bo character.
        1 -> live.localapp.local.ui.character.Bo(
            size = size,
            level = live.localapp.local.ui.character.cachedBoLevel(),
            trigger = trigger,
            modifier = mod,
        )
        2 -> LocalIcon.Events(size = size, trigger = trigger, modifier = mod)
        3 -> LocalIcon.Other(size = size, trigger = trigger, modifier = mod)
        4 -> LocalIcon.Sports(size = size, trigger = trigger, modifier = mod)
        else -> LocalIcon.ForYou(size = size, trigger = trigger, modifier = mod)
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
                    Box(
                        modifier = Modifier.size(140.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        StepIcon(page = page, trigger = triggers[page])
                    }

                    Spacer(Modifier.height(40.dp))

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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

            // CTA button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 32.dp)
                    .padding(bottom = 24.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isLastPage) AppColors.Coral else AppColors.Ink)
                    // Use a plain clickable for the CTA — `pressable` was
                    // dropping taps in this stack on iOS (gesture detector
                    // not committing on quick taps). Plain clickable is
                    // bullet-proof and the slight scale animation is
                    // cosmetic in this context.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (isLastPage) {
                            onComplete()
                        } else {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (isLastPage) "Get Started" else "Next",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        color = Color.White,
                    ),
                )
            }
        }

        // Skip button (top-right, not on last page)
        if (!isLastPage) {
            Text(
                "Skip",
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
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = AppColors.Ink3,
                ),
            )
        }
    }
}
