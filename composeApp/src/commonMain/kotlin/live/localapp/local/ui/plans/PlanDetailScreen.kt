package live.localapp.local.ui.plans

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import live.localapp.local.ui.common.CircleShape
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.model.PlanAttendee
import live.localapp.local.domain.model.PlanDay
import live.localapp.local.domain.model.PlanSlot
import live.localapp.local.platform.MapHeroStop
import live.localapp.local.platform.MapHeroTotals
import live.localapp.local.platform.PlanMapHero
import live.localapp.local.ui.common.localCardSurface
import live.localapp.local.ui.common.pressable
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

// V5 design tokens — local aliases so the file reads close to the original
// Claude Design JSX without leaking tokens into the app-wide namespace.
private val V5Coral get() = AppColors.V5Coral
private val V5CoralDk get() = AppColors.V5CoralDk
private val V5CoralSoft get() = AppColors.V5CoralSoft
private val V5CoralInk get() = AppColors.V5CoralInk
private val V5Ink get() = AppColors.V5Ink
private val V5Ink2 get() = AppColors.V5Ink2
private val V5Ink3 get() = AppColors.V5Ink3
private val V5Ink4 get() = AppColors.V5Ink4
private val V5Line get() = AppColors.V5Line
private val V5LineSoft get() = AppColors.V5LineSoft
private val V5Paper get() = AppColors.V5Paper
private val V5Shade get() = AppColors.V5Shade
private val V5Green get() = AppColors.V5Green

