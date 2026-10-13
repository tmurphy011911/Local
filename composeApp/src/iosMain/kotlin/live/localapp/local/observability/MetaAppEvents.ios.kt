package live.localapp.local.observability

/**
 * iOS Meta app-events impl. Forwards into the closures held in
 * [IosMetaBridge], which Swift populates at app startup (MetaBridge.swift)
 * with the FBSDKCoreKit calls.
 *
 * If a closure isn't set — tests, or the FBSDK package not yet resolved — the
 * call is a silent no-op rather than a crash, same as [Analytics].
 */
actual object MetaAppEvents {

    actual fun activateApp() {
        IosMetaBridge.activateApp?.invoke()
    }

    actual fun completeRegistration(method: String) {
        IosMetaBridge.completeRegistration?.invoke(method)
    }

    actual val isEnabled: Boolean get() = IosMetaBridge.enabled
}

/**
 * Bridge between common Kotlin [MetaAppEvents] calls and FBSDKCoreKit.
 * Swift fills in the closures from `MetaBridge.swift` during app startup.
 *
 * Same pattern as [IosAnalyticsBridge].
 */
object IosMetaBridge {

    /** Swift sets. Kotlin calls to boot the SDK + log `fb_mobile_activate_app`. */
    var activateApp: (() -> Unit)? = null

    /** Swift sets. Kotlin calls with the registration method. */
    var completeRegistration: ((String) -> Unit)? = null

    /**
     * Swift sets this to true once FBSDK has actually started. A plain Bool
     * rather than a closure so there's no KotlinBoolean boxing on the Swift side.
     */
    var enabled: Boolean = false
}

/**
 * Public, Swift-readable accessor for the Meta keys, so `MetaBridge.swift` can
 * fall back to them if the Info.plist substitution is empty.
 *
 * The plist is the SDK's own source of truth (FBSDKCoreKit reads FacebookAppID /
 * FacebookClientToken itself at `ApplicationDelegate` time); these are exposed
 * only so Swift can decide whether the SDK is configured at all.
 */
@Suppress("unused") // referenced from Swift via the ComposeApp framework
object MetaConfig {
    val META_APP_ID: String get() = BuildKeys.META_APP_ID
    val META_CLIENT_TOKEN: String get() = BuildKeys.META_CLIENT_TOKEN
}
