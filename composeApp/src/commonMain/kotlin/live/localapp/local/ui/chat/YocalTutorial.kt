package live.localapp.local.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
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
import live.localapp.local.ui.common.pressable
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

private data class TutorialStep(
    val headline: String,
    val styledWord: String,
    val subtext: String,
    val gifPlaceholderEmoji: String, // placeholder until real GIFs are added
)

private val tutorialSteps = listOf(
    TutorialStep(
        headline = "Meet",
        styledWord = "Bo",
        subtext = "Your local concierge. A friendly helper who turns your ideas into shareable plans in seconds.",
        gifPlaceholderEmoji = "0",
    ),
    TutorialStep(
        headline = "Tell Bo what you're",
        styledWord = "planning",
        subtext = "Date night? Visitors in town? Weekend with the crew? Just say the word.",
        gifPlaceholderEmoji = "1",
    ),
    TutorialStep(
        headline = "Get a personalized",
        styledWord = "plan",
        subtext = "Events, restaurants, and experiences curated around what you love \u2014 all in one card.",
        gifPlaceholderEmoji = "2",
    ),
    TutorialStep(
        headline = "Share with your",
        styledWord = "group",
        subtext = "One tap to share your plan with everyone. They can view, vote, and confirm.",
        gifPlaceholderEmoji = "3",
    ),
    TutorialStep(
        headline = "Book and go",
        styledWord = "instantly",
        subtext = "Reserve tables, buy tickets, and get directions \u2014 right from the plan.",
        gifPlaceholderEmoji = "4",
    ),
)

@Composable
private fun YocalStepIcon(page: Int, trigger: Int) {
    val size = 140.dp
    val mod = Modifier.size(size)
    when (page) {
        0 -> live.localapp.local.ui.character.Bo(
            size = size,
            expression = live.localapp.local.ui.character.BoExpression.Default,
            trigger = trigger,
            modifier = mod,
        )
        1 -> LocalIcon.ForYou(size = size, trigger = trigger, modifier = mod)
        2 -> LocalIcon.Events(size = size, trigger = trigger, modifier = mod)
        3 -> LocalIcon.Other(size = size, trigger = trigger, modifier = mod)
        4 -> LocalIcon.Tours(size = size, trigger = trigger, modifier = mod)
        else -> LocalIcon.ForYou(size = size, trigger = trigger, modifier = mod)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YocalTutorial(onComplete: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { tutorialSteps.size })
    val isLastPage = pagerState.currentPage == tutorialSteps.lastIndex

    val triggers = remember { mutableStateListOf<Int>().apply { repeat(tutorialSteps.size) { add(0) } } }
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

            // Pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                val step = tutorialSteps[page]
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
                        YocalStepIcon(page = page, trigger = triggers[page])
                    }

                    Spacer(Modifier.height(40.dp))

                    Text(
                        step.headline,
                        style = TextStyle(
                            fontFamily = AppFonts.Heading,
                            fontWeight = FontWeight.Black,
                            fontSize = 32.sp,
                            color = AppColors.Ink,
                            lineHeight = 38.sp,
                        ),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        step.styledWord,
                        style = TextStyle(
                            fontFamily = AppFonts.Heading,
                            fontWeight = FontWeight.Black,
                            fontSize = 32.sp,
                            fontStyle = FontStyle.Italic,
                            color = AppColors.Coral,
                            lineHeight = 38.sp,
                        ),
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(16.dp))

                    Text(
                        step.subtext,
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = AppColors.Ink2,
                            lineHeight = 22.sp,
                        ),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // Page dots
            Row(
                modifier = Modifier.padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                tutorialSteps.forEachIndexed { index, _ ->
                    val isActive = pagerState.currentPage == index
                    val dotSize by animateFloatAsState(
                        targetValue = if (isActive) 10f else 6f,
                        animationSpec = tween(200),
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(dotSize.dp)
                            .background(
                                if (isActive) AppColors.Coral else AppColors.Pebble,
                                CircleShape,
                            ),
                    )
                }
            }

            // CTA
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (isLastPage)
                            Brush.linearGradient(listOf(AppColors.Coral, Color(0xFFE85D5D)))
                        else
                            Brush.linearGradient(listOf(AppColors.Ink, AppColors.Ink)),
                    )
                    .pressable(onClick = onComplete)
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (isLastPage) "Get Started" else "Skip",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        color = Color.White,
                    ),
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
