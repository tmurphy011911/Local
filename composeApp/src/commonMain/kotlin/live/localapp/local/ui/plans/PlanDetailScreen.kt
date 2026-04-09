package live.localapp.local.ui.plans

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
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
import live.localapp.local.domain.model.PlanTravel
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

@Composable
fun PlanDetailScreen(
    plan: Plan,
    isShared: Boolean = false,
    onBack: () -> Unit,
    onShare: () -> Unit = {},
    onSaveToPlan: () -> Unit = {},
    onEditPlan: () -> Unit = {},
    onEditDay: (dayLabel: String) -> Unit = {},
) {
    val firstImageUrl = plan.days.flatMap { it.slots }.firstNotNullOfOrNull { it.imageUrl }

    Box(modifier = Modifier.fillMaxSize().background(AppColors.Snow)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Hero section (200dp) ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Brush.linearGradient(listOf(AppColors.Coral, Color(0xFFFF4F4F)))),
            ) {
                if (firstImageUrl != null) {
                    AsyncImage(
                        model = firstImageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)), startY = 60f),
                        ),
                    )
                }

                // Header buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HeaderButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp), tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    if (!isShared) {
                        HeaderButton(onClick = onEditPlan) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp), tint = Color.White)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    HeaderButton(onClick = onShare) {
                        Icon(Icons.Outlined.IosShare, contentDescription = "Share", modifier = Modifier.size(16.dp), tint = Color.White)
                    }
                }

                // Plan info — bottom
                Column(
                    modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Text(
                        plan.title,
                        style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 24.sp, color = Color.White),
                    )
                    Spacer(Modifier.height(3.dp))
                    val totalStops = plan.days.sumOf { it.slots.size }
                    Text(
                        buildString {
                            append(plan.city)
                            if (plan.dateDisplay.isNotBlank()) append(" \u00B7 ${plan.dateDisplay}")
                            append(" \u00B7 $totalStops ${if (totalStops == 1) "stop" else "stops"}")
                        },
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f)),
                    )
                }
            }

            // ── Day tabs (multi-day only) ──
            var selectedDayIndex by remember { mutableStateOf(0) }

            if (plan.days.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    plan.days.forEachIndexed { index, day ->
                        val isSelected = index == selectedDayIndex
                        val parts = day.label.replace("Today ", "").replace("Tomorrow ", "").split(",").map { it.trim() }
                        val dayName = (parts.firstOrNull() ?: day.shortName).uppercase()
                        val date = parts.getOrNull(1) ?: ""
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) AppColors.Coral else AppColors.Snow)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { selectedDayIndex = index }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(dayName, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = if (isSelected) Color.White else AppColors.Ink3))
                            if (date.isNotBlank()) {
                                Text(date, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, color = if (isSelected) Color.White.copy(alpha = 0.8f) else AppColors.Pebble))
                            }
                        }
                    }
                    // Edit Day button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppColors.Mist)
                            .clickable { onEditDay(plan.days[selectedDayIndex].label) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit Day", modifier = Modifier.size(16.dp), tint = AppColors.Ink3)
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.Mist))
            }

            // ── Content — timeline for selected day ──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = if (isShared) 100.dp else 80.dp),
            ) {
                val dayToShow = if (plan.days.size > 1) {
                    plan.days.getOrNull(selectedDayIndex) ?: plan.days.firstOrNull()
                } else {
                    plan.days.firstOrNull()
                }
                dayToShow?.let { SingleDayContent(it) }
            }
        }

        // ── Save CTA for shared plans ──
        if (isShared) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, AppColors.Snow, AppColors.Snow)))
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
                    Text("Accept Invite", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color.White))
                }
            }
        }
    }
}

