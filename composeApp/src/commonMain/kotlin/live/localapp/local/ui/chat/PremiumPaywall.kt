package live.localapp.local.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import live.localapp.local.ui.common.CircleShape
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import local.composeapp.generated.resources.Res
import local.composeapp.generated.resources.bo_tiny
import org.jetbrains.compose.resources.painterResource
import live.localapp.local.ui.common.pressable
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts
import live.localapp.local.ui.theme.AppType

@Composable
fun PremiumPaywall(
    onSubscribe: (isAnnual: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onRestore: () -> Unit = {},
    monthlyPrice: String? = null,
    annualPrice: String? = null,
    introPrice: String? = null,
    isPurchasing: Boolean = false,
    monthlyIntroEligible: Boolean = false,
) {
    // Live prices come from RevenueCat (monthlyPrice/annualPrice/introPrice).
    // The hardcoded strings here are only fallbacks for when RC pricing hasn't
    // loaded yet — keep them in sync with the store products ($0.99/mo, $8.99/yr).
    val hasIntro = introPrice != null && monthlyIntroEligible
    val displayMonthly = if (hasIntro) introPrice!! else (monthlyPrice ?: "$0.99")
    val displayMonthlySubtext = if (hasIntro) "then ${monthlyPrice ?: "$0.99"}/month" else "${monthlyPrice ?: "$0.99"}/month"
    val displayMonthlyBadge = if (hasIntro) "First month" else null
    val displayAnnual = annualPrice ?: "$8.99"
    val annualPerMonth = try {
        val raw = displayAnnual.replace(Regex("[^0-9.]"), "").toDouble()
        val cents = kotlin.math.round(raw / 12 * 100).toInt()
        "$${cents / 100}.${(cents % 100).toString().padStart(2, '0')}/month"
    } catch (_: Exception) { "$0.75/month" }
    // Bug #25: default to Monthly (iOS parity — PaywallView.swift `isAnnual =
    // false`) so the first-month intro offer + "Try for $X" CTA is the default
    // surface. Was defaulting to Annual, which hid the intro offer entirely.
    var isAnnual by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1523)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Close button
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Close",
                    modifier = Modifier
                        .size(24.dp)
                        .pressable(onClick = onDismiss),
                    tint = Color.White.copy(alpha = 0.5f),
                )
            }

            Spacer(Modifier.height(16.dp))

            // Wordmark — coral "l"s, white "oca", straight on the dark page.
            Text(
                buildAnnotatedString {
                    "local".forEach { ch ->
                        if (ch == 'l') {
                            withStyle(SpanStyle(color = AppColors.Coral)) { append(ch) }
                        } else {
                            append(ch)
                        }
                    }
                },
                style = AppType.Logo.copy(fontSize = 44.sp, color = Color.White),
            )

            Spacer(Modifier.height(20.dp))

            // Static tagline; Bo writes the second half beneath it.
            Text(
                "Plan together, effortlessly.",
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic,
                    fontSize = 18.sp,
                    color = AppColors.Coral,
                ),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            PaywallBoTagline("You and me.")

            Spacer(Modifier.height(28.dp))

            // Free vs Pro comparison matrix
            ComparisonMatrix()

            Spacer(Modifier.weight(1f))

            // Pricing toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Monthly option
                PricingCard(
                    modifier = Modifier.weight(1f),
                    isSelected = !isAnnual,
                    label = "Monthly",
                    price = displayMonthly,
                    subtext = displayMonthlySubtext,
                    badge = displayMonthlyBadge,
                    onClick = { isAnnual = false },
                )
                // Annual option
                PricingCard(
                    modifier = Modifier.weight(1f),
                    isSelected = isAnnual,
                    label = "Annual",
                    price = displayAnnual,
                    subtext = annualPerMonth,
                    badge = "Best value",
                    onClick = { isAnnual = true },
                )
            }

            Spacer(Modifier.height(16.dp))

            // Subscribe CTA
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(listOf(AppColors.Coral, Color(0xFFE85D5D))),
                    )
                    .then(
                        if (isPurchasing) Modifier
                        else Modifier.pressable(onClick = { onSubscribe(isAnnual) })
                    )
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isPurchasing) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Text(
                        if (isAnnual) "Start Local Pro" else if (hasIntro) "Try for $displayMonthly" else "Start Local Pro",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.Black,
                            fontSize = 16.sp,
                            color = Color.White,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                "Not Now",
                modifier = Modifier.pressable(onClick = onDismiss),
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.6f),
                ),
            )
        }
    }
}

/**
 * Bo slides up from below, a coral cursor fades in, then the tagline streams
 * character-by-character with a per-glyph haptic — mirrors the splash/empty-state
 * BoTapLine, retuned coral-on-dark for the paywall.
 */
