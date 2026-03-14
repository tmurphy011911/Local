package live.localapp.local.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import live.localapp.local.domain.model.CarouselSection
import live.localapp.local.domain.repository.CarouselRepository
import live.localapp.local.domain.repository.CategoryInfo
import live.localapp.local.domain.repository.SegmentMode
import live.localapp.local.domain.repository.UserPreferences
import live.localapp.local.domain.repository.UserPreferencesRepository

enum class HomeSegment { UPCOMING, ALWAYS_ON }

data class HomeUiState(
    val userName: String = "",
    val locationName: String = "",
    val userLat: Double = 42.3897,
    val userLng: Double = -71.2358,
    val streakDays: Int = 2,
    val selectedSegment: HomeSegment = HomeSegment.UPCOMING,
    val selectedCategory: String = "foryou",
    val availableCategories: List<CategoryInfo> = emptyList(),
    val carousels: List<CarouselSection> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val userPreferences: UserPreferences? = null,
)

class HomeViewModel(
    private val carouselRepository: CarouselRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadUserPreferences()
        loadCarousels()
    }

    private fun loadUserPreferences() {
        val userId = userPreferencesRepository.getLocalUserId() ?: return
        viewModelScope.launch {
            val prefs = userPreferencesRepository.loadPreferences(userId) ?: return@launch
            _uiState.update {
                it.copy(
                    userLat = prefs.userLat ?: it.userLat,
                    userLng = prefs.userLng ?: it.userLng,
                    userPreferences = prefs,
                )
            }
            loadCarousels()
        }
    }

    fun setUserLocation(lat: Double, lng: Double) {
        val current = _uiState.value
        if (current.userLat != lat || current.userLng != lng) {
            _uiState.update { it.copy(userLat = lat, userLng = lng) }
            carouselRepository.invalidateCaches()
            loadCarousels()
        }
    }

    fun debugSetLocation(lat: Double, lng: Double, name: String) {
        _uiState.update { it.copy(userLat = lat, userLng = lng, locationName = name) }
        carouselRepository.invalidateCaches()
        loadCarousels()
    }

    fun selectSegment(segment: HomeSegment) {
        _uiState.update { it.copy(selectedSegment = segment, selectedCategory = "foryou") }
        loadCarousels()
    }

    fun selectCategory(category: String) {
        _uiState.update { it.copy(selectedCategory = category) }
        loadCarousels(loadCategories = false)
    }

    fun refresh() {
        carouselRepository.invalidateCaches()
        _uiState.update { it.copy(isRefreshing = true) }
        loadCarousels()
    }

    private fun loadCarousels(loadCategories: Boolean = true) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val state = _uiState.value
                val category = state.selectedCategory
                val segment = when (state.selectedSegment) {
                    HomeSegment.UPCOMING -> SegmentMode.UPCOMING
                    HomeSegment.ALWAYS_ON -> SegmentMode.ALWAYS_ON
                }

                // Only fetch categories when segment changes or on first load
                val categories = if (loadCategories || state.availableCategories.isEmpty()) {
                    carouselRepository.getAvailableCategories(
                        segment = segment,
                        userPreferences = state.userPreferences,
                    )
                } else {
                    state.availableCategories
                }

                val carousels = carouselRepository.getCarousels(
                    userLat = state.userLat,
                    userLng = state.userLng,
                    segment = segment,
                    category = if (category == "foryou") null else category,
                    userPreferences = state.userPreferences,
                    locationName = state.locationName,
                )
                _uiState.update {
                    it.copy(
                        availableCategories = categories,
                        carousels = carousels,
                        isLoading = false,
                        isRefreshing = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false, isRefreshing = false) }
            }
        }
    }
}
