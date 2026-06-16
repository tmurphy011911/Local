package live.localapp.local.ui.leaderboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.data.remote.dto.LeaderboardDto
import live.localapp.local.data.remote.dto.LeaderboardEntryDto
import live.localapp.local.domain.model.boLevel
import live.localapp.local.domain.model.statusTierForPoints
import live.localapp.local.domain.repository.LeaderboardScope
import live.localapp.local.ui.character.Bo
import live.localapp.local.ui.common.pressable
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

// ── Leaderboard palette (Option A "Podium" handoff) ──────────────────────────
// Green == leaderboard. Medals for the top three.
private val LbGreen = Color(0xFF16B26A)
private val LbGreenDeep = Color(0xFF0E9457)
private val LbGreenSoft = Color(0xFFE4F6EC)
private val LbGreenTint = Color(0xFFF1FBF6)
private val LbGreenLine = Color(0xFFBFE9D2)
private val LbInk = Color(0xFF15201B)
private val LbSub = Color(0xFF9A96A2)
private val Gold = Color(0xFFF5B027); private val GoldFace = Color(0xFFFAC944); private val GoldDeep = Color(0xFFD9911A)
private val SilverFace = Color(0xFFC4CDD7); private val SilverDeep = Color(0xFF8A97A6)
private val BronzeFace = Color(0xFFD89A66); private val BronzeDeep = Color(0xFFA96632)

// ── Public host ──────────────────────────────────────────────────────────────

