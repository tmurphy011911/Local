package live.localapp.local.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts
import live.localapp.local.ui.theme.AppShapes

@Composable
fun ChatScreen(viewModel: ChatViewModel, onBack: (() -> Unit)? = null) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Snow),
        ) {
            // ── Header ──
            ChatHeader(onBack = onBack)

            // ── Messages ──
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    when (message.content) {
                        is MessageContent.Text -> MessageRow(message.role, (message.content as MessageContent.Text).text)
                        is MessageContent.DayCardRef -> {
                            val dayIdx = (message.content as MessageContent.DayCardRef).dayIndex
                            val day = state.days.getOrNull(dayIdx) ?: return@items
                            DayCard(
                                day = day,
                                dayIndex = dayIdx,
                                totalDays = state.days.size,
                                onRemoveSlot = { slotId -> viewModel.removeSlot(dayIdx, slotId) },
                            )
                        }
                        is MessageContent.SingleResultsRef -> {
                            val bucket = (message.content as MessageContent.SingleResultsRef).bucket
                            val results = state.singleResults[bucket] ?: state.singleResults["default"]!!
                            SingleResultsCard(results)
                        }
                        is MessageContent.SummaryRef -> {
                            SummaryCard(state)
                        }
                        is MessageContent.ProgressRef -> {
                            ProgressIndicator(
                                totalDays = state.days.size,
                                approvedDays = state.approvedDayCount,
                                currentDay = state.currentDayIndex,
                            )
                        }
                    }
                }

                // Mode selection cards removed — structured intake flow handles this via quick replies

                // Typing indicator
                if (state.isTyping) {
                    item { TypingIndicator() }
                }
            }

            // ── Intake Cards (structured steps) or Quick Replies ──
            when (state.stage) {
                ChatStage.INTAKE_DURATION -> {
                    if (!state.isBusy) {
                        IntakeDurationCards(
                            onSingleDay = { viewModel.onQuickReply(QuickReply("📅 Single day", action = "intake_single_day")) },
                            onMultiDay = { viewModel.onQuickReply(QuickReply("🗓️ Multi-day", action = "intake_multi_day")) },
                        )
                    }
                }
                ChatStage.INTAKE_PEOPLE -> {
                    if (!state.isBusy) {
                        IntakePeopleCards(
                            onSelect = { label, action -> viewModel.onQuickReply(QuickReply(label, action = action)) },
                        )
                    }
                }
                ChatStage.INTAKE_DATE -> {
                    if (!state.isBusy) {
                        IntakeDateCards(
                            isMultiDay = false,
                            onSelect = { label, action -> viewModel.onQuickReply(QuickReply(label, action = action)) },
                        )
                    }
                }
                ChatStage.INTAKE_DATE_START -> {
                    if (!state.isBusy) {
                        DatePickerGrid(
                            label = "Pick a start date",
                            startFromDate = null,
                            maxDays = 30,
                            onSelect = { isoDate, displayLabel -> viewModel.handleIntakeDateStart(isoDate, displayLabel) },
                        )
                    }
                }
                ChatStage.INTAKE_DATE_END -> {
                    if (!state.isBusy) {
                        DatePickerGrid(
                            label = "Pick an end date",
                            startFromDate = state.gatheredStartDate,
                            maxDays = 7,
                            onSelect = { isoDate, displayLabel -> viewModel.handleIntakeDateEnd(isoDate, displayLabel) },
                        )
                    }
                }
                else -> {
                    if (state.quickReplies.isNotEmpty()) {
                        QuickReplyBar(
                            replies = state.quickReplies,
                            onReply = { viewModel.onQuickReply(it) },
                        )
                    }
                }
            }

            // ── Input Bar ──
            ChatInputBar(
                enabled = state.inputEnabled,
                placeholder = state.inputPlaceholder,
                onSend = { viewModel.sendMessage(it) },
            )
        }

        // ── Loading Overlay ──
        AnimatedVisibility(
            visible = state.isLoading,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            LoadingOverlay(
                title = state.loadingTitle,
                subtitle = state.loadingSubtitle,
            )
        }
    }
}

