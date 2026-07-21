package live.localapp.local.ui.dailyquiz

import live.localapp.local.data.remote.dto.QuizCompletionDto

/**
 * The shareable text message for a completed run. Single source of truth for both
 * platforms — Android shares it directly; iOS receives it in the bridge payload
 * (as `share_text`).
 *
 * NB: only BMP characters are used (checkmark/cross/arrow, U+2713/U+2717/U+2192).
 * Astral-plane emoji (heart, colored circles, etc.) are surrogate pairs and
 * corrupt to tofu across the Kotlin/Native -> Swift bridge, so they're avoided.
 *
 * The link is the short /dl path — the worker aliases it to the Daily Local
 * "get the app" flow: attributes the install to source=dailylocalshare AND
 * suppresses the rich link preview (strips OG/Twitter tags for crawlers) so the
 * message never unfurls into a big card. Kept after text ("Play Now → …") too.
 */
fun dailyQuizShareText(c: QuizCompletionDto): String {
    val sb = StringBuilder()
    sb.append("Daily Local Results\n\n")
    c.breakdown.forEach { b ->
        val label = b.difficulty.replaceFirstChar { it.uppercase() }
        val mark = if (b.correct) "✓" else "✗"   // ✓ / ✗
        sb.append("$label: $mark ${b.points} points\n")
    }
    sb.append("\nTotal Points: ${c.score} Points\n")

    // Beat it → check; otherwise show the best they're chasing instead of a bare X.
    val isPersonalBest = c.isFirst || c.score > c.bestBefore
    if (isPersonalBest) {
        sb.append("Personal Best ✓\n\n")
    } else {
        sb.append("Personal Best: ${c.bestAllTime} points\n\n")
    }

    // Full https:// scheme so every messaging client linkifies it into a tappable
    // link — a bare "localapp.live/dl" renders as dead plain text in most apps.
    sb.append("Play Now → https://localapp.live/dl")   // → , BMP arrow
    return sb.toString()
}