/**
 * The whole leaderboard surface for the home lobby: the tappable A1 entry card
 * plus the B1 "Podium" bottom sheet. Drop into a single gated `item {}` between
 * the Ask Bo card and Saved Events. Hides itself until a rank is known and only
 * shows for users with FOMO > 0.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardHost(
    viewModel: LeaderboardViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    var showSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadCta() }

    val cta = state.cta
    val stateCode = state.stateCode
    // Hide for users with no FOMO yet (overrides spec §5's "always show").
    if (cta != null && stateCode != null && cta.fomoTotal > 0) {
        LeaderboardCta(
            rank = cta.rank,
            fomo = cta.fomoTotal,
            inTop100 = cta.inTop100,
            stateCode = stateCode,
            modifier = modifier,
            onClick = {
                viewModel.resetScopeToState()
                viewModel.prefetchAll()
                showSheet = true
            },
        )
    }

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFFF6F5F9),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(top = 12.dp, bottom = 2.dp)
                        .width(46.dp)
                        .height(5.dp)
                        .background(Color(0xFFD2CFDA), RoundedCornerShape(999.dp)),
                )
            },
        ) {
            LeaderboardSheetContent(state = state, onSelectScope = viewModel::selectScope)
        }
    }
}

// ── A1 home entry card (single line, green, podium icon) ─────────────────────

@Composable
private fun LeaderboardCta(
    rank: Int,
    fomo: Int,
    inTop100: Boolean,
    stateCode: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    // Count-up: seed with current so first paint is static; only a later change
    // (FOMO earned) animates + pulses + buzzes.
    var shownFomo by remember { mutableStateOf(fomo) }
    var shownRank by remember { mutableStateOf(rank) }
    LaunchedEffect(fomo, rank) { shownFomo = fomo; shownRank = rank }
    val animFomo by animateIntAsState(shownFomo, tween(420))
    val animRank by animateIntAsState(shownRank, tween(420))

    val pulse = remember { Animatable(1f) }
    var prevFomo by remember { mutableStateOf(fomo) }
    LaunchedEffect(fomo) {
        if (fomo != prevFomo) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            pulse.animateTo(1.07f, tween(130)); pulse.animateTo(1f, spring())
        }
        prevFomo = fomo
    }

    Row(
        modifier = modifier
            .padding(horizontal = 18.dp)
            .padding(top = 4.dp, bottom = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AppColors.White, RoundedCornerShape(18.dp))
            .border(1.5.dp, LbGreen, RoundedCornerShape(18.dp))
            .pressable(onClick = onClick, scale = 0.97f)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(LbGreenSoft),
            contentAlignment = Alignment.Center,
        ) { IconPodium(size = 21.dp) }
        Spacer(Modifier.width(12.dp))

        // Sentence with green emphasis on the rank/FOMO, single line w/ ellipsis.
        val emphasis = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = LbGreenDeep)
        val base = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = LbInk)
        Row(
            modifier = Modifier.weight(1f).graphicsLayer { scaleX = pulse.value; scaleY = pulse.value },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                inTop100 && rank == 1 ->
                    EllipsisLine(base, "You have ", emphasis to "the most", base to " FOMO in $stateCode.")
                inTop100 ->
                    EllipsisLine(base, "You have the ", emphasis to ordinal(animRank), base to " most FOMO in $stateCode.")
                else ->
                    EllipsisLine(base, "", emphasis to groupThousands(animFomo), base to " FOMO · ${ordinal(animRank)} in $stateCode")
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("›", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = LbGreen))
    }
}

/** Builds a one-line sentence from a leading + an emphasized + a trailing span. */
@Composable
private fun EllipsisLine(
    base: TextStyle,
    leading: String,
    emphasized: Pair<TextStyle, String>,
    trailing: Pair<TextStyle, String>,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (leading.isNotEmpty()) Text(leading, style = base, maxLines = 1)
        Text(emphasized.second, style = emphasized.first, maxLines = 1)
        Text(trailing.second, style = trailing.first, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── B1 Podium sheet ──────────────────────────────────────────────────────────

@Composable
private fun LeaderboardSheetContent(
    state: LeaderboardViewModel.UiState,
    onSelectScope: (LeaderboardScope) -> Unit,
) {
    val current = state.current
    val regionTitle = current?.location ?: when (state.scope) {
        LeaderboardScope.CITY -> "City"; LeaderboardScope.STATE -> "State"; LeaderboardScope.COUNTRY -> "United States"
    }
    val homeCity = state.byScope[LeaderboardScope.CITY]?.location

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp),
    ) {
        // Title
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 16.dp)) {
            Text(
                "$regionTitle Leaderboard",
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 30.sp, color = LbInk),
            )
            if (homeCity != null) {
                Spacer(Modifier.height(6.dp))
                Text("Your home city: $homeCity", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = LbSub))
            }
        }

        // Region tabs (green active)
        RegionTabs(
            scope = state.scope,
            cityLabel = state.byScope[LeaderboardScope.CITY]?.location ?: "City",
            stateLabel = state.byScope[LeaderboardScope.STATE]?.location ?: "State",
            countryLabel = "USA",
            onSelect = onSelectScope,
        )

        Spacer(Modifier.height(4.dp))

        when {
            current == null && state.sheetLoading ->
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LbGreen)
                }
            current == null || current.rows.isEmpty() ->
                Text(
                    "No rankings here yet — be the first to rack up FOMO.",
                    style = TextStyle(fontFamily = AppFonts.Body, fontSize = 14.sp, color = LbSub),
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
                )
            else -> {
                val rows = current.rows
                val me = current.me
                val podium = rows.take(3)
                val listRows = rows.drop(3) // ranks 4..10
                val meInShown = rows.any { it.isMe }

                Podium(podium)

                if (listRows.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listRows.forEach { ListRow(it, isYou = it.isMe, animateOnAppear = false) }
                    }
                }

                // The pinned own-row the handoff omitted: when the caller is below
                // the visible set, surface their spot at the bottom, clearly theirs.
                if (me != null && !meInShown) {
                    PinnedDivider()
                    Column(Modifier.padding(horizontal = 18.dp)) {
                        ListRow(me, isYou = true, animateOnAppear = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionTabs(
    scope: LeaderboardScope,
    cityLabel: String,
    stateLabel: String,
    countryLabel: String,
    onSelect: (LeaderboardScope) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Tab(cityLabel, scope == LeaderboardScope.CITY, Modifier.weight(1f)) { onSelect(LeaderboardScope.CITY) }
        Tab(stateLabel, scope == LeaderboardScope.STATE, Modifier.weight(1.35f)) { onSelect(LeaderboardScope.STATE) }
        Tab(countryLabel, scope == LeaderboardScope.COUNTRY, Modifier.weight(1f)) { onSelect(LeaderboardScope.COUNTRY) }
    }
}

@Composable
private fun Tab(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) LbGreen else AppColors.White, RoundedCornerShape(16.dp))
            .pressable(onClick = onClick, scale = 0.96f)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (on) AppColors.White else Color(0xFF3A3742)),
        )
    }
}

@Composable
private fun Podium(podium: List<LeaderboardEntryDto>) {
    val p1 = podium.getOrNull(0); val p2 = podium.getOrNull(1); val p3 = podium.getOrNull(2)
    Box(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(LbGreenSoft, LbGreenTint)))
            .border(1.5.dp, LbGreenLine, RoundedCornerShape(22.dp))
            .padding(start = 14.dp, end = 14.dp, top = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PodiumColumn(p2, place = 2, blockHeight = 86.dp, boSize = 60.dp, face = SilverFace, edge = SilverDeep, modifier = Modifier.weight(1f))
            PodiumColumn(p1, place = 1, blockHeight = 120.dp, boSize = 88.dp, face = GoldFace, edge = GoldDeep, modifier = Modifier.weight(1f))
            PodiumColumn(p3, place = 3, blockHeight = 66.dp, boSize = 56.dp, face = BronzeFace, edge = BronzeDeep, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PodiumColumn(
    p: LeaderboardEntryDto?,
    place: Int,
    blockHeight: androidx.compose.ui.unit.Dp,
    boSize: androidx.compose.ui.unit.Dp,
    face: Color,
    edge: Color,
    modifier: Modifier = Modifier,
) {
    if (p == null) { Spacer(modifier); return }
    val isYou = p.isMe
    val level = statusTierForPoints(p.fomoTotal).boLevel.coerceIn(0, 8)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
        // Every podium Bo is shown at its owner's real loyalty level — no crown,
        // halo, or other alteration. Rank is conveyed by the podium block, not Bo.
        Bo(size = boSize, level = level, showShadow = false, showSparkles = false)
        Text(
            if (isYou) "You" else (p.username ?: "Anonymous"),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = LbInk),
        )
        Spacer(Modifier.height(2.dp))
        // Block
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(blockHeight)
                .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                .background(Brush.verticalGradient(listOf(face, edge))),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 8.dp)) {
                Text("$place", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = if (place == 1) 28.sp else 22.sp, color = AppColors.White))
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(AppColors.White.copy(alpha = 0.92f)).padding(horizontal = 9.dp, vertical = 2.dp),
                ) {
                    Text(groupThousands(p.fomoTotal), style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 12.sp, color = edge))
                }
            }
        }
    }
}

