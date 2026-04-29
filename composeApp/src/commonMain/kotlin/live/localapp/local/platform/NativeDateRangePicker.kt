package live.localapp.local.platform

import androidx.compose.runtime.Composable

interface NativeDateRangePickerController {
    /**
     * Present the platform-native date-range picker. [onPicked] receives epoch
     * millis (UTC midnight of each end of the range, inclusive). [onCancelled]
     * fires if the user dismisses without confirming.
     */
    fun show(
        initialStartMillis: Long?,
        initialEndMillis: Long?,
        onPicked: (startMillis: Long, endMillis: Long) -> Unit,
        onCancelled: () -> Unit,
    )
}

@Composable
expect fun rememberNativeDateRangePicker(): NativeDateRangePickerController
