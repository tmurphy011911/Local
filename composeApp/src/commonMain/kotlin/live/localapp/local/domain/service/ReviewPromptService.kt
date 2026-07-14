package live.localapp.local.domain.service

import com.russhwolf.settings.Settings
import live.localapp.local.platform.requestAppReview

/**
 * Manages when to show the native App Store review prompt.
 * Uses Apple's SKStoreReviewController which auto-rate-limits to 3x/year.
 *
 * Triggers (positive moments only):
 * - After saving a plan (core paid feature value)
 * - After the first event save / first place save
 * - After sharing a plan, event, or place
 * - Once the user has opened 3+ events / 3+ places
 *
 * Guards (deliberately lenient — optimizing for review volume):
 * - Max 3 prompts ever from our side
 * - At least 30 days between prompts
 * (App-open count is still tracked via [recordAppOpen] for future tuning, but is
 *  no longer a gate.)
 */
class ReviewPromptService {

    private val settings = Settings()

    private companion object {
        const val KEY_PROMPT_COUNT = "review_prompt_count"
        const val KEY_LAST_PROMPT_MS = "review_last_prompt_ms"
        const val KEY_APP_OPENS = "review_app_opens"
        const val KEY_EVENTS_OPENED = "review_events_opened"
        const val KEY_PLACES_OPENED = "review_places_opened"
        const val MAX_PROMPTS = 3
        const val MIN_DAYS_BETWEEN_PROMPTS = 30L
        const val OPENS_FOR_PROMPT = 3
    }

    /** Call on every app launch to track opens. */
    fun recordAppOpen() {
        settings.putInt(KEY_APP_OPENS, settings.getInt(KEY_APP_OPENS, 0) + 1)
    }

    /** Call whenever an event detail is opened. Fires once the user has opened 3+ events. */
    fun recordEventOpened() {
        val opened = settings.getInt(KEY_EVENTS_OPENED, 0) + 1
        settings.putInt(KEY_EVENTS_OPENED, opened)
        if (opened >= OPENS_FOR_PROMPT) onPositiveMoment(ReviewTrigger.EVENTS_OPENED_3)
    }

    /** Call whenever a place/venue detail is opened. Fires once the user has opened 3+ places. */
    fun recordPlaceOpened() {
        val opened = settings.getInt(KEY_PLACES_OPENED, 0) + 1
        settings.putInt(KEY_PLACES_OPENED, opened)
        if (opened >= OPENS_FOR_PROMPT) onPositiveMoment(ReviewTrigger.PLACES_OPENED_3)
    }

    /** Call after a positive moment. Shows the review prompt if conditions are met. */
    fun onPositiveMoment(trigger: ReviewTrigger) {
        if (!shouldPrompt()) return

        // Record that we prompted
        val now = currentTimeMs()
        settings.putInt(KEY_PROMPT_COUNT, settings.getInt(KEY_PROMPT_COUNT, 0) + 1)
        settings.putLong(KEY_LAST_PROMPT_MS, now)

        println("ReviewPrompt: showing native review prompt (trigger=$trigger, count=${settings.getInt(KEY_PROMPT_COUNT, 0)})")
        requestAppReview()
    }

    private fun shouldPrompt(): Boolean {
        val now = currentTimeMs()
        val dayMs = 86_400_000L

        val promptCount = settings.getInt(KEY_PROMPT_COUNT, 0)
        if (promptCount >= MAX_PROMPTS) return false

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
    EVENT_SAVED_1ST,
    PLACE_SAVED_1ST,
    PLAN_SHARED,
    EVENT_SHARED,
    PLACE_SHARED,
    EVENTS_OPENED_3,
    PLACES_OPENED_3,
}
