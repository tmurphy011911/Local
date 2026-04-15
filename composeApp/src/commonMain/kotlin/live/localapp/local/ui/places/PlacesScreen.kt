package live.localapp.local.ui.places

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.datetime.toLocalDateTime
import live.localapp.local.domain.model.Event
import live.localapp.local.snakeToTitleCase
import live.localapp.local.domain.model.Venue
import live.localapp.local.domain.repository.VenueWithMeta
import live.localapp.local.domain.repository.CommunityInterestRepository
import live.localapp.local.domain.repository.InteractionType
import live.localapp.local.ui.detail.EventDetailScreen
import live.localapp.local.ui.home.toCardData
import live.localapp.local.ui.common.AppHeader
import live.localapp.local.ui.theme.AppColors
import org.koin.mp.KoinPlatform
import live.localapp.local.ui.theme.AppFonts
import live.localapp.local.ui.theme.AppType
import live.localapp.local.ui.theme.AppShapes
import live.localapp.local.ui.common.pressable

// ── Category gradient backgrounds ──
private val gradientBar = Brush.linearGradient(listOf(Color(0xFF2D1B4E), Color(0xFF6B3A8A)))
private val gradientFood = Brush.linearGradient(listOf(Color(0xFF8B3A00), Color(0xFFD4722A)))
private val gradientMusic = Brush.linearGradient(listOf(Color(0xFF0A3D62), Color(0xFF1E7AA8)))
private val gradientArt = Brush.linearGradient(listOf(Color(0xFF1A4A2E), Color(0xFF3D9970)))
private val gradientSports = Brush.linearGradient(listOf(Color(0xFF3D0000), Color(0xFFC0392B)))
private val gradientTheatre = Brush.linearGradient(listOf(Color(0xFF2C1654), Color(0xFF8E44AD)))
private val gradientComedy = Brush.linearGradient(listOf(Color(0xFF004D40), Color(0xFF00897B)))
private val gradientDefault = Brush.linearGradient(listOf(Color(0xFF2D2D44), Color(0xFF4A4458)))

private fun gradientForCategory(cat: String?): Brush = when (cat?.lowercase()) {
    "nightlife" -> gradientBar
    "food" -> gradientFood
    "music" -> gradientMusic
    "arts" -> gradientArt
    "sports" -> gradientSports
    "theatre" -> gradientTheatre
    "comedy" -> gradientComedy
    else -> gradientDefault
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(
    viewModel: PlacesViewModel,
    onProfileClick: () -> Unit = {},
    onLocationClick: () -> Unit = {},
    userInitial: String = "",
    onVenueClickWithBounds: ((VenueWithMeta, Rect) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    Box(modifier = Modifier.fillMaxSize().background(AppColors.Snow).clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
    ) { focusManager.clearFocus() }) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header ──
            AppHeader(
                    title = "places",
                    locationName = state.locationName,
                    radiusFilter = state.radiusFilter,
                    streakDays = state.streakDays,
                    userInitial = userInitial,
                    onProfileClick = onProfileClick,
                    onLocationClick = onLocationClick,
                )

            // Search bar — below header divider
            Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                SearchBar(query = state.searchQuery, onQueryChange = { viewModel.setSearchQuery(it) }, locationName = state.locationName)
            }

            // ── Content ──
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.weight(1f),
            ) {
                if (state.isLoading && state.venues.isEmpty()) {
                    PlacesSkeletonLoader()
                } else {
                    VenueGrid(
                        venues = state.filteredVenues,
                        favoriteIds = state.favoriteVenueIds,
                        onVenueClick = { venue, bounds ->
                            if (onVenueClickWithBounds != null) {
                                onVenueClickWithBounds(venue, bounds)
                            } else {
                                viewModel.selectVenue(venue)
                            }
                        },
                        onFavoriteToggle = { venue ->
                            viewModel.toggleVenueFavorite(venue.venue.id, venue.primaryCategory)
                        },
                    )
                }
            }
        }
    }
}

// PlacesHeader removed — now uses shared AppHeader

