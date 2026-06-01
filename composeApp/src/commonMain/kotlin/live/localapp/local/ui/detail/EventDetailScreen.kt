package live.localapp.local.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Place
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import kotlinx.datetime.toLocalDateTime
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.repository.VenueRepository
import live.localapp.local.domain.repository.formatVenueEventCount
import live.localapp.local.snakeToTitleCase
import live.localapp.local.ui.theme.AppColors
import org.koin.mp.KoinPlatform
import live.localapp.local.ui.theme.AppFonts
import live.localapp.local.ui.theme.AppShapes
import live.localapp.local.ui.theme.AppType

// ── Countdown model ──────────────────────────────────────────────────────────

private data class Countdown(
    val days: Long = 0,
    val hours: Long = 0,
    val minutes: Long = 0,
    val seconds: Long = 0,
    val isLive: Boolean = false,
    val isPast: Boolean = false,
    val hasDate: Boolean = false,
)

private fun computeCountdown(startAt: String?): Countdown {
    if (startAt == null) return Countdown(hasDate = false)
    return try {
        val eventInstant = Instant.parse(startAt)
        val now = kotlin.time.Clock.System.now()
        val totalSeconds = (eventInstant - now).inWholeSeconds
        when {
            totalSeconds in -14400..0 -> Countdown(isLive = true, hasDate = true)
            totalSeconds < -14400     -> Countdown(isPast = true, hasDate = true)
            else -> {
                val d = totalSeconds / 86400
                val h = (totalSeconds % 86400) / 3600
                val m = (totalSeconds % 3600) / 60
                val s = totalSeconds % 60
                Countdown(d, h, m, s, hasDate = true)
            }
        }
    } catch (_: Exception) { Countdown(hasDate = false) }
}

// ── Screen ───────────────────────────────────────────────────────────────────

