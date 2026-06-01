package live.localapp.local

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import live.localapp.local.di.initKoin
import live.localapp.local.observability.Analytics
import live.localapp.local.observability.AnalyticsEvent

class MainActivity : ComponentActivity() {
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

        setContent {
            App()
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
    var pendingPath: String? = null
    var callback: ((String) -> Unit)? = null
        set(value) {
            field = value
            // Deliver any pending path immediately
            val path = pendingPath
            if (value != null && path != null) {
                pendingPath = null
                value(path)
            }
        }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}