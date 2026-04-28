package live.localapp.local.ui.plans

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Remove
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.repository.EventRepository
import live.localapp.local.ui.chat.ChatViewModel
import live.localapp.local.ui.chat.ItineraryDay
import live.localapp.local.ui.chat.ItinerarySlot
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

// Internal state holders — scoped to the sheet's lifetime.

internal data class ManualEvent(
    val title: String,
    val startTime: String,
    val endTime: String,
    val location: String,
    val description: String,
)

internal data class DayPlan(
    val date: LocalDate,
    val selectedEventIds: Set<String> = emptySet(),
    val manualEvents: List<ManualEvent> = emptyList(),
    val wantBreakfast: Boolean = false,
    val wantLunch: Boolean = false,
    val wantDinner: Boolean = false,
)

private val MONTH_NAMES = listOf(
    "January","February","March","April","May","June",
    "July","August","September","October","November","December",
)
private val WEEKDAY_NAMES = listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday")

private fun today(): LocalDate = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

private fun fullLabel(d: LocalDate): String =
    "${WEEKDAY_NAMES[d.dayOfWeek.ordinal]}, ${MONTH_NAMES[d.monthNumber - 1]} ${d.dayOfMonth}"

private fun shortLabel(d: LocalDate): String =
    WEEKDAY_NAMES[d.dayOfWeek.ordinal].take(3)

private fun rangeDates(start: LocalDate, end: LocalDate): List<LocalDate> {
    val days = mutableListOf<LocalDate>()
    var cur = start
    while (cur <= end) {
        days += cur
        cur = cur.plus(DatePeriod(days = 1))
    }
    return days
}

private fun rangeDisplay(start: LocalDate, end: LocalDate): String {
    if (start == end) return "${MONTH_NAMES[start.monthNumber - 1]} ${start.dayOfMonth}"
    val sm = MONTH_NAMES[start.monthNumber - 1]
    val em = MONTH_NAMES[end.monthNumber - 1]
    return if (sm == em) "$sm ${start.dayOfMonth}–${end.dayOfMonth}"
    else "$sm ${start.dayOfMonth} – $em ${end.dayOfMonth}"
}

