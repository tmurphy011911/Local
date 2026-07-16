import SafariServices
import UIKit
import ComposeApp

/// Wires Kotlin's `InAppBrowserBridge.open` to an in-app SFSafariViewController
/// so event/place web links open inside Local and return on close. Registered
/// once at app start from ContentView.setupNativeBridges.
enum InAppBrowserBridgeSetup {
    static func register() {
        InAppBrowserBridge.shared.open = { url in
            DispatchQueue.main.async { open(url as String) }
        }
    }

    /// Opens a web URL in an in-app SFSafariViewController. Reusable from native
    /// SwiftUI views (EventDetailView / VenueDetailView) so their CTAs stay
    /// in-app instead of bouncing out via UIApplication.open.
    static func open(_ urlString: String) {
        DispatchQueue.main.async { present(urlString: urlString) }
    }

    private static func present(urlString: String) {
        guard let url = normalizedWebURL(urlString) else {
            // Non-web scheme (tel:, mailto:, app deep links) — SFSafariViewController
            // only accepts http/https and traps otherwise, so hand off to the system.
            if let u = URL(string: urlString) { UIApplication.shared.open(u) }
            return
        }
        let vc = SFSafariViewController(url: url)
        // Coral tint to match the app's CTA color (same as PlanDetailView's SafariView).
        vc.preferredControlTintColor = UIColor(red: 0.961, green: 0.42, blue: 0.388, alpha: 1.0)
        // Card sheet (not full-screen) so the screen underneath peeks at the top
        // and dismissing returns straight to it. Presented from the (attached)
        // top view controller so SFSafariViewController's remote Safari service
        // connects and the page actually loads — a detached presenter loads blank.
        vc.modalPresentationStyle = .pageSheet
        if let sheet = vc.sheetPresentationController {
            sheet.prefersGrabberVisible = true
        }
        topViewController()?.present(vc, animated: true)
    }

    /// Only http/https are valid for SFSafariViewController. Adds an https://
    /// scheme to bare hosts ("example.com") that some venue rows carry.
    private static func normalizedWebURL(_ s: String) -> URL? {
        guard !s.isEmpty else { return nil }
        if let u = URL(string: s), let scheme = u.scheme?.lowercased(),
           scheme == "http" || scheme == "https" {
            return u
        }
        if URL(string: s)?.scheme == nil {
            return URL(string: "https://\(s)")
        }
        return nil
    }

    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap { $0.windows }.first { $0.isKeyWindow }
            ?? scenes.first?.windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
