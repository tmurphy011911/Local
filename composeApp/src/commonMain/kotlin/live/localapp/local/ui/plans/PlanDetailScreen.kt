package live.localapp.local.ui.plans

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Place
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.model.PlanDay
import live.localapp.local.domain.model.PlanSlot
import live.localapp.local.ui.common.pressable
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

/**
 * Full-screen plan detail page — similar in structure to EventDetailScreen.
 * Used both for viewing own plans and for shared plan deep links.
 */
@Composable
fun PlanDetailScreen(
    plan: Plan,
    isShared: Boolean = false,
    onBack: () -> Unit,
    onShare: () -> Unit = {},
    onSaveToPlan: () -> Unit = {},
) {
    val firstImageUrl = plan.days.flatMap { it.slots }.firstNotNullOfOrNull { it.imageUrl }

    Box(modifier = Modifier.fillMaxSize().background(AppColors.Snow)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Hero section ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Brush.linearGradient(listOf(AppColors.Coral, Color(0xFFFF4F4F)))),
            ) {
                if (firstImageUrl != null) {
                    AsyncImage(
                        model = firstImageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Gradient overlay for text readability
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                                    startY = 80f,
                                ),
                            ),
                    )
                }

                // Header buttons — top row (below status bar)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Back
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp), tint = Color.White)
                    }

                    Spacer(Modifier.weight(1f))

                    // Share
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onShare),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.IosShare, contentDescription = "Share", modifier = Modifier.size(18.dp), tint = Color.White)
                    }
                }

                // Plan info — bottom of hero
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Text(
                        plan.title,
                        style = TextStyle(
                            fontFamily = AppFonts.Heading,
                            fontWeight = FontWeight.Black,
                            fontSize = 26.sp,
                            color = Color.White,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // City
                        Text(
                            plan.city,
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f)),
                        )
                        // Date
                        if (plan.dateDisplay.isNotBlank()) {
                            Box(modifier = Modifier.size(4.dp).background(Color.White.copy(alpha = 0.5f), CircleShape))
                            Text(
                                plan.dateDisplay,
                                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f)),
                            )
                        }
                        // Stop count
                        val totalStops = plan.days.sumOf { it.slots.size }
                        Box(modifier = Modifier.size(4.dp).background(Color.White.copy(alpha = 0.5f), CircleShape))
                        Text(
                            "$totalStops ${if (totalStops == 1) "stop" else "stops"}",
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f)),
                        )
                    }

                    // Attendees
                    if (plan.attendees.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        val confirmed = plan.attendees.count { it.confirmed }
                        Row(
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Outlined.People, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.White)
                            Text(
                                "$confirmed/${plan.attendees.size} confirmed",
                                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = Color.White),
                            )
                        }
                    }
                }
            }

            // ── Day-by-day content ──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = if (isShared) 100.dp else 80.dp),
            ) {
                if (plan.days.size <= 1) {
                    plan.days.firstOrNull()?.let { SingleDayContent(it) }
                } else {
                    MultiDayContent(plan.days)
                }
            }
        }

        // ── Save CTA for shared plans ──
        if (isShared) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, AppColors.Snow, AppColors.Snow),
                        ),
                    )
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AppColors.Coral)
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
    }
}

// ═══════════════════════════════════════════════════════════════════
// DAY SECTION — header + horizontally scrollable slot cards
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun DayHeader(day: PlanDay) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp).background(AppColors.Coral, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(
            day.label.replace("Today ", "").replace("Tomorrow ", "").uppercase(),
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = AppColors.Ink, letterSpacing = 1.sp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${day.slots.size} ${if (day.slots.size == 1) "stop" else "stops"}",
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
        )
    }
}

/** Multi-day: each day has a day header + horizontal carousel of cards */
@Composable
private fun MultiDayContent(days: List<PlanDay>) {
    days.forEachIndexed { dayIndex, day ->
        DayHeader(day)

        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(day.slots, key = { it.id }) { slot ->
                SlotCard(slot = slot, modifier = Modifier.width(200.dp).height(CARD_HEIGHT))
            }
        }

        if (dayIndex < days.lastIndex) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(1.dp)
                    .background(AppColors.Mist),
            )
        }
    }
}

