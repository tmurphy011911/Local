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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
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
import live.localapp.local.domain.model.BoStructuredResponse
import live.localapp.local.domain.model.Event
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

/**
 * Renders Bo's `present_suggestions` tool result as a structured tray of
 * event candidates. Replaces the prior pattern of describing events as
 * paragraph text in chat — every event is a real object the user can
 * `+` to the draft tray with one tap. Bo never has to disambiguate which
 * event the user meant from typed references.
 *
 * The component is purely presentational. The host (ChatScreen) supplies
 * already-hydrated `Event` objects keyed by event_id; this composable just
 * lays out the day/category groupings and emits add/tap callbacks.
 */
@Composable
fun SuggestionTray(
    suggestions: BoStructuredResponse.Suggestions,
    eventsById: Map<String, Event>,           // hydrated by repo
    onAddToTray: (Event) -> Unit,
    onOpenEvent: (Event) -> Unit,
    onFooterAction: (BoStructuredResponse.Suggestions.Action) -> Unit,
    isInTray: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
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
        )

        // Empty state — when search returned nothing relevant, Bo can still
        // call this with empty groups + footer.actions so the user can
        // broaden in one tap. Show a friendly empty message rather than a
        // bare card.
        if (suggestions.groups.isEmpty() || suggestions.groups.all { it.events.isEmpty() }) {
            Spacer(Modifier.height(8.dp))
            Text(
                "No matches yet — want me to broaden the search?",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = AppColors.Ink3,
                ),
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
                    )
                    Spacer(Modifier.height(6.dp))
                }
                group.events.forEach { eventRef ->
                    val ev = eventsById[eventRef.eventId]
                    if (ev != null) {
                        SuggestionRow(
                            event = ev,
                            rationale = eventRef.rationale,
                            isInTray = isInTray(eventRef.eventId),
                            onAddToTray = { onAddToTray(ev) },
                            onOpenEvent = { onOpenEvent(ev) },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }

        // Footer chip strip
        if (suggestions.footer != null && suggestions.footer.actions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            suggestions.footer.text?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = AppColors.Ink3,
                    ),
                )
                Spacer(Modifier.height(6.dp))
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 0.dp),
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
private fun SuggestionRow(
    event: Event,
    rationale: String?,
    isInTray: Boolean,
    onAddToTray: () -> Unit,
    onOpenEvent: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpenEvent,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                event.title,
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = AppColors.Ink,
                    lineHeight = 17.sp,
                ),
                maxLines = 2,
            )
            Spacer(Modifier.height(2.dp))
            val meta = listOfNotNull(
                event.venueName,
                event.dateDisplay.takeIf { it.isNotBlank() },
                if (event.isFree) "Free" else event.priceDisplay.takeIf { it.isNotBlank() },
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
                )
            }
            if (!rationale.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    rationale,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        color = AppColors.Coral,
                        lineHeight = 14.sp,
                    ),
                    maxLines = 2,
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        // [+] button — disabled state when already in tray (shows checkmark visual)
        AddToTrayButton(isInTray = isInTray, onClick = onAddToTray)
    }
}

@Composable
private fun AddToTrayButton(isInTray: Boolean, onClick: () -> Unit) {
    val bg = if (isInTray) AppColors.Sage else AppColors.Coral
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (!isInTray) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (isInTray) {
            Text(
                "✓",
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp,
                    color = Color.White,
                ),
            )
        } else {
            Icon(
                Icons.Outlined.Add,
                contentDescription = "Add to plan",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun actionLabel(action: BoStructuredResponse.Suggestions.Action): String = when (action) {
    BoStructuredResponse.Suggestions.Action.SEARCH_WIDER -> "Search wider"
    BoStructuredResponse.Suggestions.Action.DIFFERENT_CATEGORY -> "Different category"
    BoStructuredResponse.Suggestions.Action.LATE_NIGHT -> "Late night"
    BoStructuredResponse.Suggestions.Action.DIFFERENT_DAY -> "Different day"
    BoStructuredResponse.Suggestions.Action.MORE_RESULTS -> "More results"
}
