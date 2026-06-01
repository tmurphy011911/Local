package live.localapp.local.ui.navigation

import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import live.localapp.local.ui.chat.MessageRole
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
    // Enum name kept as PLACES to avoid churning every call site; user-facing label
    // is "Explore" (rebuilt into a dual Events/Places experience).
    PLACES("Explore", Icons.Outlined.Search),
    PLANS("Plans", Icons.Outlined.CalendarMonth),
    CHAT("Chat", Icons.Outlined.AutoAwesome), // AI concierge — not shown in tab bar
    PROFILE("Profile", Icons.Outlined.Person),
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(userLat: Double? = null, userLng: Double? = null, onLogout: () -> Unit = {}, showUpdateBanner: Boolean = false, onDismissUpdateBanner: () -> Unit = {}, showTutorial: Boolean = false) {
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
    var pendingChatMessage by remember { mutableStateOf<String?>(null) }
    var chatOpenCount by remember { mutableStateOf(0) }
    var showTasteProfile by remember { mutableStateOf(false) }
    var pendingShowPaywall by remember { mutableStateOf(false) }
    var selectedPlan by remember { mutableStateOf<Plan?>(null) }
    var sharedPlan by remember { mutableStateOf<Plan?>(null) }
    var sharedEvent by remember { mutableStateOf<live.localapp.local.domain.model.Event?>(null) }
    var sharedVenue by remember { mutableStateOf<live.localapp.local.domain.model.Venue?>(null) }
    val userPrefsRepo: UserPreferencesRepository = remember { koin.get() }
    val isLoggedIn = userPrefsRepo.getLocalUserId() != null
    var showLocationSheet by remember { mutableStateOf(false) }
    var showCreatePlanMenu by remember { mutableStateOf(false) }
    var showManualPlanSheet by remember { mutableStateOf(false) }

    // Home lobby tutorial — only fires for users who *just* completed onboarding
    // this session. Returning users (session-restore, re-login) never see it.
    var showHomeTutorial by remember { mutableStateOf(showTutorial) }

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

    // Auto-fetch GPS location on launch if mode is "current".
    //
    // Gated on `userPreferences != null` to avoid the race where HomeVM's
    // default state has locationMode=CURRENT_LOCATION before prefs load.
    // Without this gate, a user who picked Manual Address during onboarding
    // would still see the iOS native location prompt fire on first home
    // render, because we'd be reading the default mode (CURRENT) before
    // their saved "manual" preference arrived from the DB.
    live.localapp.local.platform.RequestLocationEffect(
        // Gate on the *stored* preference, not the derived in-memory mode.
        // HomeViewModel's corruption-repair logic can silently flip a
        // freshly-onboarded user from MANUAL back to CURRENT_LOCATION when
        // manualLat/manualLng arrive null (e.g. an autocomplete pick that
        // didn't supply coords). Reading prefs.locationMode directly means
        // the OS-prompt only fires when the user explicitly chose
        // "Allow Location" — never when they picked Manual Address.
        active = homeState.userPreferences != null &&
            homeState.userPreferences?.locationMode != "manual" &&
            homeState.locationMode == live.localapp.local.ui.home.LocationMode.CURRENT_LOCATION,
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

    // Keep PlacesViewModel in sync with Home's effective location so the pill,
    // distances, and unsupported-state flag don't drift when GPS or the manual
    // address changes while the user is on the Places tab.
    LaunchedEffect(homeState.userLat, homeState.userLng, homeState.locationName) {
        val hLat = homeState.userLat
        val hLng = homeState.userLng
        if (hLat != null && hLng != null) placesVm.setUserLocation(hLat, hLng)
        if (homeState.locationName.isNotBlank()) placesVm.setLocationName(homeState.locationName)
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

    // Listen for Universal Link deep links while app is running. Callback receives
    // the full URL path with prefix (/p/{token}, /e/{id}, /v/{id}).
    LaunchedEffect(Unit) {
        setDeepLinkCallback { path ->
            scope.launch {
                when {
                    path.startsWith("/p/") -> {
                        val token = path.removePrefix("/p/")
                        val plan = planRepo.getSharedPlan(token)
                        if (plan != null) {
                            if (userAlreadyHasPlan(plan)) {
                                selectedPlan = plan
                            } else {
                                sharedPlan = plan
                            }
                        }
                    }
                    path.startsWith("/e/") -> {
                        val eventId = path.removePrefix("/e/")
                        val event = eventRepo.getEvent(eventId)
                        if (event != null) sharedEvent = event
                    }
                    path.startsWith("/v/") -> {
                        val venueId = path.removePrefix("/v/")
                        val venue = venueRepo.getVenuesByIds(listOf(venueId)).firstOrNull()
                        if (venue != null) sharedVenue = venue
                    }
                }
            }
        }
    }

    // Sync subscription status from RevenueCat whenever the active user ID
    // changes — on launch, on login, and on account-switch. Without this,
    // hasPaidSubscription stays cached in Settings across users on the same
    // device (User A subscribes, logs out, User B logs in and inherits Pro).
    val currentUserId = userPrefsRepo.getLocalUserId()
    LaunchedEffect(currentUserId) {
        val gate = live.localapp.local.ui.chat.PremiumGate()
        // Start from a clean slate for the new user; RevenueCat re-populates
        // below if the actual entitlement is still active.
        gate.hasPaidSubscription = false
        gate.paidSubscriptionExpiry = ""
        checkSubscriptionStatus { isPro, expiryDate ->
            gate.hasPaidSubscription = isPro
            if (expiryDate != null) {
                gate.paidSubscriptionExpiry = expiryDate.take(10) // ISO date
            }
            println("RevenueCat sync: userId=$currentUserId isPro=$isPro expiry=$expiryDate")
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
            // Bo's scope is conversation-scoped. Only sync home → Bo when
            // there's no ongoing chat (no user-authored messages yet).
            // Once the user has sent a message, leave the chat's location
            // alone — whether they set it via the scope editor or it
            // carried over from a previous open within the same session.
            val hasOngoingChat = chatState.messages.any { it.role == MessageRole.USER }
            val hLat = homeState.userLat
            val hLng = homeState.userLng
            if (!hasOngoingChat && hLat != null && hLng != null) {
                chatVm.setCurrentLocation(hLat, hLng, homeState.locationName)
            }
            if (hasNativeTabBar) {
                // iOS path: snapshot the pending message before opening the sheet,
                // then fire it once the SwiftUI ChatView is ready. Without this
                // dispatch the message gets stranded — the LaunchedEffect that
                // handles pendings on Android only runs in the Compose chat block,
                // which iOS skips.
                val pending = pendingChatMessage
                pendingChatMessage = null
                openChatSheet {
                    if (!pending.isNullOrBlank()) {
                        scope.launch {
                            // Tiny delay so the chat UI has a tick to bind to state.
                            delay(300)
                            chatVm.sendMessage(pending)
                        }
                    }
                }
            } else {
                showChat = true
                chatOpenCount++
            }
        } else {
            onLogout()
        }
    }

    // Catches the in-chat "Start over" path: resetChat clears Bo's location
    // but the home-sync above only fires when the user taps the Bo button.
    // Without this, the scope chip and the scope-editor sheet open with an
    // empty name immediately after reset.
    LaunchedEffect(
        chatState.messages.any { it.role == MessageRole.USER },
        chatState.currentLocationName,
        homeState.userLat,
        homeState.userLng,
        homeState.locationName,
    ) {
        val hasOngoingChat = chatState.messages.any { it.role == MessageRole.USER }
        val locationMissing = chatState.currentLocationName.isBlank()
        val hLat = homeState.userLat
        val hLng = homeState.userLng
        if (!hasOngoingChat && locationMissing && hLat != null && hLng != null && homeState.locationName.isNotBlank()) {
            chatVm.setCurrentLocation(hLat, hLng, homeState.locationName)
        }
    }

    LaunchedEffect(Unit) {
        setupNativeTabBarCallbacks(
            onTabSelected = { idx ->
                selectedTab = when (idx) {
                    0 -> Tab.HOME
                    1 -> Tab.PLACES
                    2 -> Tab.PLANS
                    3 -> Tab.PROFILE
                    else -> Tab.HOME
                }
            },
            onChatTapped = { openChatOrLogin() },
        )
    }

    // Observability: fire once at every tab change / Bo open / manual-plan open.
    // Cleaner than instrumenting every assignment site (there are 20+).
    var lastTab by remember { mutableStateOf(selectedTab) }
    LaunchedEffect(selectedTab) {
        if (selectedTab != lastTab) {
            live.localapp.local.observability.Analytics.track(
                live.localapp.local.observability.AnalyticsEvent.tabSwitched(
                    fromTab = lastTab.name.lowercase(),
                    toTab = selectedTab.name.lowercase(),
                )
            )
            lastTab = selectedTab
        }
    }
    LaunchedEffect(showChat) {
        if (showChat) {
            live.localapp.local.observability.Analytics.track(
                live.localapp.local.observability.AnalyticsEvent.boOpened(entryPoint = "fab")
            )
        }
    }
    LaunchedEffect(showManualPlanSheet) {
        if (showManualPlanSheet) {
            live.localapp.local.observability.Analytics.track(
                live.localapp.local.observability.AnalyticsEvent.planCreateStarted(entryPoint = "create_menu")
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.White)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // Home tutorial overlay — shown once on first launch
        if (showHomeTutorial) {
            live.localapp.local.ui.home.HomeTutorial(
                cityName = homeState.locationName,
                onComplete = {
                    showHomeTutorial = false
                },
            )
        }

        // Main tab content — fills entire screen, tab bar overlays on top
        if (!showHomeTutorial) {
        val contentBlur by animateDpAsState(
            targetValue = if (showCreatePlanMenu) 16.dp else 0.dp,
            animationSpec = tween(durationMillis = 180),
        )
        Box(modifier = Modifier.fillMaxSize().blur(contentBlur)) {
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
                                            homeVm.toggleEventFavorite(eventId, event.subcategory ?: event.category, source = "event_detail")
                                        },
                                        showDirections = false,
                                        isFavorited = event.id in homeState.favoriteEventIds,
                                    )
                                } else {
                                    detailFromPlans = false
                                    detailEvent = event
                                }
                            },
                            onConciergeClick = { openChatOrLogin() },
                            onConciergeSend = { text ->
                                if (text.isNotBlank()) pendingChatMessage = text
                                chatOpenCount++
                                openChatOrLogin()
                            },
                            onProfileClick = { selectedTab = Tab.PROFILE },
                            onLocationClick = { showLocationSheet = true },
                            onFavorite = { event ->
                                homeVm.toggleEventFavorite(event.id, event.subcategory ?: event.category)
                            },
                            showUpdateBanner = showUpdateBanner,
                            onDismissUpdateBanner = onDismissUpdateBanner,
                        )
                    }
                    Tab.CHAT -> {
                        // Chat is now a bottom sheet, not a tab — show home instead
                        openChatOrLogin()
                        selectedTab = Tab.HOME
                    }
                    Tab.PLACES -> {
                        // Sync location from home state (includes lat/lng for geo queries)
                        if (homeState.userLat != null && homeState.userLng != null) {
                            placesVm.setUserLocation(homeState.userLat!!, homeState.userLng!!)
                        }
                        if (homeState.locationName.isNotBlank()) {
                            placesVm.setLocationName(homeState.locationName)
                        }
                        placesVm.setStreakDays(homeState.streakDays)
                        placesVm.ensureLoaded()
                        val initial = homeState.userName.firstOrNull()?.uppercase() ?: ""
                        PlacesScreen(
                            placesVm,
                            onProfileClick = { selectedTab = Tab.PROFILE },
                            onLocationClick = { showLocationSheet = true },
                            userInitial = initial,
                            onVenueClickWithBounds = if (hasNativeHeroTransition) { venue, bounds ->
                                val currentState = placesVm.uiState.value
                                val isFav = venue.venue.id in currentState.favoriteVenueIds
                                // Server now returns inline events alongside each venue
                                // (same source as upcomingEventCount), so the detail
                                // opens with events already populated — no race window
                                // and no count/list disagreement.
                                showNativeVenueDetail(
                                    venueWithMeta = venue,
                                    cardX = bounds.left / density,
                                    cardY = bounds.top / density,
                                    cardW = bounds.width / density,
                                    cardH = bounds.height / density,
                                    onDismissed = {},
                                    isFavorited = isFav,
                                    onFavorited = { venueId ->
                                        placesVm.toggleVenueFavorite(venueId, venue.primaryCategory)
                                    },
                                )
                                // Fallback: only re-query if the server gave us none.
                                // Covers older edge function deployments and odd cases
                                // where the count > 0 but events list is empty.
                                if (venue.upcomingEvents.isEmpty() && venue.upcomingEventCount > 0) {
                                    scope.launch {
                                        val events = try {
                                            venueRepo.getUpcomingEventsForVenue(venue.venue.name, venue.venue.city)
                                        } catch (_: Exception) {
                                            emptyList()
                                        }
                                        live.localapp.local.platform.updateNativeVenueDetailEvents(venue.venue.id, events)
                                    }
                                }
                            } else null,
                            onEventClick = { event, bounds ->
                                val currentState = placesVm.uiState.value
                                val isFav = event.id in currentState.favoriteEventIds
                                if (hasNativeHeroTransition) {
                                    showNativeEventDetail(
                                        event = event,
                                        cardX = bounds.left / density,
                                        cardY = bounds.top / density,
                                        cardW = bounds.width / density,
                                        cardH = bounds.height / density,
                                        cardImageUrl = event.imageUrl,
                                        onDismissed = {},
                                        onFavorited = { eventId ->
                                            placesVm.toggleEventFavorite(eventId, event.subcategory ?: event.category, source = "event_detail")
                                        },
                                        showDirections = false,
                                        isFavorited = isFav,
                                    )
                                } else {
                                    detailFromPlans = false
                                    detailEvent = event
                                }
                            },
                        )
                    }
                    Tab.PROFILE -> {
                        LaunchedEffect(selectedTab) { if (isLoggedIn) progressVm.refresh() }
                        ProgressScreen(
                            progressVm,
                            onLogout = onLogout,
                            onTasteProfile = { showTasteProfile = true },
                            onSignIn = { onLogout() },
                            onNameChanged = { name -> homeVm.setUserName(name) },
                            onLocationClick = { showLocationSheet = true },
                            onUpgrade = {
                                // On iOS, show the native SwiftUI paywall directly — no need
                                // to route through the chat sheet just to upgrade. On Android,
                                // keep the existing path (open chat with pendingShowPaywall)
                                // since the Compose PremiumPaywall lives inside that sheet.
                                if (hasNativeTabBar) {
                                    live.localapp.local.platform.presentNativePaywall { _ -> }
                                } else {
                                    pendingShowPaywall = true
                                    showChat = true
                                    chatOpenCount++
                                }
                            },
                            isLoggedIn = isLoggedIn,
                            locationName = homeState.locationName,
                            streakDays = homeState.streakDays,
                            isUnsupportedState = homeState.isUnsupportedState,
                            radiusFilter = homeState.radiusFilter,
                        )
                    }
                    Tab.PLANS -> {
                        val plansVm: PlansViewModel = viewModel { koin.get<PlansViewModel>() }
                        LaunchedEffect(selectedTab) { if (isLoggedIn) plansVm.refresh() }
                        PlansScreen(
                            viewModel = plansVm,
                            // "+ New Plan" opens the picker overlay (Build Manually /
                            // Create with Yocal) — same surface the tab-bar "+ Create Plan"
                            // button uses. Previously this jumped straight into Yocal,
                            // bypassing the manual option.
                            onNewPlan = { showCreatePlanMenu = true },
                            onPlanClick = { plan, bounds ->
                                if (hasNativeHeroTransition) {
                                    val sharePlanLink: () -> Unit = {
                                        scope.launch {
                                            val token = planRepo.getShareToken(plan.id) ?: plan.id
                                            val totalStops = plan.days.sumOf { it.slots.size }
                                            val venueNames = plan.days.flatMap { d -> d.slots.map { it.name } }.take(3).joinToString(", ")
                                            val text = "${plan.title} — ${plan.city} · $totalStops stops · $venueNames"
                                            presentShareSheet(text, "https://localapp.live/p/$token")
                                        }
                                    }
                                    // Refresh attendees from plan_attendees right after
                                    // opening detail. Sheet opens immediately with the
                                    // current data; once the fetch returns, push the
                                    // updated plan to the native sheet via the bridge so
                                    // "X unconfirmed" and the View Attendees list reflect
                                    // any RSVPs that landed since the Plans list was last
                                    // loaded. Non-blocking so the sheet animation isn't
                                    // delayed by the network round-trip.
                                    scope.launch {
                                        val freshAttendees = planRepo.loadAttendees(plan.id)
                                        if (freshAttendees.isNotEmpty()) {
                                            val refreshed = plan.copy(
                                                attendees = freshAttendees,
                                                confirmedCount = freshAttendees.count { it.confirmed },
                                            )
                                            live.localapp.local.platform.pushNativePlanState(refreshed)
                                        }
                                    }
                                    showNativePlanDetail(
                                        plan = plan,
                                        cardX = bounds.left / density,
                                        cardY = bounds.top / density,
                                        cardW = bounds.width / density,
                                        cardH = bounds.height / density,
                                        onDismissed = {},
                                        onShare = sharePlanLink,
                                        onEditPlan = {
                                            // Per-plan Bo chat (titled with the plan name) instead of
                                            // the main Bo chat — gives Bo full plan context + plan-edit tools.
                                            live.localapp.local.platform.openPlanChatSheet(
                                                plan = plan,
                                                prePopulatedMessage = "I'd like to make changes to my plan",
                                                onDismiss = {},
                                            )
                                        },
                                        onEditDay = { dayLabel ->
                                            live.localapp.local.platform.openPlanChatSheet(
                                                plan = plan,
                                                prePopulatedMessage = "@Bo I'd like to change $dayLabel",
                                                onDismiss = {},
                                            )
                                        },
                                        onSetGroupSize = { size ->
                                            plansVm.setGroupSize(plan.id, size)
                                            live.localapp.local.platform.pushNativePlanState(plan.copy(groupSize = size))
                                        },
                                        onChangeName = { newName ->
                                            plansVm.setPlanName(plan.id, newName)
                                            live.localapp.local.platform.pushNativePlanState(plan.copy(title = newName))
                                        },
                                        onDeletePlan = {
                                            plansVm.deletePlan(plan.id)
                                        },
                                        onAddDay = {
                                            // Same pattern as Add Event: open Bo with a prompt so
                                            // Bo can search/build the new day with full context.
                                            live.localapp.local.platform.openPlanChatSheet(
                                                plan = plan,
                                                prePopulatedMessage = "Add a day to this plan",
                                                onDismiss = {},
                                            )
                                        },
                                        onAddEvent = { dayIdx ->
                                            // Per spec: + Add event opens the per-plan Bo chat with
                                            // a prompt that names the day. Bo has plan context + edit tools.
                                            // Append the year so Bo doesn't ask "which Thursday?" — the
                                            // system prompt has the same anchor but doubling up here
                                            // means the user-visible message also reads unambiguously.
                                            val dayLabel = plan.days.getOrNull(dayIdx)?.label ?: "this day"
                                            val today = kotlin.time.Clock.System.now()
                                                .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
                                            // Inline year inference — same logic as
                                            // PlanChatViewModel.expandLabelWithYearAndIso. If the
                                            // parsed month is already past today, assume next year.
                                            val withYear = run {
                                                val months = listOf("January","February","March","April","May","June",
                                                    "July","August","September","October","November","December")
                                                val afterComma = dayLabel.substringAfter(',', dayLabel).trim()
                                                val parts = afterComma.split(' ').filter { it.isNotBlank() }
                                                val monthIdx = if (parts.isNotEmpty()) months.indexOfFirst { it.equals(parts[0], ignoreCase = true) } else -1
                                                if (monthIdx >= 0 && parts.size >= 2) {
                                                    val year = if (monthIdx + 1 < today.monthNumber) today.year + 1 else today.year
                                                    "$dayLabel, $year"
                                                } else dayLabel
                                            }
                                            live.localapp.local.platform.openPlanChatSheet(
                                                plan = plan,
                                                prePopulatedMessage = "Add an event to $withYear",
                                                onDismiss = {},
                                            )
                                        },
                                        onSetSlotReserved = { dayIdx, slotId, reserved ->
                                            plansVm.setSlotReserved(plan.id, dayIdx, slotId, reserved)
                                        },
                                        onSaveReservationDetails = { dayIdx, slotId, partySize, time ->
                                            plansVm.setReservationDetails(plan.id, dayIdx, slotId, partySize, time)
                                            // Push state so the SwiftUI side rerenders with the new badge / time.
                                            val refreshed = (plansVm.uiState.value.upcomingPlans + plansVm.uiState.value.pastPlans)
                                                .find { it.id == plan.id } ?: plan
                                            live.localapp.local.platform.pushNativePlanState(refreshed)
                                        },
                                        onReservationUnavailable = { dayIdx, slotId ->
                                            plansVm.setReservationUnavailable(plan.id, dayIdx, slotId)
                                            val refreshed = (plansVm.uiState.value.upcomingPlans + plansVm.uiState.value.pastPlans)
                                                .find { it.id == plan.id } ?: plan
                                            live.localapp.local.platform.pushNativePlanState(refreshed)
                                        },
                                        onClearReservation = { dayIdx, slotId ->
                                            plansVm.clearReservation(plan.id, dayIdx, slotId)
                                            val refreshed = (plansVm.uiState.value.upcomingPlans + plansVm.uiState.value.pastPlans)
                                                .find { it.id == plan.id } ?: plan
                                            live.localapp.local.platform.pushNativePlanState(refreshed)
                                        },
                                        onSaveTicketDetails = { dayIdx, slotId, count ->
                                            plansVm.setTicketDetails(plan.id, dayIdx, slotId, count)
                                            val refreshed = (plansVm.uiState.value.upcomingPlans + plansVm.uiState.value.pastPlans)
                                                .find { it.id == plan.id } ?: plan
                                            live.localapp.local.platform.pushNativePlanState(refreshed)
                                        },
                                        onTicketsUnavailable = { dayIdx, slotId ->
                                            plansVm.setTicketsUnavailable(plan.id, dayIdx, slotId)
                                            val refreshed = (plansVm.uiState.value.upcomingPlans + plansVm.uiState.value.pastPlans)
                                                .find { it.id == plan.id } ?: plan
                                            live.localapp.local.platform.pushNativePlanState(refreshed)
                                        },
                                        onClearTickets = { dayIdx, slotId ->
                                            plansVm.clearTickets(plan.id, dayIdx, slotId)
                                            val refreshed = (plansVm.uiState.value.upcomingPlans + plansVm.uiState.value.pastPlans)
                                                .find { it.id == plan.id } ?: plan
                                            live.localapp.local.platform.pushNativePlanState(refreshed)
                                        },
                                        onReplaceSlot = { _, _, venueName ->
                                            // Per-plan Bo chat — Bo can call update_plan with full context.
                                            live.localapp.local.platform.openPlanChatSheet(
                                                plan = plan,
                                                prePopulatedMessage = "@Bo please replace $venueName",
                                                onDismiss = {},
                                            )
                                        },
                                        onRemoveSlot = { dayIdx, slotId ->
                                            plansVm.removeSlotFromPlan(plan.id, dayIdx, slotId)
                                            // Push the updated plan into the native PlanDetailView so
                                            // the deleted slot disappears immediately. Without this,
                                            // the native view keeps its original snapshot and the row
                                            // sticks around until the sheet is dismissed and reopened.
                                            val refreshed = (plansVm.uiState.value.upcomingPlans + plansVm.uiState.value.pastPlans)
                                                .find { it.id == plan.id } ?: plan
                                            live.localapp.local.platform.pushNativePlanState(refreshed)
                                        },
                                        onInvite = sharePlanLink,
                                        onChangeRsvp = { _ ->
                                            // RSVP backend is not wired yet — placeholder hook.
                                            // TODO: PlansViewModel.setOwnRsvp(planId, status).
                                        },
                                        onRemoveAttendee = { _ ->
                                            // TODO: PlansViewModel.removeAttendee(planId, attendeeId).
                                        },
                                        onRequestSwapAlternatives = { dayIdx, slotId ->
                                            scope.launch {
                                                val swapService: live.localapp.local.domain.service.PlanSwapService = koin.get()
                                                val current = (plansVm.uiState.value.upcomingPlans + plansVm.uiState.value.pastPlans)
                                                    .find { it.id == plan.id } ?: plan
                                                val targetSlot = current.days.getOrNull(dayIdx)?.slots?.find { it.id == slotId }
                                                val alternatives = if (targetSlot != null) {
                                                    swapService.fetchAlternatives(targetSlot, current)
                                                } else emptyList()
                                                val json = kotlinx.serialization.json.buildJsonArray {
                                                    alternatives.forEach { alt ->
                                                        add(kotlinx.serialization.json.buildJsonObject {
                                                            put("kind", kotlinx.serialization.json.JsonPrimitive(alt.kind.name.lowercase()))
                                                            put("sourceId", kotlinx.serialization.json.JsonPrimitive(alt.sourceId))
                                                            put("name", kotlinx.serialization.json.JsonPrimitive(alt.name))
                                                            put("category", kotlinx.serialization.json.JsonPrimitive(alt.category))
                                                            put("neighborhood", kotlinx.serialization.json.JsonPrimitive(alt.neighborhood))
                                                            put("rating", kotlinx.serialization.json.JsonPrimitive(alt.rating))
                                                            put("imageUrl", kotlinx.serialization.json.JsonPrimitive(alt.imageUrl))
                                                            put("lat", kotlinx.serialization.json.JsonPrimitive(alt.lat))
                                                            put("lng", kotlinx.serialization.json.JsonPrimitive(alt.lng))
                                                            put("ticketUrl", kotlinx.serialization.json.JsonPrimitive(alt.ticketUrl))
                                                            put("sourceUrl", kotlinx.serialization.json.JsonPrimitive(alt.sourceUrl))
                                                            put("reservationUrl", kotlinx.serialization.json.JsonPrimitive(alt.reservationUrl))
                                                            put("venueWebsite", kotlinx.serialization.json.JsonPrimitive(alt.venueWebsite))
                                                            put("venueAddress", kotlinx.serialization.json.JsonPrimitive(alt.venueAddress))
                                                            put("priceMin", kotlinx.serialization.json.JsonPrimitive(alt.priceMin))
                                                            put("priceMax", kotlinx.serialization.json.JsonPrimitive(alt.priceMax))
                                                            put("isFree", kotlinx.serialization.json.JsonPrimitive(alt.isFree))
                                                            put("eventStartAt", kotlinx.serialization.json.JsonPrimitive(alt.eventStartAt))
                                                            put("eventTime", kotlinx.serialization.json.JsonPrimitive(alt.eventTime))
                                                        })
                                                    }
                                                }.toString()
                                                live.localapp.local.platform.pushSwapAlternatives(json)
                                            }
                                        },
                                        onSwapInAlternative = { dayIdx, oldSlotId, altJson ->
                                            // Decode the alternative + build a new PlanSlot. Restaurants
                                            // keep the OLD slot's time (per spec §3.4), events use the new event's time.
                                            try {
                                                val alt = kotlinx.serialization.json.Json.parseToJsonElement(altJson).jsonObject
                                                val current = (plansVm.uiState.value.upcomingPlans + plansVm.uiState.value.pastPlans)
                                                    .find { it.id == plan.id } ?: plan
                                                val oldSlot = current.days.getOrNull(dayIdx)?.slots?.find { it.id == oldSlotId }
                                                val kind = alt["kind"]?.jsonPrimitive?.contentOrNull
                                                val newName = alt["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                                val newTime = if (kind == "event") {
                                                    (alt["eventTime"]?.jsonPrimitive?.contentOrNull ?: oldSlot?.time ?: "")
                                                } else {
                                                    oldSlot?.time ?: ""
                                                }
                                                val newSlot = live.localapp.local.domain.model.PlanSlot(
                                                    id = "swap_${kotlin.random.Random.nextLong().toString(36)}",
                                                    time = newTime,
                                                    emoji = oldSlot?.emoji ?: "",
                                                    name = newName,
                                                    detail = alt["neighborhood"]?.jsonPrimitive?.contentOrNull ?: "",
                                                    price = "",
                                                    isFree = alt["isFree"]?.jsonPrimitive?.contentOrNull?.toBoolean() == true,
                                                    eventId = if (kind == "event") alt["sourceId"]?.jsonPrimitive?.contentOrNull else null,
                                                    isCustom = false,
                                                    imageUrl = alt["imageUrl"]?.jsonPrimitive?.contentOrNull,
                                                    ticketUrl = alt["ticketUrl"]?.jsonPrimitive?.contentOrNull,
                                                    venueRating = alt["rating"]?.jsonPrimitive?.doubleOrNull,
                                                    venueAddress = alt["venueAddress"]?.jsonPrimitive?.contentOrNull,
                                                    venueLat = alt["lat"]?.jsonPrimitive?.doubleOrNull,
                                                    venueLng = alt["lng"]?.jsonPrimitive?.doubleOrNull,
                                                    priceMin = alt["priceMin"]?.jsonPrimitive?.doubleOrNull,
                                                    priceMax = alt["priceMax"]?.jsonPrimitive?.doubleOrNull,
                                                    reservationUrl = alt["reservationUrl"]?.jsonPrimitive?.contentOrNull,
                                                    sourceUrl = alt["sourceUrl"]?.jsonPrimitive?.contentOrNull,
                                                    venueWebsite = alt["venueWebsite"]?.jsonPrimitive?.contentOrNull,
                                                    venueCategory = alt["category"]?.jsonPrimitive?.contentOrNull,
                                                )
                                                plansVm.swapSlot(plan.id, dayIdx, oldSlotId, newSlot)
                                                val refreshed = (plansVm.uiState.value.upcomingPlans + plansVm.uiState.value.pastPlans)
                                                    .find { it.id == plan.id } ?: plan
                                                live.localapp.local.platform.pushNativePlanState(refreshed)
                                            } catch (e: Throwable) {
                                                println("Phase 5 swap decode error: ${e.message}")
                                            }
                                        },
                                        onSeeMoreSwapOptions = { _, _, venueName ->
                                            live.localapp.local.platform.openPlanChatSheet(
                                                plan = plan,
                                                prePopulatedMessage = "@Bo I want to replace $venueName with something else — show me more options",
                                                onDismiss = {},
                                            )
                                        },
                                        onSwapForUnavailable = { _, _, venueName, kind ->
                                            // Tapping "No reservations available" / "No tickets available"
                                            // in the stop sheet jumps straight to Bo with a swap prompt
                                            // instead of marking the slot unavailable. Keeps the user in
                                            // motion: one tap, Bo proposes alternatives.
                                            val noun = if (kind == "ticket") "tickets" else "reservations"
                                            live.localapp.local.platform.openPlanChatSheet(
                                                plan = plan,
                                                prePopulatedMessage = "Can you swap out $venueName as there are no $noun available?",
                                                onDismiss = {},
                                            )
                                        },
                                        onSlotTap = { dayIdx, slotId, eventId, venueName ->
                                            // Slot card tap → open the same native event/venue
                                            // detail screens used elsewhere in the app. Events
                                            // win when an event_id is present (concert / show /
                                            // workshop), venues otherwise (restaurants / bars).
                                            //
                                            // Speed-first: DB lookup is capped at 300ms via
                                            // withTimeoutOrNull. Past that we fall back to a
                                            // minimal Event/Venue built from the slot's own
                                            // fields so the user never waits — a 2-second hang
                                            // on a slow Supabase read was making the tap feel
                                            // broken. Under 300ms the user perceives the lookup
                                            // as instant and gets the richer DB row.
                                            val slot = plan.days.getOrNull(dayIdx)
                                                ?.slots?.firstOrNull { it.id == slotId }
                                            scope.launch {
                                                if (!eventId.isNullOrBlank()) {
                                                    val ev = kotlinx.coroutines.withTimeoutOrNull(300) {
                                                        try { eventRepo.getEvent(eventId) } catch (_: Throwable) { null }
                                                    } ?: slot?.toFallbackEvent()
                                                    if (ev != null) {
                                                        live.localapp.local.platform.showNativeEventDetail(
                                                            event = ev,
                                                            cardX = 0f, cardY = 0f, cardW = 0f, cardH = 0f,
                                                            cardImageUrl = ev.imageUrl,
                                                            onDismissed = {},
                                                            onFavorited = null,
                                                            showDirections = false,
                                                            isFavorited = false,
                                                        )
                                                    }
                                                } else if (!venueName.isNullOrBlank()) {
                                                    val v = kotlinx.coroutines.withTimeoutOrNull(300) {
                                                        try { venueRepo.searchByName(venueName) } catch (_: Throwable) { null }
                                                    } ?: slot?.toFallbackVenue()
                                                    if (v != null) {
                                                        live.localapp.local.platform.showNativeVenueDetail(
                                                            venueWithMeta = live.localapp.local.domain.repository.VenueWithMeta(venue = v),
                                                            cardX = 0f, cardY = 0f, cardW = 0f, cardH = 0f,
                                                            onDismissed = {},
                                                        )
                                                    }
                                                }
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
                                    homeVm.reviewPromptService.onPositiveMoment(live.localapp.local.domain.service.ReviewTrigger.PLAN_SHARED)
                                }
                            },
                            userInitial = homeState.userName.firstOrNull()?.uppercase() ?: "",
                            onProfileClick = { selectedTab = Tab.PROFILE },
                            onLocationClick = { showLocationSheet = true },
                            onSignIn = { onLogout() },
                            isLoggedIn = isLoggedIn,
                            locationName = homeState.locationName,
                            streakDays = homeState.streakDays,
                            isUnsupportedState = homeState.isUnsupportedState,
                            radiusFilter = homeState.radiusFilter,
                            onBuildPlanFromEvent = { eventId, eventTitle ->
                                // Embed the literal event ID so Bo treats this
                                // as a "use this exact event_id, don't search
                                // for similar" — otherwise Bo paraphrases the
                                // title, search_events returns near-misses or
                                // nothing, and Bo falls back to recommending
                                // the venue instead of the event.
                                pendingChatMessage =
                                    "I'd like to build a plan around my saved event \"$eventTitle\" (event_id: evt:$eventId). " +
                                    "Use this exact event_id in the present_plan slot — don't search for it again."
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
                                            onFavorited = { id -> homeVm.toggleEventFavorite(id, event.subcategory ?: event.category, source = "event_detail") },
                                            showDirections = true,
                                            isFavorited = event.id in homeState.favoriteEventIds,
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

            // Create Plan scrim — rendered BELOW the tab bar so the button stays bright
            live.localapp.local.ui.plans.CreatePlanScrim(
                visible = showCreatePlanMenu,
                onDismiss = { showCreatePlanMenu = false },
            )

            // Floating tab bar — overlays content at bottom
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                val tabInitial = homeState.userName.firstOrNull()?.uppercase() ?: ""
                BottomTabBar(
                    selectedTab,
                    onSelect = { selectedTab = it },
                    onCreatePlanClick = { showCreatePlanMenu = true },
                    userInitial = tabInitial,
                )
            }

            // Menu items — rendered ABOVE the tab bar so they appear to spring from the button
            live.localapp.local.ui.plans.CreatePlanMenuItems(
                visible = showCreatePlanMenu,
                onDismiss = { showCreatePlanMenu = false },
                onYocal = { openChatOrLogin() },
                onManual = {
                    if (userPrefsRepo.getLocalUserId() != null) {
                        showManualPlanSheet = true
                    } else {
                        onLogout()
                    }
                },
            )

            if (showManualPlanSheet) {
                val manualPremiumGate = remember { live.localapp.local.ui.chat.PremiumGate() }
                live.localapp.local.ui.plans.ManualPlanSheet(
                    onDismiss = { showManualPlanSheet = false },
                    chatVm = chatVm,
                    eventRepo = eventRepo,
                    userLat = homeState.userLat,
                    userLng = homeState.userLng,
                    canSavePlan = { manualPremiumGate.canCreatePlan(live.localapp.local.ui.chat.PlanSource.MANUAL) },
                    onPaywallRequired = {
                        manualPremiumGate.hasSeenPaywall = true
                        pendingShowPaywall = true
                        showChat = true
                        chatOpenCount++
                    },
                    onPlanSavedOpenChat = {
                        manualPremiumGate.recordPlanCreated(live.localapp.local.ui.chat.PlanSource.MANUAL)
                        // Open the chat sheet so the celebration overlay surfaces.
                        openChatOrLogin()
                    },
                    onPlanWithBo = {
                        // User abandoned manual flow — open Bo fresh.
                        openChatOrLogin()
                    },
                )
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
                val plansVm: PlansViewModel = koin.get()
                val plansState by plansVm.uiState.collectAsState()
                val currentUserId = plansVm.currentUserId()
                // Creator is authoritatively determined by plan.creatorId. Previously
                // this also fell back to "is the plan in the user's own plans list,"
                // but accepted-invite plans are also copied into the attendee's
                // plans list, so that check falsely promoted attendees to creators
                // and gave them the full edit UI.
                val isCreator = plan.creatorId != null && plan.creatorId == currentUserId
                var showPlanChat by remember(plan.id) { mutableStateOf(false) }
                var planChatPrefill by remember(plan.id) { mutableStateOf<String?>(null) }
                Box(modifier = Modifier.fillMaxSize().background(AppColors.Snow)) {
                    PlanDetailScreen(
                        plan = plan,
                        isCreator = isCreator,
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
                            // Per Plan Detail spec V1: Edit Plan opens the main Yocal chat
                            // with a pre-filled prompt that names the plan.
                            pendingChatMessage = "I'd like to make changes to my plan: ${plan.title}"
                            openChatOrLogin()
                        },
                        onEditDay = { dayLabel ->
                            planChatPrefill = "@Bo I'd like to change $dayLabel."
                            showPlanChat = true
                        },
                        onSetGroupSize = { size ->
                            plansVm.setGroupSize(plan.id, size)
                            selectedPlan = plan.copy(groupSize = size)
                        },
                        onSetAttendeesCanEdit = { canEdit ->
                            plansVm.setAttendeesCanEdit(plan.id, canEdit)
                            selectedPlan = plan.copy(attendeesCanEdit = canEdit)
                        },
                        onEditName = { newName ->
                            plansVm.setPlanName(plan.id, newName)
                            selectedPlan = plan.copy(title = newName)
                        },
                        onRemoveSlot = { dayIdx, slotId ->
                            plansVm.removeSlotFromPlan(plan.id, dayIdx, slotId)
                            val updatedDays = plan.days.mapIndexed { i, d ->
                                if (i == dayIdx) d.copy(slots = d.slots.filter { it.id != slotId }) else d
                            }
                            selectedPlan = plan.copy(days = updatedDays)
                        },
                        onAddSlot = { dayIdx, slot ->
                            plansVm.addSlotToDay(plan.id, dayIdx, slot)
                            val updatedDays = plan.days.mapIndexed { i, d ->
                                if (i == dayIdx) d.copy(slots = d.slots + slot) else d
                            }
                            selectedPlan = plan.copy(days = updatedDays)
                        },
                        onAddDay = {
                            // Open Bo with a prompt so the new day gets real context (date,
                            // events, travel) instead of an empty placeholder.
                            live.localapp.local.platform.openPlanChatSheet(
                                plan = plan,
                                prePopulatedMessage = "Add a day to this plan",
                                onDismiss = {},
                            )
                        },
                        onRemoveDay = { dayIdx ->
                            plansVm.removeDayFromPlan(plan.id, dayIdx)
                            selectedPlan = plan.copy(days = plan.days.filterIndexed { i, _ -> i != dayIdx })
                        },
                        onDeletePlan = {
                            plansVm.deletePlan(plan.id)
                            selectedPlan = null
                        },
                        onSetSlotReserved = { dayIdx, slotId, reserved ->
                            plansVm.setSlotReserved(plan.id, dayIdx, slotId, reserved)
                            val updatedDays = plan.days.mapIndexed { i, d ->
                                if (i != dayIdx) d
                                else d.copy(slots = d.slots.map { s ->
                                    if (s.id == slotId) s.copy(
                                        reservationState = if (reserved) live.localapp.local.domain.model.ReservationState.MADE
                                            else live.localapp.local.domain.model.ReservationState.NONE,
                                    ) else s
                                })
                            }
                            selectedPlan = plan.copy(days = updatedDays)
                        },
                        onReplaceSlot = { _, slot ->
                            // Per spec: per-stop "Replace this stop" opens Yocal with a replacement prompt.
                            pendingChatMessage = "Please replace ${slot.name} in my plan: ${plan.title}"
                            openChatOrLogin()
                        },
                        onSaveReservationDetails = { dayIdx, slotId, partySize, time ->
                            plansVm.setReservationDetails(plan.id, dayIdx, slotId, partySize, time)
                            val updatedDays = plan.days.mapIndexed { i, d ->
                                if (i != dayIdx) d
                                else d.copy(slots = d.slots.map { s ->
                                    if (s.id == slotId) s.copy(
                                        reservationState = live.localapp.local.domain.model.ReservationState.MADE,
                                        reservationPartySize = partySize,
                                        reservationTime = time,
                                    ) else s
                                })
                            }
                            selectedPlan = plan.copy(days = updatedDays)
                        },
                        onReservationUnavailable = { dayIdx, slotId ->
                            plansVm.setReservationUnavailable(plan.id, dayIdx, slotId)
                            val updatedDays = plan.days.mapIndexed { i, d ->
                                if (i != dayIdx) d
                                else d.copy(slots = d.slots.map { s ->
                                    if (s.id == slotId) s.copy(
                                        reservationState = live.localapp.local.domain.model.ReservationState.UNAVAILABLE,
                                    ) else s
                                })
                            }
                            selectedPlan = plan.copy(days = updatedDays)
                        },
                        onClearReservation = { dayIdx, slotId ->
                            plansVm.clearReservation(plan.id, dayIdx, slotId)
                            val updatedDays = plan.days.mapIndexed { i, d ->
                                if (i != dayIdx) d
                                else d.copy(slots = d.slots.map { s ->
                                    if (s.id == slotId) s.copy(
                                        reservationState = live.localapp.local.domain.model.ReservationState.NONE,
                                        reservationPartySize = null,
                                        reservationTime = null,
                                    ) else s
                                })
                            }
                            selectedPlan = plan.copy(days = updatedDays)
                        },
                        onSaveTicketDetails = { dayIdx, slotId, ticketCount ->
                            plansVm.setTicketDetails(plan.id, dayIdx, slotId, ticketCount)
                            val updatedDays = plan.days.mapIndexed { i, d ->
                                if (i != dayIdx) d
                                else d.copy(slots = d.slots.map { s ->
                                    if (s.id == slotId) s.copy(
                                        ticketState = live.localapp.local.domain.model.TicketState.BOUGHT,
                                        ticketCount = ticketCount,
                                    ) else s
                                })
                            }
                            selectedPlan = plan.copy(days = updatedDays)
                        },
                        onTicketsUnavailable = { dayIdx, slotId ->
                            plansVm.setTicketsUnavailable(plan.id, dayIdx, slotId)
                            val updatedDays = plan.days.mapIndexed { i, d ->
                                if (i != dayIdx) d
                                else d.copy(slots = d.slots.map { s ->
                                    if (s.id == slotId) s.copy(
                                        ticketState = live.localapp.local.domain.model.TicketState.UNAVAILABLE,
                                    ) else s
                                })
                            }
                            selectedPlan = plan.copy(days = updatedDays)
                        },
                        onClearTickets = { dayIdx, slotId ->
                            plansVm.clearTickets(plan.id, dayIdx, slotId)
                            val updatedDays = plan.days.mapIndexed { i, d ->
                                if (i != dayIdx) d
                                else d.copy(slots = d.slots.map { s ->
                                    if (s.id == slotId) s.copy(
                                        ticketState = live.localapp.local.domain.model.TicketState.NONE,
                                        ticketCount = null,
                                    ) else s
                                })
                            }
                            selectedPlan = plan.copy(days = updatedDays)
                        },
                        onSlotTap = { slot ->
                            // Android tap-to-detail: events route to the existing
                            // Compose EventDetailScreen overlay (via detailEvent
                            // state), venues route through PlacesViewModel.selectVenue.
                            // DB lookup is best-effort; on miss we fall back to slot
                            // data so the tap always lands somewhere.
                            scope.launch {
                                val eventId = slot.eventId
                                if (!eventId.isNullOrBlank()) {
                                    val ev = kotlinx.coroutines.withTimeoutOrNull(300) {
                                        try { eventRepo.getEvent(eventId) } catch (_: Throwable) { null }
                                    } ?: slot.toFallbackEvent()
                                    detailFromPlans = false
                                    detailEvent = ev
                                } else {
                                    val name = slot.venueName ?: slot.name
                                    if (name.isNotBlank()) {
                                        val v = kotlinx.coroutines.withTimeoutOrNull(300) {
                                            try { venueRepo.searchByName(name) } catch (_: Throwable) { null }
                                        } ?: slot.toFallbackVenue()
                                        placesVm.selectVenue(
                                            live.localapp.local.domain.repository.VenueWithMeta(venue = v)
                                        )
                                    }
                                }
                            }
                        },
                        userLat = homeState.userLat,
                        userLng = homeState.userLng,
                    )

                    if (showPlanChat) {
                        if (hasNativeHeroTransition) {
                            // iOS — present native SwiftUI PlanChatView
                            val prefill = planChatPrefill
                            LaunchedEffect(plan.id, prefill) {
                                live.localapp.local.platform.openPlanChatSheet(
                                    plan = plan,
                                    prePopulatedMessage = prefill,
                                    onDismiss = {
                                        showPlanChat = false
                                        planChatPrefill = null
                                    },
                                )
                            }
                        } else {
                            // Android — Compose ModalBottomSheet
                            val planChatVm = remember(plan.id) {
                                koin.get<live.localapp.local.ui.plans.PlanChatViewModel> {
                                    org.koin.core.parameter.parametersOf(plan)
                                }
                            }
                            androidx.compose.material3.ModalBottomSheet(
                                onDismissRequest = {
                                    showPlanChat = false
                                    planChatPrefill = null
                                },
                                sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
                                containerColor = AppColors.Snow,
                            ) {
                                Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
                                    live.localapp.local.ui.plans.PlanChatScreen(
                                        planTitle = plan.title,
                                        viewModel = planChatVm,
                                        onClose = {
                                            showPlanChat = false
                                            planChatPrefill = null
                                        },
                                        prePopulatedMessage = planChatPrefill,
                                    )
                                }
                            }
                        }
                    }
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
                        // A user viewing a shared invite link is by definition not the creator.
                        isCreator = false,
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
                                onLogout()
                            }
                        },
                        onSlotTap = { slot ->
                            // Same tap-to-detail behavior as the main path so
                            // someone previewing a shared plan can inspect a
                            // stop before deciding to accept.
                            scope.launch {
                                val eventId = slot.eventId
                                if (!eventId.isNullOrBlank()) {
                                    val ev = kotlinx.coroutines.withTimeoutOrNull(300) {
                                        try { eventRepo.getEvent(eventId) } catch (_: Throwable) { null }
                                    } ?: slot.toFallbackEvent()
                                    detailFromPlans = false
                                    detailEvent = ev
                                } else {
                                    val name = slot.venueName ?: slot.name
                                    if (name.isNotBlank()) {
                                        val v = kotlinx.coroutines.withTimeoutOrNull(300) {
                                            try { venueRepo.searchByName(name) } catch (_: Throwable) { null }
                                        } ?: slot.toFallbackVenue()
                                        placesVm.selectVenue(
                                            live.localapp.local.domain.repository.VenueWithMeta(venue = v)
                                        )
                                    }
                                }
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
                                onLogout()
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
                                onLogout()
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
                        onFavorited = { id -> homeVm.toggleEventFavorite(id, event.subcategory ?: event.category, source = "event_detail") },
                        showDirections = detailFromPlans,
                        isFavorited = event.id in homeState.favoriteEventIds,
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
                        homeVm.toggleEventFavorite(eventId, cat, source = "event_detail")
                    },
                    initialFavorited = detailEvent?.id in homeState.favoriteEventIds,
                    onVenueClick = detailEvent?.venueName?.let { vName ->
                        {
                            val ev = detailEvent
                            if (ev != null) {
                                placesVm.openVenueByNameAndCity(vName, ev.venueCity, ev.distanceMiles)
                            }
                        }
                    },
                )
            }
            }
        }
        } // end if (!showHomeTutorial)

        // Location settings sheet — shared across all tabs
        if (showLocationSheet) {
            live.localapp.local.ui.home.LocationSettingsSheet(
                currentLocationName = homeState.locationName,
                gpsLocationName = homeState.gpsLocationName,
                locationMode = homeState.locationMode,
                manualLocationName = homeState.manualLocationName,
                radiusFilter = homeState.radiusFilter,
                hasLocationPermission = homeState.hasLocationPermission,
                onSelectCurrentLocation = { /* mode only flips to CURRENT_LOCATION on successful GPS fix */ },
                onGpsLocation = { lat, lng ->
                    homeVm.setUserLocation(lat, lng)
                    homeVm.setHasLocationPermission(true)
                    homeVm.setLocationMode(live.localapp.local.ui.home.LocationMode.CURRENT_LOCATION)
                },
                onGpsDenied = {
                    homeVm.setHasLocationPermission(false)
                    homeVm.setLocationMode(live.localapp.local.ui.home.LocationMode.MANUAL)
                },
                onSelectManualLocation = { lat, lng, name -> homeVm.setManualLocation(lat, lng, name) },
                onRadiusChanged = {
                    // Mirror to both VMs — the AppHeader pill on Explore reads
                    // placesState.radiusFilter, on Home it reads homeState.radiusFilter.
                    // Without the second call, picking a radius from the sheet would
                    // update one tab's pill and leave the other showing just the city.
                    homeVm.setRadiusFilter(it)
                    placesVm.setRadiusFilter(it)
                },
                onDismiss = { showLocationSheet = false },
                isUnsupportedState = homeState.isUnsupportedState,
            )
        }

        // Chat bottom sheet
        if (showChat) {
            val chatVm: ChatViewModel = viewModel { koin.get<ChatViewModel>() }
            // If the previous chat session ended with a successful plan save,
            // the celebration state is still in the VM. Clear it on open so
            // users get a fresh conversation instead of landing back on the
            // success overlay. Fires only when reopening post-success.
            LaunchedEffect(Unit) {
                if (chatVm.uiState.value.showPlanSuccess) {
                    chatVm.dismissPlanSuccess(navigateToPlans = false)
                }
            }
            val chatSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val premiumGate = remember { live.localapp.local.ui.chat.PremiumGate() }
            var showTutorial by remember { mutableStateOf(premiumGate.shouldShowTutorial()) }
            var showPaywall by remember { mutableStateOf(pendingShowPaywall) }
            // Tracks a save attempt that was interrupted by the paywall so we can
            // resume the save automatically once the user subscribes / restores.
            var pendingPlanSaveAfterPaywall by remember { mutableStateOf(false) }
            if (pendingShowPaywall) pendingShowPaywall = false

            // Wire premium gate into chat ViewModel. Yocal and the manual builder
            // each get their own free-save quota via PlanSource.
            chatVm.onPlanApprovalCheck = { premiumGate.canCreatePlan(live.localapp.local.ui.chat.PlanSource.YOCAL) }
            chatVm.onPlanBlocked = {
                showPaywall = true
                premiumGate.hasSeenPaywall = true
                // Remember the user was mid-save so we can resume automatically
                // after they subscribe — no second manual tap required.
                pendingPlanSaveAfterPaywall = true
            }
            chatVm.onPlanSaved = { premiumGate.recordPlanCreated(live.localapp.local.ui.chat.PlanSource.YOCAL) }

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

            // On iOS, the native SwiftUI ChatView is presented via the bridge.
            // Only render the Compose ModalBottomSheet on Android.
            if (!hasNativeHeroTransition)
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = {
                    chatVm.clearSuccessState()
                    showChat = false
                },
                sheetState = chatSheetState,
                containerColor = AppColors.Snow,
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.Snow)
                            .padding(top = 10.dp, bottom = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .background(AppColors.Pebble, RoundedCornerShape(2.dp)),
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
                            showUpgradeBanner = premiumGate.hasDeclinedPaywall,
                            onUpgrade = { showPaywall = true },
                        )
                    }
                }
            }

            // Paywall — fullscreen Dialog overlay (iOS parity).
            // Rendered as a sibling to the chat sheet so it covers the whole screen
            // instead of being constrained to the 92%-height bottom sheet.
            if (showPaywall) {
                // Fetch dynamic pricing from RevenueCat
                var rcMonthly by remember { mutableStateOf<String?>(null) }
                var rcAnnual by remember { mutableStateOf<String?>(null) }
                var rcIntro by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    live.localapp.local.platform.fetchSubscriptionPricing { m, a, i ->
                        rcMonthly = m; rcAnnual = a; rcIntro = i
                    }
                }
                Dialog(
                    onDismissRequest = {
                        showPaywall = false
                        pendingPlanSaveAfterPaywall = false
                    },
                    properties = DialogProperties(
                        usePlatformDefaultWidth = false,
                        dismissOnBackPress = true,
                        dismissOnClickOutside = false,
                    ),
                ) {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1523))) {
                        live.localapp.local.ui.chat.PremiumPaywall(
                            monthlyPrice = rcMonthly,
                            annualPrice = rcAnnual,
                            introPrice = rcIntro,
                            onSubscribe = { isAnnual ->
                                println("Paywall: Subscribe tapped, isAnnual=$isAnnual")
                                purchaseSubscription(isAnnual) { success ->
                                    println("Paywall: Purchase result=$success")
                                    if (success) {
                                        premiumGate.hasPaidSubscription = true
                                        showPaywall = false
                                        // Resume the save the user was trying to make
                                        // when the paywall intercepted them.
                                        if (pendingPlanSaveAfterPaywall) {
                                            pendingPlanSaveAfterPaywall = false
                                            chatVm.approveCurrentPlan()
                                        }
                                    } else {
                                        // Products may not be approved yet — show error or grant for testing
                                        println("Paywall: Purchase failed — products may not be approved by Apple yet")
                                    }
                                }
                            },
                            onRestore = {
                                println("Paywall: Restore tapped")
                                live.localapp.local.platform.restorePurchases { restored ->
                                    println("Paywall: Restore result=$restored")
                                    if (restored) {
                                        premiumGate.hasPaidSubscription = true
                                        showPaywall = false
                                        if (pendingPlanSaveAfterPaywall) {
                                            pendingPlanSaveAfterPaywall = false
                                            chatVm.approveCurrentPlan()
                                        }
                                    } else {
                                        // Fall back to a fresh status check — in sandbox/test
                                        // environments the restore API sometimes returns false
                                        // while the account does have an active sub.
                                        live.localapp.local.platform.checkSubscriptionStatus { isPro, expiry ->
                                            if (isPro) {
                                                premiumGate.hasPaidSubscription = true
                                                if (expiry != null) premiumGate.paidSubscriptionExpiry = expiry.take(10)
                                                showPaywall = false
                                                if (pendingPlanSaveAfterPaywall) {
                                                    pendingPlanSaveAfterPaywall = false
                                                    chatVm.approveCurrentPlan()
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            onDismiss = {
                                showPaywall = false
                                // User explicitly dismissed — drop the pending save so
                                // we don't auto-fire it on the next paywall close.
                                pendingPlanSaveAfterPaywall = false
                            },
                        )
                    }
                }
            }
        }

        // Login-required overlay removed — every "Sign in" trigger now routes
        // directly to phone-number entry via onLogout().

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
private val NAV_TABS = listOf(Tab.HOME, Tab.PLACES, Tab.PLANS)

@Composable
private fun BottomTabBar(
    selectedTab: Tab,
    onSelect: (Tab) -> Unit,
    onCreatePlanClick: () -> Unit = {},
    userInitial: String = "",
) {
    val haptic = LocalHapticFeedback.current
    val pillShape = RoundedCornerShape(22.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 4.dp),
    ) {
        // Floating pill — left side, narrower to make room for Create Plan pill
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 178.dp) // room for Create Plan button + margin
                .height(52.dp)
                .background(
                    Color(0xFFF0EDF6).copy(alpha = 0.92f),
                    pillShape,
                )
                .border(0.5.dp, Color(0xFFE0DCE8).copy(alpha = 0.6f), pillShape)
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
                                    .size(12.dp)
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
                                        fontSize = 11.sp,
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

        // Create Plan pill — bottom right.
        live.localapp.local.ui.plans.CreatePlanButton(
            onClick = onCreatePlanClick,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp),
        )
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

// Build a minimal Event from a plan slot's own data. Used as a fallback when
// the slot's eventId doesn't resolve in the events table — Bo-web finds (point
// at bo_web_events, not events), deactivated past-event rows, and id-prefix
// mismatches all leave the DB lookup empty. Without this fallback the tap was
// a silent no-op; with it the user always lands on a detail screen.
private fun live.localapp.local.domain.model.PlanSlot.toFallbackEvent(): live.localapp.local.domain.model.Event =
    live.localapp.local.domain.model.Event(
        id = eventId ?: id,
        title = name,
        description = detail.takeIf { it.isNotBlank() },
        category = venueCategory ?: "Event",
        subcategory = null,
        imageUrl = imageUrl,
        startAt = null,
        endAt = null,
        isFree = isFree,
        priceMin = priceMin,
        priceMax = priceMax,
        ticketUrl = ticketUrl,
        venueName = venueName ?: name,
        venueAddress = venueAddress,
        venueCity = venueCity ?: "",
        venueLat = venueLat,
        venueLng = venueLng,
        city = venueCity ?: "",
        source = "plan_slot",
        sourceUrl = sourceUrl,
        venueDescription = null,
        venueImageUrl = imageUrl,
        timeKnown = false,
    )

// Build a minimal Venue from a plan slot's own data. Mirrors toFallbackEvent
// for restaurant / venue stops where searchByName misses (Bo-recommended
// venues that aren't in the DB).
private fun live.localapp.local.domain.model.PlanSlot.toFallbackVenue(): live.localapp.local.domain.model.Venue =
    live.localapp.local.domain.model.Venue(
        id = venueName ?: id,
        name = venueName ?: name,
        address = venueAddress,
        city = venueCity ?: "",
        state = null,
        country = null,
        latitude = venueLat,
        longitude = venueLng,
        imageUrl = imageUrl,
        photoUrl = imageUrl,
        description = null,
        category = venueCategory,
        googleRating = venueRating,
        website = venueWebsite,
        reservationUrl = reservationUrl,
    )

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

            // Show reward if this tier has one
            val reward = live.localapp.local.ui.chat.PremiumGate().rewardForTier(event.newTier)
            if (reward != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    reward,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        color = AppColors.Sun,
                    ),
                )
            }

            Spacer(Modifier.height(40.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(30.dp))
                    .background(AppColors.Coral)
                    .clickable {
                        presentShareSheet(
                            "I just reached ${event.newTier.label} on Local with ${formatNumber(event.totalPoints)} points!",
                            "https://apps.apple.com/app/id6761425705",
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
