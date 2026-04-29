package live.localapp.local.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * iOS impl. The Swift side populates [NativeTimePickerBridge.show] at app
 * startup; this composable returns a controller that delegates into it. If the
 * Swift bridge hasn't registered yet (shouldn't happen in practice), [onCancelled]
 * fires immediately so the caller doesn't hang.
 */
@Composable
actual fun rememberNativeTimePicker(): NativeTimePickerController {
    return remember {
        object : NativeTimePickerController {
            override fun show(
                initialHour: Int,
                initialMinute: Int,
                onPicked: (Int, Int) -> Unit,
                onCancelled: () -> Unit,
            ) {
                val present = NativeTimePickerBridge.show
                if (present == null) {
                    onCancelled()
                    return
                }
                present(initialHour, initialMinute, onPicked, onCancelled)
            }
        }
    }
}

object NativeTimePickerBridge {
    var show: ((
        initialHour: Int,
        initialMinute: Int,
        onPicked: (Int, Int) -> Unit,
        onCancelled: () -> Unit,
    ) -> Unit)? = null
}