@Composable
private fun PaywallBoTagline(text: String) {
    val density = LocalDensity.current
    val travelPx = with(density) { 24.dp.toPx() }
    val haptic = LocalHapticFeedback.current

    val boTranslate = remember { Animatable(travelPx) }
    val boAlpha = remember { Animatable(0f) }
    val cursorAlpha = remember { Animatable(0f) }
    var revealed by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        // Let the paywall settle on screen before Bo makes an entrance.
        delay(450)
        coroutineScope {
            launch {
                boTranslate.animateTo(0f, tween(500, easing = CubicBezierEasing(0.2f, 0.85f, 0.3f, 1.05f)))
            }
            launch {
                boAlpha.animateTo(1f, tween(350, easing = LinearEasing))
            }
            launch {
                cursorAlpha.animateTo(1f, tween(350, easing = LinearEasing))
            }
        }
        delay(220)
        while (revealed < text.length) {
            delay(45)
            revealed++
            if (!text[revealed - 1].isWhitespace()) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
        delay(350)
        cursorAlpha.animateTo(0f, tween(260, easing = LinearEasing))
    }

    val cursorId = "cursor"
    val annotated = buildAnnotatedString {
        append(text.take(revealed))
        appendInlineContent(cursorId, "​")
    }
    val inlineContent = mapOf(
        cursorId to InlineTextContent(
            placeholder = Placeholder(
                width = 5.sp,
                height = 16.sp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
            ),
            children = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 2.dp)
                        .graphicsLayer { alpha = cursorAlpha.value }
                        .background(AppColors.Coral),
                )
            },
        ),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(Res.drawable.bo_tiny),
            contentDescription = null,
            modifier = Modifier
                .size(26.dp)
                .graphicsLayer {
                    translationY = boTranslate.value
                    alpha = boAlpha.value
                },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            annotated,
            inlineContent = inlineContent,
            style = TextStyle(
                fontFamily = AppFonts.Heading,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
                fontSize = 16.sp,
                color = AppColors.Coral,
            ),
        )
    }
}

@Composable
private fun ComparisonMatrix() {
    // (feature, inFree, inPro). Free = browse only; Pro unlocks everything else.
    val rows = listOf(
        Triple("Browse Events and Places", true, true),
        Triple("See Event and Place Info", false, true),
        Triple("Save Events and Places", false, true),
        Triple("Ask Bo", false, true),
        Triple("Create Plans", false, true),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 18.dp),
    ) {
        MatrixHeaderRow()
        rows.forEach { (feature, inFree, inPro) ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.06f)),
            )
            MatrixRow(feature = feature, inFree = inFree, inPro = inPro)
        }
    }
}

@Composable
private fun MatrixHeaderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            "Free",
            modifier = Modifier.width(52.dp),
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.5f),
            ),
        )
        Text(
            "Pro",
            modifier = Modifier.width(60.dp),
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                color = AppColors.Coral,
            ),
        )
    }
}

@Composable
private fun MatrixRow(feature: String, inFree: Boolean, inPro: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            feature,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.sp,
                color = Color.White,
            ),
        )
        Box(modifier = Modifier.width(52.dp), contentAlignment = Alignment.Center) {
            MatrixCell(included = inFree, isPro = false)
        }
        Box(modifier = Modifier.width(60.dp), contentAlignment = Alignment.Center) {
            MatrixCell(included = inPro, isPro = true)
        }
    }
}

@Composable
private fun MatrixCell(included: Boolean, isPro: Boolean) {
    if (included) {
        Icon(
            Icons.Outlined.Check,
            contentDescription = "Included",
            modifier = Modifier.size(18.dp),
            tint = if (isPro) AppColors.Coral else Color.White.copy(alpha = 0.5f),
        )
    } else {
        Box(
            modifier = Modifier
                .width(12.dp)
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.25f)),
        )
    }
}

@Composable
private fun PricingCard(
    modifier: Modifier = Modifier,
    isSelected: Boolean,
    label: String,
    price: String,
    subtext: String,
    badge: String?,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) AppColors.Coral else Color.White.copy(alpha = 0.12f)
    val bgColor = if (isSelected) AppColors.Coral.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.04f)

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(bgColor)
                .border(2.dp, borderColor, RoundedCornerShape(14.dp))
                .pressable(onClick = onClick)
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (badge != null) {
                Text(
                    badge,
                    modifier = Modifier
                        .background(AppColors.Coral, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Black,
                        fontSize = 9.sp,
                        color = Color.White,
                    ),
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                label,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f),
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                price,
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Black,
                    fontSize = 24.sp,
                    color = Color.White,
                ),
            )
            Text(
                subtext,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.4f),
                ),
            )
        }
    }
}
