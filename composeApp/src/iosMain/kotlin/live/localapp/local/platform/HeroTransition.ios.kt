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

    // If the user's last visit ended in a successful save, the celebration
    // overlay and saved-plan state are still in the VM. Opening the chat
    // without clearing them would drop the user right back on the success
    // screen instead of a fresh conversation, so reset here.
    if (chatVm.uiState.value.showPlanSuccess) {
        chatVm.dismissPlanSuccess(navigateToPlans = false)
    }
    ChatBridge.sendMessage = { msg -> chatVm.sendMessage(msg) }
    ChatBridge.resetChat = { chatVm.resetChat() }
    ChatBridge.buildPlanFromSelections = { ids -> chatVm.buildPlanFromSelections(ids) }
    ChatBridge.loadMoreSuggestions = { messageId, dayKey, categoryKey ->
        chatVm.loadMoreSuggestionsForGroup(messageId, dayKey, categoryKey)
    }
    chatVm.onSuggestionLoadMoreFinished = { messageId, groupKey, addedCount ->
        ChatBridge.onLoadMoreFinished?.invoke(messageId, groupKey, addedCount)
    }
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
    ChatBridge.sharePlan = { chatVm.shareLastSavedPlan() }
    // "View Plan" on the celebration overlay → set a pending-open
    // signal on PlansViewModel. PlansScreen observes it and triggers
    // its existing onPlanClick handler once the tab is active, which
    // wires up the FULL plan-detail bridge (delete, edit, swap, etc.).
    // Going through showNativePlanDetail directly here would require
    // duplicating ~25 handlers and they'd no-op silently.
    ChatBridge.viewSavedPlan = { planId ->
        val plansVm = org.koin.mp.KoinPlatform.getKoin()
            .get<live.localapp.local.ui.plans.PlansViewModel>()
        plansVm.requestOpenPlan(planId)
    }
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

