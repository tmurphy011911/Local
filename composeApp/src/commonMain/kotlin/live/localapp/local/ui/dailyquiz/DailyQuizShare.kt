package live.localapp.local.ui.dailyquiz

import live.localapp.local.data.remote.dto.QuizCompletionDto

/**
 * The shareable text message for a completed run. Single source of truth for both
 * platforms — Android shares it directly; iOS receives it in the bridge payload
 * (as `share_text`).
 *
 * NB: only BMP characters are used (checkmark, U+2713). Astral-plane emoji
 * (heart, colored circles, etc.) are surrogate pairs and corrupt to tofu across
 * the Kotlin/Native -> Swift bridge, so they're avoided.
 *
 * The link is the short /dl path — the worker aliases it to the Daily Local
 * "get the app" flow: attributes the install to source=dailylocalshare AND
 * suppresses the rich link preview (strips OG/Twitter tags for crawlers) so the
 * message never unfurls into a big card. That no-unfurl behavior lives in the
 * worker and is tied to this exact URL — keep it as /dl, verbatim.
 */
fun dailyQuizShareText(c: QuizCompletionDto): String {
    val sb = StringBuilder()
    sb.append("Daily Local Results\n")
    sb.append("Total Score: ${c.score}/600\n")

    // Beat it → check; otherwise show the best they're chasing instead of a bare X.
    val isPersonalBest = c.isFirst || c.score > c.bestBefore
    if (isPersonalBest) {
        sb.append("Personal Best: ✓\n\n")
    } else {
        sb.append("Personal Best: ${c.bestAllTime} points\n\n")
    }

    // Full https:// scheme so every messaging client linkifies it into a tappable
    // link — a bare "localapp.live/dl" renders as dead plain text in most apps.
    // The /dl worker strips OG/Twitter tags, so this NEVER unfurls into a card.
    sb.append("Play now: https://localapp.live/dl")
    return sb.toString()
}
