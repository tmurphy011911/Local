package live.localapp.local.feature.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import live.localapp.local.ui.common.CircleShape
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts
import live.localapp.local.ui.theme.AppShapes

data class SelectionItem(
    val id: String,
    val icon: ImageVector? = null,
    val name: String,
    val subtitle: String? = null,
    val tag: String? = null,
    val imageUrl: String? = null,
)

/** A selection card used in Neighborhoods (3-col) / Venues / Categories (2-col) grids */
@Composable
fun SelectionCard(
    item: SelectionItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    fixedHeight: Dp = Dp.Unspecified,
) {
    if (item.imageUrl != null) {
        ImageSelectionCard(item = item, isSelected = isSelected, onClick = onClick, fixedHeight = fixedHeight)
    } else {
        IconSelectionCard(item = item, isSelected = isSelected, onClick = onClick, fixedHeight = fixedHeight)
    }
}

/** Photo-forward card — full-bleed image with text overlay at the bottom */
@Composable
private fun ImageSelectionCard(
    item: SelectionItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    fixedHeight: Dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val offsetY by animateFloatAsState(
        targetValue = if (isPressed) 3f else 0f,
        animationSpec = tween(80),
    )

    val borderColor = if (isSelected) AppColors.Plum else Color.Transparent
    val shadowColor = if (isSelected) AppColors.PlumDk else AppColors.Pebble
    val cardHeight = if (fixedHeight != Dp.Unspecified) fixedHeight else 120.dp

    Box(
        modifier = Modifier
            .graphicsLayer { translationY = offsetY * density }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        // Shadow
        Box(
            modifier = Modifier.matchParentSize()
                .offset(y = if (!isPressed) 4.dp else 1.dp)
                .background(shadowColor, RoundedCornerShape(14.dp)),
        )

        Box(
            modifier = Modifier.fillMaxWidth().height(cardHeight)
                .clip(RoundedCornerShape(14.dp))
                .border(if (isSelected) 2.5.dp else 0.dp, borderColor, RoundedCornerShape(14.dp)),
        ) {
            // Full-bleed image
            coil3.compose.AsyncImage(
                model = item.imageUrl,
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )

            // Gradient overlay at bottom
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                            startY = 30f,
                        ),
                    ),
            )

            // Check circle — top right
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .background(
                        if (isSelected) AppColors.Plum else Color.Black.copy(alpha = 0.3f),
                        CircleShape,
                    )
                    .border(2.dp, if (isSelected) AppColors.PlumDk else Color.White.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Text("\u2713", style = TextStyle(fontWeight = FontWeight.Black, fontSize = 10.sp, color = Color.White))
                }
            }

            // Text overlay — bottom
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            ) {
                Text(
                    item.name,
                    style = TextStyle(
                        fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                        color = Color.White, lineHeight = 15.sp,
                    ),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (item.subtitle != null) {
                    Text(
                        item.subtitle,
                        style = TextStyle(
                            fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.8f),
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Gradient-based card — for items without images, matches image card visual style */
@Composable
private fun IconSelectionCard(
    item: SelectionItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    fixedHeight: Dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val offsetY by animateFloatAsState(
        targetValue = if (isPressed) 3f else 0f,
        animationSpec = tween(80),
    )

    val borderColor = if (isSelected) AppColors.Plum else Color.Transparent
    val shadowColor = if (isSelected) AppColors.PlumDk else AppColors.Pebble
    val cardHeight = if (fixedHeight != Dp.Unspecified) fixedHeight else 120.dp

    Box(
        modifier = Modifier
            .graphicsLayer { translationY = offsetY * density }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        Box(
            modifier = Modifier.matchParentSize()
                .offset(y = if (!isPressed) 4.dp else 1.dp)
                .background(shadowColor, RoundedCornerShape(14.dp)),
        )

        Box(
            modifier = Modifier.fillMaxWidth().height(cardHeight)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(Color(0xFF4A3F5C), Color(0xFF2A2235)),
                    ),
                )
                .border(if (isSelected) 2.5.dp else 0.dp, borderColor, RoundedCornerShape(14.dp)),
        ) {
            // Check circle — top right
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .background(
                        if (isSelected) AppColors.Plum else Color.White.copy(alpha = 0.15f),
                        CircleShape,
                    )
                    .border(2.dp, if (isSelected) AppColors.PlumDk else Color.White.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Text("\u2713", style = TextStyle(fontWeight = FontWeight.Black, fontSize = 10.sp, color = Color.White))
                }
            }

            // Text — bottom left
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            ) {
                Text(
                    item.name,
                    style = TextStyle(
                        fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                        color = Color.White, lineHeight = 15.sp,
                    ),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (item.subtitle != null) {
                    Text(
                        item.subtitle,
                        style = TextStyle(
                            fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.7f),
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Slim horizontal card — name + subtitle on the left, radio on the right */
@Composable
fun SlimSelectionCard(
    name: String,
    subtitle: String? = null,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) AppColors.Plum else AppColors.Pebble
    val bgColor = if (isSelected) AppColors.PlumLt else AppColors.White

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .background(bgColor, RoundedCornerShape(12.dp))
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    color = if (isSelected) AppColors.PlumDk else AppColors.Ink,
                ),
                maxLines = 1,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        color = AppColors.Ink3,
                    ),
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.size(8.dp))

        Box(
            modifier = Modifier
                .size(18.dp)
                .background(
                    if (isSelected) AppColors.Plum else Color.Transparent,
                    CircleShape,
                )
                .border(2.dp, if (isSelected) AppColors.PlumDk else AppColors.Pebble, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (isSelected) {
                Text(
                    "\u2713",
                    style = TextStyle(
                        fontWeight = FontWeight.Black,
                        fontSize = 9.sp,
                        color = AppColors.White,
                    ),
                )
            }
        }
    }
}

/**
 * Standard chunky onboarding CTA. Matches the event-detail primary CTA style:
 * a depth shadow the face sinks onto when pressed, a 2dp border in the shadow
 * color, a press scale-down, and a haptic tap on click.
 */
@Composable
fun OnboardingButton(
    text: String,
    color: Color,
    shadowColor: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    textColor: Color = AppColors.White,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val offsetY by animateFloatAsState(
        targetValue = if (isPressed) 4f else 0f,
        animationSpec = tween(80),
    )
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(80),
    )
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = offsetY * density
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.45f
            },
    ) {
        // Depth shadow — sits 4dp below, rising to 1dp as the face is pressed down.
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = if (!isPressed) 4.dp else 1.dp)
                .background(shadowColor, AppShapes.CardSmall),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(color, AppShapes.CardSmall)
                .border(2.dp, shadowColor, AppShapes.CardSmall)
                .clip(AppShapes.CardSmall)
                .then(
                    if (enabled && !isLoading) {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onClick()
                            },
                        )
                    } else Modifier
                )
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = textColor,
                    strokeWidth = 2.5.dp,
                )
            } else
            Text(
                text,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                    color = textColor,
                ),
            )
        }
    }
}
