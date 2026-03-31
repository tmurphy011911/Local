package live.localapp.local.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
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
import live.localapp.local.domain.repository.UserPreferencesRepository
import live.localapp.local.ui.progress.CelebrationEvent
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
    var detailFromPlans by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    var showLoginPrompt by remember { mutableStateOf(false) }
    var pendingChatMessage by remember { mutableStateOf<String?>(null) }
    var chatOpenCount by remember { mutableStateOf(0) }
    val userPrefsRepo: UserPreferencesRepository = remember { koin.get() }

    // Gate chat behind auth
    val openChatOrLogin: () -> Unit = {
        val userId = userPrefsRepo.getLocalUserId()
        if (userId != null) {
            showChat = true
        } else {
            showLoginPrompt = true
        }
    }

    // Hoist HomeViewModel so data starts loading immediately (for splash screen)
    val homeVm: HomeViewModel = viewModel {
        koin.get<HomeViewModel>().also { vm ->
            if (userLat != null && userLng != null) {
                vm.setUserLocation(userLat, userLng)
            }
        }
    }
    val homeState by homeVm.uiState.collectAsState()

    // Hoist ProgressViewModel so badge celebrations show on any screen
    val progressVm: ProgressViewModel = viewModel { koin.get<ProgressViewModel>() }
    val progressState by progressVm.uiState.collectAsState()

    // Hoist PlacesViewModel so VenueDetailPage overlay can observe its state
    val placesVm: PlacesViewModel = viewModel {
        koin.get<PlacesViewModel>().also { vm ->
            if (userLat != null && userLng != null) vm.setUserLocation(userLat, userLng)
        }
    }
    val placesState by placesVm.uiState.collectAsState()

    // Observe chat success state to defer celebrations
    val chatVm: ChatViewModel = viewModel { koin.get<ChatViewModel>() }
    val chatState by chatVm.uiState.collectAsState()
    val deferCelebrations = chatState.showPlanSuccess

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
                                            homeVm.toggleEventFavorite(eventId, event.subcategory ?: event.category)
                                        },
                                        showDirections = false,
                                    )
                                } else {
                                    detailFromPlans = false
                                    detailEvent = event
                                }
                            },
                            onConciergeClick = { openChatOrLogin() },
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
                        openChatOrLogin()
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
                        LaunchedEffect(selectedTab) { progressVm.refresh() }
                        ProgressScreen(
                            progressVm,
                            onLogout = onLogout,
                            locationName = homeState.locationName,
                            streakDays = homeState.streakDays,
                        )
                    }
                    Tab.PLANS -> {
                        val plansVm: PlansViewModel = viewModel { koin.get<PlansViewModel>() }
                        LaunchedEffect(selectedTab) { plansVm.refresh() }
                        PlansScreen(
                            viewModel = plansVm,
                            onNewPlan = { openChatOrLogin() },
                            userInitial = homeState.userName.firstOrNull()?.uppercase() ?: "",
                            onProfileClick = { selectedTab = Tab.PROFILE },
                            locationName = homeState.locationName,
                            streakDays = homeState.streakDays,
                            onBuildPlanFromEvent = { eventTitle ->
                                pendingChatMessage = "Build a plan around $eventTitle"
                                chatOpenCount++
                                openChatOrLogin()
                            },
                            onBuildPlanFromTemplate = { templateMessage ->
                                pendingChatMessage = templateMessage
                                chatOpenCount++
                                openChatOrLogin()
                            },
                            onEventClick = { eventId ->
                                scope.launch {
                                    val event = eventRepo.getEvent(eventId) ?: return@launch
                                    if (hasNativeHeroTransition) {
                                        showNativeEventDetail(
                                            event = event,
                                            cardX = 0f, cardY = 0f, cardW = 0f, cardH = 0f,
                                            cardImageUrl = event.imageUrl,
                                            onDismissed = {},
                                            onFavorited = { id -> homeVm.toggleEventFavorite(id, event.subcategory ?: event.category) },
                                            showDirections = true,
                                        )
                                    } else {
                                        detailFromPlans = true
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
                BottomTabBar(selectedTab, onSelect = { selectedTab = it }, onChatClick = { openChatOrLogin() }, userInitial = tabInitial)
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
            // On iOS, redirect to native SwiftUI detail
            if (hasNativeHeroTransition) {
                LaunchedEffect(detailEvent) {
                    val event = detailEvent ?: return@LaunchedEffect
                    detailEvent = null // clear immediately so Compose overlay doesn't show
                    showNativeEventDetail(
                        event = event,
                        cardX = 0f, cardY = 0f, cardW = 0f, cardH = 0f,
                        cardImageUrl = event.imageUrl,
                        onDismissed = { },
                        onFavorited = { id -> homeVm.toggleEventFavorite(id, event.subcategory ?: event.category) },
                        showDirections = detailFromPlans,
                    )
                }
            } else {
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
                        homeVm.toggleEventFavorite(eventId, cat)
                    },
                    initialFavorited = detailEvent?.id in homeState.favoriteEventIds,
                )
            }
            }
        }

        // Chat bottom sheet
        if (showChat) {
            val chatVm: ChatViewModel = viewModel { koin.get<ChatViewModel>() }
            val chatSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

            // Auto-send pending message when chat opens from event/template
            LaunchedEffect(chatOpenCount) {
                val msg = pendingChatMessage ?: return@LaunchedEffect
                delay(800) // let the sheet animate open and chat initialize
                pendingChatMessage = null
                chatVm.sendMessage(msg)
            }

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
                        onNavigateToPlans = {
                            dismissChat {
                                selectedTab = Tab.PLANS
                                // Force refresh even if already on Plans tab
                                val plansVm = koin.get<PlansViewModel>()
                                plansVm.refresh()
                            }
                        },
                        onNavigateHome = { dismissChat { selectedTab = Tab.HOME } },
                        onLoginRequired = {
                            // Close chat and navigate to auth
                            dismissChat {
                                // TODO: show auth sheet or navigate to login
                                // For now, show an alert-style message
                            }
                        },
                    )
                }
            }
        }

        // ── Login Required Overlay ──
        if (showLoginPrompt) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { showLoginPrompt = false },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(AppColors.White)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Sign in to use Yocal",
                        style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = AppColors.Ink),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Create an account to build plans, save events, and get personalized recommendations.",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AppColors.Ink3, lineHeight = 20.sp),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(AppColors.Coral)
                            .clickable {
                                showLoginPrompt = false
                                onLogout() // triggers re-auth flow
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Sign in", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = AppColors.White))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Maybe later",
                        modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { showLoginPrompt = false }),
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.Ink3),
                    )
                }
            }
        }

        // ── Global Badge Celebration Overlays ──
        // Defer celebrations while plan success overlay is showing
        if (!deferCelebrations) {
            progressState.celebration?.let { event ->
                when (event) {
                    is CelebrationEvent.MilestoneEarned -> GlobalMilestoneToast(
                        event = event,
                        onDismiss = { progressVm.dismissCelebration() },
                        onTap = { selectedTab = Tab.PROFILE },
                    )
                    is CelebrationEvent.StatusLevelUp -> GlobalLevelUpOverlay(
                        event = event,
                        onDismiss = { progressVm.dismissCelebration() },
                    )
                    is CelebrationEvent.NewState -> GlobalNewStateToast(
                        event = event,
                        onDismiss = { progressVm.dismissCelebration() },
                        onTap = { selectedTab = Tab.PROFILE },
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

// ═══════════════════════════════════════
// ── Global Celebration Overlays ──
// ═══════════════════════════════════════

private fun stateAbbrevToFull(abbrev: String): String = when (abbrev.uppercase()) {
    "MA" -> "Massachusetts"; "ME" -> "Maine"; "NH" -> "New Hampshire"
    "VT" -> "Vermont"; "RI" -> "Rhode Island"; "CT" -> "Connecticut"
    "NY" -> "New York"; "NJ" -> "New Jersey"; "PA" -> "Pennsylvania"
    "CA" -> "California"; "TX" -> "Texas"; "FL" -> "Florida"
    else -> abbrev
}

@Composable
private fun GlobalMilestoneToast(event: CelebrationEvent.MilestoneEarned, onDismiss: () -> Unit, onTap: () -> Unit = {}) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
        delay(3500)
        visible = false
        delay(300)
        onDismiss()
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { -it * 2 },
            animationSpec = spring(dampingRatio = 0.7f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
        ) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it * 2 }, animationSpec = tween(250)) + fadeOut(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 56.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(AppColors.Ink)
                    .clickable { onDismiss(); onTap() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(AppColors.SkyLt.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Star,
                        contentDescription = null,
                        tint = AppColors.Sun,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${event.milestone} ${event.action.label}!",
                        style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AppColors.White),
                    )
                    Text(
                        "+${formatNumber(event.points)} PTS",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = AppColors.Sun, letterSpacing = 0.5.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GlobalLevelUpOverlay(event: CelebrationEvent.StatusLevelUp, onDismiss: () -> Unit) {
    val scale = remember { Animatable(0.6f) }
    val contentAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        contentAlpha.animateTo(1f, tween(300))
        scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = androidx.compose.animation.core.Spring.StiffnessLow))
    }

    // Fully opaque background — no bleed-through
    Box(
        modifier = Modifier.fillMaxSize()
            .background(Color(0xFF1A1523))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value; alpha = contentAlpha.value }
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "LEVEL UP",
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = AppColors.Coral,
                    letterSpacing = 4.sp,
                ),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                event.newTier.label,
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Black,
                    fontSize = 52.sp,
                    color = AppColors.White,
                    letterSpacing = (-2).sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 56.sp,
                ),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "${formatNumber(event.totalPoints)} total points",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = AppColors.White.copy(alpha = 0.6f),
                ),
            )
            Spacer(Modifier.height(40.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(30.dp))
                    .background(AppColors.Coral)
                    .clickable { onDismiss() }
                    .padding(horizontal = 40.dp, vertical = 16.dp),
            ) {
                Text(
                    "Share Achievement",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        color = AppColors.White,
                    ),
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "TAP ANYWHERE TO DISMISS",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = AppColors.White.copy(alpha = 0.3f),
                    letterSpacing = 1.sp,
                ),
            )
        }
    }
}

private fun formatNumber(n: Int): String {
    if (n < 1000) return n.toString()
    val s = n.toString()
    val sb = StringBuilder()
    s.forEachIndexed { i, c ->
        if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
        sb.append(c)
    }
    return sb.toString()
}

@Composable
private fun GlobalNewStateToast(event: CelebrationEvent.NewState, onDismiss: () -> Unit, onTap: () -> Unit = {}) {
    var visible by remember { mutableStateOf(false) }
    val stateFullName = stateAbbrevToFull(event.state)

    LaunchedEffect(Unit) {
        visible = true
        delay(3500)
        visible = false
        delay(300)
        onDismiss()
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it * 2 }, animationSpec = spring(dampingRatio = 0.7f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it * 2 }, animationSpec = tween(250)) + fadeOut(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 56.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(AppColors.Sage, AppColors.SageDk)))
                    .clickable { onDismiss(); onTap() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Place, contentDescription = null, tint = AppColors.White, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Welcome to $stateFullName!", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = AppColors.White))
                    Text("Start exploring to earn milestones", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AppColors.White.copy(alpha = 0.8f)))
                }
            }
        }
    }
}
