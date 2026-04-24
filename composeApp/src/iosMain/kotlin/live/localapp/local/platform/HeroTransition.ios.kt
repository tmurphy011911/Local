package live.localapp.local.platform

import kotlinx.coroutines.launch
import live.localapp.local.ChatBridge
import live.localapp.local.ChatSheetBridge
import live.localapp.local.PlanChatBridge
import live.localapp.local.PlanChatSheetBridge
import live.localapp.local.PlanDetailBridge
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

private var chatObserverJob: kotlinx.coroutines.Job? = null

actual fun openChatSheet(onReady: () -> Unit) {
    ChatSheetBridge.onOpenComplete = onReady

    // Start observing ChatViewModel state and pushing to bridge
    val chatVm = org.koin.mp.KoinPlatform.getKoin().get<live.localapp.local.ui.chat.ChatViewModel>()
    ChatBridge.sendMessage = { msg -> chatVm.sendMessage(msg) }
    ChatBridge.resetChat = { chatVm.resetChat() }
    ChatBridge.onQuickReply = { label, action ->
        chatVm.onQuickReply(live.localapp.local.ui.chat.QuickReply(label = label, action = action))
    }
    // Premium gate — wire the approval check + blocked callback for iOS.
    // On Android this is set in AppNavigation's Compose sheet block.
    val premiumGate = live.localapp.local.ui.chat.PremiumGate()
    chatVm.onPlanApprovalCheck = { premiumGate.canCreatePlan(live.localapp.local.ui.chat.PlanSource.YOCAL) }
    chatVm.onPlanSaved = { premiumGate.recordPlanCreated(live.localapp.local.ui.chat.PlanSource.YOCAL) }
    chatVm.onPlanBlocked = { ChatBridge.onPlanBlocked?.invoke() }
    ChatBridge.clearSuccessState = { chatVm.clearSuccessState() }
    // Direct "Save to My Plans" tap bypasses the chat message path — the
    // VM runs the gate + save in place, so no "Looks good…" user bubble
    // is added to the transcript.
    ChatBridge.approvePlan = { chatVm.approveCurrentPlan() }
    // After a SwiftUI paywall purchase succeeds, Swift fires this so we can
    // re-query RevenueCat and flip hasPaidSubscription to true right away
    // (instead of waiting for the next app-launch checkSubscriptionStatus).
    ChatBridge.refreshSubscription = {
        live.localapp.local.platform.checkSubscriptionStatus { isPro, expiry ->
            premiumGate.hasPaidSubscription = isPro
            if (expiry != null) premiumGate.paidSubscriptionExpiry = expiry.take(10)
            println("ChatBridge.refreshSubscription: isPro=$isPro expiry=$expiry")
        }
    }

    // Event/venue taps from plan cards in SwiftUI chat.
    // Fetch the full object then present the native detail screen.
    ChatBridge.onEventTap = { eventId, imageUrl ->
        kotlinx.coroutines.MainScope().launch {
            chatVm.recordEventTap(eventId)
            val event = try { chatVm.getEventById(eventId) } catch (_: Throwable) { null }
            if (event != null) {
                showNativeEventDetail(
                    event = event,
                    cardX = 0f, cardY = 0f, cardW = 0f, cardH = 0f,
                    cardImageUrl = imageUrl ?: event.imageUrl,
                    onDismissed = {},
                    onFavorited = null,
                    showDirections = false,
                    isFavorited = false,
                )
            }
        }
    }
    ChatBridge.onVenueTap = { venueName ->
        kotlinx.coroutines.MainScope().launch {
            val venue = try { chatVm.getVenueByName(venueName) } catch (_: Throwable) { null }
            if (venue != null) {
                showNativeVenueDetail(
                    venueWithMeta = live.localapp.local.domain.repository.VenueWithMeta(venue = venue),
                    cardX = 0f, cardY = 0f, cardW = 0f, cardH = 0f,
                    onDismissed = {},
                )
            }
        }
    }

    chatObserverJob?.cancel()
    val scope = kotlinx.coroutines.MainScope()
    chatObserverJob = scope.launch {
        chatVm.uiState.collect { state ->
            ChatBridge.supersededIds = live.localapp.local.ui.chat.supersededPlanIds
            ChatBridge.pushState(state)
        }
    }

    ChatSheetBridge.onOpenSheet?.invoke()
}

actual fun closeChatSheet(onDone: () -> Unit) {
    chatObserverJob?.cancel()
    chatObserverJob = null
    ChatSheetBridge.onCloseComplete = onDone
    ChatSheetBridge.onCloseSheet?.invoke()
}

private var planChatObserverJob: kotlinx.coroutines.Job? = null

