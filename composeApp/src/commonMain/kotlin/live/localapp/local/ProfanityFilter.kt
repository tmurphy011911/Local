package live.localapp.local

/**
 * Simple profanity filter for user-entered names.
 * Checks against a blocklist of offensive words and common bypass patterns.
 */
object ProfanityFilter {

    // Blocklist — lowercase, covers common slurs and offensive terms
    private val blockedWords = setOf(
        // Profanity
        "fuck", "shit", "ass", "damn", "bitch", "bastard", "dick", "cock",
        "pussy", "cunt", "piss", "crap", "whore", "slut", "tits", "boob",
        "penis", "vagina", "anus", "dildo", "porn", "hentai", "orgasm",
        // Slurs (abbreviated to avoid reproducing full slurs in source)
        "nigger", "nigga", "faggot", "fag", "retard", "retarded",
        "spic", "chink", "kike", "gook", "wetback", "beaner",
        "tranny", "dyke",
        // Violence
        "kill", "murder", "rape", "nazi", "hitler",
        // Drugs (as names)
        "cocaine", "heroin", "meth",
    )

    // L33tspeak substitutions
    private val leetMap = mapOf(
        '0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a',
        '5' to 's', '7' to 't', '@' to 'a', '$' to 's',
    )

    /**
     * Returns true if the name contains offensive content.
     */
    fun isOffensive(name: String): Boolean {
        if (name.isBlank()) return false

        // Normalize: lowercase, strip non-alpha (except spaces), decode l33tspeak
        val normalized = name.lowercase()
            .map { leetMap[it] ?: it }
            .joinToString("")
            .replace(Regex("[^a-z ]"), "")
            .trim()

        // Check each blocked word
        for (word in blockedWords) {
            // Direct substring match
            if (normalized.contains(word)) return true
            // Also check with spaces stripped (catches "f u c k" etc.)
            if (normalized.replace(" ", "").contains(word)) return true
        }

        return false
    }

    /**
     * Returns an error message if the name is invalid, null if it's OK.
     */
    fun validate(name: String): String? {
        if (name.isBlank()) return "Name cannot be empty"
        if (name.length < 2) return "Name is too short"
        if (name.length > 30) return "Name is too long"
        if (isOffensive(name)) return "Please enter an appropriate name"
        // Must contain at least one letter
        if (!name.any { it.isLetter() }) return "Name must contain letters"
        return null
    }
}