@Composable
private fun PlacesSkeletonLoader() {
    val shimmerAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
    )
    val c = AppColors.Pebble.copy(alpha = 0.3f + shimmerAlpha * 0.2f)

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(12.dp))
        repeat(6) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(80.dp).background(c, RoundedCornerShape(12.dp)))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.width(160.dp).height(14.dp).background(c, RoundedCornerShape(7.dp)))
                    Box(Modifier.width(120.dp).height(10.dp).background(c, RoundedCornerShape(5.dp)))
                    Box(Modifier.width(80.dp).height(10.dp).background(c, RoundedCornerShape(5.dp)))
                }
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, locationName: String = "") {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val placeholder = if (locationName.isNotBlank()) "Search for places near $locationName" else "Search for places\u2026"
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(
                placeholder,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = AppColors.Ink3),
            )
        },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = AppColors.Ink3) },
        trailingIcon = if (query.isNotBlank()) {
            { Icon(Icons.Outlined.Close, contentDescription = "Clear", modifier = Modifier.size(18.dp).clickable { onQueryChange(""); focusManager.clearFocus() }, tint = AppColors.Ink3) }
        } else null,
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focusManager.clearFocus() }),
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = AppColors.Coral,
        ),
        textStyle = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = AppColors.Ink),
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .shadow(4.dp, RoundedCornerShape(16.dp)),
    )
}

// ═══════════════════════════════════════════════════════════════════
// VENUE GRID
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun VenueGrid(
    venues: List<VenueWithMeta>,
    favoriteIds: Set<String>,
    onVenueClick: (VenueWithMeta, Rect) -> Unit,
    onFavoriteToggle: (VenueWithMeta) -> Unit,
) {
    // Saved venues — filter from current venues list
    val savedVenues = remember(venues, favoriteIds) {
        venues.filter { it.venue.id in favoriteIds }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Saved Venues collapsible carousel
        if (savedVenues.isNotEmpty()) {
            item {
                SavedVenuesCarousel(
                    venues = savedVenues,
                    onVenueClick = onVenueClick,
                    onFavoriteToggle = onFavoriteToggle,
                )
            }
        }

        // All venue cards — slim full-width rows
        items(venues, key = { it.venue.id }) { venueWithMeta ->
            VenueCard(
                vm = venueWithMeta,
                isFavorited = venueWithMeta.venue.id in favoriteIds,
                onClick = { bounds -> onVenueClick(venueWithMeta, bounds) },
                onFavoriteToggle = { onFavoriteToggle(venueWithMeta) },
            )
        }

        // Bottom spacer
        item {
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// SAVED VENUES CAROUSEL — collapsible, same design as home page saved events
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun SavedVenuesCarousel(
    venues: List<VenueWithMeta>,
    onVenueClick: (VenueWithMeta, Rect) -> Unit,
    onFavoriteToggle: (VenueWithMeta) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Header — tappable to collapse/expand
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Saved Places (${venues.size})",
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = AppColors.Coral,
                    letterSpacing = (-0.3).sp,
                ),
            )
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.size(20.dp),
                tint = AppColors.Coral,
            )
        }

        // Cards — collapsible
        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    venues.forEach { venueWithMeta ->
                        SavedVenueAlbumCard(
                            vm = venueWithMeta,
                            onClick = { bounds -> onVenueClick(venueWithMeta, bounds) },
                            onFavoriteToggle = { onFavoriteToggle(venueWithMeta) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SavedVenueAlbumCard(vm: VenueWithMeta, onClick: (Rect) -> Unit, onFavoriteToggle: () -> Unit = {}) {
    val v = vm.venue
    var cardBounds by remember { mutableStateOf(Rect.Zero) }
    val cardShape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier
            .width(156.dp)
            .height(190.dp)
            .onGloballyPositioned { cardBounds = it.boundsInRoot() }
            .clip(cardShape)
            .pressable(onClick = { onClick(cardBounds) }),
    ) {
        // Full-card image or gradient background
        if (v.photoUrl != null || v.imageUrl != null) {
            AsyncImage(
                model = v.photoUrl ?: v.imageUrl,
                contentDescription = v.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(gradientForCategory(vm.primaryCategory)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(32.dp), tint = Color.White.copy(alpha = 0.5f))
            }
        }

        // Dark gradient overlay
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)), startY = 60f),
            ),
        )

        // Event count badge — top left
        if (vm.upcomingEventCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(AppColors.Coral, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    "${vm.upcomingEventCount} events",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 9.sp, color = Color.White),
                )
            }
        }

        // Heart — top right
        Icon(
            Icons.Filled.Favorite,
            contentDescription = "Remove",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(20.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onFavoriteToggle,
                ),
            tint = AppColors.Coral,
        )

        // Name only — bottom
        Text(
            v.name,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
            style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// VENUE CARDS
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun VenueCard(vm: VenueWithMeta, isFavorited: Boolean, onClick: (Rect) -> Unit, onFavoriteToggle: () -> Unit) {
    val v = vm.venue
    var cardBounds by remember { mutableStateOf(Rect.Zero) }
    Box(modifier = Modifier.fillMaxWidth()
        .onGloballyPositioned { cardBounds = it.boundsInRoot() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .shadow(2.dp, RoundedCornerShape(14.dp))
                .background(Color.White, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .pressable(onClick = { onClick(cardBounds) }),
        ) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .fillMaxHeight()
                    .background(gradientForCategory(vm.primaryCategory)),
                contentAlignment = Alignment.Center,
            ) {
                if (v.photoUrl != null || v.imageUrl != null) {
                    AsyncImage(
                        model = v.photoUrl ?: v.imageUrl,
                        contentDescription = v.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(28.dp), tint = Color.White.copy(alpha = 0.5f))
                }
            }

            // Body
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    v.name,
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AppColors.Ink, lineHeight = 18.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                VenueMeta(vm)
                Spacer(Modifier.height(3.dp))
                RatingRow(v)
            }

            // Heart button — top right
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(end = 10.dp, top = 10.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Icon(
                    if (isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (isFavorited) "Remove favorite" else "Favorite",
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onFavoriteToggle,
                        ),
                    tint = if (isFavorited) AppColors.Coral else AppColors.Ink3,
                )
            }
        }

        // Events badge — bottom right of card
        if (vm.upcomingEventCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 4.dp, end = 6.dp)
                    .background(AppColors.Coral, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    "${vm.upcomingEventCount} events",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 9.sp, color = Color.White),
                )
            }
        }
    }
}