@Composable
fun PlanDetailScreen(
    plan: Plan,
    isShared: Boolean = false,
    // Defaults to false so any caller that forgets to pass this gets the safe
    // (read-only) path instead of silently granting creator powers.
    isCreator: Boolean = false,
    onBack: () -> Unit,
    onShare: () -> Unit = {},
    onSaveToPlan: () -> Unit = {},
    onEditPlan: () -> Unit = {},
    onEditDay: (dayLabel: String) -> Unit = {},
    onSetGroupSize: (Int) -> Unit = {},
    onSetAttendeesCanEdit: (Boolean) -> Unit = {},
    onEditName: (String) -> Unit = {},
    onRemoveSlot: (dayIndex: Int, slotId: String) -> Unit = { _, _ -> },
    onAddSlot: (dayIndex: Int, slot: PlanSlot) -> Unit = { _, _ -> },
    onAddDay: () -> Unit = {},
    onRemoveDay: (dayIndex: Int) -> Unit = {},
    onDeletePlan: () -> Unit = {},
    onSetSlotReserved: (dayIndex: Int, slotId: String, reserved: Boolean) -> Unit = { _, _, _ -> },
    onReplaceSlot: (dayIndex: Int, slot: PlanSlot) -> Unit = { _, _ -> },
    // Slot card tap → parent opens the matching native/Compose event or venue
    // detail screen. iOS parity for "tap a stop to see more." When omitted,
    // the card falls back to opening its inline overflow menu.
    onSlotTap: (slot: PlanSlot) -> Unit = { _ -> },
    // Slot state-machine handlers (iOS parity). Detail capture (party size /
    // ticket count) defaults to going-count in this Phase 1; richer pickers
    // can land in a follow-up.
    onSaveReservationDetails: (dayIndex: Int, slotId: String, partySize: Int, time: String) -> Unit = { _, _, _, _ -> },
    onReservationUnavailable: (dayIndex: Int, slotId: String) -> Unit = { _, _ -> },
    onClearReservation: (dayIndex: Int, slotId: String) -> Unit = { _, _ -> },
    onSaveTicketDetails: (dayIndex: Int, slotId: String, ticketCount: Int) -> Unit = { _, _, _ -> },
    onTicketsUnavailable: (dayIndex: Int, slotId: String) -> Unit = { _, _ -> },
    onClearTickets: (dayIndex: Int, slotId: String) -> Unit = { _, _ -> },
    userLat: Double? = null,
    userLng: Double? = null,
) {
    // Only the creator can edit — attendees always see a read-only plan.
    // The previous attendeesCanEdit escape hatch was removed; the creator
    // is now the single source of truth for plan edits.
    val canEdit = isCreator
    val readOnly = !canEdit || isShared
    val past = plan.isPast
    val firstImageUrl = plan.days.flatMap { it.slots }.firstNotNullOfOrNull { it.imageUrl }

    var showEditNameDialog by remember { mutableStateOf(false) }
    var showGroupSizeSheet by remember { mutableStateOf(false) }
    var emptyDayIndexToConfirm by remember { mutableStateOf<Int?>(null) }
    var confirmDeletePlan by remember { mutableStateOf(false) }
    var addEventDayIndex by remember { mutableStateOf<Int?>(null) }
    var selectedDayIndex by remember { mutableStateOf(0) }
    // Active stop-action sheet (Reservation / Tickets / Unavailable chooser).
    // Drives all chip-tap flows; null = no sheet visible.
    var activeStopSheet by remember(plan.id) { mutableStateOf<StopSheetTarget?>(null) }

    // Creator-side badge claim. INVITE_ACCEPTED / GROUP_5_CONFIRMED /
    // GROUP_10_CONFIRMED milestones belong to the plan creator, awarded the
    // next time they open a plan that's accumulated accepts.
    //
    // Gated on `confirmedCount > 1` so the claim only fires for the specific
    // plan that received an accept — opening an empty Plan B shouldn't toast
    // just because Plan A pushed the global counter past a threshold.
    // (`confirmedCount` includes the creator self-row, so > 1 means at least
    // one non-creator has confirmed on THIS plan.)
    //
    // recordAction is idempotent — re-opens of the same plan after the toast
    // has already fired are no-ops. The toast surfaces via the global
    // celebration overlay in AppNavigation that observes BadgeService.celebrations.
    if (isCreator && plan.confirmedCount > 1) {
        LaunchedEffect(plan.id) {
            val badgeService = org.koin.mp.KoinPlatform.getKoin()
                .get<live.localapp.local.domain.service.BadgeService>()
            badgeService.claimCreatorBadges()
        }
    }

    LaunchedEffect(plan.id) {
        live.localapp.local.observability.Analytics.track(
            live.localapp.local.observability.AnalyticsEvent.planViewed(
                planId = plan.id,
                isCreator = isCreator,
                attendeeCount = plan.confirmedCount,
                source = "plan_detail",
            )
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(V5Paper)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Hero — 250dp map (or gradient fallback) with floating title card
            PlanHero(
                plan = plan,
                coverImageUrl = firstImageUrl,
                isCreator = isCreator,
                selectedDayIndex = selectedDayIndex,
                onSelectDay = { selectedDayIndex = it },
                onBack = onBack,
                onShare = {
                    live.localapp.local.observability.Analytics.track(
                        live.localapp.local.observability.AnalyticsEvent.planShared(
                            planId = plan.id,
                            shareChannel = "system_sheet",
                        )
                    )
                    onShare()
                },
                onEditName = { showEditNameDialog = true },
                onEditPlan = onEditPlan,
                onDeletePlan = { confirmDeletePlan = true },
                // The Android AttendeeCard is already visible inline above the
                // fold, so "View Attendees" doesn't open a sheet — the card is
                // the canonical view. Reserved for a future ModalBottomSheet
                // mirroring the iOS AttendeeSheet if we want full parity.
                onViewAttendees = {},
                onEditAttendees = { showGroupSizeSheet = true },
            )

            // Body — paper sheet with rounded top corners that overlaps the hero by 12dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(y = (-12).dp)
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(V5Paper)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 16.dp, bottom = if (isShared) 100.dp else 110.dp),
            ) {
                // Past plan banner. The ReadOnlyBanner that pointed non-creators
                // to the group chat was removed alongside chat for V1.
                if (past) {
                    PastPlanBanner(dateRange = plan.dateDisplay)
                }

                // Old AttendeeCard ("X of Y going" + Invite pill) removed —
                // replaced by the persistent Share Plan CTA pinned to the
                // bottom of the screen, plus the menu's View Attendees /
                // Edit # of Attendees actions.

                // V1 caps from spec: max 7 days, max 8 stops per day.
                val maxDaysReached = plan.days.size >= 7
                val canAddDay = isCreator && !past && !isShared && !maxDaysReached

                // Multi-day = >1 day AND >1 day actually has stops. Spec §7.5 says
                // a multi-day plan auto-demotes to single-day UI when only one day
                // has stops.
                val daysWithStops = plan.days.count { it.slots.isNotEmpty() }
                val isMultiDay = plan.days.size > 1 && daysWithStops > 1

                // Default selected day: first day with stops, else day 0.
                LaunchedEffect(plan.days) {
                    if (plan.days.isNotEmpty() && plan.days[selectedDayIndex.coerceAtMost(plan.days.lastIndex)].slots.isEmpty()) {
                        val firstWithStops = plan.days.indexOfFirst { it.slots.isNotEmpty() }
                        if (firstWithStops >= 0) selectedDayIndex = firstWithStops
                    }
                }

                if (isMultiDay) {
                    // iOS parity: peek-paging day summary carousel above the
                    // day strip. Each card is full-screen-width with 28dp
                    // horizontal content padding so the neighboring days peek
                    // from the edges. Snap-fling settles to the centered item
                    // and writes back to `selectedDayIndex` so the day strip
                    // and slot list follow.
                    DaySummaryPeekPager(
                        plan = plan,
                        selectedDayIndex = selectedDayIndex.coerceAtMost(plan.days.lastIndex),
                        onDaySelected = { selectedDayIndex = it },
                    )
                    Spacer(Modifier.height(4.dp))
                    // Multi-day: day strip with + Add chip.
                    DayTabStrip(
                        days = plan.days,
                        activeIndex = selectedDayIndex.coerceAtMost(plan.days.lastIndex),
                        onSelect = { selectedDayIndex = it },
                        onAddDay = onAddDay,
                        canAdd = canAddDay,
                        compact = plan.days.size >= 6,
                    )
                } else if (canAddDay && plan.days.isNotEmpty()) {
                    // Single-day: stop-count + inline "+ Add day" row, per spec §2.5.
                    val totalStops = plan.days.sumOf { it.slots.size }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "$totalStops ${if (totalStops == 1) "stop" else "stops"}",
                            style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 18.sp, color = V5Ink),
                        )
                        Spacer(Modifier.weight(1f))
                        Row(
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onAddDay,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, tint = V5Coral, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Add day",
                                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = V5Coral),
                            )
                        }
                    }
                }

                // Day body. When single-day-effective, render the day with the
                // stops (or day 0 if all empty). When multi-day, render the
                // user-selected day.
                val effectiveDayIndex = if (isMultiDay) {
                    selectedDayIndex.coerceAtMost(plan.days.lastIndex)
                } else {
                    val firstWithStops = plan.days.indexOfFirst { it.slots.isNotEmpty() }
                    if (firstWithStops >= 0) firstWithStops else 0
                }
                val activeDay = plan.days.getOrNull(effectiveDayIndex)
                if (activeDay != null) {
                    val maxStopsReached = activeDay.slots.size >= 8
                    val canAddSlot = !readOnly && !past && !maxStopsReached
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        if (activeDay.slots.isEmpty()) {
                            DayEmptyState(
                                readOnly = readOnly || past,
                                onAddEvent = { addEventDayIndex = effectiveDayIndex },
                            )
                        } else {
                            activeDay.slots.forEachIndexed { i, slot ->
                                // iOS parity (PlanDetailView.swift:1200-1226): each
                                // slot row is rendered as a Row composed of a 56dp
                                // timeline rail on the left + the slot card filling
                                // the rest of the width. The rail draws TIME + dot
                                // + vertical connector; the connector continues to
                                // the next slot, and stops at the dot on the last
                                // slot of the day.
                                val isLastSlot = i == activeDay.slots.lastIndex
                                // Row uses IntrinsicSize.Min so the timeline
                                // rail can `fillMaxHeight()` and stretch to the
                                // slot card's natural height — the connector
                                // line lands flush with the card's bottom edge.
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
                                ) {
                                    TimelineRail(
                                        time = slot.time,
                                        isLast = isLastSlot,
                                        modifier = Modifier
                                            .width(56.dp)
                                            .fillMaxHeight(),
                                    )
                                    Box(modifier = Modifier.weight(1f)) {
                                        V5SlotCard(
                                            slot = slot,
                                            readOnly = readOnly || past,
                                            onRemove = {
                                                val totalBefore = plan.days.sumOf { d -> d.slots.size }
                                                onRemoveSlot(effectiveDayIndex, slot.id)
                                                when {
                                                    totalBefore <= 1 -> confirmDeletePlan = true
                                                    activeDay.slots.size <= 1 -> emptyDayIndexToConfirm = effectiveDayIndex
                                                }
                                            },
                                            onMarkReserved = { onSetSlotReserved(effectiveDayIndex, slot.id, true) },
                                            onMarkNotReserved = { onSetSlotReserved(effectiveDayIndex, slot.id, false) },
                                            onReplace = { onReplaceSlot(effectiveDayIndex, slot) },
                                            onSlotTap = { onSlotTap(slot) },
                                            onOpenReservation = {
                                                // Confirmed reservation → jump straight to the
                                                // details sheet in edit mode (Clear button visible).
                                                // Otherwise show the parent Reservation chooser.
                                                activeStopSheet = if (slot.reservationState == live.localapp.local.domain.model.ReservationState.MADE) {
                                                    StopSheetTarget.ReservationDetails(slot, effectiveDayIndex, isEditing = true)
                                                } else {
                                                    StopSheetTarget.Reservation(slot, effectiveDayIndex)
                                                }
                                            },
                                            onOpenTickets = {
                                                activeStopSheet = if (slot.ticketState == live.localapp.local.domain.model.TicketState.BOUGHT) {
                                                    StopSheetTarget.TicketDetails(slot, effectiveDayIndex, isEditing = true)
                                                } else {
                                                    StopSheetTarget.Tickets(slot, effectiveDayIndex)
                                                }
                                            },
                                        )
                                    }
                                }
                                if (i < activeDay.slots.lastIndex) {
                                    activeDay.travel.getOrNull(i)?.let { t ->
                                        TransitRow(mode = t.mode, mins = t.mins)
                                    } ?: Spacer(Modifier.height(10.dp))
                                }
                            }
                            if (canAddSlot) {
                                Spacer(Modifier.height(10.dp))
                                AddEventButton(onClick = { addEventDayIndex = effectiveDayIndex })
                            } else if (maxStopsReached && !readOnly && !past) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "Day full — max 8 stops.",
                                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Normal, fontSize = 11.sp, color = V5Ink4),
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }

                // Delete plan (creator only, bottom of scroll)
                if (isCreator && !past && !isShared) {
                    Spacer(Modifier.height(30.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clickable { confirmDeletePlan = true }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(13.dp), tint = V5Ink4)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Delete plan",
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = V5Ink4),
                        )
                    }
                }
            }
        }

        // Group chat removed for V1 (per Plan Detail spec, April 27, 2026).
        // PlanChatViewModel + bridge code remains in place for re-enable later.

        // Persistent Share Plan CTA — shown when this is the user's own plan
        // (not a shared preview). Subtext adapts to RSVP state per spec:
        //   • solo (groupSize == 1): no subtext
        //   • only creator confirmed: "Only you have confirmed — X total"
        //   • partial confirmation: "X of Y confirmed"
        //   • all confirmed: "X confirmed"
        if (!isShared) {
            val totalGoing = (plan.groupSize ?: plan.attendees.size.coerceAtLeast(1))
                .coerceAtLeast(1)
            val confirmedGoing = plan.attendees.count { it.confirmed }.coerceAtLeast(0)
            val unconfirmed = (totalGoing - confirmedGoing).coerceAtLeast(0)
            val subtext = when {
                totalGoing <= 1 -> null
                unconfirmed == 0 -> "$totalGoing confirmed"
                else -> {
                    val noun = if (unconfirmed == 1) "person" else "people"
                    "$unconfirmed $noun unconfirmed"
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, V5Paper, V5Paper)))
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                // Match the event-detail primary button styling: solid coral
                // fill, 2dp coralDk border, flat coralDk drop-shadow at y=4
                // (radius=0). Stacked "block" depth, consistent across primary
                // CTAs in the app.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = (-4).dp)
                        .drawBehind {
                            val r = 14.dp.toPx()
                            drawRoundRect(
                                color = V5CoralDk.copy(alpha = 0.4f),
                                topLeft = androidx.compose.ui.geometry.Offset(0f, 4.dp.toPx()),
                                size = size,
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                            )
                        }
                        .clip(RoundedCornerShape(14.dp))
                        .background(V5Coral)
                        .border(2.dp, V5CoralDk, RoundedCornerShape(14.dp))
                        .clickable(onClick = onShare)
                        .padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.IosShare,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Share Plan",
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.Black,
                                fontSize = 16.sp,
                                color = Color.White,
                            ),
                        )
                    }
                    if (subtext != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            subtext,
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.85f),
                            ),
                        )
                    }
                }
            }
        }

        // Accept Invite bottom CTA for shared preview
        if (isShared) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, V5Paper, V5Paper)))
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(V5Coral)
                        .clickable(onClick = onSaveToPlan)
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Accept Invite",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color.White),
                    )
                }
            }
        }

        // Sheets
        if (showEditNameDialog) {
            EditPlanNameSheet(
                currentName = plan.title,
                onDismiss = { showEditNameDialog = false },
                onSave = { newName ->
                    onEditName(newName)
                    showEditNameDialog = false
                },
            )
        }

        if (showGroupSizeSheet) {
            GroupSizePickerSheet(
                current = plan.groupSize ?: plan.attendees.size.coerceAtLeast(1),
                onDismiss = { showGroupSizeSheet = false },
                onConfirm = {
                    onSetGroupSize(it)
                    showGroupSizeSheet = false
                },
            )
        }

        // Stop-action sheets (chip-driven). Phase 2: "I have a reservation"
        // and "Tickets already bought" route into dedicated detail sheets
        // (party-size + time stepper, ticket-count stepper) instead of
        // saving with attendee-count defaults — iOS parity.
        val goingCount = plan.attendees.count { it.confirmed }.coerceAtLeast(1)
        when (val sheet = activeStopSheet) {
            is StopSheetTarget.Reservation -> ReservationSheet(
                slot = sheet.slot,
                onMakeReservation = { activeStopSheet = null },
                onMarkUnavailable = {
                    activeStopSheet = StopSheetTarget.UnavailableReservation(sheet.slot, sheet.dayIdx)
                },
                onAlreadyBooked = {
                    activeStopSheet = StopSheetTarget.ReservationDetails(sheet.slot, sheet.dayIdx, isEditing = false)
                },
                onDismiss = { activeStopSheet = null },
            )
            is StopSheetTarget.ReservationDetails -> ReservationDetailsSheet(
                slot = sheet.slot,
                goingCount = goingCount,
                isEditing = sheet.isEditing,
                onSave = { partySize, time ->
                    onSaveReservationDetails(sheet.dayIdx, sheet.slot.id, partySize, time)
                    activeStopSheet = null
                },
                onClear = {
                    onClearReservation(sheet.dayIdx, sheet.slot.id)
                    activeStopSheet = null
                },
                onDismiss = { activeStopSheet = null },
            )
            is StopSheetTarget.Tickets -> TicketsSheet(
                slot = sheet.slot,
                onGetTickets = { activeStopSheet = null },
                onMarkUnavailable = {
                    activeStopSheet = StopSheetTarget.UnavailableTicket(sheet.slot, sheet.dayIdx)
                },
                onAlreadyBought = {
                    activeStopSheet = StopSheetTarget.TicketDetails(sheet.slot, sheet.dayIdx, isEditing = false)
                },
                onDismiss = { activeStopSheet = null },
            )
            is StopSheetTarget.TicketDetails -> TicketDetailsSheet(
                slot = sheet.slot,
                goingCount = goingCount,
                isEditing = sheet.isEditing,
                onSave = { count ->
                    onSaveTicketDetails(sheet.dayIdx, sheet.slot.id, count)
                    activeStopSheet = null
                },
                onClear = {
                    onClearTickets(sheet.dayIdx, sheet.slot.id)
                    activeStopSheet = null
                },
                onDismiss = { activeStopSheet = null },
            )
            is StopSheetTarget.UnavailableReservation -> UnavailableActionSheet(
                title = "No reservations for ${sheet.slot.name}",
                subtitle = "Swap it for something else or take it off the plan.",
                onReplace = {
                    onReplaceSlot(sheet.dayIdx, sheet.slot)
                    activeStopSheet = null
                },
                onRemove = {
                    onRemoveSlot(sheet.dayIdx, sheet.slot.id)
                    activeStopSheet = null
                },
                onDismiss = { activeStopSheet = null },
            )
            is StopSheetTarget.UnavailableTicket -> UnavailableActionSheet(
                title = "No tickets for ${sheet.slot.name}",
                subtitle = "Swap it for something else or take it off the plan.",
                onReplace = {
                    onReplaceSlot(sheet.dayIdx, sheet.slot)
                    activeStopSheet = null
                },
                onRemove = {
                    onRemoveSlot(sheet.dayIdx, sheet.slot.id)
                    activeStopSheet = null
                },
                onDismiss = { activeStopSheet = null },
            )
            null -> Unit
        }

        emptyDayIndexToConfirm?.let { idx ->
            ConfirmModal(
                icon = Icons.Outlined.Delete,
                title = "Remove empty day?",
                body = "${plan.days.getOrNull(idx)?.label ?: "This day"} has no events left.",
                confirmLabel = "Remove day",
                onConfirm = {
                    onRemoveDay(idx)
                    emptyDayIndexToConfirm = null
                },
                onCancel = { emptyDayIndexToConfirm = null },
            )
        }

        if (confirmDeletePlan) {
            ConfirmModal(
                icon = Icons.Outlined.Delete,
                title = "Delete this plan?",
                body = "This will permanently remove '${plan.title}' for everyone going. This action cannot be undone.",
                confirmLabel = "Delete",
                onConfirm = {
                    onDeletePlan()
                    confirmDeletePlan = false
                },
                onCancel = { confirmDeletePlan = false },
            )
        }

        addEventDayIndex?.let { idx ->
            val dayLabel = plan.days.getOrNull(idx)?.label ?: "this day"
            AddEventSheet(
                dayLabel = dayLabel,
                userLat = userLat,
                userLng = userLng,
                onDismiss = { addEventDayIndex = null },
                onAddSlot = { slot ->
                    onAddSlot(idx, slot)
                    addEventDayIndex = null
                },
            )
        }
    }
}

