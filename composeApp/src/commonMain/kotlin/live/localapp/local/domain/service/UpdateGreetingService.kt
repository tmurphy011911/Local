package live.localapp.local.domain.service

import kotlin.random.Random
import kotlinx.coroutines.withTimeoutOrNull
import live.localapp.local.data.remote.ClaudeApiService
import live.localapp.local.data.remote.ClaudeMessage
import live.localapp.local.domain.repository.UserPreferencesRepository

/** Which update prompt the line is for. */
enum class UpdateTone {
    /** The dismissable "a new version is out" home banner — a soft nudge. */
    Passive,

    /** The full-screen force-update blocker — warmer than a wall, but the user
     *  genuinely can't continue until they update. */
    Active,
}

/**
 * Produces the one-line Bo-voiced nudge shown on the two update prompts — the
 * dismissable "new version" banner ([UpdateTone.Passive]) and the full-screen
 * force-update blocker ([UpdateTone.Active]).
 *
 * Same spirit and safety contract as [SplashGreetingService]: Claude writes a
 * short line in Bo's voice, the whole thing is bounded by a hard timeout, and a
 * static fallback covers a slow/failed call so the prompt is never blocked on
 * the network. Personalization is best-effort and uses only the cheapest signal
 * we can reach without a round-trip — the cached location — for a little local
 * flavor; a logged-out or location-less user just gets a placeless line.
 */
