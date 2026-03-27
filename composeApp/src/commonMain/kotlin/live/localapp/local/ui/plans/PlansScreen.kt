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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
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
import live.localapp.local.ui.common.AppHeader
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts
import live.localapp.local.ui.theme.AppShapes
import live.localapp.local.ui.theme.AppType

@Composable
fun PlansScreen(
    viewModel: PlansViewModel,
    onNewPlan: () -> Unit,
    onEventClick: (String) -> Unit = {},
    onBuildPlanFromEvent: ((String) -> Unit)? = null,
    onBuildPlanFromTemplate: ((String) -> Unit)? = null,
    onSharePlan: ((Plan) -> Unit)? = null,
    userInitial: String = "",
    onProfileClick: () -> Unit = {},
    locationName: String = "Waltham, MA",
    streakDays: Int = 2,
) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(AppColors.Snow)) {
        AppHeader(
            title = "plans",
            locationName = locationName,
            radiusFilter = null,
            streakDays = streakDays,
            userInitial = userInitial,
            onProfileClick = onProfileClick,
            onLocationClick = {},
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Section header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Upcoming Plans",
                        style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AppColors.Ink),
                    )
                    Row(
                        modifier = Modifier
                            .background(AppColors.Coral, RoundedCornerShape(10.dp))
                            .clickable { onNewPlan() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                        Text("New Plan", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = Color.White))
                    }
                }
            }

            if (state.upcomingPlans.isEmpty()) {
                if (!state.isLoading) {
                    // Empty state with template suggestions
                    item { PlanTemplates(onNewPlan = onNewPlan, onTemplateClick = onBuildPlanFromTemplate) }
                }
            } else {
                // Hero card for the first plan
                val heroPlan = state.upcomingPlans.first()
                item(key = heroPlan.id) {
                    SwipeToRevealDelete(onDelete = { viewModel.deletePlan(heroPlan.id) }) {
                        HeroPlanCard(
                            plan = heroPlan,
                            isExpanded = state.expandedPlanId == heroPlan.id,
                            onToggleExpand = { viewModel.toggleExpand(heroPlan.id) },
                            onEventClick = onEventClick,
                            onShare = { onSharePlan?.invoke(heroPlan) },
                            onRemoveSlot = { dayIndex, slotId -> viewModel.removeSlotFromPlan(heroPlan.id, dayIndex, slotId) },
                        )
                    }
                }

                // Remaining plans as compact cards
                if (state.upcomingPlans.size > 1) {
                    items(state.upcomingPlans.drop(1), key = { it.id }) { plan ->
                        SwipeToRevealDelete(onDelete = { viewModel.deletePlan(plan.id) }) {
                            CompactPlanCard(
                                plan = plan,
                                isExpanded = state.expandedPlanId == plan.id,
                                onToggleExpand = { viewModel.toggleExpand(plan.id) },
                                onEventClick = onEventClick,
                                onShare = { onSharePlan?.invoke(plan) },
                                onRemoveSlot = { dayIndex, slotId -> viewModel.removeSlotFromPlan(plan.id, dayIndex, slotId) },
                            )
                        }
                    }
                }
            }

            // Past plans — dimmed
            if (state.pastPlans.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text("Past Plans", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AppColors.Ink3))
                }
                items(state.pastPlans, key = { it.id }) { plan ->
                    SwipeToRevealDelete(onDelete = { viewModel.deletePlan(plan.id) }) {
                        Box(modifier = Modifier.alpha(0.5f)) {
                            CompactPlanCard(
                                plan = plan,
                                isExpanded = state.expandedPlanId == plan.id,
                                onToggleExpand = { viewModel.toggleExpand(plan.id) },
                                onEventClick = onEventClick,
                                onRemoveSlot = { dayIndex, slotId -> viewModel.removeSlotFromPlan(plan.id, dayIndex, slotId) },
                            )
                        }
                    }
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
                            SavedEventCard(
                                event = event,
                                onClick = { onEventClick(event.id) },
                                onBuildPlan = { onBuildPlanFromEvent?.invoke(event.title) },
                            )
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
private fun SavedEventCard(event: Event, onClick: () -> Unit, onBuildPlan: () -> Unit = {}) {
    val cardShape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .width(140.dp)
            .height(150.dp)
            .background(AppColors.White, cardShape)
            .border(1.5.dp, AppColors.Pebble, cardShape)
            .clip(cardShape)
            .clickable(onClick = onClick),
    ) {
        // Image with Yocal button overlay
        Box(
            modifier = Modifier.fillMaxWidth().height(80.dp),
        ) {
            val imageUrl = event.imageUrl
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current).data(imageUrl).build(),
                    contentDescription = event.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                val colorKey = event.subcategory ?: event.category
                val (bgStart, bgEnd) = categoryGradient(colorKey)
                Box(
                    modifier = Modifier.fillMaxSize().background(Brush.linearGradient(listOf(bgStart, bgEnd))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = AppColors.Ink3.copy(alpha = 0.3f),
                    )
                }
            }

            // Yocal sparkle button — top right
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .background(AppColors.Coral, CircleShape)
                    .clip(CircleShape)
                    .clickable { onBuildPlan() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = "Build plan", modifier = Modifier.size(13.dp), tint = Color.White)
            }
        }

        // Body
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
            Text(
                event.title,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = AppColors.Ink),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                event.dateDisplay,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = AppColors.Ink3),
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

