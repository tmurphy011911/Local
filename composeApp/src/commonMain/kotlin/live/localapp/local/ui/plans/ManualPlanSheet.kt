package live.localapp.local.ui.plans

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import live.localapp.local.ui.common.CircleShape
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.repository.EventRepository
import live.localapp.local.platform.PlaceSuggestion
import live.localapp.local.platform.rememberNativeDateRangePicker
import live.localapp.local.platform.rememberNativeTimePicker
import live.localapp.local.platform.rememberPlacesAutocomplete
import live.localapp.local.ui.character.Bo
import live.localapp.local.ui.chat.ChatViewModel
import live.localapp.local.ui.chat.ItineraryDay
import live.localapp.local.ui.chat.ItinerarySlot
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

private val MONTHS = listOf("January","February","March","April","May","June","July","August","September","October","November","December")
private val WEEKDAYS = listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday")

private fun today(): LocalDate =
    kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

private fun fullLabel(d: LocalDate): String =
    "${WEEKDAYS[d.dayOfWeek.ordinal]}, ${MONTHS[d.monthNumber - 1]} ${d.dayOfMonth}"

private fun shortLabel(d: LocalDate): String =
    WEEKDAYS[d.dayOfWeek.ordinal].take(3)

private fun rangeDates(start: LocalDate, end: LocalDate): List<LocalDate> {
    val out = mutableListOf<LocalDate>()
    var cur = start
    while (cur <= end) { out += cur; cur = cur.plus(DatePeriod(days = 1)) }
    return out
}

private fun millisToLocalDate(millis: Long): LocalDate =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date

private fun isoDate(d: LocalDate): String =
    "${d.year}-${d.monthNumber.toString().padStart(2,'0')}-${d.dayOfMonth.toString().padStart(2,'0')}"

private fun formatTime(hour: Int, minute: Int): String {
    val h12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    val ampm = if (hour >= 12) "PM" else "AM"
    val mm = minute.toString().padStart(2, '0')
    return if (minute == 0) "$h12 $ampm" else "$h12:$mm $ampm"
}

private data class ManualEventEntry(
    val title: String,
    val startTime: String,                 // display string e.g. "7:30 PM"
    val startHour: Int,
    val startMinute: Int,
    val locationName: String,
    val locationAddress: String,
    val lat: Double?,
    val lng: Double?,
    val sourceEventId: String? = null,     // non-null when picked from search results
)

