package live.localapp.local.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppFonts
import androidx.compose.ui.unit.dp
import live.localapp.local.platform.RequestLocationEffect
import live.localapp.local.platform.checkLocationPermissionGranted
import live.localapp.local.ui.theme.AppColors

private val STEP_ORDER = OnboardingStep.entries.toList()

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onComplete: (lat: Double?, lng: Double?) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var requestingLocation by remember { mutableStateOf(false) }
    // One-shot: auto-skip the LOCATION ask only on first arrival when permission
    // is already granted. Without this, pressing Back to LOCATION (from the
    // shortened flow's NOTIFICATIONS step) would instantly re-skip forward.
    var pregrantSkipped by remember { mutableStateOf(false) }
    var showSettingsAlert by remember { mutableStateOf(false) }
    var previousStepIndex by remember { mutableStateOf(0) }

    val currentIndex = STEP_ORDER.indexOf(state.currentStep)
    val isGoingBack = currentIndex < previousStepIndex
    previousStepIndex = currentIndex

    LaunchedEffect(state.currentStep) {
        live.localapp.local.observability.Analytics.track(
            live.localapp.local.observability.AnalyticsEvent.onboardingStepViewed(
                stepName = state.currentStep.name.lowercase(),
                stepIndex = currentIndex,
            )
        )
    }

    // Platform location permission + GPS
    RequestLocationEffect(
        active = requestingLocation,
        onLocation = { lat, lng ->
            requestingLocation = false
            viewModel.onAllowLocation(lat, lng)
        },
        onDenied = {
            requestingLocation = false
            viewModel.onDenyLocation()
        },
    )

    // If system-level location permission is already granted (e.g. a prior install
    // on this device), skip the ask screen — advance to NOTIFICATIONS (shortened
    // flow) and kick off the GPS fetch. RequestLocationEffect above stays mounted
    // across steps, so coords still arrive via onAllowLocation on the next screen.
    LaunchedEffect(state.currentStep) {
        if (state.currentStep == OnboardingStep.LOCATION &&
            !requestingLocation &&
            !pregrantSkipped &&
            checkLocationPermissionGranted()
        ) {
            pregrantSkipped = true
            viewModel.onLocationPregranted()
            requestingLocation = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = state.currentStep,
            transitionSpec = {
                if (isGoingBack) {
                    (slideInHorizontally(tween(350)) { -it } + fadeIn(tween(250)))
                        .togetherWith(slideOutHorizontally(tween(300)) { it / 3 } + fadeOut(tween(200)))
                } else {
                    (slideInHorizontally(tween(350)) { it } + fadeIn(tween(250)))
                        .togetherWith(slideOutHorizontally(tween(300)) { -it / 3 } + fadeOut(tween(200)))
                }
            },
        ) { step ->
            Box(modifier = Modifier.fillMaxSize()) {
                when (step) {
                    OnboardingStep.SPLASH -> SplashScreen(
                        onComplete = { viewModel.onSplashComplete() },
                    )
                    OnboardingStep.LOCATION -> LocationAskScreen(
                        onAllow = { requestingLocation = true },
                        onManual = { viewModel.onDenyLocation() },
                    )
                    OnboardingStep.MANUAL_ADDRESS -> ManualAddressScreen(
                        onSubmit = { lat, lng, city, state -> viewModel.onManualAddressSubmit(lat, lng, city, state) },
                        onBack = { viewModel.onBackFromManualAddress() },
                    )
                    OnboardingStep.SUCCESS -> SuccessScreen(
                        eventCount = state.eventCount,
                        venueCount = state.venueCount,
                        onComplete = { viewModel.onSuccessComplete() },
                    )
                    OnboardingStep.NEIGHBOURHOODS -> {
                        val userState = state.neighborhoods.firstOrNull()?.stateCode
                        NeighbourhoodsScreen(
                            neighborhoods = state.neighborhoods,
                            selected = state.selectedNeighbourhoods,
                            onToggle = viewModel::toggleNeighbourhood,
                            onNext = { viewModel.onNeighbourhoodsNext() },
                            onSkipAll = { viewModel.onSkipAll() },
                            userStateCode = userState,
                        )
                    }
                    OnboardingStep.VENUES -> VenuesScreen(
                        venues = state.venues,
                        selectedNeighborhoods = state.selectedNeighbourhoods,
                        selected = state.selectedVenues,
                        onToggle = viewModel::toggleVenue,
                        onNext = { viewModel.onVenuesNext() },
                        onSkip = { viewModel.onSkipVenues() },
                        onBack = { viewModel.onBackFromVenues() },
                    )
                    OnboardingStep.CATEGORIES -> CategoriesScreen(
                        categoryCounts = state.categoryCounts,
                        categoryPhotos = state.categoryPhotos,
                        selected = state.selectedCategories,
                        onToggle = viewModel::toggleCategory,
                        onNext = { viewModel.onCategoriesNext() },
                        onSkip = { viewModel.onSkipCategories() },
                        isValid = state.categoriesValid,
                        onBack = { viewModel.onBackFromCategories() },
                    )
                    OnboardingStep.FAVOURITES -> FavouritesScreen(
                        events = state.savedEvents,
                        isPersonalised = state.isPersonalised,
                        onRemove = viewModel::removeEvent,
                        onNext = { viewModel.onFavouritesNext() },
                    )
                    OnboardingStep.NOTIFICATIONS -> NotificationsScreen(
                        tickets = state.notifTickets,
                        nearby = state.notifNearby,
                        venues = state.notifVenues,
                        onToggleTickets = viewModel::toggleNotifTickets,
                        onToggleNearby = viewModel::toggleNotifNearby,
                        onToggleVenues = viewModel::toggleNotifVenues,
                        onNext = {
                            val notifGranted = state.notifTickets || state.notifNearby || state.notifVenues
                            live.localapp.local.observability.Analytics.track(
                                live.localapp.local.observability.AnalyticsEvent.onboardingCompleted(
                                    totalSeconds = 0L,
                                    notificationsGranted = notifGranted,
                                )
                            )
                            if (notifGranted) {
                                live.localapp.local.platform.requestNotificationPermission { _ ->
                                    onComplete(state.userLat, state.userLng)
                                }
                            } else {
                                onComplete(state.userLat, state.userLng)
                            }
                        },
                        onSkip = {
                            live.localapp.local.observability.Analytics.track(
                                live.localapp.local.observability.AnalyticsEvent.onboardingCompleted(
                                    totalSeconds = 0L,
                                    notificationsGranted = false,
                                )
                            )
                            onComplete(state.userLat, state.userLng)
                        },
                        onBack = { viewModel.onBackFromNotifications() },
                    )
                    OnboardingStep.PHONE -> {
                        // Phone auth now happens before onboarding — skip this step
                        onComplete(state.userLat, state.userLng)
                    }
                }
            }
        }

        // Tracker overlay — hidden on splash
        val fills = state.trackerFills
        if (fills != null) {
            OnboardingTracker(
                fills = fills,
                accentColor = accentColorForStep(state.currentStep),
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 6.dp, start = 20.dp, end = 20.dp),
            )
        }
    }
}

