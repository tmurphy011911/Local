import Foundation
import UIKit
import ComposeApp

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

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Starting the SDK here is what makes `fb_mobile_activate_app` fire:
        // ApplicationDelegate observes didBecomeActive and logs it itself, on
        // this launch and every subsequent foreground. Do NOT also call
        // AppEvents.activateApp() — see MetaBridge.activateApp().
        MetaBridge.start(application, launchOptions)
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
