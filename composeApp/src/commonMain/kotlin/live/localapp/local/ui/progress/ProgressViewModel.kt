package live.localapp.local.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import live.localapp.local.domain.repository.UserPreferencesRepository

data class ProgressUiState(
    val userName: String = "",
    val userInitial: String = "",
    val favoritesSaved: Int = 0,
    val plansCreated: Int = 0,
    val eventsExplored: Int = 0,
)

class ProgressViewModel(
    private val supabaseClient: SupabaseClient,
    private val userPrefsRepo: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProgressUiState())
    val uiState: StateFlow<ProgressUiState> = _uiState.asStateFlow()

    init {
        loadProfile()
        loadStats()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            try {
                val prefs = userPrefsRepo.getLocalUserId()?.let { id ->
                    userPrefsRepo.loadPreferences(id)
                }
                val name = prefs?.firstName ?: ""
                val initial = name.firstOrNull()?.uppercase() ?: ""
                _uiState.update { it.copy(userName = name, userInitial = initial) }
            } catch (e: Throwable) {
                println("ProgressVM.loadProfile error: ${e.message}")
            }
        }
    }

    @Serializable
    private data class InteractionCountDto(
        @SerialName("interaction_type") val interactionType: String,
    )

    @Serializable
    private data class PlanCountDto(
        val id: String,
    )

    private fun loadStats() {
        viewModelScope.launch {
            val userId = userPrefsRepo.getLocalUserId() ?: return@launch
            try {
                // Count favorites
                val favorites = supabaseClient.from("community_interactions")
                    .select {
                        filter {
                            eq("user_id", userId)
                            eq("interaction_type", "favorite")
                        }
                    }
                    .decodeList<InteractionCountDto>()

                // Count plans created
                val plans = supabaseClient.from("plans")
                    .select {
                        filter { eq("user_id", userId) }
                    }
                    .decodeList<PlanCountDto>()

                // Count unique events explored (clicks)
                val clicks = supabaseClient.from("community_interactions")
                    .select {
                        filter {
                            eq("user_id", userId)
                            eq("interaction_type", "click")
                        }
                    }
                    .decodeList<InteractionCountDto>()

                _uiState.update {
                    it.copy(
                        favoritesSaved = favorites.size,
                        plansCreated = plans.size,
                        eventsExplored = clicks.size,
                    )
                }
            } catch (e: Throwable) {
                println("ProgressVM.loadStats error: ${e.message}")
            }
        }
    }
}
