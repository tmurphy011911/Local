package live.localapp.local.platform

import androidx.compose.runtime.Composable

/**
 * System/predictive back handler for in-app Compose detail screens.
 *
 * Android delegates to the stable `androidx.activity.compose.BackHandler` so a
 * back press / edge swipe (either edge) dismisses the screen. iOS is a no-op:
 * the native detail transition controllers handle the left-edge swipe-back
 * (see iosApp EdgeSwipeBackGesture). Wrapping it here keeps the call sites in
 * commonMain while avoiding the experimental common
 * `androidx.compose.ui.backhandler.BackHandler`.
 */
@Composable
expect fun AppBackHandler(enabled: Boolean, onBack: () -> Unit)