@Composable
fun EventDetailScreen(
    event: Event,
    onBack: () -> Unit,
    onShareTracked: ((String) -> Unit)? = null,
    onFavoriteTracked: ((String) -> Unit)? = null,
    initialFavorited: Boolean = false,
    sharedMode: Boolean = false,
    onSaveEvent: (() -> Unit)? = null,
    /** Tapped the venue card → open that venue's detail page. Null = non-tappable. */
    onVenueClick: (() -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current

    // Upcoming-event count for the host venue, shown on the venue card. Events
    // carry no venue FK in the read path (see EventDto), so we resolve by the
    // same name+city pair used elsewhere. Loads async; null until ready.
    var venueEventCount by remember(event.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(event.id, event.venueName) {
        val name = event.venueName
        if (name.isNullOrBlank()) return@LaunchedEffect
        venueEventCount = try {
            val repo = KoinPlatform.getKoin().get<VenueRepository>()
            repo.getUpcomingEventsForVenue(name, event.venueCity, event.venueLat, event.venueLng).size
        } catch (_: Exception) {
            null
        }
    }

    LaunchedEffect(event.id) {
        live.localapp.local.observability.Analytics.track(
            live.localapp.local.observability.AnalyticsEvent.eventViewed(
                eventId = event.id,
                source = if (sharedMode) "share_link" else "feed",
                eventMetro = event.venueCity,
            )
        )
    }

    var isFavorited by remember { mutableStateOf(initialFavorited) }
    var countdown by remember { mutableStateOf(computeCountdown(event.startAt)) }
    var mapsExpanded by remember { mutableStateOf(false) }
    var rideExpanded by remember { mutableStateOf(false) }
    var shareCopied by remember { mutableStateOf(false) }

    // Tick every second
    LaunchedEffect(event.startAt) {
        while (true) {
            delay(1000)
            countdown = computeCountdown(event.startAt)
        }
    }

    // Reset "Copied!" after 2 seconds
    LaunchedEffect(shareCopied) {
        if (shareCopied) {
            delay(2000)
            shareCopied = false
        }
    }

    val ctaUrl = event.ticketUrl ?: event.sourceUrl
    val hasLocation = event.venueLat != null && event.venueLng != null

    // Enter animation — approximates the iOS card-to-fullscreen morph
    // (EventDetailTransitionController.swift). Scale 0.92 -> 1, alpha 0 -> 1,
    // corner radius 18 -> 0, all driven by a spring. No exit animation —
    // dismissal is handled by the parent AnimatedVisibility in AppNavigation.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val enterScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.92f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "enterScale",
    )
    val enterAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "enterAlpha",
    )
    val enterCorner by animateDpAsState(
        targetValue = if (visible) 0.dp else 18.dp,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "enterCorner",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = enterScale
                scaleY = enterScale
                alpha = enterAlpha
                clip = true
                shape = RoundedCornerShape(enterCorner)
            }
            .background(AppColors.Snow),
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            HeroImage(
                event = event,
                isFavorited = isFavorited,
                onBack = onBack,
                onFavoriteToggle = {
                    val wasFavorited = isFavorited
                    isFavorited = !isFavorited
                    if (!wasFavorited) {
                        // Persistence + analytics both happen in the VM's
                        // toggleEventFavorite (wired via onFavoriteTracked).
                        onFavoriteTracked?.invoke(event.id)
                    }
                },
            )

            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
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

                Spacer(Modifier.height(14.dp))

                // Recurring indicator — shown instead of countdown/date for recurring events
                val recurrenceLabel = detectRecurrence(event)
                val isRecurring = recurrenceLabel != null

                // Countdown — only for non-recurring events with a known time.
                // TBD-time events have a placeholder noon-UTC start_at; ticking
                // down to it would be misleading.
                if (countdown.hasDate && !isRecurring && event.timeKnown) {
                    CountdownBanner(countdown)
                    Spacer(Modifier.height(16.dp))
                }

                if (recurrenceLabel != null) {
                    Row(
                        modifier = Modifier
                            .background(AppColors.SkyLt, RoundedCornerShape(8.dp))
                            .border(1.5.dp, AppColors.Sky, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = AppColors.Sky)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            recurrenceLabel,
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                color = AppColors.Sky,
                            ),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // Date — skip for recurring events
                if (event.startAt != null && !isRecurring) {
                    InfoRow(
                        icon = Icons.Outlined.CalendarMonth,
                        iconTint = AppColors.Coral,
                        text = formatDate(event.startAt),
                    )
                    Spacer(Modifier.height(10.dp))
                }

                // Venue \u2014 tappable card that routes to the venue's detail page.
                // Secondary line surfaces city \u00B7 distance \u00B7 # events at the venue.
                if (event.venueName != null) {
                    VenueCard(
                        venueName = event.venueName,
                        city = event.displayCity,
                        distanceDisplay = event.distanceDisplay,
                        eventCount = venueEventCount,
                        onClick = onVenueClick,
                    )
                    Spacer(Modifier.height(10.dp))
                }

                // Price
                if (event.priceDisplay.isNotEmpty()) {
                    InfoRow(
                        icon = Icons.Outlined.AttachMoney,
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

                if (!event.venueDescription.isNullOrBlank() && event.venueName != null) {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "About ${event.venueName}",
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
                        event.venueDescription,
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

        // ── CTA bar ──────────────────────────────────────────────────────────
        CtaBar(
            event = event,
            ctaUrl = ctaUrl,
            hasLocation = hasLocation,
            mapsExpanded = mapsExpanded,
            rideExpanded = rideExpanded,
            shareCopied = shareCopied,
            onCtaClick = { if (ctaUrl != null) uriHandler.openUri(ctaUrl) },
            onToggleMaps = {
                mapsExpanded = !mapsExpanded
                if (mapsExpanded) rideExpanded = false
            },
            onToggleRide = {
                rideExpanded = !rideExpanded
                if (rideExpanded) mapsExpanded = false
            },
            onOpenAppleMaps = {
                val url = "https://maps.apple.com/?q=${event.venueName}&ll=${event.venueLat},${event.venueLng}"
                uriHandler.openUri(url)
            },
            onOpenGoogleMaps = {
                val url = "https://maps.google.com/?q=${event.venueLat},${event.venueLng}"
                uriHandler.openUri(url)
            },
            onOpenUber = {
                val url = "https://m.uber.com/ul/?action=setPickup&pickup=my_location" +
                    "&dropoff[latitude]=${event.venueLat}&dropoff[longitude]=${event.venueLng}" +
                    "&dropoff[nickname]=${event.venueName}"
                uriHandler.openUri(url)
            },
            onOpenLyft = {
                val url = "https://lyft.com/ride?id=lyft" +
                    "&destination[lat]=${event.venueLat}&destination[lng]=${event.venueLng}"
                uriHandler.openUri(url)
            },
            onShare = {
                live.localapp.local.platform.presentShareSheet(
                    event.title,
                    "https://localapp.live/e/${event.id}",
                )
                onShareTracked?.invoke(event.id)
                live.localapp.local.observability.Analytics.track(
                    live.localapp.local.observability.AnalyticsEvent.eventShareTapped(
                        eventId = event.id,
                        shareChannel = "system_sheet",
                    )
                )
            },
        )

    }

    // "Save Event" CTA for shared deep link mode — overlays at bottom
    if (sharedMode && onSaveEvent != null) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, AppColors.Snow, AppColors.Snow)),
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.Coral)
                    .clickable(onClick = onSaveEvent)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Save Event",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color.White),
                )
            }
        }
    }
    } // end Box
}

