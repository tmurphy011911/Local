package live.localapp.local.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    locationMode: LocationMode,
    manualLocationName: String,
    radiusFilter: Double?,
    hasLocationPermission: Boolean,
    onSelectCurrentLocation: () -> Unit,
    onSelectManualLocation: (lat: Double, lng: Double, name: String) -> Unit,
    onRadiusChanged: (Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppColors.Snow,
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
                        if (hasLocationPermission) onSelectCurrentLocation()
                        else openAppSettings()
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
                    if (hasLocationPermission) {
                        if (isCurrentSelected && currentLocationName.isNotBlank()) {
                            Text(
                                currentLocationName,
                                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
                            )
                        }
                    } else {
                        Text(
                            "Not Enabled \u00B7 Enable in Settings \u2192",
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Coral),
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
                listOf<Double?>(null, 5.0, 10.0, 25.0, 50.0, 100.0).forEach { option ->
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
    var query by remember { mutableStateOf(if (isSelected) manualLocationName else "") }
    var suggestions by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val httpClient = remember { HttpClient() }

    // Debounced autocomplete
    LaunchedEffect(query) {
        if (query.length < 3 || !isSearching) {
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) AppColors.SkyLt else AppColors.Snow)
            .border(1.5.dp, if (isSelected) AppColors.Sky else AppColors.Pebble, RoundedCornerShape(14.dp))
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
                if (isSelected && manualLocationName.isNotBlank() && !isSearching) {
                    Text(
                        manualLocationName,
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
                    )
                }
            }
            if (isSelected) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(20.dp), tint = AppColors.Sky)
            }
        }

        // Search field — show when tapped or already in manual mode
        if (isSelected || suggestions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            BasicTextField(
                value = query,
                onValueChange = {
                    query = it
                    isSearching = true
                },
                singleLine = true,
                textStyle = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AppColors.Ink),
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

            // Suggestions
            suggestions.forEach { suggestion ->
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            // Resolve place to lat/lng
                            query = suggestion.mainText
                            suggestions = emptyList()
                            isSearching = false
                            // Resolve in a coroutine — use LaunchedEffect trick
                            resolvePlace(httpClient, suggestion.placeId) { lat, lng, city ->
                                val name = if (city.isNotBlank()) city else suggestion.mainText
                                onSelect(lat, lng, name)
                            }
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
        } else if (!isSelected) {
            // Tap to activate manual mode
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap to enter a location",
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
                modifier = Modifier.clickable {
                    isSearching = true
                    // This triggers showing the text field on next recomposition by making the parent call
                },
            )
        }
    }
}

private fun resolvePlace(httpClient: HttpClient, placeId: String, onResult: (Double, Double, String) -> Unit) {
    kotlinx.coroutines.MainScope().launch {
        try {
            val response = httpClient.post("${Config.SUPABASE_URL}/functions/v1/geocode") {
                header("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
                contentType(ContentType.Application.Json)
                setBody("""{"placeId":"$placeId"}""")
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            val lat = json["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@launch
            val lng = json["lng"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@launch
            val city = json["city"]?.jsonPrimitive?.content ?: ""
            onResult(lat, lng, city)
        } catch (_: Exception) {}
    }
}