// ─── Hero ────────────────────────────────────────────────────────────

@Composable
private fun PlanHero(
    plan: Plan,
    coverImageUrl: String?,
    isCreator: Boolean,
    selectedDayIndex: Int,
    onSelectDay: (Int) -> Unit = {},
    onBack: () -> Unit,
    onShare: () -> Unit,
    onEditName: () -> Unit,
    onEditPlan: () -> Unit = {},
    onDeletePlan: () -> Unit = {},
    onViewAttendees: () -> Unit = {},
    onEditAttendees: () -> Unit = {},
) {
    val planInitial = plan.title.firstOrNull()?.uppercaseChar()?.toString() ?: "L"

    // Day-aware computations — the hero updates as the day strip changes.
    val day = plan.days.getOrNull(selectedDayIndex)
    val dayStops = day?.slots?.size ?: 0
    val geocoded: List<MapHeroStop> = day?.slots
        ?.mapNotNull { slot ->
            val lat = slot.venueLat
            val lng = slot.venueLng
            if (lat != null && lng != null) MapHeroStop(lat, lng, slot.name) else null
        }
        ?: emptyList()

    var routeTotals by remember { mutableStateOf<MapHeroTotals?>(null) }
    LaunchedEffect(selectedDayIndex) { routeTotals = null }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp)
            // Coral diagonal gradient as the base layer — visible only when
            // PlanMapHero declines to render and there's no cover image.
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFFF6B6B),
                        Color(0xFFE84540),
                        Color(0xFF8C2E33),
                    ),
                ),
            ),
    ) {
        // 1) Try the platform map hero. Returns true on Android when we have
        //    coords; iOS always returns false (handled by SwiftUI separately).
        val mapRendered = PlanMapHero(
            stops = geocoded,
            modifier = Modifier.fillMaxSize(),
            onTotalsChanged = { totals -> routeTotals = totals },
        )

        // 2) When the map didn't render, fall back to the cover image or the
        //    faded plan-initial pattern over the coral gradient.
        if (!mapRendered) {
            if (coverImageUrl != null) {
                AsyncImage(
                    model = coverImageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = planInitial,
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Black,
                        fontSize = 86.sp,
                        color = Color.White.copy(alpha = 0.18f),
                    ),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 18.dp, top = 8.dp),
                )
            }
        }

        // 3) Soft top-only gradient — keeps back/share/menu buttons readable
        //    on the map without darkening the route area in the middle.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = 0.30f),
                            0.30f to Color.Black.copy(alpha = 0.08f),
                            0.55f to Color.Transparent,
                        ),
                    ),
                ),
        )

        // 4) Top toolbar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.weight(1f))
            GlassIconButton(onClick = onShare) {
                Icon(Icons.Outlined.IosShare, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(15.dp))
            }
            if (isCreator) {
                Spacer(Modifier.width(8.dp))
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    GlassIconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreHoriz, contentDescription = "More", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = Color.White,
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("View Attendees", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = V5Ink)) },
                            leadingIcon = { Icon(Icons.Outlined.Place, contentDescription = null, tint = V5Ink2, modifier = Modifier.size(18.dp)) },
                            onClick = { menuOpen = false; onViewAttendees() },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Edit # of Attendees", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = V5Ink)) },
                            leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, tint = V5Ink2, modifier = Modifier.size(18.dp)) },
                            onClick = { menuOpen = false; onEditAttendees() },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Edit Plan", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = V5Ink)) },
                            leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = V5Coral, modifier = Modifier.size(18.dp)) },
                            onClick = { menuOpen = false; onEditPlan() },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Rename Plan", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = V5Ink)) },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, tint = V5Ink2, modifier = Modifier.size(18.dp)) },
                            onClick = { menuOpen = false; onEditName() },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Delete Plan", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = V5Coral)) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = V5Coral, modifier = Modifier.size(18.dp)) },
                            onClick = { menuOpen = false; onDeletePlan() },
                        )
                    }
                }
            }
        }

        // 5) Floating white title card pinned to the bottom of the hero.
        //    Horizontal swipes on the card switch days.
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            FloatingTitleCard(
                plan = plan,
                day = day,
                dayStops = dayStops,
                routeTotals = routeTotals,
                selectedDayIndex = selectedDayIndex,
                onSwipeDay = onSelectDay,
            )
        }
    }
}

