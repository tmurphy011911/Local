package live.localapp.local.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.OTP
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import live.localapp.local.domain.repository.UserPreferencesRepository
import live.localapp.local.observability.Analytics
import live.localapp.local.observability.AnalyticsEvent
import live.localapp.local.observability.MetaAppEvents

enum class AuthStep { PHONE, OTP }

data class PhoneAuthUiState(
    val phone: String = "",
    val otpCode: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val step: AuthStep = AuthStep.PHONE,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isExistingUser: Boolean = false,
    val authComplete: Boolean = false,
    val authUserId: String? = null,
)

class PhoneAuthViewModel(
    private val supabaseClient: SupabaseClient,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PhoneAuthUiState())
    val state: StateFlow<PhoneAuthUiState> = _state.asStateFlow()

    fun updatePhone(value: String) {
        // Only keep digits, max 10
        val digits = value.filter { it.isDigit() }.take(10)
        _state.update { it.copy(phone = digits, error = null) }
    }

    fun updateOtpCode(value: String) {
        val digits = value.filter { it.isDigit() }.take(6)
        _state.update { it.copy(otpCode = digits, error = null) }
        if (digits.length == 6) verifyOtp()
    }

    fun sendOtp() {
        val phone = _state.value.phone
        if (phone.length < 10) {
            _state.update { it.copy(error = "Please enter a valid 10-digit phone number") }
            return
        }

        if (live.localapp.local.Config.devAuthActive || live.localapp.local.Config.testAuthBypassActive) {
            // Dev-auth / test mode: no real SMS, go straight to OTP entry.
            _state.update { it.copy(step = AuthStep.OTP) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                supabaseClient.auth.signInWith(OTP) {
                    this.phone = "+1$phone"
                }
                _state.update { it.copy(isLoading = false, step = AuthStep.OTP) }
            } catch (e: Exception) {
                val message = when {
                    e.message?.contains("rate", ignoreCase = true) == true ->
                        "Too many attempts. Please wait a moment and try again."
                    e.message?.contains("invalid", ignoreCase = true) == true ->
                        "Invalid phone number. Please check and try again."
                    else -> "Failed to send code. Please try again."
                }
                _state.update { it.copy(isLoading = false, error = message) }
            }
        }
    }

    fun verifyOtp() {
        val s = _state.value
        if (s.otpCode.length < 6) {
            _state.update { it.copy(error = "Please enter the full 6-digit code") }
            return
        }

        if (live.localapp.local.Config.devAuthActive) {
            // Dev-auth (debug + dev backend): any 6-digit code works. Each phone
            // number maps to its own persisted dev account, signed-in if it exists
            // or signed-up on first use. We then reuse the SAME profile-check +
            // routing as the real OTP path, so a brand-new phone runs onboarding
            // (and is recognized as existing next time), while a pre-seeded number
            // (Config.DEV_EXISTING_USER_PHONE) goes straight to home.
            viewModelScope.launch {
                _state.update { it.copy(isLoading = true, error = null) }
                try {
                    devAuthSignInOrSignUp()
                    val userId = supabaseClient.auth.currentSessionOrNull()?.user?.id
                        ?: throw IllegalStateException("No session after dev-auth sign-in")
                    val hasProfile = userPreferencesRepository.profileExistsForAuthId(userId)
                    Analytics.identify(userId = userId)
                    if (hasProfile) {
                        userPreferencesRepository.recoverProfileByAuthId(userId)
                        _state.update {
                            it.copy(isLoading = false, isExistingUser = true, authComplete = true, authUserId = userId)
                        }
                    } else {
                        _state.update {
                            it.copy(isLoading = false, authComplete = true, authUserId = userId)
                        }
                    }
                } catch (e: Exception) {
                    println("DEBUG: dev-auth sign-in failed: ${e::class.simpleName}: ${e.message}")
                    _state.update { it.copy(isLoading = false, error = "Dev sign-in failed: ${e.message}") }
                }
            }
            return
        }

        if (live.localapp.local.Config.testAuthBypassActive) {
            // Test mode: any 6-digit code works. We IGNORE the entered code but
            // still establish a REAL Supabase session by signing into the canned
            // test account — a local-only "test-<phone>" id has no JWT, and Bo
            // (plus every other Claude call) now routes through claude-proxy,
            // which 401s without a verifiable token. From here we reuse the exact
            // same profile-check + routing as the real OTP path below, so the
            // canned user's user_profiles row (created on first onboarding, with
            // the default 6-week trial) entitles it past the proxy's Pro gate.
            viewModelScope.launch {
                _state.update { it.copy(isLoading = true, error = null) }
                try {
                    signInTestUser()
                    val userId = supabaseClient.auth.currentSessionOrNull()?.user?.id
                        ?: throw IllegalStateException("No session after test sign-in")
                    // Force-onboarding: ignore the canned account's existing profile
                    // so the onboarding flow runs every sign-in (regression testing).
                    val hasProfile = !live.localapp.local.Config.testForceOnboardingActive &&
                        userPreferencesRepository.profileExistsForAuthId(userId)
                    Analytics.identify(userId = userId)
                    if (hasProfile) {
                        userPreferencesRepository.recoverProfileByAuthId(userId)
                        _state.update {
                            it.copy(isLoading = false, isExistingUser = true, authComplete = true, authUserId = userId)
                        }
                    } else {
                        _state.update {
                            it.copy(isLoading = false, authComplete = true, authUserId = userId)
                        }
                    }
                } catch (e: Exception) {
                    println("DEBUG: test-auth sign-in failed: ${e::class.simpleName}: ${e.message}")
                    _state.update { it.copy(isLoading = false, error = "Test sign-in failed: ${e.message}") }
                }
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val phoneFormatted = "+1${s.phone}"
                println("DEBUG: Verifying OTP for phone=$phoneFormatted, code=${s.otpCode}")
                supabaseClient.auth.verifyPhoneOtp(
                    type = io.github.jan.supabase.auth.OtpType.Phone.SMS,
                    phone = phoneFormatted,
                    token = s.otpCode,
                )

                // Get the authenticated user's ID
                val session = supabaseClient.auth.currentSessionOrNull()
                val userId = session?.user?.id
                    ?: throw IllegalStateException("No session after OTP verification")

                // Check if user has an existing profile with completed onboarding
                val hasProfile = userPreferencesRepository.profileExistsForAuthId(userId)

                Analytics.identify(userId = userId)
                if (hasProfile) {
                    // Existing user: recover profile and go straight to home
                    userPreferencesRepository.recoverProfileByAuthId(userId)
                    Analytics.track(AnalyticsEvent.signedIn(method = "phone_otp"))
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isExistingUser = true,
                            authComplete = true,
                            authUserId = userId,
                        )
                    }
                } else {
                    // New user: straight to onboarding (email step removed)
                    Analytics.track(AnalyticsEvent.signedUp(method = "phone_otp", entryPoint = "organic"))
                    // Meta Ads conversion: fb_mobile_complete_registration.
                    // Deliberately in the `else` (new user) branch only — the
                    // `hasProfile` branch above is an existing user signing back
                    // in, which is not a registration.
                    MetaAppEvents.completeRegistration(method = "phone_otp")
                    _state.update {
                        it.copy(
                            isLoading = false,
                            authComplete = true,
                            authUserId = userId,
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val fullError = "${e::class.simpleName}: ${e.message}"
                println("DEBUG: OTP verification error: $fullError")
                val msg = e.message.orEmpty()
                // Expired / invalid codes are ordinary user error (wrong or stale OTP),
                // not app failures. Show inline guidance but DON'T report them to
                // Sentry — they were dominating the error feed as otp_verify_failed
                // noise. Only genuinely unexpected verify failures get recorded.
                val expectedUserError = msg.contains("expired", ignoreCase = true) ||
                    msg.contains("invalid", ignoreCase = true) ||
                    msg.contains("otp_expired", ignoreCase = true) ||
                    msg.contains("Token", ignoreCase = true)
                if (!expectedUserError) {
                    Analytics.recordError(e, message = "otp_verify_failed")
                }
                val message = when {
                    msg.contains("expired", ignoreCase = true) ->
                        "Code expired. Please request a new one."
                    msg.contains("invalid", ignoreCase = true) ->
                        "Invalid code. Please check and try again."
                    msg.contains("Token", ignoreCase = true) ->
                        "Invalid code. Please try again."
                    else -> fullError
                }
                _state.update { it.copy(isLoading = false, error = message) }
            }
        }
    }

    /**
     * Sign into the canned test account. Used only under TEST_AUTH_BYPASS to
     * establish a REAL Supabase session (so claude-proxy accepts the JWT).
     *
     * The account is provisioned ONCE, server-side, with its email pre-confirmed
     * (Supabase admin API). We deliberately do NOT sign-up from the client: the
     * hosted project has `mailer_autoconfirm = false`, so a client sign-up would
     * create an *unconfirmed* user that can never sign in and would burn the
     * 2/hour confirmation-email rate limit. Sign-in-only keeps this deterministic
     * — any failure (account missing/unconfirmed, wrong password) propagates to
     * the caller as a clean error instead of looping into a dead sign-up path.
     */
    /**
     * Dev-auth account resolution (debug + dev backend only). Maps the entered
     * phone to `dev+<digits>@localapp.live` and signs in; if no such account
     * exists yet, signs up (first use of that number). The dev project has email
     * auto-confirm ON, so the sign-up returns an active session immediately —
     * giving each phone its own persisted user that onboarding then completes.
     */
    private suspend fun devAuthSignInOrSignUp() {
        val email = live.localapp.local.Config.devAuthEmailFor(_state.value.phone)
        val password = live.localapp.local.Config.TEST_AUTH_PASSWORD
        try {
            supabaseClient.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
        } catch (e: Exception) {
            // No account for this phone yet → create it (first onboarding run).
            supabaseClient.auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
            // Some supabase-kt paths don't leave an active session after sign-up;
            // ensure one exists (auto-confirm makes this immediate on dev).
            if (supabaseClient.auth.currentSessionOrNull() == null) {
                supabaseClient.auth.signInWith(Email) {
                    this.email = email
                    this.password = password
                }
            }
        }
    }

    private suspend fun signInTestUser() {
        // Multi-account bypass keyed on the PHONE number — the only field entered
        // before OTP, which is when this sign-in fires (email is now collected on
        // a later step, after the session already exists). A reserved test number
        // routes to a distinct bypass-family account so a two-device regression
        // signs each device into a DISTINCT user; every other number uses the
        // canned default, leaving normal single-account flows unchanged. All
        // bypass accounts share TEST_AUTH_PASSWORD and are provisioned
        // server-side, email pre-confirmed (see Config.TEST_AUTH_EMAIL). Fully
        // debug-gated (only reached under testAuthBypassActive).
        val email = when (_state.value.phone) {
            "5559876543" -> "testbypass+b@localapp.live"
            else -> live.localapp.local.Config.TEST_AUTH_EMAIL
        }
        supabaseClient.auth.signInWith(Email) {
            this.email = email
            this.password = live.localapp.local.Config.TEST_AUTH_PASSWORD
        }
    }

    fun resendOtp() {
        _state.update { it.copy(otpCode = "", error = null) }
        sendOtp()
    }

    fun goBackToPhone() {
        _state.update { it.copy(step = AuthStep.PHONE, otpCode = "", error = null) }
    }
}
