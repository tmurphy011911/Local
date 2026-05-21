package live.localapp.local.platform

import android.provider.Settings
import live.localapp.local.ActivityProvider

actual fun isReducedMotionEnabled(): Boolean {
    val activity = ActivityProvider.activity ?: return false
    return try {
        val scale = Settings.Global.getFloat(
            activity.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f,
        )
        scale == 0.0f
    } catch (t: Throwable) {
        false
    }
}
