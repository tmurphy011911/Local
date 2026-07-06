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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Map
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import live.localapp.local.domain.model.BoStructuredResponse
import live.localapp.local.domain.model.Event
import live.localapp.local.platform.BoResultsMapView
import live.localapp.local.platform.venuePins
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
    isDismissed: (String) -> Boolean = { false },
    onDismiss: (String) -> Unit = {},
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
                // Hide events the user dismissed (✕); drop the group entirely
                // if it empties out.
                val visibleEvents = group.events.filter { !isDismissed(it.eventId) }
                if (visibleEvents.isNotEmpty()) {
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
                        localDateKey(visibleEvents.firstOrNull()?.let { eventsById[it.eventId] }) to null
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
                    items(visibleEvents) { eventRef ->
                        val ev = eventsById[eventRef.eventId]
                        if (ev != null) {
                            SuggestionCard(
                                event = ev,
                                isInTray = isInTray(eventRef.eventId),
                                onAddToTray = { onAddToTray(ev) },
                                onDismiss = { onDismiss(eventRef.eventId) },
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
                } // end if visibleEvents.isNotEmpty()
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
    onDismiss: () -> Unit,
    onOpenEvent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.SkyLt.copy(alpha = 0.35f))
            .border(1.dp, AppColors.Pebble, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpenEvent,
            ),
    ) {
        // Text — inset so the corner controls (❤️ top-right, ✕ bottom-left)
        // never overlap it.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 14.dp, end = 36.dp),
            verticalArrangement = Arrangement.Center,
        ) {
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

        // ❤️ save — top-right.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(26.dp)
                .clip(RoundedCornerShape(50))
                .background(if (isInTray) AppColors.Sage else AppColors.Coral)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (!isInTray) onAddToTray()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isInTray) Icons.Outlined.Check else Icons.Outlined.Add,
                contentDescription = if (isInTray) "Added" else "Add",
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
        }

        // ✕ dismiss — bottom-right, directly beneath the + / ✓.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .size(20.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .border(1.dp, AppColors.Pebble, RoundedCornerShape(50))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDismiss()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Not interested",
                tint = AppColors.Ink3,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

/**
 * Full-width list row for BoResultsMap's list mode — thumbnail + text +
 * side-by-side +/✕ circles. Mirrors the iOS `EventListRow` (60pt image, 30pt
 * buttons). Swipe is added by the caller via SwipeToDismissBox.
 */
@Composable
private fun SuggestionListRow(
    event: Event,
    isInTray: Boolean,
    onAddToTray: () -> Unit,
    onDismiss: () -> Unit,
    onOpenEvent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.dp, AppColors.Pebble, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpenEvent,
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 60pt thumbnail (skyLt placeholder behind the image).
        Box(
            modifier = Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)).background(AppColors.SkyLt),
        ) {
            if (!event.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = event.imageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                event.title,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AppColors.Ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            event.venueName?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = AppColors.Ink3),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val meta = listOfNotNull(
                event.dateDisplay.takeIf { it.isNotBlank() },
                event.distanceDisplay.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Medium, fontSize = 11.sp, color = AppColors.Ink3),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        // + add (coral, → sage ✓ when added). 30pt to match iOS.
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (isInTray) AppColors.Sage else AppColors.Coral)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (!isInTray) onAddToTray()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isInTray) Icons.Outlined.Check else Icons.Outlined.Add,
                contentDescription = if (isInTray) "Added" else "Add",
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        // ✕ dismiss (white, pebble ring). 30pt.
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(1.dp, AppColors.Pebble, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDismiss()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Not interested",
                tint = AppColors.Ink3,
                modifier = Modifier.size(13.dp),
            )
        }
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

// ═══════════════════════════════════════════════════════════════════════════
// Bo results MAP (Android) — parity with the iOS BoResultsMapView. Header +
// map/list toggle + day/category tabs + a card carousel synced to venue pins.
// One pin per venue; the carousel disambiguates multi-event venues. Reuses
// SuggestionCard/FindMoreTile and the tray's empty state. The GoogleMap surface
// is the platform expect/actual BoResultsMapView (Android renders it; iOS is a
// no-op since iOS chat is native SwiftUI).
// ═══════════════════════════════════════════════════════════════════════════

