import Foundation
import UIKit
import ComposeApp
import AppTrackingTransparency

#if canImport(FBSDKCoreKit)
import FBSDKCoreKit
#endif

/// Wires FBSDKCoreKit into the shared Kotlin `IosMetaBridge` so common code can
/// call `MetaAppEvents.completeRegistration(...)` without knowing it's on iOS.
///
/// SDK setup (already committed to `project.pbxproj`; re-add via the GUI only if
/// package resolution was reset):
///   File → Add Package Dependencies… → https://github.com/facebook/facebook-ios-sdk
///   pick the latest 18.x, add the `FacebookCore` product to the `iosApp` target.
///
/// Until the package resolves, the `canImport` guard below short-circuits every
/// call to a no-op, so the app still builds and runs — same contract as
/// `AnalyticsBridge`.
enum MetaBridge {

    private(set) static var isEnabled = false

    /// True only when the plist actually carries both values. `$(FACEBOOK_...)`
    /// substitution yields an EMPTY string when the xcconfig var is unset, so an
    /// isEmpty check is the real test — never a nil check.
    private static var isConfigured: Bool {
        let bundle = Bundle.main
        let appID = bundle.object(forInfoDictionaryKey: "FacebookAppID") as? String ?? ""
        let token = bundle.object(forInfoDictionaryKey: "FacebookClientToken") as? String ?? ""
        return !appID.isEmpty && !token.isEmpty
    }

    /// Install the Kotlin bridge closures. Call from `iOSApp.init()`.
    /// Does NOT start the SDK — `AppDelegate` does that, because FBSDK requires
    /// `didFinishLaunchingWithOptions` for correct install attribution.
    static func install() {
        let bridge = IosMetaBridge.shared
        bridge.activateApp = { activateApp() }
        bridge.completeRegistration = { method in completeRegistration(method) }
    }

    /// Boot the SDK. Called from `AppDelegate.application(_:didFinishLaunchingWithOptions:)`.
    static func start(_ application: UIApplication, _ launchOptions: [UIApplication.LaunchOptionsKey: Any]?) {
        #if canImport(FBSDKCoreKit)
        guard !isEnabled else { return }   // idempotent: two paths can reach this
        guard isConfigured else { return }
        ApplicationDelegate.shared.application(application, didFinishLaunchingWithOptions: launchOptions)
        isEnabled = true
        IosMetaBridge.shared.enabled = true
        #endif
    }

    /// `fb_mobile_activate_app` is logged by the SDK itself, NOT here.
    ///
    /// `ApplicationDelegate.applicationDidBecomeActive` calls
    /// `appEvents.activateApp()` on every `UIApplication.didBecomeActive`
    /// whenever `isAutoLogAppEventsEnabled` is true — which is the default, and
    /// which we leave on. Calling `AppEvents.shared.activateApp()` here as well
    /// would log the event TWICE per cold launch and inflate activations in
    /// Meta Ads reporting.
    ///
    /// So this exists only to satisfy the shared `MetaAppEvents.activateApp()`
    /// contract ("the SDK is up and the activate event is logged") — starting
    /// the SDK in `AppDelegate` is what actually fulfills it.
    ///
    /// Note this differs from Android, where `AppEventsLogger.activateApp(app)`
    /// IS the required explicit call.
    static func activateApp() {
        // Intentionally empty. See the doc comment above before adding a call here.
    }

