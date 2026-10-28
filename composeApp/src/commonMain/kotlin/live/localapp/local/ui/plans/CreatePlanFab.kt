package live.localapp.local.ui.plans

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

/**
 * Bottom-bar "Ask Bo" button — opens the Bo chat directly.
 *
 * Interaction:
 * - Haptic on touch-down (feedback that the press registered)
 * - Scale-shrinks while held
 * - Haptic on release (completion click)
 * - onClick fires on release only (not on cancel / drag-off)
 *
 * Accessibility: at large system font scales the "Ask Bo" label grows and
 * would overrun the left nav pill (fixed-width containers, scaling text).
 * `compact` drops the label and shrinks to an icon-only pill so the Bo
 * character alone remains — the caller flips it on above a font-scale
 * threshold and reclaims the freed width for the nav pill. A
 * `contentDescription` keeps the button labeled for screen readers when the
 * visible text is gone.
 */
@Composable
fun CreatePlanButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    // Chunky 3D "Duolingo" edge — deeper than the gradient's CoralDk so the
    // 4.dp lip reads as depth beneath the coral-glow face. Follows Bo's
    // seasonal skin (pumpkin, harvest amber, Santa red, Valentine pink).
    val surface = live.localapp.local.ui.character.BoSeason.surface
    val edge = surface?.buttonEdge ?: Color(0xFFC94A4A)
    val glow = surface?.buttonFace?.get(1) ?: AppColors.Coral
    val faceColors = surface?.buttonFace ?: listOf(Color(0xFFFF8A8A), AppColors.Coral, AppColors.CoralDk)
    Box(
        modifier = modifier
            .height(52.dp) // 48 face + 4 lip
            // Full: widened past the old "Create Plan" footprint to give "Ask Bo"
            // more presence and let the 4-tab nav pill shrink a touch. Compact
            // (large fonts): icon-only square so the nav pill can reclaim the width.
            .width(if (compact) 56.dp else 176.dp)
            // Merge into one focusable node reading "Ask Bo" — keeps the button
            // labeled for screen readers when compact drops the visible text, and
            // avoids a double announce from the child Text in full mode.
            .semantics(mergeDescendants = true) { contentDescription = "Ask Bo" }
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        // Haptic 1 — touch-down
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch { scale.animateTo(0.92f, tween(90)) }

                        val released = tryAwaitRelease()

                        scope.launch {
                            scale.animateTo(
                                1f,
                                spring(dampingRatio = 0.35f, stiffness = 500f),
                            )
                        }

                        if (released) {
                            // Haptic 2 — release / commit
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClick()
                        }
                    },
                )
            }
            .testTag("plan_create_btn"),
    ) {
        // 3D lip — behind the face, peeking 4.dp below it.
        Box(
            Modifier.fillMaxWidth().height(48.dp).align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(26.dp)).background(edge),
        )
        // Face — coral glow + gradient, sitting 4.dp above the lip.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .align(Alignment.TopCenter)
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(26.dp),
                    ambientColor = glow,
                    spotColor = glow,
                )
                .background(
                    Brush.linearGradient(colors = faceColors),
                    RoundedCornerShape(26.dp),
                )
                .border(1.5.dp, edge, RoundedCornerShape(26.dp))
                .clip(RoundedCornerShape(26.dp))
                .padding(horizontal = if (compact) 0.dp else 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Bo character as the icon — matches the "Ask Bo" hero card and the
                // old Create Plan menu's "Ask Bo" item.
                live.localapp.local.ui.character.Bo(
                    size = 34.dp,
                    level = live.localapp.local.ui.character.cachedBoLevel(),
                    showSparkles = false,
                    showShadow = false,
                    modifier = Modifier.size(34.dp),
                )
                // Compact (large system fonts): icon only — the label is dropped
                // so the button can't overrun the nav pill.
                if (!compact) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Ask Bo",
                        maxLines = 1,
                        softWrap = false,
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
}

/**
 * Dim backdrop behind the Create Plan menu. Tap anywhere to dismiss.
 * Rendered *below* the tab bar so the originating button stays prominent.
 */
@Composable
fun CreatePlanScrim(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(160)),
        exit = fadeOut(animationSpec = tween(140)),
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.38f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }
}

/**
 * The two option chips that spring up from the Create Plan button.
 * Rendered *above* the tab bar so they visually "come from" the button.
 */
@Composable
fun CreatePlanMenuItems(
    visible: Boolean,
    onDismiss: () -> Unit,
    onYocal: () -> Unit,
    onManual: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                // Size to the widest chip so a shorter label (e.g. "Ask Bo")
                // keeps the same width as "Build a Plan Manually".
                .width(androidx.compose.foundation.layout.IntrinsicSize.Max)
                // 52dp button + 4dp bottom pad + system insets ~= 100dp on most
                // phones. 112dp leaves a little breathing room above the button.
                .padding(end = 16.dp, bottom = 112.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Top item bounces in second (higher up = later).
            BounceInMenuItem(
                visible = visible,
                delayMs = 70,
                label = "Build a Plan Manually",
                icon = Icons.Outlined.Edit,
                onClick = {
                    onManual()
                    onDismiss()
                },
            )
            // Lower item bounces in first (closer to the button).
            BounceInMenuItem(
                visible = visible,
                delayMs = 0,
                label = "Ask Bo",
                icon = Icons.Outlined.AutoAwesome,
                outlined = true,
                useBoCharacter = true,
                onClick = {
                    onYocal()
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun BounceInMenuItem(
    visible: Boolean,
    delayMs: Int,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    useBoCharacter: Boolean = false,
) {
    val scale = remember { Animatable(0.6f) }
    val translateY = remember { Animatable(28f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(visible) {
        if (visible) {
            if (delayMs > 0) delay(delayMs.toLong())
            launch {
                scale.animateTo(
                    1f,
                    spring(dampingRatio = 0.48f, stiffness = Spring.StiffnessMediumLow),
                )
            }
            launch {
                translateY.animateTo(
                    0f,
                    spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
                )
            }
            launch {
                alpha.animateTo(1f, tween(160, easing = LinearOutSlowInEasing))
            }
        } else {
            launch { scale.snapTo(0.6f) }
            launch { translateY.snapTo(28f) }
            launch { alpha.snapTo(0f) }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.scaleX = scale.value
                this.scaleY = scale.value
                this.translationY = translateY.value
                this.alpha = alpha.value
                // Anchor the scale to the bottom-right so items appear to grow
                // up and out from the direction of the originating button.
                this.transformOrigin =
                    androidx.compose.ui.graphics.TransformOrigin(1f, 1f)
            }
            .shadow(10.dp, RoundedCornerShape(24.dp))
            .background(Color.White, RoundedCornerShape(24.dp))
            .then(
                if (outlined) Modifier.border(1.dp, AppColors.Coral, RoundedCornerShape(24.dp))
                else Modifier,
            )
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (useBoCharacter) {
            // The "Create a Plan with Bo" menu item shows the actual Bo
            // character instead of a sparkle icon — gives Bo a recognizable
            // entry point and replaces the generic AutoAwesome glyph.
            live.localapp.local.ui.character.Bo(
                size = 44.dp,
                level = live.localapp.local.ui.character.cachedBoLevel(),
                showSparkles = false,
                showShadow = false,
                modifier = Modifier.size(44.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(AppColors.CoralLt, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = AppColors.Coral,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = AppColors.Ink,
            ),
        )
    }
}
