import UIKit

/// Holds the presented plan-chat hosting VC so the close callback can reach it.
class PlanChatSheetTransition {
    static let shared = PlanChatSheetTransition()
    var presentedVC: UIViewController?
    var dismissDelegate: PlanChatDismissDelegate?
}

/// Detects swipe-to-dismiss so the Kotlin observer can be cancelled and state cleaned up.
class PlanChatDismissDelegate: NSObject, UIAdaptivePresentationControllerDelegate {
    let onDismiss: () -> Void

    init(onDismiss: @escaping () -> Void) {
        self.onDismiss = onDismiss
    }

    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        onDismiss()
    }
}