private data class DayEntry(
    val date: LocalDate,
    val events: List<ManualEventEntry> = emptyList(),
    val skipped: Boolean = false,
)

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
    onPlanWithBo: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var planTitle by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf<LocalDate?>(null) }
    var endDate by remember { mutableStateOf<LocalDate?>(null) }
    var partySize by remember { mutableStateOf(2) }
    val dayEntries = remember { mutableStateListOf<DayEntry>() }
    var step by remember { mutableStateOf(0) }
    var addingForDayIndex by remember { mutableStateOf<Int?>(null) }

    // 3 total: (1) title + dates, (2) party size, (3) build all days at once.
    val totalSteps = 3

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
            // Header: back/X, progress tracker, "Plan with Bo" link
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AppColors.Ink.copy(alpha = 0.05f))
                        .clickable {
                            if (step == 0) {
                                live.localapp.local.observability.Analytics.track(
                                    live.localapp.local.observability.AnalyticsEvent.planCreateAbandoned(
                                        lastStep = "step_$step"
                                    )
                                )
                                onDismiss()
                            } else step -= 1
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (step == 0) Icons.Outlined.Close else Icons.Outlined.KeyboardArrowLeft,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = AppColors.Ink,
                    )
                }
                Spacer(Modifier.width(12.dp))
                ProgressTracker(step = step + 1, total = totalSteps, modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onDismiss()
                        onPlanWithBo()
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Bo(size = 18.dp, level = live.localapp.local.ui.character.cachedBoLevel(), showSparkles = false, showShadow = false)
                Spacer(Modifier.width(6.dp))
                Text(
                    "I'd rather plan with Bo",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = AppColors.Coral,
                    ),
                )
            }

            Spacer(Modifier.height(8.dp))

            when {
                step == 0 -> StepTitleAndDates(
                    title = planTitle,
                    onTitleChange = { planTitle = it },
                    startDate = startDate,
                    endDate = endDate,
                    onRangeChange = { s, e ->
                        startDate = s
                        endDate = e
                    },
                    onContinue = {
                        val s = startDate ?: return@StepTitleAndDates
                        val e = endDate ?: return@StepTitleAndDates
                        val dates = rangeDates(s, e)
                        dayEntries.clear()
                        dayEntries.addAll(dates.map { d ->
                            DayEntry(date = d)
                        })
                        step = 1
                    },
                )
                step == 1 -> StepPartySize(
                    partySize = partySize,
                    onChange = { partySize = it },
                    onContinue = { step = 2 },
                )
                else -> StepBuildAllDays(
                    days = dayEntries,
                    onAddEventForDay = { addingForDayIndex = it },
                    onRemoveEvent = { dayIdx, evIdx ->
                        val d = dayEntries[dayIdx]
                        dayEntries[dayIdx] = d.copy(
                            events = d.events.toMutableList().also { it.removeAt(evIdx) },
                        )
                    },
                    onSkipToggle = { dayIdx ->
                        val d = dayEntries[dayIdx]
                        dayEntries[dayIdx] = d.copy(skipped = !d.skipped)
                    },
                    onSave = {
                        if (!canSavePlan()) {
                            onDismiss()
                            onPaywallRequired()
                            return@StepBuildAllDays
                        }
                        val itinerary = dayEntries.map { d ->
                            ItineraryDay(
                                label = fullLabel(d.date),
                                shortName = shortLabel(d.date),
                                slots = d.events.toSlots(d.date),
                            )
                        }
                        val title = planTitle.ifBlank { "My Plan" }
                        chatVm.saveAndCelebrateManualPlan(title, itinerary, partySize)
                        onDismiss()
                        onPlanSavedOpenChat()
                    },
                )
            }
        }
    }

    val addingIndex = addingForDayIndex
    if (addingIndex != null && addingIndex in dayEntries.indices) {
        AddEventSheet(
            forDate = dayEntries[addingIndex].date,
            eventRepo = eventRepo,
            userLat = userLat,
            userLng = userLng,
            onDismiss = { addingForDayIndex = null },
            onSave = { event ->
                val d = dayEntries[addingIndex]
                dayEntries[addingIndex] = d.copy(
                    events = d.events + event,
                    skipped = false,
                )
                addingForDayIndex = null
            },
        )
    }
}

private fun List<ManualEventEntry>.toSlots(date: LocalDate): List<ItinerarySlot> {
    val sorted = sortedBy { it.startHour * 60 + it.startMinute }
    return sorted.mapIndexed { i, e ->
        ItinerarySlot(
            id = e.sourceEventId?.let { "event_$it" } ?: "manual_${date}_$i",
            time = e.startTime,
            emoji = "📍",
            name = e.title,
            detail = e.locationName.ifBlank { e.locationAddress },
            price = "",
            isFree = true,
            eventId = e.sourceEventId,
            isCustom = e.sourceEventId == null,
            venueAddress = e.locationAddress.ifBlank { null },
            venueLat = e.lat,
            venueLng = e.lng,
        )
    }
}

@Composable
private fun ProgressTracker(step: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(total) { i ->
            val target = if (i <= step - 1) 1f else 0f
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

@Composable
private fun StepTitleAndDates(
    title: String,
    onTitleChange: (String) -> Unit,
    startDate: LocalDate?,
    endDate: LocalDate?,
    onRangeChange: (LocalDate?, LocalDate?) -> Unit,
    onContinue: () -> Unit,
) {
    val datePicker = rememberNativeDateRangePicker()

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "Name your plan",
            style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = AppColors.Ink),
        )
        Spacer(Modifier.height(12.dp))
        InputBar(
            value = title,
            onValueChange = onTitleChange,
            placeholder = "e.g. Date Night in Boston",
            leadingIcon = null,
            tag = "plan_name_input",
        )

        Spacer(Modifier.height(20.dp))

        Text(
            "When is it?",
            style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = AppColors.Ink),
        )
        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, AppColors.Ink.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                .clickable {
                    datePicker.show(
                        initialStartMillis = startDate?.let { isoToMillis(it) },
                        initialEndMillis = endDate?.let { isoToMillis(it) },
                        onPicked = { sMillis, eMillis ->
                            val s = millisToLocalDate(sMillis)
                            val e = millisToLocalDate(eMillis)
                            onRangeChange(s, e)
                        },
                        onCancelled = {},
                    )
                }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Start",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    startDate?.let { fullLabel(it) } ?: "Tap to pick",
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (startDate != null) AppColors.Ink else AppColors.Ink3,
                    ),
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .width(1.dp)
                    .background(AppColors.Ink.copy(alpha = 0.1f)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "End",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    endDate?.let { fullLabel(it) } ?: "Tap to pick",
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (endDate != null) AppColors.Ink else AppColors.Ink3,
                    ),
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        val canContinue = title.isNotBlank() && startDate != null && endDate != null
        PrimaryActionButton(label = "Continue", enabled = canContinue, onClick = onContinue)
        Spacer(Modifier.height(20.dp))
    }
}

