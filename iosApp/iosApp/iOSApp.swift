import SwiftUI
import ComposeApp
import RevenueCat

@main
struct iOSApp: App {
    init() {
        InitKoinKt.doInitKoin()

        // Configure RevenueCat
        Purchases.logLevel = .debug
        Purchases.configure(withAPIKey: "test_iCUWRkTHkXlscJAkAdUwjEXZEij")
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

        // Copy URL to clipboard for Compose deep link check on next launch/resume
        UIPasteboard.general.string = url.absoluteString

        // /p/{token} — shared plan
        if path.hasPrefix("/p/") {
            let token = String(path.dropFirst(3))
            if !token.isEmpty {
                DeepLinkBridge.shared.onPlanShareTokenReceived?(token)
            }
        }
        // /e/{id} and /v/{id} are handled via clipboard check in AppNavigation
    }
}
