package live.localapp.local.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Daily Local quiz payloads. Shapes mirror the jsonb returned by the
 * `get_daily_quiz` / `submit_quiz` RPCs (see migration 20260708130000).
 *
 * The quiz is state-scoped and driven entirely by the user's home_state (never
 * their live location). Scoring is server-authoritative — the client submits
 * `(question_id, selected_index, ms_remaining)` and the RPC computes points as
 * `10 * seconds_remaining * difficulty_mult` (easy x1 / medium x2 / hard x3),
 * so max is 100 / 200 / 300 -> 600 / day.
 */

/** One answer choice. `correct` is exposed so the client can render instant feedback. */
@Serializable
data class QuizAnswerDto(
    val text: String = "",
    val correct: Boolean = false,
)

/** A single quiz question as served for the day. */
@Serializable
data class QuizQuestionDto(
    val id: String = "",
    val difficulty: String = "easy",          // easy | medium | hard
    val question: String = "",
    val answers: List<QuizAnswerDto> = emptyList(),
    @SerialName("max_points") val maxPoints: Int = 0,
)

/** One graded question in a completed quiz. */
@Serializable
data class QuizBreakdownDto(
    @SerialName("question_id") val questionId: String = "",
    val difficulty: String = "easy",
    @SerialName("selected_index") val selectedIndex: Int = -1,
    val correct: Boolean = false,
    @SerialName("ms_remaining") val msRemaining: Int = 0,
    val base: Int = 0,          // 10 × seconds-left (pre-multiplier)
    val points: Int = 0,        // base × difficulty multiplier
)

/** A stored quiz result (from get_daily_quiz.completion or submit_quiz.completion). */
@Serializable
data class QuizCompletionDto(
    @SerialName("quiz_date") val quizDate: String? = null,
    val state: String? = null,
    val score: Int = 0,
    @SerialName("correct_count") val correctCount: Int = 0,
    val streak: Int = 0,
    @SerialName("best_all_time") val bestAllTime: Int = 0,
    @SerialName("is_first") val isFirst: Boolean = false,
    @SerialName("best_before") val bestBefore: Int = 0,
    @SerialName("attempts_before") val attemptsBefore: Int = 0,
    @SerialName("recent_higher") val recentHigher: Int = 0,
    @SerialName("recent_atmost") val recentAtmost: Int = 0,
    @SerialName("bo_message") val boMessage: String = "",
    val breakdown: List<QuizBreakdownDto> = emptyList(),
)

/** Full get_daily_quiz payload. */
@Serializable
data class DailyQuizDto(
    val available: Boolean = false,
    val reason: String? = null,               // no_home_state | no_questions (when unavailable)
    @SerialName("quiz_date") val quizDate: String? = null,
    val state: String? = null,
    val streak: Int = 0,                        // current consecutive-day streak
    @SerialName("already_completed") val alreadyCompleted: Boolean = false,
    val completion: QuizCompletionDto? = null,  // present iff already played today
    val questions: List<QuizQuestionDto> = emptyList(),
)

/** submit_quiz response wrapper. */
@Serializable
data class QuizSubmitResultDto(
    val ok: Boolean = false,
    val reason: String? = null,
    @SerialName("already_completed") val alreadyCompleted: Boolean = false,
    val completion: QuizCompletionDto? = null,
)
