@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package live.localapp.local.ui.plans

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Place
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.model.PlanDay
import live.localapp.local.domain.model.Venue
import live.localapp.local.ui.common.AppHeader
import live.localapp.local.ui.common.localCardSurface
import live.localapp.local.ui.common.pressable
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts
import live.localapp.local.ui.theme.AppShapes
import live.localapp.local.ui.theme.AppType

@Composable
fun PlansScreen(
    viewModel: PlansViewModel,
    onNewPlan: () -> Unit,
    onPlanClick: (Plan, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> },
    onInviteClick: (Plan) -> Unit = {},
    onEventClick: (String, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> },
    onBuildPlanFromTemplate: ((String) -> Unit)? = null,
    onSharePlan: ((Plan) -> Unit)? = null,
    userInitial: String = "",
    onProfileClick: () -> Unit = {},
    onLocationClick: () -> Unit = {},
    onSignIn: () -> Unit = {},
    isLoggedIn: Boolean = true,
    locationName: String = "",
    streakDays: Int = 0,
    isUnsupportedState: Boolean = false,
    radiusFilter: Double? = null,
) {
    val state by viewModel.uiState.collectAsState()

    // Refresh plans + attendee counts whenever the app comes back to the
    // foreground. Without realtime, this is the cheapest way to make the
    // "X unconfirmed" subtext catch up after a friend accepts an invite
    // while the app was backgrounded — by the time the user looks again,
    // the count is already current.
    live.localapp.local.platform.OnAppResumeEffect {
        viewModel.refresh()
    }

    // Pending-open hook: when an external surface (the chat-success
    // "View Plan" link) requests a specific plan be opened, find it in
    // the loaded list and route through the normal onPlanClick handler
    // so the full plan-detail bridge gets wired up the same way as a
    // tap. Clears the signal after consumption so it doesn't fire twice.
    androidx.compose.runtime.LaunchedEffect(state.pendingOpenPlanId, state.upcomingPlans, state.pastPlans) {
        val pendingId = state.pendingOpenPlanId ?: return@LaunchedEffect
        val match = (state.upcomingPlans + state.pastPlans).firstOrNull { it.id == pendingId }
        if (match != null) {
            onPlanClick(match, androidx.compose.ui.geometry.Rect.Zero)
            viewModel.consumePendingOpenPlan()
        }
        // If not found yet (still loading after onboarding), leave the
        // signal in place — the next state emission with loaded plans
        // will trigger this effect again.
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.Snow)) {
        AppHeader(
            title = "local",
            locationName = locationName,
            radiusFilter = radiusFilter,
            streakDays = streakDays,
            userInitial = userInitial,
            onProfileClick = onProfileClick,
            onLocationClick = onLocationClick,
            isLoggedIn = isLoggedIn,
            isUnsupportedState = isUnsupportedState,
        )

        if (!isLoggedIn) {
            // ── Logged-out state (Bank 3) ──
            // Big Bo Pop-In, Excited expression, Sign in CTA, fresh copy line
            // per visit. Replaces the old static Icon + headline + subtitle.
            live.localapp.local.ui.emptystate.BoEmptyState(
                bank = live.localapp.local.ui.emptystate.EmptyStateCopy.PLANS_SIGNED_OUT,
                ctaLabel = "Sign in",
                onCta = onSignIn,
            )
            return
        }

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
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
                            .pressable(onClick = onNewPlan)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                        Text("New Plan", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = Color.White))
                    }
                }
            }

            // Plans (if any) render first; the Saved Events and Saved Venues
            // carousels below ALWAYS show. When there are no upcoming plans
            // yet we render 3 Bo-branded plan templates (rotated each time
            // the user returns to the tab — driven by templateRotationKey).
            if (state.isLoading && state.upcomingPlans.isEmpty() && state.savedEvents.isEmpty()) {
                item { PlansSkeletonLoader() }
            } else if (state.upcomingPlans.isEmpty()) {
                item {
                    PlanTemplates(
                        rotationKey = state.templateRotationKey,
                        onNewPlan = onNewPlan,
                        onTemplateClick = onBuildPlanFromTemplate,
                    )
                }
            } else if (state.upcomingPlans.isNotEmpty()) {
                // First plan — editorial hero card
                val heroPlan = state.upcomingPlans.first()
                item(key = heroPlan.id) {
                    SwipeToRevealDelete(onDelete = { viewModel.deletePlan(heroPlan.id) }) {
                        HeroPlanCard(
                            plan = heroPlan,
                            onClick = { bounds -> onPlanClick(heroPlan, bounds) },
                            onShare = { onSharePlan?.invoke(heroPlan) },
                        )
                    }
                }

                // Remaining plans — slim row cards (same style as venue cards on Places page)
                if (state.upcomingPlans.size > 1) {
                    items(state.upcomingPlans.drop(1), key = { it.id }) { plan ->
                        SwipeToRevealDelete(onDelete = { viewModel.deletePlan(plan.id) }) {
                            CompactPlanRow(
                                plan = plan,
                                onClick = { bounds -> onPlanClick(plan, bounds) },
                                onShare = { onSharePlan?.invoke(plan) },
                            )
                        }
                    }
                }
            }

            // Invited plans — pending invites from shared links
            if (state.pendingInvites.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text("Invited", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AppColors.Ink))
                }
                items(state.pendingInvites, key = { "invite_${it.id}" }) { plan ->
                    InvitePlanRow(
                        plan = plan,
                        onClick = { onInviteClick(plan) },
                        onDismiss = { viewModel.dismissInvite(plan.id) },
                        onAccept = { viewModel.acceptInvite(plan) },
                    )
                }
            }

            // Past plans — dimmed, slim rows
            if (state.pastPlans.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text("Past Plans", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AppColors.Ink3))
                }
                items(state.pastPlans, key = { it.id }) { plan ->
                    SwipeToRevealDelete(onDelete = { viewModel.deletePlan(plan.id) }) {
                        Box(modifier = Modifier.alpha(0.5f)) {
                            CompactPlanRow(
                                plan = plan,
                                onClick = { bounds -> onPlanClick(plan, bounds) },
                            )
                        }
                    }
                }
            }

            // Saved Events — ALWAYS shown. Card taps open Bo with a prefilled
            // "build a plan around X" prompt instead of the event detail.
            item {
                Text(
                    "Add Your Saved Events to a Plan",
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = AppColors.Ink,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                if (state.savedEvents.isEmpty()) {
                    Text(
                        "No saved events yet — tap the heart on any event to save it here.",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = AppColors.Ink3,
                        ),
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        state.savedEvents.forEach { event ->
                            SavedEventCard(
                                event = event,
                                onClick = { _ ->
                                    val venuePart = event.venueName?.let { " at $it" } ?: ""
                                    onBuildPlanFromTemplate?.invoke(
                                        "Help me build a plan around ${event.title}$venuePart on ${event.dateDisplay}",
                                    )
                                },
                            )
                        }
                    }
                }
            }

            // Saved Venues — ALWAYS shown. Card taps open Bo with a prefilled
            // "Show me Upcoming Events at X" prompt.
            item {
                Text(
                    "Ask Bo for Upcoming Events at...",
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = AppColors.Ink,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                if (state.savedVenues.isEmpty()) {
                    Text(
                        "No saved places yet — tap the heart on any place to save it here.",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = AppColors.Ink3,
                        ),
                    )
                }
            }
            items(state.savedVenues) { venue ->
                SavedVenueRow(
                    venue = venue,
                    onPlanForVenue = {
                        onBuildPlanFromTemplate?.invoke("Show me Upcoming Events at ${venue.name}")
                    },
                )
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// SAVED EVENT CARD
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun SavedEventCard(event: Event, onClick: (androidx.compose.ui.geometry.Rect) -> Unit) {
    var cardBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    Column(
        modifier = Modifier
            .width(140.dp)
            .height(154.dp)
            .onGloballyPositioned { cardBounds = it.boundsInRoot() }
            .localCardSurface(cornerRadius = 14.dp)
            .pressable(onClick = { onClick(cardBounds) }),
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
                )
            }

            // (Yocal sparkle badge removed — the entire card now opens Bo
            // with a prefilled "build a plan around X" prompt on tap.)
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
private fun SavedVenueRow(venue: Venue, onPlanForVenue: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .localCardSurface(cornerRadius = 14.dp)
            .pressable(onClick = onPlanForVenue),
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .width(80.dp)
                .fillMaxHeight()
                .background(AppColors.Mist),
            contentAlignment = Alignment.Center,
        ) {
            val photoUrl = venue.photoUrl ?: venue.imageUrl
            if (!photoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = photoUrl,
                    contentDescription = venue.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(28.dp), tint = AppColors.Ink3.copy(alpha = 0.5f))
            }
        }

        // Body
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                venue.name,
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AppColors.Ink, lineHeight = 18.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            val meta = buildString {
                venue.category?.let { append(it.replaceFirstChar { c -> c.uppercaseChar() }) }
                append(" \u00B7 ${venue.city}")
            }
            Text(meta, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3))
            if (venue.googleRating != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "\u2605\u2605\u2605\u2605\u2606 ${venue.googleRating}",
                    style = TextStyle(fontSize = 11.sp, color = AppColors.Sun),
                )
            }
        }

        // (Yocal sparkle removed — the whole row is the action surface;
        // tapping it opens Bo with "Show me Upcoming Events at {venue}".)
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
                            hapticToggle.performHapticFeedback(HapticFeedbackType.LongPress)
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
    var confirming by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val offsetX by animateFloatAsState(
        targetValue = if (revealed) -76f else 0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.75f,
            stiffness = 400f,
        ),
    )

    // Auto-dismiss after 4 seconds
    androidx.compose.runtime.LaunchedEffect(revealed) {
        if (revealed) {
            kotlinx.coroutines.delay(4000)
            revealed = false
        }
    }

    // Confirmation shrink-away animation. We deliberately keep `confirming=true`
    // after onDelete fires — the ViewModel's state update is async, and resetting
    // confirming=false makes us re-render content() for ~1 frame while the plan
    // is still in the parent list, causing the deleted card to flash back in
    // before LazyColumn drops the item. Leaving confirming=true keeps the
    // collapsed "Deleted" pill at zero height until disposal.
    val dismissProgress by animateFloatAsState(
        targetValue = if (confirming) 0f else 1f,
        animationSpec = tween(280),
        finishedListener = {
            if (confirming) onDelete()
        },
    )

    if (confirming) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height((56 * dismissProgress).dp)
                .graphicsLayer {
                    alpha = dismissProgress
                    scaleX = 0.95f + 0.05f * dismissProgress
                }
                .clip(RoundedCornerShape(14.dp))
                .background(AppColors.CoralLt),
            contentAlignment = Alignment.Center,
        ) {
            if (dismissProgress > 0.5f) {
                Text(
                    "Deleted",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.Coral),
                )
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).clipToBounds()) {
            // Delete action — rounded pill behind the card. Alpha is tied to
            // swipe progress so the pill is invisible at rest. Without this,
            // the press-scale on plan cards (which shrinks them to 0.94)
            // exposes the pill at the right edge before the user has swiped.
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .fillMaxHeight()
                    .width(68.dp)
                    .graphicsLayer { alpha = ((-offsetX) / 76f).coerceIn(0f, 1f) }
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.Coral)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        revealed = false
                        confirming = true
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.height(2.dp))
                    Text("Delete", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = Color.White))
                }
            }

            // Content slides left
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationX = offsetX * density }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = { },
                            onHorizontalDrag = { change, dragAmount ->
                                if (dragAmount < -10) {
                                    if (!revealed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    revealed = true
                                } else if (dragAmount > 10) {
                                    revealed = false
                                }
                            },
                        )
                    },
            ) {
                content()
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// HERO PLAN CARD
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun PlansSkeletonLoader() {
    val shimmerAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
    )
    val c = AppColors.Pebble.copy(alpha = 0.3f + shimmerAlpha * 0.2f)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        // Hero card skeleton
        Box(Modifier.fillMaxWidth().height(180.dp).background(c, RoundedCornerShape(20.dp)))
        Spacer(Modifier.height(16.dp))
        // Compact plan rows
        repeat(3) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(56.dp).background(c, RoundedCornerShape(12.dp)))
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.width(180.dp).height(13.dp).background(c, RoundedCornerShape(6.dp)))
                    Box(Modifier.width(120.dp).height(10.dp).background(c, RoundedCornerShape(5.dp)))
                }
            }
        }
    }
}

