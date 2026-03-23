package live.localapp.local.ui.progress

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts
import live.localapp.local.ui.theme.AppShapes

@Composable
fun ProgressScreen(viewModel: ProgressViewModel, onLogout: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Snow),
    ) {
        // ── Header ──
        ProfileHeader(state)

        // ── Scrollable Content ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 80.dp),
        ) {
            StreakRow(state)

            // ── Log Out ──
            Spacer(Modifier.height(32.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.Mist)
                    .clickable(onClick = onLogout)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Log Out",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = AppColors.Ink3,
                    ),
                )
            }
        }
    }
}

// ── Header ──

@Composable
private fun ProfileHeader(state: ProgressUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Coral)
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "profile",
            modifier = Modifier.weight(1f),
            style = TextStyle(
                fontFamily = AppFonts.Heading,
                fontWeight = FontWeight.Black,
                fontSize = 24.sp,
                color = AppColors.White,
                letterSpacing = (-0.5).sp,
            ),
        )

        // Avatar — user's first initial
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(AppColors.White, AppShapes.Avatar)
                .border(2.dp, AppColors.White.copy(alpha = 0.6f), AppShapes.Avatar),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                state.userInitial.ifEmpty { "?" },
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp,
                    color = AppColors.Coral,
                ),
            )
        }
    }
}

// ── Streak Row ──

@Composable
private fun StreakRow(state: ProgressUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StreakCard(
            icon = "\u2B50",
            value = state.favoritesSaved.toString(),
            label = "Favorites",
            modifier = Modifier.weight(1f),
        )
        StreakCard(
            icon = "\uD83D\uDCC5",
            value = state.plansCreated.toString(),
            label = "Plans",
            modifier = Modifier.weight(1f),
        )
        StreakCard(
            icon = "\uD83D\uDC40",
            value = state.eventsExplored.toString(),
            label = "Explored",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StreakCard(
    icon: String,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val offsetY by animateFloatAsState(
        targetValue = if (isPressed) 2f else 0f,
        animationSpec = tween(100),
    )

    Box(
        modifier = modifier
            .graphicsLayer { translationY = offsetY * density }
            .padding(bottom = 3.dp)
            .background(AppColors.Pebble, RoundedCornerShape(20.dp))
            .padding(bottom = if (!isPressed) 3.dp else 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.White, RoundedCornerShape(20.dp))
                .border(2.dp, AppColors.Pebble, RoundedCornerShape(20.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {},
                )
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(icon, fontSize = 24.sp)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    value,
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp,
                        color = AppColors.Ink,
                    ),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    label,
                    maxLines = 1,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = AppColors.Ink3,
                    ),
                )
            }
        }
    }
}
