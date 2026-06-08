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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Schedule
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import live.localapp.local.domain.model.PlanSlot
import live.localapp.local.platform.PlaceSuggestion
import live.localapp.local.platform.rememberNativeTimePicker
import live.localapp.local.platform.rememberPlacesAutocomplete
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

/**
 * "+ Add event" entry point on Plan Detail. Mirrors the home-page Create Plan
 * menu: two choices, "Add manually" or "Ask Bo". Unlike the home menu (a FAB
 * overlay), this is a small bottom sheet because the Add-event button lives
 * inline in the day list, not on the tab bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEventChooserSheet(
    dayLabel: String,
    onManual: () -> Unit,
    onAskBo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppColors.Snow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .heightIn(min = 180.dp),
        ) {
            Text(
                "Add to $dayLabel",
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 18.sp, color = AppColors.Ink),
            )
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChooserRow(
                    icon = Icons.Outlined.Edit,
                    title = "Add manually",
                    subtitle = "Enter the event name, location, and time",
                    onClick = onManual,
                )
                ChooserRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "Ask Bo",
                    subtitle = "Let Bo find and add something for you",
                    onClick = onAskBo,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ChooserRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
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

// A committed location: either a Google Places result (with coords) or the
// exact free text the user typed (coords null). canSave requires one of these
// to be set — typing alone isn't enough, so the user always makes an explicit
// "select a place" OR "use what I typed" choice.
private data class CommittedLocation(
    val name: String,
    val address: String,
    val lat: Double?,
    val lng: Double?,
)

/**
 * The "Add manually" form. Event name = free text. Location = Google Places
 * autocomplete with a "use exactly what I typed" fallback. Date is implicit
 * (the day this was launched from); the user only picks the start time.
 * On save, builds a custom [PlanSlot] and hands it to [onAddSlot].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEventFormSheet(
    dayLabel: String,
    userLat: Double?,
    userLng: Double?,
    onDismiss: () -> Unit,
    onAddSlot: (PlanSlot) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val timePicker = rememberNativeTimePicker()
    val places = rememberPlacesAutocomplete()

    var name by remember { mutableStateOf("") }

    var locationQuery by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }
    var committed by remember { mutableStateOf<CommittedLocation?>(null) }
    var locationJob by remember { mutableStateOf<Job?>(null) }

    var startHour by remember { mutableStateOf<Int?>(null) }
    var startMinute by remember { mutableStateOf<Int?>(null) }

    val canSave by remember {
        derivedStateOf { name.isNotBlank() && committed != null && startHour != null }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppColors.Snow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .heightIn(min = 280.dp),
        ) {
            Text(
                "Add to $dayLabel",
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 18.sp, color = AppColors.Ink),
            )
            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Event name — free text
                FieldLabel("Event name")
                InputBox(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "Dinner, show, meetup…",
                )

                // Location — Google Places + custom fallback
                FieldLabel("Location")
                if (committed != null) {
                    SelectedRow(label = committed!!.name) {
                        committed = null
                        locationQuery = ""
                        suggestions = emptyList()
                    }
                } else {
                    InputBox(
                        value = locationQuery,
                        onValueChange = {
                            locationQuery = it
                            locationJob?.cancel()
                            locationJob = if (it.trim().length >= 2) {
                                scope.launch {
                                    delay(220)
                                    suggestions = runCatching { places.query(it.trim(), userLat, userLng) }
                                        .getOrDefault(emptyList())
                                }
                            } else {
                                suggestions = emptyList()
                                null
                            }
                        },
                        placeholder = "Search a place or type an address",
                        leadingIcon = Icons.Outlined.Place,
                    )
                    if (locationQuery.trim().length >= 2) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            suggestions.forEach { s ->
                                SuggestionRow(primary = s.primaryText, secondary = s.secondaryText) {
                                    scope.launch {
                                        val d = runCatching { places.resolve(s) }.getOrNull()
                                        committed = CommittedLocation(
                                            name = d?.name ?: s.primaryText,
                                            address = d?.formattedAddress
                                                ?: listOf(s.primaryText, s.secondaryText).filter { it.isNotBlank() }.joinToString(", "),
                                            lat = d?.lat,
                                            lng = d?.lng,
                                        )
                                        suggestions = emptyList()
                                    }
                                }
                            }
                            // "use exactly what I typed" — custom location, no coords
                            SuggestionRow(
                                primary = "Use \"${locationQuery.trim()}\"",
                                secondary = "Add it exactly as typed",
                                accent = true,
                            ) {
                                committed = CommittedLocation(
                                    name = locationQuery.trim(),
                                    address = "",
                                    lat = null,
                                    lng = null,
                                )
                                suggestions = emptyList()
                            }
                        }
                    }
                }

                // Start time — date is implicit (the day), user picks time
                FieldLabel("Start time")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(12.dp))
                        .clickable {
                            timePicker.show(
                                initialHour = startHour ?: 19,
                                initialMinute = startMinute ?: 0,
                                onPicked = { h, m -> startHour = h; startMinute = m },
                                onCancelled = {},
                            )
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, tint = AppColors.Ink3, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    val display = startHour?.let { formatTime(it, startMinute ?: 0) } ?: "Pick a start time"
                    Text(
                        display,
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = if (startHour != null) AppColors.Ink else AppColors.Pebble,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (canSave) AppColors.Coral else AppColors.Coral.copy(alpha = 0.4f),
                        RoundedCornerShape(12.dp),
                    )
                    .clickable(enabled = canSave) {
                        val loc = committed ?: return@clickable
                        val h = startHour ?: return@clickable
                        val m = startMinute ?: 0
                        onAddSlot(
                            PlanSlot(
                                id = "slot_${kotlin.random.Random.nextLong().toString(36)}",
                                time = formatTime(h, m),
                                emoji = "",
                                name = name.trim(),
                                detail = loc.name,
                                price = "",
                                isFree = false,
                                isCustom = true,
                                venueName = loc.name,
                                venueAddress = loc.address.ifBlank { null },
                                venueLat = loc.lat,
                                venueLng = loc.lng,
                            )
                        )
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Add event", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = Color.White))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
    )
}

@Composable
private fun InputBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = AppColors.Ink3, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink),
                cursorBrush = SolidColor(AppColors.Coral),
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

@Composable
private fun SuggestionRow(primary: String, secondary: String, accent: Boolean = false, onClick: () -> Unit) {
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
private fun SelectedRow(label: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(AppColors.Coral.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClear)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Place, contentDescription = null, tint = AppColors.Coral, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink),
            modifier = Modifier.weight(1f),
        )
        Text(
            "Change",
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Coral),
        )
    }
}

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
