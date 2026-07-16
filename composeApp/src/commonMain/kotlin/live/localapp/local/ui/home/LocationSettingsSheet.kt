package live.localapp.local.ui.home

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import live.localapp.local.ui.common.CircleShape
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import live.localapp.local.Config
import live.localapp.local.platform.openAppSettings
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

private data class PlaceSuggestion(val placeId: String, val mainText: String, val secondaryText: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSettingsSheet(
    currentLocationName: String,
    gpsLocationName: String = "",
    locationMode: LocationMode,
    manualLocationName: String,
    radiusFilter: Double?,
    hasLocationPermission: Boolean,
    onSelectCurrentLocation: () -> Unit,
    onGpsLocation: (lat: Double, lng: Double) -> Unit,
    onGpsDenied: () -> Unit,
    onSelectManualLocation: (lat: Double, lng: Double, name: String) -> Unit,
    onRadiusChanged: (Double?) -> Unit,
    onDismiss: () -> Unit,
    isUnsupportedState: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var requestingGps by remember { mutableStateOf(false) }
    var showEnableDialog by remember { mutableStateOf(false) }
    var awaitingSettingsReturn by remember { mutableStateOf(false) }

    // GPS request triggered when user taps "Current Location"
    live.localapp.local.platform.RequestLocationEffect(
        active = requestingGps,
        onLocation = { lat, lng ->
            requestingGps = false
            awaitingSettingsReturn = false
            onGpsLocation(lat, lng)
        },
        onDenied = {
            requestingGps = false
            if (awaitingSettingsReturn) {
                // User returned from Settings but still didn't grant — stay on manual silently
                awaitingSettingsReturn = false
                onGpsDenied()
            } else {
                showEnableDialog = true
            }
        },
    )

    // When user returns from Settings, re-check permission and retry if now granted.
    live.localapp.local.platform.OnAppResumeEffect {
        if (awaitingSettingsReturn && live.localapp.local.platform.checkLocationPermissionGranted()) {
            requestingGps = true
        }
    }

    if (showEnableDialog) {
        AlertDialog(
            onDismissRequest = { showEnableDialog = false },
            title = {
                Text(
                    "Location is Off",
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AppColors.Ink),
                )
            },
            text = {
                Text(
                    "Enable location for Local in Settings to use your current location. Otherwise we'll keep using the address you entered.",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AppColors.Ink2, lineHeight = 20.sp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showEnableDialog = false
                    awaitingSettingsReturn = true
                    openAppSettings()
                }) {
                    Text(
                        "Open Settings",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = AppColors.Coral),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showEnableDialog = false }) {
                    Text(
                        "Cancel",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AppColors.Ink3),
                    )
                }
            },
            containerColor = AppColors.Snow,
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppColors.Snow,
        contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets.ime },
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .background(AppColors.Pebble, RoundedCornerShape(2.dp)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                "Location Settings",
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = AppColors.Ink),
            )

            if (isUnsupportedState) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.SunLt, RoundedCornerShape(12.dp))
                        .border(1.dp, AppColors.Sun, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = AppColors.SunDk,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "You're in a state that is not yet supported by Local. You will likely not see very many events.",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.SunDk, lineHeight = 18.sp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Current Location option ──
            val isCurrentSelected = locationMode == LocationMode.CURRENT_LOCATION
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isCurrentSelected) AppColors.SkyLt else AppColors.Snow)
                    .border(1.5.dp, if (isCurrentSelected) AppColors.Sky else AppColors.Pebble, RoundedCornerShape(14.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        // Always request GPS — will prompt for permission if needed
                        requestingGps = true
                        onSelectCurrentLocation()
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isCurrentSelected) AppColors.Sky else AppColors.Ink3,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Current Location",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = AppColors.Ink),
                    )
                    // Prefer the reverse-geocoded GPS city; fall back to generic
                    // text while it's being fetched or if reverse geocode fails.
                    // Avoid echoing the manual-address name here — that was
                    // misleading when Current was active.
                    val subtitle = when {
                        isCurrentSelected && hasLocationPermission && gpsLocationName.isNotBlank() -> gpsLocationName
                        isCurrentSelected && hasLocationPermission -> "Using device GPS"
                        !hasLocationPermission -> "Tap to enable location"
                        else -> ""
                    }
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
                        )
                    }
                }
                if (isCurrentSelected && hasLocationPermission) {
                    Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(20.dp), tint = AppColors.Sky)
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Manual Address option ──
            val isManualSelected = locationMode == LocationMode.MANUAL
            ManualAddressOption(
                isSelected = isManualSelected,
                manualLocationName = manualLocationName,
                onSelect = onSelectManualLocation,
            )

            Spacer(Modifier.height(20.dp))

            // ── Radius ──
            Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.Pebble))
            Spacer(Modifier.height(16.dp))

            Text(
                "RADIUS",
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = AppColors.Ink3, letterSpacing = 1.sp),
            )
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf<Double?>(null, 10.0, 25.0, 50.0, 100.0).forEach { option ->
                    val isSelected = option == radiusFilter
                    val label = if (option == null) "Any" else "${option.toInt()} mi"
                    Box(
                        modifier = Modifier
                            .background(
                                if (isSelected) AppColors.Sky else AppColors.SkyLt,
                                RoundedCornerShape(50),
                            )
                            .border(1.5.dp, AppColors.Sky, RoundedCornerShape(50))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onRadiusChanged(option) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            label,
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                color = if (isSelected) Color.White else AppColors.Sky,
                            ),
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Done button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.Ink)
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Done",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color.White),
                )
            }
        }
    }
}