@Composable
private fun ProgressTracker(step: Int, total: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(total) { i ->
            val target = when {
                i < step - 1 -> 1f
                i == step - 1 -> 1f
                else -> 0f
            }
            val fill by animateFloatAsState(targetValue = target, animationSpec = tween(450))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(AppColors.Ink.copy(alpha = 0.08f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fill)
                        .height(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(AppColors.Coral),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualPlanSheet(
    onDismiss: () -> Unit,
    chatVm: ChatViewModel,
    eventRepo: EventRepository,
    userLat: Double? = null,
    userLng: Double? = null,
    onPlanSavedOpenChat: () -> Unit = {},
    canSavePlan: () -> Boolean = { true },
    onPaywallRequired: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var currentStep by remember { mutableStateOf(1) }
    var startDate by remember { mutableStateOf(today()) }
    var endDate by remember { mutableStateOf(today()) }
    var partySize by remember { mutableStateOf(1) }
    var dayPlans by remember { mutableStateOf(listOf(DayPlan(today()))) }
    // Shared cache of every Event we've fetched so selection → slot conversion
    // at save time is a simple lookup by ID.
    val eventCache = remember { mutableStateMapOf<String, Event>() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            // Header: back arrow (or X on step 1) + tracker
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AppColors.Ink.copy(alpha = 0.05f))
                        .clickable {
                            if (currentStep == 1) onDismiss()
                            else currentStep -= 1
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (currentStep == 1) Icons.Outlined.Close else Icons.Outlined.KeyboardArrowLeft,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = AppColors.Ink,
                    )
                }
                Spacer(Modifier.width(12.dp))
                ProgressTracker(step = currentStep, total = 3, modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(18.dp))

            when (currentStep) {
                1 -> Step1DatesParty(
                    startDate = startDate,
                    endDate = endDate,
                    partySize = partySize,
                    onStartDateChange = {
                        startDate = it
                        if (endDate < it) endDate = it
                    },
                    onEndDateChange = { endDate = it },
                    onPartySizeChange = { partySize = it.coerceIn(1, 20) },
                    onNext = {
                        val dates = rangeDates(startDate, endDate)
                        dayPlans = dates.map { d ->
                            dayPlans.firstOrNull { it.date == d } ?: DayPlan(d)
                        }
                        currentStep = 2
                    },
                )
                2 -> Step2EventsPerDay(
                    dayPlans = dayPlans,
                    onDayPlansChange = { dayPlans = it },
                    eventRepo = eventRepo,
                    eventCache = eventCache,
                    userLat = userLat,
                    userLng = userLng,
                    onNext = { currentStep = 3 },
                )
                3 -> Step3Review(
                    dayPlans = dayPlans,
                    partySize = partySize,
                    startDate = startDate,
                    endDate = endDate,
                    onSave = {
                        if (!canSavePlan()) {
                            onDismiss()
                            onPaywallRequired()
                            return@Step3Review
                        }
                        val itinerary = dayPlans.map { dp ->
                            ItineraryDay(
                                label = fullLabel(dp.date),
                                shortName = shortLabel(dp.date),
                                slots = buildSlotsForDay(dp, eventCache),
                            )
                        }
                        val title = "Plan for ${rangeDisplay(startDate, endDate)}"
                        chatVm.saveAndCelebrateManualPlan(title, itinerary, partySize)
                        onDismiss()
                        onPlanSavedOpenChat()
                    },
                )
            }
        }
    }
}

private fun buildSlotsForDay(dp: DayPlan, eventCache: Map<String, Event>): List<ItinerarySlot> {
    val slots = mutableListOf<ItinerarySlot>()
    dp.selectedEventIds.forEach { id ->
        val e = eventCache[id] ?: return@forEach
        slots += ItinerarySlot(
            id = "event_${e.id}",
            time = formatTimeFromStartAt(e.startAt),
            emoji = categoryEmoji(e.category),
            name = e.title,
            detail = e.venueName ?: e.description ?: "",
            price = e.priceDisplay,
            isFree = e.isFree,
            eventId = e.id,
            imageUrl = e.imageUrl,
            ticketUrl = e.ticketUrl,
            venueAddress = e.venueAddress,
            venueLat = e.venueLat,
            venueLng = e.venueLng,
            priceMin = e.priceMin,
            priceMax = e.priceMax,
            sourceUrl = e.sourceUrl,
            venueCategory = e.category,
        )
    }
    dp.manualEvents.forEachIndexed { i, me ->
        slots += ItinerarySlot(
            id = "manual_${dp.date}_$i",
            time = me.startTime,
            emoji = "📝",
            name = me.title,
            detail = me.description.ifBlank { me.location },
            price = "",
            isFree = true,
            isCustom = true,
            venueAddress = me.location.ifBlank { null },
        )
    }
    return slots
}

private fun categoryEmoji(cat: String?): String = when (cat?.lowercase()) {
    "music" -> "🎵"
    "food" -> "🍽"
    "arts" -> "🎨"
    "sports" -> "🏟"
    "outdoors" -> "🌲"
    "nightlife" -> "🌃"
    else -> "📍"
}

// Extract a human-friendly time from an ISO-8601 startAt string like
// "2026-05-03T19:30:00". Falls back to empty when the format is unexpected.
private fun formatTimeFromStartAt(startAt: String?): String {
    if (startAt.isNullOrBlank()) return ""
    val tPart = startAt.substringAfter('T', "")
    if (tPart.isBlank()) return ""
    val hh = tPart.substringBefore(':').toIntOrNull() ?: return ""
    val mm = tPart.substringAfter(':').substringBefore(':').toIntOrNull() ?: return ""
    val ampm = if (hh >= 12) "PM" else "AM"
    val h12 = when {
        hh == 0 -> 12
        hh > 12 -> hh - 12
        else -> hh
    }
    return if (mm == 0) "$h12 $ampm" else "$h12:${mm.toString().padStart(2, '0')} $ampm"
}

@Composable
private fun Step1DatesParty(
    startDate: LocalDate,
    endDate: LocalDate,
    partySize: Int,
    onStartDateChange: (LocalDate) -> Unit,
    onEndDateChange: (LocalDate) -> Unit,
    onPartySizeChange: (Int) -> Unit,
    onNext: () -> Unit,
) {
    val next30 = remember {
        val base = today()
        (0 until 30).map { base.plus(DatePeriod(days = it)) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "When is your plan?",
            style = TextStyle(
                fontFamily = AppFonts.Heading,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = AppColors.Ink,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Pick a start and end date",
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = AppColors.Ink3,
            ),
        )
        Spacer(Modifier.height(16.dp))

        Text(
            "Start date",
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = AppColors.Ink,
            ),
        )
        Spacer(Modifier.height(8.dp))
        DateChipRow(dates = next30, selected = startDate, onSelect = onStartDateChange)

        Spacer(Modifier.height(20.dp))
        Text(
            "End date",
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = AppColors.Ink,
            ),
        )
        Spacer(Modifier.height(8.dp))
        DateChipRow(
            dates = next30.filter { it >= startDate },
            selected = endDate,
            onSelect = onEndDateChange,
        )

        Spacer(Modifier.height(28.dp))
        Text(
            "How many people?",
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = AppColors.Ink,
            ),
        )
        Spacer(Modifier.height(8.dp))
        PartySizeStepper(value = partySize, onChange = onPartySizeChange)

        Spacer(Modifier.weight(1f))
        PrimaryButton(label = "Next", onClick = onNext)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DateChipRow(
    dates: List<LocalDate>,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        dates.forEach { d ->
            val isSel = d == selected
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSel) AppColors.Coral else AppColors.Ink.copy(alpha = 0.05f))
                    .clickable { onSelect(d) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    shortLabel(d).uppercase(),
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = if (isSel) Color.White else AppColors.Ink3,
                    ),
                )
                Text(
                    d.dayOfMonth.toString(),
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = if (isSel) Color.White else AppColors.Ink,
                    ),
                )
            }
        }
    }
}

