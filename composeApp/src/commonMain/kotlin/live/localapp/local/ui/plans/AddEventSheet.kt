package live.localapp.local.ui.plans

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.datetime.toLocalDateTime
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.model.PlanSlot
import live.localapp.local.domain.repository.EventRepository
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts
import org.koin.mp.KoinPlatform

private enum class AddMode { CHOICE, SEARCH, CUSTOM }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEventSheet(
    dayLabel: String,
    userLat: Double?,
    userLng: Double?,
    onDismiss: () -> Unit,
    onAddSlot: (PlanSlot) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var mode by remember { mutableStateOf(AddMode.CHOICE) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppColors.Snow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .heightIn(min = 200.dp),
        ) {
            Text(
                "Add to $dayLabel",
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 18.sp, color = AppColors.Ink),
            )
            Spacer(Modifier.height(14.dp))

            when (mode) {
                AddMode.CHOICE -> ChoiceView(
                    onSearch = { mode = AddMode.SEARCH },
                    onCustom = { mode = AddMode.CUSTOM },
                )
                AddMode.SEARCH -> SearchView(
                    userLat = userLat,
                    userLng = userLng,
                    onAdd = { slot ->
                        onAddSlot(slot)
                        onDismiss()
                    },
                )
                AddMode.CUSTOM -> CustomView(
                    onAdd = { slot ->
                        onAddSlot(slot)
                        onDismiss()
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ChoiceView(onSearch: () -> Unit, onCustom: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChoiceRow(
            icon = Icons.Outlined.Search,
            title = "Search local events",
            subtitle = "Find something happening nearby",
            onClick = onSearch,
        )
        ChoiceRow(
            icon = Icons.Outlined.Add,
            title = "Add custom event",
            subtitle = "Enter the details yourself",
            onClick = onCustom,
        )
    }
}

@Composable
private fun ChoiceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(14.dp))
            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(AppColors.Mist, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = AppColors.Coral)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AppColors.Ink))
            Text(subtitle, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3))
        }
    }
}

@Composable
private fun SearchView(
    userLat: Double?,
    userLng: Double?,
    onAdd: (PlanSlot) -> Unit,
) {
    val eventRepo: EventRepository = remember { KoinPlatform.getKoin().get() }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Event>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    // Debounced search
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        isSearching = true
        try {
            results = eventRepo.searchEvents(
                keyword = query.trim(),
                limit = 25,
                userLat = userLat,
                userLng = userLng,
                radiusMiles = 50.0,
            )
        } catch (_: Throwable) {
            results = emptyList()
        }
        isSearching = false
    }

    // Search field
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            textStyle = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink),
            cursorBrush = SolidColor(AppColors.Coral),
            singleLine = true,
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        "Search events...",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AppColors.Pebble),
                    )
                }
                inner()
            },
        )
    }
    Spacer(Modifier.height(10.dp))

    if (query.trim().length < 2) {
        Text(
            "Type at least 2 characters to search",
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
        )
    } else if (results.isEmpty() && !isSearching) {
        Text(
            "No events found",
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
        )
    } else {
        LazyColumn(
            modifier = Modifier.heightIn(max = 400.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(results, key = { it.id }) { event ->
                EventResultRow(event = event, onClick = {
                    onAdd(eventToSlot(event))
                })
            }
        }
    }
}

@Composable
private fun EventResultRow(event: Event, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(1.dp, AppColors.Mist, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                event.title,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink),
                maxLines = 1,
            )
            if (!event.venueName.isNullOrBlank()) {
                Text(
                    event.venueName,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
                    maxLines = 1,
                )
            }
        }
        Icon(Icons.Outlined.Add, contentDescription = "Add", modifier = Modifier.size(20.dp), tint = AppColors.Coral)
    }
}

private fun eventToSlot(event: Event): PlanSlot {
    val time = parseEventTime(event.startAt) ?: ""
    return PlanSlot(
        id = "slot_${kotlin.random.Random.nextLong().toString(36)}",
        time = time,
        emoji = "",
        name = event.title,
        detail = event.venueName ?: "",
        price = event.priceDisplay,
        isFree = event.isFree,
        eventId = event.id,
        imageUrl = event.imageUrl,
        ticketUrl = event.ticketUrl,
        venueAddress = event.venueAddress,
        venueLat = event.venueLat,
        venueLng = event.venueLng,
        priceMin = event.priceMin,
        priceMax = event.priceMax,
        sourceUrl = event.sourceUrl,
    )
}

/** Parse ISO datetime and return e.g. "7:30 PM". Returns null if unparseable. */
@Suppress("DEPRECATION")
private fun parseEventTime(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return try {
        val instant = kotlinx.datetime.Instant.parse(iso)
        val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
        val hour24 = local.hour
        val minute = local.minute
        val hour12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        val ampm = if (hour24 < 12) "AM" else "PM"
        val minStr = if (minute == 0) "" else ":${minute.toString().padStart(2, '0')}"
        "$hour12$minStr $ampm"
    } catch (_: Throwable) { null }
}

@Composable
private fun CustomView(onAdd: (PlanSlot) -> Unit) {
    var name by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LabeledField(label = "Name", value = name, placeholder = "Dinner at home", onValueChange = { name = it; error = null })
        LabeledField(label = "Time", value = time, placeholder = "7:00 PM", onValueChange = { time = it; error = null }, keyboardType = KeyboardType.Text)
        LabeledField(label = "Notes (optional)", value = detail, placeholder = "Anything to remember", onValueChange = { detail = it })

        if (error != null) {
            Text(
                error!!,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Coral),
            )
        }

        Spacer(Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.Coral, RoundedCornerShape(12.dp))
                .clickable {
                    val n = name.trim()
                    val t = time.trim()
                    when {
                        n.isEmpty() -> error = "Name is required"
                        t.isEmpty() -> error = "Time is required"
                        else -> {
                            onAdd(
                                PlanSlot(
                                    id = "slot_${kotlin.random.Random.nextLong().toString(36)}",
                                    time = t,
                                    emoji = "",
                                    name = n,
                                    detail = detail.trim(),
                                    price = "",
                                    isFree = false,
                                    isCustom = true,
                                )
                            )
                        }
                    }
                }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Add event", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = Color.White))
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column {
        Text(
            label,
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(Color.White, RoundedCornerShape(10.dp))
                .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink),
                cursorBrush = SolidColor(AppColors.Coral),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                singleLine = true,
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AppColors.Pebble),
                        )
                    }
                    inner()
                },
            )
        }
    }
}
