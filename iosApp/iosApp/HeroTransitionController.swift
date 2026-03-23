import UIKit

/// UIKit-based overlay that performs an Airbnb-style card-to-detail image morph.
///
/// Usage:
///   1. Call `expand(imageUrl:from:in:)` to animate a card image to full-width header.
///   2. Call `collapse()` to reverse the animation back to the original card frame.
///
/// Completion callbacks (`onExpandComplete` / `onCollapseComplete`) notify the
/// Compose layer so it can show/hide the detail screen in sync.
class HeroTransitionController {
    static let shared = HeroTransitionController()

    // MARK: - Callbacks
    var onExpandComplete: (() -> Void)?
    var onCollapseComplete: (() -> Void)?

    // MARK: - Private state
    private var dimView: UIView?
    private var imageView: UIImageView?
    private var originalFrame: CGRect = .zero
    private var targetFrame: CGRect = .zero
    private var snapshotView: UIView?  // optional card snapshot for extra polish

    // MARK: - Expand

    func expand(imageUrl: String?, from frame: CGRect, in window: UIWindow) {
        // Clean up any leftover views from a previous transition
        cleanup()

        originalFrame = frame
        let screenWidth = window.bounds.width
        let safeTop = window.safeAreaInsets.top
        targetFrame = CGRect(x: 0, y: safeTop, width: screenWidth, height: 280)

        // --- Dim layer ---
        let dim = UIView(frame: window.bounds)
        dim.backgroundColor = .black
        dim.alpha = 0
        dim.isUserInteractionEnabled = false
        window.addSubview(dim)
        dimView = dim

        // --- Image view ---
        let iv = UIImageView(frame: frame)
        iv.contentMode = .scaleAspectFill
        iv.clipsToBounds = true
        iv.layer.cornerRadius = 18
        // Light placeholder while loading
        iv.backgroundColor = UIColor(red: 0.95, green: 0.93, blue: 0.97, alpha: 1)
        window.addSubview(iv)
        imageView = iv

        // --- Load image async ---
        if let urlStr = imageUrl, let url = URL(string: urlStr) {
            let task = URLSession.shared.dataTask(with: url) { [weak self] data, _, _ in
                guard let data = data, let image = UIImage(data: data) else { return }
                DispatchQueue.main.async {
                    // Only set if the imageView still exists (transition not cancelled)
                    self?.imageView?.image = image
                }
            }
            task.resume()
        }

        // --- Spring animation ---
        UIView.animate(
            withDuration: 0.42,
            delay: 0,
            usingSpringWithDamping: 0.82,
            initialSpringVelocity: 0.5,
            options: [.curveEaseInOut, .allowUserInteraction],
            animations: {
                iv.frame = self.targetFrame
                iv.layer.cornerRadius = 0
                dim.alpha = 0.4
            },
            completion: { _ in
                // Remove overlay so Compose's back button is accessible
                self.imageView?.removeFromSuperview()
                self.dimView?.removeFromSuperview()
                self.imageView = nil
                self.dimView = nil
                self.onExpandComplete?()
            }
        )
    }

    // MARK: - Collapse

    func collapse() {
        // Overlay was removed after expand — just fire completion
        // The Compose side handles hiding the detail screen
        cleanup()
        onCollapseComplete?()
    }

    // MARK: - Helpers

    private func cleanup() {
        imageView?.removeFromSuperview()
        dimView?.removeFromSuperview()
        snapshotView?.removeFromSuperview()
        imageView = nil
        dimView = nil
        snapshotView = nil
    }
}
