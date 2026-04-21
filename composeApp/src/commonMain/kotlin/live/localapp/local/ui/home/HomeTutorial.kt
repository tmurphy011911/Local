package live.localapp.local.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import live.localapp.local.ui.common.pressable
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

private data class HomeTutorialStep(
    val headline: String,
    val styledWord: String,
    val subtext: String,
    val iconText: String,
)

private val steps = listOf(
    HomeTutorialStep(
        headline = "Welcome to",
        styledWord = "Local",
        subtext = "Discover the best events, restaurants, and experiences in your area \u2014 all in one place.",
        iconText = "L",
    ),
    HomeTutorialStep(
        headline = "The more you engage, the better the",
        styledWord = "experience",
        subtext = "We learn what you love and surface more of it. Every tap, save, and plan makes Local smarter for you.",
        iconText = "2",
    ),
    HomeTutorialStep(
        headline = "Chat with Yocal to",
        styledWord = "plan",
        subtext = "Tell Yocal what you're in the mood for and get a personalized itinerary in seconds. Date night, family day, night out \u2014 just ask.",
        iconText = "3",
    ),
    HomeTutorialStep(
        headline = "Share plans with",
        styledWord = "friends",
        subtext = "One tap to send your plan to the group. Everyone can view the stops, vote on changes, and confirm they're in.",
        iconText = "4",
    ),
    HomeTutorialStep(
        headline = "Earn points and",
        styledWord = "level up",
        subtext = "Every event you attend, plan you share, and place you discover earns points. Unlock rewards and climb the ranks.",
        iconText = "5",
    ),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeTutorial(onComplete: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { steps.size })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == steps.lastIndex

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
                    // Placeholder GIF area
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFFF0EDF6), Color(0xFFE8E4F0)),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (page == 0) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .background(AppColors.Coral, RoundedCornerShape(18.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "L",
                                    style = TextStyle(
                                        fontFamily = AppFonts.Heading,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 36.sp,
                                        color = Color.White,
                                    ),
                                )
                            }
                        } else {
                            Text(
                                "GIF placeholder",
                                style = TextStyle(
                                    fontFamily = AppFonts.Body,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = AppColors.Ink3,
                                ),
                            )
                        }
                    }

                    Spacer(Modifier.height(32.dp))

                    // Headline with styled word
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            step.headline,
                            style = TextStyle(
                                fontFamily = AppFonts.Heading,
                                fontWeight = FontWeight.Black,
                                fontSize = 26.sp,
                                color = AppColors.Ink,
                                textAlign = TextAlign.Center,
                            ),
                        )
                        Text(
                            step.styledWord,
                            style = TextStyle(
                                fontFamily = AppFonts.Heading,
                                fontWeight = FontWeight.Black,
                                fontSize = 26.sp,
                                fontStyle = FontStyle.Italic,
                                color = AppColors.Coral,
                                textAlign = TextAlign.Center,
                            ),
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        step.subtext,
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = AppColors.Ink3,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp,
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
                    .pressable(onClick = {
                        if (isLastPage) {
                            onComplete()
                        } else {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    })
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
                    .pressable(onClick = onComplete),
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
