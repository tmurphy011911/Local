package live.localapp.local.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

/**
 * "What are you looking to do?" picker shown inside the chat on open (while
 * [ChatUiState.boIntent] is null). Picking a goal calls [onPick], which the
 * host wires to [ChatViewModel.setBoIntent] — steering Bo's framing + planning
 * behavior for the rest of the session. Mirrors the iOS `BoIntentSheet`.
 */
@Composable
fun BoIntentPicker(onPick: (BoIntent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 18.dp)
            .padding(top = 8.dp, bottom = 28.dp),
    ) {
        Text(
            "What are you looking to do?",
            style = TextStyle(
                fontFamily = AppFonts.Heading,
                fontWeight = FontWeight.Black,
                fontSize = 24.sp,
                color = AppColors.Ink,
            ),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Tell me where to point you and I'll take it from there.",
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = AppColors.Ink3,
            ),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(20.dp))

        IntentOption(
            icon = Icons.Outlined.Search,
            title = "Find events",
            subtitle = "See what's happening near you",
            onClick = { onPick(BoIntent.FIND_EVENTS) },
        )
        Spacer(Modifier.height(12.dp))
        IntentOption(
            icon = Icons.Outlined.CalendarMonth,
            title = "Create a plan",
            subtitle = "Build an itinerary to save & share",
            onClick = { onPick(BoIntent.CREATE_PLAN) },
        )
        Spacer(Modifier.height(12.dp))
        IntentOption(
            icon = Icons.Outlined.Explore,
            title = "I'm just browsing",
            subtitle = "Just looking around for now",
            onClick = { onPick(BoIntent.BROWSING) },
        )
    }
}

@Composable
private fun IntentOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.Snow)
            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(50))
                .background(AppColors.Coral.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = AppColors.Coral, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = AppColors.Ink,
                ),
            )
            Text(
                subtitle,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = AppColors.Ink3,
                ),
            )
        }
        Icon(
            Icons.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = AppColors.Ink3,
            modifier = Modifier.size(20.dp),
        )
    }
}