@Composable
private fun HeaderButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ═══════════════════════════════════════════════════════════════════
// SINGLE-DAY: Vertical timeline with compact inline-thumbnail rows
// ═══════════════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════════════
// DAY SLOT CARD — full-width card in carousel, dark themed
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun DaySlotCard(slot: PlanSlot, stopNumber: Int, totalStops: Int) {
    val uriHandler = LocalUriHandler.current
    val cardShape = RoundedCornerShape(16.dp)
    var showDirectionsSheet by remember { mutableStateOf(false) }

    if (showDirectionsSheet && slot.venueLat != null && slot.venueLng != null) {
        DirectionsSheet(lat = slot.venueLat, lng = slot.venueLng, name = slot.name, onDismiss = { showDirectionsSheet = false })
    }

    Column(
        modifier = Modifier
            .width(280.dp)
            .clip(cardShape)
            .background(Color(0xFF2A2A3E), cardShape),
    ) {
        // Image
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
        ) {
            if (slot.imageUrl != null) {
                AsyncImage(model = slot.imageUrl, contentDescription = slot.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(listOf(AppColors.Coral.copy(alpha = 0.3f), AppColors.Coral.copy(alpha = 0.1f)))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(32.dp), tint = Color.White.copy(alpha = 0.3f))
                }
            }
        }

        Column(modifier = Modifier.padding(14.dp)) {
            // Time
            Text(
                slot.time,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = AppColors.Coral),
            )
            Spacer(Modifier.height(4.dp))
            // Name
            Text(
                slot.name,
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.White),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            // Detail
            if (slot.detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    slot.detail,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f)),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            // Rating
            if (slot.venueRating != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "\u2605 ${slot.venueRating}",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = AppColors.Sun),
                )
            }
            if (slot.isFree) {
                Spacer(Modifier.height(4.dp))
                Text("Free", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = AppColors.Sage))
            }

            Spacer(Modifier.height(10.dp))

            // CTAs
            SlotCtas(slot, onDirections = { showDirectionsSheet = true })
        }
    }
}

@Composable
private fun SingleDayContent(day: PlanDay) {
    Spacer(Modifier.height(16.dp))
    day.slots.forEachIndexed { index, slot ->
        val isLast = index == day.slots.lastIndex
        val travel = if (!isLast) day.travel.getOrNull(index) else null

        // Each stop: spine on left (dot + line), content on right
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            // Timeline spine — dot at top, line fills remaining height
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(20.dp),
            ) {
                // Dot
                Box(modifier = Modifier.size(10.dp).background(AppColors.Coral, CircleShape))
                // Connecting line — fills to bottom of this row (stretches with content)
                if (!isLast) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(AppColors.Coral.copy(alpha = 0.2f)),
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            // Content column
            Column(modifier = Modifier.weight(1f)) {
                // Time — primary visual
                Text(
                    slot.time,
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppColors.Ink),
                )
                Spacer(Modifier.height(6.dp))
                // Venue row
                TimelineSlotRow(slot)
                // Travel time
                if (travel != null) {
                    Spacer(Modifier.height(10.dp))
                    val icon = if (travel.mode == "walk") "\uD83D\uDEB6" else "\uD83D\uDE97"
                    Text(
                        "$icon ${travel.mins} min ${travel.mode}",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = AppColors.Ink3),
                    )
                }
                // Spacing to next stop
                Spacer(Modifier.height(if (isLast) 0.dp else 20.dp))
            }
        }
    }
}

