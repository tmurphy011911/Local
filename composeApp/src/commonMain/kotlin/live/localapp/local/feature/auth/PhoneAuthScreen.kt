package live.localapp.local.feature.auth

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import live.localapp.local.feature.onboarding.OnboardingButton
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts
import live.localapp.local.ui.theme.AppShapes

/** VisualTransformation that formats raw digits as (555) 000-0000 */
private class PhoneVisualTransformation : androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val digits = text.text
        val formatted = formatPhone(digits)
        val offsetMapping = object : androidx.compose.ui.text.input.OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                // Map digit index to formatted position
                if (offset <= 0) return 0
                if (digits.isEmpty()) return 0
                // Count how many formatted chars correspond to `offset` digits
                var digitsSeen = 0
                for (i in formatted.indices) {
                    if (formatted[i].isDigit()) digitsSeen++
                    if (digitsSeen == offset) return i + 1
                }
                return formatted.length
            }
            override fun transformedToOriginal(offset: Int): Int {
                // Count digits up to formatted position
                var count = 0
                for (i in 0 until offset.coerceAtMost(formatted.length)) {
                    if (formatted[i].isDigit()) count++
                }
                return count
            }
        }
        return androidx.compose.ui.text.input.TransformedText(
            androidx.compose.ui.text.AnnotatedString(formatted), offsetMapping
        )
    }
}

/** Format digits to (555) 000-0000 */
private fun formatPhone(raw: String): String {
    val d = raw.filter { it.isDigit() }.take(10)
    return when {
        d.length <= 3 -> if (d.isNotEmpty()) "($d" else ""
        d.length <= 6 -> "(${d.substring(0, 3)}) ${d.substring(3)}"
        else -> "(${d.substring(0, 3)}) ${d.substring(3, 6)}-${d.substring(6)}"
    }
}

/** Format phone for display in subtitle: +1 (555) 000-0000 */
private fun formatPhoneDisplay(raw: String): String = "+1 ${formatPhone(raw)}"

@Composable
fun PhoneAuthScreen(
    viewModel: PhoneAuthViewModel,
    onAuthComplete: (isNewUser: Boolean, firstName: String, lastName: String, phone: String, authUserId: String) -> Unit,
    onSkip: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        live.localapp.local.observability.Analytics.track(
            live.localapp.local.observability.AnalyticsEvent.signupStarted(entryPoint = "organic")
        )
    }

    // Handle auth completion
    if (state.authComplete) {
        val userId = state.authUserId ?: ""
        if (state.isExistingUser) {
            onAuthComplete(false, "", "", state.phone, userId)
        } else {
            onAuthComplete(true, state.firstName, state.lastName, state.phone, userId)
        }
        return
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
        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                (slideInHorizontally(tween(350)) { it } + fadeIn(tween(250)))
                    .togetherWith(slideOutHorizontally(tween(300)) { -it / 3 } + fadeOut(tween(200)))
            },
        ) { step ->
            when (step) {
                AuthStep.PHONE -> PhoneInputContent(
                    phone = state.phone,
                    isLoading = state.isLoading,
                    error = state.error,
                    onPhoneChange = viewModel::updatePhone,
                    onContinue = viewModel::sendOtp,
                    onSkip = onSkip,
                )
                AuthStep.OTP -> OtpVerificationContent(
                    phone = state.phone,
                    otpCode = state.otpCode,
                    isLoading = state.isLoading,
                    error = state.error,
                    onOtpChange = viewModel::updateOtpCode,
                    onVerify = viewModel::verifyOtp,
                    onResend = viewModel::resendOtp,
                    onBack = viewModel::goBackToPhone,
                )
            }
        }
    }
}

@Composable
private fun PhoneInputContent(
    phone: String,
    isLoading: Boolean,
    error: String?,
    onPhoneChange: (String) -> Unit,
    onContinue: () -> Unit,
    onSkip: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding()
            .padding(top = 20.dp),
    ) {
        if (onSkip != null) {
            Box(modifier = Modifier.fillMaxWidth().padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Skip",
                    modifier = Modifier.size(22.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSkip,
                    ),
                    tint = AppColors.Ink3,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
        ) {
            Spacer(Modifier.height(48.dp))

            // "Welcome to local" — "local" uses the splash wordmark coloring (coral l's, ink "oca")
            Text(
                buildAnnotatedString {
                    append("Welcome to ")
                    withStyle(SpanStyle(color = AppColors.Coral)) { append("l") }
                    withStyle(SpanStyle(color = AppColors.Ink)) { append("oca") }
                    withStyle(SpanStyle(color = AppColors.Coral)) { append("l") }
                },
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Black,
                    fontSize = 30.sp,
                    color = AppColors.Ink,
                ),
            )

            Spacer(Modifier.height(10.dp))

            Text(
                "Enter your phone number to get started.",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = AppColors.Ink2,
                    lineHeight = 22.sp,
                ),
            )

            Spacer(Modifier.height(28.dp))

            // Phone input
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(AppColors.White, RoundedCornerShape(16.dp))
                    .border(2.5.dp, if (error != null) AppColors.Coral else AppColors.Pebble, RoundedCornerShape(16.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.padding(start = 14.dp, end = 6.dp),
                ) {
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

                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(32.dp)
                        .background(AppColors.Pebble),
                )

                BasicTextField(
                    value = phone,
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
                    enabled = !isLoading,
                    visualTransformation = PhoneVisualTransformation(),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (phone.isEmpty()) {
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
                    modifier = Modifier.testTag("auth_phone_field"),
                )
            }

            // Error
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    error,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = AppColors.Coral,
                    ),
                )
            }

            Spacer(Modifier.height(16.dp))

            // Privacy note
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
                    "We\u2019ll send a verification code. No password needed. We\u2019ll never share your number.",
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

        // Bottom button \u2014 pad for the iOS home-indicator / nav-bar safe area.
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 28.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AuthButton(
                text = if (isLoading) "Sending..." else "Continue \u2192",
                enabled = phone.length == 10 && !isLoading,
                isLoading = isLoading,
                onClick = onContinue,
            )

            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Text("By continuing you agree to our ", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3))
                Text(
                    "Terms",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = AppColors.Sky),
                    modifier = Modifier.clickable { uriHandler.openUri("https://localapp.live/terms") },
                )
                Text(" and ", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3))
                Text(
                    "Privacy Policy",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = AppColors.Sky),
                    modifier = Modifier.clickable { uriHandler.openUri("https://localapp.live/privacy-policy") },
                )
            }
        }
    }
}

