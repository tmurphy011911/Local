package live.localapp.local.platform

import androidx.compose.runtime.Composable

interface NativeTimePickerController {
    fun show(
        initialHour: Int,
        initialMinute: Int,
        onPicked: (hour: Int, minute: Int) -> Unit,
        onCancelled: () -> Unit,
    )
}

@Composable
expect fun rememberNativeTimePicker(): NativeTimePickerController
