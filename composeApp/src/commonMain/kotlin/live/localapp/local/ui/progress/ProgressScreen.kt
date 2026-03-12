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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppType
import live.localapp.local.ui.theme.AppShapes
import live.localapp.local.ui.theme.AppFonts

// ── Helper ──

private fun formatNumber(n: Int): String {
    if (n < 1_000) return n.toString()
    val result = StringBuilder()
    val s = n.toString()
    s.forEachIndexed { i, c ->
        if (i > 0 && (s.length - i) % 3 == 0) result.append(',')
        result.append(c)
    }
    return result.toString()
}

// ── Pressable modifier ──

@Composable
private fun pressableModifier(
    shadowColor: Color,
    shadowDp: Float = 3f,
    onClick: () -> Unit = {},
): Pair<Modifier, MutableInteractionSource> {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val offsetY by animateFloatAsState(
        targetValue = if (isPressed) 2f else 0f,
        animationSpec = tween(100),
    )
    val shadowHeight by animateFloatAsState(
        targetValue = if (isPressed) (shadowDp - 2f).coerceAtLeast(0f) else shadowDp,
        animationSpec = tween(100),
    )
    val modifier = Modifier
        .graphicsLayer {
            translationY = offsetY * density
            shadowElevation = shadowHeight * density
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
    return modifier to interactionSource
}

// ── Screen ──

@Composable
fun ProgressScreen(viewModel: ProgressViewModel) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Snow),
    ) {
        // ── Sticky Header ──
        ProgressHeader()

        // ── Scrollable Content ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            HeroPointsCard(state)
            StreakRow(state)
            HowToEarnSection()
            LevelsSection(state)
        }
    }
}

// ── Header ──

@Composable
private fun ProgressHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.White)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .drawBehind {
                val strokeWidth = 2.dp.toPx()
                drawLine(
                    color = AppColors.Pebble,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = strokeWidth,
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Logo "local" — all Fraunces Black, 'c' in Coral
        Row(modifier = Modifier.weight(1f)) {
            Text("lo", style = AppType.Logo)
            Text("c", style = AppType.Logo.copy(color = AppColors.Coral))
            Text("al", style = AppType.Logo)
        }

        // Notification bell — 36dp, Mist bg, Pebble 2dp border
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(AppColors.Mist, AppShapes.Avatar)
                .border(2.dp, AppColors.Pebble, AppShapes.Avatar),
            contentAlignment = Alignment.Center,
        ) {
            Text("\uD83D\uDD14", fontSize = 16.sp)
        }

        Spacer(Modifier.width(8.dp))

        // Avatar — 36dp, Coral bg, CoralDk 2dp border
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(AppColors.Coral, AppShapes.Avatar)
                .border(2.dp, AppColors.CoralDk, AppShapes.Avatar),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "J",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp,
                    color = AppColors.White,
                ),
            )
        }
    }
}

// ── Hero Points Card ──

