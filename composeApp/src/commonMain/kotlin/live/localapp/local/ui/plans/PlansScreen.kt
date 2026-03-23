package live.localapp.local.ui.plans

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.model.PlanDay
import live.localapp.local.domain.model.Venue
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts
import live.localapp.local.ui.theme.AppShapes
import live.localapp.local.ui.theme.AppType

@Composable
fun PlansScreen(
    viewModel: PlansViewModel,
    onNewPlan: () -> Unit,
    onEventClick: (String) -> Unit = {},
    userInitial: String = "",
    onProfileClick: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(AppColors.Snow)) {
        PlansHeader(onNewPlan = onNewPlan, userInitial = userInitial, onProfileClick = onProfileClick)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Upcoming plans
            if (state.upcomingPlans.isEmpty() && !state.isLoading) {
                item { EmptyPlansCard(onNewPlan = onNewPlan) }
            } else {
                items(state.upcomingPlans) { plan ->
                    PlanCard(
                        plan = plan,
                        isExpanded = state.expandedPlanId == plan.id,
                        onToggleExpand = { viewModel.toggleExpand(plan.id) },
                        onEventClick = onEventClick,
                    )
                }
            }

            // Past plans
            if (state.pastPlans.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Past Plans",
                        style = TextStyle(
                            fontFamily = AppFonts.Heading,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = AppColors.Ink3,
                        ),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                items(state.pastPlans) { plan ->
                    PlanCard(
                        plan = plan,
                        isExpanded = state.expandedPlanId == plan.id,
                        onToggleExpand = { viewModel.toggleExpand(plan.id) },
                        isPast = true,
                        onEventClick = onEventClick,
                    )
                }
            }

            // Saved Events
            if (state.savedEvents.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Saved Events",
                        style = TextStyle(
                            fontFamily = AppFonts.Heading,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = AppColors.Ink,
                        ),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        state.savedEvents.forEach { event ->
                            SavedEventCard(event = event, onClick = { onEventClick(event.id) })
                        }
                    }
                }
            }

            // Saved Venues
            if (state.savedVenues.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Saved Venues",
                        style = TextStyle(
                            fontFamily = AppFonts.Heading,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = AppColors.Ink,
                        ),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                items(state.savedVenues) { venue ->
                    SavedVenueRow(venue = venue)
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// SAVED EVENT CARD
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun SavedEventCard(event: Event, onClick: () -> Unit) {
    val cardShape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .width(140.dp)
            .background(AppColors.White, cardShape)
            .border(1.5.dp, AppColors.Pebble, cardShape)
            .clip(cardShape)
            .clickable(onClick = onClick),
    ) {
        // Image
        val imageUrl = event.imageUrl
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(imageUrl)
                    .build(),
                contentDescription = event.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(AppColors.Mist),
            )
        }
        // Body
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
            Text(
                event.title,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp,
                    color = AppColors.Ink,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                event.dateDisplay,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    color = AppColors.Ink3,
                ),
                maxLines = 1,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// SAVED VENUE ROW
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun SavedVenueRow(venue: Venue) {
    val cardShape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.White, cardShape)
            .border(1.5.dp, AppColors.Pebble, cardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Small thumbnail
        val photoUrl = venue.photoUrl ?: venue.imageUrl
        if (!photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(photoUrl)
                    .build(),
                contentDescription = venue.name,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(AppColors.Mist, RoundedCornerShape(8.dp)),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                venue.name,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    color = AppColors.Ink,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(venue.category, venue.address).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = AppColors.Ink3,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// HEADER
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun PlansHeader(
    onNewPlan: () -> Unit,
    userInitial: String = "",
    onProfileClick: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.White),
    ) {
        // Title row: logo-style "plans" + New Plan button + avatar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text("p", style = AppType.Logo.copy(color = AppColors.Coral, fontSize = 24.sp))
                Text("lans", style = AppType.Logo.copy(fontSize = 24.sp))
            }

            // New Plan button
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val newPlanScale by animateFloatAsState(if (isPressed) 0.95f else 1f, tween(80))
            val haptic = LocalHapticFeedback.current
            Row(
                modifier = Modifier
                    .graphicsLayer { scaleX = newPlanScale; scaleY = newPlanScale }
                    .background(
                        if (isPressed) AppColors.Coral.copy(alpha = 0.85f) else AppColors.Coral,
                        RoundedCornerShape(12.dp),
                    )
                    .clickable(interactionSource = interactionSource, indication = null, onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onNewPlan()
                    })
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color.White)
                Text(
                    "New Plan",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = Color.White),
                )
            }

            Spacer(Modifier.width(10.dp))

            // User avatar
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(AppColors.Coral, AppShapes.Avatar)
                    .border(2.dp, AppColors.CoralDk, AppShapes.Avatar)
                    .clip(AppShapes.Avatar)
                    .clickable { onProfileClick() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    userInitial,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        color = AppColors.White,
                    ),
                )
            }
        }

        // Bottom border
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(AppColors.Mist),
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// EMPTY STATE
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun EmptyPlansCard(onNewPlan: () -> Unit) {
    val cardShape = RoundedCornerShape(18.dp)
    Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Box(Modifier.fillMaxWidth().matchParentSize().offset(y = 4.dp).background(AppColors.Pebble, cardShape))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.White, cardShape)
                .border(2.dp, AppColors.Pebble, cardShape)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = AppColors.Coral,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "No upcoming plans yet",
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AppColors.Ink),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Use the AI concierge to build\na personalized itinerary",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = AppColors.Ink3,
                    lineHeight = 19.sp,
                ),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier
                    .background(AppColors.Coral, RoundedCornerShape(12.dp))
                    .clickable(onClick = onNewPlan)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(15.dp), tint = Color.White)
                Text(
                    "Plan with AI",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = Color.White),
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// PLAN CARD
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun PlanCard(
    plan: Plan,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    isPast: Boolean = false,
    onEventClick: (String) -> Unit = {},
) {
    val cardShape = RoundedCornerShape(18.dp)
    val totalEvents = plan.days.sumOf { it.slots.size }

    Box(modifier = Modifier.fillMaxWidth()) {
        // Shadow
        Box(Modifier.fillMaxWidth().matchParentSize().offset(y = 5.dp).background(AppColors.Pebble, cardShape))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.White, cardShape)
                .border(2.dp, AppColors.Pebble, cardShape)
                .clip(cardShape),
        ) {
            // Dark header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isPast) AppColors.Ink2 else AppColors.Ink)
                    .padding(16.dp),
            ) {
                // Glow circle
                Box(
                    Modifier
                        .size(110.dp)
                        .offset(x = 45.dp, y = (-30).dp)
                        .align(Alignment.TopEnd)
                        .background(
                            if (isPast) Color.White.copy(alpha = 0.04f) else AppColors.Coral.copy(alpha = 0.18f),
                            CircleShape,
                        ),
                )

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            plan.title,
                            modifier = Modifier.weight(1f),
                            style = TextStyle(
                                fontFamily = AppFonts.Heading,
                                fontWeight = FontWeight.Black,
                                fontSize = 20.sp,
                                color = AppColors.White,
                            ),
                        )
                        Text(
                            "Share",
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(9.dp))
                                .clickable { /* TODO: share plan URL */ }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.85f),
                            ),
                        )
                    }
                    Spacer(Modifier.height(9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HeaderChip(plan.dateDisplay)
                        HeaderChip(plan.city)
                    }
                }
            }

            // Stats row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = AppColors.Mist),
            ) {
                PlanStatCell("${plan.days.size}", if (plan.days.size == 1) "Day" else "Days", Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(48.dp).background(AppColors.Mist))
                PlanStatCell("$totalEvents", "Events", Modifier.weight(1f))
            }

            // View button row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))

                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val toggleScale by animateFloatAsState(if (isPressed) 0.95f else 1f, tween(80))
                val hapticToggle = LocalHapticFeedback.current
                Row(
                    modifier = Modifier
                        .graphicsLayer { scaleX = toggleScale; scaleY = toggleScale }
                        .background(
                            when {
                                isExpanded -> AppColors.Mist
                                isPressed -> AppColors.CoralDk
                                else -> AppColors.Coral
                            },
                            RoundedCornerShape(10.dp),
                        )
                        .clickable(interactionSource = interactionSource, indication = null, onClick = {
                            hapticToggle.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleExpand()
                        })
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (isExpanded) "Collapse" else "View Plan",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            color = if (isExpanded) AppColors.Ink3 else Color.White,
                        ),
                    )
                    Icon(
                        if (isExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        null,
                        Modifier.size(14.dp),
                        tint = if (isExpanded) AppColors.Ink3 else Color.White,
                    )
                }
            }

            // Expanded: full day cards
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.Snow)
                        .border(width = 1.dp, color = AppColors.Mist)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    plan.days.forEachIndexed { index, day ->
                        PlanDayCard(day = day, dayIndex = index, totalDays = plan.days.size, onEventClick = onEventClick)
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// PLAN DAY CARD  (mirrors Chat's DayCard visual style)
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun PlanDayCard(day: PlanDay, dayIndex: Int, totalDays: Int, onEventClick: (String) -> Unit = {}) {
    val color = AppColors.Coral
    val cardShape = RoundedCornerShape(14.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.White, cardShape)
            .border(1.5.dp, AppColors.Pebble, cardShape)
            .clip(cardShape),
    ) {
        // Day header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(color)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column {
                if (totalDays > 1) {
                    Text(
                        "DAY ${dayIndex + 1} OF $totalDays",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.Black,
                            fontSize = 9.sp,
                            color = AppColors.White.copy(alpha = 0.7f),
                            letterSpacing = 0.8.sp,
                        ),
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    day.label,
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 15.sp, color = AppColors.White),
                )
                Text(
                    "${day.slots.size} stops",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = AppColors.White.copy(alpha = 0.7f)),
                )
            }
        }

        // Slot timeline
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            day.slots.forEachIndexed { index, slot ->
                val isLast = index == day.slots.lastIndex

                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    // Time + dot column
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(34.dp).fillMaxHeight(),
                    ) {
                        Text(
                            slot.time,
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 9.sp, color = AppColors.Ink3),
                        )
                        Spacer(Modifier.height(3.dp))
                        Box(
                            Modifier.size(10.dp)
                                .background(color, CircleShape)
                                .border(2.5.dp, color.copy(alpha = 0.2f), CircleShape),
                        )
                        if (!isLast) {
                            Box(Modifier.width(2.dp).weight(1f).background(AppColors.Mist))
                        }
                    }

                    Spacer(Modifier.width(9.dp))

                    // Slot content
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(bottom = if (isLast) 0.dp else 6.dp)
                            .background(AppColors.Snow, RoundedCornerShape(11.dp))
                            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(11.dp))
                            .then(
                                if (slot.eventId != null) Modifier.clickable { onEventClick(slot.eventId) }
                                else Modifier
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                slot.name,
                                modifier = Modifier.weight(1f, fill = false),
                                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = AppColors.Ink),
                                maxLines = 1,
                            )
                            if (slot.price.isNotBlank()) {
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    slot.price,
                                    style = TextStyle(
                                        fontFamily = AppFonts.Body,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 11.sp,
                                        color = if (slot.isFree) AppColors.Sage else AppColors.Coral,
                                    ),
                                )
                            }
                        }
                        Text(
                            slot.detail,
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = AppColors.Ink3),
                            maxLines = 1,
                        )
                    }
                }

                if (!isLast) {
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// SUPPORTING COMPOSABLES
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun HeaderChip(text: String) {
    Text(
        text,
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        style = TextStyle(
            fontFamily = AppFonts.Body,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.8f),
        ),
    )
}

@Composable
private fun PlanStatCell(value: String, label: String, modifier: Modifier) {
    Column(
        modifier = modifier.padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 16.sp, color = AppColors.Ink),
        )
        Text(
            label,
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = AppColors.Ink3),
        )
    }
}

