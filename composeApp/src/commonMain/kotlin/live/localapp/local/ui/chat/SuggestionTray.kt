package live.localapp.local.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import live.localapp.local.domain.model.BoStructuredResponse
import live.localapp.local.domain.model.Event
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

/**
 * Renders Bo's `present_suggestions` tool result as a structured tray of
 * event candidates. Matches iOS `SuggestionTrayView`: each day/category
 * group is a horizontal peek-paging carousel of fixed-width cards, with a
 * trailing "Find more" tile that pulls additional events inline (no chat
 * round-trip). Every event is a real object the user can `+` to the draft
 * tray with one tap.
 *
 * The component is purely presentational. The host (ChatScreen) supplies
 * already-hydrated `Event` objects keyed by event_id, the load-more state,
 * and the find-more callback.
 */
@Composable
fun SuggestionTray(
    messageId: String,
    suggestions: BoStructuredResponse.Suggestions,
    eventsById: Map<String, Event>,           // hydrated by repo
    onAddToTray: (Event) -> Unit,
    onOpenEvent: (Event) -> Unit,
    onFooterAction: (BoStructuredResponse.Suggestions.Action) -> Unit,
    isInTray: (String) -> Boolean,
    isGroupLoading: (String) -> Boolean = { false },
    isGroupExhausted: (String) -> Boolean = { false },
    onFindMore: (dayKey: String?, categoryKey: String?) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val isEmpty = suggestions.groups.isEmpty() || suggestions.groups.all { it.events.isEmpty() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(16.dp))
            .padding(vertical = 14.dp),
    ) {
        // Headline
        Text(
            suggestions.headline,
            style = TextStyle(
                fontFamily = AppFonts.Heading,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                color = AppColors.Ink,
            ),
            modifier = Modifier.padding(horizontal = 14.dp),
        )

        // Empty state — when search returned nothing relevant, Bo can still
        // call this with empty groups + footer.actions so the user can
        // broaden in one tap.
        if (isEmpty) {
            Spacer(Modifier.height(8.dp))
            Text(
                "No matches yet — want me to broaden the search?",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = AppColors.Ink3,
                ),
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        } else {
            suggestions.groups.forEach { group ->
                Spacer(Modifier.height(12.dp))
                if (group.label.isNotBlank()) {
                    Text(
                        group.label,
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            color = AppColors.Ink3,
                            letterSpacing = 0.6.sp,
                        ),
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // Compute the find-more group key. Day-grouped trays key on
                // the first event's local date; category-grouped trays key on
                // the label; ungrouped (NONE) trays get no Find-more tile.
                val (dayKey, categoryKey) = when (suggestions.groupBy) {
                    BoStructuredResponse.Suggestions.GroupBy.DAY ->
                        localDateKey(group.events.firstOrNull()?.let { eventsById[it.eventId] }) to null
                    BoStructuredResponse.Suggestions.GroupBy.CATEGORY ->
                        null to group.label.takeIf { it.isNotBlank() }
                    BoStructuredResponse.Suggestions.GroupBy.NONE ->
                        null to null
                }
                val groupKey = dayKey ?: categoryKey ?: ""

                val listState = rememberLazyListState()
                LazyRow(
                    state = listState,
                    flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) {
                    items(group.events) { eventRef ->
                        val ev = eventsById[eventRef.eventId]
                        if (ev != null) {
                            SuggestionCard(
                                event = ev,
                                isInTray = isInTray(eventRef.eventId),
                                onAddToTray = { onAddToTray(ev) },
                                onOpenEvent = { onOpenEvent(ev) },
                                modifier = Modifier.width(270.dp),
                            )
                        }
                    }
                    if (groupKey.isNotEmpty()) {
                        item {
                            FindMoreTile(
                                isLoading = isGroupLoading(groupKey),
                                isExhausted = isGroupExhausted(groupKey),
                                onClick = { onFindMore(dayKey, categoryKey) },
                                modifier = Modifier.width(180.dp),
                            )
                        }
                    }
                }
            }
        }

        // Footer chip strip
        if (suggestions.footer != null && suggestions.footer.actions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            suggestions.footer.text?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = AppColors.Ink3,
                    ),
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                Spacer(Modifier.height(6.dp))
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) {
                items(suggestions.footer.actions) { action ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(AppColors.SkyLt)
                            .border(1.dp, AppColors.Sky, RoundedCornerShape(50))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onFooterAction(action) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            actionLabel(action),
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = AppColors.Sky,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    event: Event,
    isInTray: Boolean,
    onAddToTray: () -> Unit,
    onOpenEvent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.SkyLt.copy(alpha = 0.35f))
            .border(1.dp, AppColors.Pebble, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpenEvent,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                event.title,
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = AppColors.Ink,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            val meta = listOfNotNull(
                event.dateDisplay.takeIf { it.isNotBlank() },
                event.distanceDisplay.takeIf { it.isNotBlank() },
                event.venueName?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = AppColors.Ink3,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(8.dp))
        AddToTrayButton(isInTray = isInTray, onClick = onAddToTray)
    }
}

@Composable
private fun AddToTrayButton(isInTray: Boolean, onClick: () -> Unit) {
    val bg = if (isInTray) AppColors.Sage else AppColors.Coral
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (!isInTray) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isInTray) Icons.Outlined.Check else Icons.Outlined.Add,
            contentDescription = if (isInTray) "In plan selection" else "Add to plan selection",
            tint = Color.White,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * Trailing tile in a carousel that pulls more events for the day/category
 * inline. Mirrors iOS `FindMoreTile`: loading spinner, exhausted checkmark,
 * or a "Find more" call-to-action.
 */
@Composable
private fun FindMoreTile(
    isLoading: Boolean,
    isExhausted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !isLoading && !isExhausted
    val bg = if (isExhausted) AppColors.Pebble.copy(alpha = 0.25f) else AppColors.SkyLt.copy(alpha = 0.5f)
    val borderColor = (if (isExhausted) AppColors.Pebble else AppColors.Sky).copy(alpha = 0.5f)
    val tint = if (isExhausted) AppColors.Ink3 else AppColors.Sky
    Row(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            isLoading -> CircularProgressIndicator(
                color = AppColors.Sky,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
            isExhausted -> Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = AppColors.Ink3,
                modifier = Modifier.size(18.dp),
            )
            else -> Icon(
                Icons.Outlined.ArrowForward,
                contentDescription = null,
                tint = AppColors.Sky,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                isLoading -> "Looking…"
                isExhausted -> "No more events"
                else -> "Find more"
            },
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = tint,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Local-date string ("2026-06-05") of an event's start, for the day group key. */
private fun localDateKey(event: Event?): String? {
    val s = event?.startAt ?: return null
    return try {
        Instant.parse(s).toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
    } catch (_: Throwable) {
        null
    }
}

private fun actionLabel(action: BoStructuredResponse.Suggestions.Action): String = when (action) {
    BoStructuredResponse.Suggestions.Action.SEARCH_WIDER -> "Search wider"
    BoStructuredResponse.Suggestions.Action.DIFFERENT_CATEGORY -> "Different category"
    BoStructuredResponse.Suggestions.Action.LATE_NIGHT -> "Late night"
    BoStructuredResponse.Suggestions.Action.DIFFERENT_DAY -> "Different day"
    BoStructuredResponse.Suggestions.Action.MORE_RESULTS -> "More results"
    BoStructuredResponse.Suggestions.Action.BUILD_FROM_WEB -> "Build a plan"
}