// ── Countdown banner ─────────────────────────────────────────────────────────

@Composable
private fun CountdownBanner(countdown: Countdown) {
    val cardShape = RoundedCornerShape(16.dp)
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().matchParentSize().offset(y = 4.dp).background(AppColors.CoralDk, cardShape))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.Coral, cardShape)
                .border(1.5.dp, AppColors.CoralDk, cardShape)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            when {
                countdown.isLive -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(AppColors.White, CircleShape),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Happening now",
                            style = TextStyle(
                                fontFamily = AppFonts.Heading,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = AppColors.White,
                            ),
                        )
                    }
                }
                countdown.isPast -> {
                    Text(
                        "This event has passed",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = AppColors.White.copy(alpha = 0.78f),
                        ),
                    )
                }
                else -> {
                    Column {
                        Text(
                            "STARTS IN",
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.Black,
                                fontSize = 10.sp,
                                color = AppColors.White.copy(alpha = 0.75f),
                                letterSpacing = 1.sp,
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            CountdownUnit(countdown.days, "days")
                            CountdownSep()
                            CountdownUnit(countdown.hours, "hrs")
                            CountdownSep()
                            CountdownUnit(countdown.minutes, "min")
                            CountdownSep()
                            CountdownUnit(countdown.seconds, "sec")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountdownUnit(value: Long, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value.toString().padStart(2, '0'),
            style = TextStyle(
                fontFamily = AppFonts.Heading,
                fontWeight = FontWeight.Black,
                fontSize = 28.sp,
                color = AppColors.White,
                letterSpacing = (-0.5).sp,
            ),
        )
        Text(
            label,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 9.sp,
                color = AppColors.White.copy(alpha = 0.75f),
                letterSpacing = 0.3.sp,
            ),
        )
    }
}

@Composable
private fun CountdownSep() {
    Text(
        "·",
        style = TextStyle(
            fontFamily = AppFonts.Body,
            fontWeight = FontWeight.Black,
            fontSize = 20.sp,
            color = AppColors.White.copy(alpha = 0.6f),
        ),
        modifier = Modifier.padding(bottom = 14.dp),
    )
}

// ── CTA bar ──────────────────────────────────────────────────────────────────

@Composable
private fun CtaBar(
    event: Event,
    ctaUrl: String?,
    hasLocation: Boolean,
    mapsExpanded: Boolean,
    rideExpanded: Boolean,
    shareCopied: Boolean,
    onCtaClick: () -> Unit,
    onToggleMaps: () -> Unit,
    onToggleRide: () -> Unit,
    onOpenAppleMaps: () -> Unit,
    onOpenGoogleMaps: () -> Unit,
    onOpenUber: () -> Unit,
    onOpenLyft: () -> Unit,
    onShare: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.White)
            .border(width = 1.dp, color = AppColors.Mist)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Secondary row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasLocation) {
                SecondaryCtaButton(
                    icon = Icons.Outlined.Place,
                    label = if (mapsExpanded) "Close" else "Directions",
                    isActive = mapsExpanded,
                    modifier = Modifier.weight(1f),
                    onClick = onToggleMaps,
                )
            }
            SecondaryCtaButton(
                icon = Icons.Outlined.IosShare,
                label = "Share",
                isActive = false,
                modifier = Modifier.weight(1f),
                onClick = onShare,
            )
            if (hasLocation) {
                SecondaryCtaButton(
                    icon = Icons.Outlined.DirectionsCar,
                    label = if (rideExpanded) "Close" else "Get a Ride",
                    isActive = rideExpanded,
                    modifier = Modifier.weight(1f),
                    onClick = onToggleRide,
                )
            }
        }

        // Expandable maps options
        AnimatedVisibility(visible = mapsExpanded, enter = expandVertically(), exit = shrinkVertically()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExpandedOption(label = "Apple Maps", modifier = Modifier.weight(1f), onClick = onOpenAppleMaps)
                ExpandedOption(label = "Google Maps", modifier = Modifier.weight(1f), onClick = onOpenGoogleMaps)
            }
        }

        // Expandable ride options
        AnimatedVisibility(visible = rideExpanded, enter = expandVertically(), exit = shrinkVertically()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExpandedOption(label = "Uber", modifier = Modifier.weight(1f), onClick = onOpenUber)
                ExpandedOption(label = "Lyft", modifier = Modifier.weight(1f), onClick = onOpenLyft)
            }
        }

        // Primary CTA
        if (ctaUrl != null) {
            PrimaryCtaButton(
                label = when {
                    event.isFree -> "RSVP"
                    event.ticketUrl != null -> "Get Tickets"
                    else -> "More Info"
                },
                isPrimary = event.ticketUrl != null,
                onClick = onCtaClick,
            )
        }
    }
}