/** Single-day: slots listed vertically, full width */
@Composable
private fun SingleDayContent(day: PlanDay) {
    Spacer(Modifier.height(16.dp))
    day.slots.forEachIndexed { index, slot ->
        SlotCard(
            slot = slot,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        if (index < day.slots.lastIndex) {
            Spacer(Modifier.height(12.dp))
        }
    }
}

private val CARD_HEIGHT = 260.dp

// ═══════════════════════════════════════════════════════════════════
// SLOT CARD — individual stop within a day
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun SlotCard(slot: PlanSlot, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val cardShape = RoundedCornerShape(16.dp)
    var showDirectionsSheet by remember { mutableStateOf(false) }

    // Directions bottom sheet
    if (showDirectionsSheet && slot.venueLat != null && slot.venueLng != null) {
        DirectionsSheet(
            lat = slot.venueLat,
            lng = slot.venueLng,
            name = slot.name,
            onDismiss = { showDirectionsSheet = false },
        )
    }

    Column(
        modifier = modifier
            .clip(cardShape)
            .background(Color.White, cardShape)
            .border(1.5.dp, AppColors.Pebble, cardShape),
    ) {
        // Venue image — fixed height for consistent cards
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .background(
                    Brush.linearGradient(listOf(AppColors.Coral.copy(alpha = 0.3f), AppColors.Coral.copy(alpha = 0.1f))),
                )
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
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
                Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(32.dp), tint = AppColors.Coral.copy(alpha = 0.4f))
            }
        }

        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            // Time badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(AppColors.CoralLt, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        slot.time,
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = AppColors.Coral),
                    )
                }
                if (slot.isFree) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFEDFAF5), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    ) {
                        Text("Free", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = AppColors.Sage))
                    }
                } else if (slot.price.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        slot.price,
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Name
            Text(
                if (slot.emoji.isNotBlank()) "${slot.emoji} ${slot.name}" else slot.name,
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Detail / venue address
            if (slot.detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    slot.detail,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Rating
            if (slot.venueRating != null) {
                Spacer(Modifier.height(4.dp))
                val fullStars = slot.venueRating.toInt()
                val hasHalf = (slot.venueRating - fullStars) >= 0.3
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        buildString {
                            repeat(fullStars) { append("\u2605") }
                            if (hasHalf) append("\u2606")
                        },
                        style = TextStyle(fontSize = 11.sp, color = AppColors.Sun),
                    )
                    Text(
                        "${slot.venueRating}",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = AppColors.Ink),
                    )
                }
            }

        }

        Spacer(Modifier.weight(1f))

        // ── CTA buttons — always two, appended to bottom of card ──
        val hasLocation = slot.venueLat != null && slot.venueLng != null
        // Determine secondary CTA by priority:
        // Restaurants/bars → always "Reserve" (reservation URL > venue website)
        // Events with tickets → "Tickets"
        // Events with source URL → "Info"
        // Else → "Website" if venue website exists
        // Detect if this is a place that needs reservations (restaurants, bars, cafes, etc.)
        val reservationCategories = listOf("restaurant", "bar", "brewery", "winery", "cafe", "bistro", "pub", "tavern", "grill", "steakhouse", "pizzeria", "bakery", "diner")
        val cat = slot.venueCategory?.lowercase() ?: ""
        val needsReservation = slot.reservationUrl != null || reservationCategories.any { cat.contains(it) }

        val resolvedReserveUrl = slot.reservationUrl?.let { url ->
            val today = kotlin.time.Clock.System.now().toString().take(10)
            if (url.contains("opentable")) "${url}?dateTime=${today}T19:00&covers=2&partySize=2" else url
        } ?: slot.venueWebsite
           ?: "https://www.google.com/search?q=${slot.name.replace(" ", "+")}+reservations"

        val secondaryCta: Pair<String, String?> = when {
            needsReservation -> "Reserve" to resolvedReserveUrl
            slot.ticketUrl != null -> "Tickets" to slot.ticketUrl
            slot.sourceUrl != null -> "Info" to slot.sourceUrl
            slot.venueWebsite != null -> "Website" to slot.venueWebsite
            else -> "Info" to "https://www.google.com/search?q=${slot.name.replace(" ", "+")}"
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // Primary: Directions
            if (hasLocation) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(AppColors.Ink)
                        .clickable { showDirectionsSheet = true }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                        Text("Directions", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = Color.White))
                    }
                }
            }
            // Secondary CTA
            run {
                val (label, url) = secondaryCta
                val isReserve = label == "Reserve"
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(if (isReserve) AppColors.Coral else AppColors.Snow)
                        .clickable { if (url != null) uriHandler.openUri(url) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            color = if (isReserve) Color.White else AppColors.Ink,
                        ),
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// DIRECTIONS SHEET — Apple Maps, Google Maps, Lyft, Uber
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectionsSheet(
    lat: Double,
    lng: Double,
    name: String,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val encodedName = name.replace(" ", "+")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .background(AppColors.Pebble, RoundedCornerShape(2.dp)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                "Get Directions",
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AppColors.Ink),
            )
            Spacer(Modifier.height(16.dp))

            DirectionsOption("Apple Maps") {
                uriHandler.openUri("https://maps.apple.com/?daddr=$lat,$lng&q=$encodedName")
                onDismiss()
            }
            DirectionsOption("Google Maps") {
                uriHandler.openUri("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng")
                onDismiss()
            }
            DirectionsOption("Uber") {
                uriHandler.openUri("uber://?action=setPickup&dropoff[latitude]=$lat&dropoff[longitude]=$lng&dropoff[nickname]=$encodedName")
                onDismiss()
            }
            DirectionsOption("Lyft") {
                uriHandler.openUri("lyft://ridetype?id=lyft&destination[latitude]=$lat&destination[longitude]=$lng")
                onDismiss()
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DirectionsOption(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Text(
            label,
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = AppColors.Ink),
        )
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.Mist))
}
