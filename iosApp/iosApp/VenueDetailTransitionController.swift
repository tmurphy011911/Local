import UIKit
import SwiftUI

/// Manages an Airbnb-style expand/collapse transition for the native VenueDetailView.
class VenueDetailTransitionController {
    static let shared = VenueDetailTransitionController()

    /// When true, the venue detail view hides the Get Directions link and the
    /// Upcoming Events section. Set to true by the chat-card slot tap path
    /// (`ChatView` → `ChatBridge.onVenueTap`) so a venue opened from inside
    /// a Bo-built plan reads as "this is part of your plan", not as a
    /// generic venue lookup. Consumed (read + cleared) by every presentation via
    /// `consumeFromPlanContext()` so it applies to exactly one open — a reset
    /// only on dismiss leaked: the zero-frame pageSheet path never ran
    /// `dismiss()`, so one plan-slot open hid events on every later See All /
    /// Bo venue open until relaunch.
    static var fromPlanContext: Bool = false

    static func consumeFromPlanContext() -> Bool {
        let value = fromPlanContext
        fromPlanContext = false
        return value
    }

    // MARK: - State
    private var hostingController: UIHostingController<VenueDetailView>?
    private var dimView: UIView?
    private var originalFrame: CGRect = .zero
    private var cachedHeroImage: UIImage?
    private var onDismissed: (() -> Void)?

    // MARK: - Present

    func present(
        venue: VenueDetailData,
        cardFrame: CGRect,
        in window: UIWindow,
        isFavorited: Bool = false,
        onFavorite: ((String) -> Void)? = nil,
        onDismissed: @escaping () -> Void,
        hidesPrimaryCTAs: Bool = false
    ) {
        cleanup()

        self.originalFrame = cardFrame
        self.onDismissed = onDismissed
        self.cachedHeroImage = nil

        // Pre-warm hero image cache so the dismiss path is instant.
        if let urlStr = venue.photoUrl, let url = URL(string: urlStr) {
            let request = URLRequest(url: url)
            if let cached = URLCache.shared.cachedResponse(for: request),
               let img = UIImage(data: cached.data) {
                self.cachedHeroImage = img
            } else {
                URLSession.shared.dataTask(with: request) { [weak self] data, response, _ in
                    guard let data = data, let img = UIImage(data: data) else { return }
                    if let response = response {
                        URLCache.shared.storeCachedResponse(CachedURLResponse(response: response, data: data), for: request)
                    }
                    DispatchQueue.main.async { self?.cachedHeroImage = img }
                }.resume()
            }
        }

        // Dim overlay
        let dim = UIView(frame: window.bounds)
        dim.backgroundColor = .black
        dim.alpha = 0
        dim.isUserInteractionEnabled = true
        window.addSubview(dim)
        self.dimView = dim

        let detailView = VenueDetailView(
            venue: venue,
            onDismiss: { [weak self] in self?.dismiss() },
            isFavorited: isFavorited,
            onFavorite: onFavorite,
            hidesPrimaryCTAs: hidesPrimaryCTAs,
            fromPlanContext: Self.consumeFromPlanContext()
        )
        let hosting = UIHostingController(rootView: detailView)
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
        // Reset the from-plan flag so the next non-plan venue open shows
        // Get Directions + Upcoming Events normally.
        Self.fromPlanContext = false

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
        heroIV.backgroundColor = UIColor(red: 0.93, green: 0.96, blue: 0.94, alpha: 1)
        fakeCard.addSubview(heroIV)

        window.addSubview(fakeCard)

        // Hide hosting view — fakeCard covers it
        hosting.view.isHidden = true

        // Animate fakeCard shrinking to card; image shrinks to card's image area.
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

    private func dismissWithCardMorph_legacy(hosting: UIViewController, window: UIView) {
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

    private func dismissWithSlideDown(hosting: UIViewController, window: UIView) {
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
