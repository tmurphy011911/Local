package live.localapp.local.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

@Composable
fun LocationAskScreen(onAllow: () -> Unit, onManual: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFFFFF5F5), Color(0xFFFAF8FC), Color(0xFFEBF8FF)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 20.dp),
        ) {
            // Content area
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Pin icon with pulse rings
                Box(
                    modifier = Modifier.size(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Pulse rings
                    listOf(80.dp, 120.dp, 165.dp).forEach { size ->
                        Box(
                            modifier = Modifier
                                .size(size)
                                .border(2.dp, AppColors.Coral.copy(alpha = 0.18f), CircleShape),
                        )
                    }

                    // Pin
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .rotate(-45f)
                            .background(AppColors.Coral, RoundedCornerShape(50, 50, 50, 0)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Place,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp).rotate(45f),
                            tint = AppColors.White,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Eyebrow removed

                Spacer(Modifier.height(6.dp))

                // Title
                Text(
                    "Enable location for",
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Black,
                        fontSize = 28.sp,
                        color = AppColors.Ink,
                        lineHeight = 34.sp,
                    ),
                )
                Text(
                    "nearby picks",
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Black,
                        fontSize = 28.sp,
                        fontStyle = FontStyle.Italic,
                        color = AppColors.Coral,
                        lineHeight = 34.sp,
                    ),
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    "We'll show you events, restaurants and experiences within your neighborhood \u2014 updated in real time.",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = AppColors.Ink2,
                        lineHeight = 22.sp,
                    ),
                )
            }

            // CTAs
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OnboardingButton(
                    text = "Allow Location Access",
                    color = AppColors.Sky,
                    shadowColor = AppColors.SkyDk,
                    onClick = onAllow,
                )

                Text(
                    "Enter address manually instead",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onManual,
                        )
                        .padding(vertical = 10.dp),
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = AppColors.Ink3,
                    ),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