@Composable
private fun VenueMeta(vm: VenueWithMeta) {
    val metaStyle = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3)
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Show venue category (e.g. "Restaurant") when available
        val categoryLabel = vm.venue.category?.snakeToTitleCase()
        if (categoryLabel != null) {
            Text(categoryLabel, style = metaStyle)
            DotSeparator()
        }
        Text(vm.venue.city, style = metaStyle)
        if (vm.distanceMiles != null) {
            DotSeparator()
            Text(formatDistance(vm.distanceMiles), style = metaStyle)
        }
    }
}

@Composable
private fun DotSeparator() {
    Box(
        modifier = Modifier
            .padding(horizontal = 5.dp)
            .size(3.dp)
            .background(AppColors.Pebble, CircleShape),
    )
}

@Composable
private fun TagRow(tags: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.take(3).forEach { tag ->
            Box(
                modifier = Modifier
                    .background(AppColors.Snow, RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    tag,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = AppColors.Ink2),
                )
            }
        }
    }
}

@Composable
private fun RatingRow(venue: Venue) {
    // Prefer actual Google rating; fall back to rank-derived rating
    val rating = venue.googleRating
        ?: venue.publicInterestRank?.let { (it.coerceIn(0, 100) / 20.0).coerceIn(3.0, 5.0) }
        ?: return
    val fullStars = rating.toInt()
    val hasHalf = (rating - fullStars) >= 0.3

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            buildString {
                repeat(fullStars) { append("\u2605") }
                if (hasHalf) append("\u2606")
                repeat(5 - fullStars - (if (hasHalf) 1 else 0)) { append("\u2606") }
            },
            style = TextStyle(fontSize = 11.sp, color = AppColors.Sun),
        )
        val ratingText = "${rating.toInt()}.${((rating - rating.toInt()) * 10).toInt()}"
        Text(
            ratingText,
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = AppColors.Ink),
        )
    }
}

