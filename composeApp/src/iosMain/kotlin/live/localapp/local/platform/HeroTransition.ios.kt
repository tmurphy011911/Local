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
    showDirections: Boolean,
) {
    EventDetailBridge.onDismissed = onDismissed
    EventDetailBridge.onFavorited = onFavorited
    EventDetailBridge.showDirections = showDirections
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

    // Serialize upcoming events as JSON for Swift
    val eventsJson = kotlinx.serialization.json.buildJsonArray {
        venueWithMeta.upcomingEvents.forEach { event ->
            add(kotlinx.serialization.json.buildJsonObject {
                put("id", kotlinx.serialization.json.JsonPrimitive(event.id))
                put("title", kotlinx.serialization.json.JsonPrimitive(event.title))
                put("startAt", kotlinx.serialization.json.JsonPrimitive(event.startAt))
                put("category", kotlinx.serialization.json.JsonPrimitive(event.category))
                put("subcategory", kotlinx.serialization.json.JsonPrimitive(event.subcategory))
                put("imageUrl", kotlinx.serialization.json.JsonPrimitive(event.imageUrl))
                put("isFree", kotlinx.serialization.json.JsonPrimitive(event.isFree))
                put("priceDisplay", kotlinx.serialization.json.JsonPrimitive(event.priceDisplay))
                put("dateDisplay", kotlinx.serialization.json.JsonPrimitive(event.dateDisplay))
            })
        }
    }.toString()

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
        eventsJson,
        v.reservationUrl,
        cardX, cardY, cardW, cardH,
    )
}