private enum class BoResultsMode { MAP, LIST }

@Composable
fun BoResultsMap(
    messageId: String,
    suggestions: BoStructuredResponse.Suggestions,
    eventsById: Map<String, Event>,
    onAddToTray: (Event) -> Unit,
    onOpenEvent: (Event) -> Unit,
    onFooterAction: (BoStructuredResponse.Suggestions.Action) -> Unit,
    isInTray: (String) -> Boolean,
    isDismissed: (String) -> Boolean = { false },
    onDismiss: (String) -> Unit = {},
    isGroupLoading: (String) -> Boolean = { false },
    isGroupExhausted: (String) -> Boolean = { false },
    onFindMore: (dayKey: String?, categoryKey: String?) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    // Groups with at least one visible event. Events that are dismissed (✕) or
    // already added to the draft sheet (isInTray) drop out of the results —
    // added items live in the sheet; removing them there restores them here
    // (this is reactive on isInTray/isDismissed). Mirrors iOS.
    val visibleGroups = suggestions.groups.map { g ->
        g to g.events.mapNotNull { ref -> eventsById[ref.eventId] }
            .filter { !isDismissed(it.id) && !isInTray(it.id) }
    }.filter { it.second.isNotEmpty() }

    // Nothing to place → fall back to the list tray (which owns the empty state).
    if (visibleGroups.isEmpty()) {
        SuggestionTray(
            messageId, suggestions, eventsById, onAddToTray, onOpenEvent, onFooterAction,
            isInTray, isDismissed, onDismiss, isGroupLoading, isGroupExhausted, onFindMore, modifier,
        )
        return
    }

    var groupIndex by remember(messageId) { mutableStateOf(0) }
    var mode by remember(messageId) { mutableStateOf(BoResultsMode.MAP) }
    val safeIndex = groupIndex.coerceIn(0, visibleGroups.size - 1)
    val (group, events) = visibleGroups[safeIndex]
    val pins = venuePins(events)

    var focusedEventId by remember(messageId, safeIndex) { mutableStateOf(events.firstOrNull()?.id) }
    // Effective focus tolerates a just-dismissed card without writing state
    // during composition — falls back to the first remaining event.
    val effectiveFocusedId = focusedEventId?.takeIf { id -> events.any { it.id == id } } ?: events.firstOrNull()?.id
    val focusedPinId = pins.firstOrNull { it.eventIds.contains(effectiveFocusedId) }?.id

    val carouselState = rememberLazyListState()
    // Carousel settle → focus the leading card.
    LaunchedEffect(carouselState, safeIndex) {
        snapshotFlow { carouselState.isScrollInProgress to carouselState.firstVisibleItemIndex }
            .collect { (scrolling, idx) ->
                if (!scrolling) events.getOrNull(idx)?.let { focusedEventId = it.id }
            }
    }
    // Focus (from a pin tap) → scroll the carousel to it.
    LaunchedEffect(effectiveFocusedId) {
        val idx = events.indexOfFirst { it.id == effectiveFocusedId }
        if (idx >= 0 && idx != carouselState.firstVisibleItemIndex && !carouselState.isScrollInProgress) {
            try { carouselState.animateScrollToItem(idx) } catch (_: Throwable) {}
        }
    }

    // List-mode scroll — reset to the top whenever the mode or day tab changes
    // (otherwise switching lands you at the bottom of the previous list).
    val listScrollState = rememberLazyListState()
    LaunchedEffect(safeIndex, mode) { runCatching { listScrollState.scrollToItem(0) } }

    val showMap = mode == BoResultsMode.MAP && pins.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(16.dp))
            .padding(vertical = 14.dp),
    ) {
        // Header: title + map/list toggle.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                suggestions.headline,
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 18.sp, color = AppColors.Ink),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (pins.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                ModeToggle(mode = mode, onMode = { mode = it })
            }
        }

        // Day/category tabs — only when there's more than one group.
        if (visibleGroups.size > 1) {
            Spacer(Modifier.height(10.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) {
                itemsIndexed(visibleGroups) { i, pair ->
                    val selected = i == safeIndex
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) AppColors.Coral else AppColors.SkyLt)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { groupIndex = i }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            pair.first.label.ifBlank { "Events" },
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (selected) Color.White else AppColors.Sky,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (showMap) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .padding(horizontal = 14.dp)
                    .clip(RoundedCornerShape(14.dp)),
            ) {
                BoResultsMapView(
                    pins = pins,
                    focusedPinId = focusedPinId,
                    onPinTap = { pinId ->
                        pins.firstOrNull { it.id == pinId }?.eventIds?.firstOrNull()?.let { focusedEventId = it }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(10.dp))
            val (dayKey, categoryKey) = groupKeys(suggestions, group, events.firstOrNull())
            val groupKey = dayKey ?: categoryKey ?: ""
            LazyRow(
                state = carouselState,
                flingBehavior = rememberSnapFlingBehavior(lazyListState = carouselState),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) {
                items(events) { ev ->
                    SuggestionCard(
                        event = ev,
                        isInTray = isInTray(ev.id),
                        onAddToTray = { onAddToTray(ev) },
                        onDismiss = { onDismiss(ev.id) },
                        onOpenEvent = { onOpenEvent(ev) },
                        modifier = Modifier.width(270.dp),
                    )
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
        } else {
            // List mode (or no plottable coords) — vertical full-width cards.
            // Bounded height + its own LazyColumn so the list scrolls WITHIN the
            // component instead of scrolling the whole chat away (matches iOS).
            LazyColumn(
                state = listScrollState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(events, key = { it.id }) { ev ->
                    // Swipe RIGHT → add to plan, swipe LEFT → dismiss (mirrors the
                    // iOS EventListRow .swipeActions). The +/✕ buttons stay for
                    // discoverability. The row then leaves the list via the
                    // in-tray / dismissed filter, so we snap the box back (false).
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            when (value) {
                                SwipeToDismissBoxValue.StartToEnd -> { onAddToTray(ev); false }
                                SwipeToDismissBoxValue.EndToStart -> { onDismiss(ev.id); false }
                                else -> false
                            }
                        },
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            val add = dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd
                            val dismiss = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
                            val bg = when {
                                add -> AppColors.Coral
                                dismiss -> Color(0xFFE85D5D)
                                else -> Color.Transparent
                            }
                            Box(
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                                    .background(bg).padding(horizontal = 22.dp),
                                contentAlignment = if (add) Alignment.CenterStart else Alignment.CenterEnd,
                            ) {
                                if (add || dismiss) {
                                    Icon(
                                        if (add) Icons.Outlined.Add else Icons.Outlined.Close,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        },
                    ) {
                        SuggestionListRow(
                            event = ev,
                            isInTray = isInTray(ev.id),
                            onAddToTray = { onAddToTray(ev) },
                            onDismiss = { onDismiss(ev.id) },
                            onOpenEvent = { onOpenEvent(ev) },
                        )
                    }
                }
            }
        }

        // Footer suggestion chips intentionally removed (parity with iOS) — the
        // header, day tabs, and card actions are the only affordances.
    }
}

@Composable
private fun ModeToggle(mode: BoResultsMode, onMode: (BoResultsMode) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(AppColors.SkyLt)
            .padding(2.dp),
    ) {
        ModeToggleSeg(Icons.Outlined.Map, "Map view", mode == BoResultsMode.MAP) { onMode(BoResultsMode.MAP) }
        ModeToggleSeg(Icons.AutoMirrored.Outlined.List, "List view", mode == BoResultsMode.LIST) { onMode(BoResultsMode.LIST) }
    }
}

@Composable
private fun ModeToggleSeg(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) AppColors.Coral else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = desc,
            tint = if (selected) Color.White else AppColors.Sky,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Day-grouped trays key find-more on the first event's local date; category
 *  trays on the label; NONE gets no key. Mirrors SuggestionTray. */
private fun groupKeys(
    suggestions: BoStructuredResponse.Suggestions,
    group: BoStructuredResponse.Suggestions.Group,
    firstEvent: Event?,
): Pair<String?, String?> = when (suggestions.groupBy) {
    BoStructuredResponse.Suggestions.GroupBy.DAY -> localDateKey(firstEvent) to null
    BoStructuredResponse.Suggestions.GroupBy.CATEGORY -> null to group.label.takeIf { it.isNotBlank() }
    BoStructuredResponse.Suggestions.GroupBy.NONE -> null to null
}
