package live.localapp.local.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import coil3.compose.AsyncImage
import live.localapp.local.domain.model.Event
import live.localapp.local.ui.chat.ChatScreen
import live.localapp.local.ui.chat.ChatViewModel
import live.localapp.local.ui.detail.EventDetailScreen
import live.localapp.local.ui.home.CardTransitionInfo
import live.localapp.local.ui.home.HomeScreen
import live.localapp.local.ui.home.HomeViewModel
import live.localapp.local.domain.repository.VenueWithMeta
import live.localapp.local.ui.places.PlacesScreen
import live.localapp.local.ui.places.PlacesViewModel
import live.localapp.local.ui.places.VenueDetailPage
import live.localapp.local.ui.places.VenueDetailSheet
import live.localapp.local.ui.plans.PlansScreen
import live.localapp.local.ui.plans.PlansViewModel
import live.localapp.local.domain.repository.EventRepository
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.launch
import live.localapp.local.ui.progress.ProgressScreen
import live.localapp.local.ui.progress.ProgressViewModel
import live.localapp.local.platform.closeChatSheet
import live.localapp.local.platform.hasNativeHeroTransition
import live.localapp.local.platform.hasNativeTabBar
import live.localapp.local.platform.notifyTabChanged
import live.localapp.local.platform.openChatSheet
import live.localapp.local.platform.setupNativeTabBarCallbacks
import live.localapp.local.platform.setTabBarUserInitial
import live.localapp.local.platform.requestHeroCollapse
import live.localapp.local.platform.requestHeroExpand
import live.localapp.local.platform.showNativeEventDetail
import live.localapp.local.platform.showNativeVenueDetail
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts
import org.koin.mp.KoinPlatform

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Home),
    PLACES("Places", Icons.Outlined.Place),
    PLANS("Plans", Icons.Outlined.CalendarMonth),
    CHAT("Chat", Icons.Outlined.AutoAwesome), // AI concierge — not shown in tab bar
    PROFILE("Profile", Icons.Outlined.Person),
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(userLat: Double? = null, userLng: Double? = null, onLogout: () -> Unit = {}) {
    val koin = KoinPlatform.getKoin()
    val scope = rememberCoroutineScope()
    val eventRepo: EventRepository = remember { koin.get() }
    val density = LocalDensity.current.density
    var selectedTab by remember { mutableStateOf(Tab.HOME) }
    var detailEvent by remember { mutableStateOf<Event?>(null) }
    var showChat by remember { mutableStateOf(false) }

    // Hoist HomeViewModel so data starts loading immediately (for splash screen)
    val homeVm: HomeViewModel = viewModel {
        koin.get<HomeViewModel>().also { vm ->
            if (userLat != null && userLng != null) {
                vm.setUserLocation(userLat, userLng)
            }
        }
    }
    val homeState by homeVm.uiState.collectAsState()

    // Hoist PlacesViewModel so VenueDetailPage overlay can observe its state
    val placesVm: PlacesViewModel = viewModel {
        koin.get<PlacesViewModel>().also { vm ->
            if (userLat != null && userLng != null) vm.setUserLocation(userLat, userLng)
        }
    }
    val placesState by placesVm.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.White)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // Main tab content — fills entire screen, tab bar overlays on top
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    Tab.HOME -> {
                        HomeScreen(
                            homeVm,
                            onEventClick = { event, transitionInfo ->
                                homeVm.recordEventClick(event.id, event.subcategory ?: event.category)
                                if (hasNativeHeroTransition) {
                                    // iOS: show native SwiftUI detail with transition
                                    val bounds = transitionInfo?.bounds
                                    showNativeEventDetail(
                                        event = event,
                                        cardX = bounds?.let { it.left / density } ?: 0f,
                                        cardY = bounds?.let { it.top / density } ?: 0f,
                                        cardW = bounds?.let { it.width / density } ?: 0f,
                                        cardH = bounds?.let { it.height / density } ?: 0f,
                                        cardImageUrl = transitionInfo?.imageUrl,
                                        onDismissed = { /* state cleanup if needed */ },
                                        onFavorited = { eventId ->
                                            homeVm.recordEventFavorite(eventId, event.subcategory ?: event.category)
                                        },
                                    )
                                } else {
                                    detailEvent = event
                                }
                            },
                            onConciergeClick = { showChat = true },
                            onProfileClick = { selectedTab = Tab.PROFILE },
                            onDebugLocationChange = { lat, lng, name ->
                                placesVm.debugSetLocation(lat, lng, name)
                            },
                            onFavorite = { event ->
                                homeVm.toggleEventFavorite(event.id, event.subcategory ?: event.category)
                            },
                        )
                    }
                    Tab.CHAT -> {
                        // Chat is now a bottom sheet, not a tab — show home instead
                        showChat = true
                        selectedTab = Tab.HOME
                    }
                    Tab.PLACES -> {
                        placesVm.ensureLoaded()
                        val initial = homeState.userName.firstOrNull()?.uppercase() ?: ""
                        PlacesScreen(
                            placesVm,
                            onProfileClick = { selectedTab = Tab.PROFILE },
                            userInitial = initial,
                        )
                    }
                    Tab.PROFILE -> {
                        val vm: ProgressViewModel = viewModel { koin.get<ProgressViewModel>() }
                        ProgressScreen(vm, onLogout = onLogout)
                    }
                    Tab.PLANS -> {
                        val plansVm: PlansViewModel = viewModel { koin.get<PlansViewModel>() }
                        LaunchedEffect(selectedTab) { plansVm.refresh() }
                        PlansScreen(
                            viewModel = plansVm,
                            onNewPlan = { showChat = true },
                            userInitial = homeState.userName.firstOrNull()?.uppercase() ?: "",
                            onProfileClick = { selectedTab = Tab.PROFILE },
                            onEventClick = { eventId ->
                                scope.launch {
                                    val event = eventRepo.getEvent(eventId)
                                    if (event != null) {
                                        detailEvent = event
                                    }
                                }
                            },
                        )
                    }
                    else -> {
                        Box(
                            modifier = Modifier.fillMaxSize().background(AppColors.Snow),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                selectedTab.label,
                                style = TextStyle(
                                    fontFamily = AppFonts.Heading,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 24.sp,
                                    color = AppColors.Ink3,
                                ),
                            )
                        }
                    }
                }
            }

            }

            // Floating tab bar — overlays content at bottom
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                val tabInitial = homeState.userName.firstOrNull()?.uppercase() ?: ""
                BottomTabBar(selectedTab, onSelect = { selectedTab = it }, onChatClick = { showChat = true }, userInitial = tabInitial)
            }

        // Venue detail
        if (placesState.selectedVenue != null) {
            if (placesState.selectedVenueEvents.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxSize().background(AppColors.White)) {
                    VenueDetailPage(
                        venueWithMeta = placesState.selectedVenue!!,
                        events = placesState.selectedVenueEvents,
                        isLoading = placesState.isSheetLoading,
                        onBack = { placesVm.dismissSheet() },
                    )
                }
            } else {
                VenueDetailSheet(
                    venueWithMeta = placesState.selectedVenue!!,
                    onDismiss = { placesVm.dismissSheet() },
                )
            }
        }

        // Event detail overlay
        if (detailEvent != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppColors.White),
            ) {
                EventDetailScreen(
                    event = detailEvent!!,
                    onBack = { detailEvent = null },
                    onShareTracked = { eventId ->
                        val cat = detailEvent?.let { it.subcategory ?: it.category }
                        homeVm.recordEventShare(eventId, cat)
                    },
                    onFavoriteTracked = { eventId ->
                        val cat = detailEvent?.let { it.subcategory ?: it.category }
                        homeVm.recordEventFavorite(eventId, cat)
                    },
                )
            }
        }

        // Chat bottom sheet
        if (showChat) {
            val chatVm: ChatViewModel = viewModel { koin.get<ChatViewModel>() }
            val chatSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

            // Animated dismiss helper
            val dismissChat: ((() -> Unit)?) -> Unit = { extra ->
                scope.launch {
                    chatVm.clearSuccessState()
                    chatSheetState.hide()
                    showChat = false
                    extra?.invoke()
                }
            }

            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = {
                    chatVm.clearSuccessState()
                    showChat = false
                },
                sheetState = chatSheetState,
                containerColor = AppColors.Coral,
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.Coral)
                            .padding(top = 10.dp, bottom = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .background(Color.White.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
                        )
                    }
                },
            ) {
                val bounceScale = remember { Animatable(0.95f) }
                LaunchedEffect(Unit) {
                    bounceScale.animateTo(
                        1f,
                        spring(dampingRatio = 0.6f, stiffness = 400f),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.92f)
                        .graphicsLayer {
                            scaleX = bounceScale.value
                            scaleY = bounceScale.value
                        },
                ) {
                    ChatScreen(
                        viewModel = chatVm,
                        onBack = { dismissChat(null) },
                        onNavigateToPlans = { dismissChat { selectedTab = Tab.PLANS } },
                        onNavigateHome = { dismissChat { selectedTab = Tab.HOME } },
                    )
                }
            }
        }
    }
}

