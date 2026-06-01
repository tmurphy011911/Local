package live.localapp.local.ui.emptystate

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import local.composeapp.generated.resources.Res
import local.composeapp.generated.resources.bo_tiny
import org.jetbrains.compose.resources.painterResource
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

/**
 * Bo-driven empty state for all four surfaces.
 *
 * **Layout matches the splash screen's `BoTapLine` exactly** — mini Bo
 * slides up from below, then the line streams character-by-character with a
 * per-glyph haptic, with an inline coral cursor block at the end. The big
 * animated Bo was removed in favor of this lighter treatment.
 *
 * Per-visit copy roll: `bank.random()` is captured in a remember bucket
 * keyed on [rotationKey] so callers can force a re-roll on refresh.
 *
 * Used by:
 *   · For You / subcategory zero-carousels   (Refresh CTA)
 *   · PlacesScreen ExploreEmptyState         (Refresh CTA)
 *   · PlansScreen signed-out                 (Sign in CTA)
 *   · ProgressScreen signed-out              (Sign in CTA)
 */
@Composable
fun BoEmptyState(
    bank: List<String>,
    ctaLabel: String? = null,
    onCta: (() -> Unit)? = null,
    rotationKey: Any = Unit,
    modifier: Modifier = Modifier,
) {
    // The signature still accepts `motion` and `expression` as positional
    // arguments via overloads so existing call sites keep compiling; both
    // are ignored now that the big Bo is gone.
    val line = remember(bank, rotationKey) { bank.random() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BoTapLine(text = line, rotationKey = rotationKey)

        if (ctaLabel != null && onCta != null) {
            Spacer(Modifier.height(28.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(AppColors.Coral)
                    .clickable(onClick = onCta)
                    .padding(horizontal = 32.dp, vertical = 14.dp),
            ) {
                Text(
                    text = ctaLabel,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        color = Color.White,
                    ),
                )
            }
        }
    }
}

// ─── Splash-matching typewriter ──────────────────────────────────────────────
// Constants mirror feature/onboarding/SplashScreen.kt's BoTapLine so the
// two surfaces feel identical. If splash timings change, update here too.
private const val BO_SLIDE_UP_MS = 500
private const val BO_TRAVEL_DP = 30
private const val BO_TO_TEXT_DELAY_MS = 220L
private const val TAP_LINE_CHAR_DELAY_MS = 55L
private val TAGLINE_EASING = CubicBezierEasing(0.2f, 0.85f, 0.3f, 1.05f)

@Composable
private fun BoTapLine(
    text: String,
    rotationKey: Any,
) {
    val density = LocalDensity.current
    val travelPx = with(density) { BO_TRAVEL_DP.dp.toPx() }
    val haptic = LocalHapticFeedback.current

    val boTranslate = remember(rotationKey) { Animatable(travelPx) }
    val boAlpha = remember(rotationKey) { Animatable(0f) }
    var revealedChars by remember(rotationKey, text) { mutableStateOf(0) }

    LaunchedEffect(rotationKey, text) {
        // Bo entrance — slide + fade in parallel.
        coroutineScope {
            launch {
                boTranslate.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(BO_SLIDE_UP_MS, easing = TAGLINE_EASING),
                )
            }
            launch {
                boAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween((BO_SLIDE_UP_MS * 0.7f).toInt(), easing = LinearEasing),
                )
            }
        }
        // Small breath, then stream character-by-character with a per-glyph
        // haptic tick (whitespace excluded — matches splash).
        delay(BO_TO_TEXT_DELAY_MS)
        while (revealedChars < text.length) {
            delay(TAP_LINE_CHAR_DELAY_MS)
            revealedChars++
            val ch = text[revealedChars - 1]
            if (!ch.isWhitespace()) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    // Inline cursor — appended at the end of the streamed text so it follows
    // the wrap onto the last visual line for longer bank entries.
    val cursorId = "cursor"
    val annotated = buildAnnotatedString {
        append(text.take(revealedChars))
        // Insert a zero-width space anchor for the cursor placeholder so it
        // stays glued to the end of the visible text rather than being
        // demoted to its own line when wrap occurs.
        appendInlineContent(cursorId, "​")
    }
    val inlineContent = mapOf(
        cursorId to InlineTextContent(
            placeholder = Placeholder(
                width = 4.sp,
                height = 14.sp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
            ),
            children = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 1.dp)
                        .graphicsLayer { alpha = boAlpha.value }
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
                .size(28.dp)
                .graphicsLayer {
                    translationY = boTranslate.value
                    alpha = boAlpha.value
                },
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = annotated,
            inlineContent = inlineContent,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                letterSpacing = 0.2.sp,
                color = AppColors.Ink3,
                textAlign = TextAlign.Start,
                lineHeight = 20.sp,
            ),
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
