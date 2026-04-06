package live.localapp.local

/**
 * Converts a snake_case string to Title Case.
 * e.g. "arts_center" → "Arts Center", "music_venue" → "Music Venue"
 */
fun String.snakeToTitleCase(): String =
    split("_").joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercaseChar() }
    }

private val htmlEntityRegex = Regex("&#(\\d+);")
private val namedEntities = mapOf(
    "&amp;" to "&",
    "&lt;" to "<",
    "&gt;" to ">",
    "&quot;" to "\"",
    "&apos;" to "'",
    "&nbsp;" to " ",
    "&ndash;" to "\u2013",
    "&mdash;" to "\u2014",
    "&lsquo;" to "\u2018",
    "&rsquo;" to "\u2019",
    "&ldquo;" to "\u201C",
    "&rdquo;" to "\u201D",
)

/** Decode HTML entities (numeric &#8217; and named &amp;) to plain text. */
fun String.decodeHtmlEntities(): String {
    var result = htmlEntityRegex.replace(this) { match ->
        val code = match.groupValues[1].toIntOrNull()
        if (code != null) code.toChar().toString() else match.value
    }
    for ((entity, replacement) in namedEntities) {
        result = result.replace(entity, replacement, ignoreCase = true)
    }
    return result
}