// PlansHeader removed — now uses shared AppHeader

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

// ═══════════════════════════════════════════════════════════════════
// SWIPE TO REVEAL DELETE
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun SwipeToRevealDelete(
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }
    val offsetX by animateFloatAsState(
        targetValue = if (revealed) -80f else 0f,
        animationSpec = tween(200),
    )

    Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
        // Delete button behind
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(80.dp)
                .fillMaxHeight()
                .background(Color(0xFFFF3B30), RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
                .clickable {
                    revealed = false
                    onDelete()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = Color.White, modifier = Modifier.size(22.dp))
        }

        // Content on top — slides left
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offsetX * density }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { },
                        onHorizontalDrag = { _, dragAmount ->
                            if (dragAmount < -10) revealed = true
                            else if (dragAmount > 10) revealed = false
                        },
                    )
                },
        ) {
            content()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// HERO PLAN CARD
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun HeroPlanCard(
    plan: Plan,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onEventClick: (String) -> Unit = {},
    onShare: () -> Unit = {},
    onRemoveSlot: (dayIndex: Int, slotId: String) -> Unit = { _, _ -> },
) {
    val totalEvents = plan.days.sumOf { it.slots.size }
    val cardShape = RoundedCornerShape(20.dp)

    Column {
        // Hero
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(
                    Brush.linearGradient(listOf(AppColors.Coral, Color(0xFFFF4F4F))),
                    cardShape,
                )
                .clickable { onToggleExpand() }
                .padding(20.dp),
        ) {
            // Decorative circle
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .offset(x = 80.dp, y = (-40).dp)
                    .align(Alignment.TopEnd)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape),
            )

            Column {
                // Top row: confirmed + share
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val confirmed = plan.attendees.count { it.confirmed }
                    val total = plan.attendees.size.coerceAtLeast(1)
                    Row(
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Outlined.People, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.White)
                        Text(
                            "$confirmed/$total confirmed",
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = Color.White),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape)
                            .clip(CircleShape)
                            .clickable { onShare() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.IosShare, contentDescription = "Share", modifier = Modifier.size(14.dp), tint = Color.White)
                    }
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    plan.title,
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 22.sp, color = Color.White),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    plan.dateDisplay.ifBlank { "${plan.days.size} ${if (plan.days.size == 1) "day" else "days"}" },
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f)),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("$totalEvents events", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = Color.White))
                    }
                    Box(
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("${plan.days.size} ${if (plan.days.size == 1) "day" else "days"}", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = Color.White))
                    }
                }
            }
        }

        // Timeline — expands below the hero
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.Snow)
                    .padding(top = 8.dp),
            ) {
                plan.days.forEachIndexed { index, day ->
                    if (plan.days.size > 1) {
                        Text(
                            day.label.replace("Today ", "").replace("Tomorrow ", ""),
                            style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink),
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    TimelineView(
                        day = day,
                        onEventClick = onEventClick,
                        onRemoveSlot = { slotId -> onRemoveSlot(index, slotId) },
                    )
                    if (index < plan.days.lastIndex) {
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// COMPACT PLAN CARD
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun CompactPlanCard(
    plan: Plan,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onEventClick: (String) -> Unit = {},
    onShare: () -> Unit = {},
    onRemoveSlot: (dayIndex: Int, slotId: String) -> Unit = { _, _ -> },
) {
    val totalEvents = plan.days.sumOf { it.slots.size }
    val cardShape = RoundedCornerShape(14.dp)

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.White, cardShape)
                .border(1.5.dp, AppColors.Pebble, cardShape)
                .clip(cardShape)
                .clickable { onToggleExpand() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Coral accent dot
            Box(modifier = Modifier.size(8.dp).background(AppColors.Coral, CircleShape))
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    plan.title,
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AppColors.Ink),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    plan.dateDisplay.ifBlank { "${plan.days.size} days" },
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3),
                )
            }

            // Confirmed count
            val confirmed = plan.attendees.count { it.confirmed }
            val total = plan.attendees.size.coerceAtLeast(1)
            Text(
                "$confirmed/$total",
                modifier = Modifier
                    .background(AppColors.Mist, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 9.sp, color = AppColors.Ink3),
            )

            Spacer(Modifier.width(6.dp))

            // Event count badge
            Box(
                modifier = Modifier
                    .background(AppColors.CoralLt, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text("$totalEvents", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = AppColors.Coral))
            }

            Spacer(Modifier.width(6.dp))

            // Share
            Icon(
                Icons.Outlined.IosShare,
                contentDescription = "Share",
                modifier = Modifier.size(16.dp).clickable { onShare() },
                tint = AppColors.Ink3,
            )

        }

        // Timeline expands below
        AnimatedVisibility(visible = isExpanded) {
            Column(modifier = Modifier.padding(top = 8.dp, start = 4.dp)) {
                plan.days.forEachIndexed { index, day ->
                    if (plan.days.size > 1) {
                        Text(
                            day.label.replace("Today ", "").replace("Tomorrow ", ""),
                            style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink),
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    TimelineView(
                        day = day,
                        onEventClick = onEventClick,
                        onRemoveSlot = { slotId -> onRemoveSlot(index, slotId) },
                    )
                    if (index < plan.days.lastIndex) Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// TIMELINE VIEW
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun TimelineView(
    day: PlanDay,
    onEventClick: (String) -> Unit = {},
    onRemoveSlot: ((String) -> Unit)? = null,
) {
    Column {
        day.slots.forEachIndexed { index, slot ->
            val isLast = index == day.slots.lastIndex

            val slotContent = @Composable {
                Row(modifier = Modifier.fillMaxWidth()) {
                    // Time + line
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(50.dp),
                    ) {
                        Text(
                            slot.time,
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = AppColors.Ink3),
                        )
                        Spacer(Modifier.height(4.dp))
                        // Dot
                        Box(modifier = Modifier.size(10.dp).background(AppColors.Coral, CircleShape))
                        // Line
                        if (!isLast) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(40.dp)
                                    .background(AppColors.Coral.copy(alpha = 0.25f)),
                            )
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    // Event card
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(bottom = if (isLast) 0.dp else 8.dp)
                            .background(AppColors.White, RoundedCornerShape(10.dp))
                            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(10.dp))
                            .then(
                                if (slot.eventId != null) Modifier.clickable { onEventClick(slot.eventId) }
                                else Modifier
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                slot.name,
                                modifier = Modifier.weight(1f, fill = false),
                                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = AppColors.Ink),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (slot.isFree) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Free",
                                    modifier = Modifier
                                        .background(Color(0xFFEDFAF5), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 9.sp, color = AppColors.Sage),
                                )
                            }
                        }
                        if (slot.detail.isNotBlank()) {
                            Text(
                                slot.detail,
                                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = AppColors.Ink3),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (onRemoveSlot != null) {
                SwipeToRevealDelete(onDelete = { onRemoveSlot(slot.id) }) {
                    slotContent()
                }
            } else {
                slotContent()
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// PLAN TEMPLATES (empty state)
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun PlanTemplates(onNewPlan: () -> Unit, onTemplateClick: ((String) -> Unit)? = null) {
    val templateMessages = mapOf(
        "Date Night" to "Plan a date night",
        "Family Day Out" to "Plan a family day out",
        "Visitors in Town" to "Plan a day showing visitors around",
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Get started with a template",
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AppColors.Ink3),
        )
        Spacer(Modifier.height(2.dp))
        listOf(
            "Date Night" to "Plan the perfect evening for two",
            "Family Day Out" to "Fun for the whole crew",
            "Visitors in Town" to "Show them the best of the area",
        ).forEach { (title, subtitle) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.White, RoundedCornerShape(14.dp))
                    .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        val msg = templateMessages[title]
                        if (msg != null && onTemplateClick != null) {
                            onTemplateClick(msg)
                        } else {
                            onNewPlan()
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AppColors.Ink),
                    )
                    Text(
                        subtitle,
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3),
                    )
                }
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = AppColors.Coral,
                )
            }
        }
    }
}



private fun categoryGradient(category: String): Pair<Color, Color> = when (category.lowercase()) {
    "music" -> Color(0xFFF5F0FF) to Color(0xFFEDE0FF)
    "sports" -> Color(0xFFEEF5FF) to Color(0xFFDCEEFF)
    "arts" -> Color(0xFFEDFAF5) to Color(0xFFD5F5E8)
    "comedy", "food" -> Color(0xFFFFF8EC) to Color(0xFFFFF0CC)
    "outdoors" -> Color(0xFFEDFAF5) to Color(0xFFD5F5E8)
    else -> Color(0xFFFFF0EE) to Color(0xFFFFE0DC)
}