actual fun openPlanChatSheet(
    plan: live.localapp.local.domain.model.Plan,
    prePopulatedMessage: String?,
    onDismiss: () -> Unit,
) {
    // Build the per-plan VM via Koin factory
    val koin = org.koin.mp.KoinPlatform.getKoin()
    val vm = koin.get<live.localapp.local.ui.plans.PlanChatViewModel> {
        org.koin.core.parameter.parametersOf(plan)
    }

    PlanChatBridge.planTitle = plan.title
    PlanChatBridge.planId = plan.id
    PlanChatBridge.prePopulatedMessage = prePopulatedMessage
    PlanChatBridge.currentUserId = koin
        .get<live.localapp.local.domain.repository.UserPreferencesRepository>()
        .getLocalUserId()
    PlanChatBridge.sendMessage = { text -> vm.sendMessage(text) }
    PlanChatBridge.toggleReaction = { messageId, emoji -> vm.toggleReaction(messageId, emoji) }
    PlanChatBridge.confirmChange = { vm.confirmChange() }
    PlanChatBridge.cancelChange = { vm.cancelChange() }
    PlanChatBridge.onClose = onDismiss

    // Observe VM state and push to Swift
    planChatObserverJob?.cancel()
    val scope = kotlinx.coroutines.MainScope()
    planChatObserverJob = scope.launch {
        vm.uiState.collect { state -> PlanChatBridge.pushState(state) }
    }

    PlanChatSheetBridge.onOpenComplete = null
    PlanChatSheetBridge.onOpenSheet?.invoke()
}

actual fun closePlanChatSheet() {
    planChatObserverJob?.cancel()
    planChatObserverJob = null
    PlanChatBridge.reset()
    PlanChatSheetBridge.onCloseSheet?.invoke()
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
    isFavorited: Boolean,
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
        isFavorited,
    )
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
    PlanDetailBridge.onDismissed = onDismissed
    PlanDetailBridge.onShare = onShare
    PlanDetailBridge.onEditPlan = onEditPlan
    PlanDetailBridge.onEditDay = onEditDay
    PlanDetailBridge.onSetGroupSize = onSetGroupSize
    PlanDetailBridge.onSetGroupSize = onSetGroupSize

    // Serialize plan to JSON for Swift — preserves attendee + creator + group
    // metadata so the Compose PlanDetailScreen can render the creator view
    // correctly when re-hydrated on the iOS side.
    val planJson = kotlinx.serialization.json.buildJsonObject {
        put("id", kotlinx.serialization.json.JsonPrimitive(plan.id))
        put("title", kotlinx.serialization.json.JsonPrimitive(plan.title))
        put("city", kotlinx.serialization.json.JsonPrimitive(plan.city))
        put("dateDisplay", kotlinx.serialization.json.JsonPrimitive(plan.dateDisplay))
        put("isPast", kotlinx.serialization.json.JsonPrimitive(plan.isPast))
        put("creatorId", kotlinx.serialization.json.JsonPrimitive(plan.creatorId))
        put("creatorName", kotlinx.serialization.json.JsonPrimitive(plan.creatorName))
        put("shareToken", kotlinx.serialization.json.JsonPrimitive(plan.shareToken))
        put("groupSize", kotlinx.serialization.json.JsonPrimitive(plan.groupSize))
        put("confirmedCount", kotlinx.serialization.json.JsonPrimitive(plan.confirmedCount))
        put("attendeesCanEdit", kotlinx.serialization.json.JsonPrimitive(plan.attendeesCanEdit))
        put("attendees", kotlinx.serialization.json.buildJsonArray {
            plan.attendees.forEach { a ->
                add(kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(a.id))
                    put("name", kotlinx.serialization.json.JsonPrimitive(a.name))
                    put("initials", kotlinx.serialization.json.JsonPrimitive(a.initials))
                    put("confirmed", kotlinx.serialization.json.JsonPrimitive(a.confirmed))
                    put("avatarColor", kotlinx.serialization.json.JsonPrimitive(a.avatarColor))
                })
            }
        })
        put("days", kotlinx.serialization.json.buildJsonArray {
            plan.days.forEach { day ->
                add(kotlinx.serialization.json.buildJsonObject {
                    put("label", kotlinx.serialization.json.JsonPrimitive(day.label))
                    put("shortName", kotlinx.serialization.json.JsonPrimitive(day.shortName))
                    put("slots", kotlinx.serialization.json.buildJsonArray {
                        day.slots.forEach { slot ->
                            add(kotlinx.serialization.json.buildJsonObject {
                                put("id", kotlinx.serialization.json.JsonPrimitive(slot.id))
                                put("time", kotlinx.serialization.json.JsonPrimitive(slot.time))
                                put("emoji", kotlinx.serialization.json.JsonPrimitive(slot.emoji))
                                put("name", kotlinx.serialization.json.JsonPrimitive(slot.name))
                                put("detail", kotlinx.serialization.json.JsonPrimitive(slot.detail))
                                put("price", kotlinx.serialization.json.JsonPrimitive(slot.price))
                                put("isFree", kotlinx.serialization.json.JsonPrimitive(slot.isFree))
                                put("isReserved", kotlinx.serialization.json.JsonPrimitive(slot.isReserved))
                                put("imageUrl", kotlinx.serialization.json.JsonPrimitive(slot.imageUrl))
                                put("ticketUrl", kotlinx.serialization.json.JsonPrimitive(slot.ticketUrl))
                                put("venueRating", kotlinx.serialization.json.JsonPrimitive(slot.venueRating))
                                put("venueAddress", kotlinx.serialization.json.JsonPrimitive(slot.venueAddress))
                                put("venueLat", kotlinx.serialization.json.JsonPrimitive(slot.venueLat))
                                put("venueLng", kotlinx.serialization.json.JsonPrimitive(slot.venueLng))
                                put("venueCategory", kotlinx.serialization.json.JsonPrimitive(slot.venueCategory))
                                put("venueWebsite", kotlinx.serialization.json.JsonPrimitive(slot.venueWebsite))
                                put("sourceUrl", kotlinx.serialization.json.JsonPrimitive(slot.sourceUrl))
                                put("reservationUrl", kotlinx.serialization.json.JsonPrimitive(slot.reservationUrl))
                                put("eventId", kotlinx.serialization.json.JsonPrimitive(slot.eventId))
                            })
                        }
                    })
                })
            }
        })
    }.toString()

    PlanDetailBridge.showPlanDetail?.invoke(planJson, cardX, cardY, cardW, cardH)
}

