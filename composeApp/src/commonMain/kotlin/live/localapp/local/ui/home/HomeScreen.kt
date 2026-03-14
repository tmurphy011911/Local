package live.localapp.local.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocalDining
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Nightlife
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.SportsScore
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import coil3.compose.AsyncImage
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.Config
import live.localapp.local.domain.model.CarouselSection
import live.localapp.local.domain.repository.CategoryInfo
import live.localapp.local.domain.model.Event
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts
import live.localapp.local.ui.theme.AppShapes
import live.localapp.local.ui.theme.AppType

// ── Title case helper ──

private val minorWords = setOf("vs", "vs.", "and", "the", "of", "in", "at", "to", "for", "a", "an", "on", "or")

private fun String.toTitleCase(): String {
    if (isBlank()) return this
    return lowercase().split(" ").mapIndexed { i, word ->
        if (i > 0 && word in minorWords) word
        else word.replaceFirstChar { it.uppercase() }
    }.joinToString(" ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, onEventClick: (Event) -> Unit = {}, onConciergeClick: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsState()
    var showDebugMenu by remember { mutableStateOf(false) }

    if (showDebugMenu) {
        DebugMenuDialog(
            onDismiss = { showDebugMenu = false },
            onMockLocation = { lat, lng, name ->
                viewModel.debugSetLocation(lat, lng, name)
                showDebugMenu = false
            },
            onSetUserState = { userState ->
                // TODO: wire to user state management
                showDebugMenu = false
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Snow),
    ) {
        HomeHeader(state, onDebugOpen = { showDebugMenu = true })

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                GreetingSection(state)
                AiConciergeCard(onClick = onConciergeClick)
                SegmentedControl(state, viewModel)
                CategoryRow(state, viewModel)

                if (state.isLoading && !state.isRefreshing) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Loading events...", style = AppType.PlanMeta)
                    }
                } else if (state.carousels.isEmpty() && state.error != null) {
                    EmptyState(
                        title = "Something went wrong",
                        subtitle = "Pull down to try again",
                    )
                } else if (state.carousels.isEmpty() && !state.isLoading) {
                    EmptyState(
                        title = "No events found",
                        subtitle = "Try a different category or check back later",
                    )
                } else {
                    val isForYou = state.selectedCategory == "foryou"
                    state.carousels.forEach { section ->
                        key(section.slug) {
                            CarouselSectionView(section, onEventClick, showTags = isForYou)
                        }
                    }
                }

                PlansSection()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 60.dp, horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = AppColors.Ink,
            ),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                color = AppColors.Ink3,
            ),
        )
    }
}

// ── Dynamic Carousel Section ──

@Composable
private fun CarouselSectionView(section: CarouselSection, onEventClick: (Event) -> Unit, showTags: Boolean = true) {
    SectionHeader(section.title)
    EventCardRow(section.events, onEventClick, showTags = showTags)
}

// ── Segmented Control ──

@Composable
private fun SegmentedControl(state: HomeUiState, viewModel: HomeViewModel) {
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 14.dp)
            .fillMaxWidth()
            .background(AppColors.Mist, AppShapes.SegmentControl)
            .border(2.dp, AppColors.Pebble, AppShapes.SegmentControl)
            .padding(3.dp),
    ) {
        SegmentButton(
            label = "Upcoming Events",
            isSelected = state.selectedSegment == HomeSegment.UPCOMING,
            onClick = { viewModel.selectSegment(HomeSegment.UPCOMING) },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(3.dp))
        SegmentButton(
            label = "Always On",
            isSelected = state.selectedSegment == HomeSegment.ALWAYS_ON,
            onClick = { viewModel.selectSegment(HomeSegment.ALWAYS_ON) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SegmentButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textColor = if (isSelected) AppColors.Ink else AppColors.Ink3

    Box(modifier = modifier) {
        if (isSelected) {
            // Flat bottom shadow: 0 2px 0 pebble (matches CSS box-shadow exactly)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(y = 2.dp)
                    .background(AppColors.Pebble, AppShapes.SegmentButton),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isSelected) AppColors.White else Color.Transparent,
                    AppShapes.SegmentButton,
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = AppType.SegmentButton.copy(color = textColor),
            )
        }
    }
}