@Composable
private fun OnboardingTracker(
    fills: List<Float>,
    accentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val animatedAccent by androidx.compose.animation.animateColorAsState(
        targetValue = accentColor,
        animationSpec = tween(450),
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        fills.forEach { target ->
            val animatedFill by animateFloatAsState(
                targetValue = target,
                animationSpec = tween(450),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(AppColors.Ink.copy(alpha = 0.08f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedFill)
                        .height(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(animatedAccent),
                )
            }
        }
    }
}

/** Maps each onboarding step to its primary accent color so the progress
 *  tracker matches what the user sees on screen. */
private fun accentColorForStep(step: OnboardingStep): androidx.compose.ui.graphics.Color =
    when (step) {
        OnboardingStep.LOCATION,
        OnboardingStep.MANUAL_ADDRESS -> AppColors.Sky
        OnboardingStep.NEIGHBOURHOODS,
        OnboardingStep.VENUES,
        OnboardingStep.CATEGORIES,
        OnboardingStep.FAVOURITES -> AppColors.Plum
        OnboardingStep.NOTIFICATIONS -> AppColors.Sun
        OnboardingStep.SUCCESS -> AppColors.Sage
        OnboardingStep.PHONE,
        OnboardingStep.SPLASH -> AppColors.Coral
    }