// ── Header ──

@Composable
private fun ChatHeader(onBack: (() -> Unit)? = null) {
    val headerBrush = Brush.linearGradient(
        colors = listOf(Color(0xFFFF7A7A), AppColors.Coral, Color(0xFFE85A5A)),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(headerBrush)
            .padding(horizontal = 18.dp)
            .padding(top = 6.dp, bottom = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(AppColors.White.copy(alpha = 0.15f), RoundedCornerShape(11.dp))
                        .border(1.5.dp, AppColors.White.copy(alpha = 0.25f), RoundedCornerShape(11.dp))
                        .clip(RoundedCornerShape(11.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.ChevronLeft, "Back", Modifier.size(18.dp), tint = AppColors.White)
                }
                Spacer(Modifier.width(10.dp))
            }

            // Avatar
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(AppColors.White.copy(alpha = 0.22f), RoundedCornerShape(13.dp))
                    .border(2.dp, AppColors.White.copy(alpha = 0.35f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(20.dp), tint = AppColors.White)
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Local Concierge",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = AppColors.White),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val infiniteTransition = rememberInfiniteTransition()
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 1f, targetValue = 0.4f,
                        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
                    )
                    Box(Modifier.size(6.dp).graphicsLayer { this.alpha = alpha }.background(Color(0xFF4EFF8D), CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Always on \u00b7 Knows Boston",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.White.copy(alpha = 0.72f)),
                    )
                }
            }
        }
    }
}

// ── Message Bubbles ──

@Composable
private fun MessageRow(role: MessageRole, text: String) {
    val isUser = role == MessageRole.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            AiAvatar()
            Spacer(Modifier.width(8.dp))
        }

        val bubbleShape = if (isUser) RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp) else RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)

        Box(modifier = Modifier.weight(1f, fill = false)) {
            Box(
                Modifier.matchParentSize().offset(y = 4.dp)
                    .background(if (isUser) Color(0xFFC73E3E) else Color(0xFFD8D2E8), bubbleShape),
            )
            Box(
                Modifier
                    .background(
                        if (isUser) Brush.linearGradient(listOf(AppColors.Coral, AppColors.CoralDk))
                        else Brush.linearGradient(listOf(AppColors.White, AppColors.White)),
                        bubbleShape,
                    )
                    .border(2.dp, if (isUser) AppColors.CoralDk else Color(0xFFE2DCF0), bubbleShape)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Text(
                    text,
                    style = TextStyle(
                        fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        color = if (isUser) AppColors.White else AppColors.Ink2, lineHeight = (13 * 1.55).sp,
                    ),
                )
            }
        }

        if (isUser) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(30.dp).background(AppColors.Coral, RoundedCornerShape(10.dp))
                    .border(1.5.dp, AppColors.CoralDk, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("Y", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 11.sp, color = AppColors.White))
            }
        }
    }
}

@Composable
private fun AiAvatar() {
    Box(
        Modifier.size(30.dp).background(AppColors.CoralLt, RoundedCornerShape(10.dp))
            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(15.dp), tint = AppColors.Coral)
    }
}

// ── Typing Indicator ──

@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition()
    Row(horizontalArrangement = Arrangement.Start) {
        AiAvatar()
        Spacer(Modifier.width(8.dp))
        val bubbleShape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
        Box {
            Box(Modifier.matchParentSize().offset(y = 4.dp).background(Color(0xFFD8D2E8), bubbleShape))
            Row(
                Modifier.background(AppColors.White, bubbleShape).border(2.dp, Color(0xFFE2DCF0), bubbleShape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) { index ->
                    val offset by infiniteTransition.animateFloat(
                        initialValue = 0f, targetValue = -6f,
                        animationSpec = infiniteRepeatable(tween(600, delayMillis = index * 180), RepeatMode.Reverse),
                    )
                    Box(Modifier.size(7.dp).graphicsLayer { translationY = offset }.background(AppColors.Pebble, CircleShape))
                }
            }
        }
    }
}