@Composable
private fun HeroPointsCard(state: ProgressUiState) {
    val fillFraction by animateFloatAsState(
        targetValue = state.progressFraction,
        animationSpec = tween(800),
    )

    Box(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 18.dp)
            .fillMaxWidth()
            // Shadow
            .graphicsLayer { shadowElevation = 6.dp.toPx() }
            .background(AppColors.HeroBgDark, RoundedCornerShape(28.dp))
            .background(AppColors.Ink, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .drawBehind {
                // Coral glow top-right
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            AppColors.Coral.copy(alpha = 0.15f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.85f, size.height * 0.15f),
                        radius = size.width * 0.5f,
                    ),
                    center = Offset(size.width * 0.85f, size.height * 0.15f),
                    radius = size.width * 0.5f,
                )
                // Sage glow bottom-left
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            AppColors.Sage.copy(alpha = 0.1f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.15f, size.height * 0.85f),
                        radius = size.width * 0.45f,
                    ),
                    center = Offset(size.width * 0.15f, size.height * 0.85f),
                    radius = size.width * 0.45f,
                )
            }
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 20.dp),
    ) {
        Column {
            // Level row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Level badge
                Row(
                    modifier = Modifier
                        .background(
                            AppColors.White.copy(alpha = 0.1f),
                            RoundedCornerShape(50),
                        )
                        .border(
                            1.5.dp,
                            AppColors.White.copy(alpha = 0.15f),
                            RoundedCornerShape(50),
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(state.currentLevel.icon, fontSize = 18.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        state.currentLevel.name,
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp,
                            color = AppColors.White.copy(alpha = 0.95f),
                        ),
                    )
                }

                // Next level info
                if (state.nextLevel != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "Next: ${state.nextLevel!!.name}",
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = AppColors.White.copy(alpha = 0.45f),
                            ),
                        )
                        Text(
                            "${formatNumber(state.pointsToNext)} pts away",
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.Black,
                                fontSize = 11.sp,
                                color = AppColors.White.copy(alpha = 0.70f),
                            ),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Big counter
            Row(
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    formatNumber(state.totalPoints),
                    style = AppType.PointsCounter,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "pts",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        color = AppColors.White.copy(alpha = 0.5f),
                    ),
                )
            }

            Spacer(Modifier.height(16.dp))

            // Progress bar labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    formatNumber(state.currentLevel.minPts),
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 10.sp,
                        color = AppColors.White.copy(alpha = 0.35f),
                    ),
                )
                Text(
                    formatNumber(state.nextLevel?.minPts ?: state.currentLevel.maxPts),
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 10.sp,
                        color = AppColors.White.copy(alpha = 0.35f),
                    ),
                )
            }

            Spacer(Modifier.height(4.dp))

            // Progress bar track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .background(
                        AppColors.White.copy(alpha = 0.1f),
                        RoundedCornerShape(6.dp),
                    )
                    .clip(RoundedCornerShape(6.dp)),
            ) {
                // Fill
                if (fillFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fillFraction)
                            .height(10.dp)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(AppColors.Coral, AppColors.Sun),
                                ),
                                RoundedCornerShape(6.dp),
                            ),
                    ) {
                        // White dot indicator at the end
                        if (fillFraction > 0.05f) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .offset(x = 2.dp)
                                    .size(14.dp)
                                    .background(AppColors.White, CircleShape)
                                    .border(2.dp, AppColors.Coral, CircleShape),
                            )
                        }
                    }
                }
            }
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
            .padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StreakCard(
            icon = "\uD83D\uDD25",
            value = state.streakDays.toString(),
            label = "Day streak",
            modifier = Modifier.weight(1f),
        )
        StreakCard(
            icon = "\u2B50",
            value = state.favoritesSaved.toString(),
            label = "Favorites saved",
            modifier = Modifier.weight(1f),
        )
        StreakCard(
            icon = "\u2705",
            value = state.eventsConfirmed.toString(),
            label = "Events confirmed",
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
            // Shadow
            .padding(bottom = 3.dp)
            .background(AppColors.PebbleProgress, RoundedCornerShape(20.dp))
            .padding(bottom = if (!isPressed) 3.dp else 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.White, RoundedCornerShape(20.dp))
                .border(2.dp, AppColors.PebbleProgress, RoundedCornerShape(20.dp))
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
                Text(value, style = AppType.StreakValue)
                Spacer(Modifier.height(2.dp))
                Text(
                    label,
                    maxLines = 1,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = AppColors.Ink3Progress,
                    ),
                )
            }
        }
    }
}

// ── How to Earn ──

private data class EarnRow(
    val emoji: String,
    val emojiBg: Color,
    val emojiBorder: Color,
    val action: String,
    val desc: String,
    val pts: String,
)

@Composable
private fun HowToEarnSection() {
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 22.dp),
    ) {
        Text(
            "How to earn",
            style = TextStyle(
                fontFamily = AppFonts.Heading,
                fontWeight = FontWeight.Black,
                fontSize = 17.sp,
                color = AppColors.Ink,
            ),
        )

        Spacer(Modifier.height(12.dp))

        val rows = listOf(
            EarnRow("\uD83C\uDF05", AppColors.SunLt, Color(0xFFFFE8A0), "Daily login", "Once per day", "+10 pts"),
            EarnRow("\u2661", Color(0xFFFFF0EE), Color(0xFFFFD5D0), "Favorite an event", "Save anything you love", "+1 pt"),
            EarnRow("\u2713", Color(0xFFEDFAF5), Color(0xFFC5EFE0), "Confirm an event", "Lock it in your calendar", "+2 pts"),
            EarnRow("\uD83D\uDCC5", Color(0xFFEEF5FF), Color(0xFFC0D8FF), "Create an itinerary", "Build your perfect day", "+100 pts"),
            EarnRow("\uD83D\uDD17", Color(0xFFF5F0FF), Color(0xFFD5C0FF), "Share an itinerary", "Spread the love", "+1,000 pts"),
            EarnRow("\uD83D\uDD25", AppColors.SunLt, Color(0xFFFFE8A0), "7-day login streak", "Show up every day", "+100 pts"),
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                EarnRowCard(row)
            }
        }
    }
}

