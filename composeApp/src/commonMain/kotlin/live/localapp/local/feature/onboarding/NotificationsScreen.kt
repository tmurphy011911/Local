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
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

@Composable
fun NotificationsScreen(
    tickets: Boolean,
    nearby: Boolean,
    venues: Boolean,
    onToggleTickets: () -> Unit,
    onToggleNearby: () -> Unit,
    onToggleVenues: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFFFFFBEE), Color(0xFFFAF8FC), Color(0xFFFFFAEE)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 20.dp),
        ) {
            // Back / Skip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "\u2190 Back",
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBack,
                    ),
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp,
                        color = AppColors.Ink3,
                    ),
                )
                Text(
                    "Skip \u2192",
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSkip,
                    ),
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp,
                        color = AppColors.Ink3,
                    ),
                )
            }

            // Content centered
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = AppColors.Sun,
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    buildAnnotatedString {
                        append("Stay in the ")
                        withStyle(
                            SpanStyle(
                                fontStyle = FontStyle.Italic,
                                color = AppColors.SunDk,
                            ),
                        ) {
                            append("loop")
                        }
                    },
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Black,
                        fontSize = 26.sp,
                        color = AppColors.Ink,
                        textAlign = TextAlign.Center,
                    ),
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    "Get notified before tickets sell out or your favorite venues have something on.",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = AppColors.Ink2,
                        lineHeight = 22.sp,
                    ),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )

                Spacer(Modifier.height(24.dp))

                // Toggle rows
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NotifRow(Icons.Outlined.ConfirmationNumber, "Weekly Snapshot", "A weekly snapshot of events coming up that you'll love", tickets, onToggleTickets)
                    NotifRow(Icons.Outlined.NearMe, "Reminders", "Gentle nudges to make reservations or buy tickets for upcoming plans", nearby, onToggleNearby)
                    NotifRow(Icons.Outlined.Star, "Saved venue updates", "New events at your favorites", venues, onToggleVenues)
                }

                Spacer(Modifier.height(28.dp))

                OnboardingButton(
                    text = "Turn on notifications \u2192",
                    color = AppColors.Sun,
                    shadowColor = AppColors.SunDk,
                    textColor = AppColors.Ink,
                    onClick = onNext,
                )
            }
        }
    }
}

@Composable
private fun NotifRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isOn: Boolean,
    onToggle: () -> Unit,
) {
    Box {
        // Shadow
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(top = 3.dp)
                .background(AppColors.Pebble, RoundedCornerShape(14.dp)),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.White, RoundedCornerShape(14.dp))
                .border(2.dp, AppColors.Pebble, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = AppColors.SunDk,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        color = AppColors.Ink,
                    ),
                )
                Text(
                    subtitle,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = AppColors.Ink3,
                    ),
                )
            }

            // Toggle
            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 26.dp)
                    .background(
                        if (isOn) AppColors.Sun else AppColors.Pebble,
                        RoundedCornerShape(13.dp),
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggle,
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .padding(3.dp)
                        .size(20.dp)
                        .align(if (isOn) Alignment.CenterEnd else Alignment.CenterStart)
                        .background(AppColors.White, RoundedCornerShape(10.dp)),
                )
            }
        }
    }
}
