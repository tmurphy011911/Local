package live.localapp.local.ui.leaderboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import live.localapp.local.data.remote.dto.LeaderboardDto
import live.localapp.local.domain.repository.LeaderboardRepository
import live.localapp.local.domain.repository.LeaderboardScope

/**
 * Drives the leaderboard entry CTA + the sheet. Per-scope results are cached for
 * the session in [UiState.byScope] so the CTA's country→state→city cascade and
 * flipping the segmented control are instant after first load.
 */
class LeaderboardViewModel(
    private val repo: LeaderboardRepository,
) : ViewModel() {

    data class UiState(
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