// ── Day Card ──

@Composable
private fun DayCard(
    day: ItineraryDay,
    dayIndex: Int,
    totalDays: Int,
    onRemoveSlot: (String) -> Unit,
) {
    val color = Color(dayColor(dayIndex))
    val cardShape = RoundedCornerShape(18.dp)

    Box(modifier = Modifier.fillMaxWidth()) {
        // Shadow
        Box(
            Modifier.fillMaxWidth().matchParentSize().offset(y = 5.dp)
                .background(if (day.isApproved) AppColors.SageDk else AppColors.Pebble, cardShape),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.White, cardShape)
                .border(2.dp, if (day.isApproved) AppColors.Sage else AppColors.Pebble, cardShape)
                .clip(cardShape),
        ) {
            // Header
            Column(
                modifier = Modifier.fillMaxWidth().background(color).padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    "DAY ${dayIndex + 1} OF $totalDays",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 10.sp,
                        color = AppColors.White.copy(alpha = 0.7f), letterSpacing = 0.8.sp),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    day.shortName,
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 18.sp, color = AppColors.White),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${day.label.substringAfter(", ")} \u00b7 ${day.slots.size} events",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                        color = AppColors.White.copy(alpha = 0.7f)),
                )
            }

            // Approved banner
            if (day.isApproved) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(AppColors.SageLt)
                        .border(width = 1.5.dp, color = AppColors.Sage)
                        .padding(vertical = 7.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(14.dp), tint = AppColors.SageDk)
                    Spacer(Modifier.width(6.dp))
                    Text("Approved", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = AppColors.SageDk))
                }
            }

            // Slots
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                day.slots.forEachIndexed { index, slot ->
                    val isLast = index == day.slots.lastIndex
                    SlotRow(
                        slot = slot,
                        color = color,
                        isLast = isLast,
                        isApproved = day.isApproved,
                        onRemove = { onRemoveSlot(slot.id) },
                    )

                    // Travel pill between slots
                    if (!isLast) {
                        val travel = day.travel.getOrNull(index)
                        if (travel != null) {
                            TravelPill(travel)
                        } else {
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotRow(
    slot: ItinerarySlot,
    color: Color,
    isLast: Boolean,
    isApproved: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
    ) {
        // Time column
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(36.dp).fillMaxHeight(),
        ) {
            Text(
                slot.time,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = AppColors.Ink3),
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.size(11.dp).background(color, CircleShape)
                    .border(3.dp, color.copy(alpha = 0.2f), CircleShape),
            )
            if (!isLast) {
                Box(Modifier.width(2.dp).weight(1f).background(AppColors.Mist))
            }
        }

        Spacer(Modifier.width(10.dp))

        // Slot content box
        Row(
            modifier = Modifier
                .weight(1f)
                .background(AppColors.Snow, RoundedCornerShape(13.dp))
                .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(13.dp))
                .clip(RoundedCornerShape(13.dp))
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(slot.emoji, style = TextStyle(fontSize = 18.sp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    slot.name,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = AppColors.Ink, lineHeight = (12 * 1.3).sp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    slot.detail,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = AppColors.Ink3, lineHeight = (10 * 1.4).sp),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    slot.price,
                    style = TextStyle(
                        fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 11.sp,
                        color = if (slot.isFree) AppColors.Sage else AppColors.Coral,
                    ),
                )
            }

            // Remove button (only if not approved)
            if (!isApproved) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(AppColors.Mist, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onRemove),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Close, "Remove", Modifier.size(12.dp), tint = AppColors.Ink3)
                }
            }
        }
    }
}

