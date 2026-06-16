package live.localapp.local.ui.leaderboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import live.localapp.local.data.remote.dto.LeaderboardDto
import live.localapp.local.data.remote.dto.LeaderboardEntryDto
import live.localapp.local.domain.repository.LeaderboardRepository
import live.localapp.local.domain.repository.LeaderboardScope

/**
 * Drives both the home CTA (always STATE scope) and the leaderboard sheet (the
 * user-toggled scope). Per-scope results are cached for the session in
 * [UiState.byScope] so flipping the segmented control is instant after first load.
 */
class LeaderboardViewModel(
    private val repo: LeaderboardRepository,
) : ViewModel() {

    data class UiState(
        // Home CTA — the caller's own row at STATE scope.
        val ctaLoading: Boolean = true,
        val cta: LeaderboardEntryDto? = null,
        val stateLabel: String? = null,
        // Two-letter state code (e.g. "MA") — the compact CTA uses this to stay
        // single-line, while the sheet title uses the full stateLabel.
        val stateCode: String? = null,
        // Sheet.
        val scope: LeaderboardScope = LeaderboardScope.STATE,
        val byScope: Map<LeaderboardScope, LeaderboardDto> = emptyMap(),
        // Scopes we've finished a fetch for (success or failure) — lets the UI
        // tell "still loading" apart from "loaded, genuinely empty".
        val attempted: Set<LeaderboardScope> = emptySet(),
    ) {
        val current: LeaderboardDto? get() = byScope[scope]
        val sheetLoading: Boolean get() = scope !in attempted
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Load the STATE scope for the home CTA. Cheap; safe to call on each Home open. */
    fun loadCta() {
        viewModelScope.launch {
            _uiState.update { it.copy(ctaLoading = true) }
            val dto = repo.getLeaderboard(LeaderboardScope.STATE)
            _uiState.update { s ->
                s.copy(
                    ctaLoading = false,
                    cta = dto?.me ?: s.cta,
                    stateLabel = dto?.location ?: s.stateLabel,
                    stateCode = dto?.locationCode ?: s.stateCode,
                    byScope = if (dto != null) s.byScope + (LeaderboardScope.STATE to dto) else s.byScope,
                    attempted = s.attempted + LeaderboardScope.STATE,
                )
            }
        }
    }

    /** Fetch a scope once if we haven't already; never re-fetches a cached scope. */
    private fun ensure(scope: LeaderboardScope) {
        if (scope in _uiState.value.attempted) return
        viewModelScope.launch {
            val dto = repo.getLeaderboard(scope)
            _uiState.update { s ->
                s.copy(
                    byScope = if (dto != null) s.byScope + (scope to dto) else s.byScope,
                    attempted = s.attempted + scope,
                )
            }
        }
    }

    /** Switch the visible scope (serves cache immediately, fetches if cold). */
    fun selectScope(scope: LeaderboardScope) {
        _uiState.update { it.copy(scope = scope) }
        ensure(scope)
    }

    /** On sheet open: warm all three scopes so toggle labels + switching are instant. */
    fun prefetchAll() {
        LeaderboardScope.entries.forEach { ensure(it) }
    }

    /** Reset the per-open session: default back to STATE scope (spec §2). */
    fun resetScopeToState() {
        _uiState.update { it.copy(scope = LeaderboardScope.STATE) }
    }
}