private fun formatDistance(miles: Double?): String = when {
    miles == null -> ""
    miles < 0.1 -> "Here"
    miles < 10 -> "${miles.toInt()}.${((miles - miles.toInt()) * 10).toInt()} mi"
    else -> "${miles.toInt()} mi"
}

// ═══════════════════════════════════════════════════════════════════
// VENUE DETAIL PAGE  (full-screen page with back button)
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VenueDetailPage(
    venueWithMeta: VenueWithMeta,
    events: List<Event>,
    isLoading: Boolean,
    onBack: () -> Unit,
    isFavorited: Boolean = false,
    onFavoriteToggle: () -> Unit = {},
    onShare: () -> Unit = {},
    isShared: Boolean = false,
    onSaveVenue: (() -> Unit)? = null,
) {
    var selectedEvent by remember { mutableStateOf<Event?>(null) }
    var selectedDateFilter by remember { mutableStateOf("All") }
    val communityInterestRepo = remember { KoinPlatform.getKoin().get<CommunityInterestRepository>() }
    val v = venueWithMeta.venue

    Box(modifier = Modifier.fillMaxSize().background(AppColors.Snow)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(AppColors.Snow, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp), tint = AppColors.Ink)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    v.name,
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AppColors.Ink),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                // Favorite
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(AppColors.Snow, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onFavoriteToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (isFavorited) "Remove favorite" else "Favorite",
                        modifier = Modifier.size(18.dp),
                        tint = if (isFavorited) AppColors.Coral else AppColors.Ink,
                    )
                }
                Spacer(Modifier.width(6.dp))
                // Share
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(AppColors.Snow, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onShare),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.IosShare, contentDescription = "Share", modifier = Modifier.size(18.dp), tint = AppColors.Ink)
                }
            }

            // Scrollable content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Hero image
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(gradientForCategory(venueWithMeta.primaryCategory)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (v.photoUrl != null || v.imageUrl != null) {
                        AsyncImage(
                            model = v.photoUrl ?: v.imageUrl,
                            contentDescription = v.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.White.copy(alpha = 0.5f))
                    }
                }

                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        v.name,
                        style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 24.sp, color = AppColors.Ink),
                    )
                    Spacer(Modifier.height(4.dp))
                    val parts = listOfNotNull(
                        venueWithMeta.primaryCategory?.replaceFirstChar { it.uppercaseChar() },
                        v.city,
                        venueWithMeta.distanceMiles?.let { formatDistance(it) },
                    )
                    Text(
                        parts.joinToString(" \u00B7 "),
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AppColors.Ink3),
                    )

                    // Address
                    if (!v.address.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(14.dp), tint = AppColors.Ink3)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                v.address,
                                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        SheetButton(
                            label = "Directions",
                            isPrimary = true,
                            modifier = Modifier.weight(1f),
                            icon = { Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White) },
                            onClick = {},
                        )
                        if (!v.website.isNullOrBlank()) {
                            SheetButton(
                                label = "Website",
                                isPrimary = false,
                                modifier = Modifier.weight(1f),
                                onClick = { /* TODO: open v.website in browser */ },
                            )
                        }
                    }

                    if (!v.description.isNullOrBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            v.description,
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Normal, fontSize = 14.sp, color = AppColors.Ink2, lineHeight = 20.sp),
                        )
                    }

                    if (events.isNotEmpty()) {
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "Upcoming Events",
                            style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppColors.Ink),
                        )
                        Spacer(Modifier.height(10.dp))

                        // Date filter chips — matches plan detail day tabs style
                        val filters = listOf("All", "Today", "Tomorrow", "This Week", "Weekend")
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            filters.forEach { filter ->
                                val isSelected = selectedDateFilter == filter
                                Text(
                                    filter.uppercase(),
                                    style = TextStyle(
                                        fontFamily = AppFonts.Body,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 12.sp,
                                        color = if (isSelected) Color.White else AppColors.Ink3,
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (isSelected) AppColors.Coral else AppColors.Snow)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) { selectedDateFilter = filter }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Filter events by selected date range
                        val nowMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
                        val dayMs = 86_400_000L
                        val todayStartMs = (nowMs / dayMs) * dayMs // midnight UTC today
                        val filteredEvents = events.filter { event ->
                            if (selectedDateFilter == "All") return@filter true
                            val startStr = event.startAt ?: return@filter true
                            val eventMs = try {
                                kotlinx.datetime.Instant.parse(startStr).toEpochMilliseconds()
                            } catch (_: Exception) { return@filter true }
                            when (selectedDateFilter) {
                                "Today" -> eventMs >= todayStartMs && eventMs < todayStartMs + dayMs
                                "Tomorrow" -> eventMs >= todayStartMs + dayMs && eventMs < todayStartMs + 2 * dayMs
                                "This Week" -> eventMs >= todayStartMs && eventMs < todayStartMs + 7 * dayMs
                                "Weekend" -> {
                                    // Find upcoming Sat/Sun within 7 days
                                    val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
                                    val eventDate = kotlinx.datetime.Instant.parse(startStr).toLocalDateTime(tz)
                                    val dow = eventDate.dayOfWeek
                                    (dow == kotlinx.datetime.DayOfWeek.SATURDAY || dow == kotlinx.datetime.DayOfWeek.SUNDAY) &&
                                        eventMs >= todayStartMs && eventMs < todayStartMs + 7 * dayMs
                                }
                                else -> true
                            }
                        }

                        if (filteredEvents.isEmpty()) {
                            Text(
                                "No events for this period",
                                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AppColors.Ink3),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        } else {
                            filteredEvents.forEach { event ->
                                VenueEventListRow(event = event, onClick = { selectedEvent = event })
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(32.dp))
                }
            }
        }

        // "Add to Favorites" CTA for shared deep link mode
        if (isShared && onSaveVenue != null) {
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
                        .clickable(onClick = onSaveVenue)
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Add to Favorites",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color.White),
                    )
                }
            }
        }

        // Event detail as bottom sheet
        if (selectedEvent != null) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { selectedEvent = null },
                sheetState = sheetState,
                containerColor = AppColors.Snow,
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .padding(top = 12.dp, bottom = 4.dp)
                            .width(40.dp)
                            .height(4.dp)
                            .background(AppColors.Pebble, RoundedCornerShape(2.dp)),
                    )
                },
            ) {
                EventDetailScreen(
                    event = selectedEvent!!,
                    onBack = { selectedEvent = null },
                    onShareTracked = { eventId ->
                        val cat = selectedEvent?.let { it.subcategory ?: it.category }
                        communityInterestRepo.recordShare(eventId, InteractionType.EVENT, cat)
                    },
                    onFavoriteTracked = { eventId ->
                        val cat = selectedEvent?.let { it.subcategory ?: it.category }
                        communityInterestRepo.recordFavorite(eventId, InteractionType.EVENT, cat)
                    },
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// VENUE DETAIL BOTTOM SHEET  (compact sheet for venues without events)
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VenueDetailSheet(
    venueWithMeta: VenueWithMeta,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val v = venueWithMeta.venue

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .background(AppColors.Pebble, RoundedCornerShape(2.dp)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            // Compact hero image
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(gradientForCategory(venueWithMeta.primaryCategory)),
                contentAlignment = Alignment.Center,
            ) {
                if (v.photoUrl != null || v.imageUrl != null) {
                    AsyncImage(
                        model = v.photoUrl ?: v.imageUrl,
                        contentDescription = v.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(40.dp), tint = Color.White.copy(alpha = 0.5f))
                }
            }

            // Info
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    v.name,
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 22.sp, color = AppColors.Ink),
                )
                Spacer(Modifier.height(4.dp))
                val parts = buildList {
                    venueWithMeta.primaryCategory?.let { add(it.replaceFirstChar { c -> c.uppercaseChar() }) }
                    add(v.city)
                    venueWithMeta.distanceMiles?.let { add(formatDistance(it)) }
                }
                Text(
                    parts.joinToString(" \u00B7 "),
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AppColors.Ink3),
                )

                if (!v.description.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        v.description,
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Normal, fontSize = 14.sp, color = AppColors.Ink2, lineHeight = 20.sp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    SheetButton(
                        label = "Directions",
                        isPrimary = true,
                        modifier = Modifier.weight(1f),
                        icon = { Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White) },
                        onClick = {},
                    )
                    if (!v.website.isNullOrBlank()) {
                        SheetButton(
                            label = "Website",
                            isPrimary = false,
                            modifier = Modifier.weight(1f),
                            onClick = { /* TODO: open v.website in browser */ },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SheetButton(
    label: String,
    isPrimary: Boolean,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .background(
                if (isPrimary) AppColors.Coral else AppColors.Snow,
                RoundedCornerShape(14.dp),
            )
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp,
                color = if (isPrimary) Color.White else AppColors.Ink,
            ),
        )
    }
}

