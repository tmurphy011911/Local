package live.localapp.local.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import live.localapp.local.domain.repository.UserPreferencesRepository

enum class AuthStep { PHONE, OTP, NAME }

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

    fun updateFirstName(value: String) {
        _state.update { it.copy(firstName = value, error = null) }
    }

    fun updateLastName(value: String) {
        _state.update { it.copy(lastName = value, error = null) }
    }

    fun sendOtp() {
        val phone = _state.value.phone
        if (phone.length < 10) {
            _state.update { it.copy(error = "Please enter a valid 10-digit phone number") }
            return
        }

        if (live.localapp.local.Config.TEST_AUTH_BYPASS) {
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

        if (live.localapp.local.Config.TEST_AUTH_BYPASS) {
            // Test mode: any 6-digit code works, create a local test user
            viewModelScope.launch {
                _state.update { it.copy(isLoading = true, error = null) }
                val testUserId = "test-${s.phone}"
                // Check local storage — the DB won't have test IDs
                val existingLocalId = userPreferencesRepository.getLocalUserId()
                val isReturning = existingLocalId != null && userPreferencesRepository.isOnboardingComplete()
                if (isReturning) {
                    _state.update {
                        it.copy(isLoading = false, isExistingUser = true, authComplete = true, authUserId = existingLocalId!!)
                    }
                } else {
                    _state.update {
                        it.copy(isLoading = false, authComplete = true, authUserId = testUserId)
                    }
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

                if (hasProfile) {
                    // Existing user: recover profile and go straight to home
                    userPreferencesRepository.recoverProfileByAuthId(userId)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isExistingUser = true,
                            authComplete = true,
                            authUserId = userId,
                        )
                    }
                } else {
                    // New user: skip name collection, go straight to onboarding
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

    fun resendOtp() {
        _state.update { it.copy(otpCode = "", error = null) }
        sendOtp()
    }

    fun submitName() {
        val s = _state.value
        if (s.firstName.isBlank()) {
            _state.update { it.copy(error = "Please enter your first name") }
            return
        }
        // Signal ready for onboarding
        _state.update { it.copy(authComplete = true) }
    }

    fun goBackToPhone() {
        _state.update { it.copy(step = AuthStep.PHONE, otpCode = "", error = null) }
    }
}
