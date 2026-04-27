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

// No native paywall on Android — callers fall back to the Compose PremiumPaywall.
actual fun presentNativePaywall(onPurchased: (Boolean) -> Unit) { onPurchased(false) }

actual fun openPlanChatSheet(
    plan: live.localapp.local.domain.model.Plan,
    prePopulatedMessage: String?,
    onDismiss: () -> Unit,
) {
    // No-op on Android — Compose ModalBottomSheet in AppNavigation handles it
}
actual fun closePlanChatSheet() {
    // No-op
}

actual fun showNativeEventDetail(
    event: live.localapp.local.domain.model.Event,
    cardX: Float,
    cardY: Float,
    cardW: Float,
    cardH: Float,
    cardImageUrl: String?,
    onDismissed: () -> Unit,
    onFavorited: ((String) -> Unit)?,
    showDirections: Boolean,
    isFavorited: Boolean,
) {
    // No-op on Android — Compose handles the detail screen
}

actual fun showNativePlanDetail(
    plan: live.localapp.local.domain.model.Plan,
    cardX: Float,
    cardY: Float,
    cardW: Float,
    cardH: Float,
    onDismissed: () -> Unit,
    onShare: () -> Unit,
    onEditPlan: () -> Unit,
    onEditDay: (String) -> Unit,
    onSetGroupSize: (Int) -> Unit,
) {
    // No-op on Android
}

actual fun showNativeVenueDetail(
    venueWithMeta: live.localapp.local.domain.repository.VenueWithMeta,
    cardX: Float,
    cardY: Float,
    cardW: Float,
    cardH: Float,
    onDismissed: () -> Unit,
    isFavorited: Boolean,
    onFavorited: ((String) -> Unit)?,
) {
    // No-op on Android — Compose handles the detail screen
}

actual fun updateNativeVenueDetailEvents(
    venueId: String,
    upcomingEvents: List<live.localapp.local.domain.model.Event>,
) {
    // No-op on Android — Compose detail reads events reactively from PlacesViewModel state
}
