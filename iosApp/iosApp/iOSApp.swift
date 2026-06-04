import SwiftUI
import ComposeApp
import RevenueCat

@main
struct iOSApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Sentry/PostHog first so anything thrown by later lines is captured.
        AnalyticsBridge.installAndStart()

        // Route Kotlin/Native uncaught exceptions through Sentry (with the
        // Kotlin throwable type/message) before the process dies. Must come
        // after the bridge so recordError/flush have somewhere to go.
        IosCrashHandlerKt.installIosCrashHandler()

        InitKoinKt.doInitKoin()

        // Push super-properties + first lifecycle event through the bridge.
        let bundle = Bundle.main
        let appVersion = bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown"
        let buildNumber = bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "unknown"
        #if DEBUG
        let environment = "dev"
        #else
        let environment = "prod"
        #endif
        Analytics.shared.initialize(
            environment: environment,
            appVersion: appVersion,
            buildNumber: buildNumber,
            platform: "ios"
        )
        Analytics.shared.track(event: AnalyticsEvent.Events.shared.appOpened(coldStart: true, secondsBackgrounded: nil))

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
        .onChange(of: scenePhase) { phase in
            // iOS PostHog runs on default batching and we never wire an
            // app-lifecycle flush, so interaction events queued late in a
            // short session are lost when the app is killed from background.
            // Flush on every background transition so the queue ships.
            if phase == .background {
                Analytics.shared.track(event: AnalyticsEvent.Events.shared.appBackgrounded())
                Analytics.shared.flush()
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
