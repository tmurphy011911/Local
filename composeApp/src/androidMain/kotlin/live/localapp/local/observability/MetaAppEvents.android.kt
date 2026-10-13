package live.localapp.local.observability

import android.app.Application
import android.content.Context
import android.os.Bundle
import com.facebook.FacebookSdk
import com.facebook.appevents.AppEventsConstants
import com.facebook.appevents.AppEventsLogger

/**
 * Android Meta app-events impl. Call [bind] once from MainActivity with the
 * Application BEFORE [activateApp] — the SDK needs an Application (not just a
 * Context) to register the activity-lifecycle callbacks that drive session
 * tracking.
 *
 * `com.facebook.sdk.AutoInitEnabled` is false in the manifest, so nothing here
 * runs until [activateApp] is called.
 */
actual object MetaAppEvents {

    private var app: Application? = null
    private var logger: AppEventsLogger? = null

    @Volatile
    private var enabled = false

    actual val isEnabled: Boolean get() = enabled

    /** Hand the SDK the Application. Called from MainActivity. */
    fun bind(context: Context) {
        app = context.applicationContext as? Application
    }

    actual fun activateApp() {
        if (enabled) return

        // Both keys must be present. A blank client token makes the SDK throw
        // on the first event ("A valid Facebook client token must be set"), so
        // we stay off entirely rather than crash a build that hasn't had the
        // token pasted into local.properties yet.
        if (BuildKeys.META_APP_ID.isBlank() || BuildKeys.META_CLIENT_TOKEN.isBlank()) return

        val application = app ?: error("MetaAppEvents.bind(context) must be called before activateApp()")

        try {
            FacebookSdk.setApplicationId(BuildKeys.META_APP_ID)
            FacebookSdk.setClientToken(BuildKeys.META_CLIENT_TOKEN)
            @Suppress("DEPRECATION") // sdkInitialize(Context) is the documented manual-init entry point
            FacebookSdk.sdkInitialize(application)

            // Logs `fb_mobile_activate_app` and installs the lifecycle callbacks
            // that log subsequent foregrounds for the rest of the process.
            AppEventsLogger.activateApp(application)
            logger = AppEventsLogger.newLogger(application)
            enabled = true
        } catch (t: Throwable) {
            // Never let an ad-SDK failure take down app start. Report and move on.
            Analytics.recordError(t, "Meta SDK init failed")
        }
    }

    actual fun completeRegistration(method: String) {
        val log = logger ?: return
        try {
            log.logEvent(
                AppEventsConstants.EVENT_NAME_COMPLETED_REGISTRATION,
                Bundle().apply {
                    putString(AppEventsConstants.EVENT_PARAM_REGISTRATION_METHOD, method)
                },
            )
        } catch (t: Throwable) {
            Analytics.recordError(t, "Meta completeRegistration failed")
        }
    }
}
