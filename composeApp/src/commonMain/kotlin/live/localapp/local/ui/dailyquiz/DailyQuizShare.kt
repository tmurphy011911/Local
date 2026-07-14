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
 * The link carries the share source (?s=dailylocalshare) for click attribution.
 * It is deliberately placed after text ("Get Local → …") rather than alone on a
 * line so Messages/iMessage treats it as inline text and won't unfurl it into a
 * big preview card. (Full unfurl suppression also depends on the /get landing
 * page not serving OpenGraph preview tags — that's server-side.)
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

    sb.append("Get Local → localapp.live/get?s=dailylocalshare")   // → , BMP arrow
    return sb.toString()
}
