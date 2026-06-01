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
import live.localapp.local.feature.auth.ColdStartRouter
import live.localapp.local.feature.auth.PhoneAuthScreen
import live.localapp.local.feature.auth.PhoneAuthViewModel
import live.localapp.local.feature.auth.RouteDestination
import live.localapp.local.feature.onboarding.OnboardingScreen
import live.localapp.local.feature.onboarding.OnboardingViewModel
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.model.Venue
import live.localapp.local.domain.repository.EventRepository
import live.localapp.local.domain.repository.PlanRepository
import live.localapp.local.domain.repository.VenueRepository
import live.localapp.local.domain.service.SplashConfigService
import live.localapp.local.feature.onboarding.SplashScreen
import live.localapp.local.feature.onboarding.SplashVariant
import androidx.compose.runtime.rememberCoroutineScope
import live.localapp.local.platform.checkClipboardForDeepLink
import live.localapp.local.platform.checkInstallReferrerForDeepLink
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
        val coldStartRouter = remember(supabaseClient, userPrefsRepo) {
            ColdStartRouter(supabaseClient, userPrefsRepo)
        }

        // Splash kill switch. Reads the cached flag synchronously for this
        // cold start (default true) and fires an async refresh from Supabase
        // that lands on the *next* launch. When the flag is false, the splash
        // collapses to a static wordmark frame.
        val splashConfig = remember { SplashConfigService(supabaseClient) }
        val splashVariant = remember {
            if (splashConfig.isAnimationEnabled()) SplashVariant.Animated
            else SplashVariant.Static
        }
        LaunchedEffect(Unit) { splashConfig.refreshFromRemoteAsync() }

        // Resolve the PlanSyncer once at startup so its drain loop is alive
        // for the full session and any persisted ops from a prior run drain
        // immediately. Without this, the syncer would only spin up the first
        // time a ViewModel happens to ask for it.
        remember { KoinPlatform.getKoin().get<live.localapp.local.data.sync.PlanSyncer>() }

        var appState by remember { mutableStateOf(AppState.SPLASH) }
        var justOnboarded by remember { mutableStateOf(false) }
        var userLat by remember { mutableStateOf<Double?>(null) }
        var userLng by remember { mutableStateOf<Double?>(null) }
        // Set by the splash's session-check LaunchedEffect. The splash always
        // plays its full animation; on completion we route here (HOME if the
        // user is already authenticated, otherwise AUTH or DEEP_LINK).
        var postSplashRoute by remember { mutableStateOf<AppState?>(null) }

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

        // Pre-auth deep-link callback. iOS onOpenURL / Android onNewIntent may fire
        // AFTER Compose renders, so this catches Universal Links that arrive too
        // late for the splash clipboard check below. The bridge buffers paths
        // received before this callback is registered.
        LaunchedEffect(Unit) {
            setDeepLinkCallback { path ->
                appScope.launch {
                    try {
                        val linkType = when {
                            path.startsWith("/p/") -> "plan"
                            path.startsWith("/e/") -> "event"
                            path.startsWith("/v/") -> "venue"
                            else -> "other"
                        }
                        when {
                            path.startsWith("/p/") -> {
                                val token = path.removePrefix("/p/")
                                val plan = KoinPlatform.getKoin().get<PlanRepository>().getSharedPlan(token)
                                if (plan != null) {
                                    deepLinkContent = DeepLinkContent.SharedPlan(plan)
                                    println("App: deep link plan via callback '${plan.title}'")
                                    live.localapp.local.observability.Analytics.track(
                                        live.localapp.local.observability.AnalyticsEvent.shareLinkOpened(linkType = "plan", planId = plan.id)
                                    )
                                    live.localapp.local.observability.Analytics.track(
                                        live.localapp.local.observability.AnalyticsEvent.planInviteAccepted(
                                            planId = plan.id,
                                            wasSignedIn = appState != live.localapp.local.AppState.AUTH,
                                        )
                                    )
                                }
                            }
                            path.startsWith("/e/") -> {
                                val eventId = path.removePrefix("/e/")
                                val event = KoinPlatform.getKoin().get<EventRepository>().getEvent(eventId)
                                if (event != null) {
                                    deepLinkContent = DeepLinkContent.SharedEvent(event)
                                    println("App: deep link event via callback '${event.title}'")
                                    live.localapp.local.observability.Analytics.track(
                                        live.localapp.local.observability.AnalyticsEvent.shareLinkOpened(linkType = "event", eventId = event.id)
                                    )
                                }
                            }
                            path.startsWith("/v/") -> {
                                val venueId = path.removePrefix("/v/")
                                val venue = KoinPlatform.getKoin().get<VenueRepository>().getVenuesByIds(listOf(venueId)).firstOrNull()
                                if (venue != null) {
                                    deepLinkContent = DeepLinkContent.SharedVenue(venue)
                                    println("App: deep link venue via callback '${venue.name}'")
                                    live.localapp.local.observability.Analytics.track(
                                        live.localapp.local.observability.AnalyticsEvent.shareLinkOpened(linkType = "venue")
                                    )
                                }
                            }
                        }
                        // Fire-and-forget no-op reference so unused-var linters stay quiet.
                        if (linkType.isEmpty()) {}
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
                SplashScreen(
                    onComplete = {
                        if (appState == AppState.SPLASH) {
                            appState = postSplashRoute
                                ?: if (deepLinkContent != null) AppState.DEEP_LINK else AppState.AUTH
                        }
                    },
                    appReady = postSplashRoute != null,
                    variant = splashVariant,
                )

                // While splash plays, check for deep link + existing session
                LaunchedEffect(Unit) {
                    // Deferred deep link: Play Install Referrer (Android, reliable)
                    // first, then clipboard fallback (iOS App Store install path).
                    val deepLink = checkInstallReferrerForDeepLink() ?: checkClipboardForDeepLink()
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

                    // Resolve the post-splash destination in parallel with the
                    // splash animation. We never set appState here — that would
                    // cut the splash off mid-play. Instead we stash the resolved
                    // route in postSplashRoute and let SplashScreen.onComplete
                    // pick it up when the animation finishes naturally.
                    //
                    // postSplashRoute also doubles as the "app-ready" signal
                    // for the splash tap-line gate, so we must set it on every
                    // path — including timeouts / errors — via the router's
                    // unauthenticated fallback.
                    //
                    // All session + profile logic lives in ColdStartRouter
                    // (feature/auth/ColdStartRouter.kt). The router waits for
                    // Supabase to finish hydrating, classifies the user, and
                    // returns one of {Home, Onboarding(resume), Auth, DeepLink}.
                    // See that file for the full state machine and security
                    // invariants (no-session-no-home, clear-on-RefreshFailure).
                    postSplashRoute = when (
                        val dest = coldStartRouter.resolve(deepLinkPending = deepLinkContent != null)
                    ) {
                        is RouteDestination.Home -> {
                            authUserId = dest.authUserId
                            AppState.HOME
                        }
                        is RouteDestination.Onboarding -> {
                            authUserId = dest.authUserId
                            // ONBOARDING resume vs fresh is handled by the
                            // OnboardingViewModel — see its init path. We just
                            // hand it a valid authUserId so the eventual
                            // user_profiles upsert lands on the right row.
                            AppState.ONBOARDING
                        }
                        RouteDestination.DeepLink -> AppState.DEEP_LINK
                        RouteDestination.Auth -> AppState.AUTH
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
                    // The X-close affordance on PhoneAuthScreen would otherwise
                    // let an unauthenticated user reach HOME with authUserId=""
                    // and silently RLS-fail every query. We only expose it under
                    // the test-bypass flag so the bypass is impossible in prod.
                    onSkip = if (live.localapp.local.Config.TEST_AUTH_BYPASS) {
                        { appState = AppState.HOME }
                    } else null,
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
                            // HomeViewModel is a singleton — its initial pref
                            // load happened before auth/onboarding (userId
                            // was null then). Without this refresh the For
                            // You feed keeps firing anon edge-fn calls and
                            // favVenue/neighborhood rails never appear.
                            try {
                                KoinPlatform.getKoin()
                                    .get<live.localapp.local.ui.home.HomeViewModel>()
                                    .refreshUserPreferences()
                            } catch (_: Throwable) { /* never block nav */ }
                            justOnboarded = true
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
                            live.localapp.local.observability.Analytics.track(
                                live.localapp.local.observability.AnalyticsEvent.signedOut()
                            )
                            live.localapp.local.observability.Analytics.reset()
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
                    showTutorial = justOnboarded,
                )
            }
        }
    }
}