@Composable
private fun FloatingTitleCard(
    plan: Plan,
    day: PlanDay?,
    dayStops: Int,
    routeTotals: MapHeroTotals?,
    selectedDayIndex: Int,
    onSwipeDay: (Int) -> Unit,
) {
    val titleHeader = headerTitleLabel(plan)
    val dateLabel = headerDateLabel(plan, day)
    val metrics = headerMetricsLabel(dayStops, routeTotals)
    val totalDays = plan.days.size
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .border(
                    width = 0.5.dp,
                    color = V5Line,
                    shape = RoundedCornerShape(14.dp),
                )
                // Horizontal swipe switches days. Accumulates drag deltas via
                // detectHorizontalDragGestures and commits at gesture end if
                // total displacement exceeds the 50px threshold.
                .pointerInput(totalDays, selectedDayIndex) {
                    if (totalDays <= 1) return@pointerInput
                    var totalDx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDx = 0f },
                        onDragEnd = {
                            val thresholdPx = 50.dp.toPx()
                            if (totalDx <= -thresholdPx) {
                                onSwipeDay((selectedDayIndex + 1).coerceAtMost(totalDays - 1))
                            } else if (totalDx >= thresholdPx) {
                                onSwipeDay((selectedDayIndex - 1).coerceAtLeast(0))
                            }
                            totalDx = 0f
                        },
                        onDragCancel = { totalDx = 0f },
                        onHorizontalDrag = { _, dx -> totalDx += dx },
                    )
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (titleHeader.isNotBlank()) {
                Text(
                    titleHeader.uppercase(),
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp,
                        color = V5Coral,
                        letterSpacing = 0.8.sp,
                    ),
                )
            }
            if (dateLabel.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    dateLabel,
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Black,
                        fontSize = 22.sp,
                        color = V5Ink,
                        lineHeight = 26.sp,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (metrics.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    metrics,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = V5Ink3,
                    ),
                )
            }
        }
        // (Stop-count badge removed — the metrics line already shows it.)
    }
}

private fun headerTitleLabel(plan: Plan): String {
    // Strip "Family Trip: May 13-16" → "Family Trip" so the all-caps header
    // line doesn't duplicate the date range shown below it.
    val raw = plan.title
    val colon = raw.indexOf(':')
    return if (colon >= 0) raw.substring(0, colon).trim() else raw
}

private fun headerDateLabel(plan: Plan, day: PlanDay?): String {
    val raw = day?.label
    if (!raw.isNullOrBlank()) return shortDayLabel(raw)
    return plan.dateDisplay
}

private fun shortDayLabel(raw: String): String {
    // "Saturday, May 16" → "Sat, May 16". Single-token labels pass through.
    val parts = raw.split(",", limit = 2)
    return if (parts.size == 2) {
        val short = parts[0].trim().take(3)
        "$short, ${parts[1].trim()}"
    } else raw
}

private fun headerMetricsLabel(dayStops: Int, totals: MapHeroTotals?): String {
    val parts = mutableListOf<String>()
    if (dayStops > 0) parts += "$dayStops ${if (dayStops == 1) "stop" else "stops"}"
    if (totals != null) {
        if (totals.miles > 0.0) parts += formatMiles(totals.miles)
        if (totals.minutes > 0) parts += "~${formatTravelMinutes(totals.minutes)} ${totals.dominantMode}"
    }
    return parts.joinToString(separator = " · ")
}

private fun formatTravelMinutes(mins: Int): String {
    if (mins >= 60) {
        val hr = mins / 60
        val rem = mins % 60
        return if (rem == 0) "$hr hr" else "$hr hr $rem min"
    }
    return "$mins min"
}

// KMP commonMain has no printf formatter, so do tenths manually.
private fun formatMiles(value: Double): String {
    val rounded = (value * 10).toLong()
    val intPart = rounded / 10
    val frac = (rounded - intPart * 10).let { if (it < 0) -it else it }
    return "$intPart.$frac mi"
}

@Composable
private fun GlassIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ─── Attendee card ────────────────────────────────────────────────────

@Composable
private fun AttendeeCard(
    attendees: List<PlanAttendee>,
    groupSize: Int,
    creatorId: String?,
    onOpenGroupSize: (() -> Unit)?,
    onInvite: () -> Unit,
    onShare: () -> Unit,
) {
    val accepted = attendees.filter { it.confirmed }
    val pending = attendees.filter { !it.confirmed }
    val shortCount = (groupSize - attendees.size).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .border(1.dp, V5Line, RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "WHO'S COMING",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = V5Ink3, letterSpacing = 0.5.sp),
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${accepted.size}",
                        style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = V5Ink, letterSpacing = (-0.3).sp),
                    )
                    Text(
                        " of $groupSize",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Normal, fontSize = 14.sp, color = V5Ink4),
                    )
                    if (pending.isNotEmpty()) {
                        Text(
                            "  ·  ${pending.size} pending",
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = V5Ink3),
                        )
                    }
                }
            }
            if (onOpenGroupSize != null) {
                GroupSizeChip(value = groupSize, onClick = onOpenGroupSize)
            }
        }

        Spacer(Modifier.height(14.dp))

        // Avatar cells row — render up to 5 cells plus the Invite cell
        val cells = buildList {
            accepted.forEach { add(AttendeeCell.Person(it)) }
            pending.forEach { add(AttendeeCell.Person(it)) }
            repeat(shortCount) { add(AttendeeCell.OpenSlot) }
        }
        val maxVisible = 5
        val visible = cells.take(maxVisible)
        val extra = (cells.size - visible.size).coerceAtLeast(0)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            visible.forEach { cell ->
                AttendeeCellView(cell = cell, modifier = Modifier.weight(1f), onInvite = onInvite)
            }
            // Invite CTA cell — always present
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onInvite,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(V5CoralSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Invite", tint = V5Coral, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Invite",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = V5Coral),
                )
            }
        }

        if (extra > 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                "+ $extra more",
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Normal, fontSize = 11.sp, color = V5Ink3, textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(14.dp))

        // Share plan link button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(V5Coral)
                .clickable(onClick = { onShare() })
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.IosShare, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Share plan link",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White),
                )
            }
        }
    }
}

private sealed interface AttendeeCell {
    data class Person(val person: PlanAttendee) : AttendeeCell
    object OpenSlot : AttendeeCell
}

@Composable
private fun AttendeeCellView(cell: AttendeeCell, modifier: Modifier, onInvite: () -> Unit) {
    when (cell) {
        is AttendeeCell.Person -> {
            val p = cell.person
            Column(
                modifier = modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                V5AttendeeAvatar(attendee = p, size = 44.dp)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (p.confirmed) p.name.take(12) else "Invited",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        color = if (p.confirmed) V5Ink2 else V5Ink3,
                        fontStyle = if (!p.confirmed) FontStyle.Italic else FontStyle.Normal,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AttendeeCell.OpenSlot -> {
            Column(
                modifier = modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onInvite,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(V5LineSoft)
                        .border(1.5.dp, V5Line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Open slot", tint = V5Ink4, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Open",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Medium, fontSize = 11.sp, color = V5Ink4),
                )
            }
        }
    }
}

@Composable
private fun V5AttendeeAvatar(attendee: PlanAttendee, size: androidx.compose.ui.unit.Dp) {
    val initial = attendee.initials.take(1).uppercase().ifBlank { attendee.name.take(1).uppercase() }
    if (!attendee.confirmed) {
        // Pending → dashed outline circle with "?"
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(V5Shade)
                .border(1.5.dp, V5Ink4, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "?",
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = V5Ink3),
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color(attendee.avatarColor)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initial,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Color.White),
            )
        }
    }
}

@Composable
private fun GroupSizeChip(value: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.5.dp, V5Ink, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "GROUP",
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = V5Ink3, letterSpacing = 0.6.sp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "$value",
            style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = V5Ink, letterSpacing = (-0.3).sp),
        )
        Spacer(Modifier.width(4.dp))
        Text("\u25BE", style = TextStyle(fontSize = 10.sp, color = V5Ink2))
    }
}

// ─── Banners ────────────────────────────────────────────────────────

