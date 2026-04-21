import SwiftUI
import ComposeApp
import RevenueCat

@main
struct iOSApp: App {
    init() {
        InitKoinKt.doInitKoin()

        // Oversize URLCache so venue hero images viewed once (as thumbnails or in detail)
        // are still warm when re-opening the detail page — removes the blank-frame jank on
        // dismiss when the original download hadn't finished.
        URLCache.shared = URLCache(memoryCapacity: 64 * 1024 * 1024, diskCapacity: 512 * 1024 * 1024, diskPath: "venue_hero_cache")

        // Configure RevenueCat
        Purchases.logLevel = .debug
        Purchases.configure(withAPIKey: "appl_EYwaWlbtrUMLciPvdLpNOymStJV")
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

        // /p/{token} — shared plan. Uses receiveToken so the token is buffered
        // if Compose hasn't registered its callback yet (cold-start race).
        if path.hasPrefix("/p/") {
            let token = String(path.dropFirst(3))
            if !token.isEmpty {
                DeepLinkBridge.shared.receiveToken(token: token)
            }
        }
        // /e/{id} and /v/{id} are handled via clipboard check in AppNavigation
    }
}
