package live.localapp.local.platform

import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberInAppBrowser(): InAppBrowser {
    val context = LocalContext.current
    return remember(context) {
        InAppBrowser { url ->
            val uri = normalize(url) ?: return@InAppBrowser
            try {
                CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                    .launchUrl(context, uri)
            } catch (e: Throwable) {
                // No Custom Tabs provider / odd URL — fall back to a normal view
                // intent rather than swallowing the tap entirely.
                println("DEBUG: Custom Tabs failed, falling back to ACTION_VIEW: ${e.message}")
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }
        }
    }
}

/** Tolerate scheme-less URLs ("example.com") that some venue rows carry. */
private fun normalize(url: String): Uri? {
    if (url.isBlank()) return null
    val withScheme = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    return runCatching { Uri.parse(withScheme) }.getOrNull()
}
