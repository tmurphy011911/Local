package live.localapp.local.feature.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

/** Format digits to (555) 000-0000 */
private fun formatPhone(raw: String): String {
    val d = raw.filter { it.isDigit() }.take(10)
    return when {
        d.length <= 3 -> if (d.isNotEmpty()) "($d" else ""
        d.length <= 6 -> "(${d.substring(0, 3)}) ${d.substring(3)}"
        else -> "(${d.substring(0, 3)}) ${d.substring(3, 6)}-${d.substring(6)}"
    }
}

@Composable
fun PhoneScreen(
    firstName: String,
    lastName: String,
    phoneNumber: String,
    phoneState: PhoneState,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onLookupResult: (PhoneState) -> Unit,
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit = {},
) {
    // Simulate lookup
    LaunchedEffect(phoneState) {
        if (phoneState == PhoneState.CHECKING) {
            delay(2200)
            // Simulate: resolve as new user
            onLookupResult(PhoneState.NEW_USER)
        }
        if (phoneState == PhoneState.RETURNING_COMPLETE) {
            delay(1500)
            onComplete()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFFFFF5F5), Color(0xFFFAF8FC), Color(0xFFF3EEFF)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 20.dp),
        ) {
            // Back / Skip (hidden during/after verification)
            if (phoneState == PhoneState.ENTRY) {
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
            } else {
                Spacer(Modifier.height(28.dp))
            }

            // Phone entry
            AnimatedVisibility(
                visible = phoneState == PhoneState.ENTRY,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 28.dp),
                    ) {
                        Spacer(Modifier.height(32.dp))

                        Text(
                            "Almost there",
                            style = TextStyle(
                                fontFamily = AppFonts.Heading,
                                fontWeight = FontWeight.Black,
                                fontSize = 28.sp,
                                color = AppColors.Ink,
                                lineHeight = 34.sp,
                            ),
                        )
                        Text(
                            "create your account",
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
                            "We'll send a quick verification code. No password needed.",
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = AppColors.Ink2,
                                lineHeight = 22.sp,
                            ),
                        )

                        Spacer(Modifier.height(24.dp))

                        // Name inputs
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // First name
                            BasicTextField(
                                value = firstName,
                                onValueChange = onFirstNameChange,
                                textStyle = TextStyle(
                                    fontFamily = AppFonts.Body,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp,
                                    color = AppColors.Ink,
                                ),
                                cursorBrush = SolidColor(AppColors.Coral),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                singleLine = true,
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(52.dp)
                                            .background(AppColors.White, RoundedCornerShape(16.dp))
                                            .border(2.5.dp, AppColors.Pebble, RoundedCornerShape(16.dp))
                                            .padding(horizontal = 14.dp),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        if (firstName.isEmpty()) {
                                            Text(
                                                "First name",
                                                style = TextStyle(
                                                    fontFamily = AppFonts.Body,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 16.sp,
                                                    color = AppColors.Pebble,
                                                ),
                                            )
                                        }
                                        innerTextField()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )

                            // Last name
                            BasicTextField(
                                value = lastName,
                                onValueChange = onLastNameChange,
                                textStyle = TextStyle(
                                    fontFamily = AppFonts.Body,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp,
                                    color = AppColors.Ink,
                                ),
                                cursorBrush = SolidColor(AppColors.Coral),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                singleLine = true,
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(52.dp)
                                            .background(AppColors.White, RoundedCornerShape(16.dp))
                                            .border(2.5.dp, AppColors.Pebble, RoundedCornerShape(16.dp))
                                            .padding(horizontal = 14.dp),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        if (lastName.isEmpty()) {
                                            Text(
                                                "Last name",
                                                style = TextStyle(
                                                    fontFamily = AppFonts.Body,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 16.sp,
                                                    color = AppColors.Pebble,
                                                ),
                                            )
                                        }
                                        innerTextField()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // Phone input
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .background(AppColors.White, RoundedCornerShape(16.dp))
                                .border(2.5.dp, AppColors.Pebble, RoundedCornerShape(16.dp)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Country code
                            Box(
                                modifier = Modifier
                                    .padding(start = 14.dp, end = 6.dp)
                                    .border(
                                        width = 0.dp,
                                        color = Color.Transparent,
                                    ),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "+1",
                                        style = TextStyle(
                                            fontFamily = AppFonts.Body,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 15.sp,
                                            color = AppColors.Ink,
                                        ),
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(32.dp)
                                    .background(AppColors.Pebble),
                            )

                            BasicTextField(
                                value = formatPhone(phoneNumber),
                                onValueChange = { raw ->
                                    val digits = raw.filter { it.isDigit() }.take(10)
                                    onPhoneChange(digits)
                                },
                                textStyle = TextStyle(
                                    fontFamily = AppFonts.Body,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 22.sp,
                                    color = AppColors.Ink,
                                ),
                                cursorBrush = SolidColor(AppColors.Coral),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                singleLine = true,
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        if (phoneNumber.isEmpty()) {
                                            Text(
                                                "(555) 000-0000",
                                                style = TextStyle(
                                                    fontFamily = AppFonts.Body,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 22.sp,
                                                    color = AppColors.Pebble,
                                                ),
                                            )
                                        }
                                        innerTextField()
                                    }
                                },
                            )
                        }

                        Spacer(Modifier.height(14.dp))

                        // Privacy
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AppColors.Mist, RoundedCornerShape(14.dp))
                                .border(2.dp, AppColors.Pebble, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = AppColors.Ink3,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Your number is only used to sign you in \u2014 we'll never share it or use it for marketing.",
                                style = TextStyle(
                                    fontFamily = AppFonts.Body,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = AppColors.Ink3,
                                    lineHeight = 18.sp,
                                ),
                            )
                        }
                    }

                    // Bottom
                    Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OnboardingButton(
                            text = "Continue \u2192",
                            color = AppColors.Coral,
                            shadowColor = AppColors.CoralDk,
                            onClick = onSubmit,
                        )

                        Text(
                            "By continuing you agree to our Terms and Privacy Policy",
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp,
                                color = AppColors.Ink3,
                                lineHeight = 16.sp,
                            ),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // Checking / Outcome states
            if (phoneState != PhoneState.ENTRY) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    when (phoneState) {
                        PhoneState.CHECKING -> {
                            // Spinner
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .border(3.dp, AppColors.Mist, CircleShape),
                            )
                            Spacer(Modifier.height(20.dp))
                            Text(
                                "Checking your number...",
                                style = TextStyle(
                                    fontFamily = AppFonts.Body,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = AppColors.Ink3,
                                ),
                            )
                        }

                        PhoneState.NEW_USER -> {
                            Icon(
                                Icons.Outlined.Celebration,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                tint = AppColors.Coral,
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "We're so happy",
                                style = TextStyle(
                                    fontFamily = AppFonts.Heading,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 26.sp,
                                    color = AppColors.Ink,
                                ),
                            )
                            Text(
                                "to have you",
                                style = TextStyle(
                                    fontFamily = AppFonts.Heading,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 26.sp,
                                    fontStyle = FontStyle.Italic,
                                    color = AppColors.Coral,
                                ),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Let's discover together",
                                style = TextStyle(
                                    fontFamily = AppFonts.Body,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    color = AppColors.Ink3,
                                ),
                            )
                            Spacer(Modifier.height(32.dp))
                            Box(Modifier.padding(horizontal = 28.dp)) {
                                OnboardingButton(
                                    text = "Let's go \u2192",
                                    color = AppColors.Coral,
                                    shadowColor = AppColors.CoralDk,
                                    onClick = onComplete,
                                )
                            }
                        }

                        PhoneState.RETURNING_ONBOARDING -> {
                            Icon(
                                Icons.Outlined.WavingHand,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                tint = AppColors.Sage,
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Welcome back",
                                style = TextStyle(
                                    fontFamily = AppFonts.Heading,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 26.sp,
                                    fontStyle = FontStyle.Italic,
                                    color = AppColors.Sage,
                                ),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Let's discover together",
                                style = TextStyle(
                                    fontFamily = AppFonts.Body,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    color = AppColors.Ink3,
                                ),
                            )
                            Spacer(Modifier.height(32.dp))
                            Box(Modifier.padding(horizontal = 28.dp)) {
                                OnboardingButton(
                                    text = "Let's go \u2192",
                                    color = AppColors.Sage,
                                    shadowColor = AppColors.SageDk,
                                    onClick = onComplete,
                                )
                            }
                        }

                        PhoneState.RETURNING_COMPLETE -> {
                            Icon(
                                Icons.Outlined.WavingHand,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                tint = AppColors.Sage,
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Welcome back",
                                style = TextStyle(
                                    fontFamily = AppFonts.Heading,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 26.sp,
                                    fontStyle = FontStyle.Italic,
                                    color = AppColors.Sage,
                                ),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Let's discover in 3, 2, 1...",
                                style = TextStyle(
                                    fontFamily = AppFonts.Body,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    color = AppColors.Ink3,
                                ),
                            )
                        }

                        else -> {}
                    }
                }
            }
        }
    }
}
