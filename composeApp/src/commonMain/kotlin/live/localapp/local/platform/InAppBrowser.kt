package live.localapp.local.platform

import androidx.compose.runtime.Composable

/**
 * Opens a web URL in an IN-APP browser so the user never leaves Local and
 * returns the moment they close the site:
 *   • iOS     → SFSafariViewController (modal, shares Safari login/cookies)
 *   • Android → Chrome Custom Tabs (shares the browser session)
 *
 * Use this for event/place WEB-LINK CTAs only — "Visit website", "Get tickets",
 * "Make reservation", "More info", source links. Do NOT route maps/directions
 * or rideshare (Uber/Lyft) through here: those are app-to-app deep links that
 * should open the native Maps/Uber/Lyft apps, where a webview is strictly worse.
 */
fun interface InAppBrowser {
    fun open(url: String)
}

@Composable
expect fun rememberInAppBrowser(): InAppBrowser
