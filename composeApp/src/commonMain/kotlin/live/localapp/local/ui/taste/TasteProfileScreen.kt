package live.localapp.local.ui.taste

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

@Composable
fun TasteProfileScreen(
    viewModel: TasteProfileViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val profile = state.profile

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Snow)
            .padding(top = 56.dp),
    ) {
        // Header
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
            Column {
                Text(
                    "Your Taste Profile",
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 20.sp, color = AppColors.Ink),
                )
                if (profile != null && profile.interactionCount > 0) {
                    Text(
                        "Learned from ${profile.interactionCount} interactions",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink2),
                    )
                }
            }
        }

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading...", style = TextStyle(fontFamily = AppFonts.Body, fontSize = 14.sp, color = AppColors.Ink2))
            }
            return
        }

        if (profile == null || profile.interactionCount == 0) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No taste data yet. Approve a few plans and I'll start learning what you like.",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AppColors.Ink2, lineHeight = 20.sp),
                )
            }
            return
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            if (profile.categoryScores.isNotEmpty()) {
                ScoreSection("Categories", "category", profile.categoryScores, AppColors.Coral, viewModel)
            }
            if (profile.cuisineScores.isNotEmpty()) {
                ScoreSection("Cuisines", "cuisine", profile.cuisineScores, AppColors.Sun, viewModel)
            }
            if (profile.neighborhoodScores.isNotEmpty()) {
                ScoreSection("Neighborhoods", "neighborhood", profile.neighborhoodScores, AppColors.Sage, viewModel)
            }
            if (profile.timePreferences.isNotEmpty()) {
                ScoreSection("Time of Day", "time", profile.timePreferences, AppColors.Sky, viewModel)
            }
            if (profile.venueTypeScores.isNotEmpty()) {
                ScoreSection("Venue Types", "venue_type", profile.venueTypeScores, AppColors.Plum, viewModel)
            }

            // Price comfort
            val pc = profile.priceComfort
            if (pc.avgMin > 0 || pc.avgMax > 0) {
                Column {
                    Text(
                        "Price Comfort",
                        style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 15.sp, color = AppColors.Ink),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(AppColors.White, RoundedCornerShape(12.dp))
                            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "$${pc.avgMin.toInt()}-${pc.avgMax.toInt()} per person",
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink),
                        )
                        Text(
                            "${(pc.freeRatio * 100).toInt()}% free events",
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Sage),
                        )
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun ScoreSection(
    title: String,
    dimension: String,
    scores: Map<String, Int>,
    color: Color,
    viewModel: TasteProfileViewModel,
) {
    val sorted = scores.entries.sortedByDescending { it.value }.take(8)
    val maxScore = sorted.maxOfOrNull { it.value } ?: 1

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 15.sp, color = AppColors.Ink),
            )
            Icon(
                Icons.Outlined.Refresh, "Reset",
                modifier = Modifier.size(18.dp).clickable { viewModel.resetSection(dimension) },
                tint = AppColors.Ink2,
            )
        }
        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.White, RoundedCornerShape(12.dp))
                .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (entry in sorted) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.key,
                        modifier = Modifier.width(100.dp),
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink),
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier.weight(1f).height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(AppColors.Pebble.copy(alpha = 0.3f)),
                    ) {
                        val fraction = (entry.value.toFloat() / maxScore).coerceIn(0.05f, 1f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(color),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${entry.value}",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = AppColors.Ink2),
                    )
                }
            }
        }
    }
}
