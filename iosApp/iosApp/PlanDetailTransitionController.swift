import UIKit
import SwiftUI
import ComposeApp

/// Manages an Airbnb-style expand/collapse transition for the plan detail screen.
/// Hosts the Compose PlanDetailScreen inside a UIKit transition container.
class PlanDetailTransitionController {
    static let shared = PlanDetailTransitionController()

    // MARK: - State
    private weak var hostingController: UIViewController?
    private var onDismissed: (() -> Void)?
    private var onShare: (() -> Void)?
    private var sheetDismissDelegate: PlanDetailSheetDelegate?

    // MARK: - Present

    func present(planJson: String, cardFrame: CGRect, in window: UIWindow, onDismissed: @escaping () -> Void, onShare: @escaping () -> Void) {
        self.onDismissed = onDismissed
        self.onShare = onShare

        // Decode plan JSON; bail with a no-op presentation if it's malformed.
        guard let plan = PlanDetailPlan.decode(from: planJson) else {
            print("PlanDetailTransitionController: failed to decode planJson")
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
        hostVC.modalPresentationStyle = .pageSheet
        if let sheet = hostVC.sheetPresentationController {
            sheet.detents = [.large()]
            sheet.prefersGrabberVisible = true
            sheet.preferredCornerRadius = 20
        }

        // Detect swipe-to-dismiss so we still fire onDismissed.
        let delegate = PlanDetailSheetDelegate { [weak self] in
            self?.handleDidDismiss()
        }
        hostVC.presentationController?.delegate = delegate
        self.sheetDismissDelegate = delegate
        self.hostingController = hostVC

        guard let topVC = topViewController(in: window) else {
            onDismissed()
            return
        }
        topVC.present(hostVC, animated: true)
    }

    // MARK: - Dismiss

    func dismiss() {
        guard let vc = hostingController else {
            onDismissed?()
            return
        }
        vc.dismiss(animated: true) { [weak self] in
            self?.handleDidDismiss()
        }
    }

    // MARK: - Helpers

    private func handleDidDismiss() {
        let cb = onDismissed
        onDismissed = nil
        onShare = nil
        hostingController = nil
        sheetDismissDelegate = nil
        cb?()
    }

    private func topViewController(in window: UIWindow) -> UIViewController? {
        var top = window.rootViewController
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
    }
}

private class PlanDetailSheetDelegate: NSObject, UIAdaptivePresentationControllerDelegate {
    let onDismiss: () -> Void
    init(onDismiss: @escaping () -> Void) { self.onDismiss = onDismiss }
    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        onDismiss()
    }
}