@Composable
private fun PastPlanBanner(dateRange: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(V5LineSoft)
                .border(1.dp, V5Line, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("\u23F1", style = TextStyle(fontSize = 13.sp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "This plan has passed.",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = V5Ink2),
                )
                if (dateRange.isNotBlank()) {
                    Text(
                        "Archived — read only.  ·  $dateRange",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Normal, fontSize = 11.sp, color = V5Ink3),
                    )
                }
            }
        }
    }
}

// ─── Day summary peek-pager ──────────────────────────────────────────
//
// iOS parity (PlanDetailView.swift:614-664) — a horizontal pager of
// day-summary cards. Each card is full-screen-width with 28dp content
// margins so the previous/next day peek at the edges. Snap-fling settles
// to the centered card; the centered index is written back to
// `selectedDayIndex` so the active day stays in sync with the rest of
// the screen.
@Composable
private fun DaySummaryPeekPager(
    plan: Plan,
    selectedDayIndex: Int,
    onDaySelected: (Int) -> Unit,
) {
    val days = plan.days
    if (days.isEmpty()) return

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedDayIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    // When `selectedDayIndex` is changed externally (e.g. from the day
    // strip below), drive the pager to match.
    LaunchedEffect(selectedDayIndex) {
        if (listState.firstVisibleItemIndex != selectedDayIndex) {
            listState.animateScrollToItem(selectedDayIndex)
        }
    }

    // When the user settles the pager onto a new card, publish that as
    // the selected day. Using `derivedStateOf` keyed on
    // `firstVisibleItemIndex` together with `isScrollInProgress` only
    // commits the change once the fling has come to rest.
    val settledIndex by remember(listState) {
        derivedStateOf {
            if (listState.isScrollInProgress) null else listState.firstVisibleItemIndex
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { settledIndex }
            .collect { idx ->
                if (idx != null && idx != selectedDayIndex && idx in days.indices) {
                    onDaySelected(idx)
                }
            }
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        state = listState,
        flingBehavior = flingBehavior,
        // 28dp content padding leaves 28dp of the neighbouring card
        // visible on each side — the "peek" feel from iOS.
        contentPadding = PaddingValues(horizontal = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(days) { idx, day ->
            DaySummaryPagerCard(
                plan = plan,
                day = day,
                dayIndex = idx,
                isActive = idx == selectedDayIndex,
            )
        }
    }
}

@Composable
private fun DaySummaryPagerCard(
    plan: Plan,
    day: PlanDay,
    dayIndex: Int,
    isActive: Boolean,
) {
    val titleHeader = headerTitleLabel(plan)
    val dateLabel = headerDateLabel(plan, day)
    // Sibling cards don't have access to `routeTotals` (that's the active
    // day's MapHero output), so they show stops-only metrics. The active
    // card's full metrics live in the existing FloatingTitleCard above.
    val metrics = headerMetricsLabel(day.slots.size, totals = null)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // 56dp = 28dp peek on each side. Card fills the visible width minus
        // those gutters so exactly one card centers in the viewport.
        val cardWidth = maxWidth - 56.dp
        Column(
            modifier = Modifier
                .width(cardWidth)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .border(
                    width = if (isActive) 1.dp else 0.5.dp,
                    color = if (isActive) V5CoralDk else V5Line,
                    shape = RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (titleHeader.isNotBlank()) {
                Text(
                    titleHeader.uppercase(),
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp,
                        color = V5Coral,
                        letterSpacing = 0.8.sp,
                    ),
                )
            }
            if (dateLabel.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    dateLabel,
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Black,
                        fontSize = 22.sp,
                        color = V5Ink,
                        lineHeight = 26.sp,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (metrics.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    metrics,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = V5Ink3,
                    ),
                )
            }
        }
    }
}

// ─── Day tabs ────────────────────────────────────────────────────────

@Composable
private fun DayTabStrip(
    days: List<PlanDay>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onAddDay: () -> Unit,
    canAdd: Boolean,
    compact: Boolean,
) {
    // Trim trailing "phantom" days — days with 0 stops AND no day-of-week
    // label. These come from upstream code adding orphan placeholder days;
    // rendering them is confusing because they look like real future days.
    // A trailing empty day with a real label (e.g. "Sun, May 17") is kept.
    val trimmed = days.dropLastWhile { day ->
        day.slots.isEmpty() && shortDayOfWeekKt(day).isEmpty()
    }
    val visibleDays = if (trimmed.isNotEmpty()) trimmed else days

    val totalCells = visibleDays.size + (if (canAdd) 1 else 0)
    val fitsWithoutScroll = totalCells <= 4

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(0.5.dp, V5Line, RoundedCornerShape(14.dp)),
    ) {
        if (fitsWithoutScroll) {
            // Equal-width cells filling the card.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                visibleDays.forEachIndexed { i, day ->
                    DayCell(
                        day = day,
                        active = i == activeIndex,
                        onClick = { onSelect(i) },
                        modifier = Modifier.weight(1f),
                    )
                    val isLast = i == visibleDays.lastIndex && !canAdd
                    if (!isLast) {
                        Box(
                            modifier = Modifier
                                .width(0.5.dp)
                                .height(60.dp)
                                .background(V5Line),
                        )
                    }
                }
                if (canAdd) {
                    AddDayCell(onClick = onAddDay, modifier = Modifier.weight(1f))
                }
            }
        } else {
            // >4 cells — fixed minimum width and horizontal scroll.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                visibleDays.forEachIndexed { i, day ->
                    DayCell(
                        day = day,
                        active = i == activeIndex,
                        onClick = { onSelect(i) },
                        modifier = Modifier.width(78.dp),
                    )
                    val isLast = i == visibleDays.lastIndex && !canAdd
                    if (!isLast) {
                        Box(
                            modifier = Modifier
                                .width(0.5.dp)
                                .height(60.dp)
                                .background(V5Line),
                        )
                    }
                }
                if (canAdd) {
                    AddDayCell(onClick = onAddDay, modifier = Modifier.width(78.dp))
                }
            }
        }
    }
}

// Day-of-week extractor used by both DayCell rendering and the phantom-day
// trim filter. Returns "" when no real DOW label is available.
private fun shortDayOfWeekKt(day: PlanDay): String {
    val parts = day.label.replace("Today ", "").replace("Tomorrow ", "").split(",").map { it.trim() }
    val first = parts.firstOrNull() ?: day.shortName
    if (first.isBlank() || first.all { it.isDigit() }) return ""
    return first.take(3).uppercase()
}

@Composable
private fun DayCell(
    day: PlanDay,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Parse day.label "Friday, Nov 8" → dow = "FRI", date = "8"
    val parts = day.label.replace("Today ", "").replace("Tomorrow ", "").split(",").map { it.trim() }
    val dow = (parts.firstOrNull() ?: day.shortName).take(3).uppercase()
    val datePart = parts.getOrNull(1)?.split(" ")?.lastOrNull() ?: ""

    val textColor = if (active) Color.White else V5Ink
    val subColor = if (active) Color.White.copy(alpha = 0.85f) else V5Ink3
    val activeBackground = Brush.verticalGradient(
        colors = listOf(V5Coral, V5CoralDk),
    )
    Column(
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (active) Modifier.background(activeBackground) else Modifier
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            dow,
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 9.sp, color = subColor, letterSpacing = 0.8.sp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            datePart.ifBlank { "·" },
            style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 22.sp, color = textColor, letterSpacing = (-0.4).sp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (day.slots.isEmpty()) "—" else "${day.slots.size} ${if (day.slots.size == 1) "stop" else "stops"}",
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = subColor),
        )
    }
}

@Composable
private fun AddDayCell(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.Add,
            contentDescription = "Add day",
            tint = V5Coral,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "ADD",
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 9.sp, color = V5Coral, letterSpacing = 0.8.sp),
        )
    }
}

// ─── Day empty state ────────────────────────────────────────────────

@Composable
private fun DayEmptyState(readOnly: Boolean, onAddEvent: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .border(1.5.dp, V5Line, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(V5CoralSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Star, contentDescription = null, tint = V5Coral, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Your plan is empty",
            style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = V5Ink, letterSpacing = (-0.2).sp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (readOnly) "The planner hasn't added anything yet." else "Build with Bo, or add a stop yourself.",
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Normal, fontSize = 12.sp, color = V5Ink3, textAlign = TextAlign.Center, lineHeight = 16.sp),
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (!readOnly) {
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(V5Coral)
                    .clickable(onClick = onAddEvent)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Add an event",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White),
                    )
                }
            }
        }
    }
}

@Composable
private fun AddEventButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressable(onClick = onClick)
            .localCardSurface(cornerRadius = 16.dp, borderWidth = 1.dp, borderColor = V5Line)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, tint = V5Coral, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            "Add event",
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = V5Coral),
        )
    }
}

