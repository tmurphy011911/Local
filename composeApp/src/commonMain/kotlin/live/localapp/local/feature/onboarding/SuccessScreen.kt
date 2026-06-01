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

/** Format a number with commas, e.g. 1475 → "1,475" */
private fun formatNumber(n: Int): String {
    val s = n.toString()
    val result = StringBuilder()
    for (i in s.indices) {
        if (i > 0 && (s.length - i) % 3 == 0) result.append(',')
        result.append(s[i])
    }
    return result.toString()
}

@Composable
fun SuccessScreen(
    eventCount: Int?,
    venueCount: Int?,
    onComplete: () -> Unit,
) {
    // "Loaded" = query returned, regardless of count. A zero result is still
    // a completed query — the cascade must advance or the user gets stuck.
    val eventsLoaded = eventCount != null
    val venuesLoaded = venueCount != null

    // Reactive labels — show actual numbers when loaded.
    // 5000 is the EVENTS_PAGE_COUNT × PostgREST page cap from
    // OnboardingRepositoryImpl. When we hit it, the real total is higher
    // — show "5,000+" rather than implying we counted everything.
    val eventsLabel = if (eventsLoaded) {
        val displayCount = if (eventCount!! >= 5000) "${formatNumber(5000)}+" else formatNumber(eventCount)
        "$displayCount events loaded"
    } else "Finding events..."
    val venuesLabel = if (venuesLoaded) "${formatNumber(venueCount!!)} venues matched" else "Discovering venues..."

    val cards = listOf(
        LoadingCard(Icons.Outlined.Place, "Location confirmed"),
        LoadingCard(Icons.Outlined.ConfirmationNumber, eventsLabel),
        LoadingCard(Icons.Outlined.Restaurant, venuesLabel),
        LoadingCard(Icons.Outlined.Map, "Your local map is ready"),
    )

    // 0=inactive, 1=loading, 2=done  — for each card
    var cardStates by remember { mutableStateOf(listOf(0, 0, 0, 0)) }

    // Animate cards starting one by one
    LaunchedEffect(Unit) {
        delay(200); cardStates = listOf(1, 0, 0, 0)  // location starts loading
        delay(800); cardStates = listOf(2, 1, 0, 0)  // location done, events start
    }

    // Events card: mark done when data arrives
    LaunchedEffect(eventsLoaded, cardStates) {
        if (eventsLoaded && cardStates[1] == 1) {
            delay(300) // brief pause so the fill animation is visible
            cardStates = listOf(2, 2, 1, 0) // events done, venues start
        }
    }

    // Venues card: mark done when data arrives. Each LaunchedEffect makes
    // exactly one state change — chaining two assignments separated by a
    // delay would self-cancel (mutating cardStates re-launches this effect).
    LaunchedEffect(venuesLoaded, cardStates) {
        if (venuesLoaded && cardStates[2] == 1) {
            delay(300)
            cardStates = listOf(2, 2, 2, 1) // venues done, map starts
        }
    }

    // Map card: brief delay then mark done.
    LaunchedEffect(cardStates) {
        if (cardStates[3] == 1) {
            delay(800)
            cardStates = listOf(2, 2, 2, 2) // map done
        }
    }

    // Auto-complete when all done
    LaunchedEffect(cardStates) {
        if (cardStates.all { it == 2 }) {
            delay(650)
            onComplete()
        }
    }

    // Safety timeout — only fires if neither query ever completed (network
    // dropped mid-load). The events query is keyset-paginated and can take
    // 5-9s cold; the 10s blanket timeout used to cut the animation cascade
    // short. Once data arrives the normal cascade handles completion.
    LaunchedEffect(eventsLoaded, venuesLoaded) {
        delay(30000)
        if (!eventsLoaded && !venuesLoaded) onComplete()
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
                "Building your personalized feed right now.",
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
                    1 -> 0.9f   // fill to 90% while searching
                    2 -> 1f     // snap to 100% when data arrives
                    else -> 0f
                }
                val barProgress by animateFloatAsState(
                    targetValue = barTarget,
                    animationSpec = when (cardState) {
                        1 -> tween(2500)  // slow fill to 90%
                        2 -> tween(200)   // quick snap to 100%
                        else -> tween(0)
                    },
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
