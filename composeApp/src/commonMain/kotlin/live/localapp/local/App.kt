package live.localapp.local

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import live.localapp.local.platform.hasNativeHeroTransition
import live.localapp.local.platform.showNativeEventDetail
import live.localapp.local.platform.showNativePlanDetail
import live.localapp.local.platform.showNativeVenueDetail
import live.localapp.local.platform.updateNativeVenueDetailEvents
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
import live.localapp.local.domain.service.SplashConfigService
import live.localapp.local.feature.onboarding.SplashScreen
import live.localapp.local.feature.onboarding.SplashVariant
import live.localapp.local.feature.deeplink.DeepLinkContent
import live.localapp.local.feature.deeplink.DeepLinkController
import live.localapp.local.ui.navigation.AppNavigation
import live.localapp.local.ui.detail.EventDetailScreen
import live.localapp.local.ui.plans.PlanDetailScreen
import live.localapp.local.ui.theme.LocalAppTheme
import org.koin.mp.KoinPlatform

private enum class AppState {
    SPLASH, DEEP_LINK, AUTH, ONBOARDING, HOME
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

        // Single owner of deep-link resolution. start() is idempotent; it
        // registers the one runtime Universal Link callback and runs the
        // one-shot deferred (install referrer / clipboard) check.
        val deepLinkController = remember { KoinPlatform.getKoin().get<DeepLinkController>() }
        remember { deepLinkController.start(); true }
        // The resolved shared link (plan/event/venue), or null. Logged-out users
        // see it as a full-screen preview here; logged-in users get it as an
        // overlay inside AppNavigation. Whoever presents it calls consume().
        val deepLink by deepLinkController.pending.collectAsState()

        // Splash kill switch. Reads the cached flag synchronously for this
        // cold start (default true) and fires an async refresh from Supabase
        // that lands on the *next* launch. When the flag is false, the splash
        // collapses to a static wordmark frame.
        val splashConfig = remember { SplashConfigService(supabaseClient) }
        // Record this open once, up front: returns whether it's the first-ever
        // launch (drives the variant) and an optional recency/streak line that
        // overrides the Claude greeting (welcome-back or milestone). recordOpen
        // flips the first-launch flag as a side effect, so it must run here in
        // remember — before the variant is read — so the first launch still
        // sees isFirstEver == true.
        val openState = remember { splashConfig.recordOpen(userPrefsRepo.getLocalFirstName()) }
        // Variant split: kill-switch off → Static for everyone. Otherwise the
        // first-ever cold start plays the full editorial intro; every later
        // fresh open plays the returning-user Bo splash.
        val splashVariant = remember {
            when {
                !splashConfig.isAnimationEnabled() -> SplashVariant.Static
                openState.isFirstEver -> SplashVariant.Animated
                else -> SplashVariant.Returning
            }
        }
        LaunchedEffect(Unit) {
            splashConfig.refreshFromRemoteAsync()
        }

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
        var authEmail by remember { mutableStateOf("") }
        var authUserId by remember { mutableStateOf("") }

        // If a deep link resolves while the user is logged out (on splash or the
        // phone-auth screen), pivot to the DEEP_LINK preview so the shared
        // plan/event/venue is shown first. Logged-in users (appState == HOME)
        // are handled by AppNavigation, which overlays the link on Home — so we
        // deliberately only react on AUTH here.
        LaunchedEffect(deepLink) {
            if (deepLink != null && appState == AppState.AUTH) {
                appState = AppState.DEEP_LINK
            }
        }

