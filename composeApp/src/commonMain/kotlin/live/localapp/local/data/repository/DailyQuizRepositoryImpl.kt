package live.localapp.local.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import live.localapp.local.data.remote.dto.DailyQuizDto
import live.localapp.local.data.remote.dto.QuizCompletionDto
import live.localapp.local.data.remote.dto.QuizSubmitResultDto
import live.localapp.local.domain.repository.DailyQuizRepository
import live.localapp.local.domain.repository.QuizAnswerInput
import live.localapp.local.domain.repository.UserPreferencesRepository

/**
 * Calls the `get_daily_quiz` / `submit_quiz` Postgres RPCs directly — same
 * transport as get_leaderboard / accept_plan_invite. Each is a single round trip
 * returning one jsonb object; no edge function needed.
 */
class DailyQuizRepositoryImpl(
    private val supabaseClient: SupabaseClient,
    private val userPrefsRepo: UserPreferencesRepository,
) : DailyQuizRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getDailyQuiz(): DailyQuizDto? {
        val userId = userPrefsRepo.getLocalUserId() ?: return null
        return try {
            val params = buildJsonObject {
                put("p_user_id", JsonPrimitive(userId))
            }
            val result = supabaseClient.postgrest.rpc("get_daily_quiz", params)
            json.decodeFromString(DailyQuizDto.serializer(), result.data)
        } catch (e: Throwable) {
            println("DailyQuizRepository.getDailyQuiz error: ${e.message}")
            null
        }
    }

    override suspend fun submitQuiz(answers: List<QuizAnswerInput>): QuizCompletionDto? {
        val userId = userPrefsRepo.getLocalUserId() ?: return null
        return try {
            val params = buildJsonObject {
                put("p_user_id", JsonPrimitive(userId))
                putJsonArray("p_answers") {
                    answers.forEach { a ->
                        add(buildJsonObject {
                            put("question_id", JsonPrimitive(a.questionId))
                            put("selected_index", JsonPrimitive(a.selectedIndex))
                            put("ms_remaining", JsonPrimitive(a.msRemaining))
                        })
                    }
                }
            }
            val result = supabaseClient.postgrest.rpc("submit_quiz", params)
            val parsed = json.decodeFromString(QuizSubmitResultDto.serializer(), result.data)
            if (parsed.ok) parsed.completion else null
        } catch (e: Throwable) {
            println("DailyQuizRepository.submitQuiz error: ${e.message}")
            null
        }
    }
}
