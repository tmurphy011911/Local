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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocalDining
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Museum
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Nightlife
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.SportsScore
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material.icons.outlined.Tour
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import coil3.compose.AsyncImage
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.domain.model.CarouselSection
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

@Composable
fun HomeScreen(viewModel: HomeViewModel, onEventClick: (Event) -> Unit = {}) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Snow),
    ) {
        HomeHeader(state)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            GreetingSection(state)
            AiConciergeCard()
            SegmentedControl(state, viewModel)
            CategoryRow(state, viewModel)

            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Loading events...", style = AppType.PlanMeta)
                }
            } else if (state.carousels.isEmpty() && state.error != null) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Could not load events", style = AppType.PlanMeta)
                }
            } else if (state.carousels.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No events found", style = AppType.PlanMeta)
                }
            } else {
                state.carousels.forEach { section ->
                    CarouselSectionView(section, onEventClick)
                }
            }

            PlansSection()
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Dynamic Carousel Section ──

@Composable
private fun CarouselSectionView(section: CarouselSection, onEventClick: (Event) -> Unit) {
    SectionHeader(section.title)
    EventCardRow(section.events, onEventClick)
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

private data class CategoryTile(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val activeBg: Color,
    val activeBorder: Color,
    val inactiveBgStart: Color,
    val inactiveBgEnd: Color,
)

private val upcomingCategories = listOf(
    CategoryTile("foryou", "For You", Icons.Outlined.AutoAwesome, AppColors.Coral, AppColors.CoralDk, Color(0xFFFFF3F1), Color(0xFFFFE4DF)),
    CategoryTile("music", "Music", Icons.Outlined.MusicNote, AppColors.Plum, AppColors.PlumDk, Color(0xFFF6F0FF), Color(0xFFEDE0FF)),
    CategoryTile("arts", "Arts", Icons.Outlined.Palette, AppColors.Sage, AppColors.SageDk, Color(0xFFF0FDF7), Color(0xFFD5F5E8)),
    CategoryTile("theatre", "Theatre", Icons.Outlined.TheaterComedy, AppColors.Sun, AppColors.SunDk, Color(0xFFFFFAF0), Color(0xFFFFF0CC)),
    CategoryTile("food", "Food", Icons.Outlined.LocalDining, AppColors.Orange, AppColors.OrangeDk, Color(0xFFFFF5EE), Color(0xFFFFE8D5)),
    CategoryTile("sports", "Sports", Icons.Outlined.SportsScore, AppColors.Sky, AppColors.SkyDk, Color(0xFFEEF5FF), Color(0xFFDCEEFF)),
    CategoryTile("other", "Other", Icons.Outlined.Category, AppColors.Plum, AppColors.PlumDk, Color(0xFFF6F0FF), Color(0xFFEDE0FF)),
)

private val alwaysOnCategories = listOf(
    CategoryTile("foryou", "For You", Icons.Outlined.AutoAwesome, AppColors.Coral, AppColors.CoralDk, Color(0xFFFFF3F1), Color(0xFFFFE4DF)),
    CategoryTile("museums", "Museums", Icons.Outlined.Museum, AppColors.Sage, AppColors.SageDk, Color(0xFFF0FDF7), Color(0xFFD5F5E8)),
    CategoryTile("tours", "Tours", Icons.Outlined.Tour, AppColors.Orange, AppColors.OrangeDk, Color(0xFFFFF5EE), Color(0xFFFFE8D5)),
    CategoryTile("libraries", "Libraries", Icons.Outlined.LocalLibrary, AppColors.Sky, AppColors.SkyDk, Color(0xFFEEF5FF), Color(0xFFDCEEFF)),
    CategoryTile("food", "Food", Icons.Outlined.LocalDining, AppColors.Sun, AppColors.SunDk, Color(0xFFFFFAF0), Color(0xFFFFF0CC)),
    CategoryTile("locations", "Locations", Icons.Outlined.Place, AppColors.Coral, AppColors.CoralDk, Color(0xFFFFF3F1), Color(0xFFFFE4DF)),
)

@Composable
private fun CategoryRow(state: HomeUiState, viewModel: HomeViewModel) {
    val categories = if (state.selectedSegment == HomeSegment.UPCOMING) upcomingCategories else alwaysOnCategories

    Row(
        modifier = Modifier
            .padding(top = 14.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
}

@Composable
private fun CategoryTileItem(
    tile: CategoryTile,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val offsetY by animateFloatAsState(
        targetValue = if (isPressed) 3f else 0f,
        animationSpec = tween(80),
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(modifier = Modifier.graphicsLayer { translationY = offsetY * density }) {
            // Shadow
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .offset(y = if (!isPressed) 3.dp else 1.dp)
                    .background(
                        if (isActive) tile.activeBorder else AppColors.Pebble,
                        AppShapes.CategoryBox,
                    ),
            )

            // Box
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .then(
                        if (isActive) {
                            Modifier.background(tile.activeBg, AppShapes.CategoryBox)
                        } else {
                            Modifier.background(
                                Brush.linearGradient(listOf(tile.inactiveBgStart, tile.inactiveBgEnd)),
                                AppShapes.CategoryBox,
                            )
                        },
                    )
                    .border(
                        2.dp,
                        if (isActive) tile.activeBorder else AppColors.Pebble,
                        AppShapes.CategoryBox,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    tile.icon,
                    contentDescription = tile.label,
                    modifier = Modifier.size(28.dp),
                    tint = if (isActive) AppColors.White else AppColors.Ink3,
                )
            }
        }

        Spacer(Modifier.height(7.dp))

        Text(
            tile.label,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp,
                color = AppColors.Ink3,
            ),
        )
    }
}

// ── Section Header ──

@Composable
private fun SectionHeader(title: String, link: String = "See all ->") {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 18.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = AppType.SectionTitle)
        Text(
            link,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.sp,
                color = AppColors.Coral,
            ),
        )
    }
}

// ── Event Card ──

private data class EventCardData(
    val title: String,
    val date: String,
    val distance: String,
    val tag: String,
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
            .height(192.dp)
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
                .height(192.dp)
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

                // Tag pill
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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

@Composable
private fun EventCardRow(events: List<Event>, onEventClick: (Event) -> Unit) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        events.forEach { event ->
            EventCard(data = event.toCardData(), onClick = { onEventClick(event) })
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

private fun Event.toCardData(): EventCardData {
    val (bgS, bgE) = categoryColors(category)
    return EventCardData(
        title = title.toTitleCase(),
        date = dateDisplay,
        distance = distanceDisplay,
        tag = category,
        isFree = isFree,
        imageUrl = imageUrl,
        bgStart = bgS,
        bgEnd = bgE,
        icon = categoryIcon(category),
    )
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
private fun AiConciergeCard() {
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
                    onClick = {},
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
private fun HomeHeader(state: HomeUiState) {
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
            Row(modifier = Modifier.weight(1f)) {
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