@Composable
private fun VenueEventListRow(event: Event, onClick: () -> Unit) {
    val (month, day) = parseMonthDay(event.startAt)
    val weekday = parseWeekday(event.startAt)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(1.dp, AppColors.Pebble, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Date block
        Column(
            modifier = Modifier.width(44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(month, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = AppColors.Coral))
            Text(day, style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 20.sp, color = AppColors.Ink, lineHeight = 22.sp))
            Text(weekday, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = AppColors.Ink3))
        }

        // Event info
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                event.title,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    event.dateDisplay,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3),
                )
                val rawCat = event.subcategory ?: event.category
                val displayCat = if (rawCat.lowercase() == "always_on") "Event" else rawCat.replaceFirstChar { it.uppercaseChar() }
                Text(
                    displayCat,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 9.sp, color = AppColors.Ink2),
                    modifier = Modifier.background(AppColors.Mist, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        // Price / Free badge
        if (event.isFree) {
            Text(
                "Free",
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = Color(0xFF2D9F73)),
                modifier = Modifier
                    .background(Color(0xFFE8F5E9), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        } else if (event.priceDisplay.isNotEmpty()) {
            Text(
                event.priceDisplay,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = AppColors.Ink3),
            )
        }
    }
}

@Composable
private fun EventRow(event: Event, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Date block
        val (month, day) = parseMonthDay(event.startAt)
        Column(
            modifier = Modifier
                .size(44.dp)
                .background(AppColors.Snow, RoundedCornerShape(12.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                month,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 9.sp, color = AppColors.Coral),
            )
            Text(
                day,
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 18.sp, color = AppColors.Ink, lineHeight = 18.sp),
            )
        }

        // Event info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                event.title,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                event.dateDisplay,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
            )
        }

        // Price
        if (event.priceDisplay.isNotEmpty()) {
            Text(
                event.priceDisplay,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = AppColors.Sage),
            )
        }
    }

    // Divider
    Box(
        modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.Snow),
    )
}

@Suppress("DEPRECATION")
private fun parseMonthDay(startAt: String?): Pair<String, String> {
    if (startAt == null) return "" to ""
    return try {
        val instant = kotlinx.datetime.Instant.parse(startAt)
        val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
        val monthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        monthNames[local.monthNumber - 1] to local.dayOfMonth.toString()
    } catch (_: Exception) {
        "" to ""
    }
}

private fun parseWeekday(startAt: String?): String {
    if (startAt == null) return ""
    return try {
        val instant = kotlinx.datetime.Instant.parse(startAt)
        val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
        val dayNames = arrayOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
        dayNames[local.dayOfWeek.ordinal]
    } catch (_: Exception) {
        ""
    }
}
