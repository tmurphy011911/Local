package live.localapp.local.platform

import androidx.compose.runtime.Composable

@Composable
actual fun AppBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No-op: iOS detail screens are native-hosted and handle the left-edge
    // swipe-back via EdgeSwipeBackGesture, not the Compose back dispatcher.
}
