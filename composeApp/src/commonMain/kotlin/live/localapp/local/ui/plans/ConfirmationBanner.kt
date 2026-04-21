package live.localapp.local.ui.plans

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

private val AVATAR_COLORS = listOf(
    0xFFFF6B6B, 0xFF4ECBA0, 0xFFFFB830, 0xFF6B9FFF, 0xFFCC6BFF,
    0xFFFF6BB5, 0xFF6BFFD4, 0xFF9F6BFF, 0xFFFF9F6B, 0xFF6BCCFF,
    0xFFE85D5D, 0xFF3DB890, 0xFFE5A020, 0xFF5A8EE5, 0xFFBB5AEE,
    0xFFEE5AA5,
)

/**
 * Confirmation tracker banner for plans.
 *
 * State A: groupSize known → shows X/Y confirmed with avatar circles
 * State B: groupSize unknown → shows "Just you" with number picker
 * State C: all confirmed → celebratory message
 *
 * Share button always visible.
 */
@Composable
fun ConfirmationBanner(
    groupSize: Int?,
    confirmedCount: Int,
    creatorInitial: String,
    isCreator: Boolean = true,
    onSetGroupSize: (Int) -> Unit,
    onShare: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 8.dp)
            .background(Color.White, RoundedCornerShape(16.dp))
            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        if (groupSize != null && groupSize > 1) {
            // State A or C: group size known — compact avatar stack + inline progress
            val allConfirmed = confirmedCount >= groupSize

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Compact overlapping avatar stack — max 4 visible, creator first, then "+N"
                val maxVisible = 4
                val visibleConfirmed = minOf(confirmedCount, maxVisible)
                val showMoreBadge = groupSize > maxVisible
                Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                    for (i in 0 until visibleConfirmed) {
                        val color = Color(AVATAR_COLORS[i % AVATAR_COLORS.size])
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .background(color, CircleShape)
                                .border(2.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (i == 0) creatorInitial else "?",
                                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 11.sp, color = Color.White),
                            )
                        }
                    }
                    // Empty slots shown as muted outlined circles (cap at maxVisible total)
                    val remainingVisible = maxOf(0, minOf(groupSize, maxVisible) - visibleConfirmed)
                    for (i in 0 until remainingVisible) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .background(AppColors.Mist, CircleShape)
                                .border(2.dp, Color.White, CircleShape),
                        )
                    }
                    // "+N" badge when group exceeds maxVisible
                    if (showMoreBadge) {
                        val extra = groupSize - maxVisible
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .background(AppColors.Mist, CircleShape)
                                .border(2.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "+$extra",
                                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 9.sp, color = AppColors.Ink3),
                            )
                        }
                    }
                }

                Spacer(Modifier.width(10.dp))

                // Progress text
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (allConfirmed) "All confirmed" else "$confirmedCount of $groupSize confirmed",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp,
                            color = if (allConfirmed) Color(0xFF2D9F73) else AppColors.Ink,
                        ),
                    )
                    if (allConfirmed) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color(0xFF2D9F73))
                            Spacer(Modifier.width(3.dp))
                            Text(
                                "Your crew is locked in",
                                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Color(0xFF2D9F73)),
                            )
                        }
                    } else {
                        Text(
                            "Share to get the rest on board",
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3),
                        )
                    }
                }
            }
        } else {
            // State B: group size unknown
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Creator avatar
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color(AVATAR_COLORS[0]), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        creatorInitial,
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 12.sp, color = Color.White),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "Just you so far",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink),
                )
            }

            // Number picker only shown to the creator — attendees can't set group size
            if (isCreator) {
                Spacer(Modifier.height(12.dp))

                Text(
                    "How many people total?",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
                )

                Spacer(Modifier.height(8.dp))

                // Number picker: 2-16
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (n in 2..16) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(AppColors.Mist, RoundedCornerShape(10.dp))
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onSetGroupSize(n) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$n",
                                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = AppColors.Ink),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Share button — always visible
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.Sky)
                .clickable(onClick = onShare)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                Text(
                    "Share with your group",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color.White),
                )
            }
        }
    }
}
