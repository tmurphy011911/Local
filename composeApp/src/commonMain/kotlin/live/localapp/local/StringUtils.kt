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
    // Entity decoding (the multi-pass numeric/hex/named replaces) only does
    // anything when there's an '&'. Skip it entirely otherwise \u2014 the vast
    // majority of titles have none, and running ~2 passes of regex + 15 string
    // replaces per string across a whole carousel payload on the main thread was
    // hanging the app (decodeHtmlEntities ran inside ServerCarouselRepository's
    // map). Named entities are also case-sensitive per spec, so the replaces use
    // case-sensitive matching (the old ignoreCase=true forced a much slower scan).
    if (result.indexOf('&') >= 0) {
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
                result = result.replace(entity, replacement)
            }
            // Fixed point reached \u2014 no more entities decoded this pass.
            if (result == before) break
        }
    }
    // 4. Strip HTML tags (<span class="title-light">, </span>, <i>, <br/>, etc.).
    //    Only relevant when markup is actually present.
    if (result.indexOf('<') >= 0) {
        result = htmlTagRegex.replace(result, "")
    }
    // 5. Collapse whitespace and trim \u2014 tag stripping can leave extra spaces.
    result = multiSpaceRegex.replace(result, " ").trim()
    return result
}

// Accented Latin → base letter(s). Used by [sanitizeForDisplay].
private fun Char.deaccent(): String = when (this) {
    'À', 'Á', 'Â', 'Ã', 'Ä', 'Å', 'Ā', 'Ă', 'Ą' -> "A"
    'à', 'á', 'â', 'ã', 'ä', 'å', 'ā', 'ă', 'ą' -> "a"
    'Ç', 'Ć', 'Č', 'Ĉ', 'Ċ' -> "C"
    'ç', 'ć', 'č', 'ĉ', 'ċ' -> "c"
    'Ď', 'Đ' -> "D"
    'ď', 'đ' -> "d"
    'È', 'É', 'Ê', 'Ë', 'Ē', 'Ĕ', 'Ė', 'Ę', 'Ě' -> "E"
    'è', 'é', 'ê', 'ë', 'ē', 'ĕ', 'ė', 'ę', 'ě' -> "e"
    'Ì', 'Í', 'Î', 'Ï', 'Ĩ', 'Ī', 'Ĭ', 'Į', 'İ' -> "I"
    'ì', 'í', 'î', 'ï', 'ĩ', 'ī', 'ĭ', 'į', 'ı' -> "i"
    'Ñ', 'Ń', 'Ň', 'Ņ' -> "N"
    'ñ', 'ń', 'ň', 'ņ' -> "n"
    'Ò', 'Ó', 'Ô', 'Õ', 'Ö', 'Ø', 'Ō', 'Ŏ', 'Ő' -> "O"
    'ò', 'ó', 'ô', 'õ', 'ö', 'ø', 'ō', 'ŏ', 'ő' -> "o"
    'Ř', 'Ŕ' -> "R"
    'ř', 'ŕ' -> "r"
    'Š', 'Ś', 'Ŝ', 'Ş' -> "S"
    'š', 'ś', 'ŝ', 'ş' -> "s"
    'Ť', 'Ţ' -> "T"
    'ť', 'ţ' -> "t"
    'Ù', 'Ú', 'Û', 'Ü', 'Ũ', 'Ū', 'Ŭ', 'Ů', 'Ű', 'Ų' -> "U"
    'ù', 'ú', 'û', 'ü', 'ũ', 'ū', 'ŭ', 'ů', 'ű', 'ų' -> "u"
    'Ý', 'Ÿ' -> "Y"
    'ý', 'ÿ' -> "y"
    'Ž', 'Ź', 'Ż' -> "Z"
    'ž', 'ź', 'ż' -> "z"
    'Æ' -> "AE"; 'æ' -> "ae"; 'Œ' -> "OE"; 'œ' -> "oe"; 'ß' -> "ss"
    else -> this.toString()
}

/**
 * Normalise a display string to characters that render on iOS.
 *
 * Compose Multiplatform on iOS (Skia) fails to render a range of non-ASCII
 * glyphs from our variable heading/body fonts (Fraunces / Nunito) even though
 * the glyphs exist in the font files — they come out as `.notdef` "tofu" boxes.
 * Android falls back to a system font and renders them fine, so this is an
 * iOS-only failure. Crawled event titles are full of the offenders (en/em
 * dashes, smart quotes, ellipses, bullets, accented letters, emoji), and
 * [decodeHtmlEntities] actively PRODUCES them (`&mdash;` → —, `&#8217;` → '),
 * so display strings are normalised here to iOS-safe equivalents.
 *
 * Display-only. Never feed the result back into matching / dedupe / analytics /
 * navigation lookups — only into a `Text(...)`.
 */
fun String.sanitizeForDisplay(): String {
    // Fast path: pure-ASCII strings (the majority) are already safe.
    var hasNonAscii = false
    for (ch in this) if (ch.code >= 0x80) { hasNonAscii = true; break }
    if (!hasNonAscii) return this

    val sb = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        when (val code = c.code) {
            in 0x00..0x7F -> sb.append(c)
            // Dashes → hyphen-minus
            0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015, 0x2212 -> sb.append('-')
            // Single quotes / prime → straight apostrophe
            0x2018, 0x2019, 0x201A, 0x201B, 0x2032 -> sb.append('\'')
            // Double quotes / double prime → straight quote
            0x201C, 0x201D, 0x201E, 0x201F, 0x2033 -> sb.append('"')
            0x2026 -> sb.append("...")                       // horizontal ellipsis
            0x2022, 0x2023, 0x2043 -> sb.append('·')    // bullets → middot (renders on iOS)
            0x00A0, 0x2007, 0x2009, 0x202F -> sb.append(' ') // fixed / thin / no-break spaces
            0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF -> { }    // zero-width → drop
            0x2122 -> sb.append("(TM)")                      // ™
            0x00AE, 0x00A9 -> { }                            // ® © → drop
            in 0x00C0..0x017F -> sb.append(c.deaccent())     // accented Latin → base letter(s)
            in 0x2190..0x2BFF, in 0xFE00..0xFE0F, 0x20E3 -> { } // arrows / symbols / emoji presentation → drop
            else -> {
                if (c.isHighSurrogate()) {
                    // Emoji / astral plane → drop the whole surrogate pair.
                    if (i + 1 < length && this[i + 1].isLowSurrogate()) i++
                } else if (!c.isLowSurrogate()) {
                    // Unknown BMP char we don't have a rule for: keep it. Most
                    // Latin-1 punctuation (·, °, etc.) renders on iOS; only the
                    // ranges above reliably tofu.
                    sb.append(c)
                }
            }
        }
        i++
    }
    return multiSpaceRegex.replace(sb.toString(), " ").trim()
}
