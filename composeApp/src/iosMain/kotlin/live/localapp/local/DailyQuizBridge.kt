package live.localapp.local

/**
 * Bridge between the shared Kotlin layer and the native SwiftUI Daily Local quiz
 * (DailyQuizView.swift), mirroring [HomeCityEditBridge] / EventDetailBridge.
 *
 * Swift registers [present] on launch (via ContentView's setupDailyQuizBridge).
 * When the user starts the quiz, Kotlin sets [quizJson] + the callbacks and calls
 * [present]. Swift reads [quizJson], runs the quiz, and on completion calls
 * [onSubmit] with the serialized answers; the `done` closure hands back the
 * serialized completion for the results screen. [onDismissed] fires on close.
 */
object DailyQuizBridge {
    /** Set by Swift: present the native quiz sheet. */
    var present: (() -> Unit)? = null

    /** Set by Kotlin before present(): serialized DailyQuizDto for today. */
    var quizJson: String? = null

    /** Set by Kotlin: Swift calls this with serialized answers; `done` returns the completion JSON. */
    var onSubmit: ((answersJson: String, done: (String?) -> Unit) -> Unit)? = null

    /** Set by Kotlin: Swift calls this when the sheet is dismissed. */
    var onDismissed: (() -> Unit)? = null

    /** Set by Kotlin: Swift calls this when the user taps Share on the results. */
    var onShared: (() -> Unit)? = null
}