@Composable
private fun ManualAddressOption(
    isSelected: Boolean,
    manualLocationName: String,
    onSelect: (lat: Double, lng: Double, name: String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var displayAddress by remember { mutableStateOf(manualLocationName) }
    var suggestions by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }
    var isEditing by remember { mutableStateOf(false) }

    // The prop can change after first composition (e.g. cache/network hydrate
    // arrives after the sheet was opened). `remember` would otherwise keep
    // the original — usually empty — value and the subtitle would never
    // appear under "Manual Address". Don't clobber the user's in-progress edit.
    LaunchedEffect(manualLocationName) {
        if (!isEditing) displayAddress = manualLocationName
    }
    var resolveError by remember { mutableStateOf<String?>(null) }
    var isResolving by remember { mutableStateOf(false) }
    val httpClient = remember { HttpClient() }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    // Debounced autocomplete
    LaunchedEffect(query) {
        if (query.length < 3 || !isEditing) {
            if (query.length < 3) suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        try {
            val response = httpClient.post("${Config.SUPABASE_URL}/functions/v1/geocode") {
                header("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
                contentType(ContentType.Application.Json)
                val escaped = query.replace("\\", "\\\\").replace("\"", "\\\"")
                setBody("""{"query":"$escaped"}""")
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            val items = json["suggestions"]?.jsonArray ?: return@LaunchedEffect
            suggestions = items.mapNotNull { item ->
                val obj = item.jsonObject
                PlaceSuggestion(
                    placeId = obj["placeId"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    mainText = obj["mainText"]?.jsonPrimitive?.content ?: "",
                    secondaryText = obj["secondaryText"]?.jsonPrimitive?.content ?: "",
                )
            }
        } catch (_: Exception) {
            suggestions = emptyList()
        }
    }

    // Auto-focus when editing starts
    LaunchedEffect(isEditing) {
        if (isEditing) {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) AppColors.SkyLt else AppColors.Snow)
            .border(1.5.dp, if (isSelected) AppColors.Sky else AppColors.Pebble, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (!isEditing) {
                    isEditing = true
                    query = ""
                }
            }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isSelected) AppColors.Sky else AppColors.Ink3,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Manual Address",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = AppColors.Ink),
                )
                if (displayAddress.isNotBlank() && !isEditing) {
                    Text(
                        displayAddress,
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
                    )
                }
            }
            if (isSelected && !isEditing) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(20.dp), tint = AppColors.Sky)
            }
        }

        // Search field — only visible when actively editing
        if (isEditing) {
            Spacer(Modifier.height(10.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AppColors.Ink),
                modifier = Modifier.focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(10.dp))
                            .border(1.dp, AppColors.Pebble, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                "Search city or address...",
                                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AppColors.Ink3.copy(alpha = 0.5f)),
                            )
                        }
                        inner()
                    }
                },
            )

            if (resolveError != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    resolveError ?: "",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = AppColors.Coral,
                    ),
                )
            } else if (isResolving) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Looking up address…",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = AppColors.Ink3,
                    ),
                )
            }

            // Suggestions
            suggestions.forEach { suggestion ->
                val fullAddress = if (suggestion.secondaryText.isNotBlank()) "${suggestion.mainText}, ${suggestion.secondaryText}" else suggestion.mainText
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            query = fullAddress
                            displayAddress = fullAddress
                            suggestions = emptyList()
                            isEditing = false
                            resolveError = null
                            isResolving = true
                            resolvePlace(
                                httpClient = httpClient,
                                placeId = suggestion.placeId,
                                onResult = { lat, lng, city ->
                                    isResolving = false
                                    // Use city for the pill display, not full street address
                                    val pillName = city.ifBlank { suggestion.mainText }
                                    onSelect(lat, lng, pillName)
                                },
                                onFailure = {
                                    isResolving = false
                                    // Don't silently commit a name without coords — surface
                                    // the error and keep the user's previous selection intact.
                                    resolveError = "Couldn't resolve that address. Please try another."
                                    isEditing = true
                                },
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp), tint = AppColors.Ink3)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(suggestion.mainText, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.Ink))
                        if (suggestion.secondaryText.isNotBlank()) {
                            Text(suggestion.secondaryText, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3))
                        }
                    }
                }
            }
        }
    }
}

private fun resolvePlace(
    httpClient: HttpClient,
    placeId: String,
    onResult: (Double, Double, String) -> Unit,
    onFailure: () -> Unit,
) {
    kotlinx.coroutines.MainScope().launch {
        try {
            val response = httpClient.post("${Config.SUPABASE_URL}/functions/v1/geocode") {
                header("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
                contentType(ContentType.Application.Json)
                setBody("""{"placeId":"$placeId"}""")
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            val lat = json["lat"]?.jsonPrimitive?.content?.toDoubleOrNull()
            val lng = json["lng"]?.jsonPrimitive?.content?.toDoubleOrNull()
            if (lat == null || lng == null) {
                onFailure()
                return@launch
            }
            val city = json["city"]?.jsonPrimitive?.content ?: ""
            onResult(lat, lng, city)
        } catch (_: Exception) {
            onFailure()
        }
    }
}