@Composable
private fun PartySizeStepper(value: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.Ink.copy(alpha = 0.05f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(Icons.Outlined.Remove) { onChange(value - 1) }
        Spacer(Modifier.width(18.dp))
        Text(
            value.toString(),
            style = TextStyle(
                fontFamily = AppFonts.Heading,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = AppColors.Ink,
            ),
        )
        Spacer(Modifier.width(18.dp))
        StepButton(Icons.Outlined.Add) { onChange(value + 1) }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = AppColors.Ink)
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) AppColors.Coral else AppColors.Ink.copy(alpha = 0.1f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
                color = Color.White,
            ),
        )
    }
}

@Composable
private fun Step2EventsPerDay(
    dayPlans: List<DayPlan>,
    onDayPlansChange: (List<DayPlan>) -> Unit,
    eventRepo: EventRepository,
    eventCache: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Event>,
    userLat: Double?,
    userLng: Double?,
    onNext: () -> Unit,
) {
    var activeIdx by remember { mutableStateOf(0) }
    var showAddManual by remember { mutableStateOf(false) }
    val active = dayPlans.getOrNull(activeIdx) ?: return
    val updateActive: (DayPlan) -> Unit = { updated ->
        onDayPlansChange(dayPlans.mapIndexed { i, d -> if (i == activeIdx) updated else d })
    }

    // Events for the active day — fetched once per date, cached in a
    // parent-owned SnapshotStateMap so selection survives day-switching.
    var dayEvents by remember(active.date) { mutableStateOf<List<Event>>(emptyList()) }
    var isLoadingEvents by remember(active.date) { mutableStateOf(true) }
    LaunchedEffect(active.date) {
        isLoadingEvents = true
        val isoDate = active.date.toString()
        val results = try {
            eventRepo.searchEvents(
                startDate = isoDate,
                endDate = isoDate,
                limit = 50,
                userLat = userLat,
                userLng = userLng,
                radiusMiles = 25.0,
            )
        } catch (_: Throwable) {
            emptyList()
        }
        results.forEach { eventCache[it.id] = it }
        dayEvents = results
        isLoadingEvents = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Plan each day",
            style = TextStyle(
                fontFamily = AppFonts.Heading,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = AppColors.Ink,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            fullLabel(active.date),
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = AppColors.Ink3,
            ),
        )
        Spacer(Modifier.height(12.dp))

        if (dayPlans.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                dayPlans.forEachIndexed { i, dp ->
                    val isSel = i == activeIdx
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSel) AppColors.Coral else AppColors.Ink.copy(alpha = 0.05f))
                            .clickable { activeIdx = i }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "Day ${i + 1}",
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (isSel) Color.White else AppColors.Ink,
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SectionLabel("Events this day (${dayEvents.size})")
                Spacer(Modifier.height(8.dp))
            }
            if (isLoadingEvents) {
                item {
                    Text(
                        "Loading…",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            color = AppColors.Ink3,
                        ),
                    )
                }
            } else if (dayEvents.isEmpty()) {
                item {
                    Text(
                        "No nearby events found. Add a manual event below.",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            color = AppColors.Ink3,
                        ),
                    )
                }
            } else {
                items(dayEvents, key = { it.id }) { event ->
                    val selected = event.id in active.selectedEventIds
                    EventPickRow(
                        event = event,
                        selected = selected,
                        onToggle = {
                            val ids = if (selected) active.selectedEventIds - event.id
                            else active.selectedEventIds + event.id
                            updateActive(active.copy(selectedEventIds = ids))
                        },
                    )
                }
            }
            item {
                Spacer(Modifier.height(20.dp))
                SectionLabel("Find me a meal")
                Spacer(Modifier.height(8.dp))
            }
            item {
                MealCheckboxRow(
                    breakfast = active.wantBreakfast,
                    lunch = active.wantLunch,
                    dinner = active.wantDinner,
                    onBreakfast = { updateActive(active.copy(wantBreakfast = it)) },
                    onLunch = { updateActive(active.copy(wantLunch = it)) },
                    onDinner = { updateActive(active.copy(wantDinner = it)) },
                )
                Spacer(Modifier.height(20.dp))
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Your own event")
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(AppColors.CoralLt)
                            .clickable { showAddManual = !showAddManual }
                            .padding(6.dp),
                    ) {
                        Icon(
                            if (showAddManual) Icons.Outlined.Close else Icons.Outlined.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = AppColors.Coral,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (showAddManual) {
                item {
                    ManualEventForm(
                        onSave = { me ->
                            updateActive(active.copy(manualEvents = active.manualEvents + me))
                            showAddManual = false
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
            items(active.manualEvents) { me ->
                ManualEventRow(
                    event = me,
                    onRemove = {
                        updateActive(active.copy(manualEvents = active.manualEvents - me))
                    },
                )
            }
        }

        val canAdvance = activeIdx == dayPlans.lastIndex
        PrimaryButton(
            label = if (canAdvance) "Review Plan" else "Next Day",
            onClick = {
                if (canAdvance) onNext()
                else activeIdx += 1
            },
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun EventPickRow(
    event: Event,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(
                1.dp,
                if (selected) AppColors.Coral else AppColors.Ink.copy(alpha = 0.08f),
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onToggle)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                event.title,
                maxLines = 2,
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = AppColors.Ink,
                ),
            )
            Spacer(Modifier.height(3.dp))
            val timeStr = formatTimeFromStartAt(event.startAt)
            val subtitle = buildString {
                if (timeStr.isNotBlank()) append(timeStr)
                event.venueName?.let {
                    if (isNotEmpty()) append(" • ")
                    append(it)
                }
                if (event.priceDisplay.isNotBlank()) {
                    if (isNotEmpty()) append(" • ")
                    append(event.priceDisplay)
                }
            }
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        color = AppColors.Ink3,
                    ),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (selected) AppColors.Coral else AppColors.CoralLt),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (selected) Icons.Outlined.Check else Icons.Outlined.Add,
                contentDescription = if (selected) "Added" else "Add",
                modifier = Modifier.size(16.dp),
                tint = if (selected) Color.White else AppColors.Coral,
            )
        }
    }
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        label,
        style = TextStyle(
            fontFamily = AppFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = AppColors.Ink,
        ),
    )
}