@Composable
private fun TravelPill(travel: TravelInfo) {
    Row(
        modifier = Modifier.padding(start = 46.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.5.dp).background(AppColors.Mist))

        val isWalk = travel.mode == "walk"
        val bgColor = if (isWalk) Color(0xFFEBF2FF) else Color(0xFFFFF4DC)
        val borderColor = if (isWalk) Color(0xFFB3CFFF) else Color(0xFFFFD97A)
        val textColor = if (isWalk) Color(0xFF3A7CE8) else Color(0xFF9A6200)
        val icon = if (isWalk) Icons.Outlined.DirectionsWalk else Icons.Outlined.DirectionsCar

        Row(
            modifier = Modifier
                .background(bgColor, AppShapes.Pill)
                .border(1.5.dp, borderColor, AppShapes.Pill)
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(12.dp), tint = textColor)
            Spacer(Modifier.width(4.dp))
            Text(
                "${travel.mins} min",
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = textColor),
            )
        }

        Box(Modifier.weight(1f).height(1.5.dp).background(AppColors.Mist))
    }
}

// ── Progress Indicator ──

@Composable
private fun ProgressIndicator(totalDays: Int, approvedDays: Int, currentDay: Int) {
    val dayNames = listOf("Thursday", "Friday", "Saturday")
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until totalDays) {
            val isDone = i < approvedDays
            val isActive = i == currentDay
            val dotColor = when {
                isDone -> AppColors.Sage
                isActive -> AppColors.Coral
                else -> AppColors.Pebble
            }
            val textColor = when {
                isDone -> AppColors.SageDk
                isActive -> AppColors.Coral
                else -> AppColors.Ink3
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(dotColor, CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(
                    dayNames.getOrElse(i) { "Day ${i + 1}" },
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = textColor),
                )
            }

            if (i < totalDays - 1) {
                Box(
                    Modifier.weight(1f).height(2.dp).padding(horizontal = 6.dp)
                        .background(if (isDone) AppColors.Sage else AppColors.Mist),
                )
            }
        }
    }
}

// ── Single Results Card ──

@Composable
private fun SingleResultsCard(results: SingleResults) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Top 3 picks label
        Text(
            "\u2B50  TOP 3 PICKS",
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 10.sp, color = AppColors.Ink3, letterSpacing = 0.8.sp),
        )
        Spacer(Modifier.height(8.dp))

        // Picks
        results.picks.forEach { pick ->
            PickCard(pick)
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(6.dp))
        // Divider
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(AppColors.Mist))
        Spacer(Modifier.height(10.dp))

        // Honorable mentions label
        Text(
            "\u2726  5 HONORABLE MENTIONS",
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 10.sp, color = AppColors.Ink3, letterSpacing = 0.8.sp),
        )
        Spacer(Modifier.height(8.dp))

        // Mentions
        results.mentions.forEach { mention ->
            MentionRow(mention)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun PickCard(pick: SinglePick) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().matchParentSize().offset(y = 3.dp).background(AppColors.Pebble, RoundedCornerShape(14.dp)))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.White, RoundedCornerShape(14.dp))
                .border(2.dp, AppColors.Pebble, RoundedCornerShape(14.dp))
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(pick.emoji, style = TextStyle(fontSize = 22.sp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pick.name,
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 12.sp, color = AppColors.Ink),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        pick.tag,
                        modifier = Modifier
                            .background(AppColors.CoralLt, AppShapes.Pill)
                            .border(1.dp, AppColors.Coral, AppShapes.Pill)
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 9.sp, color = AppColors.CoralDk),
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    pick.detail,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = AppColors.Ink3, lineHeight = (10 * 1.45).sp),
                )
                if (pick.price.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        pick.price,
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 11.sp, color = AppColors.Coral),
                    )
                }
            }
        }
    }
}

