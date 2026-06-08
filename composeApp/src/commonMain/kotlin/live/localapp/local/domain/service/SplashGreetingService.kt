package live.localapp.local.domain.service

import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import live.localapp.local.data.remote.ClaudeApiService
import live.localapp.local.data.remote.ClaudeMessage
import live.localapp.local.domain.repository.CommunityInterestRepository
import live.localapp.local.domain.repository.EventRepository
import live.localapp.local.domain.repository.UserPreferencesRepository

/**
 * Produces the one-line greeting Bo typewriters on the returning-user splash.
 *
 * Hard rule: this never gates app routing. [greeting] is bounded by an overall
 * timeout — if Claude (or any data lookup) doesn't return in time it falls back
 * to a generic line. The splash awaits [greeting] only to decide what text to
 * type; it does not block dismissal, which stays driven by `appReady`.
 *
 * Personalization is best-effort and uses only the most recent signals we can
 * cheaply reach: cached location, the user's saved/upcoming events, and their
 * onboarding categories + neighborhoods. Logged-out returning users (no cached
 * user id) get a location-flavored or fully generic fallback.
 */
class SplashGreetingService(
    private val claude: ClaudeApiService,
    private val userPrefs: UserPreferencesRepository,
    private val communityInterest: CommunityInterestRepository,
    private val eventRepo: EventRepository,
) {
    /**
     * @param timeoutMs total budget for context gathering + the Claude call.
     *   The caller sizes this to fit inside Bo's entrance animation so the
     *   line is usually ready by the time the typewriter starts.
     */
    suspend fun greeting(timeoutMs: Long): String {
        val locationName = nearestLocationName()
        val locationRaw = nearestLocationRaw()
        val personalized = withTimeoutOrNull(timeoutMs) {
            runCatching { buildPersonalizedLine(locationName, locationRaw) }.getOrNull()
        }
        val line = personalized?.takeIf { it.isNotBlank() } ?: fallback(locationName)
        return finalizePunctuation(line)
    }

    /**
     * Copy rule: questions keep their "?"; everything else ends with an
     * ellipsis. Strips any trailing sentence punctuation first so we never
     * double up (e.g. "...", ".…", "!…").
     */
    private fun finalizePunctuation(line: String): String {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.endsWith("?")) return trimmed
        return trimmed.trimEnd('.', '…', '!', ',', ';', ':', ' ') + "..."
    }

    /**
     * Best-effort: gather context, ask Claude for a single short Bo line.
     *
     * @param locationName short, display form (e.g. "Waltham") used for {loc}
     *   substitution in angles/fallbacks.
     * @param locationRaw region-qualified form (e.g. "Waltham, MA") handed to
     *   Claude so it can reliably judge which surrounding towns are genuinely
     *   within ~10 miles. The state-less short name is ambiguous.
     */
    private suspend fun buildPersonalizedLine(
        locationName: String?,
        locationRaw: String?,
    ): String? = coroutineScope {
        val userId = userPrefs.getLocalUserId()

        // Parallel, best-effort lookups. Any failure → empty, never throws out.
        val savedEventsDeferred = async {
            if (userId == null) return@async emptyList<String>()
            runCatching {
                val ids = communityInterest.getFavoriteEventIds(userId).take(5)
                if (ids.isEmpty()) return@runCatching emptyList<String>()
                eventRepo.getEventsByIds(ids).take(3).map { e ->
                    e.venueName?.let { "${e.title} at $it" } ?: e.title
                }
            }.getOrDefault(emptyList())
        }
        val prefsDeferred = async {
            if (userId == null) return@async null
            runCatching { userPrefs.loadPreferences(userId) }.getOrNull()
        }
        val savedEvents = savedEventsDeferred.await()
        val prefs = prefsDeferred.await()
        val categories = prefs?.selectedCategories.orEmpty()
        val neighborhoods = prefs?.selectedNeighborhoods.orEmpty()

        // Per-launch emphasis: ~6/10 location-led, ~4/10 taste-led (saved
        // events / saved places / categories). This keeps a single strong
        // interest (e.g. theatre) from dominating EVERY launch — the user
        // complained every open became another Oedipus line.
        val hasTaste = categories.isNotEmpty() || savedEvents.isNotEmpty()
        val useLocation = when {
            locationName == null -> false
            !hasTaste -> true
            else -> Random.nextInt(10) < LOCATION_BIAS
        }

        // CRITICAL: on a location turn we DON'T feed Claude the taste signals at
        // all — otherwise it latches onto "theatre" regardless of the angle and
        // we're back to Oedipus every time. Neighborhoods stay (they're places).
        val context = buildString {
            (locationRaw ?: locationName)?.let { appendLine("Location: $it") }
            if (neighborhoods.isNotEmpty()) appendLine("Neighborhoods: ${neighborhoods.joinToString(", ")}")
            if (!useLocation) {
                if (categories.isNotEmpty()) appendLine("Likes: ${categories.joinToString(", ")}")
                if (savedEvents.isNotEmpty()) appendLine("Saved events: ${savedEvents.joinToString("; ")}")
            }
        }.trim()

        // Per-launch variation: a random angle (from the chosen pool) + seed so
        // the same context never yields the same line twice.
        val anglePool = when {
            useLocation -> LOCATION_ANGLES
            hasTaste -> TASTE_ANGLES
            else -> GENERIC_ANGLES
        }
        val angle = anglePool.random().replace("{loc}", locationName ?: "")
        val seed = Random.nextInt(1_000_000)

        val userMessage = buildString {
            if (context.isNotBlank()) appendLine(context).appendLine()
            appendLine("Angle for this one: $angle")
            appendLine("Variation seed (do not output, just don't repeat a past phrasing): $seed")
            append("Write the line.")
        }

        val response = claude.sendRaw(
            systemPrompt = SYSTEM_PROMPT,
            messages = listOf(ClaudeMessage.text("user", userMessage)),
            model = ClaudeApiService.MODEL_CHAT,
        )
        claude.extractText(response)?.let(::sanitize)
    }

    /**
     * Strip quotes, collapse to one line, and enforce the [MAX_CHARS] length
     * budget so the splash greeting always fits on a single readable line next
     * to Bo. [MAX_CHARS] is sized to the longest line that renders cleanly at
     * the base font.
     *
     * If Claude blows the budget we REJECT the line (return blank) rather than
     * truncate it — truncating mid-thought produces gibberish like "Still
     * pretending Waltham's just a?" (the noun got cut). A blank here makes
     * [greeting] fall back to a complete, coherent static line instead.
     */
    private fun sanitize(raw: String): String {
        val oneLine = raw.trim().trim('"', '“', '”', '\'').lineSequence().firstOrNull().orEmpty().trim()
        return if (oneLine.length <= MAX_CHARS) oneLine else ""
    }

    /** Most-recent location name from cache (no network), short form for
     *  display/{loc} substitution (e.g. "Waltham"). */
    private fun nearestLocationName(): String? = nearestLocationRaw()?.let(::shortPlace)

    /**
     * Same cached source as [nearestLocationName] but keeps the region (e.g.
     * "Waltham, MA" rather than just "Waltham"). We hand THIS to Claude so it
     * can accurately tell which surrounding towns are genuinely within ~10
     * miles — the short, state-less name is ambiguous (many states have a
     * "Waltham"/"Newton"). Display + fallbacks still use the short form.
     */
    private fun nearestLocationRaw(): String? {
        userPrefs.getCachedManualLocation().manualLocationName
            ?.takeIf { it.isNotBlank() }
            ?.let { return it.trim() }
        userPrefs.getCachedGpsLocationName()?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        userPrefs.getCachedUserCity()?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        return null
    }

    /** "Perkasie, PA" → "Perkasie"; title-case a bare city slug. */
    private fun shortPlace(raw: String): String {
        val head = raw.substringBefore(",").trim()
        return head.split(" ").joinToString(" ") { w ->
            w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun fallback(locationName: String?): String {
        val pool = if (locationName != null) {
            LOCATION_FALLBACKS.map { it.replace("{loc}", locationName) }
        } else {
            GENERIC_FALLBACKS
        }
        return pool.random()
    }

    companion object {
        /**
         * Hard cap on the greeting length. Sized to the longest line that still
         * renders at a comfortable font on one row beside Bo (≈ "How'd the
         * Oedipus adaptation land for you?"). Keep fallbacks/prompt under this.
         */
        private const val MAX_CHARS = 42

        // Location-led fallbacks (used when Claude doesn't answer in time).
        // {loc} is the user's most recent place.
        private val LOCATION_FALLBACKS = listOf(
            "Back in {loc}, I see",
            "{loc} again? Good call",
            "Bored of the usual {loc} spots yet?",
            "Already three steps ahead in {loc}",
            "Let's get you out into {loc}",
            "Something new in {loc} tonight?",
            "Got my eye on a few {loc} spots",
            "You and {loc} have unfinished business",
        )

        private val GENERIC_FALLBACKS = listOf(
            "Back for more, huh",
            "Long time no see",
            "Plotting your escape from the couch",
            "Let's find you something good",
            "What are we getting into tonight?",
        )

        // How often (out of 10) we pick a LOCATION angle over a TASTE angle
        // when the user has both signals. 6 = ~60% location / ~40% taste, so a
        // single strong interest (e.g. theatre) can't dominate every launch.
        private const val LOCATION_BIAS = 6

        // Random angle injected per launch so the same context never produces
        // the same line twice. Most angles steer AWAY from "I'm working" toward
        // a question, an aside, or an inside joke.
        //
        // LOCATION_ANGLES are PURELY about the place — no taste/saved-event
        // references — because on a location turn we deliberately withhold the
        // taste signals from Claude. {loc} → the user's location.
        private val LOCATION_ANGLES = listOf(
            "Make a wry observation about their actual neighborhood or commute.",
            "Riff on a real {loc} quirk, landmark, or rivalry like a local would.",
            "Tease {loc} affectionately — its reputation, its vibe, its scene.",
            "Be a regular who lives in {loc} and knows the area cold.",
            "Drop a dry aside about what {loc} is like right about now.",
            "Nod to a {loc} street, landmark, or local rivalry like a townie.",
            "Ask a casual question about getting around or out in {loc}.",
            "NAME a specific real town within ~10 miles of {loc} and nod to its scene or a worth-the-trip spot, like a local who roams the whole area. Use the actual neighbor's name, not {loc}.",
            "Riff on the short hop from {loc} to a SPECIFIC nearby town (name it) for a night out, the way locals actually cross over for the better spot.",
        )

        // TASTE_ANGLES lean on saved events / saved places / categories / taste
        // profile — the ~40% of launches that are personal rather than place.
        // Vary which interest you lean on so it isn't the same one every time.
        private val TASTE_ANGLES = listOf(
            "Ask a casual question tied to one of their go-to categories.",
            "Drop an inside-joke nod to one of their saved events.",
            "Riff on a saved place of theirs like you both know it well.",
            "React to their taste with a dry, knowing aside.",
            "Tie it to a recent moment in something they're clearly into.",
            "Tease them gently about a go-to category of theirs.",
        )

        private val GENERIC_ANGLES = listOf(
            "Ask them a casual question tied to their taste or a recent cultural moment.",
            "Drop an inside-joke nod to one of their saved events or go-to category.",
            "Make a dry, friendly observation about their week or their taste.",
            "Be playfully unsure where the user even is.",
            "React to something happening in their world right now, as a friend would.",
        )

        private val SYSTEM_PROMPT = """
            You are Bo, the user's witty local insider inside Local, an events
            app. You're plugged into everything happening around town AND you
            know this user cold — their taste, their go-to's, what they'd
            actually show up for. Write ONE short line shown on the loading
            splash for a returning user. Keep it SHORT — max 7 words and at
            most 42 characters, so it fits on a single line. Shorter is better.

            Two things you genuinely know, and play like you know them:
            - The location: like a local who's lived there for years, with the
              good spots, the scene, and the insider corners already in mind.
              You're given the town WITH its state (e.g. "Waltham, MA"). You
              roam the whole area, so a neighboring town within about 10 miles
              is fair game — reference it like a local who actually drives over
              there (the short hop, the better spot one town over, the
              rivalry). ONLY name a nearby town when you're genuinely sure it's
              that close and real; if you're not certain, just stay with the
              given town. Never invent or guess a neighbor.
            - The user: their taste, their saved events, their neighborhood.

            Never sound like you're pretending, faking, guessing, or "acting
            like" you know either one — you do.

            POV — THIS IS THE IMPORTANT PART: do NOT default to narrating your
            own task. Most lines should not start with a verb like
            "Hunting/Finding/Scouting/Casing/Digging" or describe you working.
            The line can just be something funny and personal to THIS user.
            Mix it up across these shapes (pick whatever the context fits):
            - A casual question to them, tied to their taste or a current
              moment: "What'd you make of the Tonys?" (likes theatre).
            - A wry local observation about their actual neighborhood: "Avoiding
              the Cambridge traffic again?" (based in Watertown).
            - An inside-joke nod to their saved events or go-to category.
            - A dry aside about the town's vibe or a real local quirk/landmark.
            - Occasionally, sure, a "you're up to something" line — but rarely,
              not as the default.
            Lean on the specific context (likes, neighborhoods, saved events,
            location) you're given. The more personal and specific, the better.

            Tone: witty, funny, personable — like a clever text from a local
            friend who knows the area and knows YOU, not a mascot or an ad. Dry,
            in-the-know wit beats enthusiasm.

            Hard rules: never use the user's name. No emoji, no hashtags, no
            exclamation points. Don't be corny, peppy, or salesy. Vary your
            phrasing AND your sentence shape every time. Don't fixate on a single
            work, show, or example — if they like a category, reference it
            broadly or pick a different angle, don't name the same title every
            launch. Questions are welcome (keep the "?").

            Output ONLY the line. No quotes. No trailing punctuation (a question
            mark is fine).
        """.trimIndent()
    }
}
