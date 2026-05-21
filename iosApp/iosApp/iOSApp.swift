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
        // Buffer the path if Compose hasn't mounted its callback yet (cold-start race).
        if path.hasPrefix("/p/") || path.hasPrefix("/e/") || path.hasPrefix("/v/") {
            DeepLinkBridge.shared.receivePath(path: path)
        }
    }
}