@Composable
private fun MentionRow(mention: SingleMention) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Snow, RoundedCornerShape(11.dp))
            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(11.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(mention.emoji, style = TextStyle(fontSize = 16.sp))
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(mention.name, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = AppColors.Ink))
            Text(mention.detail, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = AppColors.Ink3))
        }
        Text("\u203A", style = TextStyle(fontSize = 13.sp, color = AppColors.Pebble))
    }
}

// ── Summary Card ──

@Composable
private fun SummaryCard(state: ChatUiState) {
    val cardShape = RoundedCornerShape(18.dp)
    val totalEvents = state.days.sumOf { it.slots.size }
    val freeEvents = state.days.sumOf { day -> day.slots.count { it.isFree } }

    Box(modifier = Modifier.fillMaxWidth()) {
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
                modifier = Modifier.fillMaxWidth().background(AppColors.Ink).padding(16.dp),
            ) {
                // Glow circle
                Box(
                    Modifier.size(120.dp).offset(x = 60.dp, y = (-30).dp)
                        .align(Alignment.TopEnd)
                        .background(AppColors.Coral.copy(alpha = 0.2f), CircleShape),
                )
                Column {
                    Text(
                        state.planTitle,
                        style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 18.sp, color = AppColors.White),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SummaryChip("\uD83D\uDCC5 ${state.planDates}")
                        SummaryChip("\uD83D\uDC65 ${state.planGroup}")
                        SummaryChip("\uD83D\uDCCD Boston")
                    }
                }
            }

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth().border(width = 1.5.dp, color = AppColors.Mist),
            ) {
                StatCell("${state.days.size}", "Days", Modifier.weight(1f))
                Box(Modifier.width(1.5.dp).height(50.dp).background(AppColors.Mist))
                StatCell("$totalEvents", "Events", Modifier.weight(1f))
                Box(Modifier.width(1.5.dp).height(50.dp).background(AppColors.Mist))
                StatCell("$freeEvents", "Free", Modifier.weight(1f))
            }

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(
                    label = "\uD83D\uDCE4 Share",
                    isPrimary = false,
                    modifier = Modifier.weight(1f),
                    onClick = {},
                )
                ActionButton(
                    label = "\uD83D\uDDD3\uFE0F Save to Plans",
                    isPrimary = true,
                    modifier = Modifier.weight(1f),
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun SummaryChip(text: String) {
    Text(
        text,
        modifier = Modifier
            .background(AppColors.White.copy(alpha = 0.12f), AppShapes.Pill)
            .border(1.dp, AppColors.White.copy(alpha = 0.2f), AppShapes.Pill)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = AppColors.White.copy(alpha = 0.85f)),
    )
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 20.sp, color = AppColors.Ink))
        Spacer(Modifier.height(2.dp))
        Text(label, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = AppColors.Ink3))
    }
}

@Composable
private fun ActionButton(label: String, isPrimary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(modifier = modifier) {
        Box(
            Modifier.fillMaxWidth().matchParentSize()
                .offset(y = if (isPressed) 0.dp else 3.dp)
                .background(
                    if (isPrimary) AppColors.CoralDk else AppColors.Pebble,
                    RoundedCornerShape(13.dp),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(
                    if (isPrimary) AppColors.Coral else AppColors.White,
                    RoundedCornerShape(13.dp),
                )
                .then(
                    if (!isPrimary) Modifier.border(2.dp, AppColors.Pebble, RoundedCornerShape(13.dp))
                    else Modifier
                )
                .clip(RoundedCornerShape(13.dp))
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = TextStyle(
                    fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
                    color = if (isPrimary) AppColors.White else AppColors.Ink2,
                ),
            )
        }
    }
}

// ── Loading Overlay ──