@Composable
private fun ListRow(entry: LeaderboardEntryDto, isYou: Boolean, animateOnAppear: Boolean) {
    val level = statusTierForPoints(entry.fomoTotal).boLevel.coerceIn(0, 8)
    var shown by remember { mutableStateOf(if (animateOnAppear) 0 else entry.fomoTotal) }
    LaunchedEffect(entry.fomoTotal) { shown = entry.fomoTotal }
    val animFomo by animateIntAsState(shown, tween(600))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isYou) LbGreenSoft else AppColors.White, RoundedCornerShape(16.dp))
            .then(if (isYou) Modifier.border(1.5.dp, LbGreen, RoundedCornerShape(16.dp)) else Modifier)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${entry.rank}", modifier = Modifier.width(26.dp), style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = if (isYou) LbGreenDeep else LbSub))
        Bo(size = 38.dp, level = level, showShadow = false, showSparkles = false)
        Spacer(Modifier.width(13.dp))
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(if (isYou) "You" else (entry.username ?: "Anonymous"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = LbInk))
            if (isYou) {
                Spacer(Modifier.width(7.dp))
                Box(Modifier.clip(RoundedCornerShape(999.dp)).background(LbGreen).padding(horizontal = 7.dp, vertical = 1.dp)) {
                    Text("YOU", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 9.sp, color = AppColors.White))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(groupThousands(animFomo), style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 18.sp, color = LbGreenDeep))
        Spacer(Modifier.width(4.dp))
        Text("FOMO", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = LbSub))
    }
}

/** Dashed green divider labelled "YOUR RANK" above the pinned own-row. */
@Composable
private fun PinnedDivider() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f).height(2.dp).background(LbGreenLine))
        Text("YOUR RANK", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 11.sp, color = LbGreenDeep))
        Box(Modifier.weight(1f).height(2.dp).background(LbGreenLine))
    }
}

// ── Iconography (faithful to sprites.jsx IconPodium) ─────────────────────────

@Composable
private fun IconPodium(size: androidx.compose.ui.unit.Dp, tint: Color = LbGreen) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = this.size.minDimension / 64f
            fun block(x: Float, y: Float, w: Float, h: Float, a: Float) =
                drawRoundRect(color = tint.copy(alpha = a), topLeft = Offset(x * s, y * s), size = Size(w * s, h * s), cornerRadius = CornerRadius(2f * s, 2f * s))
            block(8f, 34f, 16f, 22f, 0.35f)   // 2nd
            block(24f, 22f, 16f, 34f, 1f)     // 1st
            block(40f, 40f, 16f, 16f, 0.22f)  // 3rd
            // flag
            drawLine(LbInk, Offset(32f * s, 8f * s), Offset(32f * s, 20f * s), strokeWidth = 3f * s, cap = StrokeCap.Round)
            val flag = Path().apply { moveTo(32f * s, 9f * s); lineTo(44f * s, 12f * s); lineTo(32f * s, 16f * s); close() }
            drawPath(flag, Gold)
        }
        Text("1", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = (size.value * 0.2f).sp, color = AppColors.White), modifier = Modifier.padding(top = (size.value * 0.18f).dp))
    }
}

// ── helpers ──────────────────────────────────────────────────────────────────

/** 1 -> "1st", 2 -> "2nd", 3 -> "3rd", 11 -> "11th", 22 -> "22nd", ... */
private fun ordinal(n: Int): String {
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    return "$n$suffix"
}

/** 1234 -> "1,234" (no locale dependency in commonMain). */
private fun groupThousands(n: Int): String {
    val neg = n < 0
    val digits = (if (neg) -n else n).toString()
    val sb = StringBuilder()
    for (i in digits.indices) {
        if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
        sb.append(digits[i])
    }
    return (if (neg) "-" else "") + sb.toString()
}
