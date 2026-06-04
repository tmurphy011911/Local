package live.localapp.local.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.domain.model.ChipCategory
import live.localapp.local.domain.model.DraftTrayChip
import live.localapp.local.domain.model.DraftTrayEvent
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

/**
 * Persistent tray that sits above the keyboard collecting events the user
 * `+`'d across multiple Bo turns. The user accumulates events, then taps
 * Build to ship the entire list to Bo as one structured prompt.
 *
 * This is the load-bearing change from the Phase 6 spec — it replaces the
 * "user re-types which event they meant" failure path with explicit
 * event_id passing. Bo never has to guess.
 *
 * Hidden when [tray] is empty.
 */
@Composable
fun DraftPlanTray(
    tray: List<DraftTrayEvent>,
    chips: List<DraftTrayChip>,
    onRemove: (String) -> Unit,
    onChipTap: (DraftTrayChip) -> Unit,
    onBuild: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tray.isEmpty()) return

    val buildPriority = chips.firstOrNull { it.category == ChipCategory.BUILD }?.priority ?: 5

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(Color.White)
            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        // Header row: count + Build button
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "In your plan (${tray.size})",
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp,
                    color = AppColors.Ink,
                ),
            )
            // Build button — when priority is 1 (3+ events), promote visually.
            BuildButton(emphasized = buildPriority == 1, onClick = onBuild)
        }

        Spacer(Modifier.height(8.dp))

        // Stops list (compact one-liner per event)
        tray.forEach { ev ->
            DraftStopRow(ev, onRemove = { onRemove(ev.eventId) })
            Spacer(Modifier.height(4.dp))
        }

        // Dynamic chip strip — non-Build chips
        val nonBuildChips = chips.filter { it.category != ChipCategory.BUILD }
        if (nonBuildChips.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                items(nonBuildChips) { chip ->
                    DraftChipPill(chip, onClick = { onChipTap(chip) })
                }
            }
        }
    }
}

@Composable
private fun DraftStopRow(ev: DraftTrayEvent, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // date · time block
        Column(modifier = Modifier.padding(end = 10.dp)) {
            Text(
                ev.dateLabel,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp,
                    color = AppColors.Ink3,
                    letterSpacing = 0.4.sp,
                ),
            )
            Text(
                ev.timeLabel,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = AppColors.Coral,
                ),
            )
        }
        // Title + venue
        Column(modifier = Modifier.weight(1f)) {
            Text(
                ev.title,
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = AppColors.Ink,
                    lineHeight = 16.sp,
                ),
                maxLines = 1,
            )
            ev.venueName?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = AppColors.Ink3,
                    ),
                    maxLines = 1,
                )
            }
        }
        // [×] remove
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(50))
                .background(AppColors.Mist)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onRemove,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Remove",
                tint = AppColors.Ink3,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun BuildButton(emphasized: Boolean, onClick: () -> Unit) {
    val bg = if (emphasized) AppColors.Coral else AppColors.SkyLt
    val fg = if (emphasized) Color.White else AppColors.Sky
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            if (emphasized) "Build my plan →" else "Build →",
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.sp,
                color = fg,
            ),
        )
    }
}

@Composable
private fun DraftChipPill(chip: DraftTrayChip, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(AppColors.SkyLt)
            .border(1.dp, AppColors.Sky, RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            chip.label,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = AppColors.Sky,
            ),
        )
    }
}
