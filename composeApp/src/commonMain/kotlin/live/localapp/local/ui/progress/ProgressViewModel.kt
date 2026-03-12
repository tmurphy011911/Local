package live.localapp.local.ui.progress

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Level(
    val name: String,
    val icon: String,
    val minPts: Int,
    val maxPts: Int,
)

data class ProgressUiState(
    val totalPoints: Int = 847,
    val streakDays: Int = 6,
    val favoritesSaved: Int = 12,
    val eventsConfirmed: Int = 5,
    val levels: List<Level> = listOf(
        Level("New Here", "\uD83C\uDF31", 0, 499),
        Level("Intern", "\uD83D\uDCBC", 500, 4_999),
        Level("Regular", "\uD83C\uDFD8\uFE0F", 5_000, 19_999),
        Level("Local", "\uD83D\uDCCD", 20_000, 99_999),
        Level("Townie", "\uD83D\uDDDD\uFE0F", 100_000, 999_999),
        Level("Icon", "\uD83D\uDC51", 1_000_000, Int.MAX_VALUE),
    ),
) {
    val currentLevelIndex: Int
        get() = levels.indexOfLast { totalPoints >= it.minPts }.coerceAtLeast(0)

    val currentLevel: Level
        get() = levels[currentLevelIndex]

    val nextLevel: Level?
        get() = levels.getOrNull(currentLevelIndex + 1)

    val progressFraction: Float
        get() {
            val next = nextLevel ?: return 1f
            val range = next.minPts - currentLevel.minPts
            if (range <= 0) return 1f
            return ((totalPoints - currentLevel.minPts).toFloat() / range).coerceIn(0f, 1f)
        }

    val pointsToNext: Int
        get() {
            val next = nextLevel ?: return 0
            return (next.minPts - totalPoints).coerceAtLeast(0)
        }
}

class ProgressViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ProgressUiState())
    val uiState: StateFlow<ProgressUiState> = _uiState.asStateFlow()
}
