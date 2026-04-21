package live.localapp.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch
import live.localapp.local.domain.repository.UserPreferencesRepository
import live.localapp.local.feature.auth.PhoneAuthScreen
import live.localapp.local.feature.auth.PhoneAuthViewModel
import live.localapp.local.feature.onboarding.OnboardingScreen
import live.localapp.local.feature.onboarding.OnboardingViewModel
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.model.Venue
import live.localapp.local.domain.repository.EventRepository
import live.localapp.local.domain.repository.PlanRepository
import live.localapp.local.domain.repository.VenueRepository
import live.localapp.local.feature.onboarding.SplashScreen
import androidx.compose.runtime.rememberCoroutineScope
import live.localapp.local.platform.checkClipboardForDeepLink
import live.localapp.local.platform.clearClipboardDeepLink
import live.localapp.local.platform.setDeepLinkCallback
import live.localapp.local.ui.navigation.AppNavigation
import live.localapp.local.ui.detail.EventDetailScreen
import live.localapp.local.ui.plans.PlanDetailScreen
import live.localapp.local.ui.theme.LocalAppTheme
import org.koin.mp.KoinPlatform

private enum class AppState {
    SPLASH, DEEP_LINK, AUTH, ONBOARDING, HOME
}

/** What type of deep link was found */
private sealed class DeepLinkContent {
    data class SharedPlan(val plan: Plan) : DeepLinkContent()
    data class SharedEvent(val event: Event) : DeepLinkContent()
    data class SharedVenue(val venue: Venue) : DeepLinkContent()
}

