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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

@Composable
fun ManualAddressScreen(onSubmit: () -> Unit, onBack: () -> Unit = {}) {
    var address by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFFF0FAFF), Color(0xFFFAF8FC), Color(0xFFFFF5F5)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 20.dp),
        ) {
            // Back
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
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
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 28.dp),
            ) {
                // House illustration
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box {
                        // Chimney
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(end = 20.dp)
                                .size(width = 12.dp, height = 22.dp)
                                .background(AppColors.CoralDk, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)),
                        )
                        // Roof
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 20.dp)
                                .size(width = 96.dp, height = 38.dp)
                                .background(AppColors.Coral, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                        )
                        // Body
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .size(width = 80.dp, height = 58.dp)
                                .background(AppColors.SkyLt, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .border(2.5.dp, AppColors.Sky, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                        ) {
                            // Door
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .size(width = 22.dp, height = 32.dp)
                                    .background(AppColors.Sky, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                            )
                            // Windows
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp, start = 8.dp, end = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 18.dp, height = 16.dp)
                                        .background(AppColors.SunLt, RoundedCornerShape(3.dp))
                                        .border(2.dp, AppColors.Sun, RoundedCornerShape(3.dp)),
                                )
                                Box(
                                    modifier = Modifier
                                        .size(width = 18.dp, height = 16.dp)
                                        .background(AppColors.SunLt, RoundedCornerShape(3.dp))
                                        .border(2.dp, AppColors.Sun, RoundedCornerShape(3.dp)),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    "STEP 1 \u00B7 LOCATION",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        color = AppColors.Coral,
                        letterSpacing = 0.8.sp,
                    ),
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    "Enter your",
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Black,
                        fontSize = 28.sp,
                        color = AppColors.Ink,
                        lineHeight = 34.sp,
                    ),
                )
                Text(
                    "home base",
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Black,
                        fontSize = 28.sp,
                        fontStyle = FontStyle.Italic,
                        color = AppColors.SkyDk,
                        lineHeight = 34.sp,
                    ),
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    "Tell us your address or neighbourhood and we'll do the rest.",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = AppColors.Ink2,
                        lineHeight = 22.sp,
                    ),
                )

                Spacer(Modifier.height(14.dp))

                // Address input
                BasicTextField(
                    value = address,
                    onValueChange = { address = it },
                    textStyle = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = AppColors.Ink,
                    ),
                    cursorBrush = SolidColor(AppColors.Sky),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .background(AppColors.White, RoundedCornerShape(16.dp))
                                .border(2.5.dp, if (address.isNotEmpty()) AppColors.Sky else AppColors.Pebble, RoundedCornerShape(16.dp))
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (address.isEmpty()) {
                                Text(
                                    "Street address, neighbourhood or zip",
                                    style = TextStyle(
                                        fontFamily = AppFonts.Body,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp,
                                        color = AppColors.Pebble,
                                    ),
                                )
                            }
                            innerTextField()
                        }
                    },
                    singleLine = true,
                )

                Spacer(Modifier.height(12.dp))

                // Privacy badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.SkyLt, RoundedCornerShape(14.dp))
                        .border(2.dp, AppColors.Sky, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = AppColors.SkyDk,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Your address is only used to generate relevant recommendations for you \u2014 it is never shared, sold, or stored beyond your preferences.",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = AppColors.SkyDk,
                            lineHeight = 18.sp,
                        ),
                    )
                }
            }

            // Bottom
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            ) {
                OnboardingButton(
                    text = "Set my location \u2192",
                    color = AppColors.Sky,
                    shadowColor = AppColors.SkyDk,
                    onClick = onSubmit,
                )
            }
        }
    }
}
