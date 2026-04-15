import UIKit
import SwiftUI
import ComposeApp
import RevenueCat

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let vc = MainViewControllerKt.MainViewController()

        // Wire the Kotlin ↔ Swift hero transition bridge (image-only morph)
        setupHeroTransitionBridge(composeVC: vc)

        // Wire the Kotlin ↔ Swift native event detail bridge
        setupEventDetailBridge(composeVC: vc)

        // Wire the Kotlin ↔ Swift native venue detail bridge
        setupVenueDetailBridge(composeVC: vc)

        // Wire chat sheet background animation
        setupChatSheetBridge(composeVC: vc)

        // Wire share sheet bridge
        setupShareBridge(composeVC: vc)

        // Wire plan detail transition bridge
        setupPlanDetailBridge(composeVC: vc)

        // Wire RevenueCat subscription bridge
        setupRevenueCatBridge()

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

    private func setupEventDetailBridge(composeVC: UIViewController) {
        let detailBridge = EventDetailBridge.shared
        let transition = EventDetailTransitionController.shared

        detailBridge.showEventDetail = {
            id, title, description, venueDescription,
            imageUrl, startAt, venueName, venueAddress,
            venueCity, venueLat, venueLng,
            isFree, priceDisplay, ticketUrl, sourceUrl,
            category, subcategory, source,
            cardX, cardY, cardW, cardH, cardImageUrl in

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
                    imageUrl: imageUrl,
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
                    showDirections: detailBridge.showDirections
                )

                let cardFrame = CGRect(
                    x: CGFloat(cardX),
                    y: CGFloat(cardY),
                    width: CGFloat(cardW),
                    height: CGFloat(cardH)
                )

                transition.present(event: event, cardFrame: cardFrame, in: window) {
                    detailBridge.onDismissed?()
                }
            }
        }

        detailBridge.dismissEventDetail = {
            DispatchQueue.main.async {
                transition.dismiss()
            }
        }
    }

    // MARK: - Native Venue Detail Wiring

    private func setupVenueDetailBridge(composeVC: UIViewController) {
        let venueBridge = VenueDetailBridge.shared
        let transition = VenueDetailTransitionController.shared

        venueBridge.showVenueDetail = {
            name, address, city,
            lat, lng,
            photoUrl, description,
            category, googleRating,
            website, distanceMiles,
            upcomingEventCount,
            upcomingEventsJson,
            reservationUrl,
            cardX, cardY, cardW, cardH in

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

                transition.present(venue: venue, cardFrame: cardFrame, in: window) {
                    venueBridge.onDismissed?()
                }
            }
        }

        venueBridge.dismissVenueDetail = {
            DispatchQueue.main.async {
                transition.dismiss()
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
                // Make sure we have the view reference
                chatTransition.setup(composeView: composeVC.view)
                chatTransition.open {
                    bridge.onOpenComplete?()
                }
            }
        }

        bridge.onCloseSheet = {
            DispatchQueue.main.async {
                chatTransition.close {
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
