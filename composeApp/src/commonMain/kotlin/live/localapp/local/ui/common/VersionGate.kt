package live.localapp.local.ui.common

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

/**
 * Dismissable banner for soft update nudge.
 * Shows below the header on the home page.
 */
@Composable
fun UpdateAvailableBanner(
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.SkyLt)
            .clickable(onClick = onUpdate)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp), tint = AppColors.Sky)
        Spacer(Modifier.width(8.dp))
        Text(
            "A new version of Local is available",
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = AppColors.Sky),
            modifier = Modifier.weight(1f),
        )
        Text(
            "Update",
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = Color.White),
            modifier = Modifier
                .background(AppColors.Sky, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
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

/**
 * Full-screen force update blocker.
 * Prevents all app usage until user updates.
 */
@Composable
fun ForceUpdateScreen(
    message: String,
    onUpdate: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.SystemUpdate,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = AppColors.Coral,
            )

            Spacer(Modifier.height(24.dp))

            Text(
                "Update Required",
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Black,
                    fontSize = 24.sp,
                    color = AppColors.Ink,
                ),
            )

            Spacer(Modifier.height(12.dp))

            Text(
                message,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = AppColors.Ink3,
                    lineHeight = 22.sp,
                ),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(AppColors.Coral, Color(0xFFE85D5D))))
                    .clickable(onClick = onUpdate)
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Update Now",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        color = Color.White,
                    ),
                )
            }
        }
    }
}
