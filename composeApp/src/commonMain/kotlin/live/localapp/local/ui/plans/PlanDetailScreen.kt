package live.localapp.local.ui.plans

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
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.model.PlanAttendee
import live.localapp.local.domain.model.PlanDay
import live.localapp.local.domain.model.PlanSlot
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
    userLat: Double? = null,
    userLng: Double? = null,
    onOpenChat: () -> Unit = {},
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

    Box(modifier = Modifier.fillMaxSize().background(V5Paper)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Hero — 200dp cover with title + meta
            PlanHero(
                plan = plan,
                coverImageUrl = firstImageUrl,
                isCreator = isCreator,
                onBack = onBack,
                onShare = onShare,
                onEditName = { showEditNameDialog = true },
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
                // Read-only / past banners
                if (readOnly && !past && !isShared) {
                    ReadOnlyBanner(onRequestChange = onOpenChat)
                }
                if (past) {
                    PastPlanBanner(dateRange = plan.dateDisplay)
                }

                // Attendee card
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp)) {
                    AttendeeCard(
                        attendees = plan.attendees,
                        groupSize = plan.groupSize ?: plan.attendees.size.coerceAtLeast(1),
                        creatorId = plan.creatorId,
                        onOpenGroupSize = if (isCreator && !past) {
                            { showGroupSizeSheet = true }
                        } else null,
                        onInvite = onShare,
                        onShare = onShare,
                    )
                }
                Spacer(Modifier.height(12.dp))

                // Settings strip (Open / Locked plan) has been removed — the
                // plan is always creator-editable only, and attendees are always
                // read-only, so the toggle no longer has meaning.

                // Day tabs
                if (plan.days.isNotEmpty()) {
                    DayTabStrip(
                        days = plan.days,
                        activeIndex = selectedDayIndex.coerceAtMost(plan.days.lastIndex),
                        onSelect = { selectedDayIndex = it },
                        onAddDay = onAddDay,
                        canAdd = isCreator && !past && !isShared && plan.days.size < 10,
                        compact = plan.days.size >= 6,
                    )
                }

                // Day body
                val activeDay = plan.days.getOrNull(selectedDayIndex.coerceAtMost(plan.days.lastIndex))
                if (activeDay != null) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        if (activeDay.slots.isEmpty()) {
                            DayEmptyState(
                                readOnly = readOnly || past,
                                onAddEvent = { addEventDayIndex = selectedDayIndex },
                            )
                        } else {
                            activeDay.slots.forEachIndexed { i, slot ->
                                V5SlotCard(
                                    slot = slot,
                                    readOnly = readOnly || past,
                                    onRemove = {
                                        val totalBefore = plan.days.sumOf { d -> d.slots.size }
                                        onRemoveSlot(selectedDayIndex, slot.id)
                                        when {
                                            totalBefore <= 1 -> confirmDeletePlan = true
                                            activeDay.slots.size <= 1 -> emptyDayIndexToConfirm = selectedDayIndex
                                        }
                                    },
                                )
                                if (i < activeDay.slots.lastIndex) {
                                    activeDay.travel.getOrNull(i)?.let { t ->
                                        TransitRow(mode = t.mode, mins = t.mins)
                                    } ?: Spacer(Modifier.height(10.dp))
                                }
                            }
                            if (!readOnly && !past) {
                                Spacer(Modifier.height(10.dp))
                                AddEventButton(onClick = { addEventDayIndex = selectedDayIndex })
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

        // Chat peek bar — dark, floats above the home indicator. Not shown on shared-invite preview.
        if (!isShared && !past) {
            ChatPeekBar(
                unread = plan.attendees.count { !it.confirmed }.coerceAtLeast(0),
                preview = if (isCreator) "Send a message to the group, or tag @yocal to make changes" else "Tap to see the group chat",
                onClick = onOpenChat,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
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

        // Dialogs
        if (showEditNameDialog) {
            EditPlanNameDialog(
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
                body = "Everyone invited loses access. This can't be undone.",
                confirmLabel = "Delete plan",
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
    onBack: () -> Unit,
    onShare: () -> Unit,
    onEditName: () -> Unit,
) {
    val totalStops = plan.days.sumOf { it.slots.size }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(Color(0xFF201C19)),
    ) {
        if (coverImageUrl != null) {
            AsyncImage(
                model = coverImageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = 0.4f),
                            0.4f to Color.Black.copy(alpha = 0.1f),
                            1f to Color.Black.copy(alpha = 0.75f),
                        ),
                    ),
                ),
        )

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
        }

        // Meta + title
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    plan.dateDisplay.ifBlank { plan.city },
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.White.copy(alpha = 0.85f), letterSpacing = 1.5.sp),
                )
                if (totalStops > 0) {
                    Text(
                        "  ·  $totalStops ${if (totalStops == 1) "stop" else "stops"}",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.White.copy(alpha = 0.65f), letterSpacing = 1.5.sp),
                    )
                }
                val dayCount = plan.days.size
                if (dayCount > 0) {
                    Text(
                        "  ·  $dayCount ${if (dayCount == 1) "day" else "days"}",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.White.copy(alpha = 0.65f), letterSpacing = 1.5.sp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    plan.title,
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Color.White, letterSpacing = (-0.6).sp, lineHeight = 32.sp),
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isCreator) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                            .clickable(onClick = onEditName),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit name", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
            val plannedBy = when {
                isCreator -> "you"
                !plan.creatorName.isNullOrBlank() -> plan.creatorName
                else -> "the planner"
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Planned by $plannedBy",
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f)),
            )
        }
    }
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
                .clickable(onClick = onShare)
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