@Composable
private fun OtpVerificationContent(
    phone: String,
    otpCode: String,
    isLoading: Boolean,
    error: String?,
    onOtpChange: (String) -> Unit,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding()
            .padding(top = 20.dp),
    ) {
        // Back button
        Text(
            "\u2190 Back",
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .clickable(
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

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
        ) {
            Spacer(Modifier.height(32.dp))

            Text(
                buildAnnotatedString {
                    append("Enter the ")
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = AppColors.Coral)) {
                        append("code")
                    }
                },
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Black,
                    fontSize = 28.sp,
                    color = AppColors.Ink,
                ),
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "We sent a code to ${formatPhoneDisplay(phone)}",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = AppColors.Ink2,
                    lineHeight = 22.sp,
                ),
            )

            Spacer(Modifier.height(32.dp))

            // OTP input - invisible text field overlaid on 6 visual boxes
            val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                // Visual boxes
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (i in 0 until 6) {
                        val char = otpCode.getOrNull(i)?.toString() ?: ""
                        val isFocused = i == otpCode.length

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .background(AppColors.White, RoundedCornerShape(14.dp))
                                .border(
                                    width = 2.5.dp,
                                    color = when {
                                        error != null -> AppColors.Coral
                                        isFocused -> AppColors.Coral.copy(alpha = 0.6f)
                                        char.isNotEmpty() -> AppColors.Ink.copy(alpha = 0.2f)
                                        else -> AppColors.Pebble
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = char,
                                style = TextStyle(
                                    fontFamily = AppFonts.Heading,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 24.sp,
                                    color = AppColors.Ink,
                                ),
                            )
                        }
                    }
                }

                // Invisible text field covering the entire box area to capture taps + input.
                //
                // Bind to a TextFieldValue with the caret pinned to the END, not a
                // plain String. On iOS the platform IME's selection drifts out of
                // sync after each programmatic value update, so a String-bound field
                // lets the caret land mid-string — backspace then deletes the wrong
                // digit and gets stuck with one left (the reported iOS-only bug).
                // Re-deriving with selection = length on every otpCode change keeps
                // the caret at the end so backspace always removes the last digit.
                val otpFieldValue = remember(otpCode) {
                    TextFieldValue(text = otpCode, selection = TextRange(otpCode.length))
                }
                BasicTextField(
                    value = otpFieldValue,
                    onValueChange = { tfv ->
                        val digits = tfv.text.filter { it.isDigit() }.take(6)
                        onOtpChange(digits)
                    },
                    textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
                    cursorBrush = SolidColor(Color.Transparent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier
                        .matchParentSize()
                        .alpha(0f)
                        .focusRequester(focusRequester)
                        .testTag("auth_otp_field"),
                )
            }

            // Error
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    error,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = AppColors.Coral,
                    ),
                )
            }

            Spacer(Modifier.height(20.dp))

            // Resend
            Text(
                "Didn\u2019t get a code? Resend",
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !isLoading,
                    onClick = onResend,
                ),
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    color = AppColors.Coral,
                ),
            )
        }

        // Bottom button \u2014 pad for the iOS home-indicator / nav-bar safe area so it
        // isn't clipped at the bottom edge.
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 28.dp, vertical = 20.dp),
        ) {
            AuthButton(
                text = if (isLoading) "Verifying..." else "Verify \u2192",
                enabled = otpCode.length == 6 && !isLoading,
                isLoading = isLoading,
                onClick = onVerify,
            )
        }
    }
}

@Composable
private fun AuthButton(
    text: String,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    onClick: () -> Unit,
) {
    // Duolingo-style 3D button — shared with onboarding/tutorial CTAs.
    OnboardingButton(
        text = text,
        color = AppColors.Coral,
        shadowColor = AppColors.CoralDk,
        onClick = onClick,
        enabled = enabled,
        isLoading = isLoading,
        textColor = AppColors.White,
    )
}