// ── Category Row ──

private const val ICON_BASE = "${Config.SUPABASE_URL}/storage/v1/object/public/category-icons"

private val forYouTile = CategoryInfo(
    id = "foryou",
    label = "For You",
    iconUrl = "$ICON_BASE/foryou.png",
    eventCount = 0,
)

@Composable
private fun CategoryRow(state: HomeUiState, viewModel: HomeViewModel) {
    val categories = remember(state.availableCategories) {
        listOf(forYouTile) + state.availableCategories
    }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val selectedIndex = categories.indexOfFirst { it.id == state.selectedCategory }.coerceAtLeast(0)

    // Item width in px: 64dp per item
    val itemWidthPx = with(density) { 64.dp.toPx() }

    // Auto-scroll to center the selected category
    LaunchedEffect(selectedIndex, state.selectedSegment) {
        val targetCenter = (selectedIndex * itemWidthPx + itemWidthPx / 2).toInt()
        val viewportCenter = scrollState.viewportSize / 2
        val scrollTo = (targetCenter - viewportCenter).coerceIn(0, scrollState.maxValue)
        scrollState.animateScrollTo(scrollTo)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 10.dp)
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            categories.forEach { cat ->
                val isActive = cat.id == state.selectedCategory
                CategoryTileItem(
                    tile = cat,
                    isActive = isActive,
                    onClick = { viewModel.selectCategory(cat.id) },
                )
            }
        }

        // Bottom border line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.5.dp)
                .background(Color(0xFFEBEBEB)),
        )
    }
}

@Composable
private fun CategoryTileItem(
    tile: CategoryInfo,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.08f else 1f,
        animationSpec = tween(150),
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        AsyncImage(
            model = tile.iconUrl,
            contentDescription = tile.label,
            modifier = Modifier
                .size(50.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale },
            contentScale = ContentScale.Fit,
        )

        Spacer(Modifier.height(5.dp))

        Text(
            tile.label,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                fontSize = 12.sp,
                color = if (isActive) AppColors.Ink else AppColors.Ink3,
            ),
            maxLines = 1,
        )

        Spacer(Modifier.height(10.dp))

        // Bottom indicator bar
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

// ── Section Header ──

@Composable
private fun SectionHeader(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 18.dp, bottom = 12.dp),
    ) {
        Text(title, style = AppType.SectionTitle)
    }
}

// ── Event Card ──

private data class EventCardData(
    val title: String,
    val venueName: String,
    val date: String,
    val city: String,
    val distance: String,
    val tag: String,
    val showTag: Boolean = true,
    val isFree: Boolean = false,
    val imageUrl: String?,
    val bgStart: Color,
    val bgEnd: Color,
    val icon: ImageVector,
)

