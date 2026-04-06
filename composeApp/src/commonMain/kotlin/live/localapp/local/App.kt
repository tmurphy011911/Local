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
import live.localapp.local.feature.onboarding.SplashScreen
import live.localapp.local.ui.navigation.AppNavigation
import live.localapp.local.ui.theme.LocalAppTheme
import org.koin.mp.KoinPlatform

private enum class AppState {
    SPLASH, AUTH, ONBOARDING, HOME
}

@Composable
fun App() {
    LocalAppTheme {
        val supabaseClient = remember { KoinPlatform.getKoin().get<SupabaseClient>() }
        val userPrefsRepo = remember { KoinPlatform.getKoin().get<UserPreferencesRepository>() }

        var appState by remember { mutableStateOf(AppState.SPLASH) }
        var userLat by remember { mutableStateOf<Double?>(null) }
        var userLng by remember { mutableStateOf<Double?>(null) }

        // Auth info passed from auth screen to onboarding
        var authFirstName by remember { mutableStateOf("") }
        var authLastName by remember { mutableStateOf("") }
        var authPhone by remember { mutableStateOf("") }
        var authUserId by remember { mutableStateOf("") }

        when (appState) {
            AppState.SPLASH -> {
                SplashScreen(onComplete = {
                    // After splash animation, check if user is already authenticated
                    appState = AppState.AUTH // will be overridden by LaunchedEffect if already logged in
                })

                // While splash plays, check for existing session in background
                LaunchedEffect(Unit) {
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
                        // Not authenticated — splash onComplete will set AUTH
                    } catch (_: Exception) {
                        val alreadyOnboarded = userPrefsRepo.isOnboardingComplete()
                        if (alreadyOnboarded) {
                            appState = AppState.HOME
                        }
                        // Otherwise splash onComplete will set AUTH
                    }
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
                            // Clear all in-memory caches so next user starts fresh
                            try { KoinPlatform.getKoin().get<live.localapp.local.domain.repository.CommunityInterestRepository>().clearCaches() } catch (_: Exception) {}
                            appState = AppState.AUTH
                        }
                    },
                )
            }
        }
    }
}