@Composable
private fun MealCheckboxRow(
    breakfast: Boolean,
    lunch: Boolean,
    dinner: Boolean,
    onBreakfast: (Boolean) -> Unit,
    onLunch: (Boolean) -> Unit,
    onDinner: (Boolean) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MealChip("Breakfast", breakfast, onBreakfast)
        MealChip("Lunch", lunch, onLunch)
        MealChip("Dinner", dinner, onDinner)
    }
}

@Composable
private fun MealChip(label: String, selected: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AppColors.Coral else AppColors.Ink.copy(alpha = 0.05f))
            .clickable { onToggle(!selected) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = Color.White,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = if (selected) Color.White else AppColors.Ink,
            ),
        )
    }
}

@Composable
private fun ManualEventForm(onSave: (ManualEvent) -> Unit) {
    var title by remember { mutableStateOf("") }
    var startTime by remember { mutableStateOf("") }
    var endTime by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.Ink.copy(alpha = 0.04f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FormField(label = "Title", value = title, onChange = { title = it })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                FormField(label = "Start time", value = startTime, onChange = { startTime = it }, placeholder = "7:00 PM")
            }
            Box(modifier = Modifier.weight(1f)) {
                FormField(label = "End time", value = endTime, onChange = { endTime = it }, placeholder = "9:00 PM")
            }
        }
        FormField(label = "Location", value = location, onChange = { location = it })
        FormField(label = "Description (optional)", value = description, onChange = { description = it })
        Spacer(Modifier.height(4.dp))
        PrimaryButton(
            label = "Add to day",
            enabled = title.isNotBlank() && startTime.isNotBlank(),
            onClick = {
                onSave(
                    ManualEvent(
                        title = title.trim(),
                        startTime = startTime.trim(),
                        endTime = endTime.trim(),
                        location = location.trim(),
                        description = description.trim(),
                    ),
                )
            },
        )
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
) {
    Column {
        Text(
            label,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                color = AppColors.Ink3,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
                .border(1.dp, AppColors.Ink.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = AppColors.Ink,
                ),
                decorationBox = { inner ->
                    if (value.isEmpty() && placeholder.isNotBlank()) {
                        Text(
                            placeholder,
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                color = AppColors.Ink3,
                            ),
                        )
                    }
                    inner()
                },
            )
        }
    }
}

