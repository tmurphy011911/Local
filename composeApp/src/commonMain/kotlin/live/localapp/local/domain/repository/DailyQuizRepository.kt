package live.localapp.local.domain.repository

import live.localapp.local.data.remote.dto.DailyQuizDto
import live.localapp.local.data.remote.dto.QuizCompletionDto

/** One answered question the client submits for scoring. */
data class QuizAnswerInput(
    val questionId: String,
    val selectedIndex: Int,   // -1 = no answer / timed out
    val msRemaining: Int,     // milliseconds left on the 10s clock when answered
)

/**
 * Daily Local quiz data access. Backed by the `get_daily_quiz` / `submit_quiz`
 * RPCs; the quiz is resolved server-side from the caller's home_state.
 */
interface DailyQuizRepository {
    /** Today's quiz for the caller's home state, or null on error/unavailable. */
    suspend fun getDailyQuiz(): DailyQuizDto?

    /**
     * Submit answers for scoring. The server computes the score and credits FOMO;
     * returns the stored completion (idempotent — replaying returns the same result).
     */
    suspend fun submitQuiz(answers: List<QuizAnswerInput>): QuizCompletionDto?
}
