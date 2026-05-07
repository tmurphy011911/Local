package live.localapp.local.platform

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import live.localapp.local.ActivityProvider

private fun rootView(): View? = ActivityProvider.activity?.window?.decorView

actual fun hapticSelection() {
    val v = rootView() ?: return
    val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.GESTURE_END
    } else {
        HapticFeedbackConstants.VIRTUAL_KEY
    }
    v.performHapticFeedback(constant)
}

actual fun hapticImpact() {
    val v = rootView() ?: return
    val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.VIRTUAL_KEY
    }
    v.performHapticFeedback(constant)
}