// ─── Timeline rail ───────────────────────────────────────────────────
//
// iOS PlanDetailView.swift:1200-1226 — a 56pt-wide column to the LEFT of
// each slot card containing the slot's TIME label, a coral dot, and a
// thin vertical line that continues down to the next slot. The line stops
// at the dot on the last slot of a day so the day "ends" visually.
@Composable
private fun TimelineRail(
    time: String,
    isLast: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(end = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // TIME label — top-aligned, matches iOS Body Bold 10pt V5Ink3.
        if (time.isNotBlank()) {
            Text(
                time.uppercase(),
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = V5Ink3,
                    letterSpacing = 0.4.sp,
                ),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
        }
        // Coral dot.
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(V5Coral),
        )
        // Vertical connector — fills the remaining height of the row,
        // pulling the rail flush with the bottom of the slot card. On the
        // last slot the line is suppressed so the day terminates at the dot.
        if (!isLast) {
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .weight(1f)
                    .background(V5Line),
            )
        }
    }
}

// ─── Slot card + transit row ─────────────────────────────────────────

@Composable
private fun V5SlotCard(
    slot: PlanSlot,
    readOnly: Boolean,
    onRemove: () -> Unit,
    onMarkReserved: () -> Unit = {},
    onMarkNotReserved: () -> Unit = {},
    onReplace: () -> Unit = {},
    // iOS parity: tapping the slot card body opens the venue/event detail
    // screen. Parent provides the action; when null, falls back to opening
    // the inline overflow menu so the card is never a dead tap target.
    onSlotTap: (() -> Unit)? = null,
    // Chip-driven state-machine entry points (iOS parity). Parent renders the
    // matching ReservationSheet / TicketsSheet when invoked.
    onOpenReservation: () -> Unit = {},
    onOpenTickets: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    var showDirectionsSheet by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    if (showDirectionsSheet && slot.venueLat != null && slot.venueLng != null) {
        DirectionsSheet(lat = slot.venueLat, lng = slot.venueLng, name = slot.name, onDismiss = { showDirectionsSheet = false })
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // iOS parity: 14pt corner radius + 2pt coralDk overlay stroke.
            // (Was 16.dp + 1.dp V5Line; pulled in 2026-05-19 to match iOS.)
            .localCardSurface(cornerRadius = 14.dp, borderWidth = 2.dp, borderColor = V5CoralDk)
            // Slot body is tappable. Use clickable (not pointerInput) — the
            // lambda is invoked fresh each tap so there's no stale-capture
            // risk like the For-Kids→Sports misroute (2026-05-20).
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    val tap = onSlotTap
                    if (tap != null) tap() else menuOpen = true
                },
            )
            .padding(12.dp),
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(V5Shade),
            contentAlignment = Alignment.Center,
        ) {
            if (slot.imageUrl != null) {
                AsyncImage(
                    model = slot.imageUrl,
                    contentDescription = slot.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Outlined.Place, contentDescription = null, tint = V5Ink4, modifier = Modifier.size(24.dp))
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    slot.time.uppercase(),
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = V5Coral, letterSpacing = 0.5.sp),
                )
                if (slot.isReserved) {
                    Spacer(Modifier.width(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Check, contentDescription = null, tint = V5Green, modifier = Modifier.size(10.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(
                            "RESERVATION CONFIRMED",
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = V5Green, letterSpacing = 0.4.sp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!readOnly) {
                    Box {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { menuOpen = true },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.MoreHoriz, contentDescription = "More", tint = V5Ink4, modifier = Modifier.size(16.dp))
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            containerColor = Color.White,
                        ) {
                            // Reservation actions for restaurant-like slots
                            if (isRestaurantSlot(slot)) {
                                if (slot.isReserved) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("Mark as not booked", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = V5Ink)) },
                                        leadingIcon = { Icon(Icons.Outlined.Close, contentDescription = null, tint = V5Ink2, modifier = Modifier.size(18.dp)) },
                                        onClick = { menuOpen = false; onMarkNotReserved() },
                                    )
                                } else {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("I have a reservation", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = V5Ink)) },
                                        leadingIcon = { Icon(Icons.Outlined.Check, contentDescription = null, tint = V5Green, modifier = Modifier.size(18.dp)) },
                                        onClick = { menuOpen = false; onMarkReserved() },
                                    )
                                }
                            }
                            if (slot.venueLat != null && slot.venueLng != null) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Get directions", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = V5Ink)) },
                                    leadingIcon = { Icon(Icons.Outlined.Place, contentDescription = null, tint = V5Ink2, modifier = Modifier.size(18.dp)) },
                                    onClick = { menuOpen = false; showDirectionsSheet = true },
                                )
                            }
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Replace this stop", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = V5Ink)) },
                                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = V5Coral, modifier = Modifier.size(18.dp)) },
                                onClick = { menuOpen = false; onReplace() },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Remove from plan", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = V5Coral)) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = V5Coral, modifier = Modifier.size(18.dp)) },
                                onClick = { menuOpen = false; onRemove() },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                slot.name,
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = V5Ink, letterSpacing = (-0.2).sp, lineHeight = 18.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val kind = slotKind(slot)
            val location = slotLocation(slot)
            if (kind.isNotBlank() || location.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        append(kind)
                        if (kind.isNotBlank() && location.isNotBlank()) append(" · ")
                        append(location)
                    },
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Normal, fontSize = 12.sp, color = V5Ink3),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (slot.venueRating != null || slot.isFree || slot.price.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (slot.venueRating != null) {
                        Text(
                            "\u2605 ${slot.venueRating}",
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = V5Ink2),
                        )
                    }
                    if (slot.isFree) {
                        if (slot.venueRating != null) Spacer(Modifier.width(8.dp))
                        Text(
                            "Free",
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = V5Green),
                        )
                    } else if (slot.price.isNotBlank()) {
                        if (slot.venueRating != null) Spacer(Modifier.width(8.dp))
                        Text(
                            slot.price,
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Normal, fontSize = 11.sp, color = V5Ink3),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            SlotCtas(
                slot = slot,
                readOnly = readOnly,
                onDirections = { showDirectionsSheet = true },
                onOpenReservation = onOpenReservation,
                onOpenTickets = onOpenTickets,
            )
        }
    }
}

// Venue-type nouns — matched against name OR category.
private val RESTAURANT_CATEGORIES = listOf(
    "restaurant", "bar", "brewery", "winery", "cafe", "café", "bistro", "pub",
    "tavern", "grill", "steakhouse", "pizzeria", "pizza", "bakery", "diner",
    "eatery", "kitchen", "food", "gastropub", "trattoria", "osteria", "cantina",
    "taqueria", "ramen", "noodle", "bbq", "barbecue", "coffee", "lounge", "cocktail",
)

// Cuisine adjectives — matched against category ONLY. These appear in a
// `cuisine_or_type` value ("contemporary American", "Italian") but never in a
// personal-location activity label ("Dinner", "Brunch", "Drinks"), so they're a
// safe dining signal. `recommend_and_save_venue` is dining-only by definition.
private val CUISINE_WORDS = listOf(
    "american", "italian", "mexican", "french", "thai", "chinese", "japanese",
    "sushi", "seafood", "mediterranean", "indian", "korean", "vietnamese",
    "spanish", "greek", "contemporary", "wine",
)

private fun isRestaurantSlot(slot: PlanSlot): Boolean {
    val cat = slot.venueCategory?.lowercase() ?: ""
    val nameLower = slot.name.lowercase()
    return slot.eventId == null && (
        RESTAURANT_CATEGORIES.any { cat.contains(it) } ||
            RESTAURANT_CATEGORIES.any { nameLower.contains(it) } ||
            CUISINE_WORDS.any { cat.contains(it) }
        )
}

private fun slotKind(slot: PlanSlot): String {
    if (isRestaurantSlot(slot)) return inferMealType(slot.time).ifBlank { "Dining" }
    if (slot.venueCategory != null) return slot.venueCategory.replaceFirstChar { it.uppercase() }
    return ""
}

private fun slotLocation(slot: PlanSlot): String {
    if (!slot.venueAddress.isNullOrBlank()) {
        return slot.venueAddress.substringBefore(",").trim()
    }
    return slot.detail.take(30)
}

@Composable
private fun TransitRow(mode: String, mins: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f).height(1.dp).background(V5Line))
        Spacer(Modifier.width(8.dp))
        Icon(
            if (mode == "walk") Icons.Outlined.DirectionsWalk else Icons.Outlined.DirectionsCar,
            contentDescription = null,
            tint = V5Ink3,
            modifier = Modifier.size(11.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "$mins MIN ${mode.uppercase()}",
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = V5Ink3, letterSpacing = 0.8.sp),
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f).height(1.dp).background(V5Line))
    }
}


// ─── Confirm modal ──────────────────────────────────────────────────