@Composable
fun App() {
    LocalAppTheme {
        // Configure Coil's singleton ImageLoader once. Default memory cache is ~30 images
        // which causes constant re-decode during venue grid scroll. 25% of available RAM is
        // the Coil-recommended value and restores 60 fps scrolling.
        remember {
            coil3.SingletonImageLoader.setSafe { ctx ->
                coil3.ImageLoader.Builder(ctx)
                    .memoryCache {
                        coil3.memory.MemoryCache.Builder()
                            .maxSizePercent(ctx, 0.25)
                            .build()
                    }
                    .build()
            }
        }

        val supabaseClient = remember { KoinPlatform.getKoin().get<SupabaseClient>() }
        val userPrefsRepo = remember { KoinPlatform.getKoin().get<UserPreferencesRepository>() }

        var appState by remember { mutableStateOf(AppState.SPLASH) }
        var userLat by remember { mutableStateOf<Double?>(null) }
        var userLng by remember { mutableStateOf<Double?>(null) }

        // Version check
        var versionResult by remember { mutableStateOf<live.localapp.local.domain.service.VersionCheckResult?>(null) }
        var updateBannerDismissed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            val service = live.localapp.local.domain.service.VersionCheckService(supabaseClient)
            versionResult = service.check()
        }

        // Force update — blocks everything
        val forceUpdate = versionResult?.status == live.localapp.local.domain.service.VersionStatus.FORCE_UPDATE
        if (forceUpdate) {
            live.localapp.local.ui.common.ForceUpdateScreen(
                message = versionResult?.message ?: "Please update Local to continue.",
                onUpdate = { live.localapp.local.platform.openAppStoreListing() },
            )
            return@LocalAppTheme
        }

        // Auth info passed from auth screen to onboarding
        var authFirstName by remember { mutableStateOf("") }
        var authLastName by remember { mutableStateOf("") }
        var authPhone by remember { mutableStateOf("") }
        var authUserId by remember { mutableStateOf("") }

        // Deep link content (shown before auth for new users)
        var deepLinkContent by remember { mutableStateOf<DeepLinkContent?>(null) }

        // Scope that outlives the Splash LaunchedEffect — used by the deep-link
        // callback, which may fire any time after cold-start.
        val appScope = rememberCoroutineScope()

        // Register a pre-auth deep-link callback. iOS onOpenURL often fires AFTER
        // Compose renders, so this catches Universal Link /p/{token} tokens that
        // arrive too late for the clipboard check below. DeepLinkBridge on iOS
        // buffers the token if this callback hasn't been registered yet.
        LaunchedEffect(Unit) {
            setDeepLinkCallback { token ->
                appScope.launch {
                    try {
                        val plan = KoinPlatform.getKoin().get<PlanRepository>().getSharedPlan(token)
                        if (plan != null) {
                            deepLinkContent = DeepLinkContent.SharedPlan(plan)
                            clearClipboardDeepLink()
                            println("App: deep link plan via callback '${plan.title}'")
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // If a deep link arrives while the user is on the phone-auth screen,
        // pivot back to the DEEP_LINK state so the plan is shown first.
        LaunchedEffect(deepLinkContent) {
            if (deepLinkContent != null && appState == AppState.AUTH) {
                appState = AppState.DEEP_LINK
            }
        }

        when (appState) {
            AppState.SPLASH -> {
                SplashScreen(onComplete = {
                    if (appState == AppState.SPLASH) {
                        appState = if (deepLinkContent != null) AppState.DEEP_LINK else AppState.AUTH
                    }
                })

                // While splash plays, check for deep link + existing session
                LaunchedEffect(Unit) {
                    // Check clipboard for deep link
                    val deepLink = checkClipboardForDeepLink()
                    if (deepLink != null) {
                        try {
                            when {
                                deepLink.startsWith("/p/") -> {
                                    val token = deepLink.removePrefix("/p/")
                                    val plan = KoinPlatform.getKoin().get<PlanRepository>().getSharedPlan(token)
                                    if (plan != null) {
                                        deepLinkContent = DeepLinkContent.SharedPlan(plan)
                                        clearClipboardDeepLink()
                                        println("App: deep link plan '${plan.title}'")
                                    }
                                }
                                deepLink.startsWith("/e/") -> {
                                    val eventId = deepLink.removePrefix("/e/")
                                    val event = KoinPlatform.getKoin().get<EventRepository>().getEvent(eventId)
                                    if (event != null) {
                                        deepLinkContent = DeepLinkContent.SharedEvent(event)
                                        clearClipboardDeepLink()
                                        println("App: deep link event '${event.title}'")
                                    }
                                }
                                deepLink.startsWith("/v/") -> {
                                    val venueId = deepLink.removePrefix("/v/")
                                    val venues = KoinPlatform.getKoin().get<VenueRepository>().getVenuesByIds(listOf(venueId))
                                    val venue = venues.firstOrNull()
                                    if (venue != null) {
                                        deepLinkContent = DeepLinkContent.SharedVenue(venue)
                                        clearClipboardDeepLink()
                                        println("App: deep link venue '${venue.name}'")
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    try {
                        val session = supabaseClient.auth.currentSessionOrNull()
                        if (session != null) {
                            val userId = session.user?.id ?: ""
                            if (userId.isNotEmpty()) {
                                authUserId = userId
                                val recovered = userPrefsRepo.recoverProfileByAuthId(userId)
                                if (recovered != null) {
                                    appState = AppState.HOME
                                    return@LaunchedEffect
                                }
                            }
                        }
                        // Also check legacy local cache
                        val alreadyOnboarded = userPrefsRepo.isOnboardingComplete()
                        if (alreadyOnboarded) {
                            appState = AppState.HOME
                            return@LaunchedEffect
                        }
                        val recoveredId = userPrefsRepo.recoverProfile()
                        if (recoveredId != null) {
                            appState = AppState.HOME
                            return@LaunchedEffect
                        }
                        // Not authenticated — splash onComplete will set AUTH or SHARED_PLAN
                    } catch (_: Exception) {
                        val alreadyOnboarded = userPrefsRepo.isOnboardingComplete()
                        if (alreadyOnboarded) {
                            appState = AppState.HOME
                        }
                    }
                }
            }

            AppState.DEEP_LINK -> {
                when (val content = deepLinkContent) {
                    is DeepLinkContent.SharedPlan -> {
                        PlanDetailScreen(
                            plan = content.plan,
                            isShared = true,
                            // A user viewing a shared invite link is by definition not the creator.
                            isCreator = false,
                            onBack = { appState = AppState.AUTH },
                            onSaveToPlan = {
                                val plansVm = KoinPlatform.getKoin().get<live.localapp.local.ui.plans.PlansViewModel>()
                                plansVm.setPendingAcceptAfterAuth(content.plan)
                                appState = AppState.AUTH
                            },
                        )
                    }
                    is DeepLinkContent.SharedEvent -> {
                        EventDetailScreen(
                            event = content.event,
                            onBack = { appState = AppState.AUTH },
                            sharedMode = true,
                            onSaveEvent = {
                                // Store event ID to favorite after auth
                                val settings = com.russhwolf.settings.Settings()
                                settings.putString("pending_favorite_event", content.event.id)
                                appState = AppState.AUTH
                            },
                        )
                    }
                    is DeepLinkContent.SharedVenue -> {
                        // Simple venue preview with "Add to Favorites" CTA
                        live.localapp.local.ui.places.VenueDetailPage(
                            venueWithMeta = live.localapp.local.domain.repository.VenueWithMeta(
                                venue = content.venue,
                                primaryCategory = content.venue.category,
                                tags = emptyList(),
                                upcomingEventCount = 0,
                                upcomingEvents = emptyList(),
                                distanceMiles = null,
                            ),
                            events = emptyList(),
                            isLoading = false,
                            onBack = { appState = AppState.AUTH },
                            isShared = true,
                            onSaveVenue = {
                                val settings = com.russhwolf.settings.Settings()
                                settings.putString("pending_favorite_venue", content.venue.id)
                                settings.putString("pending_favorite_venue_category", content.venue.category ?: "")
                                appState = AppState.AUTH
                            },
                        )
                    }
                    null -> appState = AppState.AUTH
                }
            }

            AppState.AUTH -> {
                val viewModel = remember {
                    PhoneAuthViewModel(supabaseClient, userPrefsRepo)
                }
                PhoneAuthScreen(
                    viewModel = viewModel,
                    onAuthComplete = { isNewUser, firstName, lastName, phone, userId ->
                        authUserId = userId
                        if (isNewUser) {
                            authFirstName = firstName
                            authLastName = lastName
                            authPhone = phone
                            appState = AppState.ONBOARDING
                        } else {
                            appState = AppState.HOME
                        }
                    },
                    onSkip = { appState = AppState.HOME },
                )
            }

            AppState.ONBOARDING -> {
                val viewModel = remember {
                    KoinPlatform.getKoin().get<OnboardingViewModel>().also { vm ->
                        if (authFirstName.isNotEmpty()) vm.updateFirstName(authFirstName)
                        if (authLastName.isNotEmpty()) vm.updateLastName(authLastName)
                        if (authPhone.isNotEmpty()) vm.updatePhoneNumber(authPhone)
                        if (authUserId.isNotEmpty()) vm.setAuthUserId(authUserId)
                    }
                }
                OnboardingScreen(
                    viewModel = viewModel,
                    onComplete = { lat, lng ->
                        userLat = lat
                        userLng = lng
                        viewModel.saveOnboardingPreferences {
                            appState = AppState.HOME
                        }
                    },
                )
            }

            AppState.HOME -> {
                val scope = kotlinx.coroutines.MainScope()
                AppNavigation(
                    userLat = userLat,
                    userLng = userLng,
                    onLogout = {
                        scope.launch {
                            try {
                                supabaseClient.auth.signOut(io.github.jan.supabase.auth.SignOutScope.LOCAL)
                            } catch (_: Exception) {}
                            try {
                                supabaseClient.auth.clearSession()
                            } catch (_: Exception) {}
                            userPrefsRepo.clearLocal()
                            // Clear premium gate so new user starts fresh
                            try { live.localapp.local.ui.chat.PremiumGate().clear() } catch (_: Exception) {}
                            // Clear all in-memory caches so next user starts fresh
                            try { KoinPlatform.getKoin().get<live.localapp.local.domain.repository.CommunityInterestRepository>().clearCaches() } catch (_: Exception) {}
                            appState = AppState.AUTH
                        }
                    },
                    showUpdateBanner = !updateBannerDismissed && versionResult?.status == live.localapp.local.domain.service.VersionStatus.UPDATE_AVAILABLE,
                    onDismissUpdateBanner = { updateBannerDismissed = true },
                )
            }
        }
    }
}