class UpdateGreetingService(
    private val claude: ClaudeApiService,
    private val userPrefs: UserPreferencesRepository,
) {
    /**
     * @param timeoutMs total budget for the (optional) context read + the Claude
     *   call. On timeout or any failure we fall back to a static line.
     */
    suspend fun line(tone: UpdateTone, timeoutMs: Long): String {
        val locationName = nearestLocationName()
        val personalized = withTimeoutOrNull(timeoutMs) {
            runCatching { buildLine(tone, locationName) }.getOrNull()
        }
        return personalized?.takeIf { it.isNotBlank() } ?: fallback(tone)
    }

    /** Best-effort: pick a per-launch angle, ask Claude for a single short line. */
    private suspend fun buildLine(tone: UpdateTone, locationName: String?): String? {
        val anglePool = if (tone == UpdateTone.Passive) PASSIVE_ANGLES else ACTIVE_ANGLES
        val angle = anglePool.random()
        val seed = Random.nextInt(1_000_000)

        val userMessage = buildString {
            locationName?.let { appendLine("The user's town: $it (only nod to it if it lands naturally)") }
            appendLine("Angle for this one: $angle")
            appendLine("Variation seed (do not output, just don't repeat a stock phrasing): $seed")
            append("Write the line.")
        }

        val response = claude.sendRaw(
            systemPrompt = if (tone == UpdateTone.Passive) PASSIVE_SYSTEM else ACTIVE_SYSTEM,
            messages = listOf(ClaudeMessage.text("user", userMessage)),
            model = ClaudeApiService.MODEL_CHAT,
        )
        return claude.extractText(response)?.let { sanitize(it, tone) }
    }

    /**
     * Strip quotes, collapse to one line, enforce the per-tone length budget.
     * Like the splash service, we REJECT an over-budget line (return blank →
     * static fallback) rather than truncate mid-thought.
     */
    private fun sanitize(raw: String, tone: UpdateTone): String {
        val oneLine = raw.trim().trim('"', '“', '”', '\'')
            .lineSequence().firstOrNull().orEmpty().trim()
        val cap = if (tone == UpdateTone.Passive) MAX_CHARS_PASSIVE else MAX_CHARS_ACTIVE
        return if (oneLine.length <= cap) oneLine else ""
    }

    /** Most-recent cached location (no network), short display form ("Waltham"). */
    private fun nearestLocationName(): String? {
        userPrefs.getCachedManualLocation().manualLocationName
            ?.takeIf { it.isNotBlank() }?.let { return shortPlace(it) }
        userPrefs.getCachedGpsLocationName()?.takeIf { it.isNotBlank() }?.let { return shortPlace(it) }
        userPrefs.getCachedUserCity()?.takeIf { it.isNotBlank() }?.let { return shortPlace(it) }
        return null
    }

    /** "Perkasie, PA" → "Perkasie"; title-case a bare city slug. */
    private fun shortPlace(raw: String): String {
        val head = raw.substringBefore(",").trim()
        return head.split(" ").joinToString(" ") { w ->
            w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun fallback(tone: UpdateTone): String =
        (if (tone == UpdateTone.Passive) PASSIVE_FALLBACKS else ACTIVE_FALLBACKS).random()

    companion object {
        // Passive shares a single banner row with the Update pill + close, so it
        // has to stay tight. Active is full-screen and centered, free to wrap to
        // a second line, so it can breathe a little more.
        private const val MAX_CHARS_PASSIVE = 46
        private const val MAX_CHARS_ACTIVE = 88

        // ── Passive: the soft "there's a newer Local" nudge ──
        private val PASSIVE_ANGLES = listOf(
            "Tease that you've been busy adding good stuff since they last updated.",
            "Drop a low-key tip that a fresher version's worth grabbing.",
            "Nod to something new waiting, like a friend with the inside line.",
            "Be casually excited about what's in the new version, no hard sell.",
            "A dry aside that they're a version behind and missing out a little.",
        )

        private val PASSIVE_FALLBACKS = listOf(
            "Freshened things up. Grab the update",
            "New version's out. Worth the tap",
            "Been busy. There's a newer Local waiting",
            "Something new's ready when you are",
            "Snuck in some good stuff. Update to see",
        )

        // ── Active: the full-screen "you have to update to continue" wall ──
        private val ACTIVE_ANGLES = listOf(
            "Gently break it that this version's too old to keep going without an update.",
            "Warmly insist they update so you two can pick back up.",
            "Be the friend saying 'gotta update me first' — kind, not a scold.",
            "Frame updating as the one quick step before you're back in business.",
        )

        private val ACTIVE_FALLBACKS = listOf(
            "This version's a bit behind. Update and we're back in",
            "Gotta get you on the latest before we head out",
            "Time for a fresh Local. Update to keep going",
            "One quick update and we're right back to it",
        )

        // Bo's voice. Kept in lockstep with SplashGreetingService's rules (witty
        // local friend, never a mascot/ad; no emoji, hashtags, or exclamation
        // points; never the user's name) so the update prompts read as the same
        // Bo the user meets on the splash.
        private val PASSIVE_SYSTEM = """
            You are Bo, the user's witty local insider inside Local, an events
            app. Write ONE short line for a small banner nudging the user that a
            newer version of the app is out — a soft, low-key tip, never a hard
            sell. Keep it SHORT: max 8 words and at most 46 characters so it fits
            on one banner row beside an "Update" button.

            Tone: dry, in-the-know, like a text from a local friend who's been
            busy and has something good waiting — not a mascot, not an ad, not
            peppy. If a town is given, you may nod to it, but only if it lands
            naturally; usually skip it.

            Hard rules: never use the user's name. No emoji, no hashtags, no
            exclamation points. Don't be corny or salesy. Vary the phrasing every
            time. Output ONLY the line — no quotes, no trailing ellipsis.
        """.trimIndent()

        private val ACTIVE_SYSTEM = """
            You are Bo, the user's witty local insider inside Local, an events
            app. The user's app is too old to keep running and they must update
            to continue — write ONE short line for that full-screen prompt. Be
            warm and a little funny about it, like a friend saying "you gotta
            update me first," never a cold error or a scold. Make clear, in Bo's
            voice, that updating is the one quick step to get back in. Keep it
            SHORT: max 12 words and at most 88 characters (it can wrap to a
            second line).

            Tone: friendly, dry wit, on the user's side — not a mascot, not an
            ad. If a town is given you may nod to it, but only if it lands
            naturally.

            Hard rules: never use the user's name. No emoji, no hashtags, no
            exclamation points. Don't be corny or salesy. Vary the phrasing every
            time. Output ONLY the line — no quotes, no trailing ellipsis.
        """.trimIndent()
    }
}
