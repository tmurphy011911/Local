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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import live.localapp.local.ui.theme.AppColors

private val STEP_ORDER = OnboardingStep.entries.toList()

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onComplete: (lat: Double?, lng: Double?) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var requestingLocation by remember { mutableStateOf(false) }
    var showSettingsAlert by remember { mutableStateOf(false) }
    var previousStepIndex by remember { mutableStateOf(0) }

    val currentIndex = STEP_ORDER.indexOf(state.currentStep)
    val isGoingBack = currentIndex < previousStepIndex
    previousStepIndex = currentIndex

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
                        onAllow = { println("LOCATION: Allow tapped, setting requestingLocation=true"); requestingLocation = true },
                        onManual = { viewModel.onDenyLocation() },
                    )
                    OnboardingStep.MANUAL_ADDRESS -> ManualAddressScreen(
                        onSubmit = { lat, lng, city -> viewModel.onManualAddressSubmit(lat, lng, city) },
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
                        onNext = { onComplete(state.userLat, state.userLng) },
                        onSkip = { onComplete(state.userLat, state.userLng) },
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
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 6.dp, start = 20.dp, end = 20.dp),
            )
        }
    }
}

@Composable
private fun OnboardingTracker(fills: List<Float>, modifier: Modifier = Modifier) {
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
                        .background(AppColors.Coral),
                )
            }
        }
    }
}