@Composable
private fun EventCard(data: EventCardData, onClick: () -> Unit = {}) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val offsetY by animateFloatAsState(
        targetValue = if (isPressed) 3f else 0f,
        animationSpec = tween(80),
    )

    Box(
        modifier = Modifier
            .width(156.dp)
            .height(212.dp)
            .graphicsLayer { translationY = offsetY * density },
    ) {
        // Shadow
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = if (!isPressed) 4.dp else 1.dp)
                .background(AppColors.Pebble, AppShapes.CardMedium),
        )

        // Card
        Column(
            modifier = Modifier
                .width(156.dp)
                .height(212.dp)
                .background(AppColors.White, AppShapes.CardMedium)
                .border(2.dp, AppColors.Pebble, AppShapes.CardMedium)
                .clip(AppShapes.CardMedium)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
        ) {
            // Image area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .background(
                        Brush.linearGradient(listOf(data.bgStart, data.bgEnd)),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (data.imageUrl != null) {
                    AsyncImage(
                        model = data.imageUrl,
                        contentDescription = data.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(data.icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = AppColors.Ink3.copy(alpha = 0.5f))
                }

                // Tag pill — only shown on "For You" tab
                if (data.showTag) {
                    Text(
                        data.tag,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(7.dp)
                            .background(AppColors.White, AppShapes.Pill)
                            .border(1.5.dp, AppColors.Pebble, AppShapes.Pill)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        style = AppType.LabelMicro.copy(color = AppColors.Ink2),
                    )
                }

                // Heart button
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .size(26.dp)
                        .background(AppColors.White, CircleShape)
                        .border(1.5.dp, AppColors.Pebble, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.FavoriteBorder, contentDescription = "Favorite", modifier = Modifier.size(13.dp), tint = AppColors.Ink3)
                }
            }

            // Body
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    data.title,
                    style = AppType.EventCardTitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Column {
                    // Venue name
                    if (data.venueName.isNotEmpty()) {
                        Text(
                            data.venueName,
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

                    // Date
                    if (data.date.isNotEmpty()) {
                        Text(
                            data.date,
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 11.sp,
                                color = AppColors.Ink2,
                            ),
                            maxLines = 1,
                        )
                    }

                    // City + distance
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (data.city.isNotEmpty()) {
                            Text(
                                data.city,
                                style = TextStyle(
                                    fontFamily = AppFonts.Body,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp,
                                    color = AppColors.Ink3,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (data.distance.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 4.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Place,
                                    contentDescription = null,
                                    modifier = Modifier.size(11.dp),
                                    tint = AppColors.Ink3,
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    data.distance,
                                    style = TextStyle(
                                        fontFamily = AppFonts.Body,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp,
                                        color = AppColors.Ink3,
                                    ),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventCardRow(events: List<Event>, onEventClick: (Event) -> Unit, showTags: Boolean = true) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        events.forEach { event ->
            EventCard(data = event.toCardData(showTag = showTags), onClick = { onEventClick(event) })
        }
    }
}

// ── Category styling helpers ──

private fun categoryColors(category: String): Pair<Color, Color> = when (category.lowercase()) {
    "music" -> AppColors.BgPlumStart to AppColors.BgPlumEnd
    "sports" -> AppColors.BgSkyStart to AppColors.BgSkyEnd
    "arts" -> AppColors.BgSageStart to AppColors.BgSageEnd
    "comedy" -> AppColors.BgSunStart to AppColors.BgSunEnd
    "food" -> AppColors.BgSunStart to AppColors.BgSunEnd
    "outdoors" -> AppColors.BgSageStart to AppColors.BgSageEnd
    "nightlife" -> AppColors.BgPlumStart to AppColors.BgPlumEnd
    else -> AppColors.BgCoralStart to AppColors.BgCoralEnd
}

private fun categoryIcon(category: String): ImageVector = when (category.lowercase()) {
    "music" -> Icons.Outlined.MusicNote
    "sports" -> Icons.Outlined.SportsScore
    "arts" -> Icons.Outlined.Palette
    "comedy" -> Icons.Outlined.TheaterComedy
    "food" -> Icons.Outlined.LocalDining
    "outdoors" -> Icons.Outlined.Park
    "nightlife" -> Icons.Outlined.Nightlife
    else -> Icons.Outlined.AutoAwesome
}

private fun Event.toCardData(showTag: Boolean = true): EventCardData {
    val displaySubcategory = formatSubcategoryDisplay(subcategory ?: category)
    val colorKey = subcategory ?: category
    val (bgS, bgE) = categoryColors(colorKey)
    return EventCardData(
        title = title.toTitleCase(),
        venueName = venueName ?: "",
        date = dateDisplay,
        city = buildString {
            append(venueCity.replaceFirstChar { it.uppercase() })
            val state = venueState?.uppercase()
            if (state != null && state != "MA") {
                append(", $state")
            }
        },
        distance = distanceDisplay,
        tag = displaySubcategory,
        showTag = showTag,
        isFree = isFree,
        imageUrl = imageUrl,
        bgStart = bgS,
        bgEnd = bgE,
        icon = categoryIcon(colorKey),
    )
}

private fun formatSubcategoryDisplay(key: String): String = when (key.lowercase()) {
    "for-kids" -> "For Kids"
    else -> key.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
}

// ── Plans Section ──

@Composable
private fun PlansSection() {
    SectionHeader("Your Plans")
    PlanRow(
        icon = Icons.Outlined.Map,
        name = "Sarah's Visit",
        meta = "5 events · Nov 8-10",
    )
    Spacer(Modifier.height(9.dp))
    PlanRow(
        icon = Icons.Outlined.FavoriteBorder,
        name = "Date Night Ideas",
        meta = "3 events · This weekend",
    )
}

@Composable
private fun PlanRow(icon: ImageVector, name: String, meta: String) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val offsetY by animateFloatAsState(
        targetValue = if (isPressed) 2f else 0f,
        animationSpec = tween(80),
    )

    Box(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .graphicsLayer { translationY = offsetY * density },
    ) {
        // Shadow
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = if (!isPressed) 3.dp else 1.dp)
                .background(AppColors.Pebble, RoundedCornerShape(16.dp)),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.White, RoundedCornerShape(16.dp))
                .border(2.dp, AppColors.Pebble, RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {},
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(AppColors.Mist, AppShapes.IconBox)
                    .border(2.dp, AppColors.Pebble, AppShapes.IconBox),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = AppColors.Ink2)
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = AppType.PlanName)
                Spacer(Modifier.height(2.dp))
                Text(meta, style = AppType.PlanMeta)
            }

            Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = AppColors.Pebble)
        }
    }
}

