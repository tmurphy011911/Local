import SwiftUI
import ComposeApp
import RevenueCat
import OneSignalFramework

@main
struct iOSApp: App {
    // SwiftUI has no AppDelegate of its own, but FBSDK's install attribution
    // needs the real didFinishLaunchingWithOptions / open-url callbacks.
    // AppDelegate (MetaBridge.swift) supplies them and starts the Meta SDK.
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Sentry/PostHog first so anything thrown by later lines is captured.
        AnalyticsBridge.installAndStart()

        // Route Kotlin/Native uncaught exceptions through Sentry (with the
        // Kotlin throwable type/message) before the process dies. Must come
        // after the bridge so recordError/flush have somewhere to go.
        IosCrashHandlerKt.installIosCrashHandler()

        InitKoinKt.doInitKoin()

        #if DEBUG
        // Preview a seasonal Bo skin without changing the clock:
        //   xcrun simctl launch <device> live.localapp.local -BoSeasonOverride thanksgiving
        // (halloween, thanksgiving, christmas, valentines, newyear, winter,
        // stpatricks, easter, july4, none). Launch args land in UserDefaults.
        if let season = UserDefaults.standard.string(forKey: "BoSeasonOverride") {
            BoSeason.shared.setDebugOverride(name: season)
        }
        #endif

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

        // Configure OneSignal (push). App ID is public — the single source of
        // truth is Config.ONESIGNAL_APP_ID (Kotlin); duplicated here as a literal
        // to match the RevenueCat pattern and avoid a cross-language read in init.
        // The device only gets a push token once notification permission is
        // granted, which we request from onboarding via the PushBridge
        // (wired in ContentView.setupPushBridge).
        OneSignal.initialize("bdbdc7ed-00db-4961-9ad7-67ec3a5cba9f", withLaunchOptions: nil)

        // Install the Meta (Facebook) bridge closures so shared Kotlin can log
        // fb_mobile_complete_registration. The SDK itself is started from
        // AppDelegate.didFinishLaunchingWithOptions, which UIKit calls *after*
        // this initializer — installing the closures here is safe because
        // nothing logs a Meta event before the user reaches signup.
        MetaBridge.install()
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
