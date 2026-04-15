package live.localapp.local.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Place
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import live.localapp.local.Config
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

private data class PlaceSuggestion(
    val placeId: String,
    val mainText: String,
    val secondaryText: String,
)

@Composable
fun ManualAddressScreen(
    onSubmit: (lat: Double, lng: Double, city: String) -> Unit,
    onBack: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }
    var selectedPlace by remember { mutableStateOf<PlaceSuggestion?>(null) }
    var isResolving by remember { mutableStateOf(false) }
    val httpClient = remember { HttpClient() }

    // Debounced autocomplete
    LaunchedEffect(query) {
        if (query.length < 3 || selectedPlace != null) {
            if (query.length < 3) suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(350) // debounce
        try {
            val response = httpClient.post("${Config.SUPABASE_URL}/functions/v1/geocode") {
                header("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
                contentType(ContentType.Application.Json)
                val escaped = query.replace("\\", "\\\\").replace("\"", "\\\"")
                setBody("""{"query":"$escaped"}""")
            }
            println("geocode autocomplete: status=${response.status} body=${response.bodyAsText().take(300)}")
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

    // Pre-resolve selected place to lat/lng (ready for when user taps CTA)
    var resolvedLat by remember { mutableStateOf<Double?>(null) }
    var resolvedLng by remember { mutableStateOf<Double?>(null) }
    var resolvedCity by remember { mutableStateOf("") }

    LaunchedEffect(selectedPlace) {
        val place = selectedPlace ?: return@LaunchedEffect
        resolvedLat = null
        resolvedLng = null
        isResolving = true
        try {
            val response = httpClient.post("${Config.SUPABASE_URL}/functions/v1/geocode") {
                header("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
                contentType(ContentType.Application.Json)
                setBody("""{"placeId":"${place.placeId}"}""")
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            resolvedLat = json["lat"]?.jsonPrimitive?.content?.toDoubleOrNull()
            resolvedLng = json["lng"]?.jsonPrimitive?.content?.toDoubleOrNull()
            resolvedCity = json["city"]?.jsonPrimitive?.content ?: ""
        } catch (_: Exception) {
            selectedPlace = null
        }
        isResolving = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFFF0FAFF), Color(0xFFFAF8FC), Color(0xFFFFF5F5)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 20.dp),
        ) {
            // Back
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    "\u2190 Back",
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBack,
                    ),
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp,
                        color = AppColors.Ink3,
                    ),
                )
            }

            val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 28.dp),
            ) {
                // Pin icon with pulse rings (matches location allow screen)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier.size(130.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        listOf(60.dp, 90.dp, 125.dp).forEach { size ->
                            Box(
                                modifier = Modifier
                                    .size(size)
                                    .border(2.dp, AppColors.Sky.copy(alpha = 0.18f), CircleShape),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .rotate(-45f)
                                .background(AppColors.Sky, RoundedCornerShape(50, 50, 50, 0)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Place,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp).rotate(45f),
                                tint = AppColors.White,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    "Enter your",
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Black,
                        fontSize = 28.sp,
                        color = AppColors.Ink,
                        lineHeight = 34.sp,
                    ),
                )
                Text(
                    "home base",
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Black,
                        fontSize = 28.sp,
                        fontStyle = FontStyle.Italic,
                        color = AppColors.Sky,
                        lineHeight = 34.sp,
                    ),
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    "Tell us your address or neighborhood and we'll do the rest.",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = AppColors.Ink2,
                        lineHeight = 22.sp,
                    ),
                )

                Spacer(Modifier.height(20.dp))

                // Search input
                BasicTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        selectedPlace = null // clear selection on edit
                    },
                    textStyle = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = AppColors.Ink,
                    ),
                    cursorBrush = SolidColor(AppColors.Sky),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .background(AppColors.White, RoundedCornerShape(16.dp))
                                .border(2.5.dp, if (query.isNotEmpty()) AppColors.Sky else AppColors.Pebble, RoundedCornerShape(16.dp))
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (query.isEmpty()) {
                                Text(
                                    "Street address, neighborhood or zip",
                                    style = TextStyle(
                                        fontFamily = AppFonts.Body,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp,
                                        color = AppColors.Pebble,
                                    ),
                                )
                            }
                            innerTextField()
                        }
                    },
                    singleLine = true,
                )

                // Autocomplete suggestions
                if (suggestions.isNotEmpty() && selectedPlace == null) {
                    Spacer(Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.White, RoundedCornerShape(14.dp))
                            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(14.dp)),
                    ) {
                        suggestions.forEachIndexed { index, suggestion ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        query = if (suggestion.secondaryText.isNotBlank()) "${suggestion.mainText}, ${suggestion.secondaryText}" else suggestion.mainText
                                        selectedPlace = suggestion
                                        suggestions = emptyList()
                                        focusManager.clearFocus()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        suggestion.mainText,
                                        style = TextStyle(
                                            fontFamily = AppFonts.Body,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = AppColors.Ink,
                                        ),
                                    )
                                    if (suggestion.secondaryText.isNotBlank()) {
                                        Text(
                                            suggestion.secondaryText,
                                            style = TextStyle(
                                                fontFamily = AppFonts.Body,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 11.sp,
                                                color = AppColors.Ink3,
                                            ),
                                        )
                                    }
                                }
                            }
                            if (index < suggestions.lastIndex) {
                                Box(
                                    Modifier.fillMaxWidth().height(1.dp)
                                        .padding(horizontal = 14.dp)
                                        .background(AppColors.Pebble),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Privacy badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.SkyLt, RoundedCornerShape(14.dp))
                        .border(2.dp, AppColors.Sky, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = AppColors.SkyDk,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Your address is only used to generate relevant recommendations for you \u2014 it is never shared, sold, or stored beyond your preferences.",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = AppColors.SkyDk,
                            lineHeight = 18.sp,
                        ),
                    )
                }

                Spacer(Modifier.height(32.dp))

                // CTA — inside scroll so it sits right below content
                val canSubmit = selectedPlace != null && resolvedLat != null && resolvedLng != null && !isResolving
                OnboardingButton(
                    text = if (isResolving) "Locating..." else "Set my location \u2192",
                    color = if (canSubmit) AppColors.Sky else AppColors.Pebble,
                    shadowColor = if (canSubmit) AppColors.SkyDk else AppColors.Pebble,
                    onClick = {
                        if (canSubmit) onSubmit(resolvedLat!!, resolvedLng!!, resolvedCity)
                    },
                    enabled = canSubmit,
                )

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