        when (appState) {
            AppState.SPLASH -> {
                SplashScreen(
                    onComplete = {
                        if (appState == AppState.SPLASH) {
                            // Base destination comes from auth state only. A
                            // pending deep link overrides AUTH with the preview;
                            // a logged-in HOME keeps HOME (AppNavigation overlays
                            // the link there).
                            val base = postSplashRoute ?: AppState.AUTH
                            appState = if (deepLink != null && base == AppState.AUTH) {
                                AppState.DEEP_LINK
                            } else {
                                base
                            }
                        }
                    },
                    appReady = postSplashRoute != null,
                    variant = splashVariant,
                    specialLine = openState.specialLine,
                )

                // While splash plays, resolve the auth-based destination. Deep
                // links are owned by DeepLinkController (registered + deferred-
                // checked at the top of App) and observed via `deepLink` — they
                // are no longer resolved here, which removes the old snapshot
                // race where a link arriving after this ran was missed.
                LaunchedEffect(Unit) {
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
                    // returns one of {Home, Onboarding(resume), Auth}.
                    // See that file for the full state machine and security
                    // invariants (no-session-no-home, clear-on-RefreshFailure).
                    postSplashRoute = when (val dest = coldStartRouter.resolve()) {
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
                        RouteDestination.Auth -> AppState.AUTH
                    }
                }
            }

            AppState.DEEP_LINK -> {
                // Leaving the preview: clear the pending link first so the
                // LaunchedEffect(deepLink) rescue above doesn't immediately
                // bounce us back into DEEP_LINK, then drop to sign-in.
                val dismissToAuth = {
                    deepLinkController.consume()
                    appState = AppState.AUTH
                }
                when (val content = deepLink) {
                    is DeepLinkContent.SharedPlan -> {
                        if (hasNativeHeroTransition) {
                            // iOS: present the native SwiftUI PlanDetailView in
                            // read-only shared-invite mode over a blank surface.
                            // Logged-out save stashes the plan for post-auth accept;
                            // dismissing routes to sign-in.
                            Box(modifier = Modifier.fillMaxSize().background(Color(0xFFFAFAFA)))
                            LaunchedEffect(content.plan.id) {
                                showNativePlanDetail(
                                    plan = content.plan,
                                    cardX = 0f, cardY = 0f, cardW = 0f, cardH = 0f,
                                    isShared = true,
                                    onShare = {},
                                    onDismissed = { dismissToAuth() },
                                    onSaveToPlan = {
                                        val plansVm = KoinPlatform.getKoin().get<live.localapp.local.ui.plans.PlansViewModel>()
                                        plansVm.setPendingAcceptAfterAuth(content.plan)
                                        dismissToAuth()
                                    },
                                )
                            }
                        } else {
                            PlanDetailScreen(
                                plan = content.plan,
                                isShared = true,
                                // A user viewing a shared invite link is by definition not the creator.
                                isCreator = false,
                                onBack = dismissToAuth,
                                onSaveToPlan = {
                                    val plansVm = KoinPlatform.getKoin().get<live.localapp.local.ui.plans.PlansViewModel>()
                                    plansVm.setPendingAcceptAfterAuth(content.plan)
                                    dismissToAuth()
                                },
                            )
                        }
                    }
                    is DeepLinkContent.SharedEvent -> {
                        if (hasNativeHeroTransition) {
                            // iOS: present the native SwiftUI event detail over a
                            // blank surface. The Compose EventDetailScreen crashes
                            // on iOS (see AppNavigation's native shared-event path).
                            // Logged-out heart taps stash a pending favorite that
                            // the post-auth flow applies; dismissing routes to sign-in.
                            Box(modifier = Modifier.fillMaxSize().background(Color(0xFFFAFAFA)))
                            LaunchedEffect(content.event.id) {
                                showNativeEventDetail(
                                    event = content.event,
                                    cardX = 0f, cardY = 0f, cardW = 0f, cardH = 0f,
                                    cardImageUrl = content.event.imageUrl,
                                    onDismissed = { dismissToAuth() },
                                    onFavorited = { id ->
                                        val settings = com.russhwolf.settings.Settings()
                                        settings.putString("pending_favorite_event", id)
                                    },
                                    isFavorited = false,
                                )
                            }
                        } else {
                            EventDetailScreen(
                                event = content.event,
                                onBack = dismissToAuth,
                                sharedMode = true,
                                onSaveEvent = {
                                    // Store event ID to favorite after auth
                                    val settings = com.russhwolf.settings.Settings()
                                    settings.putString("pending_favorite_event", content.event.id)
                                    dismissToAuth()
                                },
                            )
                        }
                    }
                    is DeepLinkContent.SharedVenue -> {
                        if (hasNativeHeroTransition) {
                            // iOS: present the native SwiftUI venue detail. The
                            // Compose VenueDetailPage crashes on iOS (mirror the
                            // native shared-venue path in AppNavigation). Logged-out
                            // heart taps stash a pending favorite; dismiss → sign-in.
                            Box(modifier = Modifier.fillMaxSize().background(Color(0xFFFAFAFA)))
                            LaunchedEffect(content.venue.id) {
                                val meta = live.localapp.local.domain.repository.VenueWithMeta(
                                    venue = content.venue,
                                    primaryCategory = content.venue.category,
                                    tags = emptyList(),
                                    upcomingEventCount = 0,
                                    upcomingEvents = emptyList(),
                                    distanceMiles = null,
                                )
                                showNativeVenueDetail(
                                    venueWithMeta = meta,
                                    cardX = 0f, cardY = 0f, cardW = 0f, cardH = 0f,
                                    onDismissed = { dismissToAuth() },
                                    isFavorited = false,
                                    onFavorited = { _ ->
                                        val settings = com.russhwolf.settings.Settings()
                                        settings.putString("pending_favorite_venue", content.venue.id)
                                        settings.putString("pending_favorite_venue_category", content.venue.category ?: "")
                                    },
                                )
                                // The shared payload carries no event list — hydrate
                                // the native detail with the venue's upcoming events.
                                val events = try {
                                    KoinPlatform.getKoin()
                                        .get<live.localapp.local.domain.repository.VenueRepository>()
                                        .getUpcomingEventsForVenue(
                                            content.venue.name,
                                            content.venue.city,
                                            content.venue.latitude,
                                            content.venue.longitude,
                                        )
                                } catch (_: Exception) {
                                    emptyList()
                                }
                                updateNativeVenueDetailEvents(content.venue.id, events)
                            }
                        } else {
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
                                onBack = dismissToAuth,
                                isShared = true,
                                onSaveVenue = {
                                    val settings = com.russhwolf.settings.Settings()
                                    settings.putString("pending_favorite_venue", content.venue.id)
                                    settings.putString("pending_favorite_venue_category", content.venue.category ?: "")
                                    dismissToAuth()
                                },
                            )
                        }
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
                    onAuthComplete = { isNewUser, firstName, lastName, phone, email, userId ->
                        authUserId = userId
                        if (isNewUser) {
                            authFirstName = firstName
                            authLastName = lastName
                            authPhone = phone
                            authEmail = email
                            appState = AppState.ONBOARDING
                        } else {
                            appState = AppState.HOME
                        }
                    },
                    // Guest mode: dismissing the auth screen drops the user on HOME
                    // unauthenticated (authUserId=""). Browsing is anon-safe (the
                    // carousel feed runs anon), and authed surfaces are protected —
                    // plans/progress refreshes are isLoggedIn-guarded and every gated
                    // action routes the guest back to sign-in (showPaywallGate in
                    // AppNavigation). So a guest never silently RLS-fails into a
                    // broken authed query; they're sent to sign-in the moment they act.
                    onSkip = { appState = AppState.HOME },
                )
            }

            AppState.ONBOARDING -> {
                val viewModel = remember {
                    KoinPlatform.getKoin().get<OnboardingViewModel>().also { vm ->
                        if (authFirstName.isNotEmpty()) vm.updateFirstName(authFirstName)
                        if (authLastName.isNotEmpty()) vm.updateLastName(authLastName)
                        if (authPhone.isNotEmpty()) vm.updatePhoneNumber(authPhone)
                        if (authEmail.isNotEmpty()) vm.updateEmail(authEmail)
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
                            // Reset RevenueCat to an anonymous id so the next
                            // user doesn't inherit this account's entitlement.
                            try { live.localapp.local.platform.logOutRevenueCatUser() } catch (_: Exception) {}
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
