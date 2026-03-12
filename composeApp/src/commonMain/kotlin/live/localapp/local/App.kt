package live.localapp.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import live.localapp.local.domain.repository.UserPreferencesRepository
import live.localapp.local.feature.onboarding.OnboardingScreen
import live.localapp.local.feature.onboarding.OnboardingViewModel
import live.localapp.local.ui.navigation.AppNavigation
import live.localapp.local.ui.theme.LocalAppTheme
import org.koin.mp.KoinPlatform

@Composable
fun App() {
    LocalAppTheme {
        val userPrefsRepo = remember { KoinPlatform.getKoin().get<UserPreferencesRepository>() }
        val alreadyOnboarded = remember { userPrefsRepo.isOnboardingComplete() }

        var onboardingComplete by remember { mutableStateOf(alreadyOnboarded) }
        var userLat by remember { mutableStateOf<Double?>(null) }
        var userLng by remember { mutableStateOf<Double?>(null) }

        if (onboardingComplete) {
            AppNavigation(userLat = userLat, userLng = userLng)
        } else {
            val viewModel = remember {
                KoinPlatform.getKoin().get<OnboardingViewModel>()
            }
            OnboardingScreen(
                viewModel = viewModel,
                onComplete = { lat, lng ->
                    userLat = lat
                    userLng = lng
                    viewModel.saveOnboardingPreferences {
                        onboardingComplete = true
                    }
                },
            )
        }
    }
}
