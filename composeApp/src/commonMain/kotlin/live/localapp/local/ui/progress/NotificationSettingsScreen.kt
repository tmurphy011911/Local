package live.localapp.local.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.common.RoundedCornerShape
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    // The toggle mirrors the OS-level notification permission — it is the only
    // place that state can actually be changed, so flipping it routes the user
    // to the system settings (or the first-run permission prompt on Android).
    var enabled by remember { mutableStateOf(false) }

    // Re-read OS permission on every resume — the primary flow sends the user out
    // to the system settings to flip it, so the toggle must refresh on return.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                live.localapp.local.platform.areNotificationsEnabled { enabled = it }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun onToggle() {
        if (!enabled) {
            // Turning on: ask the OS. If it can't grant (already denied, or iOS
            // post-first-prompt), send the user to the app's settings page.
            live.localapp.local.platform.requestNotificationPermission { granted ->
                if (granted) enabled = true else live.localapp.local.platform.openAppSettings()
            }
        } else {
            // Turning off can only happen in the OS settings.
            live.localapp.local.platform.openAppSettings()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Snow)
            .padding(top = 56.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.ArrowBack, "Back",
                modifier = Modifier.size(24.dp).clickable { onBack() },
                tint = AppColors.Ink,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Notifications",
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 20.sp, color = AppColors.Ink),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .background(Color.White, RoundedCornerShape(16.dp))
                .border(1.dp, AppColors.Pebble, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = ::onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Allow Notifications", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink))
                    Text("Reminders, weekly picks, and updates from your favorite venues", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3, lineHeight = 16.sp))
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 26.dp)
                        .background(if (enabled) AppColors.Coral else AppColors.Pebble, RoundedCornerShape(13.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .padding(3.dp)
                            .size(20.dp)
                            .align(if (enabled) Alignment.CenterEnd else Alignment.CenterStart)
                            .background(Color.White, RoundedCornerShape(10.dp)),
                    )
                }
            }
        }
    }
}
