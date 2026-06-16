package live.localapp.local.ui.leaderboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import live.localapp.local.data.remote.dto.LeaderboardDto
import live.localapp.local.domain.repository.ChangeHomeCityResult
import live.localapp.local.domain.repository.LeaderboardRepository
import live.localapp.local.domain.repository.LeaderboardScope
import live.localapp.local.domain.repository.UserPreferencesRepository
import live.localapp.local.domain.repository.UsernameUpdateResult
import live.localapp.local.domain.service.BadgeService

/**
 * Drives the leaderboard entry CTA + the sheet. Per-scope results are cached for
 * the session in [UiState.byScope] so the CTA's country→state→city cascade and
 * flipping the segmented control are instant after first load.
 *
 * Cached scopes are re-fetched whenever FOMO is earned: we observe
 * [BadgeService.dataChanged] (emitted after the badge insert that fires the
 * fomo_total trigger), so the board updates live instead of going stale until the
 * app is relaunched.
 */
class LeaderboardViewModel(
    private val repo: LeaderboardRepository,
    badgeService: BadgeService,
    private val userPrefsRepo: UserPreferencesRepository,
) : ViewModel() {

    init {
        loadProfile()
        viewModelScope.launch {
            badgeService.dataChanged.collect { refresh(); loadProfile() }
        }
    }

    data class UiState(
        val scope: LeaderboardScope = LeaderboardScope.STATE,
        val byScope: Map<LeaderboardScope, LeaderboardDto> = emptyMap(),
        // Scopes we've finished a fetch for (success or failure) — lets the UI
        // tell "still loading" apart from "loaded, genuinely empty".
        val attempted: Set<LeaderboardScope> = emptySet(),
        // Current user's profile bits used by the own-row + header.
        val username: String? = null,
        val usernameIsCustom: Boolean = false,
        val homeCity: String? = null,
    ) {
        val current: LeaderboardDto? get() = byScope[scope]
        val sheetLoading: Boolean get() = scope !in attempted
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private fun loadProfile() {
        viewModelScope.launch {
            val uid = userPrefsRepo.getLocalUserId() ?: return@launch
            val p = userPrefsRepo.loadPreferences(uid) ?: return@launch
            _uiState.update {
                it.copy(username = p.username, usernameIsCustom = p.usernameIsCustom, homeCity = p.hometownName)
            }
        }
    }

    /** Change home city; on success reloads the profile + leaderboard so everything re-scopes. */
    fun changeHomeCity(name: String, lat: Double, lng: Double, placeId: String?, onResult: (ChangeHomeCityResult) -> Unit) {
        viewModelScope.launch {
            val res = repo.changeHomeCity(name, lat, lng, placeId)
            if (res is ChangeHomeCityResult.Success) {
                loadProfile()
                // Home location moved → every scope's membership changes. Drop the
                // cache so the sheet re-fetches against the new home.
                _uiState.update { it.copy(byScope = emptyMap(), attempted = emptySet()) }
            }
            onResult(res)
        }
    }

    /** Set a custom username; on success reloads the profile + leaderboard so the own row updates. */
    fun saveUsername(name: String, onResult: (UsernameUpdateResult) -> Unit) {
        viewModelScope.launch {
            val uid = userPrefsRepo.getLocalUserId()
            if (uid == null) { onResult(UsernameUpdateResult.Error); return@launch }
            val res = userPrefsRepo.updateUsername(uid, name.trim())
            if (res == UsernameUpdateResult.Success) { loadProfile(); refresh() }
            onResult(res)
        }
    }

    /** Fetch a scope and store the result (force — overwrites any cached value). */
    private fun fetch(scope: LeaderboardScope) {
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

    /** Fetch a scope once if we haven't already; never re-fetches a cached scope. */
    private fun ensure(scope: LeaderboardScope) {
        if (scope in _uiState.value.attempted) return
        fetch(scope)
    }

    /** Re-fetch every already-loaded scope (e.g. after FOMO is earned). */
    private fun refresh() {
        _uiState.value.attempted.forEach { fetch(it) }
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
