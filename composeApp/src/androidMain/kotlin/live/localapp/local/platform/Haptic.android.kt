package live.localapp.local.platform

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import live.localapp.local.ActivityProvider

private fun rootView(): View? = ActivityProvider.activity?.window?.decorView

private fun performIfAllowed(constant: Int) {
    if (isReduceMotionEnabled()) return
    rootView()?.performHapticFeedback(constant)
}

actual fun hapticSelection() {
    performIfAllowed(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.GESTURE_END
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        },
    )
}

actual fun hapticImpact() {
    performIfAllowed(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        },
    )
}

/** Threshold-cross cue — CONFIRM reads as a firm "you can let go now". */
actual fun hapticImpactMedium() {
    performIfAllowed(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        },
    )
}

/** Release cue — a light KEYBOARD_TAP, matching the spec's ~10ms tick. */
actual fun hapticImpactLight() {
    performIfAllowed(HapticFeedbackConstants.KEYBOARD_TAP)
}

/** Refresh succeeded — CONFIRM double-tick feel where available. */
actual fun hapticNotifySuccess() {
    performIfAllowed(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        },
    )
}

/** Refresh failed — REJECT is the negative/error pattern. */
actual fun hapticNotifyError() {
    performIfAllowed(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        },
    )
}

/**
 * Android has no single "reduce motion" flag; the standard proxy for the
 * accessibility "Remove animations" toggle is an animator duration scale of 0.
 * Defaults to false (motion allowed) if we can't read settings.
 */
actual fun isReduceMotionEnabled(): Boolean {
    val ctx = ActivityProvider.activity ?: return false
    return try {
        Settings.Global.getFloat(
            ctx.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    } catch (_: Throwable) {
        false
    }
}
