import UIKit
import ObjectiveC

/// Adds a left-edge swipe-back gesture to a view that invokes `onBack`.
///
/// The native detail screens (Event / Venue / Plan) are hosted outside a
/// UINavigationController — a single ComposeUIViewController root with custom
/// transition controllers / a page sheet — so iOS's built-in interactive-pop
/// gesture doesn't exist. This restores the expected "swipe from the left edge
/// to go back" behavior by recognizing a screen-edge pan and running each
/// host's existing dismiss animation.
///
/// Fires once when the edge pan begins (non-interactive): the screen-edge
/// recognizer only starts within ~20pt of the left edge and after horizontal
/// movement, so it reads as an intentional back-swipe without hijacking the
/// detail screen's own scrolling/gestures.
final class EdgeSwipeBackGesture: NSObject {
    private let onBack: () -> Void
    private init(onBack: @escaping () -> Void) { self.onBack = onBack }

    private static var associationKey: UInt8 = 0

    /// Installs the gesture on `view`. The handler's lifetime is tied to the
    /// view via an associated object, so it's released when the view goes away.
    static func install(on view: UIView, onBack: @escaping () -> Void) {
        let handler = EdgeSwipeBackGesture(onBack: onBack)
        let recognizer = UIScreenEdgePanGestureRecognizer(target: handler, action: #selector(handle(_:)))
        recognizer.edges = .left
        view.addGestureRecognizer(recognizer)
        objc_setAssociatedObject(view, &associationKey, handler, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
    }

    @objc private func handle(_ recognizer: UIScreenEdgePanGestureRecognizer) {
        if recognizer.state == .began {
            onBack()
        }
    }
}
