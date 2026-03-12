package live.localapp.local.feature.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

private data class LoadingCard(
    val icon: ImageVector,
    val label: String,
)

/** Rounds down to nearest hundred and appends "+", e.g. 847 → "800+", 1234 → "1,200+" */
private fun formatCount(count: Int): String {
    val rounded = (count / 100) * 100
    val formatted = if (rounded >= 1000) {
        val thousands = rounded / 1000
        val hundreds = (rounded % 1000) / 100
        if (hundreds > 0) "$thousands,${hundreds}00" else "$thousands,000"
    } else {
        rounded.toString()
    }
    return "$formatted+"
}

@Composable
fun SuccessScreen(
    eventCount: Int?,
    venueCount: Int?,
    onComplete: () -> Unit,
) {
    val cards = remember(eventCount, venueCount) {
        listOf(
            LoadingCard(Icons.Outlined.Place, "Location confirmed"),
            LoadingCard(
                Icons.Outlined.ConfirmationNumber,
                if (eventCount != null) "${formatCount(eventCount)} events loaded" else "Loading events...",
            ),
            LoadingCard(
                Icons.Outlined.Restaurant,
                if (venueCount != null) "${formatCount(venueCount)} venues matched" else "Matching venues...",
            ),
            LoadingCard(Icons.Outlined.Map, "Your local map is ready"),
        )
    }

    // 0=inactive, 1=loading, 2=done  — for each card
    var cardStates by remember { mutableStateOf(listOf(0, 0, 0, 0)) }

    LaunchedEffect(Unit) {
        val timing = listOf(200L, 1100L, 2000L, 2900L)
        val doneTiming = listOf(1050L, 1950L, 2850L, 3750L)

        timing.forEachIndexed { i, t ->
            delay(if (i == 0) t else t - (timing.getOrElse(i - 1) { 0L }))
            cardStates = cardStates.toMutableList().also { it[i] = 1 }
        }

        // Mark each done
        delay(doneTiming[3] - timing[3])
        cardStates = listOf(2, 2, 2, 2)

        delay(650)
        onComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFFE6F9F2), Color(0xFFFAF8FC), Color(0xFFE6F9F2)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 20.dp)
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Nice!",
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Black,
                    fontSize = 26.sp,
                    color = AppColors.Ink,
                ),
            )
            Text(
                "Setting you up...",
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Black,
                    fontSize = 26.sp,
                    fontStyle = FontStyle.Italic,
                    color = AppColors.SageDk,
                ),
            )

            Spacer(Modifier.height(4.dp))

            Text(
                "Building your personalised feed right now.",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = AppColors.Ink2,
                ),
            )

            Spacer(Modifier.height(20.dp))

            cards.forEachIndexed { i, card ->
                val cardState = cardStates[i]
                val alpha = if (cardState == 0) 0.3f else 1f
                val borderColor = if (cardState > 0) AppColors.Sage else AppColors.Pebble
                val shadowColor = if (cardState > 0) AppColors.SageDk else AppColors.Pebble

                val barTarget = when (cardState) {
                    1 -> 1f
                    2 -> 1f
                    else -> 0f
                }
                val barProgress by animateFloatAsState(
                    targetValue = barTarget,
                    animationSpec = tween(if (cardState == 1) 850 else 0),
                )

                Box(modifier = Modifier.padding(bottom = 10.dp)) {
                    // Shadow
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .padding(top = 3.dp)
                            .background(shadowColor, RoundedCornerShape(14.dp)),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.White.copy(alpha = alpha), RoundedCornerShape(14.dp))
                            .border(2.dp, borderColor, RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                card.icon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (cardState > 0) AppColors.SageDk else AppColors.Ink3,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    card.label,
                                    style = TextStyle(
                                        fontFamily = AppFonts.Body,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 13.sp,
                                        color = AppColors.Ink.copy(alpha = alpha),
                                    ),
                                )
                                Spacer(Modifier.height(5.dp))
                                Box(
                                    modifier = Modifier
                                        .width(120.dp)
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(AppColors.Mist)
                                        .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(5.dp)),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(barProgress)
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(
                                                Brush.horizontalGradient(listOf(AppColors.Sage, AppColors.Sky)),
                                            ),
                                    )
                                }
                            }
                        }

                        // Check circle
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    if (cardState == 2) AppColors.SageDk else AppColors.Pebble,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = AppColors.White,
                            )
                        }
                    }
                }
            }
        }
    }
}