@Composable
private fun LoadingOverlay(title: String, subtitle: String) {
    val infiniteTransition = rememberInfiniteTransition()
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Snow.copy(alpha = 0.93f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Pulsing logo
            Box(
                modifier = Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .size(72.dp)
                    .background(AppColors.Coral, RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "l",
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 38.sp, color = AppColors.White),
                )
            }

            Spacer(Modifier.height(14.dp))

            Text(
                title,
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = AppColors.Ink),
            )

            Spacer(Modifier.height(6.dp))

            Text(
                subtitle,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AppColors.Ink3),
            )

            Spacer(Modifier.height(14.dp))

            // Loading dots
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { index ->
                    val dotAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.4f, targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(600, delayMillis = index * 200), RepeatMode.Reverse),
                    )
                    val dotOffset by infiniteTransition.animateFloat(
                        initialValue = 0f, targetValue = -8f,
                        animationSpec = infiniteRepeatable(tween(600, delayMillis = index * 200), RepeatMode.Reverse),
                    )
                    Box(
                        Modifier.size(8.dp)
                            .graphicsLayer { alpha = dotAlpha; translationY = dotOffset }
                            .background(AppColors.Coral, CircleShape),
                    )
                }
            }
        }
    }
}

// ── Mode Selection Cards ──

@Composable
private fun ModeSelectCards(onSelectSingle: () -> Unit, onSelectItinerary: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        ModeCard(
            icon = Icons.Outlined.MyLocation, title = "Find an event",
            description = "One great thing to do \u2014 a restaurant, show, activity, or hidden gem.",
            accentColor = AppColors.Coral, accentLt = AppColors.CoralLt,
            onClick = onSelectSingle,
        )
        ModeCard(
            icon = Icons.Outlined.CalendarMonth, title = "Build an itinerary",
            description = "A full plan with multiple events, day by day, built around your trip.",
            accentColor = AppColors.Sage, accentLt = AppColors.SageLt,
            onClick = onSelectItinerary,
        )
    }
}

@Composable
private fun ModeCard(
    icon: ImageVector, title: String, description: String,
    accentColor: Color, accentLt: Color, onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().matchParentSize()
                .offset(y = if (isPressed) 1.dp else 4.dp)
                .background(AppColors.Pebble, AppShapes.CardSmall),
        )
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(AppColors.White, AppShapes.CardSmall)
                .border(2.dp, AppColors.Pebble, AppShapes.CardSmall)
                .clip(AppShapes.CardSmall)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).background(accentLt, RoundedCornerShape(13.dp))
                    .border(2.dp, accentColor, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(22.dp), tint = accentColor)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 14.sp, color = AppColors.Ink))
                Spacer(Modifier.height(3.dp))
                Text(description, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3, lineHeight = (11 * 1.4).sp))
            }
            Spacer(Modifier.width(8.dp))
            Text("\u203A", style = TextStyle(fontSize = 18.sp, color = AppColors.Pebble))
        }
    }
}

// ── Intake Cards (structured steps with rich card styling) ──

@Composable
private fun IntakeDurationCards(onSingleDay: () -> Unit, onMultiDay: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        ModeCard(
            icon = Icons.Outlined.MyLocation,
            title = "Single day",
            description = "Find something great to do today or any day.",
            accentColor = AppColors.Coral,
            accentLt = AppColors.CoralLt,
            onClick = onSingleDay,
        )
        ModeCard(
            icon = Icons.Outlined.CalendarMonth,
            title = "Multi-day",
            description = "Plan a trip or weekend with events across multiple days.",
            accentColor = AppColors.Sage,
            accentLt = AppColors.SageLt,
            onClick = onMultiDay,
        )
    }
}

@Composable
private fun IntakePeopleCards(onSelect: (String, String) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                IntakeOptionCard(label = "Just me", emoji = "🧍", onClick = { onSelect("Just me", "intake_people_1") })
            }
            Box(Modifier.weight(1f)) {
                IntakeOptionCard(label = "2 people", emoji = "👫", onClick = { onSelect("2 people", "intake_people_2") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                IntakeOptionCard(label = "3–4 people", emoji = "👨\u200D👩\u200D👧", onClick = { onSelect("3–4 people", "intake_people_3-4") })
            }
            Box(Modifier.weight(1f)) {
                IntakeOptionCard(label = "5+ people", emoji = "🎉", onClick = { onSelect("5+ people", "intake_people_5+") })
            }
        }
    }
}

