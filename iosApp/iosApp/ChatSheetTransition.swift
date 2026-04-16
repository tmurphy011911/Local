import UIKit

/// Handles the Airbnb-style background scale animation when the chat sheet opens/closes.
/// Scales the Compose view to 92% with rounded corners, creating the layered depth effect.
class ChatSheetTransition {
    static let shared = ChatSheetTransition()

    private weak var composeView: UIView?
    var presentedChatVC: UIViewController?
    var dismissDelegate: ChatDismissDelegate?

    func setup(composeView: UIView) {
        self.composeView = composeView
    }

    func open(completion: @escaping () -> Void) {
        guard let view = composeView else {
            completion()
            return
        }

        UIView.animate(
            withDuration: 0.35,
            delay: 0,
            usingSpringWithDamping: 0.9,
            initialSpringVelocity: 0.5,
            options: [.curveEaseInOut, .allowUserInteraction],
            animations: {
                view.transform = CGAffineTransform(scaleX: 0.92, y: 0.92)
                view.layer.cornerRadius = 16
                view.layer.masksToBounds = true
            },
            completion: { _ in
                completion()
            }
        )
    }

    func close(completion: @escaping () -> Void) {
        guard let view = composeView else {
            completion()
            return
        }

        UIView.animate(
            withDuration: 0.3,
            delay: 0,
            usingSpringWithDamping: 0.9,
            initialSpringVelocity: 0.3,
            options: [.curveEaseInOut],
            animations: {
                view.transform = .identity
                view.layer.cornerRadius = 0
            },
            completion: { _ in
                completion()
            }
        )
    }
}

/// Detects when the chat sheet is dismissed by swipe-down gesture.
class ChatDismissDelegate: NSObject, UIAdaptivePresentationControllerDelegate {
    let onDismiss: () -> Void

    init(onDismiss: @escaping () -> Void) {
        self.onDismiss = onDismiss
    }

    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        onDismiss()
    }
}