// ─── Settings strip ──────────────────────────────────────────────────

@Composable
private fun SettingsStrip(allowEdit: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.dp, V5Line, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(V5LineSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (allowEdit) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                contentDescription = null,
                tint = V5Ink2,
                modifier = Modifier.size(15.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (allowEdit) "Open plan" else "Locked plan",
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = V5Ink),
            )
            Text(
                if (allowEdit) "Anyone in the group can add stops" else "Only you can change the plan",
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Normal, fontSize = 11.sp, color = V5Ink3),
            )
        }
        V5Toggle(on = allowEdit, onChange = onToggle)
    }
}

@Composable
private fun V5Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (on) V5Coral else V5Line)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onChange(!on) },
    ) {
        Box(
            modifier = Modifier
                .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

// ─── Banners ────────────────────────────────────────────────────────

@Composable
private fun ReadOnlyBanner(onRequestChange: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 0.dp)
            .padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(V5Shade)
                .border(1.dp, V5Line, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = V5Ink3, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(10.dp))
            Row(modifier = Modifier.weight(1f)) {
                Text(
                    "View only. ",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = V5Ink),
                )
                Text(
                    "Only the planner can edit.",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Normal, fontSize = 12.sp, color = V5Ink2),
                )
            }
            Text(
                "Request change",
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onRequestChange,
                ),
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = V5Coral),
            )
        }
    }
}

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
    val tabWidth = if (compact) 56.dp else 64.dp
    val tabHeight = if (compact) 58.dp else 66.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        days.forEachIndexed { i, day ->
            DayTab(
                day = day,
                active = i == activeIndex,
                compact = compact,
                width = tabWidth,
                height = tabHeight,
                onClick = { onSelect(i) },
            )
        }
        if (canAdd) {
            Column(
                modifier = Modifier
                    .size(width = tabWidth, height = tabHeight)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.5.dp, V5Coral, RoundedCornerShape(14.dp))
                    .clickable(onClick = onAddDay),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "Add day", tint = V5Coral, modifier = Modifier.size(if (compact) 14.dp else 16.dp))
                Spacer(Modifier.height(3.dp))
                Text(
                    "ADD DAY",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = V5Coral, letterSpacing = 0.3.sp),
                )
            }
        }
    }
}

@Composable
private fun DayTab(
    day: PlanDay,
    active: Boolean,
    compact: Boolean,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    // Parse day.label "Friday, Nov 8" → dow = "FRI", date = "8"
    val parts = day.label.replace("Today ", "").replace("Tomorrow ", "").split(",").map { it.trim() }
    val dow = (parts.firstOrNull() ?: day.shortName).take(3).uppercase()
    val datePart = parts.getOrNull(1)?.split(" ")?.lastOrNull() ?: ""

    val textColor = if (active) Color.White else V5Ink
    val subColor = if (active) Color.White.copy(alpha = 0.75f) else V5Ink3
    Column(
        modifier = Modifier
            .size(width, height)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) V5Coral else Color.White)
            .then(if (active) Modifier else Modifier.border(1.dp, V5Line, RoundedCornerShape(14.dp)))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            dow,
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = subColor, letterSpacing = 0.6.sp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            datePart.ifBlank { "·" },
            style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = if (compact) 22.sp else 26.sp, color = textColor, letterSpacing = (-0.6).sp),
        )
        if (!compact) {
            Spacer(Modifier.height(3.dp))
            Text(
                if (day.slots.isEmpty()) "—" else "${day.slots.size} stops",
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Medium, fontSize = 9.sp, color = subColor),
            )
        }
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
            "Nothing planned yet",
            style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = V5Ink, letterSpacing = (-0.2).sp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (readOnly) "Wait for the planner to add something — or ask in chat." else "Add your first stop, or try @yocal in the group chat for ideas.",
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
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, V5Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
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

