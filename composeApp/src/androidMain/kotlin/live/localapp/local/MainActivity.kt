package live.localapp.local

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import live.localapp.local.di.initKoin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
    }

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