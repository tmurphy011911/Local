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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Favorite
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import live.localapp.local.platform.rememberNativeDateRangePicker
import live.localapp.local.domain.model.Event
import live.localapp.local.snakeToTitleCase
import live.localapp.local.domain.model.Venue
import live.localapp.local.domain.repository.VenueWithMeta
import live.localapp.local.domain.repository.CommunityInterestRepository
import live.localapp.local.domain.repository.InteractionType
import live.localapp.local.ui.detail.EventDetailScreen
import live.localapp.local.ui.home.isLocationInSupportedState
import live.localapp.local.ui.home.toCardData
import live.localapp.local.ui.common.AppHeader
import live.localapp.local.ui.common.localCardSurface
import live.localapp.local.ui.theme.AppColors
import org.koin.mp.KoinPlatform
import live.localapp.local.ui.theme.AppFonts
import live.localapp.local.ui.theme.AppType
import live.localapp.local.ui.theme.AppShapes
import live.localapp.local.ui.common.pressable

// ── Category gradient backgrounds ──
// Card-fallback gradients aligned with Home (HomeScreen.categoryGradient) and
// iOS event detail (EventDetailView.categoryGradientColors). Pale pastels keyed
// to category so the same event/venue gets the same wash on Explore, Home, and
// the detail screen. Previous palette here was dark/saturated, which read as a
// different design language from the rest of the app.
private fun gradientForCategory(cat: String?): Brush {
    val (start, end) = when (cat?.lowercase()) {
        "music", "live-music", "concerts", "nightlife" -> AppColors.BgPlumStart to AppColors.BgPlumEnd
        "food", "food-drink" -> AppColors.BgCoralStart to AppColors.BgCoralEnd
        "comedy" -> AppColors.BgSunStart to AppColors.BgSunEnd
        "sports" -> AppColors.BgSageStart to AppColors.BgSageEnd
        "arts", "performing-arts", "theater", "theatre" -> AppColors.BgSkyStart to AppColors.BgSkyEnd
        else -> AppColors.BgCoralStart to AppColors.BgCoralEnd
    }
    return Brush.linearGradient(listOf(start, end))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(
    viewModel: PlacesViewModel,
    onProfileClick: () -> Unit = {},
    onLocationClick: () -> Unit = {},
    userInitial: String = "",
    onVenueClickWithBounds: ((VenueWithMeta, Rect) -> Unit)? = null,
    onEventClick: ((live.localapp.local.domain.model.Event, Rect) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val cityLabel = state.locationName.split(",").firstOrNull()?.trim().orEmpty()

    Box(modifier = Modifier.fillMaxSize().background(AppColors.Snow).clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
    ) { focusManager.clearFocus() }) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header ──
            AppHeader(
                    title = "explore",
                    locationName = state.locationName,
                    radiusFilter = state.radiusFilter,
                    streakDays = state.streakDays,
                    userInitial = userInitial,
                    isUnsupportedState = !isLocationInSupportedState(state.userLat, state.userLng),
                    onProfileClick = onProfileClick,
                    onLocationClick = onLocationClick,
                )

            // ── Events | Places sub-tab row ──
            SearchSubTabRow(
                active = state.activeTab,
                onSelect = { viewModel.setActiveTab(it) },
            )

            // ── Search bar — placeholder flips with active tab ──
            Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                SearchBar(
                    query = state.searchQuery,
                    onQueryChange = { viewModel.setSearchQuery(it) },
                    placeholder = searchPlaceholder(state.activeTab, cityLabel),
                )
            }

            // ── Per-tab filter chips ──
            when (state.activeTab) {
                SearchTab.EVENTS -> EventDateFilterRow(
                    selected = state.dateFilter,
                    customStart = state.customStart,
                    customEnd = state.customEnd,
                    onSelectPreset = { viewModel.setDateFilter(it) },
                    onCustomRangePicked = { start, end -> viewModel.setCustomDateRange(start, end) },
                )
                SearchTab.PLACES -> PlaceSortRow(
                    selected = state.placeSort,
                    onSelect = { viewModel.setPlaceSort(it) },
                )
            }

            // ── Content ──
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.weight(1f),
            ) {
                when (state.activeTab) {
                    SearchTab.PLACES -> {
                        if (state.isLoading && state.venues.isEmpty()) {
                            PlacesSkeletonLoader()
                        } else {
                            // Sort happens server-side now — the list arrives
                            // already ordered for the active sort, paginated
                            // across the full dataset. The scrollResetKey on
                            // VenueGrid still snaps the user back to the top
                            // when the sort changes.
                            VenueGrid(
                                venues = state.filteredVenues,
                                favoriteIds = state.favoriteVenueIds,
                                hasMore = state.hasMore,
                                onLoadMore = { viewModel.loadMore() },
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
                                scrollResetKey = state.placeSort,
                            )
                        }
                    }
                    SearchTab.EVENTS -> {
                        if (state.isEventsLoading && state.filteredEvents.isEmpty()) {
                            EventsSkeletonLoader()
                        } else {
                            EventGrid(
                                events = state.filteredEvents,
                                savedEvents = state.savedEvents,
                                favoriteIds = state.favoriteEventIds,
                                userLat = state.userLat,
                                userLng = state.userLng,
                                hasMore = state.eventsHasMore,
                                onLoadMore = { viewModel.loadMoreEvents() },
                                onEventClick = { event, bounds -> onEventClick?.invoke(event, bounds) },
                                onFavoriteToggle = { event ->
                                    viewModel.toggleEventFavorite(event.id, event.subcategory ?: event.category)
                                },
                                scrollResetKey = Triple(state.dateFilter, state.customStart, state.customEnd),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Sub-tab row (Events | Places) ───────────────────────────────────

private fun searchPlaceholder(tab: SearchTab, cityLabel: String): String {
    val cityPart = if (cityLabel.isNotBlank()) " near $cityLabel" else ""
    return when (tab) {
        SearchTab.EVENTS -> "Search for events$cityPart"
        SearchTab.PLACES -> "Search for places$cityPart"
    }
}

@Composable
private fun SearchSubTabRow(active: SearchTab, onSelect: (SearchTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.White)
            .padding(top = 4.dp),
    ) {
        SubTabTile(
            label = "Events",
            iconName = "events",
            isActive = active == SearchTab.EVENTS,
            onClick = { onSelect(SearchTab.EVENTS) },
            modifier = Modifier.weight(1f),
        )
        SubTabTile(
            label = "Places",
            iconName = "places",
            isActive = active == SearchTab.PLACES,
            onClick = { onSelect(SearchTab.PLACES) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SubTabTile(
    label: String,
    iconName: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var trigger by remember { mutableStateOf(0) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.pressable(onClick = { trigger++; onClick() }),
    ) {
        Spacer(Modifier.height(4.dp))
        when (iconName) {
            "events" -> live.localapp.local.ui.icons.LocalIcon.Events(
                size = 50.dp, trigger = trigger,
                modifier = Modifier.size(50.dp).clipToBounds(),
            )
            "places" -> live.localapp.local.ui.icons.LocalIcon.Places(
                size = 50.dp, trigger = trigger,
                modifier = Modifier.size(50.dp).clipToBounds(),
            )
            else -> CategoryIconWithFallback(
                name = iconName,
                fallbackName = "foryou",
                modifier = Modifier.size(50.dp),
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            label,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                fontSize = 12.sp,
                color = if (isActive) AppColors.Ink else AppColors.Ink3,
            ),
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(2.5.dp)
                .background(
                    if (isActive) AppColors.Ink else Color.Transparent,
                    RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
                ),
        )
    }
}

@Composable
private fun CategoryIconWithFallback(
    name: String,
    fallbackName: String,
    modifier: Modifier = Modifier,
) {
    val primaryUrl = buildSearchIconUrl(name)
    val fallbackUrl = buildSearchIconUrl(fallbackName)
    var url by remember(name) { mutableStateOf(primaryUrl) }
    coil3.compose.AsyncImage(
        model = url,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        onError = {
            // If the named icon doesn't exist in Storage yet, fall back to foryou.png
            // so we never render a broken image placeholder.
            if (url != fallbackUrl) url = fallbackUrl
        },
    )
}

private fun buildSearchIconUrl(name: String): String =
    "${live.localapp.local.Config.SUPABASE_URL}/storage/v1/object/public/category-icons/$name.png" +
        "?v=${live.localapp.local.domain.repository.CATEGORY_ICONS_VERSION}"

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

/**
 * Events-tab skeleton — mirrors the row layout of EventRowCard (72dp thumbnail
 * + 3 text rows) so the swap from skeleton → real list doesn't reflow the page.
 */
@Composable
private fun EventsSkeletonLoader() {
    val shimmerAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
    )
    val c = AppColors.Pebble.copy(alpha = 0.3f + shimmerAlpha * 0.2f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(8) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(c))
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.width(180.dp).height(14.dp).background(c, RoundedCornerShape(7.dp)))
                    Box(Modifier.width(140.dp).height(10.dp).background(c, RoundedCornerShape(5.dp)))
                    Box(Modifier.width(90.dp).height(10.dp).background(c, RoundedCornerShape(5.dp)))
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(28.dp).clip(CircleShape).background(c))
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, placeholder: String) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
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
            .height(56.dp)
            .shadow(4.dp, RoundedCornerShape(16.dp)),
    )
}

// ═══════════════════════════════════════════════════════════════════
// FILTER CHIP ROWS
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun EventDateFilterRow(
    selected: EventDateFilter?,
    customStart: LocalDate?,
    customEnd: LocalDate?,
    onSelectPreset: (EventDateFilter?) -> Unit,
    onCustomRangePicked: (LocalDate, LocalDate) -> Unit,
) {
    val pickerController = rememberNativeDateRangePicker()
    val customActive = selected == EventDateFilter.CUSTOM && customStart != null && customEnd != null

    val openPicker: () -> Unit = {
        pickerController.show(
            initialStartMillis = customStart?.toLocalMidnightMillis(),
            initialEndMillis = customEnd?.toLocalMidnightMillis(),
            onPicked = { startMs, endMs ->
                onCustomRangePicked(epochMillisToLocalDate(startMs), epochMillisToLocalDate(endMs))
            },
            onCancelled = { /* keep current state */ },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (customActive) {
            // Active custom-range chip lives at the front with an X to clear.
            // Replaces the "All" chip in this state — the X is the way to
            // get back to no-filter.
            FilterChip(
                label = formatDateRangeShort(customStart!!, customEnd!!),
                selected = true,
                onClick = openPicker,
                onDismiss = { onSelectPreset(null) },
            )
        } else {
            FilterChip(label = "All", selected = selected == null, onClick = { onSelectPreset(null) })
        }
        FilterChip(
            label = EventDateFilter.TODAY.label,
            selected = selected == EventDateFilter.TODAY,
            onClick = { onSelectPreset(EventDateFilter.TODAY) },
        )
        FilterChip(
            label = EventDateFilter.TOMORROW.label,
            selected = selected == EventDateFilter.TOMORROW,
            onClick = { onSelectPreset(EventDateFilter.TOMORROW) },
        )
        FilterChip(
            label = EventDateFilter.THIS_WEEKEND.label,
            selected = selected == EventDateFilter.THIS_WEEKEND,
            onClick = { onSelectPreset(EventDateFilter.THIS_WEEKEND) },
        )
        if (!customActive) {
            FilterChip(
                label = EventDateFilter.CUSTOM.label,
                selected = false,
                onClick = openPicker,
            )
        }
    }
}

// Use the system timezone for both directions so the date the user picks in
// the native sheet round-trips back unchanged when re-opening the picker.
// (Round-tripping through UTC silently shifts dates by a day for users west
// of UTC, since e.g. May 7 00:00 UTC is May 6 20:00 EDT.)
private fun LocalDate.toLocalMidnightMillis(): Long =
    atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()

private fun epochMillisToLocalDate(millis: Long): LocalDate =
    kotlinx.datetime.Instant.fromEpochMilliseconds(millis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date

@Suppress("DEPRECATION") // monthNumber is the existing convention in this file
private fun formatDateRangeShort(start: LocalDate, end: LocalDate): String {
    val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val sm = months[start.monthNumber - 1]
    val em = months[end.monthNumber - 1]
    return when {
        start == end -> "$sm ${start.dayOfMonth}"
        sm == em -> "$sm ${start.dayOfMonth}–${end.dayOfMonth}"
        else -> "$sm ${start.dayOfMonth} – $em ${end.dayOfMonth}"
    }
}

@Composable
private fun PlaceSortRow(selected: PlaceSort, onSelect: (PlaceSort) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlaceSort.entries.forEach { sort ->
            FilterChip(
                label = sort.label,
                selected = selected == sort,
                onClick = { onSelect(sort) },
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    val bg = if (selected) AppColors.Coral else AppColors.White
    val fg = if (selected) AppColors.White else AppColors.Ink2
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(start = 14.dp, end = if (onDismiss != null) 6.dp else 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.sp,
                color = fg,
            ),
        )
        if (onDismiss != null) {
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Clear filter",
                    modifier = Modifier.size(14.dp),
                    tint = fg,
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// VENUE GRID
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun VenueGrid(
    venues: List<VenueWithMeta>,
    favoriteIds: Set<String>,
    hasMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    onVenueClick: (VenueWithMeta, Rect) -> Unit,
    onFavoriteToggle: (VenueWithMeta) -> Unit,
    /** When this changes, the list jumps to the top — pass the sort enum so
     *  switching sort doesn't strand the user mid-list. */
    scrollResetKey: Any? = null,
) {
    // Saved venues — filter from current venues list
    val savedVenues = remember(venues, favoriteIds) {
        venues.filter { it.venue.id in favoriteIds }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(scrollResetKey) {
        if (scrollResetKey != null) listState.scrollToItem(0)
    }

    LazyColumn(
        state = listState,
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

        // Explicit Load more button — no infinite scroll per user UX preference.
        if (hasMore) {
            item { LoadMoreButton(onClick = onLoadMore) }
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
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Header — tappable to collapse/expand
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressable(onClick = { expanded = !expanded }, scale = 0.98f),
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

    Box(
        modifier = Modifier
            .width(156.dp)
            .height(194.dp)
            .onGloballyPositioned { cardBounds = it.boundsInRoot() }
            .localCardSurface(cornerRadius = 16.dp)
            .pressable(onClick = { onClick(cardBounds) }),
    ) {
        // Full-card image or category-pastel gradient. The dark overlay is
        // applied ONLY over a real photo so white name text is legible — over
        // the pale placeholder the name swaps to ink color.
        val hasImage = v.photoUrl != null || v.imageUrl != null
        if (hasImage) {
            AsyncImage(
                model = v.photoUrl ?: v.imageUrl,
                contentDescription = v.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)), startY = 60f),
                ),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(gradientForCategory(vm.primaryCategory)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(32.dp), tint = AppColors.Ink3.copy(alpha = 0.4f))
            }
        }

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

        // Name only — bottom. White over the dark overlay on real photos;
        // dark ink over the pale gradient when there's no image.
        Text(
            v.name,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
            style = TextStyle(
                fontFamily = AppFonts.Heading,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = if (hasImage) Color.White else AppColors.Ink,
            ),
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
    // Hero source rect = the thumbnail, not the whole row, so the detail
    // page expands/collapses from the image.
    var imageBounds by remember { mutableStateOf(Rect.Zero) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .pressable(onClick = { onClick(imageBounds) })
            .localCardSurface(cornerRadius = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumbnail — flush to card edge (top/left/bottom), full card height
        Box(
            modifier = Modifier
                .width(92.dp)
                .fillMaxHeight()
                .onGloballyPositioned { imageBounds = it.boundsInRoot() }
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
                Icon(
                    Icons.Outlined.Place,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = AppColors.Ink3.copy(alpha = 0.5f),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(
                v.name,
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = AppColors.Ink,
                    lineHeight = 18.sp,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            VenueMeta(vm)
            // Third line mirrors event-card price/date row: rating + event count.
            val ratingShown = (v.googleRating ?: 0.0) > 0
            val eventsShown = vm.upcomingEventCount > 0
            if (ratingShown || eventsShown) {
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ratingShown) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                            tint = AppColors.Sun,
                        )
                        Spacer(Modifier.width(3.dp))
                        val r = v.googleRating!!
                        val ratingText = "${r.toInt()}.${((r - r.toInt()) * 10).toInt()}"
                        Text(
                            ratingText,
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 11.sp,
                                color = AppColors.Ink,
                            ),
                        )
                    }
                    if (ratingShown && eventsShown) Spacer(Modifier.width(8.dp))
                    if (eventsShown) {
                        Text(
                            "${vm.upcomingEventCount} event${if (vm.upcomingEventCount == 1) "" else "s"}",
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 11.sp,
                                color = AppColors.Coral,
                            ),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        // Heart — inline, matches EventRowCard (28dp white circle, 16dp coral icon)
        Box(
            modifier = Modifier
                .padding(end = 12.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(AppColors.White)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onFavoriteToggle,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = "Favorite",
                modifier = Modifier.size(16.dp),
                tint = if (isFavorited) AppColors.Coral else AppColors.Ink3,
            )
        }
    }
}

@Composable
private fun VenueMeta(vm: VenueWithMeta) {
    val metaStyle = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3)
    Row(verticalAlignment = Alignment.CenterVertically) {
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
                        Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(48.dp), tint = AppColors.Ink3.copy(alpha = 0.4f))
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
                    Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(40.dp), tint = AppColors.Ink3.copy(alpha = 0.4f))
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

// ═══════════════════════════════════════════════════════════════════
// Events list — mirrors VenueGrid visually (72dp thumbnail + meta row).
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun EventGrid(
    events: List<live.localapp.local.domain.model.Event>,
    savedEvents: List<live.localapp.local.domain.model.Event>,
    favoriteIds: Set<String>,
    userLat: Double?,
    userLng: Double?,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onEventClick: (live.localapp.local.domain.model.Event, Rect) -> Unit,
    onFavoriteToggle: (live.localapp.local.domain.model.Event) -> Unit,
    /** When this changes, the list jumps to the top — pass the date filter
     *  so switching filters doesn't strand the user mid-list. */
    scrollResetKey: Any? = null,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(scrollResetKey) {
        if (scrollResetKey != null) listState.scrollToItem(0)
    }

    if (events.isEmpty() && savedEvents.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No events found",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = AppColors.Ink3,
                ),
            )
        }
        return
    }

    androidx.compose.foundation.lazy.LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (savedEvents.isNotEmpty()) {
            item {
                SavedEventsSection(
                    events = savedEvents,
                    userLat = userLat,
                    userLng = userLng,
                    onEventClick = onEventClick,
                    onFavoriteToggle = onFavoriteToggle,
                )
            }
        }
        items(events.size, key = { events[it].id }) { i ->
            EventRowCard(
                event = events[i],
                isFavorited = events[i].id in favoriteIds,
                userLat = userLat,
                userLng = userLng,
                onClick = { bounds -> onEventClick(events[i], bounds) },
                onFavoriteToggle = { onFavoriteToggle(events[i]) },
            )
        }
        if (hasMore) {
            item {
                LoadMoreButton(onClick = onLoadMore)
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

/**
 * Horizontal album carousel for saved events — mirrors the Home screen's
 * `SavedEventsAlbumCarousel` look and feel so the two surfaces stay visually
 * consistent. Tappable header toggles expand/collapse.
 */
@Composable
private fun SavedEventsSection(
    events: List<live.localapp.local.domain.model.Event>,
    userLat: Double?,
    userLng: Double?,
    onEventClick: (live.localapp.local.domain.model.Event, Rect) -> Unit,
    onFavoriteToggle: (live.localapp.local.domain.model.Event) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        // Header — tappable to collapse/expand, matches Home styling.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressable(onClick = { expanded = !expanded }, scale = 0.98f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Saved Events (${events.size})",
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

        if (expanded) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                events.forEach { event ->
                    SavedAlbumEventCard(
                        event = event,
                        onClick = { bounds -> onEventClick(event, bounds) },
                        onUnfavorite = { onFavoriteToggle(event) },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.Mist),
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun SavedAlbumEventCard(
    event: live.localapp.local.domain.model.Event,
    onClick: (Rect) -> Unit,
    onUnfavorite: () -> Unit,
) {
    var cardBounds by remember { mutableStateOf(Rect.Zero) }
    Box(
        modifier = Modifier
            .width(156.dp)
            .height(194.dp)
            .onGloballyPositioned { cardBounds = it.boundsInRoot() }
            .pressable(onClick = { onClick(cardBounds) })
            .localCardSurface(cornerRadius = 16.dp),
    ) {
        // Full-bleed image or category gradient fallback
        if (!event.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = event.imageUrl,
                contentDescription = event.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(gradientForCategory(event.subcategory ?: event.category)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = AppColors.Ink3.copy(alpha = 0.3f),
                )
            }
        }

        // Date pill top-left
        val dateText = event.dateDisplay
        if (dateText.isNotBlank()) {
            Text(
                dateText,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.sp,
                    color = Color.White,
                ),
                maxLines = 1,
            )
        }

        // Heart — top-right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(28.dp)
                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onUnfavorite,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = "Remove",
                modifier = Modifier.size(14.dp),
                tint = AppColors.Coral,
            )
        }

        // Title at bottom with gradient scrim
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            Text(
                event.title,
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp,
                    color = Color.White,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EventRowCard(
    event: live.localapp.local.domain.model.Event,
    isFavorited: Boolean,
    userLat: Double?,
    userLng: Double?,
    onClick: (Rect) -> Unit,
    onFavoriteToggle: () -> Unit,
) {
    // Hero source rect = the thumbnail, not the whole row, so the detail
    // page expands/collapses from the image.
    var imageBounds by remember { mutableStateOf(Rect.Zero) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .pressable(onClick = { onClick(imageBounds) })
            .localCardSurface(cornerRadius = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumbnail — flush to card edge (top/left/bottom), full card height
        Box(
            modifier = Modifier
                .width(92.dp)
                .fillMaxHeight()
                .onGloballyPositioned { imageBounds = it.boundsInRoot() }
                .background(gradientForCategory(event.subcategory ?: event.category)),
            contentAlignment = Alignment.Center,
        ) {
            if (!event.imageUrl.isNullOrBlank()) {
                coil3.compose.AsyncImage(
                    model = event.imageUrl,
                    contentDescription = event.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    tint = AppColors.Ink3.copy(alpha = 0.5f),
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(
                event.title,
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = AppColors.Ink,
                    lineHeight = 18.sp,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            val metaParts = buildList {
                event.venueName?.takeIf { it.isNotBlank() }?.let { add(it) }
                val distLabel = distanceLabel(event, userLat, userLng)
                if (distLabel.isNotBlank()) add(distLabel)
            }
            if (metaParts.isNotEmpty()) {
                Text(
                    metaParts.joinToString(" · "),
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = AppColors.Ink3,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (event.dateDisplay.isNotBlank() || event.priceDisplay.isNotBlank() || event.isFree) {
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (event.dateDisplay.isNotBlank()) {
                        Text(
                            event.dateDisplay,
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 11.sp,
                                color = AppColors.Coral,
                            ),
                        )
                    }
                    if (event.isFree) {
                        if (event.dateDisplay.isNotBlank()) Spacer(Modifier.width(8.dp))
                        Text(
                            "Free",
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 11.sp,
                                color = AppColors.Sage,
                            ),
                        )
                    } else if (event.priceDisplay.isNotBlank()) {
                        if (event.dateDisplay.isNotBlank()) Spacer(Modifier.width(8.dp))
                        Text(
                            event.priceDisplay,
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
        }

        Spacer(Modifier.width(8.dp))

        // Heart — matches venue card style (28dp white circle, 16dp coral)
        Box(
            modifier = Modifier
                .padding(end = 12.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(AppColors.White)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onFavoriteToggle,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = "Favorite",
                modifier = Modifier.size(16.dp),
                tint = if (isFavorited) AppColors.Coral else AppColors.Ink3,
            )
        }
    }
}

@Composable
private fun LoadMoreButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(AppColors.White)
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text(
                "Load more",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = AppColors.Coral,
                ),
            )
        }
    }
}

private fun distanceLabel(
    event: live.localapp.local.domain.model.Event,
    userLat: Double?,
    userLng: Double?,
): String {
    val lat = event.venueLat ?: return ""
    val lng = event.venueLng ?: return ""
    if (userLat == null || userLng == null) return ""
    val miles = live.localapp.local.domain.model.distanceMiles(userLat, userLng, lat, lng)
    if (miles <= 0.0 || miles > 500.0) return ""
    return if (miles < 1.0) "<1 mi" else "${miles.toInt()} mi"
}