// ─── Slot card + transit row ─────────────────────────────────────────

@Composable
private fun V5SlotCard(slot: PlanSlot, readOnly: Boolean, onRemove: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var showDirectionsSheet by remember { mutableStateOf(false) }
    if (showDirectionsSheet && slot.venueLat != null && slot.venueLng != null) {
        DirectionsSheet(lat = slot.venueLat, lng = slot.venueLng, name = slot.name, onDismiss = { showDirectionsSheet = false })
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, V5Line, RoundedCornerShape(16.dp))
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
                            "RESERVED",
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = V5Green, letterSpacing = 0.4.sp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!readOnly) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onRemove,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "Remove", tint = V5Ink4, modifier = Modifier.size(14.dp))
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
            SlotCtas(slot, onDirections = { showDirectionsSheet = true })
        }
    }
}

private fun slotKind(slot: PlanSlot): String {
    val restaurantCategories = listOf("restaurant", "bar", "brewery", "winery", "cafe", "bistro", "pub", "tavern", "grill", "steakhouse", "pizzeria", "bakery", "diner")
    val cat = slot.venueCategory?.lowercase() ?: ""
    val nameLower = slot.name.lowercase()
    val isRestaurant = slot.eventId == null && (
        restaurantCategories.any { cat.contains(it) } ||
            restaurantCategories.any { nameLower.contains(it) }
        )
    if (isRestaurant) return inferMealType(slot.time).ifBlank { "Dining" }
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

// ─── Chat peek bar ──────────────────────────────────────────────────

@Composable
private fun ChatPeekBar(
    unread: Int,
    preview: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(V5Ink)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "GROUP CHAT",
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f), letterSpacing = 0.5.sp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                preview,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Color.White),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (unread > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .background(V5Coral)
                    .padding(horizontal = 7.dp, vertical = 2.dp)
                    .heightIn(min = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$unread",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Text("\u25B4", style = TextStyle(fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f)))
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

@Composable
private fun EditPlanNameDialog(currentName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var nameInput by remember { mutableStateOf(currentName) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = Color.White,
        title = {
            Text(
                "Rename plan",
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = V5Ink),
            )
        },
        text = {
            Column {
                androidx.compose.foundation.text.BasicTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it; error = null },
                    textStyle = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = V5Ink),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(V5Coral),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(V5Shade)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
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
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        error!!,
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = V5Coral),
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val trimmed = nameInput.trim()
                val validationError = live.localapp.local.ProfanityFilter.validate(trimmed)
                if (validationError != null) {
                    error = validationError
                } else {
                    onSave(trimmed)
                }
            }) {
                Text("Save", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, color = V5Coral))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, color = V5Ink3))
            }
        },
    )
}

// ─── Slot CTAs (kept from original — Directions + secondary link) ──

@Composable
private fun SlotCtas(slot: PlanSlot, onDirections: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val hasLocation = slot.venueLat != null && slot.venueLng != null

    val reservationCategories = listOf("restaurant", "bar", "brewery", "winery", "cafe", "bistro", "pub", "tavern", "grill", "steakhouse", "pizzeria", "bakery", "diner")
    val cat = slot.venueCategory?.lowercase() ?: ""
    val needsReservation = slot.reservationUrl != null || reservationCategories.any { cat.contains(it) }

    val resolvedReserveUrl = slot.reservationUrl?.let { url ->
        val today = kotlin.time.Clock.System.now().toString().take(10)
        if (url.contains("opentable")) "${url}?dateTime=${today}T19:00&covers=2&partySize=2" else url
    } ?: slot.venueWebsite ?: "https://www.google.com/search?q=${slot.name.replace(" ", "+")}+reservations"

    val secondaryCta: Pair<String, String> = when {
        needsReservation -> "Reserve" to resolvedReserveUrl
        slot.ticketUrl != null -> "Tickets" to slot.ticketUrl!!
        slot.sourceUrl != null -> "Info" to slot.sourceUrl!!
        slot.venueWebsite != null -> "Website" to slot.venueWebsite!!
        else -> "Info" to "https://www.google.com/search?q=${slot.name.replace(" ", "+")}"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (hasLocation) {
            Text(
                "Directions",
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDirections,
                ),
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = V5Coral),
            )
            Text("  ·  ", style = TextStyle(fontSize = 12.sp, color = V5Line))
        }
        val (label, url) = secondaryCta
        Text(
            label,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { uriHandler.openUri(url) },
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = V5Coral),
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
