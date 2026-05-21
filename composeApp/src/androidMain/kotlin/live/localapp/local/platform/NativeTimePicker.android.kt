package live.localapp.local.platform

import android.app.TimePickerDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberNativeTimePicker(): NativeTimePickerController {
    val context = LocalContext.current
    return remember {
        object : NativeTimePickerController {
            override fun show(
                initialHour: Int,
                initialMinute: Int,
                onPicked: (Int, Int) -> Unit,
                onCancelled: () -> Unit,
            ) {
                val dialog = TimePickerDialog(
                    context,
                    { _, h, m -> onPicked(h, m) },
                    initialHour,
                    initialMinute,
                    false,
                )
                dialog.setTitle("Start Time")
                dialog.setOnCancelListener { onCancelled() }
                dialog.show()
            }
        }
    }
}
