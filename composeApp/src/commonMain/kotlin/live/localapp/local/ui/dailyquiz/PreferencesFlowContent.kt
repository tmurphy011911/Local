package live.localapp.local.ui.dailyquiz

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.feature.onboarding.CategoriesScreen
import live.localapp.local.feature.onboarding.NeighbourhoodsScreen
import live.localapp.local.feature.onboarding.OnboardingStep
import live.localapp.local.feature.onboarding.OnboardingViewModel
import live.localapp.local.feature.onboarding.VenuesScreen
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

/**
 * The lobby-launched preferences flow — the existing onboarding pick screens
 * (Neighbourhoods → Venues → Categories, Favourites intentionally dropped)
 * driven by a fresh [OnboardingViewModel] in preferences-only mode.
 *
 * Completion = finishing the Categories step via its primary CTA, which saves
 * the selections + flips preferences_completed (the server awards 1000 FOMO on
 * that transition) and then fires [onComplete]. Backing out of any step
 * (skip-all, category skip, sheet dismiss) fires [onClose] without completing,
 * so the CTA stays until the user actually finishes.
 */
@Composable
fun PreferencesFlowContent(
    vm: OnboardingViewModel,
    onComplete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.beginPreferencesOnly() }

    // "Completing" (save + flip preferences_completed + award FOMO) only counts
    // if the user actually picked something. Clicking straight through every step
    // with nothing selected closes without completing, so the CTA stays.
    val hasSelection = s.selectedNeighbourhoods.isNotEmpty() ||
        s.selectedVenues.isNotEmpty() || s.selectedCategories.isNotEmpty()

    Box(modifier.fillMaxSize()) {
        when (s.currentStep) {
            OnboardingStep.NEIGHBOURHOODS ->
                if (s.neighborhoods.isEmpty() && s.isLoadingData) {
                    PreferencesSkeleton(onSkipAll = onClose)
                } else {
                    NeighbourhoodsScreen(
                        neighborhoods = s.neighborhoods,
                        selected = s.selectedNeighbourhoods,
                        onToggle = vm::toggleNeighbourhood,
                        onNext = { vm.onNeighbourhoodsNext() },
                        onSkipAll = onClose,
                        userStateCode = s.neighborhoods.firstOrNull()?.stateCode,
                    )
                }
            OnboardingStep.VENUES -> VenuesScreen(
                venues = s.venues,
                selectedNeighborhoods = s.selectedNeighbourhoods,
                selected = s.selectedVenues,
                onToggle = vm::toggleVenue,
                onNext = { vm.onVenuesNext() },
                onSkip = { vm.onSkipVenues() },
                onBack = { vm.onBackFromVenues() },
            )
            OnboardingStep.CATEGORIES -> CategoriesScreen(
                categoryCounts = s.categoryCounts,
                categoryPhotos = s.categoryPhotos,
                selected = s.selectedCategories,
                onToggle = vm::toggleCategory,
                // Continue completes only if something was picked; otherwise it's
                // a skip-through, so close without awarding.
                onNext = { if (hasSelection) vm.savePreferencesOnly { onComplete() } else onClose() },
                onSkip = onClose,
                isValid = s.categoriesValid,
                onBack = { vm.onBackFromCategories() },
            )
            // The flow advanced past the pick steps (e.g. no category data to
            // show) — nothing left to select, so just close without completing.
            OnboardingStep.NOTIFICATIONS, OnboardingStep.SUCCESS ->
                LaunchedEffect(Unit) { onClose() }
            // Transient default (SPLASH/LOCATION) before beginPreferencesOnly
            // lands on NEIGHBOURHOODS.
            else -> PreferencesSkeleton(onSkipAll = onClose)
        }
    }
}

/**
 * Skeleton for the first pick screen — mirrors NeighbourhoodsScreen's layout
 * (gradient bg, title/subtitle, search bar, 2-column card grid) so the swap to
 * real content doesn't reflow. Shimmers via an animated placeholder alpha.
 */
@Composable
private fun PreferencesSkeleton(onSkipAll: () -> Unit) {
    val shimmer by animateFloatAsState(
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "prefsSkeleton",
    )
    val c = AppColors.Pebble.copy(alpha = 0.3f + shimmer * 0.2f)
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFFF8F5FF), Color(0xFFFAF8FC), Color(0xFFF0F5FF))),
        ),
    ) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(top = 20.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                // Real, tappable escape hatch during a slow load — matches the
                // "Skip all →" affordance on the loaded screen so the user is
                // never trapped waiting.
                Text(
                    "Skip all →",
                    modifier = Modifier
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSkipAll)
                        .padding(4.dp),
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = AppColors.Ink3),
                )
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(220.dp).height(26.dp).clip(RoundedCornerShape(8.dp)).background(c))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.width(160.dp).height(14.dp).clip(RoundedCornerShape(7.dp)).background(c))
                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(c))
                Spacer(Modifier.height(20.dp))
                repeat(4) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        repeat(2) {
                            Box(Modifier.weight(1f).height(96.dp).clip(RoundedCornerShape(16.dp)).background(c))
                        }
                    }
                }
            }
        }
    }
}
