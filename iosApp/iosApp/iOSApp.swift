import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    init() {
        InitKoinKt.doInitKoin()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    handleDeepLink(url)
                }
        }
    }

    private func handleDeepLink(_ url: URL) {
        guard url.host() == "localapp.live" || url.host == "localapp.live" else { return }
        let path = url.path

        // /p/{token} — shared plan
        if path.hasPrefix("/p/") {
            let token = String(path.dropFirst(3))
            if !token.isEmpty {
                // Copy the URL to clipboard so the Compose deep link check picks it up
                UIPasteboard.general.string = url.absoluteString
                // Also notify via bridge for immediate handling
                DeepLinkBridge.shared.onPlanShareTokenReceived?(token)
            }
        }
    }
}
