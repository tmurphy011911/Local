package live.localapp.local.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Place
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import live.localapp.local.domain.model.Event
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts
import live.localapp.local.ui.theme.AppShapes
import live.localapp.local.ui.theme.AppType
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun EventDetailScreen(
    event: Event,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var isFavorited by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Snow),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // Hero image
            HeroImage(
                event = event,
                isFavorited = isFavorited,
                onBack = onBack,
                onFavoriteToggle = { isFavorited = !isFavorited },
            )

            // Content
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                // Title
                Text(
                    event.title,
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        color = AppColors.Ink,
                        lineHeight = 30.sp,
                    ),
                )

                Spacer(Modifier.height(16.dp))

                // Date/time
                if (event.startAt != null) {
                    InfoRow(
                        icon = Icons.Outlined.CalendarMonth,
                        iconTint = AppColors.Coral,
                        text = formatDate(event.startAt),
                    )
                    Spacer(Modifier.height(10.dp))
                }

                // Venue
                if (event.venueName != null) {
                    val venueText = buildString {
                        append(event.venueName)
                        if (event.venueAddress != null) {
                            append(" \u00B7 ${event.venueAddress}")
                        }
                    }
                    val canOpenMap = event.venueLat != null && event.venueLng != null
                    InfoRow(
                        icon = Icons.Outlined.Place,
                        iconTint = AppColors.Sky,
                        text = venueText,
                        isClickable = canOpenMap,
                        onClick = {
                            if (canOpenMap) {
                                val label = event.venueName
                                val url = "https://maps.apple.com/?q=${label}&ll=${event.venueLat},${event.venueLng}"
                                uriHandler.openUri(url)
                            }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                }

                // Price
                if (event.priceDisplay.isNotEmpty()) {
                    InfoRow(
                        icon = Icons.Outlined.AccessTime,
                        iconTint = if (event.isFree) AppColors.Sage else AppColors.Ink2,
                        text = event.priceDisplay,
                        textColor = if (event.isFree) AppColors.Sage else AppColors.Ink,
                    )
                    Spacer(Modifier.height(16.dp))
                }

                // Description
                if (!event.description.isNullOrBlank()) {
                    Text(
                        "Description from ${descriptionSource(event)}",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp,
                            color = AppColors.Ink3,
                            letterSpacing = 0.3.sp,
                        ),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        event.description,
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = AppColors.Ink2,
                            lineHeight = 22.sp,
                        ),
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }

        // Bottom ticket button
        if (event.ticketUrl != null) {
            TicketButton(
                isFree = event.isFree,
                onClick = { uriHandler.openUri(event.ticketUrl) },
            )
        }
    }
}

// ── Hero image with gradient overlay ──

@Composable
private fun HeroImage(
    event: Event,
    isFavorited: Boolean,
    onBack: () -> Unit,
    onFavoriteToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp),
    ) {
        // Image or placeholder
        if (event.imageUrl != null) {
            AsyncImage(
                model = event.imageUrl,
                contentDescription = event.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(AppColors.BgCoralStart, AppColors.BgCoralEnd),
                        ),
                    ),
            )
        }

        // Bottom gradient overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, AppColors.Snow),
                    ),
                ),
        )

        // Category pill
        Text(
            event.category,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, bottom = 16.dp)
                .background(AppColors.White, AppShapes.Pill)
                .border(1.5.dp, AppColors.Pebble, AppShapes.Pill)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            style = AppType.LabelSmall.copy(color = AppColors.Ink2),
        )

        // Back button
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 12.dp)
                .size(36.dp)
                .background(AppColors.White.copy(alpha = 0.9f), CircleShape)
                .border(1.5.dp, AppColors.Pebble, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBack,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                modifier = Modifier.size(18.dp),
                tint = AppColors.Ink,
            )
        }

        // Favorite button
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 16.dp, top = 12.dp)
                .size(36.dp)
                .background(AppColors.White.copy(alpha = 0.9f), CircleShape)
                .border(1.5.dp, AppColors.Pebble, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onFavoriteToggle,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isFavorited) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = "Favorite",
                modifier = Modifier.size(18.dp),
                tint = if (isFavorited) AppColors.Coral else AppColors.Ink3,
            )
        }
    }
}

// ── Info row with icon ──

@Composable
private fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    text: String,
    textColor: Color = AppColors.Ink2,
    isClickable: Boolean = false,
    onClick: () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = if (isClickable) {
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
        } else {
            Modifier
        },
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(AppColors.Mist, AppShapes.IconBox)
                .border(1.5.dp, AppColors.Pebble, AppShapes.IconBox),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = iconTint)
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = textColor,
                    lineHeight = 18.sp,
                ),
            )
            if (isClickable) {
                Text(
                    "Open in Maps",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        color = AppColors.Sky,
                    ),
                )
            }
        }
    }
}

// ── Ticket / RSVP button ──

@Composable
private fun TicketButton(isFree: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val offsetY by animateFloatAsState(
        targetValue = if (isPressed) 4f else 0f,
        animationSpec = tween(80),
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.White)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationY = offsetY * density },
        ) {
            // Shadow
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(y = if (!isPressed) 4.dp else 1.dp)
                    .background(AppColors.CoralDk, AppShapes.CardSmall),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.Coral, AppShapes.CardSmall)
                    .border(2.dp, AppColors.CoralDk, AppShapes.CardSmall)
                    .clip(AppShapes.CardSmall)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (isFree) "RSVP" else "Get Tickets",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        color = AppColors.White,
                    ),
                )
            }
        }
    }
}

// ── Description source label ──

private fun descriptionSource(event: Event): String {
    val src = event.source?.lowercase() ?: return event.venueName ?: "Venue"
    return when {
        src == "ticketmaster" -> "Ticketmaster"
        // Crawled events: use the venue name as the source
        event.venueName != null -> event.venueName
        else -> src.replaceFirstChar { it.uppercase() }
    }
}

// ── Date formatter ──

private fun formatDate(isoDate: String): String {
    return try {
        // Parse ISO 8601: "2026-03-15T19:00:00"
        val parts = isoDate.split("T")
        if (parts.size < 2) return isoDate
        val dateParts = parts[0].split("-")
        if (dateParts.size < 3) return isoDate
        val months = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        )
        val month = months.getOrNull((dateParts[1].toIntOrNull() ?: 1) - 1) ?: ""
        val day = dateParts[2].toIntOrNull() ?: 0
        val year = dateParts[0]
        val timeParts = parts[1].split(":")
        val hour = timeParts[0].toIntOrNull() ?: 0
        val minute = timeParts.getOrNull(1) ?: "00"
        val amPm = if (hour < 12) "AM" else "PM"
        val hour12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        "$month $day, $year \u00B7 $hour12:$minute $amPm"
    } catch (_: Exception) {
        isoDate
    }
}