/** Compact row: 48dp thumbnail + venue info + CTAs */
@Composable
private fun TimelineSlotRow(slot: PlanSlot) {
    val uriHandler = LocalUriHandler.current
    var showDirectionsSheet by remember { mutableStateOf(false) }

    if (showDirectionsSheet && slot.venueLat != null && slot.venueLng != null) {
        DirectionsSheet(lat = slot.venueLat, lng = slot.venueLng, name = slot.name, onDismiss = { showDirectionsSheet = false })
    }

    Row(verticalAlignment = Alignment.Top) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(AppColors.Coral.copy(alpha = 0.2f), AppColors.Coral.copy(alpha = 0.1f)))),
            contentAlignment = Alignment.Center,
        ) {
            if (slot.imageUrl != null) {
                AsyncImage(model = slot.imageUrl, contentDescription = slot.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(20.dp), tint = AppColors.Coral.copy(alpha = 0.4f))
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Display logic: events show title + venue, venues show meal type + venue name
            val isVenue = slot.eventId == null
            val displayName: String
            val displaySubtitle: String

            val restaurantCategories = listOf("restaurant", "bar", "brewery", "winery", "cafe", "bistro", "pub", "tavern", "grill", "steakhouse", "pizzeria", "bakery", "diner")
            val cat = slot.venueCategory?.lowercase() ?: ""
            val nameLower = slot.name.lowercase()
            // Check category OR infer from name (for venues without category data)
            val isRestaurant = isVenue && (
                restaurantCategories.any { cat.contains(it) } ||
                restaurantCategories.any { nameLower.contains(it) }
            )

            if (isRestaurant) {
                // Restaurant/bar/cafe: show meal type + venue name
                displayName = inferMealType(slot.time).ifBlank { slot.name }
                displaySubtitle = buildString {
                    append(slot.name)
                    if (slot.venueRating != null) append(" \u00B7 \u2605 ${slot.venueRating}")
                }
            } else if (isVenue) {
                // Non-restaurant venue: show venue name, detail as subtitle
                displayName = slot.name
                displaySubtitle = buildString {
                    if (slot.detail.isNotBlank()) append(slot.detail)
                    if (slot.venueRating != null) {
                        if (isNotBlank()) append(" \u00B7 ")
                        append("\u2605 ${slot.venueRating}")
                    }
                }
            } else {
                // Event: show clean event title, venue/location as subtitle
                // Strip date suffixes like " - April 13", " – May 10"
                displayName = slot.name
                    .replace(Regex("\\s*[-–]\\s*(January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{1,2}.*$", RegexOption.IGNORE_CASE), "")
                    .trim()
                displaySubtitle = buildString {
                    // Extract venue name from detail (usually "venue description at VenueName")
                    val detail = slot.detail
                    // Try venueAddress first (clean venue name)
                    if (slot.venueAddress != null) {
                        append(slot.venueAddress)
                    } else if (detail.isNotBlank()) {
                        // Extract venue from detail pattern "... at VenueName" or just use first part
                        val atMatch = Regex(" at (.+?)( [-–·]|$)", RegexOption.IGNORE_CASE).find(detail)
                        if (atMatch != null) {
                            append(atMatch.groupValues[1].trim())
                        } else {
                            append(detail)
                        }
                    }
                    if (slot.isFree) {
                        if (isNotBlank()) append(" \u00B7 ")
                        append("Free")
                    } else if (slot.price.isNotBlank()) {
                        if (isNotBlank()) append(" \u00B7 ")
                        append(slot.price)
                    }
                }
            }

            Text(
                displayName,
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (displaySubtitle.isNotBlank()) {
                Text(displaySubtitle, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Spacer(Modifier.height(6.dp))

            // CTAs
            SlotCtas(slot, onDirections = { showDirectionsSheet = true })
        }
    }
}

// Old carousel code removed — multi-day now uses day tabs + SingleDayContent

@Suppress("unused")
@Composable
private fun _OldMultiDayContent(days: List<PlanDay>, planTitle: String, onEditDay: (String) -> Unit) {
    days.forEachIndexed { dayIndex, day ->
        // Day header with Edit Day button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 10.dp)
                .background(AppColors.Mist, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(24.dp).background(AppColors.Coral, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("${dayIndex + 1}", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = Color.White))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                day.label.replace("Today ", "").replace("Tomorrow ", "").uppercase(),
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = AppColors.Ink, letterSpacing = 0.5.sp),
                modifier = Modifier.weight(1f),
            )
            // Edit Day
            Text(
                "Edit",
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onEditDay(day.label) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = AppColors.Coral),
            )
        }

        // Carousel
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val slots = day.slots
            items(slots.size) { index ->
                CarouselSlotCard(slot = slots[index], stopNumber = index + 1)
            }
        }

        if (dayIndex < days.lastIndex) {
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// CAROUSEL CARD — multi-day stops (180x190dp)
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun CarouselSlotCard(slot: PlanSlot, stopNumber: Int) {
    val uriHandler = LocalUriHandler.current
    val cardShape = RoundedCornerShape(14.dp)
    var showDirectionsSheet by remember { mutableStateOf(false) }

    if (showDirectionsSheet && slot.venueLat != null && slot.venueLng != null) {
        DirectionsSheet(lat = slot.venueLat, lng = slot.venueLng, name = slot.name, onDismiss = { showDirectionsSheet = false })
    }

    Column(
        modifier = Modifier
            .width(180.dp)
            .clip(cardShape)
            .background(Color.White, cardShape)
            .border(1.5.dp, AppColors.Pebble, cardShape),
    ) {
        // Image (70dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
                .background(Brush.linearGradient(listOf(AppColors.Coral.copy(alpha = 0.2f), AppColors.Coral.copy(alpha = 0.1f))))
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)),
        ) {
            if (slot.imageUrl != null) {
                AsyncImage(model = slot.imageUrl, contentDescription = slot.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(24.dp), tint = AppColors.Coral.copy(alpha = 0.4f))
                }
            }
            // Stop number badge
            Box(
                modifier = Modifier.padding(6.dp).size(20.dp).background(AppColors.Coral, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$stopNumber", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 9.sp, color = Color.White))
            }
        }

        // Info
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                "${slot.name} \u00B7 ${slot.time}",
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = AppColors.Ink),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (slot.detail.isNotBlank()) {
                Text(slot.detail, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = AppColors.Ink3), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Rating
            if (slot.venueRating != null || slot.isFree || slot.price.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (slot.venueRating != null) {
                        Text("\u2605 ${slot.venueRating}", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = AppColors.Sun))
                    }
                    if (slot.isFree) {
                        if (slot.venueRating != null) Spacer(Modifier.width(6.dp))
                        Text("Free", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = AppColors.Sage))
                    } else if (slot.price.isNotBlank()) {
                        if (slot.venueRating != null) Spacer(Modifier.width(6.dp))
                        Text(slot.price, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = AppColors.Ink3))
                    }
                }
            }
        }

        // CTAs
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SlotCtas(slot, onDirections = { showDirectionsSheet = true }, small = true, modifier = Modifier.weight(1f))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// SHARED CTA BUTTONS — used by both layouts
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun SlotCtas(slot: PlanSlot, onDirections: () -> Unit, small: Boolean = false, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val hasLocation = slot.venueLat != null && slot.venueLng != null
    val fontSize = if (small) 9.sp else 10.sp
    val vPad = if (small) 5.dp else 6.dp

    val reservationCategories = listOf("restaurant", "bar", "brewery", "winery", "cafe", "bistro", "pub", "tavern", "grill", "steakhouse", "pizzeria", "bakery", "diner")
    val cat = slot.venueCategory?.lowercase() ?: ""
    val needsReservation = slot.reservationUrl != null || reservationCategories.any { cat.contains(it) }

    val resolvedReserveUrl = slot.reservationUrl?.let { url ->
        val today = kotlin.time.Clock.System.now().toString().take(10)
        if (url.contains("opentable")) "${url}?dateTime=${today}T19:00&covers=2&partySize=2" else url
    } ?: slot.venueWebsite ?: "https://www.google.com/search?q=${slot.name.replace(" ", "+")}+reservations"

    val secondaryCta: Pair<String, String> = when {
        needsReservation -> "Reserve" to resolvedReserveUrl
        slot.ticketUrl != null -> "Tickets" to slot.ticketUrl
        slot.sourceUrl != null -> "Info" to slot.sourceUrl
        slot.venueWebsite != null -> "Website" to slot.venueWebsite
        else -> "Info" to "https://www.google.com/search?q=${slot.name.replace(" ", "+")}"
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (hasLocation) {
            Text(
                "Directions",
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDirections,
                ),
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = AppColors.Coral),
            )
        }
        run {
            val (label, url) = secondaryCta
            if (hasLocation) {
                Text(" \u00B7 ", style = TextStyle(fontSize = 12.sp, color = AppColors.Pebble))
            }
            Text(
                label,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { uriHandler.openUri(url) },
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = AppColors.Coral),
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// DIRECTIONS SHEET
// ═══════════════════════════════════════════════════════════════════

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
            Box(Modifier.padding(top = 12.dp, bottom = 8.dp).width(40.dp).height(4.dp).background(AppColors.Pebble, RoundedCornerShape(2.dp)))
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text("Get Directions", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AppColors.Ink))
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
                    Text(label, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = AppColors.Ink))
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.Mist))
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
