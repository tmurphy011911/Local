package live.localapp.local

/**
 * Converts a snake_case string to Title Case.
 * e.g. "arts_center" → "Arts Center", "music_venue" → "Music Venue"
 */
fun String.snakeToTitleCase(): String =
    split("_").joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercaseChar() }
    }
