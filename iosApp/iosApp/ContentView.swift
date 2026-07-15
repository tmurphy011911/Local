import UIKit
import SwiftUI
import ComposeApp
import RevenueCat

/// Walk to the actual top-most view controller (presented chain). Needed
/// because the slot-tap path on PlanDetailView (presented as a sheet,
/// possibly itself nested behind ChatSheet) needs to present its event/
/// venue detail OVER PlanDetail, not on whatever rootVC happens to be at
/// the moment. `presentedViewController` only walks one step — this helper
/// walks to the leaf so the new detail always lands on top of PlanDetail.
private func topPresentedViewController(in window: UIWindow) -> UIViewController? {
    var top = window.rootViewController
    while let presented = top?.presentedViewController {
        top = presented
    }
    return top
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let vc = MainViewControllerKt.MainViewController()

        // Wire the Kotlin ↔ Swift hero transition bridge (image-only morph)
        setupHeroTransitionBridge(composeVC: vc)

        // Wire the Kotlin ↔ Swift native event detail bridge
        setupEventDetailBridge(composeVC: vc)

        // Wire the Kotlin ↔ Swift native venue detail bridge
        setupVenueDetailBridge(composeVC: vc)

        // Wire the Kotlin ↔ Swift native home-city editor (leaderboard)
        setupHomeCityEditBridge(composeVC: vc)

        // Wire chat sheet background animation
        setupChatSheetBridge(composeVC: vc)

        // Wire ChatBridge.presentPaywall so non-chat surfaces (Profile upgrade
        // CTA, etc.) can show the native paywall without opening the chat.
        setupPaywallBridge()

        // Wire plan chat sheet (per-plan group chat)
        setupPlanChatSheetBridge(composeVC: vc)

        // Wire share sheet bridge
        setupShareBridge(composeVC: vc)

        // Wire plan detail transition bridge
        setupPlanDetailBridge(composeVC: vc)

        // Wire RevenueCat subscription bridge
        setupRevenueCatBridge()

        // Wire OneSignal push bridge (login/logout + permission + tap→deep-link)
        setupPushBridge()

        // Wire native time picker + Apple Maps autocomplete + date range bridges
        NativeTimePickerBridgeSetup.register()
        PlacesAutocompleteBridgeSetup.register()
        NativeDateRangePickerBridgeSetup.register()
        InAppBrowserBridgeSetup.register()

        return vc
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    // MARK: - Hero Transition Wiring (image-only morph, still used by AppNavigation)

    private func setupHeroTransitionBridge(composeVC: UIViewController) {
        let bridge = HeroTransitionBridge.shared
        let hero = HeroTransitionController.shared

        bridge.onExpandRequested = { imageUrl, frameX, frameY, frameW, frameH in
            DispatchQueue.main.async {
                guard let window = composeVC.view.window ?? UIApplication.shared.connectedScenes
                    .compactMap({ $0 as? UIWindowScene })
                    .flatMap({ $0.windows })
                    .first(where: { $0.isKeyWindow })
                else {
                    bridge.onExpandComplete?()
                    return
                }

                let frame = CGRect(
                    x: CGFloat(frameX),
                    y: CGFloat(frameY),
                    width: CGFloat(frameW),
                    height: CGFloat(frameH)
                )

                hero.onExpandComplete = {
                    bridge.onExpandComplete?()
                }

                hero.expand(imageUrl: imageUrl, from: frame, in: window)
            }
        }

        bridge.onCollapseRequested = {
            DispatchQueue.main.async {
                hero.onCollapseComplete = {
                    bridge.onCollapseComplete?()
                }
                hero.collapse()
            }
        }
    }

    // MARK: - Native Event Detail Wiring

    private func setupHomeCityEditBridge(composeVC: UIViewController) {
        HomeCityEditBridge.shared.present = {
            DispatchQueue.main.async {
                guard let window = composeVC.view.window ?? UIApplication.shared.connectedScenes
                    .compactMap({ $0 as? UIWindowScene })
                    .flatMap({ $0.windows })
                    .first(where: { $0.isKeyWindow })
                else { return }

                weak var weakVC: UIHostingController<HomeCityEditView>?
                let view = HomeCityEditView(
                    onConfirm: { name, lat, lng, completion in
                        HomeCityEditBridge.shared.onConfirm?(name, KotlinDouble(value: lat), KotlinDouble(value: lng), nil) { ok, rateLimited, nextAllowedAt in
                            completion(ok.boolValue, rateLimited.boolValue, nextAllowedAt)
                            return KotlinUnit()
                        }
                    },
                    onDismiss: { weakVC?.dismiss(animated: true) }
                )
                let hostingVC = UIHostingController(rootView: view)
                weakVC = hostingVC
                hostingVC.modalPresentationStyle = .pageSheet
                if let sheet = hostingVC.sheetPresentationController {
                    sheet.detents = [.large()]
                    sheet.prefersGrabberVisible = true
                }
                topPresentedViewController(in: window)?.present(hostingVC, animated: true)
            }
        }
    }

    private func setupEventDetailBridge(composeVC: UIViewController) {
        let detailBridge = EventDetailBridge.shared
        let transition = EventDetailTransitionController.shared

        detailBridge.showEventDetail = {
            id, title, description, venueDescription,
            imageUrl, venueImageUrl,
            startAt, venueName, venueAddress,
            venueCity, venueLat, venueLng,
            isFree, priceDisplay, ticketUrl, sourceUrl,
            category, subcategory, source,
            cardX, cardY, cardW, cardH, cardImageUrl,
            isFavorited, timeKnown, venueDistanceMiles in

            DispatchQueue.main.async {
                guard let window = composeVC.view.window ?? UIApplication.shared.connectedScenes
                    .compactMap({ $0 as? UIWindowScene })
                    .flatMap({ $0.windows })
                    .first(where: { $0.isKeyWindow })
                else { return }

                let event = EventDetailData(
                    id: id,
                    title: title,
                    description: description,
                    venueDescription: venueDescription,
                    imageUrl: imageUrl ?? cardImageUrl,
                    venueImageUrl: venueImageUrl,
                    startAt: startAt,
                    venueName: venueName,
                    venueAddress: venueAddress,
                    venueCity: venueCity,
                    venueLat: venueLat.doubleValue,
                    venueLng: venueLng.doubleValue,
                    isFree: isFree.boolValue,
                    priceDisplay: priceDisplay,
                    ticketUrl: ticketUrl,
                    sourceUrl: sourceUrl,
                    category: category,
                    subcategory: subcategory,
                    source: source,
                    showDirections: detailBridge.showDirections,
                    isFavorited: isFavorited.boolValue,
                    timeKnown: timeKnown.boolValue,
                    venueDistanceMiles: venueDistanceMiles.doubleValue,
                    venueUpcomingEventCount: 0
                )

                let cardFrame = CGRect(
                    x: CGFloat(cardX),
                    y: CGFloat(cardY),
                    width: CGFloat(cardW),
                    height: CGFloat(cardH)
                )

                // When opened from chat plan cards, coords are all zero —
                // use a bottom-slide modal instead of the hero transition.
                if cardFrame.width == 0 && cardFrame.height == 0 {
                    // Fire onDismissed exactly once, from whichever path closes
                    // the sheet: the back button (programmatic dismiss, below) or
                    // an interactive swipe-down (the presentation delegate). The
                    // two are mutually exclusive in UIKit, but the flag guards
                    // against any future overlap. Without the swipe path, a
                    // deep-linked event's onDismissed never fired and the app was
                    // stranded on a blank surface.
                    var didFireDismiss = false
                    let fireDismiss = {
                        if didFireDismiss { return }
                        didFireDismiss = true
                        detailBridge.onDismissed?()
                    }
                    weak var weakHostingVC: UIHostingController<EventDetailView>?
                    let detailView = EventDetailView(event: event, onDismiss: {
                        weakHostingVC?.dismiss(animated: true) { fireDismiss() }
                    })
                    let hostingVC = UIHostingController(rootView: detailView)
                    weakHostingVC = hostingVC
                    hostingVC.modalPresentationStyle = .pageSheet
                    if let sheet = hostingVC.sheetPresentationController {
                        sheet.detents = [.large()]
                        sheet.prefersGrabberVisible = true
                    }
                    // Swipe-to-dismiss doesn't call the dismiss completion above,
                    // so a delegate relays the interactive dismissal. Retained on
                    // the transition controller (presentationController is weak).
                    let dismissDelegate = SheetDismissDelegate { fireDismiss() }
                    transition.sheetDismissDelegate = dismissDelegate
                    hostingVC.presentationController?.delegate = dismissDelegate
                    // Walk to the actual top — events triggered from a slot
                    // tap inside PlanDetail (itself a sheet, possibly over
                    // ChatSheet) need this deep walk; the prior shallow
                    // lookup landed on the wrong VC and either failed
                    // silently or stacked weirdly.
                    let topVC = topPresentedViewController(in: window)
                    topVC?.present(hostingVC, animated: true)
                } else {
                    transition.present(event: event, cardFrame: cardFrame, in: window) {
                        detailBridge.onDismissed?()
                    }
                }
            }
        }

        detailBridge.dismissEventDetail = {
            DispatchQueue.main.async {
                transition.dismiss()
            }
        }

        // Async venue upcoming-event count arrives after the detail is presented
        // (events carry no venue FK, so the count needs a name+city lookup).
        // Broadcast so the presented EventDetailView swaps in the real count.
        detailBridge.updateVenueEventCount = { eventId, count in
            DispatchQueue.main.async {
                NotificationCenter.default.post(
                    name: .eventVenueCountDidUpdate,
                    object: nil,
                    userInfo: ["eventId": eventId as String, "count": Int(truncating: count)]
                )
            }
        }
    }

    // MARK: - Native Venue Detail Wiring

    private func setupVenueDetailBridge(composeVC: UIViewController) {
        let venueBridge = VenueDetailBridge.shared
        let transition = VenueDetailTransitionController.shared

        venueBridge.showVenueDetail = {
            id, name, address, city,
            lat, lng,
            photoUrl, description,
            category, googleRating,
            website, distanceMiles,
            upcomingEventCount,
            upcomingEventsJson,
            reservationUrl,
            isFavorited,
            cardX, cardY, cardW, cardH,
            hidesPrimaryCTAs in

            DispatchQueue.main.async {
                guard let window = composeVC.view.window ?? UIApplication.shared.connectedScenes
                    .compactMap({ $0 as? UIWindowScene })
                    .flatMap({ $0.windows })
                    .first(where: { $0.isKeyWindow })
                else { return }

                // Parse upcoming events JSON
                var events: [VenueUpcomingEvent] = []
                if let data = (upcomingEventsJson as String).data(using: .utf8),
                   let json = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] {
                    events = json.compactMap { dict in
                        guard let id = dict["id"] as? String,
                              let title = dict["title"] as? String else { return nil }
                        return VenueUpcomingEvent(
                            id: id,
                            title: title,
                            description: dict["description"] as? String,
                            startAt: dict["startAt"] as? String,
                            category: dict["category"] as? String,
                            subcategory: dict["subcategory"] as? String,
                            imageUrl: dict["imageUrl"] as? String,
                            isFree: dict["isFree"] as? Bool ?? false,
                            priceDisplay: dict["priceDisplay"] as? String,
                            dateDisplay: dict["dateDisplay"] as? String,
                            venueName: dict["venueName"] as? String,
                            venueCity: dict["venueCity"] as? String,
                            ticketUrl: dict["ticketUrl"] as? String,
                            sourceUrl: dict["sourceUrl"] as? String
                        )
                    }
                }

                let venue = VenueDetailData(
                    id: id,
                    name: name,
                    address: address,
                    city: city,
                    lat: lat.doubleValue,
                    lng: lng.doubleValue,
                    photoUrl: photoUrl,
                    description: description,
                    category: category,
                    googleRating: googleRating?.doubleValue,
                    website: website,
                    distanceMiles: distanceMiles?.doubleValue,
                    upcomingEventCount: Int(upcomingEventCount),
                    upcomingEvents: events,
                    reservationUrl: reservationUrl as? String
                )

                let cardFrame = CGRect(
                    x: CGFloat(cardX),
                    y: CGFloat(cardY),
                    width: CGFloat(cardW),
                    height: CGFloat(cardH)
                )

                // Zero-frame path = called from PlanDetail's slot tap (or any
                // non-card-anchored origin). The hero transition adds the
                // detail directly to the WINDOW via addSubview, which covers
                // PlanDetail and prevents a clean dismiss-back-to-PlanDetail.
                // For these calls, present as a regular pageSheet OVER the
                // top VC instead — same pattern Event detail already uses.
                if cardFrame.width == 0 && cardFrame.height == 0 {
                    weak var weakHostingVC: UIHostingController<VenueDetailView>?
                    let detailView = VenueDetailView(
                        venue: venue,
                        onDismiss: {
                            weakHostingVC?.dismiss(animated: true) {
                                venueBridge.onDismissed?()
                            }
                        },
                        isFavorited: isFavorited.boolValue,
                        onFavorite: { vid in venueBridge.onFavorited?(vid) },
                        hidesPrimaryCTAs: hidesPrimaryCTAs.boolValue
                    )
                    let hostingVC = UIHostingController(rootView: detailView)
                    weakHostingVC = hostingVC
                    hostingVC.modalPresentationStyle = .pageSheet
                    if let sheet = hostingVC.sheetPresentationController {
                        sheet.detents = [.large()]
                        sheet.prefersGrabberVisible = true
                    }
                    let topVC = topPresentedViewController(in: window)
                    topVC?.present(hostingVC, animated: true)
                } else {
                    transition.present(
                        venue: venue,
                        cardFrame: cardFrame,
                        in: window,
                        isFavorited: isFavorited.boolValue,
                        onFavorite: { vid in venueBridge.onFavorited?(vid) },
                        onDismissed: { venueBridge.onDismissed?() },
                        hidesPrimaryCTAs: hidesPrimaryCTAs.boolValue
                    )
                }
            }
        }

        venueBridge.dismissVenueDetail = {
            DispatchQueue.main.async {
                transition.dismiss()
            }
        }

        // Async events arrive via this bridge call after the detail has been presented.
        // Parse, then broadcast via NotificationCenter so the SwiftUI VenueDetailView
        // picks it up in an .onReceive and swaps skeleton -> real events.
        venueBridge.updateVenueEvents = { venueId, upcomingEventsJson in
            DispatchQueue.main.async {
                var events: [VenueUpcomingEvent] = []
                if let data = (upcomingEventsJson as String).data(using: .utf8),
                   let json = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] {
                    events = json.compactMap { dict in
                        guard let id = dict["id"] as? String,
                              let title = dict["title"] as? String else { return nil }
                        return VenueUpcomingEvent(
                            id: id,
                            title: title,
                            description: dict["description"] as? String,
                            startAt: dict["startAt"] as? String,
                            category: dict["category"] as? String,
                            subcategory: dict["subcategory"] as? String,
                            imageUrl: dict["imageUrl"] as? String,
                            isFree: dict["isFree"] as? Bool ?? false,
                            priceDisplay: dict["priceDisplay"] as? String,
                            dateDisplay: dict["dateDisplay"] as? String,
                            venueName: dict["venueName"] as? String,
                            venueCity: dict["venueCity"] as? String,
                            ticketUrl: dict["ticketUrl"] as? String,
                            sourceUrl: dict["sourceUrl"] as? String
                        )
                    }
                }
                NotificationCenter.default.post(
                    name: .venueEventsDidUpdate,
                    object: nil,
                    userInfo: ["venueId": venueId as String, "events": events]
                )
            }
        }
    }

    // MARK: - Native Tab Bar

    private func setupNativeTabBar(composeVC: UIViewController) {
        let tabBar = NativeTabBar.shared
        let bridge = TabBarBridge.shared

        // Setup after view is in hierarchy
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            guard let window = composeVC.view.window else { return }
            tabBar.setup(in: window)
        }

        // Compose notifies us of tab changes
        bridge.onTabChanged = { index in
            DispatchQueue.main.async {
                tabBar.updateSelection(Int(index))
            }
        }

        // Compose sends user initial for profile tab
        bridge.onUserInitialChanged = { initial in
            DispatchQueue.main.async {
                tabBar.setUserInitial(initial)
            }
        }
    }

    // MARK: - Chat Sheet Background Animation

    private func setupChatSheetBridge(composeVC: UIViewController) {
        let bridge = ChatSheetBridge.shared
        let chatTransition = ChatSheetTransition.shared

        // Setup with the compose view after a tick (so view is in hierarchy)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
            chatTransition.setup(composeView: composeVC.view)
        }

        bridge.onOpenSheet = {
            DispatchQueue.main.async {
                // Present native SwiftUI ChatView as a sheet
                guard let windowScene = UIApplication.shared.connectedScenes
                    .compactMap({ $0 as? UIWindowScene }).first,
                    let rootVC = windowScene.windows.first(where: { $0.isKeyWindow })?.rootViewController else {
                    bridge.onOpenComplete?()
                    return
                }

                let chatView = ChatView(
                    onDismiss: {
                        rootVC.presentedViewController?.dismiss(animated: true) {
                            bridge.onCloseComplete?()
                            // Cancel the Kotlin state observer
                            ChatSheetTransition.shared.presentedChatVC = nil
                        }
                    }
                )
                let hostingVC = UIHostingController(rootView: chatView)
                hostingVC.modalPresentationStyle = .pageSheet
                if let sheet = hostingVC.sheetPresentationController {
                    sheet.detents = [.large()]
                    sheet.prefersGrabberVisible = true
                }

                // Store reference for close
                ChatSheetTransition.shared.presentedChatVC = hostingVC

                // Detect swipe-to-dismiss so we can cancel the Kotlin observer
                let dismissDelegate = ChatDismissDelegate {
                    bridge.onCloseComplete?()
                    ChatSheetTransition.shared.presentedChatVC = nil
                }
                hostingVC.presentationController?.delegate = dismissDelegate
                ChatSheetTransition.shared.dismissDelegate = dismissDelegate

                // Wire paywall: when Kotlin's PremiumGate blocks a plan,
                // present the native SwiftUI paywall over the chat sheet.
                // On successful purchase/restore, dismiss only the paywall
                // (not the chat), refresh Kotlin's PremiumGate from the
                // RevenueCat entitlement, then fire approvePlan so the save
                // resumes without the user tapping "Save" a second time.
                ChatBridge.shared.onPlanBlocked = {
                    DispatchQueue.main.async {
                        var paywallVC: UIViewController?
                        let paywallView = PaywallView(onDismiss: { didPurchase in
                            paywallVC?.dismiss(animated: true) {
                                guard didPurchase else { return }
                                // Sync Kotlin's gate state from RevenueCat before
                                // retrying, so canCreatePlan returns true. The
                                // refresh is async — give it a brief window to
                                // complete before firing the save.
                                ChatBridge.shared.refreshSubscription?()
                                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                                    ChatBridge.shared.approvePlan?()
                                }
                            }
                        })
                        let vc = UIHostingController(rootView: paywallView)
                        vc.modalPresentationStyle = .fullScreen
                        paywallVC = vc
                        hostingVC.present(vc, animated: true)
                    }
                }

                rootVC.present(hostingVC, animated: true) {
                    bridge.onOpenComplete?()
                }
            }
        }

        bridge.onCloseSheet = {
            DispatchQueue.main.async {
                if let vc = ChatSheetTransition.shared.presentedChatVC {
                    vc.dismiss(animated: true) {
                        bridge.onCloseComplete?()
                        ChatSheetTransition.shared.presentedChatVC = nil
                    }
                } else {
                    bridge.onCloseComplete?()
                }
            }
        }
    }

    // MARK: - Paywall Bridge (surfaces that need the paywall without chat)

    private func setupPaywallBridge() {
        ChatBridge.shared.presentPaywall = { onPurchased in
            DispatchQueue.main.async {
                guard let windowScene = UIApplication.shared.connectedScenes
                    .compactMap({ $0 as? UIWindowScene }).first,
                    let rootVC = windowScene.windows.first(where: { $0.isKeyWindow })?.rootViewController else {
                    onPurchased(KotlinBoolean(value: false))
                    return
                }
                // Present on the topmost VC so this works even if another
                // sheet (e.g. Location Settings) is already up.
                var presenter: UIViewController = rootVC
                while let next = presenter.presentedViewController {
                    presenter = next
                }
                var paywallVC: UIViewController?
                let paywallView = PaywallView(onDismiss: { didPurchase in
                    paywallVC?.dismiss(animated: true) {
                        onPurchased(KotlinBoolean(value: didPurchase))
                    }
                })
                let vc = UIHostingController(rootView: paywallView)
                vc.modalPresentationStyle = .fullScreen
                paywallVC = vc
                presenter.present(vc, animated: true)
            }
        }
    }

    // MARK: - Plan Chat Sheet Bridge (per-plan group chat)

    private func setupPlanChatSheetBridge(composeVC: UIViewController) {
        let bridge = PlanChatSheetBridge.shared
        let chatBridge = PlanChatBridge.shared

        bridge.onOpenSheet = {
            DispatchQueue.main.async {
                guard let windowScene = UIApplication.shared.connectedScenes
                    .compactMap({ $0 as? UIWindowScene }).first,
                    let rootVC = windowScene.windows.first(where: { $0.isKeyWindow })?.rootViewController else {
                    bridge.onOpenComplete?()
                    return
                }

                // Find topmost presented VC (plan detail may be presented above root)
                var presenter: UIViewController = rootVC
                while let next = presenter.presentedViewController { presenter = next }

                let planChat = PlanChatView(
                    planTitle: chatBridge.planTitle,
                    prePopulated: chatBridge.prePopulatedMessage,
                    onDismiss: {
                        presenter.presentedViewController?.dismiss(animated: true) {
                            chatBridge.onClose?()
                            PlanChatSheetTransition.shared.presentedVC = nil
                        }
                    }
                )
                let hostingVC = UIHostingController(rootView: planChat)
                hostingVC.modalPresentationStyle = .pageSheet
                if let sheet = hostingVC.sheetPresentationController {
                    sheet.detents = [.large()]
                    sheet.prefersGrabberVisible = true
                }

                PlanChatSheetTransition.shared.presentedVC = hostingVC

                let dismissDelegate = PlanChatDismissDelegate {
                    chatBridge.onClose?()
                    PlanChatSheetTransition.shared.presentedVC = nil
                }
                hostingVC.presentationController?.delegate = dismissDelegate
                PlanChatSheetTransition.shared.dismissDelegate = dismissDelegate

                presenter.present(hostingVC, animated: true) {
                    bridge.onOpenComplete?()
                }
            }
        }

        bridge.onCloseSheet = {
            DispatchQueue.main.async {
                if let vc = PlanChatSheetTransition.shared.presentedVC {
                    vc.dismiss(animated: true) {
                        bridge.onCloseComplete?()
                        PlanChatSheetTransition.shared.presentedVC = nil
                    }
                } else {
                    bridge.onCloseComplete?()
                }
            }
        }
    }

    // MARK: - Plan Detail Transition Bridge

    private func setupPlanDetailBridge(composeVC: UIViewController) {
        let bridge = PlanDetailBridge.shared
        let transition = PlanDetailTransitionController.shared

        bridge.showPlanDetail = { planJson, cardX, cardY, cardW, cardH in
            DispatchQueue.main.async {
                guard let windowScene = UIApplication.shared.connectedScenes
                    .compactMap({ $0 as? UIWindowScene }).first,
                      let window = windowScene.windows.first(where: { $0.isKeyWindow })
                else { return }

                let frame = CGRect(
                    x: CGFloat(truncating: cardX as NSNumber),
                    y: CGFloat(truncating: cardY as NSNumber),
                    width: CGFloat(truncating: cardW as NSNumber),
                    height: CGFloat(truncating: cardH as NSNumber)
                )

                transition.present(
                    planJson: planJson as String,
                    cardFrame: frame,
                    in: window,
                    onDismissed: {
                        bridge.onDismissed?()
                    },
                    onShare: {
                        bridge.onShare?()
                    }
                )
            }
        }

        bridge.dismissPlanDetail = {
            DispatchQueue.main.async {
                transition.dismiss()
            }
        }
    }

    // MARK: - Push (OneSignal) Bridge

    private func setupPushBridge() {
        let bridge = PushBridge.shared

        // Associate / clear the OneSignal user (external id = Supabase uid), so
        // the server-side evaluator can target pushes by user.
        bridge.login = { uid in OneSignal.login(uid) }
        bridge.logout = { OneSignal.logout() }

        // Prompt for notification permission THROUGH OneSignal so a grant also
        // registers for remote notifications (mints the APNs/OneSignal token).
        bridge.requestPermission = { onResult in
            OneSignal.Notifications.requestPermission({ accepted in
                DispatchQueue.main.async { onResult(KotlinBoolean(value: accepted)) }
            }, fallbackToSettings: true)
        }

        // Notification tap → deep link, same path routing as universal links.
        OneSignal.Notifications.addClickListener(PushClickListener.shared)
    }

    // MARK: - RevenueCat Subscription Bridge

    private func setupRevenueCatBridge() {
        let bridge = RevenueCatBridge.shared

        // Product IDs — update these after creating in App Store Connect
        let monthlyProductId = "local_pro_monthly"
        let annualProductId = "local_pro_annual"

        bridge.purchase = { isAnnual, onResult in
            Task {
                do {
                    let offerings = try await Purchases.shared.offerings()
                    guard let offering = offerings.current else {
                        print("RevenueCat: No current offering")
                        DispatchQueue.main.async { onResult(KotlinBoolean(value: false)) }
                        return
                    }

                    // Find the right package
                    let annual = isAnnual.boolValue
                    let package: Package?
                    if annual {
                        package = offering.annual ?? offering.availablePackages.first(where: { $0.storeProduct.productIdentifier == annualProductId })
                    } else {
                        package = offering.monthly ?? offering.availablePackages.first(where: { $0.storeProduct.productIdentifier == monthlyProductId })
                    }

                    guard let pkg = package else {
                        print("RevenueCat: Package not found (isAnnual=\(annual))")
                        DispatchQueue.main.async { onResult(KotlinBoolean(value: false)) }
                        return
                    }

                    let (_, customerInfo, _) = try await Purchases.shared.purchase(package: pkg)
                    let isPro = customerInfo.entitlements["Local Pro"]?.isActive == true
                    print("RevenueCat: Purchase complete, pro=\(isPro)")
                    DispatchQueue.main.async { onResult(KotlinBoolean(value: isPro)) }
                } catch {
                    print("RevenueCat: Purchase error: \(error.localizedDescription)")
                    DispatchQueue.main.async { onResult(KotlinBoolean(value: false)) }
                }
            }
        }

        bridge.restorePurchases = { onResult in
            Task {
                do {
                    let customerInfo = try await Purchases.shared.restorePurchases()
                    let isPro = customerInfo.entitlements["Local Pro"]?.isActive == true
                    print("RevenueCat: Restore complete, pro=\(isPro)")
                    DispatchQueue.main.async { onResult(KotlinBoolean(value: isPro)) }
                } catch {
                    print("RevenueCat: Restore error: \(error.localizedDescription)")
                    DispatchQueue.main.async { onResult(KotlinBoolean(value: false)) }
                }
            }
        }

        bridge.checkSubscriptionStatus = { onResult in
            Task {
                do {
                    let customerInfo = try await Purchases.shared.customerInfo()
                    let isPro = customerInfo.entitlements["Local Pro"]?.isActive == true
                    let expiry = customerInfo.entitlements["Local Pro"]?.expirationDate?.ISO8601Format()
                    print("RevenueCat: Status check, pro=\(isPro), expiry=\(expiry ?? "none")")
                    DispatchQueue.main.async { onResult(KotlinBoolean(value: isPro), expiry) }
                } catch {
                    print("RevenueCat: Status check error: \(error.localizedDescription)")
                    DispatchQueue.main.async { onResult(KotlinBoolean(value: false), nil) }
                }
            }
        }
        bridge.logIn = { appUserId, onResult in
            Task {
                do {
                    let (customerInfo, _) = try await Purchases.shared.logIn(appUserId)
                    let isPro = customerInfo.entitlements["Local Pro"]?.isActive == true
                    let expiry = customerInfo.entitlements["Local Pro"]?.expirationDate?.ISO8601Format()
                    print("RevenueCat: logIn complete user=\(appUserId), pro=\(isPro)")
                    DispatchQueue.main.async { onResult(KotlinBoolean(value: isPro), expiry) }
                } catch {
                    print("RevenueCat: logIn error: \(error.localizedDescription)")
                    DispatchQueue.main.async { onResult(KotlinBoolean(value: false), nil) }
                }
            }
        }

        bridge.logOut = {
            Task {
                do {
                    _ = try await Purchases.shared.logOut()
                    print("RevenueCat: logOut complete")
                } catch {
                    print("RevenueCat: logOut error: \(error.localizedDescription)")
                }
            }
        }

        bridge.fetchPricing = { onResult in
            Task {
                do {
                    let offerings = try await Purchases.shared.offerings()
                    guard let offering = offerings.current else {
                        DispatchQueue.main.async { onResult(nil, nil, nil) }
                        return
                    }
                    let monthlyPkg = offering.monthly ?? offering.availablePackages.first(where: { $0.storeProduct.productIdentifier == "local_pro_monthly" })
                    let annualPkg = offering.annual ?? offering.availablePackages.first(where: { $0.storeProduct.productIdentifier == "local_pro_annual" })

                    let monthlyPrice = monthlyPkg?.storeProduct.localizedPriceString
                    let annualPrice = annualPkg?.storeProduct.localizedPriceString

                    // Check intro eligibility
                    var introPrice: String? = nil
                    if let monthly = monthlyPkg {
                        let eligible = await Purchases.shared.checkTrialOrIntroDiscountEligibility(product: monthly.storeProduct)
                        if eligible == .eligible, let intro = monthly.storeProduct.introductoryDiscount {
                            introPrice = intro.localizedPriceString
                        }
                    }

                    DispatchQueue.main.async { onResult(monthlyPrice, annualPrice, introPrice) }
                } catch {
                    print("RevenueCat: fetchPricing error: \(error.localizedDescription)")
                    DispatchQueue.main.async { onResult(nil, nil, nil) }
                }
            }
        }
    }

    // MARK: - Share Sheet Bridge

    private func setupShareBridge(composeVC: UIViewController) {
        let bridge = ShareBridge.shared

        bridge.presentShareSheet = { text, url in
            DispatchQueue.main.async {
                guard let windowScene = UIApplication.shared.connectedScenes
                    .compactMap({ $0 as? UIWindowScene }).first,
                      let rootVC = windowScene.windows.first(where: { $0.isKeyWindow })?.rootViewController
                else { return }

                // Find the topmost presented controller
                var topVC = rootVC
                while let presented = topVC.presentedViewController {
                    topVC = presented
                }

                var items: [Any] = [text as Any]
                if let shareUrl = URL(string: url as String) {
                    items.append(shareUrl)
                }
                // Attach app icon for richer share previews
                if let appIcon = UIImage(named: "AppIcon") ?? UIImage(named: "app-icon-1024") {
                    items.append(appIcon)
                }

                let activityVC = UIActivityViewController(activityItems: items, applicationActivities: nil)

                // iPad popover anchor
                if let popover = activityVC.popoverPresentationController {
                    popover.sourceView = topVC.view
                    popover.sourceRect = CGRect(x: topVC.view.bounds.midX, y: topVC.view.bounds.midY, width: 0, height: 0)
                }

                topVC.present(activityVC, animated: true)
            }
        }
    }
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}

/// Routes a OneSignal notification tap to the shared deep-link handler, using the
/// same `/p/ /e/ /v/` path scheme as universal links. The push payload carries the
/// path in `additionalData["path"]` (set server-side by the push evaluator).
final class PushClickListener: NSObject, OSNotificationClickListener {
    static let shared = PushClickListener()

    func onClick(event: OSNotificationClickEvent) {
        guard let path = event.notification.additionalData?["path"] as? String else { return }
        if path.hasPrefix("/p/") || path.hasPrefix("/e/") || path.hasPrefix("/v/") {
            DeepLinkBridge.shared.receivePath(path: path)
        }
    }
}