@Composable
private fun IntakeDateCards(isMultiDay: Boolean, onSelect: (String, String) -> Unit) {
    val options = if (isMultiDay) {
        listOf(
            Triple("This weekend", "🗓️", "intake_date_this_weekend"),
            Triple("Next weekend", "📅", "intake_date_next_weekend"),
            Triple("This week", "📆", "intake_date_this_week"),
            Triple("Next week", "🗓️", "intake_date_next_week"),
        )
    } else {
        listOf(
            Triple("Today", "☀️", "intake_date_today"),
            Triple("Tomorrow", "🌅", "intake_date_tomorrow"),
            Triple("This Saturday", "🗓️", "intake_date_saturday"),
            Triple("This Sunday", "📅", "intake_date_sunday"),
        )
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                IntakeOptionCard(label = options[0].first, emoji = options[0].second, onClick = { onSelect(options[0].first, options[0].third) })
            }
            Box(Modifier.weight(1f)) {
                IntakeOptionCard(label = options[1].first, emoji = options[1].second, onClick = { onSelect(options[1].first, options[1].third) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                IntakeOptionCard(label = options[2].first, emoji = options[2].second, onClick = { onSelect(options[2].first, options[2].third) })
            }
            Box(Modifier.weight(1f)) {
                IntakeOptionCard(label = options[3].first, emoji = options[3].second, onClick = { onSelect(options[3].first, options[3].third) })
            }
        }
    }
}

@Composable
private fun IntakeOptionCard(label: String, emoji: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(modifier = Modifier.fillMaxWidth()) {
        // Shadow
        Box(
            Modifier.fillMaxWidth().matchParentSize()
                .offset(y = if (isPressed) 1.dp else 3.dp)
                .background(AppColors.Pebble, AppShapes.CardSmall),
        )
        // Card
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
                .background(AppColors.White, AppShapes.CardSmall)
                .border(2.dp, AppColors.Pebble, AppShapes.CardSmall)
                .clip(AppShapes.CardSmall)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(vertical = 14.dp, horizontal = 8.dp),
        ) {
            Text(emoji, style = TextStyle(fontSize = 22.sp))
            Spacer(Modifier.height(6.dp))
            Text(
                label,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = AppColors.Ink,
                ),
            )
        }
    }
}

// ── Date Picker Grid (multi-day start/end) ──

@Composable
private fun DatePickerGrid(
    label: String,
    startFromDate: String?, // ISO YYYY-MM-DD — if set, dates start the day after this
    maxDays: Int,
    onSelect: (isoDate: String, displayLabel: String) -> Unit,
) {
    val tz = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(tz).date

    // Calculate the first selectable date
    val firstDate = if (startFromDate != null) {
        LocalDate.parse(startFromDate).plus(1, DateTimeUnit.DAY)
    } else {
        today
    }

    // Generate date list
    val dates = (0 until maxDays).map { offset ->
        firstDate.plus(offset, DateTimeUnit.DAY)
    }

    val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val monthNames = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    fun LocalDate.displayLabel(): String {
        if (this == today) return "Today"
        if (this == today.plus(1, DateTimeUnit.DAY)) return "Tomorrow"
        val dow = dayNames[dayOfWeek.ordinal]
        val mon = monthNames[monthNumber - 1]
        return "$dow, $mon $dayOfMonth"
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.sp,
                color = AppColors.Ink3,
            ),
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // Grid: 4 columns
        val rows = dates.chunked(4)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in rows) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    for (date in row) {
                        Box(Modifier.weight(1f)) {
                            DateCell(
                                date = date,
                                isToday = date == today,
                                dayNames = dayNames,
                                monthNames = monthNames,
                                onClick = {
                                    onSelect(date.toString(), date.displayLabel())
                                },
                            )
                        }
                    }
                    // Fill remaining columns with empty space
                    repeat(4 - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DateCell(
    date: LocalDate,
    isToday: Boolean,
    dayNames: List<String>,
    monthNames: List<String>,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val dow = dayNames[date.dayOfWeek.ordinal]
    val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
    val borderColor = if (isToday) AppColors.Coral else AppColors.Pebble
    val bgColor = if (isWeekend) AppColors.CoralLt.copy(alpha = 0.3f) else AppColors.White

    Box(modifier = Modifier.fillMaxWidth()) {
        // Shadow
        Box(
            Modifier.fillMaxWidth().matchParentSize()
                .offset(y = if (isPressed) 1.dp else 3.dp)
                .background(AppColors.Pebble, AppShapes.CardSmall),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
                .background(bgColor, AppShapes.CardSmall)
                .border(if (isToday) 2.dp else 1.5.dp, borderColor, AppShapes.CardSmall)
                .clip(AppShapes.CardSmall)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(vertical = 10.dp, horizontal = 4.dp),
        ) {
            Text(
                dow,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = AppColors.Ink3,
                ),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${date.dayOfMonth}",
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    color = if (isToday) AppColors.Coral else AppColors.Ink,
                ),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                monthNames[date.monthNumber - 1],
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    color = AppColors.Ink3,
                ),
            )
        }
    }
}

