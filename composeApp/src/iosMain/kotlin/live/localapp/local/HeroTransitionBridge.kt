package live.localapp.local

/**
 * Kotlin/Native object exposed to Swift as `HeroTransitionBridge`.
 *
 * Swift sets the callback lambdas during app startup (in ContentView).
 * Compose calls [requestExpand] / [requestCollapse] when the user taps a card
 * or presses back from a detail screen.
 */
object HeroTransitionBridge {

    /**
     * Swift sets this to trigger the native UIKit expand animation.
     * Parameters: imageUrl, frameX, frameY, frameW, frameH (all in points).
     */
    var onExpandRequested: ((imageUrl: String?, frameX: Float, frameY: Float, frameW: Float, frameH: Float) -> Unit)? = null

    /**
     * Swift sets this to trigger the native UIKit collapse animation.
     */
    var onCollapseRequested: (() -> Unit)? = null

    /**
     * Compose sets this so Swift can notify when the expand animation is done.
     */
    var onExpandComplete: (() -> Unit)? = null

    /**
     * Compose sets this so Swift can notify when the collapse animation is done.
     */
    var onCollapseComplete: (() -> Unit)? = null

    fun requestExpand(imageUrl: String?, frameX: Float, frameY: Float, frameW: Float, frameH: Float) {
        onExpandRequested?.invoke(imageUrl, frameX, frameY, frameW, frameH)
    }

    fun requestCollapse() {
        onCollapseRequested?.invoke()
    }
}
