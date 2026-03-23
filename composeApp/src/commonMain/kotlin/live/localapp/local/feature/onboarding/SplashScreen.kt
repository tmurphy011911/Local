package live.localapp.local.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

private val WORDS = listOf(
    "trips" to AppColors.Coral,
    "dates" to AppColors.Plum,
    "weekends" to AppColors.Sage,
    "days" to AppColors.Sky,
    "everything" to AppColors.Sun,
)

@Composable
fun SplashScreen(onComplete: () -> Unit) {
    var logoVisible by remember { mutableStateOf(false) }
    var taglineVisible by remember { mutableStateOf(false) }
    var wordIndex by remember { mutableStateOf(0) }
    var progressTarget by remember { mutableStateOf(0f) }
    var loadingVisible by remember { mutableStateOf(false) }
    var readyVisible by remember { mutableStateOf(false) }
    var tapEnabled by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    val progress by animateFloatAsState(
        targetValue = progressTarget,
        animationSpec = tween(if (progressTarget > 0f) 4200 else 0),
    )

    LaunchedEffect(Unit) {
        delay(350)
        logoVisible = true
        delay(600)
        taglineVisible = true
        delay(300)
        loadingVisible = true
        progressTarget = 1f

        // Word flip with haptic
        repeat(WORDS.size - 1) {
            delay(850)
            wordIndex++
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        }

        delay(600)
        readyVisible = true
        tapEnabled = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFFFFF8F8), Color(0xFFFAF8FC), Color(0xFFEEF6FF)),
                    radius = 800f,
                ),
            )
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
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            // Logo mark
            AnimatedVisibility(
                visible = logoVisible,
                enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 4 },
            ) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .background(AppColors.Coral, RoundedCornerShape(30.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "L",
                        style = TextStyle(
                            fontFamily = AppFonts.Heading,
                            fontWeight = FontWeight.Black,
                            fontSize = 56.sp,
                            color = AppColors.White,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // Tagline
            AnimatedVisibility(
                visible = taglineVisible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 3 },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "plan",
                        style = TextStyle(
                            fontFamily = AppFonts.Heading,
                            fontWeight = FontWeight.Bold,
                            fontSize = 34.sp,
                            color = AppColors.Ink,
                        ),
                    )

                    // Animated word with crossfade only (no slide to avoid layout jank)
                    Box(
                        modifier = Modifier.height(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedContent(
                            targetState = wordIndex,
                            transitionSpec = {
                                fadeIn(tween(500))
                                    .togetherWith(fadeOut(tween(400)))
                            },
                        ) { index ->
                            val (word, color) = WORDS[index]
                            Text(
                                word,
                                style = TextStyle(
                                    fontFamily = AppFonts.Heading,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 36.sp,
                                    fontStyle = FontStyle.Italic,
                                    color = color,
                                ),
                            )
                        }
                    }

                    Text(
                        "like a local",
                        style = TextStyle(
                            fontFamily = AppFonts.Heading,
                            fontWeight = FontWeight.Bold,
                            fontSize = 34.sp,
                            color = AppColors.Ink,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(52.dp))

            // Loading bar
            AnimatedVisibility(
                visible = loadingVisible,
                enter = fadeIn(tween(400)),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(0.55f),
                ) {
                    Text(
                        "Finding what's on nearby...",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = AppColors.Ink3,
                        ),
                    )

                    Spacer(Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AppColors.Mist),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .height(10.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(AppColors.Coral, AppColors.Sun, AppColors.Sage),
                                    ),
                                ),
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    AnimatedVisibility(
                        visible = readyVisible,
                        enter = fadeIn(tween(500)),
                    ) {
                        Text(
                            "Let's discover",
                            style = TextStyle(
                                fontFamily = AppFonts.Heading,
                                fontWeight = FontWeight.Black,
                                fontSize = 19.sp,
                                fontStyle = FontStyle.Italic,
                                color = AppColors.Coral,
                            ),
                        )
                    }
                }
            }
        }

        // Tap hint at bottom
        if (tapEnabled) {
            Text(
                "Tap anywhere to continue",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp),
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
