package live.localapp.local.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import live.localapp.local.ui.common.pressable
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
import live.localapp.local.ui.plans.PlanDetailScreen
import live.localapp.local.ui.plans.PlansScreen
import live.localapp.local.ui.plans.PlansViewModel
import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.repository.EventRepository
import live.localapp.local.domain.repository.PlanRepository
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.launch
import live.localapp.local.ui.progress.ProgressScreen
import live.localapp.local.domain.repository.UserPreferencesRepository
import live.localapp.local.ui.progress.CelebrationEvent
import live.localapp.local.ui.progress.ProgressViewModel
import live.localapp.local.ui.taste.TasteProfileScreen
import live.localapp.local.ui.taste.TasteProfileViewModel
import live.localapp.local.platform.closeChatSheet
import live.localapp.local.platform.hasNativeHeroTransition
import live.localapp.local.platform.hasNativeTabBar
import live.localapp.local.platform.notifyTabChanged
import live.localapp.local.platform.openChatSheet
import live.localapp.local.platform.setupNativeTabBarCallbacks
import live.localapp.local.platform.setTabBarUserInitial
import live.localapp.local.platform.requestHeroCollapse
import live.localapp.local.platform.checkClipboardForDeepLink
import live.localapp.local.platform.checkSubscriptionStatus
import live.localapp.local.platform.purchaseSubscription
import live.localapp.local.platform.clearClipboardDeepLink
import live.localapp.local.platform.setDeepLinkCallback
import live.localapp.local.platform.presentShareSheet
import live.localapp.local.platform.showNativePlanDetail
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
    val venueRepo: live.localapp.local.domain.repository.VenueRepository = remember { koin.get() }
    val planRepo: PlanRepository = remember { koin.get() }
    val density = LocalDensity.current.density
    var selectedTab by remember { mutableStateOf(Tab.HOME) }
    var detailEvent by remember { mutableStateOf<Event?>(null) }
    var detailFromPlans by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    var showLoginPrompt by remember { mutableStateOf(false) }
    var pendingChatMessage by remember { mutableStateOf<String?>(null) }
    var chatOpenCount by remember { mutableStateOf(0) }
    var showTasteProfile by remember { mutableStateOf(false) }
    var selectedPlan by remember { mutableStateOf<Plan?>(null) }
    var sharedPlan by remember { mutableStateOf<Plan?>(null) }
    var sharedEvent by remember { mutableStateOf<live.localapp.local.domain.model.Event?>(null) }
    var sharedVenue by remember { mutableStateOf<live.localapp.local.domain.model.Venue?>(null) }
    val userPrefsRepo: UserPreferencesRepository = remember { koin.get() }
    val isLoggedIn = userPrefsRepo.getLocalUserId() != null

    // Gate chat behind auth + sync location (defined as a lambda referencing hoisted VMs below)

    // Hoist HomeViewModel so data starts loading immediately (for splash screen)
    val homeVm: HomeViewModel = viewModel {
        koin.get<HomeViewModel>().also { vm ->
            if (userLat != null && userLng != null) {
                vm.setUserLocation(userLat, userLng)
            }
        }
    }
    val homeState by homeVm.uiState.collectAsState()

    // Auto-fetch GPS location on launch if mode is "current" and permission granted
    live.localapp.local.platform.RequestLocationEffect(
        active = homeState.locationMode == live.localapp.local.ui.home.LocationMode.CURRENT_LOCATION,
        onLocation = { lat, lng ->
            homeVm.setUserLocation(lat, lng)
            homeVm.setHasLocationPermission(true)
        },
        onDenied = {
            homeVm.setHasLocationPermission(false)
        },
    )

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

    // Helper: check if user already has this plan (match by title + city)
    suspend fun userAlreadyHasPlan(plan: Plan): Boolean {
        val existing = planRepo.getPlans()
        return existing.any { it.title == plan.title && it.city == plan.city }
    }

    // Check clipboard for deferred deep link on launch
    LaunchedEffect(Unit) {
        val deepLink = checkClipboardForDeepLink() ?: return@LaunchedEffect
        println("DeepLink: found clipboard link: $deepLink")
        clearClipboardDeepLink()
        when {
            deepLink.startsWith("/p/") -> {
                val token = deepLink.removePrefix("/p/")
                val plan = planRepo.getSharedPlan(token)
                if (plan != null) {
                    if (userAlreadyHasPlan(plan)) {
                        selectedPlan = plan
                    } else {
                        sharedPlan = plan
                    }
                }
            }
            deepLink.startsWith("/e/") -> {
                val eventId = deepLink.removePrefix("/e/")
                val event = eventRepo.getEvent(eventId)
                if (event != null) {
                    sharedEvent = event
                }
            }
            deepLink.startsWith("/v/") -> {
                val venueId = deepLink.removePrefix("/v/")
                val venues = venueRepo.getVenuesByIds(listOf(venueId))
                val venue = venues.firstOrNull()
                if (venue != null) {
                    sharedVenue = venue
                }
            }
        }
    }

    // Listen for Universal Link deep links while app is running (iOS bridge)
    LaunchedEffect(Unit) {
        setDeepLinkCallback { path ->
            scope.launch {
                when {
                    path.length == 12 || !path.contains("-") -> {
                        // Plan share token (12 chars, no dashes)
                        val plan = planRepo.getSharedPlan(path)
                        if (plan != null) {
                            clearClipboardDeepLink()
                            if (userAlreadyHasPlan(plan)) {
                                selectedPlan = plan
                            } else {
                                sharedPlan = plan
                            }
                        }
                    }
                }
            }
        }
    }

    // Sync subscription status from RevenueCat on launch
    LaunchedEffect(Unit) {
        checkSubscriptionStatus { isPro, expiryDate ->
            val gate = live.localapp.local.ui.chat.PremiumGate()
            gate.hasPaidSubscription = isPro
            if (expiryDate != null) {
                gate.paidSubscriptionExpiry = expiryDate.take(10) // ISO date
            }
            println("RevenueCat sync: isPro=$isPro expiry=$expiryDate")
        }
    }

    // After auth completes, check if there's a pending invite/favorite to process
    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn) return@LaunchedEffect

        // Pending plan invite
        val plansVm: PlansViewModel = koin.get()
        val pendingPlan = plansVm.uiState.value.pendingAcceptAfterAuth
        if (pendingPlan != null) {
            println("DeepLink: processing pending invite after auth: '${pendingPlan.title}'")
            plansVm.acceptInvite(pendingPlan)
            selectedTab = Tab.PLANS
            return@LaunchedEffect
        }

        // Pending event favorite
        val settings = com.russhwolf.settings.Settings()
        val pendingEventId = settings.getStringOrNull("pending_favorite_event")
        if (pendingEventId != null) {
            println("DeepLink: processing pending event favorite after auth: $pendingEventId")
            val communityRepo = koin.get<live.localapp.local.domain.repository.CommunityInterestRepository>()
            communityRepo.recordFavorite(pendingEventId, live.localapp.local.domain.repository.InteractionType.EVENT)
            settings.remove("pending_favorite_event")
            selectedTab = Tab.HOME
            return@LaunchedEffect
        }

        // Pending venue favorite
        val pendingVenueId = settings.getStringOrNull("pending_favorite_venue")
        if (pendingVenueId != null) {
            println("DeepLink: processing pending venue favorite after auth: $pendingVenueId")
            val communityRepo = koin.get<live.localapp.local.domain.repository.CommunityInterestRepository>()
            val venueCategory = settings.getStringOrNull("pending_favorite_venue_category")
            communityRepo.recordFavorite(pendingVenueId, live.localapp.local.domain.repository.InteractionType.PLACE, venueCategory)
            settings.remove("pending_favorite_venue")
            settings.remove("pending_favorite_venue_category")
            selectedTab = Tab.PLACES
            return@LaunchedEffect
        }
    }

    val openChatOrLogin: () -> Unit = {
        val userId = userPrefsRepo.getLocalUserId()
        if (userId != null) {
            chatVm.setCurrentLocation(homeState.userLat ?: 42.3601, homeState.userLng ?: -71.0589, homeState.locationName)
            showChat = true
        } else {
            showLoginPrompt = true
        }
    }

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
                        // Sync location and streak from home state
                        if (homeState.locationName.isNotBlank()) {
                            placesVm.setLocationName(homeState.locationName)
                        }
                        placesVm.setStreakDays(homeState.streakDays)
                        val initial = homeState.userName.firstOrNull()?.uppercase() ?: ""
                        PlacesScreen(
                            placesVm,
                            onProfileClick = { selectedTab = Tab.PROFILE },
                            userInitial = initial,
                            onVenueClickWithBounds = if (hasNativeHeroTransition) { venue, bounds ->
                                showNativeVenueDetail(
                                    venueWithMeta = venue,
                                    cardX = bounds.left / density,
                                    cardY = bounds.top / density,
                                    cardW = bounds.width / density,
                                    cardH = bounds.height / density,
                                    onDismissed = {},
                                )
                            } else null,
                        )
                    }
                    Tab.PROFILE -> {
                        LaunchedEffect(selectedTab) { if (isLoggedIn) progressVm.refresh() }
                        ProgressScreen(
                            progressVm,
                            onLogout = onLogout,
                            onTasteProfile = { showTasteProfile = true },
                            onSignIn = { showLoginPrompt = true },
                            onNameChanged = { name -> homeVm.setUserName(name) },
                            isLoggedIn = isLoggedIn,
                            locationName = homeState.locationName,
                            streakDays = homeState.streakDays,
                        )
                    }
                    Tab.PLANS -> {
                        val plansVm: PlansViewModel = viewModel { koin.get<PlansViewModel>() }
                        LaunchedEffect(selectedTab) { if (isLoggedIn) plansVm.refresh() }
                        PlansScreen(
                            viewModel = plansVm,
                            onNewPlan = { openChatOrLogin() },
                            onPlanClick = { plan, bounds ->
                                if (hasNativeHeroTransition) {
                                    showNativePlanDetail(
                                        plan = plan,
                                        cardX = bounds.left / density,
                                        cardY = bounds.top / density,
                                        cardW = bounds.width / density,
                                        cardH = bounds.height / density,
                                        onDismissed = {},
                                        onShare = {
                                            scope.launch {
                                                val token = planRepo.getShareToken(plan.id) ?: plan.id
                                                val totalStops = plan.days.sumOf { it.slots.size }
                                                val venueNames = plan.days.flatMap { d -> d.slots.map { it.name } }.take(3).joinToString(", ")
                                                val text = "${plan.title} — ${plan.city} · $totalStops stops · $venueNames"
                                                presentShareSheet(text, "https://localapp.live/p/$token")
                                            }
                                        },
                                    )
                                } else {
                                    selectedPlan = plan
                                }
                            },
                            onSharePlan = { plan ->
                                scope.launch {
                                    val token = planRepo.getShareToken(plan.id) ?: plan.id
                                    val totalStops = plan.days.sumOf { it.slots.size }
                                    val venueNames = plan.days.flatMap { d -> d.slots.map { it.name } }.take(3).joinToString(", ")
                                    val text = "${plan.title} — ${plan.city} · $totalStops stops · $venueNames"
                                    presentShareSheet(text, "https://localapp.live/p/$token")
                                }
                            },
                            userInitial = homeState.userName.firstOrNull()?.uppercase() ?: "",
                            onProfileClick = { selectedTab = Tab.PROFILE },
                            onSignIn = { showLoginPrompt = true },
                            isLoggedIn = isLoggedIn,
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
                            onInviteClick = { plan -> sharedPlan = plan },
                            onEventClick = { eventId, bounds ->
                                scope.launch {
                                    val event = eventRepo.getEvent(eventId) ?: return@launch
                                    if (hasNativeHeroTransition) {
                                        showNativeEventDetail(
                                            event = event,
                                            cardX = bounds.left / density,
                                            cardY = bounds.top / density,
                                            cardW = bounds.width / density,
                                            cardH = bounds.height / density,
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

        // Venue detail — slide up / slide down transition
        AnimatedVisibility(
            visible = placesState.selectedVenue != null && placesState.selectedVenueEvents.isNotEmpty(),
            enter = slideInVertically(animationSpec = tween(320)) { it } + fadeIn(animationSpec = tween(220)),
            exit = slideOutVertically(animationSpec = tween(280)) { it } + fadeOut(animationSpec = tween(180)),
        ) {
            placesState.selectedVenue?.let { venue ->
                Box(modifier = Modifier.fillMaxSize().background(AppColors.White)) {
                    VenueDetailPage(
                        venueWithMeta = venue,
                        events = placesState.selectedVenueEvents,
                        isLoading = placesState.isSheetLoading,
                        onBack = { placesVm.dismissSheet() },
                        isFavorited = venue.venue.id in placesState.favoriteVenueIds,
                        onFavoriteToggle = { placesVm.toggleVenueFavorite(venue.venue.id, venue.primaryCategory) },
                        onShare = {
                            presentShareSheet(venue.venue.name, "https://localapp.live/v/${venue.venue.id}")
                        },
                    )
                }
            }
        }
        if (placesState.selectedVenue != null && placesState.selectedVenueEvents.isEmpty()) {
            VenueDetailSheet(
                venueWithMeta = placesState.selectedVenue!!,
                onDismiss = { placesVm.dismissSheet() },
            )
        }

        // Plan detail — spring slide up with scale (matches native event detail feel)
        AnimatedVisibility(
            visible = selectedPlan != null,
            enter = slideInVertically(animationSpec = spring(dampingRatio = 0.85f, stiffness = 300f)) { it / 3 }
                + scaleIn(initialScale = 0.92f, animationSpec = spring(dampingRatio = 0.85f, stiffness = 300f))
                + fadeIn(animationSpec = tween(150)),
            exit = slideOutVertically(animationSpec = tween(250)) { it / 3 }
                + scaleOut(targetScale = 0.92f, animationSpec = tween(250))
                + fadeOut(animationSpec = tween(150)),
        ) {
            selectedPlan?.let { plan ->
                Box(modifier = Modifier.fillMaxSize().background(AppColors.Snow)) {
                    PlanDetailScreen(
                        plan = plan,
                        onBack = { selectedPlan = null },
                        onShare = {
                            scope.launch {
                                val token = planRepo.getShareToken(plan.id) ?: plan.id
                                val totalStops = plan.days.sumOf { it.slots.size }
                                val venueNames = plan.days.flatMap { d -> d.slots.map { it.name } }.take(3).joinToString(", ")
                                val text = "${plan.title} — ${plan.city} · $totalStops stops · $venueNames"
                                presentShareSheet(text, "https://localapp.live/p/$token")
                            }
                        },
                        onEditPlan = {
                            selectedPlan = null
                            pendingChatMessage = "Edit my plan: ${plan.title}"
                            chatOpenCount++
                            openChatOrLogin()
                        },
                        onEditDay = { dayLabel ->
                            selectedPlan = null
                            pendingChatMessage = "Change $dayLabel of ${plan.title}"
                            chatOpenCount++
                            openChatOrLogin()
                        },
                    )
                }
            }
        }

        // Shared plan invite (from deep link)
        AnimatedVisibility(
            visible = sharedPlan != null,
            enter = slideInVertically(animationSpec = spring(dampingRatio = 0.85f, stiffness = 300f)) { it / 3 }
                + fadeIn(animationSpec = tween(150)),
            exit = slideOutVertically(animationSpec = tween(250)) { it / 3 }
                + fadeOut(animationSpec = tween(150)),
        ) {
            sharedPlan?.let { plan ->
                Box(modifier = Modifier.fillMaxSize().background(AppColors.Snow)) {
                    PlanDetailScreen(
                        plan = plan,
                        isShared = true,
                        onBack = {
                            // Didn't accept — add to pending invites so it shows in "Invited" section
                            val plansVm: PlansViewModel = koin.get()
                            plansVm.addPendingInvite(plan)
                            sharedPlan = null
                        },
                        onShare = {},
                        onSaveToPlan = {
                            val plansVm: PlansViewModel = koin.get()
                            if (isLoggedIn) {
                                plansVm.acceptInvite(plan)
                                sharedPlan = null
                                selectedTab = Tab.PLANS
                            } else {
                                // Save plan to accept after auth, then trigger login
                                plansVm.setPendingAcceptAfterAuth(plan)
                                sharedPlan = null
                                showLoginPrompt = true
                            }
                        },
                    )
                }
            }
        }

        // Shared event deep link overlay
        AnimatedVisibility(
            visible = sharedEvent != null,
            enter = slideInVertically(animationSpec = spring(dampingRatio = 0.85f, stiffness = 300f)) { it / 3 }
                + fadeIn(animationSpec = tween(150)),
            exit = slideOutVertically(animationSpec = tween(250)) { it / 3 }
                + fadeOut(animationSpec = tween(150)),
        ) {
            sharedEvent?.let { event ->
                Box(modifier = Modifier.fillMaxSize().background(AppColors.Snow)) {
                    EventDetailScreen(
                        event = event,
                        onBack = { sharedEvent = null },
                        sharedMode = true,
                        onSaveEvent = {
                            if (isLoggedIn) {
                                val communityRepo = koin.get<live.localapp.local.domain.repository.CommunityInterestRepository>()
                                communityRepo.recordFavorite(event.id, live.localapp.local.domain.repository.InteractionType.EVENT, event.subcategory ?: event.category)
                                sharedEvent = null
                                selectedTab = Tab.HOME
                            } else {
                                val settings = com.russhwolf.settings.Settings()
                                settings.putString("pending_favorite_event", event.id)
                                sharedEvent = null
                                showLoginPrompt = true
                            }
                        },
                    )
                }
            }
        }

        // Shared venue deep link overlay
        AnimatedVisibility(
            visible = sharedVenue != null,
            enter = slideInVertically(animationSpec = spring(dampingRatio = 0.85f, stiffness = 300f)) { it / 3 }
                + fadeIn(animationSpec = tween(150)),
            exit = slideOutVertically(animationSpec = tween(250)) { it / 3 }
                + fadeOut(animationSpec = tween(150)),
        ) {
            sharedVenue?.let { venue ->
                Box(modifier = Modifier.fillMaxSize().background(AppColors.Snow)) {
                    VenueDetailPage(
                        venueWithMeta = live.localapp.local.domain.repository.VenueWithMeta(
                            venue = venue,
                            primaryCategory = venue.category,
                            tags = emptyList(),
                            upcomingEventCount = 0,
                            upcomingEvents = emptyList(),
                            distanceMiles = null,
                        ),
                        events = emptyList(),
                        isLoading = false,
                        onBack = { sharedVenue = null },
                        isShared = true,
                        onSaveVenue = {
                            if (isLoggedIn) {
                                val communityRepo = koin.get<live.localapp.local.domain.repository.CommunityInterestRepository>()
                                communityRepo.recordFavorite(venue.id, live.localapp.local.domain.repository.InteractionType.PLACE, venue.category)
                                sharedVenue = null
                                selectedTab = Tab.PLACES
                            } else {
                                val settings = com.russhwolf.settings.Settings()
                                settings.putString("pending_favorite_venue", venue.id)
                                settings.putString("pending_favorite_venue_category", venue.category ?: "")
                                sharedVenue = null
                                showLoginPrompt = true
                            }
                        },
                    )
                }
            }
        }

        // Taste profile overlay
        if (showTasteProfile) {
            val tasteVm: TasteProfileViewModel = viewModel { koin.get<TasteProfileViewModel>() }
            TasteProfileScreen(
                viewModel = tasteVm,
                onBack = { showTasteProfile = false },
            )
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
            val premiumGate = remember { live.localapp.local.ui.chat.PremiumGate() }
            var showTutorial by remember { mutableStateOf(premiumGate.shouldShowTutorial()) }
            var showPaywall by remember { mutableStateOf(false) }

            // Wire premium gate into chat ViewModel
            chatVm.onPlanApprovalCheck = { premiumGate.canCreatePlan() }
            chatVm.onPlanBlocked = { showPaywall = true }
            chatVm.onPlanSaved = { premiumGate.recordPlanCreated() }

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
                containerColor = if (showPaywall) Color(0xFF1A1523) else if (showTutorial) AppColors.Snow else AppColors.Snow,
                dragHandle = {
                    val handleBg = if (showPaywall) Color(0xFF1A1523) else AppColors.Snow
                    val handleColor = if (showPaywall) Color.White.copy(alpha = 0.2f) else AppColors.Pebble
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(handleBg)
                            .padding(top = 10.dp, bottom = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .background(handleColor, RoundedCornerShape(2.dp)),
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
                    if (showTutorial) {
                        live.localapp.local.ui.chat.YocalTutorial(
                            onComplete = {
                                premiumGate.tutorialSeen = true
                                showTutorial = false
                            },
                        )
                    } else if (showPaywall) {
                        live.localapp.local.ui.chat.PremiumPaywall(
                            onSubscribe = { isAnnual ->
                                println("Paywall: Subscribe tapped, isAnnual=$isAnnual")
                                purchaseSubscription(isAnnual) { success ->
                                    println("Paywall: Purchase result=$success")
                                    if (success) {
                                        premiumGate.hasPaidSubscription = true
                                        showPaywall = false
                                    } else {
                                        // Products may not be approved yet — show error or grant for testing
                                        println("Paywall: Purchase failed — products may not be approved by Apple yet")
                                    }
                                }
                            },
                            onDismiss = { showPaywall = false },
                        )
                    } else {
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
                        // Events/venues in chat stay as Compose sheets inside the chat (don't dismiss chat)
                    )
                    } // end else (chat content)
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
                    is CelebrationEvent.NewState -> {
                        // State-specific toasts removed — dismiss silently
                        progressVm.dismissCelebration()
                    }
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
                        .pressable(onClick = { onSelect(tab) }),
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
                .pressable(
                    onClick = {
                        // Double haptic for Yocal open
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onChatClick()
                    },
                    scale = 0.88f,
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
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        visible = true
        // Aggressive haptic burst for points earned
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        delay(150)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        // Really aggressive haptic burst for level up
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        delay(100)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        delay(100)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        contentAlpha.animateTo(1f, tween(300))
        scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = androidx.compose.animation.core.Spring.StiffnessLow))
        // One more haptic on the bounce
        delay(200)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
                    .clickable {
                        presentShareSheet(
                            "I just reached ${event.newTier.label} on Local with ${formatNumber(event.totalPoints)} points!",
                            "https://apps.apple.com/app/id6740043653",
                        )
                    }
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
