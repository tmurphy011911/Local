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

        // `isShared` rides on the JSON payload as a top-level flag (set by the
        // Kotlin shared-invite paths). Parsed out-of-band here rather than added
        // to PlanDetailPlan so the model's memberwise initializers are untouched.
        let isShared: Bool = {
            guard let data = planJson.data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            else { return false }
            return (obj["isShared"] as? Bool) ?? false
        }()

        let view = PlanDetailView(
            initialPlan: plan,
            isCreator: isCreator,
            currentUserId: plan.currentUserId,
            isShared: isShared,
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

        presentWhenReady(hostVC: hostVC, in: window, attempt: 0)
    }

    /// Present only once the topmost view controller is NOT mid-transition.
    ///
    /// The plan-success "View Plan" CTA dismisses the chat sheet (animated) and
    /// then asks to open the saved plan. If we present while that dismissal is
    /// still in flight, UIKit drops the present and strands an orphaned
    /// transition/dimming layer over the window — the app looks "completely
    /// frozen" (touches swallowed, no crash). Waiting for the transition to
    /// settle instead of guessing a fixed delay removes the race entirely.
    private func presentWhenReady(hostVC: UIViewController, in window: UIWindow, attempt: Int) {
        guard let topVC = topViewController(in: window) else {
            onDismissed?()
            return
        }
        let isTransitioning = topVC.isBeingDismissed
            || topVC.isBeingPresented
            || topVC.transitionCoordinator != nil
        // ~1s ceiling (20 * 50ms) so a stuck transition can never wait forever.
        if isTransitioning && attempt < 20 {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
                self?.presentWhenReady(hostVC: hostVC, in: window, attempt: attempt + 1)
            }
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
