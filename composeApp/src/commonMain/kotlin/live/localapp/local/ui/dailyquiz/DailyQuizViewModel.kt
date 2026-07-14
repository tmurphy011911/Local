package live.localapp.local.ui.dailyquiz

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import live.localapp.local.data.remote.dto.DailyQuizDto
import live.localapp.local.data.remote.dto.QuizCompletionDto
import live.localapp.local.data.remote.dto.QuizQuestionDto
import live.localapp.local.domain.repository.DailyQuizRepository
import live.localapp.local.domain.repository.LeaderboardRepository
import live.localapp.local.domain.repository.LeaderboardScope
import live.localapp.local.domain.repository.QuizAnswerInput
import live.localapp.local.domain.service.BadgeService

/**
 * Drives Daily Local on both platforms: the Home entry card's show/hide, the
 * in-sheet question flow, and the results. The countdown itself is owned by the
 * view layer (Compose LaunchedEffect / SwiftUI Timer) since it's frame-timed;
 * the view reports the captured [QuizAnswerInput.msRemaining] here.
 *
 * Scoring + the FOMO award are server-authoritative (submit_quiz). We only mirror
 * the formula locally in [previewPoints] for the instant per-question flourish.
 */
class DailyQuizViewModel(
    private val repo: DailyQuizRepository,
    private val badgeService: BadgeService,
    private val leaderboardRepo: LeaderboardRepository,
) : ViewModel() {

    enum class Phase { IDLE, IN_PROGRESS, SUBMITTING, RESULTS }

    data class UiState(
        val loading: Boolean = true,
        val quiz: DailyQuizDto? = null,
        val phase: Phase = Phase.IDLE,
        val questionIndex: Int = 0,
        val answers: List<QuizAnswerInput> = emptyList(),
        // Feedback moment: after a tap/timeout we reveal correct/incorrect for ~1s
        // before advancing. `revealSelectedIndex` == -1 means the question timed out.
        val revealing: Boolean = false,
        val revealSelectedIndex: Int? = null,
        val result: QuizCompletionDto? = null,
        val submitError: Boolean = false,
        // City rank/percentile for the results postcard (from the leaderboard RPC).
        val rankLocation: String? = null,
        val rank: Int = 0,
        val totalUsers: Int = 0,
    ) {
        val available: Boolean get() = quiz?.available == true
        val alreadyCompleted: Boolean get() = quiz?.alreadyCompleted == true
        val questions: List<QuizQuestionDto> get() = quiz?.questions ?: emptyList()
        val currentQuestion: QuizQuestionDto? get() = questions.getOrNull(questionIndex)
        val questionCount: Int get() = questions.size
        /** Home card shows whenever today's quiz exists — played or not (played -> "done" state). */
        val showCard: Boolean get() = available
        /** Current consecutive-day streak (from the completion when played, else the at-risk streak). */
        val streak: Int get() = quiz?.completion?.streak ?: quiz?.streak ?: 0
        /** "beat X% of locals" — percentile from the city rank. */
        val percentile: Int get() = if (totalUsers > 0 && rank > 0) (((totalUsers - rank).toDouble() / totalUsers) * 100).toInt() else 0
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val bridgeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init { refresh() }

    // ── Native (iOS SwiftUI) bridge helpers ───────────────────────────────────
    // The Compose (Android) path drives the flow through begin/recordAnswer/advance
    // above. On iOS, SwiftUI owns the session and only needs today's quiz + a way
    // to submit, so we hand it JSON and grade server-side here.

    /** Serialized DailyQuizDto for the native quiz, or null if none loaded. */
    fun currentQuizJson(): String? {
        val q = _uiState.value.quiz ?: return null
        return bridgeJson.encodeToString(DailyQuizDto.serializer(), q)
    }

    /**
     * Grade answers submitted by the native UI. [answersJson] is an array of
     * `{question_id, selected_index, ms_remaining}`; [done] receives the serialized
     * completion (or null on failure).
     */
    fun submitAnswersJson(answersJson: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            val answers = try {
                bridgeJson.parseToJsonElement(answersJson).jsonArray.map { el ->
                    val o = el.jsonObject
                    QuizAnswerInput(
                        questionId = o["question_id"]!!.jsonPrimitive.content,
                        selectedIndex = o["selected_index"]?.jsonPrimitive?.int ?: -1,
                        msRemaining = o["ms_remaining"]?.jsonPrimitive?.int ?: 0,
                    )
                }
            } catch (e: Throwable) {
                done(null); return@launch
            }
            val res = repo.submitQuiz(answers)
            if (res != null) {
                badgeService.notifyDataChanged()
                // Fetch city rank so the native postcard has everything in one payload.
                val lb = leaderboardRepo.getLeaderboard(LeaderboardScope.CITY)
                val rank = lb?.me?.rank ?: 0
                val total = lb?.totalUsers ?: 0
                val pct = if (total > 0 && rank > 0) (((total - rank).toDouble() / total) * 100).toInt() else 0
                val payload = buildJsonObject {
                    put("score", res.score)
                    put("correct_count", res.correctCount)
                    put("streak", res.streak)
                    put("best_all_time", res.bestAllTime)
                    put("is_first", res.isFirst)
                    put("state", res.state ?: "")
                    put("quiz_date", res.quizDate ?: "")
                    put("rank", rank)
                    put("rank_location", lb?.location ?: "")
                    put("percentile", pct)
                    put("share_text", dailyQuizShareText(res))
                    put("bo_message", res.boMessage)
                    put("breakdown", bridgeJson.encodeToJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(
                            live.localapp.local.data.remote.dto.QuizBreakdownDto.serializer()
                        ), res.breakdown))
                }
                refresh()
                done(payload.toString())
            } else {
                done(null)
            }
        }
    }

    /** (Re)load today's quiz meta — drives the Home card and pre-loads questions. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val dto = repo.getDailyQuiz()
            _uiState.update {
                it.copy(
                    loading = false,
                    quiz = dto,
                    // If already played, surface the stored result for the card/share.
                    result = if (dto?.alreadyCompleted == true) dto.completion else it.result,
                )
            }
        }
    }

    /** Begin a fresh run (no-op if unavailable or already completed today). */
    fun begin() {
        val s = _uiState.value
        if (!s.available || s.alreadyCompleted || s.questions.isEmpty()) return
        _uiState.update {
            it.copy(
                phase = Phase.IN_PROGRESS,
                questionIndex = 0,
                answers = emptyList(),
                revealing = false,
                revealSelectedIndex = null,
                result = null,
                submitError = false,
            )
        }
    }

    /**
     * Record the answer for the current question. [selectedIndex] = -1 for a
     * timeout. Enters the reveal state; the view shows feedback then calls [advance].
     */
    fun recordAnswer(selectedIndex: Int, msRemaining: Int) {
        val s = _uiState.value
        if (s.phase != Phase.IN_PROGRESS || s.revealing) return
        val q = s.currentQuestion ?: return
        val clamped = msRemaining.coerceIn(0, 10_000)
        val entry = QuizAnswerInput(q.id, selectedIndex, if (selectedIndex < 0) 0 else clamped)
        _uiState.update {
            it.copy(
                answers = it.answers + entry,
                revealing = true,
                revealSelectedIndex = selectedIndex,
            )
        }
    }

    /** Convenience for the timer hitting 0 with no selection. */
    fun timeout() = recordAnswer(-1, 0)

    /** Advance past the reveal: next question, or submit after the last. */
    fun advance() {
        val s = _uiState.value
        if (!s.revealing) return
        if (s.answers.size >= s.questionCount) {
            submit()
        } else {
            _uiState.update {
                it.copy(
                    questionIndex = it.answers.size,
                    revealing = false,
                    revealSelectedIndex = null,
                )
            }
        }
    }

    private fun submit() {
        _uiState.update { it.copy(phase = Phase.SUBMITTING, revealing = false, revealSelectedIndex = null) }
        viewModelScope.launch {
            val res = repo.submitQuiz(_uiState.value.answers)
            if (res != null) {
                badgeService.notifyDataChanged()   // let the leaderboard refresh live
                _uiState.update { it.copy(phase = Phase.RESULTS, result = res, submitError = false) }
                fetchRank()                        // city rank/percentile for the postcard
                refresh()                          // reload meta -> card flips to "done" state
            } else {
                _uiState.update { it.copy(phase = Phase.RESULTS, submitError = true) }
            }
        }
    }

    /** Fetch the caller's city rank (post-award) for the results postcard. */
    private fun fetchRank() {
        viewModelScope.launch {
            val lb = leaderboardRepo.getLeaderboard(LeaderboardScope.CITY) ?: return@launch
            _uiState.update {
                it.copy(rankLocation = lb.location, rank = lb.me?.rank ?: 0, totalUsers = lb.totalUsers)
            }
        }
    }

    /** Re-open the results recap for a quiz already played today. */
    fun viewStoredResults() {
        val comp = _uiState.value.quiz?.completion ?: return
        _uiState.update { it.copy(phase = Phase.RESULTS, result = comp, submitError = false) }
        if (_uiState.value.rank == 0) fetchRank()
    }

    /** Retry a failed submit from the results screen. */
    fun retrySubmit() {
        if (_uiState.value.answers.isEmpty()) return
        submit()
    }

    /** Close the sheet: drop the session but keep loaded meta/result. */
    fun endSession() {
        _uiState.update { it.copy(phase = Phase.IDLE, revealing = false, revealSelectedIndex = null) }
    }

    companion object {
        /** Difficulty multiplier: easy x1, medium x2, hard x3. */
        fun multiplier(difficulty: String): Int = when (difficulty) {
            "medium" -> 2
            "hard" -> 3
            else -> 1
        }

        /** Client-side mirror of the server formula (10 pts / sec remaining × mult). */
        fun previewPoints(difficulty: String, msRemaining: Int, correct: Boolean): Int {
            if (!correct) return 0
            val ms = msRemaining.coerceIn(0, 10_000)
            return kotlin.math.round(ms / 100.0 * multiplier(difficulty)).toInt()
        }
    }
}
