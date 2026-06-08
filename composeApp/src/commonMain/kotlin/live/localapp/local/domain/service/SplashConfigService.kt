package live.localapp.local.domain.service

import com.russhwolf.settings.Settings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

/**
 * Splash kill switch — single flag, default on. The full editorial splash
 * plays every cold start; the only override is a remote off-switch that
 * collapses to a static wordmark frame in case the animation needs to be
 * killed without a new app release.
 *
 * The Supabase fetch is best-effort: any failure leaves the cached value
 * unchanged. Default is fail-open (animation enabled).
 */
class SplashConfigService(
    private val supabaseClient: SupabaseClient,
    private val settings: Settings = Settings(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Returns the cached animation-enabled flag (default true). Sync. */
    fun isAnimationEnabled(): Boolean =
        settings.getBoolean(KEY_ANIM_ENABLED, true)

    /**
     * Result of recording a cold-start open.
     *
     * @param isFirstEver true on the very first launch — drives the splash
     *   split (first-ever → editorial [Animated]; later opens → returning Bo).
     * @param specialLine a pre-punctuated line that *overrides* the Claude
     *   greeting when recency/streak warrants it (welcome-back after a long
     *   absence, or a streak milestone). Null means "use the normal greeting."
     */
    data class OpenState(val isFirstEver: Boolean, val specialLine: String?)

    /**
     * Records this cold start and returns the resulting [OpenState]. Tracks the
     * last-open calendar day and a consecutive-day streak (both in [Settings]),
     * computed once per launch. Idempotent within a single calendar day: a
     * second open the same day neither bumps the streak nor re-fires a milestone
     * line.
     *
     * Must be called exactly once at startup, *before* the variant is decided,
     * since it flips the first-launch flag as a side effect.
     */
    fun recordOpen(firstName: String?): OpenState {
        val tz = TimeZone.currentSystemDefault()
        val today = Instant.fromEpochMilliseconds(
            kotlin.time.Clock.System.now().toEpochMilliseconds(),
        ).toLocalDateTime(tz).date.toEpochDays()
        val isFirstEver = !settings.getBoolean(KEY_LAUNCHED_BEFORE, false)
        settings.putBoolean(KEY_LAUNCHED_BEFORE, true)

        val lastDay = settings.getLong(KEY_LAST_OPEN_DAY, -1L)
        val priorStreak = settings.getInt(KEY_STREAK, 0)
        val daysSince = if (lastDay < 0L) Long.MAX_VALUE else today - lastDay

        // Streak: +1 on a next-day open, reset to 1 on a gap, unchanged on a
        // same-day reopen (so milestones can't double-fire within one day).
        val streak = when {
            lastDay < 0L -> 1
            daysSince == 0L -> priorStreak.coerceAtLeast(1)
            daysSince == 1L -> priorStreak + 1
            else -> 1
        }
        settings.putLong(KEY_LAST_OPEN_DAY, today)
        settings.putInt(KEY_STREAK, streak)

        val specialLine = when {
            isFirstEver -> null
            // Long absence wins over a milestone — they can't co-occur anyway
            // (a >14-day gap resets the streak to 1).
            daysSince > WELCOME_BACK_DAYS -> welcomeBackLine(firstName)
            streak in MILESTONE_LINES.keys && settings.getLong(KEY_MILESTONE_DAY, -1L) != today -> {
                settings.putLong(KEY_MILESTONE_DAY, today)
                MILESTONE_LINES.getValue(streak).random()
            }
            else -> null
        }
        return OpenState(isFirstEver = isFirstEver, specialLine = specialLine)
    }

    private fun welcomeBackLine(firstName: String?): String {
        val pool = buildList {
            add("Hello darkness, my old friend...")
            add("Fancy seeing you here...")
            add("I'm so glad you're back...")
            firstName?.takeIf { it.isNotBlank() }?.let { add("$it? Is that you?") }
        }
        return pool.random()
    }

    /**
     * Best-effort remote refresh — kicked off at startup, never awaited by
     * the splash. The cached value is read synchronously for *this* cold
     * start; any update from this fetch applies on the *next* launch.
     */
    fun refreshFromRemoteAsync() {
        scope.launch {
            try {
                val row = withContext(Dispatchers.Default) {
                    supabaseClient.from("app_config")
                        .select { filter { eq("key", REMOTE_KEY_ANIM) } }
                        .decodeSingleOrNull<AppConfigRow>()
                }
                val remoteValue = row?.value?.toBooleanStrictOrNull() ?: return@launch
                settings.putBoolean(KEY_ANIM_ENABLED, remoteValue)
            } catch (_: Throwable) {
                // Table may not exist yet, network down, RLS, etc. — fall
                // through. The cached / default-true value remains in effect.
            }
        }
    }

    @Serializable
    private data class AppConfigRow(val key: String, val value: String)

    companion object {
        private const val KEY_ANIM_ENABLED = "splash_animation_enabled"
        private const val REMOTE_KEY_ANIM = "splash_animation_enabled"
        private const val KEY_LAUNCHED_BEFORE = "splash_has_launched_before"
        private const val KEY_LAST_OPEN_DAY = "splash_last_open_epoch_day"
        private const val KEY_STREAK = "splash_consecutive_streak"
        private const val KEY_MILESTONE_DAY = "splash_milestone_shown_epoch_day"

        // A gap longer than this (in days) since the last open earns a
        // welcome-back line instead of the usual greeting.
        private const val WELCOME_BACK_DAYS = 14

        // Streak milestones → pre-punctuated line pools. Keyed by the exact
        // consecutive-day count that triggers them (one-shot per calendar day,
        // gated by KEY_MILESTONE_DAY). Questions keep "?"; everything else
        // ends with "..." per the splash copy rule.
        private val MILESTONE_LINES: Map<Int, List<String>> = mapOf(
            3 to listOf(
                "It's our 3rd day in a row together...",
                "Three days straight, you and me...",
                "Day three of this little streak...",
            ),
            7 to listOf(
                "You've seen me every day for a week...",
                "Seven days in a row now...",
                "A whole week of showing up...",
            ),
            10 to listOf(
                "Aren't you sick of me yet?",
                "Ten days straight—you good?",
                "Day ten, and still you return...",
            ),
        )
    }
}
