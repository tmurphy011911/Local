package live.localapp.local.platform

/** Android has no native quiz — fall back to the shared Compose quiz sheet. */
actual fun presentNativeDailyQuiz(
    quizJson: String,
    onSubmit: (answersJson: String, done: (resultJson: String?) -> Unit) -> Unit,
    onDismissed: () -> Unit,
    onShared: () -> Unit,
): Boolean = false
