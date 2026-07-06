package live.localapp.local.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.domain.model.DraftTrayEvent
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

/**
 * Draft picks drawer — mirrors the iOS `draftDrawer` (ChatView.swift):
 *   • header "Your picks (N)" / "Add to your plan (N)"
 *   • day tabs when the picks span more than one day
 *   • a scrollable ~2-row pick list (title + "time · venue", removable ✕)
 *   • two Duolingo-style CTAs: Save Events (secondary) + Create Plan / Add to
 *     Plan (primary)
 *
 * Hidden when [tray] is empty.
 */
@Composable
fun DraftPlanTray(
    tray: List<DraftTrayEvent>,
    onRemove: (String) -> Unit,
    onSaveEvents: () -> Unit,
    onCreatePlan: () -> Unit,
    addingToPlan: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (tray.isEmpty()) return
    val count = tray.size
    // Chronological so day tabs + rows read earliest-first (undated sort last).
    val picks = remember(tray) { tray.sortedWith(compareBy(nullsLast()) { it.startAt }) }
    // Day tabs only when picks span >1 day. dateLabel is day-only ("Sat May 25")
    // — NOT the time-bearing label, so two same-day picks make one tab.
    val dayKeys = remember(picks) { picks.map { it.dateLabel }.filter { it.isNotBlank() }.distinct() }
    val showDayTabs = dayKeys.size > 1
    var dayIndex by remember { mutableStateOf(0) }
    // Auto-navigate to the day of the most-recently-added pick (tray is append-
    // order, so the last element is the newest selection). Keyed on that id so a
    // fresh pick from a new day jumps its tab into view; manual tab taps still
    // win until the next add. Mirrors the iOS draftDayIndex behaviour.
    val newestPickId = tray.lastOrNull()?.eventId
    LaunchedEffect(newestPickId, dayKeys) {
        val newestDay = tray.lastOrNull()?.dateLabel
        val idx = dayKeys.indexOf(newestDay)
        if (idx >= 0) dayIndex = idx
    }
    val safeDay = dayIndex.coerceIn(0, (dayKeys.size - 1).coerceAtLeast(0))
    val activeDay = dayKeys.getOrNull(safeDay) ?: ""
    val visiblePicks = if (showDayTabs) picks.filter { it.dateLabel == activeDay } else picks

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(Color.White)
            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            if (addingToPlan) "Add to your plan ($count)" else "Your picks ($count)",
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                color = AppColors.Ink3,
                letterSpacing = 0.4.sp,
            ),
        )

        if (showDayTabs) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                dayKeys.forEachIndexed { idx, day ->
                    val active = idx == safeDay
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (active) AppColors.Coral else AppColors.Coral.copy(alpha = 0.10f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { dayIndex = idx }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    ) {
                        Text(
                            day,
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (active) Color.White else AppColors.Ink,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        // ~2 rows visible, scroll for more (matches iOS min(count*46, 94)).
        val listH = minOf(maxOf(visiblePicks.size, 1) * 46, 94)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(listH.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            visiblePicks.forEach { ev ->
                PickRow(ev, onRemove = { onRemove(ev.eventId) })
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DuoButton("Save Events", primary = false, modifier = Modifier.weight(1f), onClick = onSaveEvents)
            DuoButton(
                if (addingToPlan) "Add to Plan" else "Create Plan",
                primary = true,
                modifier = Modifier.weight(1f),
                onClick = onCreatePlan,
            )
        }
    }
}

@Composable
private fun PickRow(ev: DraftTrayEvent, onRemove: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                ev.title,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOf(ev.timeLabel, ev.venueName ?: "").filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = AppColors.Ink3),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(50))
                .background(AppColors.Snow)
                .border(1.dp, AppColors.Pebble, RoundedCornerShape(50))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onRemove,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "Remove", tint = AppColors.Ink3, modifier = Modifier.size(11.dp))
        }
    }
}

/**
 * Chunky "Duolingo" CTA — a darker edge layer peeking 4dp below a face with a
 * matching stroke, for the pressed-depth look. Mirrors the iOS `duoButton`.
 */
@Composable
private fun DuoButton(
    title: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val face = if (primary) AppColors.Coral else AppColors.CoralLt
    val edge = if (primary) Color(0xFFE85D5D) else AppColors.Coral.copy(alpha = 0.30f)
    Box(
        modifier = modifier
            .height(54.dp) // 50 face + 4 lip
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        // 3D lip — behind the face, peeking 4dp below it.
        Box(
            Modifier.fillMaxWidth().height(50.dp).align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(14.dp)).background(edge),
        )
        // Face.
        Box(
            Modifier.fillMaxWidth().height(50.dp).align(Alignment.TopCenter)
                .clip(RoundedCornerShape(14.dp)).background(face)
                .border(1.5.dp, edge, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp,
                    color = if (primary) Color.White else AppColors.Coral,
                ),
            )
        }
    }
}