private fun isoToMillis(d: LocalDate): Long {
    // Treat the date as UTC midnight; this matches what DateRangePicker emits.
    val components = listOf(
        d.year, d.monthNumber.toString().padStart(2, '0'), d.dayOfMonth.toString().padStart(2, '0'),
    ).joinToString("-")
    return Instant.parse("${components}T00:00:00Z").toEpochMilliseconds()
}

@Composable
private fun StepPartySize(
    partySize: Int,
    onChange: (Int) -> Unit,
    onContinue: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "How many people?",
            style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = AppColors.Ink),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Including you.",
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AppColors.Ink3),
        )

        Spacer(Modifier.height(28.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperButton(icon = Icons.Outlined.Remove, enabled = partySize > 1) {
                onChange((partySize - 1).coerceAtLeast(1))
            }
            Spacer(Modifier.width(20.dp))
            Text(
                partySize.toString(),
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Bold,
                    fontSize = 56.sp,
                    color = AppColors.Ink,
                ),
                modifier = Modifier.width(80.dp),
            )
            Spacer(Modifier.width(20.dp))
            StepperButton(icon = Icons.Outlined.Add, enabled = partySize < 10) {
                onChange((partySize + 1).coerceAtMost(10))
            }
        }

        Spacer(Modifier.height(40.dp))

        PrimaryActionButton(label = "Continue", enabled = true, onClick = onContinue)
    }
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (enabled) AppColors.Coral.copy(alpha = 0.12f) else AppColors.Ink.copy(alpha = 0.05f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = null,
            tint = if (enabled) AppColors.Coral else AppColors.Ink.copy(alpha = 0.3f),
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun StepBuildAllDays(
    days: List<DayEntry>,
    onAddEventForDay: (Int) -> Unit,
    onRemoveEvent: (dayIdx: Int, eventIdx: Int) -> Unit,
    onSkipToggle: (Int) -> Unit,
    onSave: () -> Unit,
) {
    // Save is enabled when every day either has at least one event or is
    // explicitly skipped. Prevents accidentally saving a plan with empty
    // middle days.
    val canSave = days.all { it.events.isNotEmpty() || it.skipped }
        && days.any { it.events.isNotEmpty() }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Build your plan",
            style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = AppColors.Ink),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (days.size > 1) "Add events to each day, or skip days you're not planning."
            else "Add events to your day.",
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AppColors.Ink3),
        )

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            days.forEachIndexed { idx, entry ->
                DaySection(
                    entry = entry,
                    dayNumber = idx + 1,
                    showDayNumber = days.size > 1,
                    onAddEvent = { onAddEventForDay(idx) },
                    onRemoveEvent = { evIdx -> onRemoveEvent(idx, evIdx) },
                    onSkipToggle = { onSkipToggle(idx) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        PrimaryActionButton(
            label = "Save Plan",
            enabled = canSave,
            onClick = onSave,
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun DaySection(
    entry: DayEntry,
    dayNumber: Int,
    showDayNumber: Boolean,
    onAddEvent: () -> Unit,
    onRemoveEvent: (Int) -> Unit,
    onSkipToggle: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                if (showDayNumber) {
                    Text(
                        "Day $dayNumber",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3),
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    fullLabel(entry.date),
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppColors.Ink),
                )
            }
            Text(
                if (entry.skipped) "Add events" else "Skip",
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Coral),
                modifier = Modifier.clickable(onClick = onSkipToggle).padding(8.dp),
            )
        }

        Spacer(Modifier.height(10.dp))

        if (entry.skipped) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.Ink.copy(alpha = 0.04f))
                    .padding(vertical = 18.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Skipped",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AppColors.Ink3),
                )
            }
        } else {
            entry.events.forEachIndexed { i, e ->
                EventListRow(event = e, onRemove = { onRemoveEvent(i) })
                Spacer(Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(BorderStrokeDashed(), RoundedCornerShape(14.dp))
                    .clickable(onClick = onAddEvent)
                    .padding(vertical = 14.dp, horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = AppColors.Coral, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (entry.events.isEmpty()) "Add an event" else "Add another event",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.Coral),
                )
            }
        }
    }
}

