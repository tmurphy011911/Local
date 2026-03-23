import UIKit
import SwiftUI
import ComposeApp

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
                    source: source
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
            cardX, cardY, cardW, cardH in

            DispatchQueue.main.async {
                guard let window = composeVC.view.window ?? UIApplication.shared.connectedScenes
                    .compactMap({ $0 as? UIWindowScene })
                    .flatMap({ $0.windows })
                    .first(where: { $0.isKeyWindow })
                else { return }

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
                    upcomingEventCount: Int(upcomingEventCount)
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
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}
