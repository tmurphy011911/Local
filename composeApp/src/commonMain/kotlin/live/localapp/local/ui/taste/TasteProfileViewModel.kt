package live.localapp.local.ui.taste

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import live.localapp.local.domain.model.TasteProfile
import live.localapp.local.domain.repository.TasteRepository
import live.localapp.local.domain.repository.UserPreferencesRepository

data class TasteProfileUiState(
    val profile: TasteProfile? = null,
    val isLoading: Boolean = true,
)

class TasteProfileViewModel(
    private val tasteRepo: TasteRepository,
    private val userPrefsRepo: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TasteProfileUiState())
    val uiState: StateFlow<TasteProfileUiState> = _uiState.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val userId = userPrefsRepo.getLocalUserId() ?: return@launch
            val profile = try { tasteRepo.getTasteProfile(userId) } catch (_: Throwable) { null }
            _uiState.update { it.copy(profile = profile, isLoading = false) }
        }
    }

    fun updateScore(dimension: String, key: String, newScore: Int) {
        val profile = _uiState.value.profile ?: return
        val updated = when (dimension) {
            "cuisine" -> profile.copy(cuisineScores = profile.cuisineScores.toMutableMap().apply { put(key, newScore.coerceIn(0, 20)) })
            "category" -> profile.copy(categoryScores = profile.categoryScores.toMutableMap().apply { put(key, newScore.coerceIn(0, 20)) })
            "neighborhood" -> profile.copy(neighborhoodScores = profile.neighborhoodScores.toMutableMap().apply { put(key, newScore.coerceIn(0, 20)) })
            "venue_type" -> profile.copy(venueTypeScores = profile.venueTypeScores.toMutableMap().apply { put(key, newScore.coerceIn(0, 20)) })
            "time" -> profile.copy(timePreferences = profile.timePreferences.toMutableMap().apply { put(key, newScore.coerceIn(0, 20)) })
            else -> profile
        }
        _uiState.update { it.copy(profile = updated) }
        viewModelScope.launch {
            try { tasteRepo.saveTasteProfile(updated) } catch (_: Throwable) {}
        }
    }

    fun resetSection(dimension: String) {
        val profile = _uiState.value.profile ?: return
        val updated = when (dimension) {
            "cuisine" -> profile.copy(cuisineScores = emptyMap())
            "category" -> profile.copy(categoryScores = emptyMap())
            "neighborhood" -> profile.copy(neighborhoodScores = emptyMap())
            "venue_type" -> profile.copy(venueTypeScores = emptyMap())
            "time" -> profile.copy(timePreferences = emptyMap())
            else -> profile
        }
        _uiState.update { it.copy(profile = updated) }
        viewModelScope.launch {
            try { tasteRepo.saveTasteProfile(updated) } catch (_: Throwable) {}
        }
    }
}