// ── Greeting ──

@Composable
private fun GreetingSection(state: HomeUiState) {
    Column(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp),
    ) {
        Text(
            "Good evening, ${state.userName}",
            style = AppType.GreetingSmall,
        )
        Spacer(Modifier.height(3.dp))
        Row {
            Text("What are you ", style = AppType.GreetingBig)
            Text(
                "planning?",
                style = AppType.GreetingBig.copy(
                    fontStyle = FontStyle.Italic,
                    color = AppColors.Coral,
                ),
            )
        }
    }
}

// ── AI Concierge Card ──

@Composable
private fun AiConciergeCard(onClick: () -> Unit = {}) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val offsetY by animateFloatAsState(
        targetValue = if (isPressed) 5f else 0f,
        animationSpec = tween(80),
    )
    val shadowHeight by animateFloatAsState(
        targetValue = if (isPressed) 0f else 5f,
        animationSpec = tween(60),
    )

    Box(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 14.dp)
            .fillMaxWidth()
            .graphicsLayer { translationY = offsetY * density },
    ) {
        // Shadow layer
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .matchParentSize()
                .offset(y = shadowHeight.dp)
                .background(AppColors.CoralDk, RoundedCornerShape(20.dp)),
        )

        // Card content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.Coral, RoundedCornerShape(20.dp))
                .border(2.dp, AppColors.CoralDk, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .padding(16.dp),
        ) {
            // Decorative shine circle
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .offset(x = 60.dp, y = (-48).dp)
                    .align(Alignment.TopEnd)
                    .background(
                        AppColors.White.copy(alpha = 0.13f),
                        CircleShape,
                    ),
            )

            Column {
                // AI badge
                Row(
                    modifier = Modifier
                        .background(
                            AppColors.White.copy(alpha = 0.25f),
                            AppShapes.Pill,
                        )
                        .padding(horizontal = 11.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(12.dp), tint = AppColors.White)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "AI Concierge",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp,
                            color = AppColors.White,
                        ),
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Title
                Row {
                    Text(
                        "Tell me what you're ",
                        style = AppType.AiTitle,
                    )
                    Text(
                        "planning",
                        style = AppType.AiTitle.copy(fontStyle = FontStyle.Italic),
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Subtitle
                Text(
                    "Date night? Visitors in town? Weekend plans?",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = AppColors.White.copy(alpha = 0.78f),
                    ),
                )

                Spacer(Modifier.height(12.dp))

                // Input bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            AppColors.White.copy(alpha = 0.2f),
                            AppShapes.AiInput,
                        )
                        .border(
                            2.dp,
                            AppColors.White.copy(alpha = 0.35f),
                            AppShapes.AiInput,
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "e.g. \"fun Saturday for two...\"",
                        modifier = Modifier.weight(1f),
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = AppColors.White.copy(alpha = 0.68f),
                        ),
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(AppColors.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Send, contentDescription = "Send", modifier = Modifier.size(14.dp), tint = AppColors.Coral)
                    }
                }
            }
        }
    }
}

