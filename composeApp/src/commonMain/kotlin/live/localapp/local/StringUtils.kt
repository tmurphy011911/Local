package live.localapp.local

/**
 * Converts a snake_case or kebab-case string to Title Case.
 * e.g. "arts_center" → "Arts Center", "for-kids" → "For Kids",
 *      "no-public-interest" → "No Public Interest".
 */
fun String.snakeToTitleCase(): String =
    split("_", "-").joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercaseChar() }
    }

private val htmlEntityRegex = Regex("&#(\\d+);")
private val htmlHexEntityRegex = Regex("&#x([0-9a-fA-F]+);")
private val htmlTagRegex = Regex("<[^>]+>")
private val multiSpaceRegex = Regex("\\s+")
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
    "&hellip;" to "\u2026",
    "&copy;" to "\u00A9",
    "&reg;" to "\u00AE",
    "&trade;" to "\u2122",
)

/**
 * Clean a title-like string for display: decode HTML entities (numeric &#8217;,
 * hex &#x2019;, and named &amp;), strip HTML tags (<span class="\u2026">\u2026</span>,
 * <i>, <b>, etc.), and collapse runs of whitespace. Cheap, idempotent \u2014 safe
 * to apply at multiple layers (DTO boundary AND render boundary).
 *
 * Loops the entity passes up to 5 times so double-encoded values unwind
 * fully in one call: `&amp;#8211;` \u2192 `&#8211;` \u2192 `\u2013`. Without
 * the loop, the named pass turns `&amp;` into `&` AFTER the numeric pass
 * has already run, leaving `&#8211;` literal in the output. Municipal
 * feeds and some RSS sources produce double-encoded values like this,
 * which previously rendered as raw `&#8211;` on the iOS event detail.
 */
fun String.decodeHtmlEntities(): String {
    var result = this
    for (i in 0 until 5) {
        val before = result
        // 1. Decode numeric entities (e.g. &#8220; \u2192 ")
        result = htmlEntityRegex.replace(result) { match ->
            val code = match.groupValues[1].toIntOrNull()
            if (code != null && code in 0..0xFFFF) code.toChar().toString() else match.value
        }
        // 2. Decode hex entities (e.g. &#x201C; \u2192 ")
        result = htmlHexEntityRegex.replace(result) { match ->
            val code = match.groupValues[1].toIntOrNull(16)
            if (code != null && code in 0..0xFFFF) code.toChar().toString() else match.value
        }
        // 3. Decode named entities
        for ((entity, replacement) in namedEntities) {
            result = result.replace(entity, replacement, ignoreCase = true)
        }
        // Fixed point reached \u2014 no more entities decoded this pass.
        if (result == before) break
    }
    // 4. Strip HTML tags (<span class="title-light">, </span>, <i>, <br/>, etc.)
    //    Crawlers occasionally leave markup in title fields when the source
    //    venue's events page mixes inline styling into the event name.
    result = htmlTagRegex.replace(result, "")
    // 5. Collapse whitespace and trim \u2014 tag stripping can leave extra spaces.
    result = multiSpaceRegex.replace(result, " ").trim()
    return result
}