@Composable
private fun ManualEventRow(event: ManualEvent, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.dp, AppColors.Ink.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(14.dp),
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
            )
            Spacer(Modifier.height(2.dp))
            val sub = buildString {
                if (event.startTime.isNotBlank()) append(event.startTime)
                if (event.endTime.isNotBlank()) append(" – ${event.endTime}")
                if (event.location.isNotBlank()) {
                    if (isNotEmpty()) append(" • ")
                    append(event.location)
                }
            }
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        color = AppColors.Ink3,
                    ),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(AppColors.Ink.copy(alpha = 0.05f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Remove",
                modifier = Modifier.size(14.dp),
                tint = AppColors.Ink3,
            )
        }
    }
}

@Composable
private fun Step3Review(
    dayPlans: List<DayPlan>,
    partySize: Int,
    startDate: LocalDate,
    endDate: LocalDate,
    onSave: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Review your plan",
            style = TextStyle(
                fontFamily = AppFonts.Heading,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = AppColors.Ink,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${rangeDisplay(startDate, endDate)} • $partySize ${if (partySize == 1) "person" else "people"}",
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = AppColors.Ink3,
            ),
        )
        Spacer(Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(dayPlans) { dp ->
                val mealCount = listOf(dp.wantBreakfast, dp.wantLunch, dp.wantDinner).count { it }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AppColors.Ink.copy(alpha = 0.04f))
                        .padding(14.dp),
                ) {
                    Text(
                        fullLabel(dp.date),
                        style = TextStyle(
                            fontFamily = AppFonts.Heading,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = AppColors.Ink,
                        ),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${dp.selectedEventIds.size + dp.manualEvents.size} event(s), $mealCount meal slot(s)",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            color = AppColors.Ink3,
                        ),
                    )
                }
            }
        }

        PrimaryButton(label = "Save Plan", onClick = onSave)
        Spacer(Modifier.height(12.dp))
    }
}