/** Tabs rendered in the bar: 4 standard + AI concierge on the right. */
private val NAV_TABS = listOf(Tab.HOME, Tab.PLACES, Tab.PLANS, Tab.PROFILE)

@Composable
private fun BottomTabBar(selectedTab: Tab, onSelect: (Tab) -> Unit, onChatClick: () -> Unit = {}, userInitial: String = "") {
    val haptic = LocalHapticFeedback.current
    val pillShape = RoundedCornerShape(22.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 4.dp),
    ) {
        // Floating pill — centered, not full width
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 20.dp, end = 72.dp) // leave room for AI FAB on right
                .height(52.dp)
                .background(
                    Color(0xFFF0EDF6).copy(alpha = 0.75f),
                    pillShape,
                )
                .border(1.dp, Color(0xFFE0DCE8).copy(alpha = 0.6f), pillShape)
                .clip(pillShape)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NAV_TABS.forEach { tab ->
                val isSelected = tab == selectedTab

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .then(
                            if (isSelected) {
                                Modifier.background(
                                    Color.White.copy(alpha = 0.85f),
                                    RoundedCornerShape(18.dp),
                                ).border(0.5.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                            } else Modifier,
                        )
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(tab)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (tab == Tab.PROFILE && userInitial.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(
                                        if (isSelected) AppColors.Coral else AppColors.Ink3,
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    userInitial,
                                    style = TextStyle(
                                        fontFamily = AppFonts.Body,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 10.sp,
                                        color = Color.White,
                                    ),
                                )
                            }
                        } else {
                            Icon(
                                tab.icon,
                                contentDescription = tab.label,
                                modifier = Modifier.size(20.dp),
                                tint = if (isSelected) AppColors.Coral else AppColors.Ink3,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            tab.label,
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                                fontSize = 8.sp,
                                color = if (isSelected) AppColors.Coral else AppColors.Ink3,
                            ),
                        )
                    }
                }
            }
        }

        // Floating AI button — separate, bottom right
        val aiSelected = selectedTab == Tab.CHAT
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp)
                .size(52.dp)
                .background(
                    Brush.verticalGradient(
                        if (aiSelected) listOf(AppColors.CoralDk, AppColors.Coral)
                        else listOf(AppColors.Coral, Color(0xFFFF8787)),
                    ),
                    CircleShape,
                )
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onChatClick()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = "AI Concierge",
                modifier = Modifier.size(24.dp),
                tint = Color.White,
            )
        }
    }
}