@Composable
private fun EventListRow(event: ManualEventEntry, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.dp, AppColors.Ink.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
            .padding(vertical = 12.dp, horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                event.startTime.ifBlank { "—" },
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = AppColors.Coral),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                event.title,
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AppColors.Ink),
            )
            if (event.locationName.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    event.locationName,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "Remove", tint = AppColors.Ink3, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun PrimaryActionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) AppColors.Coral else AppColors.Coral.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = Color.White),
        )
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector?,
    tag: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, AppColors.Ink.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = AppColors.Ink3, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Box(modifier = Modifier.weight(1f)) {
            if (value.isBlank()) {
                Text(
                    placeholder,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AppColors.Ink3),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AppColors.Ink),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(AppColors.Coral),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BorderStrokeDashed(): androidx.compose.foundation.BorderStroke =
    androidx.compose.foundation.BorderStroke(width = 1.dp, color = AppColors.Coral.copy(alpha = 0.5f))

// ─── Add-event sheet ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEventSheet(
    forDate: LocalDate,
    eventRepo: EventRepository,
    userLat: Double?,
    userLng: Double?,
    onDismiss: () -> Unit,
    onSave: (ManualEventEntry) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val timePicker = rememberNativeTimePicker()
    val placesAutocomplete = rememberPlacesAutocomplete()

    var eventQuery by remember { mutableStateOf("") }
    var eventResults by remember { mutableStateOf<List<Event>>(emptyList()) }
    var pickedTitle by remember { mutableStateOf<String?>(null) }
    var pickedSourceEventId by remember { mutableStateOf<String?>(null) }
    var eventSearchJob by remember { mutableStateOf<Job?>(null) }

    var locationQuery by remember { mutableStateOf("") }
    var locationSuggestions by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }
    var pickedLocationName by remember { mutableStateOf<String?>(null) }
    var pickedLocationAddress by remember { mutableStateOf<String?>(null) }
    var pickedLocationLat by remember { mutableStateOf<Double?>(null) }
    var pickedLocationLng by remember { mutableStateOf<Double?>(null) }
    var locationJob by remember { mutableStateOf<Job?>(null) }

    var startHour by remember { mutableStateOf<Int?>(null) }
    var startMinute by remember { mutableStateOf<Int?>(null) }

    val canSave by remember {
        derivedStateOf {
            !pickedTitle.isNullOrBlank()
                && !pickedLocationName.isNullOrBlank()
                && startHour != null
        }
    }

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
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AppColors.Ink.copy(alpha = 0.05f))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(20.dp), tint = AppColors.Ink)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "Add an event",
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AppColors.Ink),
                )
            }

            Spacer(Modifier.height(20.dp))

            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                // Event name
                Text(
                    "Event",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
                )
                Spacer(Modifier.height(6.dp))
                InputBar(
                    value = eventQuery,
                    onValueChange = {
                        eventQuery = it
                        pickedTitle = null
                        pickedSourceEventId = null
                        eventSearchJob?.cancel()
                        eventSearchJob = if (it.length >= 3) {
                            scope.launch {
                                delay(220)
                                val results = runCatching {
                                    eventRepo.searchEvents(
                                        keyword = it,
                                        userLat = userLat,
                                        userLng = userLng,
                                        radiusMiles = 50.0,
                                        limit = 5,
                                        startDate = isoDate(forDate),
                                        endDate = isoDate(forDate),
                                    )
                                }.getOrNull().orEmpty()
                                eventResults = results
                            }
                        } else {
                            eventResults = emptyList()
                            null
                        }
                    },
                    placeholder = "Search or type a new event",
                    leadingIcon = Icons.Outlined.Search,
                )
                if (pickedTitle != null) {
                    Spacer(Modifier.height(6.dp))
                    SelectionChip(label = "Selected: ${pickedTitle}") {
                        pickedTitle = null
                        pickedSourceEventId = null
                    }
                } else if (eventQuery.length >= 3) {
                    Spacer(Modifier.height(6.dp))
                    eventResults.forEach { ev ->
                        SuggestionRow(
                            primary = ev.title,
                            secondary = listOfNotNull(ev.venueName, ev.dateDisplay).joinToString(" · "),
                        ) {
                            pickedTitle = ev.title
                            pickedSourceEventId = ev.id
                            eventQuery = ev.title
                            eventResults = emptyList()
                            // If the event has a venue, prefill the location bar.
                            if (!ev.venueName.isNullOrBlank()) {
                                locationQuery = ev.venueName ?: ""
                                pickedLocationName = ev.venueName
                                pickedLocationAddress = ev.venueAddress ?: ""
                                pickedLocationLat = ev.venueLat
                                pickedLocationLng = ev.venueLng
                            }
                        }
                    }
                    SuggestionRow(
                        primary = "+ Add new event \"$eventQuery\"",
                        secondary = "Use what you typed as the event name",
                        accent = true,
                    ) {
                        pickedTitle = eventQuery
                        pickedSourceEventId = null
                        eventResults = emptyList()
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Location
                Text(
                    "Location",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
                )
                Spacer(Modifier.height(6.dp))
                InputBar(
                    value = locationQuery,
                    onValueChange = {
                        locationQuery = it
                        pickedLocationName = null
                        pickedLocationAddress = null
                        pickedLocationLat = null
                        pickedLocationLng = null
                        locationJob?.cancel()
                        locationJob = if (it.length >= 3) {
                            scope.launch {
                                delay(220)
                                locationSuggestions = placesAutocomplete.query(it, userLat, userLng)
                            }
                        } else {
                            locationSuggestions = emptyList()
                            null
                        }
                    },
                    placeholder = "Search a venue or address",
                    leadingIcon = Icons.Outlined.Place,
                )
                if (pickedLocationName != null) {
                    Spacer(Modifier.height(6.dp))
                    SelectionChip(label = "Selected: ${pickedLocationName}") {
                        pickedLocationName = null
                        pickedLocationAddress = null
                        pickedLocationLat = null
                        pickedLocationLng = null
                    }
                } else if (locationQuery.length >= 3) {
                    Spacer(Modifier.height(6.dp))
                    locationSuggestions.forEach { s ->
                        SuggestionRow(primary = s.primaryText, secondary = s.secondaryText) {
                            scope.launch {
                                val details = placesAutocomplete.resolve(s)
                                pickedLocationName = details?.name ?: s.primaryText
                                pickedLocationAddress = details?.formattedAddress
                                    ?: listOf(s.primaryText, s.secondaryText).filter { it.isNotBlank() }.joinToString(", ")
                                pickedLocationLat = details?.lat
                                pickedLocationLng = details?.lng
                                locationQuery = pickedLocationName ?: ""
                                locationSuggestions = emptyList()
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Start time
                Text(
                    "Start time",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, AppColors.Ink.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .clickable {
                            timePicker.show(
                                initialHour = startHour ?: 19,
                                initialMinute = startMinute ?: 0,
                                onPicked = { h, m ->
                                    startHour = h
                                    startMinute = m
                                },
                                onCancelled = {},
                            )
                        }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, tint = AppColors.Ink3, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    val display = if (startHour != null) formatTime(startHour!!, startMinute ?: 0) else "Pick a start time"
                    Text(
                        display,
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = if (startHour != null) AppColors.Ink else AppColors.Ink3,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            PrimaryActionButton(
                label = "Save event",
                enabled = canSave,
                onClick = {
                    val title = pickedTitle ?: return@PrimaryActionButton
                    val name = pickedLocationName ?: return@PrimaryActionButton
                    val h = startHour ?: return@PrimaryActionButton
                    val m = startMinute ?: 0
                    onSave(
                        ManualEventEntry(
                            title = title,
                            startTime = formatTime(h, m),
                            startHour = h,
                            startMinute = m,
                            locationName = name,
                            locationAddress = pickedLocationAddress ?: "",
                            lat = pickedLocationLat,
                            lng = pickedLocationLng,
                            sourceEventId = pickedSourceEventId,
                        )
                    )
                },
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SuggestionRow(
    primary: String,
    secondary: String,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                primary,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = if (accent) AppColors.Coral else AppColors.Ink,
                ),
            )
            if (secondary.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    secondary,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
                )
            }
        }
    }
}

@Composable
private fun SelectionChip(label: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(AppColors.Coral.copy(alpha = 0.12f))
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Check, contentDescription = null, tint = AppColors.Coral, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Coral),
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .clickable(onClick = onClear),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "Clear", tint = AppColors.Coral, modifier = Modifier.size(12.dp))
        }
    }
}