actual fun presentNativePaywall(onPurchased: (Boolean) -> Unit) {
    val present = ChatBridge.presentPaywall
    if (present == null) {
        // Bridge not wired yet (Swift side hasn't attached). Fail closed so
        // callers don't assume a purchase succeeded.
        onPurchased(false)
        return
    }
    present { purchased ->
        if (purchased) {
            // Mirror the in-chat paywall flow — sync Kotlin's PremiumGate from
            // RevenueCat after a purchase / restore so subsequent gate checks
            // pass without waiting for the next app launch.
            live.localapp.local.platform.checkSubscriptionStatus { isPro, expiry ->
                val gate = live.localapp.local.ui.chat.PremiumGate()
                gate.hasPaidSubscription = isPro
                if (expiry != null) gate.paidSubscriptionExpiry = expiry.take(10)
                println("presentNativePaywall: post-purchase sync isPro=$isPro expiry=$expiry")
                onPurchased(isPro)
            }
        } else {
            onPurchased(false)
        }
    }
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
        event.timeKnown,
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
    onChangeName: (String) -> Unit,
    onDeletePlan: () -> Unit,
    onAddDay: () -> Unit,
    onAddEvent: (Int) -> Unit,
    onSetSlotReserved: (Int, String, Boolean) -> Unit,
    onSaveReservationDetails: (Int, String, Int, String) -> Unit,
    onReservationUnavailable: (Int, String) -> Unit,
    onClearReservation: (Int, String) -> Unit,
    onSaveTicketDetails: (Int, String, Int) -> Unit,
    onTicketsUnavailable: (Int, String) -> Unit,
    onClearTickets: (Int, String) -> Unit,
    onReplaceSlot: (Int, String, String) -> Unit,
    onRemoveSlot: (Int, String) -> Unit,
    onInvite: () -> Unit,
    onChangeRsvp: (String) -> Unit,
    onRemoveAttendee: (String) -> Unit,
    onRequestSwapAlternatives: (Int, String) -> Unit,
    onSwapInAlternative: (Int, String, String) -> Unit,
    onSeeMoreSwapOptions: (Int, String, String) -> Unit,
    onSwapForUnavailable: (Int, String, String, String) -> Unit,
    onSlotTap: (Int, String, String?, String?) -> Unit,
) {
    PlanDetailBridge.onSlotTap = onSlotTap
    PlanDetailBridge.onDismissed = onDismissed
    PlanDetailBridge.onShare = onShare
    PlanDetailBridge.onEditPlan = onEditPlan
    PlanDetailBridge.onEditDay = onEditDay
    PlanDetailBridge.onSetGroupSize = onSetGroupSize
    PlanDetailBridge.onChangeName = onChangeName
    PlanDetailBridge.onDeletePlan = onDeletePlan
    PlanDetailBridge.onAddDay = onAddDay
    PlanDetailBridge.onAddEvent = onAddEvent
    PlanDetailBridge.onSetSlotReserved = onSetSlotReserved
    PlanDetailBridge.onSaveReservationDetails = onSaveReservationDetails
    PlanDetailBridge.onReservationUnavailable = onReservationUnavailable
    PlanDetailBridge.onClearReservation = onClearReservation
    PlanDetailBridge.onSaveTicketDetails = onSaveTicketDetails
    PlanDetailBridge.onTicketsUnavailable = onTicketsUnavailable
    PlanDetailBridge.onClearTickets = onClearTickets
    PlanDetailBridge.onRequestSwapAlternatives = onRequestSwapAlternatives
    PlanDetailBridge.onSwapInAlternative = onSwapInAlternative
    PlanDetailBridge.onSeeMoreSwapOptions = onSeeMoreSwapOptions
    PlanDetailBridge.onSwapForUnavailable = onSwapForUnavailable
    PlanDetailBridge.onReplaceSlot = onReplaceSlot
    PlanDetailBridge.onRemoveSlot = onRemoveSlot
    PlanDetailBridge.onInvite = onInvite
    PlanDetailBridge.onChangeRsvp = onChangeRsvp
    PlanDetailBridge.onRemoveAttendee = onRemoveAttendee

    // Serialize plan to JSON for Swift — preserves attendee + creator + group
    // metadata so the Compose PlanDetailScreen can render the creator view
    // correctly when re-hydrated on the iOS side.
    val currentUserId = try {
        org.koin.mp.KoinPlatform.getKoin()
            .get<live.localapp.local.domain.repository.UserPreferencesRepository>()
            .getLocalUserId()
    } catch (_: Throwable) { null }
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
        put("currentUserId", kotlinx.serialization.json.JsonPrimitive(currentUserId))
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
                                put("venueReservable", kotlinx.serialization.json.JsonPrimitive(slot.venueReservable))
                                put("reservationState", kotlinx.serialization.json.JsonPrimitive(slot.reservationState.name.lowercase()))
                                put("reservationPartySize", kotlinx.serialization.json.JsonPrimitive(slot.reservationPartySize))
                                put("reservationTime", kotlinx.serialization.json.JsonPrimitive(slot.reservationTime))
                                put("ticketState", kotlinx.serialization.json.JsonPrimitive(slot.ticketState.name.lowercase()))
                                put("ticketCount", kotlinx.serialization.json.JsonPrimitive(slot.ticketCount))
                                put("imageUrl", kotlinx.serialization.json.JsonPrimitive(slot.imageUrl))
                                put("ticketUrl", kotlinx.serialization.json.JsonPrimitive(slot.ticketUrl))
                                put("venueRating", kotlinx.serialization.json.JsonPrimitive(slot.venueRating))
                                put("venueAddress", kotlinx.serialization.json.JsonPrimitive(slot.venueAddress))
                                put("venueCity", kotlinx.serialization.json.JsonPrimitive(slot.venueCity))
                                put("venueName", kotlinx.serialization.json.JsonPrimitive(slot.venueName))
                                put("venueLat", kotlinx.serialization.json.JsonPrimitive(slot.venueLat))
                                put("venueLng", kotlinx.serialization.json.JsonPrimitive(slot.venueLng))
                                put("venueCategory", kotlinx.serialization.json.JsonPrimitive(slot.venueCategory))
                                put("venueWebsite", kotlinx.serialization.json.JsonPrimitive(slot.venueWebsite))
                                put("sourceUrl", kotlinx.serialization.json.JsonPrimitive(slot.sourceUrl))
                                put("reservationUrl", kotlinx.serialization.json.JsonPrimitive(slot.reservationUrl))
                                put("eventId", kotlinx.serialization.json.JsonPrimitive(slot.eventId))
                                put("isBoWeb", kotlinx.serialization.json.JsonPrimitive(slot.isBoWeb))
                                put("boWebState", kotlinx.serialization.json.JsonPrimitive(slot.boWebState))
                            })
                        }
                    })
                })
            }
        })
    }.toString()

    PlanDetailBridge.showPlanDetail?.invoke(planJson, cardX, cardY, cardW, cardH)
}