// ── Quick Reply Chips ──

@Composable
private fun QuickReplyBar(replies: List<QuickReply>, onReply: (QuickReply) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        items(replies) { reply ->
            QuickReplyChip(reply = reply, onClick = { onReply(reply) })
        }
    }
}

@Composable
private fun QuickReplyChip(reply: QuickReply, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val bgColor = if (reply.isGreen) AppColors.SageLt else AppColors.White
    val borderColor = if (reply.isGreen) AppColors.Sage else Color(0xFFE2DCF0)
    val shadowColor = if (reply.isGreen) Color(0xFFB8EDD9) else Color(0xFFD8D2E8)
    val textColor = if (reply.isGreen) AppColors.SageDk else AppColors.Ink2

    Box {
        Box(Modifier.matchParentSize().offset(y = if (isPressed) 0.dp else 3.dp).background(shadowColor, AppShapes.Pill))
        Box(
            Modifier.background(bgColor, AppShapes.Pill).border(2.dp, borderColor, AppShapes.Pill)
                .clip(AppShapes.Pill)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = 13.dp, vertical = 7.dp),
        ) {
            Text(
                reply.label, maxLines = 1,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = textColor),
            )
        }
    }
}

// ── Input Bar ──

@Composable
private fun ChatInputBar(enabled: Boolean, placeholder: String, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    Row(
        modifier = Modifier.fillMaxWidth().background(AppColors.White)
            .border(width = 1.5.dp, color = Color(0xFFEDE9F5))
            .padding(horizontal = 14.dp, vertical = 8.dp).padding(bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                .background(if (enabled) AppColors.Mist else Color(0xFFEDEAF3), RoundedCornerShape(14.dp))
                .border(2.dp, if (enabled) AppColors.Pebble else Color(0xFFD8D4E4), RoundedCornerShape(14.dp))
                .padding(horizontal = 13.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                Text(placeholder, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AppColors.Ink3))
            }
            if (enabled) {
                BasicTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AppColors.Ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) { onSend(text); text = "" } }),
                    singleLine = false, maxLines = 3,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        val canSend = enabled && text.isNotBlank()
        Box(
            modifier = Modifier.size(44.dp).graphicsLayer { alpha = if (canSend) 1f else 0.4f }
                .background(Brush.linearGradient(listOf(AppColors.Coral, AppColors.CoralDk)), RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .then(if (canSend) Modifier.clickable { onSend(text); text = "" } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Outlined.Send, "Send", Modifier.size(20.dp), tint = AppColors.White)
        }
    }
}