actual fun showNativeVenueDetail(
    venueWithMeta: VenueWithMeta,
    cardX: Float,
    cardY: Float,
    cardW: Float,
    cardH: Float,
    onDismissed: () -> Unit,
    isFavorited: Boolean,
    onFavorited: ((String) -> Unit)?,
) {
    val v = venueWithMeta.venue
    VenueDetailBridge.onDismissed = onDismissed
    VenueDetailBridge.onFavorited = onFavorited

    // Serialize upcoming events as JSON for Swift
    val eventsJson = kotlinx.serialization.json.buildJsonArray {
        venueWithMeta.upcomingEvents.forEach { event ->
            add(kotlinx.serialization.json.buildJsonObject {
                put("id", kotlinx.serialization.json.JsonPrimitive(event.id))
                put("title", kotlinx.serialization.json.JsonPrimitive(event.title))
                put("description", kotlinx.serialization.json.JsonPrimitive(event.description))
                put("startAt", kotlinx.serialization.json.JsonPrimitive(event.startAt))
                put("category", kotlinx.serialization.json.JsonPrimitive(event.category))
                put("subcategory", kotlinx.serialization.json.JsonPrimitive(event.subcategory))
                put("imageUrl", kotlinx.serialization.json.JsonPrimitive(event.imageUrl))
                put("isFree", kotlinx.serialization.json.JsonPrimitive(event.isFree))
                put("priceDisplay", kotlinx.serialization.json.JsonPrimitive(event.priceDisplay))
                put("dateDisplay", kotlinx.serialization.json.JsonPrimitive(event.dateDisplay))
                put("venueName", kotlinx.serialization.json.JsonPrimitive(event.venueName))
                put("venueCity", kotlinx.serialization.json.JsonPrimitive(event.venueCity))
                put("ticketUrl", kotlinx.serialization.json.JsonPrimitive(event.ticketUrl))
                put("sourceUrl", kotlinx.serialization.json.JsonPrimitive(event.sourceUrl))
            })
        }
    }.toString()

    VenueDetailBridge.showVenueDetail?.invoke(
        v.id,
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
        isFavorited,
        cardX, cardY, cardW, cardH,
    )
}

actual fun updateNativeVenueDetailEvents(
    venueId: String,
    upcomingEvents: List<live.localapp.local.domain.model.Event>,
) {
    val eventsJson = kotlinx.serialization.json.buildJsonArray {
        upcomingEvents.forEach { event ->
            add(kotlinx.serialization.json.buildJsonObject {
                put("id", kotlinx.serialization.json.JsonPrimitive(event.id))
                put("title", kotlinx.serialization.json.JsonPrimitive(event.title))
                put("description", kotlinx.serialization.json.JsonPrimitive(event.description))
                put("startAt", kotlinx.serialization.json.JsonPrimitive(event.startAt))
                put("category", kotlinx.serialization.json.JsonPrimitive(event.category))
                put("subcategory", kotlinx.serialization.json.JsonPrimitive(event.subcategory))
                put("imageUrl", kotlinx.serialization.json.JsonPrimitive(event.imageUrl))
                put("isFree", kotlinx.serialization.json.JsonPrimitive(event.isFree))
                put("priceDisplay", kotlinx.serialization.json.JsonPrimitive(event.priceDisplay))
                put("dateDisplay", kotlinx.serialization.json.JsonPrimitive(event.dateDisplay))
                put("venueName", kotlinx.serialization.json.JsonPrimitive(event.venueName))
                put("venueCity", kotlinx.serialization.json.JsonPrimitive(event.venueCity))
                put("ticketUrl", kotlinx.serialization.json.JsonPrimitive(event.ticketUrl))
                put("sourceUrl", kotlinx.serialization.json.JsonPrimitive(event.sourceUrl))
            })
        }
    }.toString()
    VenueDetailBridge.updateVenueEvents?.invoke(venueId, eventsJson)
}