@Composable
private fun PrimaryCtaButton(label: String, isPrimary: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val offsetY by animateFloatAsState(targetValue = if (isPressed) 4f else 0f, animationSpec = tween(80))
    val scale by animateFloatAsState(targetValue = if (isPressed) 0.97f else 1f, animationSpec = tween(80))
    val haptic = LocalHapticFeedback.current

    Box(modifier = Modifier.fillMaxWidth().graphicsLayer { translationY = offsetY * density; scaleX = scale; scaleY = scale }) {
        val bgColor = if (isPrimary) AppColors.Coral else AppColors.Ink2
        val shadowColor = if (isPrimary) AppColors.CoralDk else Color(0xFF0D0B12)
        Box(Modifier.matchParentSize().offset(y = if (!isPressed) 4.dp else 1.dp).background(shadowColor, AppShapes.CardSmall))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor, AppShapes.CardSmall)
                .border(2.dp, shadowColor, AppShapes.CardSmall)
                .clip(AppShapes.CardSmall)
                .clickable(interactionSource = interactionSource, indication = null, onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                })
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color.White),
            )
        }
    }
}

@Composable
private fun SecondaryCtaButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (isPressed) 0.95f else 1f, animationSpec = tween(80))
    val haptic = LocalHapticFeedback.current
    val bgColor = if (isActive) AppColors.CoralLt else AppColors.Snow
    val borderColor = if (isActive) AppColors.Coral else AppColors.Pebble
    val contentColor = if (isActive) AppColors.Coral else AppColors.Ink2

    Column(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(bgColor, RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            })
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = contentColor)
        Text(
            label,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 10.sp,
                color = contentColor,
            ),
        )
    }
}

@Composable
private fun ExpandedOption(label: String, modifier: Modifier, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (isPressed) 0.95f else 1f, animationSpec = tween(80))
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(AppColors.Snow, RoundedCornerShape(10.dp))
            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(10.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            })
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = AppColors.Ink),
        )
    }
}

// ── Hero image ───────────────────────────────────────────────────────────────

@Composable
private fun HeroImage(
    event: Event,
    isFavorited: Boolean,
    onBack: () -> Unit,
    onFavoriteToggle: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().height(280.dp),
    ) {
        val heroUrl = event.imageUrl ?: event.venueImageUrl
        if (heroUrl != null) {
            AsyncImage(
                model = heroUrl,
                contentDescription = event.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(AppColors.BgCoralStart, AppColors.BgCoralEnd)),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null, modifier = Modifier.size(48.dp), tint = AppColors.Ink3.copy(alpha = 0.3f))
            }
        }

        // Bottom fade
        Box(
            modifier = Modifier.fillMaxWidth().height(120.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(colors = listOf(Color.Transparent, AppColors.Snow))),
        )

        // Category pill
        Text(
            (event.subcategory ?: event.category).snakeToTitleCase(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, bottom = 16.dp)
                .background(AppColors.White, AppShapes.Pill)
                .border(1.5.dp, AppColors.Pebble, AppShapes.Pill)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            style = AppType.LabelSmall.copy(color = AppColors.Ink2),
        )

        // Back button
        HeroIconButton(
            modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 12.dp),
            onClick = onBack,
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", Modifier.size(18.dp), tint = AppColors.Ink)
        }

        // Favorite button
        HeroIconButton(
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 12.dp),
            onClick = onFavoriteToggle,
        ) {
            Icon(
                if (isFavorited) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                "Favorite",
                Modifier.size(18.dp),
                tint = if (isFavorited) AppColors.Coral else AppColors.Ink3,
            )
        }
    }
}

@Composable
private fun HeroIconButton(modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val btnScale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = tween(80),
    )
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .size(36.dp)
            .graphicsLayer { scaleX = btnScale; scaleY = btnScale }
            .background(AppColors.White.copy(alpha = 0.9f), CircleShape)
            .border(1.5.dp, AppColors.Pebble, CircleShape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ── Info row ─────────────────────────────────────────────────────────────────

@Composable
private fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    text: String,
    textColor: Color = AppColors.Ink2,
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.size(32.dp).background(AppColors.Mist, AppShapes.IconBox).border(1.5.dp, AppColors.Pebble, AppShapes.IconBox),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = iconTint)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            modifier = Modifier.padding(top = 6.dp),
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = textColor, lineHeight = 18.sp),
        )
    }
}

