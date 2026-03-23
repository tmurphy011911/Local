package live.localapp.local.platform

import live.localapp.local.ChatSheetBridge
import live.localapp.local.TabBarBridge
import live.localapp.local.EventDetailBridge
import live.localapp.local.HeroTransitionBridge
import live.localapp.local.VenueDetailBridge
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.repository.VenueWithMeta

actual val hasNativeHeroTransition: Boolean = true
actual val hasNativeTabBar: Boolean = true

actual fun notifyTabChanged(tabIndex: Int) {
    TabBarBridge.onTabChanged?.invoke(tabIndex)
}

actual fun setTabBarUserInitial(initial: String) {
    TabBarBridge.onUserInitialChanged?.invoke(initial)
}

actual fun setupNativeTabBarCallbacks(
    onTabSelected: (Int) -> Unit,
    onChatTapped: () -> Unit,
) {
    TabBarBridge.onTabSelected = onTabSelected
    TabBarBridge.onChatTapped = onChatTapped
}

actual fun requestHeroExpand(
    imageUrl: String?,
    frameX: Float,
    frameY: Float,
    frameW: Float,
    frameH: Float,
    onComplete: () -> Unit,
) {
    HeroTransitionBridge.onExpandComplete = onComplete
    HeroTransitionBridge.requestExpand(imageUrl, frameX, frameY, frameW, frameH)
}

actual fun requestHeroCollapse(onComplete: () -> Unit) {
    HeroTransitionBridge.onCollapseComplete = onComplete
    HeroTransitionBridge.requestCollapse()
}

actual fun openChatSheet(onReady: () -> Unit) {
    ChatSheetBridge.onOpenComplete = onReady
    ChatSheetBridge.onOpenSheet?.invoke()
}

actual fun closeChatSheet(onDone: () -> Unit) {
    ChatSheetBridge.onCloseComplete = onDone
    ChatSheetBridge.onCloseSheet?.invoke()
}

actual fun showNativeEventDetail(
    event: Event,
    cardX: Float,
    cardY: Float,
    cardW: Float,
    cardH: Float,
    cardImageUrl: String?,
    onDismissed: () -> Unit,
    onFavorited: ((String) -> Unit)?,
) {
    EventDetailBridge.onDismissed = onDismissed
    EventDetailBridge.onFavorited = onFavorited
    EventDetailBridge.showEventDetail?.invoke(
        event.id,
        event.title,
        event.description,
        event.venueDescription,
        event.imageUrl,
        event.startAt,
        event.venueName,
        event.venueAddress,
        event.venueCity,
        event.venueLat ?: 0.0,
        event.venueLng ?: 0.0,
        event.isFree,
        event.priceDisplay,
        event.ticketUrl,
        event.sourceUrl,
        event.category,
        event.subcategory,
        event.source,
        cardX, cardY, cardW, cardH,
        cardImageUrl,
    )
}

actual fun showNativeVenueDetail(
    venueWithMeta: VenueWithMeta,
    cardX: Float,
    cardY: Float,
    cardW: Float,
    cardH: Float,
    onDismissed: () -> Unit,
) {
    val v = venueWithMeta.venue
    VenueDetailBridge.onDismissed = onDismissed
    VenueDetailBridge.showVenueDetail?.invoke(
        v.name,
        v.address,
        v.city,
        v.latitude ?: 0.0,
        v.longitude ?: 0.0,
        v.photoUrl ?: v.imageUrl,
        v.description,
        venueWithMeta.primaryCategory,
        v.googleRating,
        v.website,
        venueWithMeta.distanceMiles,
        venueWithMeta.upcomingEventCount,
        cardX, cardY, cardW, cardH,
    )
}
