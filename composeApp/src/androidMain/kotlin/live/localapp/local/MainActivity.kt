package live.localapp.local

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.onesignal.OneSignal
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import live.localapp.local.di.initKoin
import live.localapp.local.observability.Analytics
import live.localapp.local.observability.AnalyticsEvent

class MainActivity : ComponentActivity() {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Install the SplashScreen API *before* super.onCreate. The library
        // shows the @style/Theme.App.Starting frame (cream + app icon) and
        // hands off to the Compose splash. See docs/splash-spec-v1.1.md §10.
        //
        // Hold the system splash on screen until composeReady flips true,
        // which MainActivity.composeReady flips below as soon as setContent
        // returns. After that, the Compose splash takes over the animation.
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !composeReady }

        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            isAppearanceLightStatusBars = true   // dark icons on light bg
            isAppearanceLightNavigationBars = true
        }
        window.navigationBarColor = android.graphics.Color.parseColor("#FAF8FC")
        super.onCreate(savedInstanceState)

        // Init observability before Koin so any startup error gets captured.
        Analytics.bind(this)
        val info = packageManager.getPackageInfo(packageName, 0)
        Analytics.initialize(
            environment = if (BuildConfig.DEBUG) "dev" else "prod",
            appVersion = info.versionName ?: "unknown",
            buildNumber = info.longVersionCode.toString(),
            platform = "android",
        )
        Analytics.track(AnalyticsEvent.appOpened(coldStart = savedInstanceState == null))

        initKoin()
        ActivityProvider.activity = this
        handleDeepLink(intent)

        // Configure RevenueCat
        if (!Purchases.isConfigured) {
            Purchases.logLevel = LogLevel.DEBUG
            Purchases.configure(
                PurchasesConfiguration.Builder(this, "goog_ktssSARuahhCjlJaIGVLUQGEtYi")
                    .build()
            )
        }

        // Configure OneSignal (push). initWithContext registers the device with
        // OneSignal; a push token is only obtained once the OS notification
        // permission is granted, which we request from the onboarding
        // "Turn on notifications" screen (not at launch). A notification tap
        // carries a `path` in additionalData that routes through the existing
        // deep-link handler, same as a universal link.
        if (BuildConfig.DEBUG) OneSignal.Debug.logLevel = com.onesignal.debug.LogLevel.VERBOSE
        OneSignal.initWithContext(this, Config.ONESIGNAL_APP_ID)
        OneSignal.Notifications.addClickListener(object : com.onesignal.notifications.INotificationClickListener {
            override fun onClick(event: com.onesignal.notifications.INotificationClickEvent) {
                val path = event.notification.additionalData?.optString("path").orEmpty()
                if (path.isNotBlank()) DeepLinkHandler.pendingPath = path
            }
        })

        setContent {
            // Expose Compose testTags as Android resource-ids so Maestro (and any
            // other UIAutomator-based tooling) can target tagged elements by id.
            // Debug-only — release builds never opt in, keeping the tree clean.
            if (BuildConfig.DEBUG) {
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier.semantics {
                        testTagsAsResourceId = true
                    },
                ) {
                    App()
                }
            } else {
                App()
            }
        }
        // setContent is synchronous about *enqueueing* the composition, so
        // flipping this immediately is safe — the first Compose frame will
        // paint the cream Splash background, blending cleanly out of the
        // identical-colored system splash.
        composeReady = true
    }

    @Volatile
    private var composeReady: Boolean = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        val path = uri.path ?: return
        if (uri.host == "localapp.live" && path.matches(Regex("/[pev]/[A-Za-z0-9_-]+"))) {
            DeepLinkHandler.pendingPath = path
        }
    }

    override fun onStop() {
        super.onStop()
        // Parity with iOS scenePhase .background (iOSApp.swift): emit the
        // session-end signal and drain PostHog's queue before the process can
        // be killed in the background, so late interaction events (event_saved,
        // etc.) aren't lost. Single-activity Compose app, so onStop ≈ app
        // backgrounded. Analytics.flush() is designed for shutdown (Sentry
        // capped at 2s), so calling it here is safe.
        Analytics.track(AnalyticsEvent.appBackgrounded())
        Analytics.flush()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ActivityProvider.activity === this) ActivityProvider.activity = null
    }
}

/** Provides Activity reference for platform APIs that need it (e.g. Play In-App Review). */
object ActivityProvider {
    var activity: ComponentActivity? = null
}

/** Stores pending deep link path for pickup by Compose. */
object DeepLinkHandler {
    var callback: ((String) -> Unit)? = null
        set(value) {
            field = value
            // Cold start: callback is assigned after the activity stored a
            // pendingPath in onCreate -> deliver it now.
            val path = pendingPath
            if (value != null && path != null) {
                pendingPath = null
                value(path)
            }
        }

    // Warm start: the activity is already composed (callback assigned) when a
    // new deep-link intent arrives via onNewIntent. A plain field would just
    // sit here unread, silently dropping the link. Deliver straight to the live
    // callback if one is registered; otherwise hold it for the callback setter.
    var pendingPath: String? = null
        set(value) {
            val cb = callback
            if (value != null && cb != null) {
                field = null
                cb(value)
            } else {
                field = value
            }
        }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}