actual fun pushSwapAlternatives(json: String) {
    PlanDetailBridge.onSwapAlternativesReady?.invoke(json)
}

actual fun pushNativePlanState(plan: live.localapp.local.domain.model.Plan) {
    val planJson = serializePlanToJson(plan)
    PlanDetailBridge.pushPlanState?.invoke(planJson)
}

actual fun signalPlanEditApplied() {
    live.localapp.local.PlanChatBridge.onPlanEditApplied?.invoke()
}

private fun serializePlanToJson(plan: live.localapp.local.domain.model.Plan): String =
    kotlinx.serialization.json.buildJsonObject {
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
            plan.days.forEachIndexed { idx, day ->
                add(kotlinx.serialization.json.buildJsonObject {
                    put("dayIndex", kotlinx.serialization.json.JsonPrimitive(idx))
                    put("label", kotlinx.serialization.json.JsonPrimitive(day.label))
                    put("shortName", kotlinx.serialization.json.JsonPrimitive(day.shortName))
                    put("travel", kotlinx.serialization.json.buildJsonArray {
                        day.travel.forEach { t ->
                            add(kotlinx.serialization.json.buildJsonObject {
                                put("mode", kotlinx.serialization.json.JsonPrimitive(t.mode))
                                put("mins", kotlinx.serialization.json.JsonPrimitive(t.mins))
                            })
                        }
                    })
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
                                put("venueReservable", kotlinx.serialization.json.JsonPrimitive(slot.venueReservable))
                                put("reservationState", kotlinx.serialization.json.JsonPrimitive(slot.reservationState.name.lowercase()))
                                put("reservationPartySize", kotlinx.serialization.json.JsonPrimitive(slot.reservationPartySize))
                                put("reservationTime", kotlinx.serialization.json.JsonPrimitive(slot.reservationTime))
                                put("ticketState", kotlinx.serialization.json.JsonPrimitive(slot.ticketState.name.lowercase()))
                                put("ticketCount", kotlinx.serialization.json.JsonPrimitive(slot.ticketCount))
                                put("imageUrl", kotlinx.serialization.json.JsonPrimitive(slot.imageUrl))
                                put("ticketUrl", kotlinx.serialization.json.JsonPrimitive(slot.ticketUrl))
                                put("venueRating", kotlinx.serialization.json.JsonPrimitive(slot.venueRating))
                                put("venueAddress", kotlinx.serialization.json.JsonPrimitive(slot.venueAddress))
                                put("venueCity", kotlinx.serialization.json.JsonPrimitive(slot.venueCity))
                                put("venueName", kotlinx.serialization.json.JsonPrimitive(slot.venueName))
                                put("venueLat", kotlinx.serialization.json.JsonPrimitive(slot.venueLat))
                                put("venueLng", kotlinx.serialization.json.JsonPrimitive(slot.venueLng))
                                put("venueCategory", kotlinx.serialization.json.JsonPrimitive(slot.venueCategory))
                                put("venueWebsite", kotlinx.serialization.json.JsonPrimitive(slot.venueWebsite))
                                put("sourceUrl", kotlinx.serialization.json.JsonPrimitive(slot.sourceUrl))
                                put("reservationUrl", kotlinx.serialization.json.JsonPrimitive(slot.reservationUrl))
                                put("eventId", kotlinx.serialization.json.JsonPrimitive(slot.eventId))
                                put("isBoWeb", kotlinx.serialization.json.JsonPrimitive(slot.isBoWeb))
                                put("boWebState", kotlinx.serialization.json.JsonPrimitive(slot.boWebState))
                            })
                        }
                    })
                })
            }
        })
    }.toString()

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
