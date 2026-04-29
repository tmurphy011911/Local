package live.localapp.local.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberNativeDateRangePicker(): NativeDateRangePickerController {
    return remember {
        object : NativeDateRangePickerController {
            override fun show(
                initialStartMillis: Long?,
                initialEndMillis: Long?,
                onPicked: (Long, Long) -> Unit,
                onCancelled: () -> Unit,
            ) {
                val present = NativeDateRangePickerBridge.show
                if (present == null) {
                    onCancelled()
                    return
                }
                present(initialStartMillis, initialEndMillis, onPicked, onCancelled)
            }
        }
    }
}

object NativeDateRangePickerBridge {
    var show: ((
        initialStartMillis: Long?,
        initialEndMillis: Long?,
        onPicked: (Long, Long) -> Unit,
        onCancelled: () -> Unit,
    ) -> Unit)? = null
}
