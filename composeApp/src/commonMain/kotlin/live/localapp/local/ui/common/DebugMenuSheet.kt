package live.localapp.local.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.Config
import live.localapp.local.ui.theme.AppColors

/**
 * Developer-only bottom sheet (opened by tapping the "Local" logo in a debug
 * build). Lets you point the debug build at the dev or prod Supabase backend.
 *
 * Release builds never construct this — [AppHeader] gates it behind
 * [live.localapp.local.platform.isDebugBuild], and [Config.setBackendEnv] is a
 * no-op in release anyway, so even if it somehow rendered it could not switch a
 * shipped binary off prod.
 *
 * Switching only takes full effect on the next launch: the SupabaseClient and
 * the repositories that captured [Config.SUPABASE_URL] are built once at startup.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DebugMenuSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Track the selection locally so the rows update immediately even though the
    // real effect lands next launch.
    var selected by remember { mutableStateOf(Config.backendEnv) }
    var changed by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Text("Debug menu", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = AppColors.Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "Backend (debug + staging builds — the real release app is always prod)",
                fontSize = 13.sp,
                color = AppColors.Pebble,
            )
            Spacer(Modifier.height(16.dp))

            BackendRow("Dev", "xbyqwekcanrzovytxmdv — staging", selected == Config.BackendEnv.DEV) {
                if (selected != Config.BackendEnv.DEV) {
                    Config.setBackendEnv(Config.BackendEnv.DEV); selected = Config.BackendEnv.DEV; changed = true
                }
            }
            Spacer(Modifier.height(10.dp))
            BackendRow("Prod", "blifubxslaucshmlphdc — LIVE data", selected == Config.BackendEnv.PROD) {
                if (selected != Config.BackendEnv.PROD) {
                    Config.setBackendEnv(Config.BackendEnv.PROD); selected = Config.BackendEnv.PROD; changed = true
                }
            }

            if (changed) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Relaunch the app to apply the backend change.",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.Coral,
                )
            }
        }
    }
}

@Composable
private fun BackendRow(title: String, subtitle: String, isSelected: Boolean, onClick: () -> Unit) {
    val border = if (isSelected) AppColors.Coral else AppColors.Mist
    val bg = if (isSelected) AppColors.CoralLt else AppColors.White
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(12.dp))
            .border(2.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = AppColors.Ink)
            Text(subtitle, fontSize = 12.sp, color = AppColors.Pebble)
        }
        if (isSelected) Text("✓", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = AppColors.Coral)
    }
}