// ── Header ──

@Composable
private fun HomeHeader(state: HomeUiState, onDebugOpen: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.White),
    ) {
        // Top row: logo + icons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDebugOpen,
                    ),
            ) {
                Text("lo", style = AppType.Logo)
                Text("c", style = AppType.Logo.copy(color = AppColors.Coral))
                Text("al", style = AppType.Logo)
            }

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(AppColors.Mist, AppShapes.Avatar)
                    .border(2.dp, AppColors.Pebble, AppShapes.Avatar),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Notifications, contentDescription = "Notifications", modifier = Modifier.size(18.dp), tint = AppColors.Ink2)
            }

            Spacer(Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(AppColors.Coral, AppShapes.Avatar)
                    .border(2.dp, AppColors.CoralDk, AppShapes.Avatar),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "J",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        color = AppColors.White,
                    ),
                )
            }
        }

        // Sub-header: location pill + streak badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Location pill
            Row(
                modifier = Modifier
                    .background(AppColors.SkyLt, AppShapes.Pill)
                    .border(2.dp, AppColors.Sky, AppShapes.Pill)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(AppColors.Sky, CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    state.locationName,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp,
                        color = AppColors.Sky,
                    ),
                )
            }

            // Streak badge
            Row(
                modifier = Modifier
                    .background(AppColors.SunLt, AppShapes.Pill)
                    .border(2.dp, AppColors.Sun, AppShapes.Pill)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(14.dp), tint = AppColors.SunDk)
                Spacer(Modifier.width(3.dp))
                Text(
                    "${state.streakDays} day streak!",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp,
                        color = AppColors.SunDk,
                    ),
                )
            }
        }

        // Bottom border
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(AppColors.Mist),
        )
    }
}

// ── Debug Menu ──

private data class MockLocation(val name: String, val lat: Double, val lng: Double)

private val MOCK_LOCATIONS = listOf(
    // Massachusetts
    MockLocation("Boston, MA", 42.3601, -71.0589),
    MockLocation("Cambridge, MA", 42.3736, -71.1097),
    MockLocation("Worcester, MA", 42.2626, -71.8023),
    MockLocation("Springfield, MA", 42.1015, -72.5898),
    MockLocation("Lowell, MA", 42.6334, -71.3162),
    MockLocation("New Bedford, MA", 41.6362, -70.9342),
    MockLocation("Pittsfield, MA", 42.4501, -73.2454),
    MockLocation("Cape Cod, MA", 41.6688, -70.2962),
    MockLocation("Northampton, MA", 42.3251, -72.6412),
    MockLocation("Salem, MA", 42.5195, -70.8967),
    // Out of state
    MockLocation("Providence, RI", 41.8240, -71.4128),
    MockLocation("New York, NY", 40.7128, -74.0060),
    MockLocation("Philadelphia, PA", 39.9526, -75.1652),
)

@Composable
private fun DebugMenuDialog(
    onDismiss: () -> Unit,
    onMockLocation: (lat: Double, lng: Double, name: String) -> Unit,
    onSetUserState: (String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(AppColors.White)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Header
            Text(
                "Debug Menu",
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = AppColors.Ink,
                ),
            )

            Spacer(Modifier.height(16.dp))

            // ── User State ──
            Text(
                "USER STATE",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.sp,
                    color = AppColors.Ink3,
                    letterSpacing = 1.sp,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("New User", "Returning", "Existing").forEach { label ->
                    Box(
                        modifier = Modifier
                            .background(AppColors.Mist, RoundedCornerShape(10.dp))
                            .clickable { onSetUserState(label.lowercase()) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            label,
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = AppColors.Ink,
                            ),
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Mock Location ──
            Text(
                "MOCK LOCATION",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.sp,
                    color = AppColors.Ink3,
                    letterSpacing = 1.sp,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MOCK_LOCATIONS.forEach { loc ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onMockLocation(loc.lat, loc.lng, loc.name) }
                            .background(AppColors.Snow)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            loc.name,
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = AppColors.Ink,
                            ),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Close button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.Coral)
                    .clickable { onDismiss() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Close",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = AppColors.White,
                    ),
                )
            }
        }
    }
}
