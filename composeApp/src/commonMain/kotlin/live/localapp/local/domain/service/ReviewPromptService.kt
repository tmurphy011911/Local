package live.localapp.local.domain.service

import com.russhwolf.settings.Settings
import live.localapp.local.platform.requestAppReview

/**
 * Manages when to show the native App Store review prompt.
 * Uses Apple's SKStoreReviewController which auto-rate-limits to 3x/year.
 *
 * Triggers (positive moments only):
 * - After saving a plan (core paid feature value)
 * - After 5th event save
 * - After sharing a plan or event
 *
 * Guards:
 * - Max 3 prompts ever from our side
 * - At least 7 days since install
 * - At least 3 app opens
 * - At least 30 days between prompts
 */
class ReviewPromptService {

    private val settings = Settings()

    private companion object {
        const val KEY_PROMPT_COUNT = "review_prompt_count"
        const val KEY_LAST_PROMPT_MS = "review_last_prompt_ms"
        const val KEY_INSTALL_MS = "review_install_ms"
        const val KEY_APP_OPENS = "review_app_opens"
        const val MAX_PROMPTS = 3
        const val MIN_DAYS_SINCE_INSTALL = 7L
        const val MIN_DAYS_BETWEEN_PROMPTS = 30L
        const val MIN_APP_OPENS = 3
    }

    /** Call on every app launch to track opens and install date. */
    fun recordAppOpen() {
        val now = currentTimeMs()
        if (settings.getLong(KEY_INSTALL_MS, 0L) == 0L) {
            settings.putLong(KEY_INSTALL_MS, now)
        }
        settings.putInt(KEY_APP_OPENS, settings.getInt(KEY_APP_OPENS, 0) + 1)
    }

    /** Call after a positive moment. Shows the review prompt if conditions are met. */
    fun onPositiveMoment(trigger: ReviewTrigger) {
        if (!shouldPrompt(trigger)) return

        // Record that we prompted
        val now = currentTimeMs()
        settings.putInt(KEY_PROMPT_COUNT, settings.getInt(KEY_PROMPT_COUNT, 0) + 1)
        settings.putLong(KEY_LAST_PROMPT_MS, now)

        println("ReviewPrompt: showing native review prompt (trigger=$trigger, count=${settings.getInt(KEY_PROMPT_COUNT, 0)})")
        requestAppReview()
    }

    private fun shouldPrompt(trigger: ReviewTrigger): Boolean {
        val now = currentTimeMs()
        val dayMs = 86_400_000L

        val promptCount = settings.getInt(KEY_PROMPT_COUNT, 0)
        if (promptCount >= MAX_PROMPTS) return false

        val installMs = settings.getLong(KEY_INSTALL_MS, now)
        val daysSinceInstall = (now - installMs) / dayMs
        if (daysSinceInstall < MIN_DAYS_SINCE_INSTALL) return false

        val appOpens = settings.getInt(KEY_APP_OPENS, 0)
        if (appOpens < MIN_APP_OPENS) return false

        val lastPromptMs = settings.getLong(KEY_LAST_PROMPT_MS, 0L)
        if (lastPromptMs > 0) {
            val daysSinceLastPrompt = (now - lastPromptMs) / dayMs
            if (daysSinceLastPrompt < MIN_DAYS_BETWEEN_PROMPTS) return false
        }

        return true
    }

    private fun currentTimeMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}

enum class ReviewTrigger {
    PLAN_SAVED,
    EVENT_SAVED_5TH,
    PLAN_SHARED,
    EVENT_SHARED,
}
