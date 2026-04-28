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

        // Decode plan JSON; bail with a no-op transition if it's malformed.
        guard let plan = PlanDetailPlan.decode(from: planJson) else {
            print("PlanDetailTransitionController: failed to decode planJson")
            cleanup()
            onDismissed()
            return
        }
        let isCreator = (plan.creatorId != nil) && plan.creatorId == plan.currentUserId

        let view = PlanDetailView(
            initialPlan: plan,
            isCreator: isCreator,
            currentUserId: plan.currentUserId,
            onBack: { [weak self] in
                self?.dismiss()
            }
        )
        let hostVC = UIHostingController(rootView: view)
        hostVC.view.backgroundColor = .clear
        hostVC.view.frame = window.bounds
        hostVC.view.transform = cardTransform(cardFrame: cardFrame, windowBounds: window.bounds)
        hostVC.view.layer.cornerRadius = 20
        hostVC.view.layer.masksToBounds = true
        hostVC.view.alpha = 0

        window.addSubview(hostVC.view)
        self.hostingController = hostVC

        // Fade in quickly, then spring to fullscreen
        UIView.animate(withDuration: 0.1) {
            hostVC.view.alpha = 1
        }

        UIView.animate(
            withDuration: 0.45,
            delay: 0,
            usingSpringWithDamping: 0.85,
            initialSpringVelocity: 0.5,
            options: [.curveEaseInOut, .allowUserInteraction],
            animations: {
                hostVC.view.transform = .identity
                hostVC.view.layer.cornerRadius = 0
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

        // Simple slide-down + fade dismiss (avoids content stretching)
        UIView.animate(
            withDuration: 0.3,
            delay: 0,
            usingSpringWithDamping: 0.9,
            initialSpringVelocity: 0.3,
            options: [.curveEaseInOut],
            animations: { [self] in
                vc.view.transform = CGAffineTransform(translationX: 0, y: window.bounds.height * 0.4)
                vc.view.alpha = 0
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
