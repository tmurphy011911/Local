package live.localapp.local.platform

actual val hasNativeHeroTransition: Boolean = false
actual val hasNativeTabBar: Boolean = false
actual fun notifyTabChanged(tabIndex: Int) {}
actual fun setTabBarUserInitial(initial: String) {}
actual fun setupNativeTabBarCallbacks(onTabSelected: (Int) -> Unit, onChatTapped: () -> Unit) {}

actual fun requestHeroExpand(
    imageUrl: String?,
    frameX: Float,
    frameY: Float,
    frameW: Float,
    frameH: Float,
    onComplete: () -> Unit,
) {
    // No-op on Android — Compose handles the transition
    onComplete()
}

actual fun requestHeroCollapse(onComplete: () -> Unit) {
    // No-op on Android
    onComplete()
}

actual fun openChatSheet(onReady: () -> Unit) { onReady() }
actual fun closeChatSheet(onDone: () -> Unit) { onDone() }

actual fun showNativeEventDetail(
    event: live.localapp.local.domain.model.Event,
    cardX: Float,
    cardY: Float,
    cardW: Float,
    cardH: Float,
    cardImageUrl: String?,
    onDismissed: () -> Unit,
    onFavorited: ((String) -> Unit)?,
) {
    // No-op on Android — Compose handles the detail screen
}

actual fun showNativeVenueDetail(
    venueWithMeta: live.localapp.local.domain.repository.VenueWithMeta,
    cardX: Float,
    cardY: Float,
    cardW: Float,
    cardH: Float,
    onDismissed: () -> Unit,
) {
    // No-op on Android — Compose handles the detail screen
}