@Composable
private fun EarnRowCard(row: EarnRow) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val offsetY by animateFloatAsState(
        targetValue = if (isPressed) 2f else 0f,
        animationSpec = tween(100),
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = offsetY * density }
            // Shadow
            .padding(bottom = 3.dp)
            .background(AppColors.PebbleProgress, RoundedCornerShape(16.dp))
            .padding(bottom = if (!isPressed) 3.dp else 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.White, RoundedCornerShape(16.dp))
                .border(2.dp, AppColors.PebbleProgress, RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {},
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Emoji box
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(row.emojiBg, RoundedCornerShape(12.dp))
                    .border(2.dp, row.emojiBorder, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(row.emoji, fontSize = 18.sp)
            }

            Spacer(Modifier.width(12.dp))

            // Info column
            Column(modifier = Modifier.weight(1f)) {
                Text(row.action, style = AppType.EarnAction)
                Text(row.desc, style = AppType.EarnDesc.copy(color = AppColors.Ink3Progress))
            }

            Spacer(Modifier.width(8.dp))

            // Points
            Text(row.pts, style = AppType.EarnPts)
        }
    }
}

// ── Levels Section ──

@Composable
private fun LevelsSection(state: ProgressUiState) {
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 22.dp),
    ) {
        Text(
            "Levels",
            style = TextStyle(
                fontFamily = AppFonts.Heading,
                fontWeight = FontWeight.Black,
                fontSize = 17.sp,
                color = AppColors.Ink,
            ),
        )

        Spacer(Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.levels.forEachIndexed { index, level ->
                LevelRowCard(
                    level = level,
                    isCurrent = index == state.currentLevelIndex,
                    isPast = index < state.currentLevelIndex,
                    isFuture = index > state.currentLevelIndex,
                )
            }
        }
    }
}

@Composable
private fun LevelRowCard(
    level: Level,
    isCurrent: Boolean,
    isPast: Boolean,
    isFuture: Boolean,
) {
    val bgColor = if (isCurrent) AppColors.Ink else AppColors.White
    val borderColor = if (isCurrent) AppColors.Ink else AppColors.PebbleProgress
    val shadowColor = if (isCurrent) AppColors.HeroBgDark else AppColors.PebbleProgress
    val alphaModifier = if (isFuture) 0.45f else 1f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alphaModifier)
            // Shadow
            .padding(bottom = 3.dp)
            .background(shadowColor, RoundedCornerShape(16.dp))
            .padding(bottom = 3.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor, RoundedCornerShape(16.dp))
                .border(2.dp, borderColor, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon
            Text(level.icon, fontSize = 22.sp)

            Spacer(Modifier.width(12.dp))

            // Name + points range
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    level.name,
                    style = AppType.LevelName.copy(
                        color = if (isCurrent) AppColors.White else AppColors.Ink,
                    ),
                )
                Text(
                    levelPtsLabel(level),
                    style = AppType.LevelPts.copy(
                        color = if (isCurrent) AppColors.White.copy(alpha = 0.5f) else AppColors.Ink3,
                    ),
                )
            }

            // Badge
            when {
                isCurrent -> {
                    Text(
                        "YOU ARE HERE",
                        modifier = Modifier
                            .background(AppColors.Coral, RoundedCornerShape(20.dp))
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.Black,
                            fontSize = 10.sp,
                            color = AppColors.White,
                        ),
                    )
                }
                isPast -> {
                    Text(
                        "\u2713 Unlocked",
                        modifier = Modifier
                            .background(Color(0xFFEDFAF5), RoundedCornerShape(20.dp))
                            .border(1.5.dp, Color(0xFFC5EFE0), RoundedCornerShape(20.dp))
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = AppColors.Sage,
                        ),
                    )
                }
                isFuture -> {
                    Text(
                        "\uD83D\uDD12",
                        modifier = Modifier
                            .background(AppColors.MistProgress, RoundedCornerShape(20.dp))
                            .border(1.5.dp, AppColors.PebbleProgress, RoundedCornerShape(20.dp))
                            .padding(horizontal = 9.dp, vertical = 4.dp),
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
}

private fun levelPtsLabel(level: Level): String {
    return when {
        level.maxPts == Int.MAX_VALUE -> "${formatNumber(level.minPts)} pts"
        level.minPts == 0 -> "0 \u2013 ${formatNumber(level.maxPts)} pts"
        else -> "${formatNumber(level.minPts)} pts"
    }
}