@Composable
private fun ConfirmModal(
    icon: ImageVector,
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        shape = RoundedCornerShape(20.dp),
        containerColor = Color.White,
        icon = {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(V5CoralSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = V5Coral, modifier = Modifier.size(20.dp))
            }
        },
        title = {
            Text(
                title,
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = V5Ink),
            )
        },
        text = {
            Text(
                body,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Normal, fontSize = 14.sp, color = V5Ink2, lineHeight = 20.sp),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, color = V5Coral),
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text(
                    "Cancel",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, color = V5Ink3),
                )
            }
        },
    )
}

// ─── Group size picker sheet ────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupSizePickerSheet(current: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var value by remember { mutableStateOf(current.coerceIn(1, 20)) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(width = 44.dp, height = 5.dp).background(V5Line, RoundedCornerShape(3.dp)))
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Group size",
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = V5Ink),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "How many people are you planning for?",
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Normal, fontSize = 13.sp, color = V5Ink3, textAlign = TextAlign.Center),
            )
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StepperButton(enabled = value > 1, onClick = { value = (value - 1).coerceAtLeast(1) }, icon = Icons.Outlined.Close)
                Text(
                    "$value",
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 56.sp, color = V5Ink, letterSpacing = (-1).sp),
                )
                StepperButton(enabled = value < 20, onClick = { value = (value + 1).coerceAtMost(20) }, icon = Icons.Outlined.Add)
            }
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(V5Coral)
                    .clickable { onConfirm(value) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Save",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StepperButton(enabled: Boolean, onClick: () -> Unit, icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (enabled) V5Shade else V5LineSoft)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) V5Ink else V5Ink4, modifier = Modifier.size(20.dp))
    }
}

// ─── Edit plan name dialog (reused, restyled) ──────────────────────

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EditPlanNameSheet(currentName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var nameInput by remember { mutableStateOf(currentName) }
    var error by remember { mutableStateOf<String?>(null) }
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val charLimit = 60
    val trimmed = nameInput.trim()
    val canSave = trimmed.isNotEmpty() && trimmed != currentName.trim() && trimmed.length <= charLimit
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Change plan name",
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = V5Ink),
            )
            Spacer(Modifier.height(14.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = nameInput,
                onValueChange = {
                    nameInput = it.take(charLimit)
                    error = null
                },
                textStyle = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = V5Ink),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(V5Coral),
                singleLine = true,
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(V5Shade)
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                    ) {
                        if (nameInput.isEmpty()) {
                            Text(
                                "Plan name",
                                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Normal, fontSize = 15.sp, color = V5Ink4),
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (error != null) {
                    Text(
                        error!!,
                        modifier = Modifier.weight(1f),
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = V5Coral),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Text(
                    "${nameInput.length}/$charLimit",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Normal, fontSize = 11.sp, color = V5Ink4),
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(V5Shade)
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Cancel",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = V5Ink2),
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (canSave) V5Coral else V5Coral.copy(alpha = 0.4f))
                        .clickable(enabled = canSave) {
                            val validationError = live.localapp.local.ProfanityFilter.validate(trimmed)
                            if (validationError != null) {
                                error = validationError
                            } else {
                                onSave(trimmed)
                            }
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Save",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 14.sp, color = Color.White),
                    )
                }
            }
        }
    }
}

// ─── Slot CTA chip ──────────────────────────────────────────────────
//
// V1 reservation flow per Plan Detail spec (April 27, 2026):
//   - Restaurant + State A (no reservation): "Make reservation" → OpenTable.
//   - Restaurant + State B (reserved): "View booking" → OpenTable for venue.
//   - Non-restaurant w/ ticket URL: "Tickets" → ticket URL.
//   - Anything with coords: "Directions" → DirectionsSheet.

@Composable
private fun SlotCtas(
    slot: PlanSlot,
    readOnly: Boolean,
    onDirections: () -> Unit,
    onOpenReservation: () -> Unit,
    onOpenTickets: () -> Unit,
) {
    val hasLocation = slot.venueLat != null && slot.venueLng != null
    val isRestaurant = isRestaurantSlot(slot)
    val isTicketed = slot.eventId != null && !slot.ticketUrl.isNullOrBlank()

    // iOS parity: primary state-aware chip + Directions chip on the same row.
    // - Restaurant: "Reservation" / "Booked · N" / "No reservations" (or "View
    //   booking" on read-only when confirmed).
    // - Ticketed: "Tickets" / "N tickets" with green check / "No tickets".
    // - Free RSVP / venue stops: no primary chip, Directions only.
    // Tapping primary chip opens the corresponding sheet; Directions opens
    // the existing DirectionsSheet.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            isRestaurant && slot.reservationState == live.localapp.local.domain.model.ReservationState.MADE -> {
                val label = slot.reservationPartySize?.let { "Booked · $it" } ?: "Booked"
                StateChip(label = label, icon = Icons.Outlined.Check, accent = V5Green, onClick = { if (!readOnly) onOpenReservation() })
            }
            isRestaurant && slot.reservationState == live.localapp.local.domain.model.ReservationState.UNAVAILABLE -> {
                StateChip(label = "No reservations", icon = Icons.Outlined.Close, accent = V5Coral, onClick = { if (!readOnly) onOpenReservation() })
            }
            isRestaurant && !readOnly -> {
                ActionChip(label = "Reservation", icon = Icons.Outlined.Place, onClick = onOpenReservation)
            }
            isTicketed && slot.ticketState == live.localapp.local.domain.model.TicketState.BOUGHT -> {
                val count = slot.ticketCount ?: 1
                val label = "$count ${if (count == 1) "ticket" else "tickets"}"
                StateChip(label = label, icon = Icons.Outlined.Check, accent = V5Green, onClick = { if (!readOnly) onOpenTickets() })
            }
            isTicketed && slot.ticketState == live.localapp.local.domain.model.TicketState.UNAVAILABLE -> {
                StateChip(label = "No tickets", icon = Icons.Outlined.Close, accent = V5Coral, onClick = { if (!readOnly) onOpenTickets() })
            }
            isTicketed && !readOnly -> {
                ActionChip(label = "Tickets", icon = Icons.Outlined.AutoAwesome, onClick = onOpenTickets)
            }
        }
        if (hasLocation) {
            ActionChip(label = "Directions", icon = Icons.Outlined.Place, onClick = onDirections)
        }
    }
}

@Composable
private fun StateChip(label: String, icon: ImageVector, accent: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(accent.copy(alpha = 0.12f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = accent),
        )
    }
}

@Composable
private fun ActionChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, V5Line, RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = V5Ink2, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = V5Ink2),
        )
    }
}

// ─── Directions sheet (kept from original) ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectionsSheet(lat: Double, lng: Double, name: String, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val encodedName = name.replace(" ", "+")
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(width = 44.dp, height = 5.dp).background(V5Line, RoundedCornerShape(3.dp)))
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text("Get Directions", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = V5Ink))
            Spacer(Modifier.height(16.dp))
            listOf(
                "Apple Maps" to "https://maps.apple.com/?daddr=$lat,$lng&q=$encodedName",
                "Google Maps" to "https://www.google.com/maps/dir/?api=1&destination=$lat,$lng",
                "Uber" to "uber://?action=setPickup&dropoff[latitude]=$lat&dropoff[longitude]=$lng&dropoff[nickname]=$encodedName",
                "Lyft" to "lyft://ridetype?id=lyft&destination[latitude]=$lat&destination[longitude]=$lng",
            ).forEach { (label, url) ->
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { uriHandler.openUri(url); onDismiss() }.padding(vertical = 14.dp),
                ) {
                    Text(label, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = V5Ink))
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(V5LineSoft))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Infer meal type from a time string like "7 PM", "11 AM", "6:30 PM" */
private fun inferMealType(time: String): String {
    val hour = try {
        val clean = time.replace(":", " ").uppercase()
        val parts = clean.split(" ").filter { it.isNotBlank() }
        var h = parts.firstOrNull()?.toIntOrNull() ?: return ""
        if (clean.contains("PM") && h != 12) h += 12
        if (clean.contains("AM") && h == 12) h = 0
        h
    } catch (_: Exception) { return "" }

    return when {
        hour in 6..10 -> "Breakfast"
        hour == 11 -> "Brunch"
        hour in 12..14 -> "Lunch"
        hour in 15..16 -> "Afternoon"
        hour in 17..20 -> "Dinner"
        hour >= 21 -> "Drinks"
        else -> ""
    }
}

// ─── Stop-action sheets (iOS parity for chip-driven Reservation/Tickets) ────
//
// Mirrors the iOS PlanDetailView StopSheet flow. The slot's primary-action
// chip opens ReservationSheet or TicketsSheet; the unavailable CTA in each
// bounces through UnavailableActionSheet (Replace event / Remove event)
// instead of jumping straight to Bo. Detail capture (party-size + time for
// reservations, ticket count for tickets) is currently bound to sensible
// defaults — full pickers can come in a Phase 2.

