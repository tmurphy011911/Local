import UIKit
import SwiftUI

/// Manages an Airbnb-style expand/collapse transition for the native VenueDetailView.
class VenueDetailTransitionController {
    static let shared = VenueDetailTransitionController()

    // MARK: - State
    private var hostingController: UIHostingController<AnyView>?
    private var dimView: UIView?
    private var originalFrame: CGRect = .zero
    private var cachedHeroImage: UIImage?
    private var onDismissed: (() -> Void)?
    private var hasValidCardFrame: Bool = false

    // MARK: - Present

    func present(venue: VenueDetailData, cardFrame: CGRect, in window: UIWindow, onDismissed: @escaping () -> Void) {
        cleanup()

        self.originalFrame = cardFrame
        self.onDismissed = onDismissed
        self.cachedHeroImage = nil

        // Determine if we have a real card frame for the morph animation
        self.hasValidCardFrame = cardFrame.width > 10 && cardFrame.height > 10

        // Pre-download the hero image for the dismiss animation
        if let urlStr = venue.photoUrl, let url = URL(string: urlStr) {
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
        let detailView = VenueDetailView(venue: venue) { [weak self] in
            self?.dismiss()
        }
        let hosting = UIHostingController(rootView: AnyView(
            detailView
                .ignoresSafeArea(.all)
        ))
        hosting.view.backgroundColor = .white

        if hasValidCardFrame {
            // Card-morph animation
            hosting.view.frame = window.bounds
            hosting.view.transform = cardTransform(cardFrame: cardFrame, windowBounds: window.bounds)
            hosting.view.layer.cornerRadius = 18
            hosting.view.layer.masksToBounds = true
            hosting.view.alpha = 0

            window.addSubview(hosting.view)
            self.hostingController = hosting

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
        } else {
            // Slide-up animation (no card bounds)
            hosting.view.frame = window.bounds
            hosting.view.transform = CGAffineTransform(translationX: 0, y: window.bounds.height)
            hosting.view.layer.cornerRadius = 18
            hosting.view.layer.masksToBounds = true
            hosting.view.alpha = 1

            window.addSubview(hosting.view)
            self.hostingController = hosting

            UIView.animate(
                withDuration: 0.45,
                delay: 0,
                usingSpringWithDamping: 0.88,
                initialSpringVelocity: 0.4,
                options: [.curveEaseInOut, .allowUserInteraction],
                animations: {
                    hosting.view.transform = .identity
                    hosting.view.layer.cornerRadius = 0
                    dim.alpha = 0.5
                },
                completion: nil
            )
        }
    }

    // MARK: - Dismiss

    func dismiss() {
        guard let hosting = hostingController,
              let window = hosting.view.superview else {
            onDismissed?()
            return
        }

        // Always use slide-down dismiss (card morph causes content stretching)
        dismissWithSlideDown(hosting: hosting, window: window)
    }

    private func dismissWithCardMorph(hosting: UIHostingController<AnyView>, window: UIView) {
        let screenBounds = window.bounds
        let heroRect = CGRect(x: 0, y: 0, width: screenBounds.width, height: 200)
        let cardImageHeight: CGFloat = 80

        // Build a fake card: cached hero image + white body
        let fakeCard = UIView(frame: screenBounds)
        fakeCard.backgroundColor = .white
        fakeCard.layer.cornerRadius = 0
        fakeCard.clipsToBounds = true

        let heroIV = UIImageView(frame: heroRect)
        heroIV.contentMode = .scaleAspectFill
        heroIV.clipsToBounds = true
        heroIV.image = cachedHeroImage
        heroIV.backgroundColor = .black
        fakeCard.addSubview(heroIV)

        window.addSubview(fakeCard)

        // Hide the hosting view immediately
        hosting.view.isHidden = true

        // Animate fakeCard shrinking to card position
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
            withDuration: 0.12,
            delay: 0.22,
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

    private func dismissWithSlideDown(hosting: UIHostingController<AnyView>, window: UIView) {
        UIView.animate(
            withDuration: 0.35,
            delay: 0,
            usingSpringWithDamping: 0.9,
            initialSpringVelocity: 0.3,
            options: [.curveEaseInOut],
            animations: { [self] in
                hosting.view.transform = CGAffineTransform(translationX: 0, y: window.bounds.height)
                hosting.view.layer.cornerRadius = 18
                dimView?.alpha = 0
            },
            completion: { [self] _ in
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
