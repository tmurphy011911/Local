package live.localapp.local.ui.leaderboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import live.localapp.local.domain.repository.ChangeHomeCityResult
import live.localapp.local.platform.PlaceDetails
import live.localapp.local.platform.PlaceSuggestion
import live.localapp.local.platform.rememberPlacesAutocomplete
import live.localapp.local.ui.common.pressable
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

/**
 * Full-screen-ish dialog to change the home city: Google Places autocomplete →
 * pick a city → confirm (with the 1x/year warning) → change_home_city RPC. The
 * server enforces the rate limit and returns the next-allowed date.
 */
@Composable
fun HomeCityEditSheet(
    viewModel: LeaderboardViewModel,
    onDismiss: () -> Unit,
) {
    val places = rememberPlacesAutocomplete()
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }
    var selected by remember { mutableStateOf<Pair<PlaceSuggestion, PlaceDetails>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(input) {
        if (input.trim().length < 2) { suggestions = emptyList(); return@LaunchedEffect }
        delay(250)
        suggestions = runCatching { places.query(input.trim(), null, null) }.getOrDefault(emptyList())
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(AppColors.Snow)
                .imePadding(),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    "Enter new home address",
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = AppColors.Ink),
                )
                Spacer(Modifier.height(14.dp))

                val pick = selected
                if (pick == null) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search an address") },
                    )
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(Modifier.fillMaxWidth().height(260.dp)) {
                        items(suggestions, key = { it.id }) { s ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .pressable(onClick = {
                                        scope.launch {
                                            val d = runCatching { places.resolve(s) }.getOrNull()
                                            if (d?.lat != null && d.lng != null) selected = s to d
                                            else message = "Couldn't resolve that place — try another."
                                        }
                                    }, scale = 0.98f)
                                    .padding(horizontal = 12.dp, vertical = 11.dp),
                            ) {
                                Column {
                                    Text(s.primaryText, style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AppColors.Ink))
                                    if (s.secondaryText.isNotBlank()) {
                                        Text(s.secondaryText, style = TextStyle(fontFamily = AppFonts.Body, fontSize = 12.sp, color = AppColors.Ink3))
                                    }
                                }
                            }
                        }
                    }
                    message?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = TextStyle(fontFamily = AppFonts.Body, fontSize = 12.sp, color = AppColors.Coral))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Cancel", color = AppColors.Ink3) }
                    }
                } else {
                    val (s, d) = pick
                    Text(
                        "Change your home address to ${d.name}?",
                        style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppColors.Ink),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "You won't be able to change it again for a year.",
                        style = TextStyle(fontFamily = AppFonts.Body, fontSize = 13.sp, color = AppColors.Ink3),
                    )
                    message?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AppColors.Coral))
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = !saving, onClick = { selected = null; message = null }) { Text("Back", color = AppColors.Ink3) }
                        Spacer(Modifier.width(6.dp))
                        Button(
                            enabled = !saving,
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Sage),
                            onClick = {
                                saving = true; message = null
                                viewModel.changeHomeCity(d.name, d.lat!!, d.lng!!, s.id) { res ->
                                    saving = false
                                    when (res) {
                                        is ChangeHomeCityResult.Success -> onDismiss()
                                        is ChangeHomeCityResult.RateLimited ->
                                            message = "You can't change your home city until ${res.nextAllowedAt ?: "later"}."
                                        ChangeHomeCityResult.Error -> message = "Couldn't change — try again."
                                    }
                                }
                            },
                        ) {
                            if (saving) CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), color = AppColors.White, strokeWidth = 2.dp)
                            else Text("Confirm", color = AppColors.White, style = TextStyle(fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }
        }
    }
}
