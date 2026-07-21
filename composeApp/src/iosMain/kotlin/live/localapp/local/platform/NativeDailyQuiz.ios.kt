package live.localapp.local.platform

import live.localapp.local.DailyQuizBridge

/**
 * iOS: wire the submit/dismiss handlers into the bridge and ask Swift to present
 * the native quiz. If Swift hasn't registered a presenter yet, return false so the
 * caller falls back to the Compose sheet.
 */
actual fun presentNativeDailyQuiz(
    quizJson: String,
    onSubmit: (answersJson: String, done: (resultJson: String?) -> Unit) -> Unit,
    onDismissed: () -> Unit,
    onShared: () -> Unit,
): Boolean {
    val present = DailyQuizBridge.present ?: return false
    DailyQuizBridge.quizJson = quizJson
    DailyQuizBridge.onSubmit = onSubmit
    DailyQuizBridge.onDismissed = onDismissed
    DailyQuizBridge.onShared = onShared
    present()
    return true
}
