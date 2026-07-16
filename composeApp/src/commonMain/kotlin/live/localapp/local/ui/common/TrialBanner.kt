package live.localapp.local.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

/**
 * Home-lobby countdown nudging the user to upgrade before their Local Pro trial
 * ends. Shown when the trial has < 14 days left.
 *
 * Dismissibility is decided by the caller (14–7 days remaining → dismissible;
 * under 7 days → non-dismissible, so [dismissible] is false and no close
 * affordance renders). Tapping the row or the "Upgrade Now" pill opens the
 * paywall via [onUpgrade].
 */
@Composable
fun TrialCountdownBanner(
    daysLeft: Int,
    dismissible: Boolean,
    onUpgrade: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dayLabel = if (daysLeft == 1) "day" else "days"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.BgCoralStart)
            .clickable(onClick = onUpgrade)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Schedule,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = AppColors.Ink2,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$daysLeft $dayLabel left on your Local Pro trial",
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = AppColors.Ink,
            ),
            modifier = Modifier.weight(1f),
        )
        // Chunky 3D "Duolingo" pill — a deeper-coral edge peeks 3.dp below the
        // face, the shared house-style depth scaled down for this inline pill.
        Box {
            Box(
                Modifier.matchParentSize()
                    .background(Color(0xFFC94A4A), RoundedCornerShape(9.dp)),
            )
            Text(
                "Upgrade Now",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp,
                    color = Color.White,
                ),
                modifier = Modifier
                    .padding(bottom = 3.dp)
                    .background(AppColors.Coral, RoundedCornerShape(9.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        if (dismissible) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Dismiss",
                modifier = Modifier
                    .size(16.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
                tint = AppColors.Ink3,
            )
        }
    }
}