// ── Bootstrap splash screen ──────────────────────────────────────────────────

private val SPLASH_WORDS = listOf(
    "trips" to AppColors.Coral,
    "dates" to AppColors.Plum,
    "weekends" to AppColors.Sage,
    "days" to AppColors.Sky,
    "everything" to AppColors.Sun,
)

@Composable
private fun BootstrapSplash() {
    // Word cycling
    var wordIndex by remember { mutableStateOf(0) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        delay(800)
        while (true) {
            wordIndex = (wordIndex + 1) % SPLASH_WORDS.size
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            delay(850)
        }
    }

    // Progress bar — simple tween, no external dependencies
    val progress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 5000),
    )

    val (currentWord, currentColor) = SPLASH_WORDS[wordIndex]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFFFFF8F8), Color(0xFFFAF8FC), Color(0xFFEEF6FF)),
                    radius = 800f,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(AppColors.Coral, RoundedCornerShape(30.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "L",
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Black,
                        fontSize = 56.sp,
                        color = AppColors.White,
                    ),
                )
            }

            Spacer(Modifier.height(32.dp))

            Text(
                "plan",
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                    color = AppColors.Ink,
                ),
            )

            Text(
                currentWord,
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Black,
                    fontSize = 36.sp,
                    fontStyle = FontStyle.Italic,
                    color = currentColor,
                ),
            )

            Text(
                "like a local",
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                    color = AppColors.Ink,
                ),
            )

            Spacer(Modifier.height(52.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.Mist),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(AppColors.Coral, AppColors.Sun, AppColors.Sage),
                            ),
                        ),
                )
            }
        }
    }
}