    /// Show the ATT prompt, then start the SDK once the user has answered.
    ///
    /// Apple REJECTED v1.4.0 (502) under Guideline 2.1 because the prompt was
    /// chained to the onboarding notifications step — behind phone-OTP auth the
    /// reviewer could not get past, so they never saw it. Apple also requires
    /// the request to appear BEFORE any data that could track the user is
    /// collected, and FBSDK pings Meta the moment it starts. Both problems have
    /// the same fix: ask on first launch, and don't start FBSDK until answered.
    ///
    /// Must run while the app is `.active` — at `didFinishLaunchingWithOptions`
    /// it is still `.inactive` and the prompt silently fails to present, which
    /// is why AppDelegate calls this from `applicationDidBecomeActive`.
    ///
    /// iOS asks once per install; later calls return the stored answer without
    /// prompting, so the `then` block still runs and the SDK still starts.
    static func requestTrackingThenStart(_ application: UIApplication,
                                         _ launchOptions: [UIApplication.LaunchOptionsKey: Any]?) {

        func attempt(_ remaining: Int) {
            // Half a second past activation. Asking the instant the app becomes
            // active is a known case where iOS declines to present.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {

                // Already answered (or a retry won the race) — just start.
                guard ATTrackingManager.trackingAuthorizationStatus == .notDetermined else {
                    start(application, launchOptions)
                    return
                }

                // iOS logs "returning 0 due to backgrounded app" and silently
                // does NOT prompt if we're not foreground — e.g. a system alert
                // stole focus. It doesn't consume the one-shot, so retry later.
                guard application.applicationState == .active else {
                    if remaining > 0 { attempt(remaining - 1) } else { start(application, launchOptions) }
                    return
                }

                ATTrackingManager.requestTrackingAuthorization { status in
                    DispatchQueue.main.async {
                        if status == .notDetermined, remaining > 0 {
                            attempt(remaining - 1)   // prompt never presented; go again
                        } else {
                            start(application, launchOptions)
                        }
                    }
                }
            }
        }

        attempt(6)

        // Safety net: a missing ATT callback must never cost us the SDK
        // entirely. start() is idempotent, so whichever path lands first wins.
        DispatchQueue.main.asyncAfter(deadline: .now() + 20) {
            start(application, launchOptions)
        }
    }

    /// Logs `fb_mobile_complete_registration`.
    static func completeRegistration(_ method: String) {
        #if canImport(FBSDKCoreKit)
        guard isEnabled else { return }
        AppEvents.shared.logEvent(
            .completedRegistration,
            parameters: [.registrationMethod: method]
        )
        #endif
    }

    /// Let the SDK claim the `fb<APPID>` callback URL. Returns true if it handled it.
    static func handleOpen(_ url: URL, _ options: [UIApplication.OpenURLOptionsKey: Any]) -> Bool {
        #if canImport(FBSDKCoreKit)
        guard isEnabled else { return false }
        return ApplicationDelegate.shared.application(
            UIApplication.shared,
            open: url,
            sourceApplication: options[.sourceApplication] as? String,
            annotation: options[.annotation]
        )
        #else
        return false
        #endif
    }
}

/// SwiftUI `App` has no AppDelegate, but FBSDK's install attribution needs the
/// real `didFinishLaunchingWithOptions` / `open url` callbacks. This adaptor
/// supplies them. Attached via `@UIApplicationDelegateAdaptor` in `iOSApp`.
final class AppDelegate: NSObject, UIApplicationDelegate {

    private var launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    private var hasAskedForTracking = false

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Deliberately does NOT start FBSDK. Starting it here would ping Meta
        // before the ATT prompt could appear, which is what Apple rejected 502
        // for. We only stash the launch options; applicationDidBecomeActive
        // asks for tracking and starts the SDK afterwards.
        self.launchOptions = launchOptions

        // NOT applicationDidBecomeActive: SwiftUI apps are scene-based, so UIKit
        // delivers sceneDidBecomeActive to the scene delegate and never calls
        // the app delegate's version. This notification fires either way.
        var token: NSObjectProtocol?
        token = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self, !self.hasAskedForTracking else { return }
            self.hasAskedForTracking = true
            if let token { NotificationCenter.default.removeObserver(token) }
            MetaBridge.requestTrackingThenStart(application, self.launchOptions)
        }

        return true
    }

    func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        MetaBridge.handleOpen(url, options)
    }
}
