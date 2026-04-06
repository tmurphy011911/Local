import UIKit
import SwiftUI
import ComposeApp

/// Manages an Airbnb-style expand/collapse transition for the plan detail screen.
/// Hosts the Compose PlanDetailScreen inside a UIKit transition container.
class PlanDetailTransitionController {
    static let shared = PlanDetailTransitionController()

    // MARK: - State
    private var hostingController: UIViewController?
    private var dimView: UIView?
    private var originalFrame: CGRect = .zero
    private var onDismissed: (() -> Void)?
    private var onShare: (() -> Void)?

    // MARK: - Present

    func present(planJson: String, cardFrame: CGRect, in window: UIWindow, onDismissed: @escaping () -> Void, onShare: @escaping () -> Void) {
        cleanup()

        self.originalFrame = cardFrame
        self.onDismissed = onDismissed
        self.onShare = onShare

        // Dim overlay
        let dim = UIView(frame: window.bounds)
        dim.backgroundColor = .black
        dim.alpha = 0
        dim.isUserInteractionEnabled = true
        window.addSubview(dim)
        self.dimView = dim

        // Create the Compose PlanDetailScreen via a helper MainViewController
        let composeVC = PlanDetailViewControllerKt.PlanDetailViewController(
            planJson: planJson,
            onBack: { [weak self] in
                self?.dismiss()
            },
            onShare: { [weak self] in
                self?.onShare?()
            }
        )
        composeVC.view.frame = window.bounds
        composeVC.view.transform = cardTransform(cardFrame: cardFrame, windowBounds: window.bounds)
        composeVC.view.layer.cornerRadius = 20
        composeVC.view.layer.masksToBounds = true
        composeVC.view.alpha = 0

        window.addSubview(composeVC.view)
        self.hostingController = composeVC

        // Fade in quickly, then spring to fullscreen
        UIView.animate(withDuration: 0.1) {
            composeVC.view.alpha = 1
        }

        UIView.animate(
            withDuration: 0.45,
            delay: 0,
            usingSpringWithDamping: 0.85,
            initialSpringVelocity: 0.5,
            options: [.curveEaseInOut, .allowUserInteraction],
            animations: {
                composeVC.view.transform = .identity
                composeVC.view.layer.cornerRadius = 0
                dim.alpha = 0.5
            },
            completion: nil
        )
    }

    // MARK: - Dismiss

    func dismiss() {
        guard let vc = hostingController,
              let window = vc.view.superview else {
            onDismissed?()
            return
        }

        UIView.animate(
            withDuration: 0.38,
            delay: 0,
            usingSpringWithDamping: 0.82,
            initialSpringVelocity: 0.2,
            options: [.curveEaseInOut],
            animations: { [self] in
                vc.view.transform = cardTransform(cardFrame: originalFrame, windowBounds: window.bounds)
                vc.view.layer.cornerRadius = 20
                dimView?.alpha = 0
            },
            completion: nil
        )

        UIView.animate(
            withDuration: 0.1,
            delay: 0.24,
            options: [.curveEaseIn],
            animations: {
                vc.view.alpha = 0
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
