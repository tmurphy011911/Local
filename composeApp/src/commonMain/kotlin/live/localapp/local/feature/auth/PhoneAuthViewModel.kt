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

enum class AuthStep { PHONE, OTP, EMAIL }

/** Minimal RFC-pragmatic email check: non-empty local part, "@", domain with a dot. */
fun isValidEmail(value: String): Boolean {
    val trimmed = value.trim()
    val at = trimmed.indexOf('@')
    if (at <= 0 || at != trimmed.lastIndexOf('@')) return false
    val domain = trimmed.substring(at + 1)
    return domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.') &&
        !trimmed.contains(' ')
}

data class PhoneAuthUiState(
    val phone: String = "",
    val email: String = "",
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

    fun updateEmail(value: String) {
        _state.update { it.copy(email = value, error = null) }
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

        if (live.localapp.local.Config.testAuthBypassActive) {
            // Test mode: skip SMS, go straight to OTP entry
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
                            it.copy(isLoading = false, step = AuthStep.EMAIL, authUserId = userId)
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
                    // New user: collect email before onboarding
                    Analytics.track(AnalyticsEvent.signedUp(method = "phone_otp", entryPoint = "organic"))
                    _state.update {
                        it.copy(
                            isLoading = false,
                            step = AuthStep.EMAIL,
                            authUserId = userId,
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val fullError = "${e::class.simpleName}: ${e.message}"
                println("DEBUG: OTP verification error: $fullError")
                Analytics.recordError(e, message = "otp_verify_failed")
                val message = when {
                    e.message?.contains("expired", ignoreCase = true) == true ->
                        "Code expired. Please request a new one."
                    e.message?.contains("invalid", ignoreCase = true) == true ->
                        "Invalid code. Please check and try again."
                    e.message?.contains("Token", ignoreCase = true) == true ->
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
    private suspend fun signInTestUser() {
        supabaseClient.auth.signInWith(Email) {
            this.email = live.localapp.local.Config.TEST_AUTH_EMAIL
            this.password = live.localapp.local.Config.TEST_AUTH_PASSWORD
        }
    }

    fun resendOtp() {
        _state.update { it.copy(otpCode = "", error = null) }
        sendOtp()
    }

    fun submitEmail() {
        val s = _state.value
        if (!isValidEmail(s.email)) {
            _state.update { it.copy(error = "Please enter a valid email address") }
            return
        }
        // Email captured — signal ready for onboarding
        _state.update { it.copy(authComplete = true) }
    }

    fun goBackToPhone() {
        _state.update { it.copy(step = AuthStep.PHONE, otpCode = "", error = null) }
    }
}
