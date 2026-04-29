package live.localapp.local.platform

import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun rememberNativeDateRangePicker(): NativeDateRangePickerController {
    var pendingRequest by remember { mutableStateOf<PendingRequest?>(null) }

    val controller = remember {
        object : NativeDateRangePickerController {
            override fun show(
                initialStartMillis: Long?,
                initialEndMillis: Long?,
                onPicked: (Long, Long) -> Unit,
                onCancelled: () -> Unit,
            ) {
                pendingRequest = PendingRequest(initialStartMillis, initialEndMillis, onPicked, onCancelled)
            }
        }
    }

    val request = pendingRequest
    if (request != null) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = request.initialStart,
            initialSelectedEndDateMillis = request.initialEnd,
        )
        DatePickerDialog(
            onDismissRequest = {
                request.onCancelled()
                pendingRequest = null
            },
            confirmButton = {
                Button(
                    enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null,
                    onClick = {
                        val s = state.selectedStartDateMillis
                        val e = state.selectedEndDateMillis
                        if (s != null && e != null) {
                            request.onPicked(s, e)
                            pendingRequest = null
                        }
                    },
                ) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = {
                    request.onCancelled()
                    pendingRequest = null
                }) { Text("Cancel") }
            },
        ) {
            DateRangePicker(state = state, showModeToggle = false, title = null, headline = null)
        }
    }

    return controller
}

private data class PendingRequest(
    val initialStart: Long?,
    val initialEnd: Long?,
    val onPicked: (Long, Long) -> Unit,
    val onCancelled: () -> Unit,
)