@Composable
private fun HeroPlanCard(
    plan: Plan,
    onClick: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onShare: () -> Unit = {},
) {
    val totalEvents = plan.days.sumOf { it.slots.size }
    val cardShape = RoundedCornerShape(20.dp)
    val firstImageUrl = plan.days.flatMap { it.slots }.firstNotNullOfOrNull { it.imageUrl }
    var cardBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { cardBounds = it.boundsInRoot() }
            // pressable must come BEFORE clip/background so its internal
            // graphicsLayer wraps them — otherwise the background is drawn
            // outside the scaled layer and the coral gradient leaks around
            // all four edges of the image during the press shrink.
            .pressable(onClick = { onClick(cardBounds) })
            .clip(cardShape)
            .background(
                Brush.linearGradient(listOf(AppColors.Coral, Color(0xFFFF4F4F))),
                cardShape,
            ),
    ) {
        // Background venue image
        if (firstImageUrl != null) {
            AsyncImage(
                model = firstImageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            // Dark overlay for text readability
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
            )
        } else {
            // Decorative circle (fallback when no image)
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .offset(x = 80.dp, y = (-40).dp)
                    .align(Alignment.TopEnd)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape),
            )
        }

        Column(modifier = Modifier.padding(20.dp)) {
            // Top row: confirmed + share
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val confirmed = plan.attendees.count { it.confirmed }
                // Use the creator-set group size as the denominator so a 3-person
                // plan reads "1/3 confirmed" while invites are still pending,
                // not "1/1 confirmed". Falls back to attendees.size for legacy
                // plans where groupSize was never set.
                val total = (plan.groupSize ?: plan.attendees.size)
                    .coerceAtLeast(plan.attendees.size)
                    .coerceAtLeast(1)
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
                    Text("$totalEvents stops", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = Color.White))
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
}

