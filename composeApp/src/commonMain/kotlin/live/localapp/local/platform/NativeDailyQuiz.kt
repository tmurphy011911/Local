package live.localapp.local.platform

/**
 * Present the platform-native Daily Local quiz (iOS = native SwiftUI via the
 * DailyQuizBridge; Android returns false so the caller falls back to the shared
 * Compose quiz sheet). Mirrors [presentNativeHomeCityEditor].
 *
 * [quizJson] is the serialized DailyQuizDto (questions for today). [onSubmit] is
 * invoked by the native UI with the serialized answers; it runs submit_quiz and
 * reports the serialized completion back through [done]. [onDismissed] fires when
 * the native sheet closes.
 *
 * @return true if a native quiz was presented, false otherwise.
 */
expect fun presentNativeDailyQuiz(
    quizJson: String,
    onSubmit: (answersJson: String, done: (resultJson: String?) -> Unit) -> Unit,
    onDismissed: () -> Unit,
    onShared: () -> Unit,
): Boolean
