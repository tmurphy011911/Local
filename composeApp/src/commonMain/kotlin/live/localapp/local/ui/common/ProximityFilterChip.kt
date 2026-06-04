package live.localapp.local.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

// 5 and 10 mi removed — too narrow for our event density per metro; users
// who picked them saw mostly empty carousels. Jump straight from 3 mi
// (walkable) to 25 mi (full metro).
private val RADIUS_OPTIONS = listOf<Double?>(null, 1.0, 3.0, 25.0)

private fun radiusLabel(miles: Double?): String = when (miles) {
    null  -> "Any distance"
    1.0   -> "1 mi"
    3.0   -> "3 mi"
    25.0  -> "25 mi"
    else  -> "${miles.toInt()} mi"
}

@Composable
fun ProximityFilterChip(
    selectedRadius: Double?,
    onSelect: (Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val isActive = selectedRadius != null
    val chipLabel = if (isActive) radiusLabel(selectedRadius) else "Radius"

    Column(modifier = modifier) {
        // Main chip
        Row(
            modifier = Modifier
                .shadow(if (isActive) 4.dp else 2.dp, RoundedCornerShape(20.dp))
                .background(
                    if (isActive) AppColors.Sky else Color.White,
                    RoundedCornerShape(20.dp),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                Icons.Outlined.Place,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = if (isActive) Color.White else AppColors.Sky,
            )
            Text(
                chipLabel,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (isActive) Color.White else AppColors.Ink2,
                ),
            )
        }

        // Expanded options
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RADIUS_OPTIONS.forEach { option ->
                    val isSelected = option == selectedRadius
                    Box(
                        modifier = Modifier
                            .shadow(if (isSelected) 4.dp else 2.dp, RoundedCornerShape(20.dp))
                            .background(
                                if (isSelected) AppColors.Sky else Color.White,
                                RoundedCornerShape(20.dp),
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                onSelect(option)
                                expanded = false
                            }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    ) {
                        Text(
                            radiusLabel(option),
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (isSelected) Color.White else AppColors.Ink2,
                            ),
                        )
                    }
                }
            }
        }
    }
}
