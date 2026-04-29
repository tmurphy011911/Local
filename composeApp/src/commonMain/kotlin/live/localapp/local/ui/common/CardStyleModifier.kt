package live.localapp.local.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import live.localapp.local.ui.theme.AppColors

/**
 * The canonical Local card surface — white background, hairline pebble
 * border, rounded corners, and a flat offset "stacked block" shadow that
 * gives the card a soft 3D feel without using Material's elevation glow.
 *
 * Use this instead of `.shadow(...)` so all save-list / carousel cards
 * share the same look. Pair with [Modifier.pressable] for the tap
 * haptic + scale animation.
 *
 * Renders the shadow rectangle BEFORE the white background via drawBehind,
 * offset by [shadowOffset] dp on the y-axis. The shadow rectangle is the
 * same shape as the card so the effect reads as a flat plane underneath.
 */
fun Modifier.localCardSurface(
    cornerRadius: Dp = 14.dp,
    borderWidth: Dp = 1.5.dp,
    borderColor: Color = AppColors.Pebble,
    shadowColor: Color = AppColors.Pebble,
    shadowOffset: Dp = 4.dp,
    background: Color = AppColors.White,
): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .drawBehind {
            val r = cornerRadius.toPx()
            drawRoundRect(
                color = shadowColor,
                topLeft = Offset(0f, shadowOffset.toPx()),
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(r, r),
            )
        }
        .background(background, shape)
        .border(borderWidth, borderColor, shape)
        .clip(shape)
}