// ═══════════════════════════════════════════════════════════════════
// COMPACT PLAN ROW — matches venue card style on Places page
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun CompactPlanRow(
    plan: Plan,
    onClick: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onShare: () -> Unit = {},
) {
    val totalStops = plan.days.sumOf { it.slots.size }
    val firstImageUrl = plan.days.flatMap { it.slots }.firstNotNullOfOrNull { it.imageUrl }
    var cardBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }

    Box(
        modifier = Modifier.fillMaxWidth()
            .onGloballyPositioned { cardBounds = it.boundsInRoot() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .pressable(onClick = { onClick(cardBounds) })
                .localCardSurface(cornerRadius = 14.dp),
        ) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .fillMaxHeight()
                    .background(Brush.linearGradient(listOf(AppColors.Coral, Color(0xFFFF4F4F)))),
                contentAlignment = Alignment.Center,
            ) {
                if (firstImageUrl != null) {
                    AsyncImage(
                        model = firstImageUrl,
                        contentDescription = plan.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // No-image plans render the gradient tile only.
            }

            // Body
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    plan.title,
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AppColors.Ink, lineHeight = 18.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        append(plan.dateDisplay.ifBlank { "${plan.days.size} days" })
                        append(" \u00B7 ")
                        append("$totalStops ${if (totalStops == 1) "stop" else "stops"}")
                        val attendeeCount = plan.attendees.size.coerceAtLeast(1)
                        if (attendeeCount > 1) {
                            append(" \u00B7 $attendeeCount people")
                        }
                    },
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3),
                )
            }

            // Share
            Box(
                modifier = Modifier.fillMaxHeight().padding(end = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.IosShare,
                    contentDescription = "Share",
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onShare,
                        ),
                    tint = AppColors.Ink3,
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// INVITE PLAN ROW — pending invites from shared links
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun InvitePlanRow(
    plan: Plan,
    onClick: () -> Unit = {},
    onDismiss: () -> Unit = {},
    onAccept: () -> Unit = {},
) {
    val totalStops = plan.days.sumOf { it.slots.size }
    val firstImageUrl = plan.days.flatMap { it.slots }.firstNotNullOfOrNull { it.imageUrl }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressable(onClick = onClick)
                .localCardSurface(cornerRadius = 14.dp),
        ) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(88.dp)
                    .background(Brush.linearGradient(listOf(AppColors.Coral, Color(0xFFFF4F4F)))),
                contentAlignment = Alignment.Center,
            ) {
                if (firstImageUrl != null) {
                    AsyncImage(
                        model = firstImageUrl,
                        contentDescription = plan.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // No-image plans render the gradient tile only.
            }

            // Body
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(88.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                // "Tom invited you" or just the plan title
                if (plan.creatorName != null) {
                    Text(
                        "${plan.creatorName} invited you",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = AppColors.Coral),
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    plan.title,
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AppColors.Ink, lineHeight = 18.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        append(plan.dateDisplay.ifBlank { "${plan.days.size} days" })
                        append(" \u00B7 ")
                        append("$totalStops ${if (totalStops == 1) "stop" else "stops"}")
                    },
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3),
                )
            }

            // Dismiss X
            Box(
                modifier = Modifier
                    .height(88.dp)
                    .padding(end = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Dismiss",
                    modifier = Modifier
                        .size(20.dp)
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
}

// ═══════════════════════════════════════════════════════════════════
// TIMELINE VIEW
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun TimelineView(
    day: PlanDay,
    onEventClick: (String, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> },
    onRemoveSlot: ((String) -> Unit)? = null,
) {
    Column {
        day.slots.forEachIndexed { index, slot ->
            val isLast = index == day.slots.lastIndex

            val slotContent = @Composable {
                var slotBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
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
                            .onGloballyPositioned { slotBounds = it.boundsInRoot() }
                            .background(AppColors.White, RoundedCornerShape(10.dp))
                            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(10.dp))
                            .then(
                                if (slot.eventId != null) Modifier.clickable { onEventClick(slot.eventId, slotBounds) }
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

                        // Action buttons
                        val hasActions = (slot.venueLat != null && slot.venueLng != null) || slot.ticketUrl != null || slot.reservationUrl != null
                        if (hasActions) {
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (slot.reservationUrl != null) {
                                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                                    Text(
                                        "Reserve",
                                        modifier = Modifier
                                            .background(AppColors.CoralLt, RoundedCornerShape(6.dp))
                                            .border(1.dp, AppColors.Coral.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                            .clickable {
                                                val today = kotlin.time.Clock.System.now().toString().take(10)
                                                val url = if (slot.reservationUrl.contains("opentable")) {
                                                    "${slot.reservationUrl}?dateTime=${today}T19:00&covers=2&partySize=2"
                                                } else slot.reservationUrl
                                                uriHandler.openUri(url)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 9.sp, color = AppColors.Coral),
                                    )
                                }
                                if (slot.ticketUrl != null) {
                                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                                    Text(
                                        "Tickets",
                                        modifier = Modifier
                                            .background(AppColors.CoralLt, RoundedCornerShape(6.dp))
                                            .border(1.dp, AppColors.Coral.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                            .clickable { uriHandler.openUri(slot.ticketUrl) }
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 9.sp, color = AppColors.Coral),
                                    )
                                }
                                if (slot.venueLat != null && slot.venueLng != null) {
                                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                                    Text(
                                        "Directions",
                                        modifier = Modifier
                                            .background(AppColors.SkyLt, RoundedCornerShape(6.dp))
                                            .border(1.dp, AppColors.Sky.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                            .clickable { uriHandler.openUri("https://maps.apple.com/?daddr=${slot.venueLat},${slot.venueLng}") }
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 9.sp, color = AppColors.Sky),
                                    )
                                }
                            }
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

private data class PlanTemplate(
    val title: String,
    val subtitle: String,
    /** Prompt sent to Bo when this template is tapped. */
    val prompt: String,
)

// Pool of templates that rotate on the empty state. Each render of the
// Plans tab picks 3 at random so the empty state doesn't feel stale.
private val PLAN_TEMPLATES = listOf(
    PlanTemplate("Date Night", "Plan the perfect evening for two", "Plan a date night"),
    PlanTemplate("Family Day Out", "Fun for the whole crew", "Plan a family day out"),
    PlanTemplate("Visitors in Town", "Show them the best of the area", "Plan a day showing visitors around"),
    PlanTemplate("Solo Wander", "Just me, exploring", "Plan a solo afternoon for me"),
    PlanTemplate("Brunch + Activity", "Eat well, then go do something", "Plan brunch and an afternoon activity"),
    PlanTemplate("Concert Night", "A show with dinner before", "Plan a concert night with dinner first"),
    PlanTemplate("Sports + Pints", "Game day, properly", "Plan a sports game with drinks before and after"),
    PlanTemplate("Museum Day", "Culture, then a meal", "Plan a museum day with a great lunch"),
    PlanTemplate("Outdoor Adventure", "Get outside while the weather's good", "Plan an outdoor day"),
    PlanTemplate("Late-Night Out", "After-work plans that go late", "Plan a late-night with drinks and music"),
    PlanTemplate("Cheap Date", "Big vibes, small bill", "Plan a fun cheap date — under $40 for two"),
    PlanTemplate("Birthday Plans", "Make it feel special", "Plan a birthday celebration"),
)

@Composable
private fun PlanTemplates(
    rotationKey: Int,
    onNewPlan: () -> Unit,
    onTemplateClick: ((String) -> Unit)? = null,
) {
    // Pick 3 templates at random, keyed on rotationKey. The ViewModel bumps
    // the key on every refresh() — including the OnAppResumeEffect on the
    // Plans tab — so leaving and coming back yields a fresh rotation.
    val templates = remember(rotationKey) {
        PLAN_TEMPLATES.shuffled().take(3)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Get started with a template",
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AppColors.Ink3),
        )
        Spacer(Modifier.height(2.dp))
        templates.forEach { template ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable(onClick = {
                        if (onTemplateClick != null) {
                            onTemplateClick(template.prompt)
                        } else {
                            onNewPlan()
                        }
                    })
                    .localCardSurface(cornerRadius = 14.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        template.title,
                        style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AppColors.Ink),
                    )
                    Text(
                        template.subtitle,
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3),
                    )
                }
                // Bo character on the right — same coral accent the old
                // sparkle used, but ties the entry point to Bo explicitly.
                live.localapp.local.ui.character.Bo(
                    size = 28.dp,
                    showSparkles = false,
                    showShadow = false,
                    modifier = Modifier.size(28.dp),
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
