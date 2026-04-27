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
    isFavorited: Boolean = false,
)

/**
 * Show a native venue detail screen with an Airbnb-style card-to-fullscreen transition.
 * On iOS this presents a SwiftUI VenueDetailView; on Android this is a no-op.
 */
/** Animate the background scale-down and open the chat sheet. */
expect fun openChatSheet(onReady: () -> Unit)

/** Animate the background scale-up and close the chat sheet. */
expect fun closeChatSheet(onDone: () -> Unit)

/**
 * Present the native RevenueCat-backed paywall on iOS. Used by surfaces that
 * want the paywall without opening the chat first (Profile "Upgrade" CTA,
 * etc.). On Android this is a no-op — callers should fall back to the Compose
 * `PremiumPaywall` inside the chat sheet.
 *
 * [onPurchased] fires true if the user successfully subscribed / restored an
 * entitlement, false if they dismissed without purchasing.
 */
expect fun presentNativePaywall(onPurchased: (Boolean) -> Unit)

/**
 * Present the plan-specific group chat as a native sheet on iOS.
 * On Android this is a no-op — Compose handles the sheet directly.
 *
 * [onDismiss] fires when the user closes the sheet (swipe or tap X).
 */
expect fun openPlanChatSheet(
    plan: live.localapp.local.domain.model.Plan,
    prePopulatedMessage: String?,
    onDismiss: () -> Unit,
)

/** Close the plan chat sheet. */
expect fun closePlanChatSheet()

expect fun showNativePlanDetail(
    plan: live.localapp.local.domain.model.Plan,
    cardX: Float,
    cardY: Float,
    cardW: Float,
    cardH: Float,
    onDismissed: () -> Unit,
    onShare: () -> Unit,
    onEditPlan: () -> Unit = {},
    onEditDay: (String) -> Unit = {},
    onSetGroupSize: (Int) -> Unit = {},
)

expect fun showNativeVenueDetail(
    venueWithMeta: live.localapp.local.domain.repository.VenueWithMeta,
    cardX: Float,
    cardY: Float,
    cardW: Float,
    cardH: Float,
    onDismissed: () -> Unit,
    isFavorited: Boolean = false,
    onFavorited: ((String) -> Unit)? = null,
)

/**
 * Pushes freshly-fetched upcoming events into an already-presented native venue detail.
 * Called after the grid card animates in, so the detail page opens instantly with a
 * skeleton and swaps in real events when the fetch completes. iOS-only; no-op on Android.
 */
expect fun updateNativeVenueDetailEvents(
    venueId: String,
    upcomingEvents: List<live.localapp.local.domain.model.Event>,
)