internal sealed interface StopSheetTarget {
    val slot: PlanSlot
    val dayIdx: Int

    data class Reservation(override val slot: PlanSlot, override val dayIdx: Int) : StopSheetTarget
    data class ReservationDetails(override val slot: PlanSlot, override val dayIdx: Int, val isEditing: Boolean) : StopSheetTarget
    data class Tickets(override val slot: PlanSlot, override val dayIdx: Int) : StopSheetTarget
    data class TicketDetails(override val slot: PlanSlot, override val dayIdx: Int, val isEditing: Boolean) : StopSheetTarget
    data class UnavailableReservation(override val slot: PlanSlot, override val dayIdx: Int) : StopSheetTarget
    data class UnavailableTicket(override val slot: PlanSlot, override val dayIdx: Int) : StopSheetTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReservationSheet(
    slot: PlanSlot,
    onMakeReservation: () -> Unit,
    onMarkUnavailable: () -> Unit,
    onAlreadyBooked: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val reservationUrl = slot.reservationUrl?.takeIf { it.isNotBlank() }
        ?: "https://www.opentable.com/s?term=${slot.name.replace(" ", "+")}"
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(width = 44.dp, height = 5.dp).background(V5Line, RoundedCornerShape(3.dp)))
        },
    ) {
        StopActionSheetBody(
            title = "Reservation at ${slot.name}",
            subtitle = "Make it official, mark it unavailable, or save what you've already booked.",
            primary = StopAction("Make reservation", Icons.Outlined.Place) {
                uriHandler.openUri(reservationUrl)
                onDismiss()
            },
            secondaries = listOf(
                StopAction("No reservations available", Icons.Outlined.Close, onMarkUnavailable),
                StopAction("I have a reservation", Icons.Outlined.Check, onAlreadyBooked),
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TicketsSheet(
    slot: PlanSlot,
    onGetTickets: () -> Unit,
    onMarkUnavailable: () -> Unit,
    onAlreadyBought: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val ticketUrl = slot.ticketUrl?.takeIf { it.isNotBlank() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(width = 44.dp, height = 5.dp).background(V5Line, RoundedCornerShape(3.dp)))
        },
    ) {
        StopActionSheetBody(
            title = "Tickets for ${slot.name}",
            subtitle = "Get them now, mark sold out, or save the ones you've already bought.",
            primary = StopAction("Get tickets", Icons.Outlined.AutoAwesome) {
                if (ticketUrl != null) uriHandler.openUri(ticketUrl)
                onDismiss()
            },
            secondaries = listOf(
                StopAction("No tickets available", Icons.Outlined.Close, onMarkUnavailable),
                StopAction("Tickets already bought", Icons.Outlined.Check, onAlreadyBought),
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnavailableActionSheet(
    title: String,
    subtitle: String,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(width = 44.dp, height = 5.dp).background(V5Line, RoundedCornerShape(3.dp)))
        },
    ) {
        StopActionSheetBody(
            title = title,
            subtitle = subtitle,
            primary = StopAction("Replace event", Icons.Outlined.AutoAwesome, onReplace),
            secondaries = listOf(
                StopAction("Remove event", Icons.Outlined.Delete, onRemove),
            ),
        )
    }
}

private data class StopAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

// ─── Detail-capture sheets (party size + time; ticket count) ─────────
//
// Phase 2 of the chip-system parity. Opened from the "I have a
// reservation" / "Tickets already bought" CTAs on the parent sheets,
// or directly from a confirmed state chip in edit mode (shows Clear).
// Mirrors iOS ReservationDetailsSheet / TicketDetailsSheet (stepper
// UX kept simple — wheel-style time picker on iOS is a single 30-min
// stepper here, good enough for V1).

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReservationDetailsSheet(
    slot: PlanSlot,
    goingCount: Int,
    isEditing: Boolean,
    onSave: (partySize: Int, time: String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var partySize by remember(slot.id) {
        mutableStateOf((slot.reservationPartySize ?: goingCount).coerceIn(1, 20))
    }
    var minutes by remember(slot.id) {
        mutableStateOf(parseTimeToMinutesOfDay(slot.reservationTime?.ifBlank { null } ?: slot.time) ?: (18 * 60))
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(width = 44.dp, height = 5.dp).background(V5Line, RoundedCornerShape(3.dp)))
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                "Reservation details",
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = V5Ink),
            )
            Spacer(Modifier.height(16.dp))
            StepperRow(
                label = "Number of people",
                value = partySize,
                onMinus = { if (partySize > 1) partySize -= 1 },
                onPlus = { if (partySize < 20) partySize += 1 },
                minusEnabled = partySize > 1,
                plusEnabled = partySize < 20,
            )
            Spacer(Modifier.height(16.dp))
            StepperRow(
                label = "Reservation time",
                value = formatMinutesOfDay(minutes),
                onMinus = { minutes = ((minutes - 30) + 24 * 60) % (24 * 60) },
                onPlus = { minutes = (minutes + 30) % (24 * 60) },
                minusEnabled = true,
                plusEnabled = true,
                wide = true,
            )
            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(V5Coral)
                    .clickable { onSave(partySize, formatMinutesOfDay(minutes)) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Save",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White),
                )
            }
            if (isEditing) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onClear() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, tint = V5Coral, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Clear reservation",
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = V5Coral),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TicketDetailsSheet(
    slot: PlanSlot,
    goingCount: Int,
    isEditing: Boolean,
    onSave: (count: Int) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var count by remember(slot.id) {
        mutableStateOf((slot.ticketCount ?: goingCount).coerceIn(1, 20))
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(width = 44.dp, height = 5.dp).background(V5Line, RoundedCornerShape(3.dp)))
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                "Ticket details",
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = V5Ink),
            )
            Spacer(Modifier.height(16.dp))
            StepperRow(
                label = "Number of tickets",
                value = count,
                onMinus = { if (count > 1) count -= 1 },
                onPlus = { if (count < 20) count += 1 },
                minusEnabled = count > 1,
                plusEnabled = count < 20,
            )
            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(V5Coral)
                    .clickable { onSave(count) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Save",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White),
                )
            }
            if (isEditing) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onClear() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, tint = V5Coral, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Clear tickets",
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = V5Coral),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Any,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    minusEnabled: Boolean,
    plusEnabled: Boolean,
    wide: Boolean = false,
) {
    Column {
        Text(
            label,
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = V5Ink2),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StepperCircle(icon = Icons.Outlined.Close, enabled = minusEnabled, onClick = onMinus)
            Text(
                value.toString(),
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = V5Ink),
                modifier = Modifier.widthIn(min = if (wide) 110.dp else 44.dp),
                textAlign = TextAlign.Center,
            )
            StepperCircle(icon = Icons.Outlined.Add, enabled = plusEnabled, onClick = onPlus)
        }
    }
}

@Composable
private fun StepperCircle(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(V5Shade)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) V5Ink else V5Ink4, modifier = Modifier.size(18.dp))
    }
}

/** Parse "5:30 PM" / "17:30" / "5:30pm" → minutes since midnight, or null. */
private fun parseTimeToMinutesOfDay(raw: String): Int? {
    val s = raw.trim().uppercase()
    if (s.isEmpty()) return null
    val isPm = s.endsWith("PM")
    val isAm = s.endsWith("AM")
    val core = if (isPm || isAm) s.dropLast(2).trim() else s
    val parts = core.split(":")
    if (parts.isEmpty()) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val hour24 = when {
        isPm && h < 12 -> h + 12
        isAm && h == 12 -> 0
        else -> h
    }
    if (hour24 !in 0..23 || m !in 0..59) return null
    return hour24 * 60 + m
}

private fun formatMinutesOfDay(min: Int): String {
    val hour24 = (min / 60).coerceIn(0, 23)
    val mm = (min % 60).coerceIn(0, 59)
    val amPm = if (hour24 < 12) "AM" else "PM"
    val h12 = when {
        hour24 == 0 -> 12
        hour24 > 12 -> hour24 - 12
        else -> hour24
    }
    val mmStr = mm.toString().padStart(2, '0')
    return "$h12:$mmStr $amPm"
}

@Composable
private fun StopActionSheetBody(
    title: String,
    subtitle: String,
    primary: StopAction,
    secondaries: List<StopAction>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            title,
            style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = V5Ink),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Normal, fontSize = 13.sp, color = V5Ink3),
        )
        Spacer(Modifier.height(16.dp))
        // Primary CTA
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(V5Coral)
                .clickable { primary.onClick() }
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(primary.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                primary.label,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White),
            )
        }
        secondaries.forEach { action ->
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(V5Shade)
                    .clickable { action.onClick() }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(action.icon, contentDescription = null, tint = V5Ink2, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    action.label,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = V5Ink),
                )
                Spacer(Modifier.weight(1f))
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = V5Ink4, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