// ── Venue card ───────────────────────────────────────────────────────────────

/**
 * Venue row in the icon-chip language. Tappable when [onClick] is non-null
 * (shows a chevron) — routes to the venue's own detail page. Secondary line
 * surfaces city · distance · # of upcoming events at the venue.
 */
@Composable
private fun VenueCard(
    venueName: String,
    city: String,
    distanceDisplay: String,
    eventCount: Int?,
    onClick: (() -> Unit)?,
) {
    val secondary = buildList {
        if (city.isNotBlank()) add(city)
        if (distanceDisplay.isNotBlank()) add(distanceDisplay)
        eventCount?.let { formatVenueEventCount(it) }?.let { add(it) }
    }.joinToString(" · ")

    val rowModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(32.dp).background(AppColors.Mist, AppShapes.IconBox).border(1.5.dp, AppColors.Pebble, AppShapes.IconBox),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Place, null, Modifier.size(16.dp), tint = AppColors.Sky)
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                venueName,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.Ink, lineHeight = 18.sp),
            )
            if (secondary.isNotEmpty()) {
                Text(
                    secondary,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3, lineHeight = 16.sp),
                )
            }
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "View venue",
                modifier = Modifier.size(20.dp),
                tint = AppColors.Ink3,
            )
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun descriptionSource(event: Event): String {
    val src = event.source?.lowercase() ?: return event.venueName ?: "Venue"
    return when {
        src == "ticketmaster" -> "Ticketmaster"
        event.venueName != null -> event.venueName
        else -> src.replaceFirstChar { it.uppercase() }
    }
}

/** Detect if an event is recurring based on title, description, and category */
private fun detectRecurrence(event: Event): String? {
    val text = "${event.title} ${event.description ?: ""}".lowercase()
    val isAlwaysOn = event.category == "always_on"

    // Check for specific day patterns
    val dayPatterns = mapOf(
        "sunday" to "Happens Every Sunday",
        "monday" to "Happens Every Monday",
        "tuesday" to "Happens Every Tuesday",
        "wednesday" to "Happens Every Wednesday",
        "thursday" to "Happens Every Thursday",
        "friday" to "Happens Every Friday",
        "saturday" to "Happens Every Saturday",
    )

    // Check for "every [day]" pattern
    for ((day, label) in dayPatterns) {
        if (text.contains("every $day") || text.contains("${day}s") ||
            (text.contains(day) && (text.contains("weekly") || text.contains("every week")))) {
            return label
        }
    }

    // Check for weekly
    if (text.contains("weekly") || text.contains("every week")) return "Happens Weekly"

    // Check for monthly
    if (text.contains("monthly") || text.contains("every month") ||
        text.contains("first friday") || text.contains("first saturday") ||
        text.contains("last friday") || text.contains("second saturday") ||
        text.contains("third thursday")) return "Happens Monthly"

    // Check for daily
    if (text.contains("daily") || text.contains("every day") || text.contains("everyday")) return "Happens Daily"

    // Always-on events without a specific start_at are anytime experiences
    if (isAlwaysOn && event.startAt == null) return "Available Anytime"

    // Always-on with a start_at but no weekly/monthly pattern — likely recurring
    if (isAlwaysOn && event.startAt != null) return "Recurring Event"

    return null
}

private fun formatDate(isoDate: String): String {
    return try {
        // Midnight UTC means "no specific time" — just show the date
        val isMidnightUtc = isoDate.contains("T00:00:00")
        val instant = Instant.parse(isoDate)
        val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
        val local = if (isMidnightUtc) {
            instant.toLocalDateTime(kotlinx.datetime.TimeZone.UTC)
        } else {
            instant.toLocalDateTime(tz)
        }
        val months = listOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
        val month = months.getOrNull(local.monthNumber - 1) ?: ""
        val day = local.dayOfMonth
        val year = local.year
        if (isMidnightUtc) {
            "$month $day, $year"
        } else {
            val hour = local.hour
            val minute = local.minute
            val amPm = if (hour < 12) "AM" else "PM"
            val hour12 = when { hour == 0 -> 12; hour > 12 -> hour - 12; else -> hour }
            val minuteStr = minute.toString().padStart(2, '0')
            "$month $day, $year \u00B7 $hour12:$minuteStr $amPm"
        }
    } catch (_: Exception) { isoDate }
}
