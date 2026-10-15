import UIKit
import SwiftUI
import ComposeApp

/// Fires a callback when a sheet is dismissed interactively (swipe-down). UIKit
/// does NOT call the presenter's `dismiss(completion:)` for an interactive
/// dismissal — only a programmatic dismiss does — so without this delegate a
/// swipe-away leaves the Kotlin side unaware the sheet closed. Used for the
/// no-source-card event sheet (deep links / chat), where that gap stranded a
/// deep-linked event on a blank surface (undismissable white screen).
final class SheetDismissDelegate: NSObject, UIAdaptivePresentationControllerDelegate {
    private let onDismiss: () -> Void
    init(onDismiss: @escaping () -> Void) { self.onDismiss = onDismiss }
    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        onDismiss()
    }
}

/// Manages an Airbnb-style expand/collapse transition for the native EventDetailView.
class EventDetailTransitionController {
    static let shared = EventDetailTransitionController()

    // MARK: - State
    private var hostingController: UIHostingController<AnyView>?
    private var dimView: UIView?
    private var originalFrame: CGRect = .zero
    private var cachedHeroImage: UIImage?
    private var onDismissed: (() -> Void)?
    /// Retains the delegate for the no-card bottom-sheet path (its
    /// presentationController holds it weakly). Replaced on each present.
    var sheetDismissDelegate: SheetDismissDelegate?

    // MARK: - Present

    func present(event: EventDetailData, cardFrame: CGRect, in window: UIWindow, onDismissed: @escaping () -> Void) {
        cleanup()

        self.originalFrame = cardFrame
        self.onDismissed = onDismissed
        // Seed with the card's image so the collapse animation has a hero even
        // if the full-size download below hasn't finished.
        self.cachedHeroImage = event.cardImage

        // Pre-download the hero image for the dismiss animation
        if let urlStr = event.imageUrl, let url = URL(string: urlStr) {
            URLSession.shared.dataTask(with: url) { [weak self] data, _, _ in
                if let data = data, let img = UIImage(data: data) {
                    DispatchQueue.main.async {
                        self?.cachedHeroImage = img
                    }
                }
            }.resume()
        }

        // Dim overlay
        let dim = UIView(frame: window.bounds)
        dim.backgroundColor = .black
        dim.alpha = 0
        dim.isUserInteractionEnabled = true
        window.addSubview(dim)
        self.dimView = dim

        // Create the SwiftUI detail view
        let detailView = EventDetailView(event: event, onDismiss: { [weak self] in
            self?.dismiss()
        }, onFavorite: { eventId in
            EventDetailBridge.shared.onFavorited?(eventId)
        }, onShare: { eventId in
            EventDetailBridge.shared.onShare?(eventId)
        })
        let hosting = UIHostingController(rootView: AnyView(
            detailView
                .ignoresSafeArea(.all)
        ))
        hosting.view.backgroundColor = .white
        hosting.view.frame = window.bounds
        hosting.view.transform = cardTransform(cardFrame: cardFrame, windowBounds: window.bounds)
        hosting.view.layer.cornerRadius = 18
        hosting.view.layer.masksToBounds = true
        hosting.view.alpha = 0

        window.addSubview(hosting.view)
        self.hostingController = hosting

        // Left-edge swipe-back (no UINavigationController hosts this view).
        EdgeSwipeBackGesture.install(on: hosting.view) { [weak self] in self?.dismiss() }

        // Fade in quickly, then spring to fullscreen
        UIView.animate(withDuration: 0.1) {
            hosting.view.alpha = 1
        }

        UIView.animate(
            withDuration: 0.45,
            delay: 0,
            usingSpringWithDamping: 0.85,
            initialSpringVelocity: 0.5,
            options: [.curveEaseInOut, .allowUserInteraction],
            animations: {
                hosting.view.transform = .identity
                hosting.view.layer.cornerRadius = 0
                dim.alpha = 0.5
            },
            completion: nil
        )
    }

    // MARK: - Dismiss

    func dismiss() {
        guard let hosting = hostingController,
              let window = hosting.view.superview else {
            onDismissed?()
            return
        }

        let screenBounds = window.bounds
        let heroRect = CGRect(x: 0, y: 0, width: screenBounds.width, height: 280)

        // Build fake card on window: cached hero image + white body
        let fakeCard = UIView(frame: screenBounds)
        fakeCard.backgroundColor = .white
        fakeCard.layer.cornerRadius = 0
        fakeCard.clipsToBounds = true

        let heroIV = UIImageView(frame: heroRect)
        heroIV.contentMode = .scaleAspectFill
        heroIV.clipsToBounds = true
        heroIV.image = cachedHeroImage
        // If no cached image, use a light background instead of black
        heroIV.backgroundColor = UIColor(red: 0.93, green: 0.96, blue: 0.94, alpha: 1)
        fakeCard.addSubview(heroIV)

        window.addSubview(fakeCard)

        // Hide hosting view — fakeCard covers it
        hosting.view.isHidden = true

        // Animate fakeCard shrinking to card, image shrinks to card's image area (top 92pt)
        let cardImageHeight: CGFloat = min(originalFrame.height * 0.43, 92)

        UIView.animate(
            withDuration: 0.38,
            delay: 0,
            usingSpringWithDamping: 0.82,
            initialSpringVelocity: 0.2,
            options: [.curveEaseInOut],
            animations: { [self] in
                fakeCard.frame = originalFrame
                fakeCard.layer.cornerRadius = 18
                heroIV.frame = CGRect(x: 0, y: 0, width: originalFrame.width, height: cardImageHeight)
                dimView?.alpha = 0
            },
            completion: nil
        )

        // Fade out before fully reaching card size
        UIView.animate(
            withDuration: 0.1,
            delay: 0.24,
            options: [.curveEaseIn],
            animations: {
                fakeCard.alpha = 0
            },
            completion: { [self] _ in
                fakeCard.removeFromSuperview()
                cleanup()
                onDismissed?()
            }
        )
    }

    // MARK: - Helpers

    private func cardTransform(cardFrame: CGRect, windowBounds: CGRect) -> CGAffineTransform {
        let scaleX = cardFrame.width / windowBounds.width
        let scaleY = cardFrame.height / windowBounds.height
        let translateX = cardFrame.midX - windowBounds.midX
        let translateY = cardFrame.midY - windowBounds.midY
        return CGAffineTransform(translationX: translateX, y: translateY)
            .scaledBy(x: scaleX, y: scaleY)
    }

    private func cleanup() {
        hostingController?.view.removeFromSuperview()
        hostingController = nil

        dimView?.removeFromSuperview()
        dimView = nil
    }
}
