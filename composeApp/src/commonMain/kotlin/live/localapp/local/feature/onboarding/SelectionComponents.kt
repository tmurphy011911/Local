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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

data class SelectionItem(
    val id: String,
    val icon: ImageVector? = null,
    val name: String,
    val subtitle: String? = null,
    val tag: String? = null,
)

/** A selection card used in Neighbourhoods (3-col) / Venues / Categories (2-col) grids */
@Composable
fun SelectionCard(
    item: SelectionItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    fixedHeight: Dp = Dp.Unspecified,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val offsetY by animateFloatAsState(
        targetValue = if (isPressed) 3f else 0f,
        animationSpec = tween(80),
    )

    val borderColor = if (isSelected) AppColors.Plum else AppColors.Pebble
    val shadowColor = if (isSelected) AppColors.PlumDk else AppColors.Pebble
    val bgColor = if (isSelected) AppColors.PlumLt else AppColors.White

    val heightMod = if (fixedHeight != Dp.Unspecified) Modifier.height(fixedHeight) else Modifier

    Box(
        modifier = Modifier
            .graphicsLayer { translationY = offsetY * density }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        // Shadow
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = if (!isPressed) 4.dp else 1.dp)
                .background(shadowColor, RoundedCornerShape(16.dp)),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(heightMod)
                .background(bgColor, RoundedCornerShape(16.dp))
                .border(2.dp, borderColor, RoundedCornerShape(16.dp))
                .padding(11.dp),
        ) {
            // Top: icon/spacer + check
            Box(modifier = Modifier.fillMaxWidth()) {
                if (item.icon != null) {
                    Icon(
                        item.icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = if (isSelected) AppColors.PlumDk else AppColors.Ink2,
                    )
                } else {
                    Spacer(Modifier.size(22.dp))
                }

                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .align(Alignment.TopEnd)
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

            Spacer(Modifier.height(6.dp))

            Text(
                item.name,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    color = if (isSelected) AppColors.PlumDk else AppColors.Ink,
                    lineHeight = 16.sp,
                ),
            )

            if (item.tag != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    item.tag,
                    modifier = Modifier
                        .background(AppColors.Coral.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 9.sp,
                        color = AppColors.Coral,
                    ),
                )
            }

            if (item.subtitle != null) {
                Spacer(Modifier.height(if (item.tag != null) 2.dp else 4.dp))
                Text(
                    item.subtitle,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        color = AppColors.Ink3,
                        lineHeight = 14.sp,
                    ),
                )
            }
        }
    }
}

/** Standard chunky onboarding button with drop shadow */
@Composable
fun OnboardingButton(
    text: String,
    color: Color,
    shadowColor: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    textColor: Color = AppColors.White,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val offsetY by animateFloatAsState(
        targetValue = if (isPressed) 4f else 0f,
        animationSpec = tween(80),
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = offsetY * density
                alpha = if (enabled) 1f else 0.45f
            },
    ) {
        // Shadow
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = if (!isPressed) 4.dp else 0.dp)
                .background(shadowColor, RoundedCornerShape(14.dp)),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(color, RoundedCornerShape(14.dp))
                .then(
                    if (enabled) {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick,
                        )
                    } else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp,
                    color = textColor,
                ),
            )
        }
    }
}
