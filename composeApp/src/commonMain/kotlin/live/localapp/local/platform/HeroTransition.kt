package live.localapp.local.platform

/**
 * Platform bridge for native hero image morph transitions.
 * On iOS this drives a UIKit overlay animation; on Android it's a no-op
 * (the Compose transition handles it).
 */

/** Whether the current platform supports native hero transitions. */
expect val hasNativeHeroTransition: Boolean

/** Whether the platform uses a native tab bar (hide Compose tab bar). */
expect val hasNativeTabBar: Boolean

/** Notify native side of tab change. */
expect fun notifyTabChanged(tabIndex: Int)

/** Set the user initial for the profile tab icon. */
expect fun setTabBarUserInitial(initial: String)

/** Register callbacks for native tab bar. */
expect fun setupNativeTabBarCallbacks(
    onTabSelected: (Int) -> Unit,
    onChatTapped: () -> Unit,
)

/**
 * Ask the native layer to animate the hero image from [frameX],[frameY],[frameW],[frameH]
 * (in points / dp) to a full-width header position.
 * [onComplete] is invoked on the main thread once the animation finishes.
 */
expect fun requestHeroExpand(
    imageUrl: String?,
    frameX: Float,
    frameY: Float,
    frameW: Float,
    frameH: Float,
    onComplete: () -> Unit,
)

/**
 * Ask the native layer to animate the hero image back to its original card frame.
 * [onComplete] is invoked on the main thread once the animation finishes.
 */
expect fun requestHeroCollapse(onComplete: () -> Unit)

/**
 * Show a native event detail screen with an Airbnb-style card-to-fullscreen transition.
 * On iOS this presents a SwiftUI EventDetailView; on Android this is a no-op.
 */
expect fun showNativeEventDetail(
    event: live.localapp.local.domain.model.Event,
    cardX: Float,
    cardY: Float,
    cardW: Float,
    cardH: Float,
    cardImageUrl: String?,
    onDismissed: () -> Unit,
    onFavorited: ((String) -> Unit)? = null,
    showDirections: Boolean = false,
)

/**
 * Show a native venue detail screen with an Airbnb-style card-to-fullscreen transition.
 * On iOS this presents a SwiftUI VenueDetailView; on Android this is a no-op.
 */
/** Animate the background scale-down and open the chat sheet. */
expect fun openChatSheet(onReady: () -> Unit)

/** Animate the background scale-up and close the chat sheet. */
expect fun closeChatSheet(onDone: () -> Unit)

expect fun showNativePlanDetail(
    plan: live.localapp.local.domain.model.Plan,
    cardX: Float,
    cardY: Float,
    cardW: Float,
    cardH: Float,
    onDismissed: () -> Unit,
    onShare: () -> Unit,
)

expect fun showNativeVenueDetail(
    venueWithMeta: live.localapp.local.domain.repository.VenueWithMeta,
    cardX: Float,
    cardY: Float,
    cardW: Float,
    cardH: Float,
    onDismissed: () -> Unit,
)
