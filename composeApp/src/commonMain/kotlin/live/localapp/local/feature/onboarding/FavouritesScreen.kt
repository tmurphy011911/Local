package live.localapp.local.feature.onboarding

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

private val FavOrange = Color(0xFFFF8C42)
private val FavOrangeDk = Color(0xFFD4692A)

@Composable
fun FavouritesScreen(
    events: List<SavedEvent>,
    isPersonalised: Boolean,
    onRemove: (String) -> Unit,
    onNext: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFFFFF6EE), Color(0xFFFAF8FC), Color(0xFFFFF8EE)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 20.dp),
        ) {
            // Header
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                if (isPersonalised) {
                    Row {
                        Text(
                            "Your starter pack is ",
                            style = TextStyle(
                                fontFamily = AppFonts.Heading,
                                fontWeight = FontWeight.Black,
                                fontSize = 24.sp,
                                color = AppColors.Ink,
                                lineHeight = 30.sp,
                            ),
                        )
                        Text(
                            "ready",
                            style = TextStyle(
                                fontFamily = AppFonts.Heading,
                                fontWeight = FontWeight.Black,
                                fontSize = 24.sp,
                                fontStyle = FontStyle.Italic,
                                color = FavOrange,
                                lineHeight = 30.sp,
                            ),
                        )
                    }
                    Text(
                        "We've saved ${events.size} event${if (events.size != 1) "s" else ""} based on your picks",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = AppColors.Ink2,
                        ),
                    )
                } else {
                    Row {
                        Text(
                            "What's ",
                            style = TextStyle(
                                fontFamily = AppFonts.Heading,
                                fontWeight = FontWeight.Black,
                                fontSize = 24.sp,
                                color = AppColors.Ink,
                                lineHeight = 30.sp,
                            ),
                        )
                        Text(
                            "hot",
                            style = TextStyle(
                                fontFamily = AppFonts.Heading,
                                fontWeight = FontWeight.Black,
                                fontSize = 24.sp,
                                fontStyle = FontStyle.Italic,
                                color = FavOrange,
                                lineHeight = 30.sp,
                            ),
                        )
                        Text(
                            " in Boston",
                            style = TextStyle(
                                fontFamily = AppFonts.Heading,
                                fontWeight = FontWeight.Black,
                                fontSize = 24.sp,
                                color = AppColors.Ink,
                                lineHeight = 30.sp,
                            ),
                        )
                    }
                    Text(
                        "${events.size} popular event${if (events.size != 1) "s" else ""} saved to get you started",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = AppColors.Ink2,
                        ),
                    )
                }
            }

            // Event list
            if (events.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "You've cleared everything out.",
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = AppColors.Ink3,
                            ),
                        )
                        Text(
                            "You can always save events once you're in the app.",
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = AppColors.Ink2,
                            ),
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(events, key = { it.id }) { event ->
                        FavEventCard(event = event, onRemove = { onRemove(event.id) })
                    }
                }
            }

            // Bottom
            Column(
                modifier = Modifier
                    .background(AppColors.Snow)
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(AppColors.Mist),
                )
                Spacer(Modifier.height(10.dp))
                OnboardingButton(
                    text = if (events.isEmpty()) "Continue anyway \u2192" else "Save my picks \u2192",
                    color = FavOrange,
                    shadowColor = FavOrangeDk,
                    onClick = onNext,
                )
            }
        }
    }
}

@Composable
private fun FavEventCard(event: SavedEvent, onRemove: () -> Unit) {
    Box {
        // Shadow
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(top = 4.dp)
                .background(AppColors.Pebble, RoundedCornerShape(16.dp)),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.White, RoundedCornerShape(16.dp))
                .border(2.dp, AppColors.Pebble, RoundedCornerShape(16.dp))
                .padding(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumb
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        Brush.linearGradient(listOf(Color(event.thumbBgStart), Color(event.thumbBgEnd))),
                        RoundedCornerShape(12.dp),
                    )
                    .border(2.dp, AppColors.Pebble, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    event.icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = Color(event.tagColor),
                )
            }

            Spacer(Modifier.width(10.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    event.venueCity,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 10.sp,
                        color = AppColors.Ink3,
                    ),
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    event.title,
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = AppColors.Ink,
                        lineHeight = 17.sp,
                    ),
                    maxLines = 1,
                )
                Text(
                    event.meta,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        color = AppColors.Ink3,
                        lineHeight = 14.sp,
                    ),
                    maxLines = 2,
                )
            }

            // Remove button
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(AppColors.Mist, CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onRemove,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Remove",
                    modifier = Modifier.size(14.dp),
                    tint = AppColors.Ink3,
                )
            }
        }
    }
}
