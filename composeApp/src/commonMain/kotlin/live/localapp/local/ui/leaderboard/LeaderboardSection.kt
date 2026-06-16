package live.localapp.local.ui.leaderboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
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
import live.localapp.local.ui.theme.AppType

// ── Leaderboard palette (Option A "Podium" handoff) ──────────────────────────
// Green == leaderboard. Podium ranks use the app's brand family (same accents as
// the onboarding steps), not metal medal colors.
private val LbGreen = Color(0xFF16B26A)
private val LbGreenDeep = Color(0xFF0E9457)
private val LbGreenSoft = Color(0xFFE4F6EC)
private val LbGreenTint = Color(0xFFF1FBF6)
private val LbGreenLine = Color(0xFFBFE9D2)
private val LbInk = Color(0xFF15201B)
private val LbSub = Color(0xFF9A96A2)
private val SheetBg = Color(0xFFF6F5F9)

// ── Public host ──────────────────────────────────────────────────────────────

/**
 * The whole leaderboard surface for the home lobby: the tappable A1 entry card
 * plus the B1 "Podium" bottom sheet. Drop into a single gated `item {}` between
 * the Ask Bo card and Saved Events. Hides itself until a rank is known and only
 * shows for users with FOMO > 0.
 */
/** Lobby entry point — the shared card, hidden for 0-FOMO users. */
@Composable
fun LeaderboardHost(viewModel: LeaderboardViewModel, modifier: Modifier = Modifier) {
    LeaderboardCard(viewModel, hideAtZeroFomo = true, modifier = modifier)
}

/** Account-page entry point — the same shared card, always shown. */
@Composable
fun LeaderboardAccountEntry(viewModel: LeaderboardViewModel, modifier: Modifier = Modifier) {
    LeaderboardCard(viewModel, hideAtZeroFomo = false, modifier = modifier)
}

/**
 * The single leaderboard entry CTA used on BOTH the lobby and the account page:
 * podium icon + "leaderboard" wordmark + a cascading rank subtitle, opening the
 * B1 podium sheet. [hideAtZeroFomo] is the only difference between placements.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardCard(
    viewModel: LeaderboardViewModel,
    hideAtZeroFomo: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    var showSheet by remember { mutableStateOf(false) }
    // Load all three scopes — the subtitle cascades country → state → city.
    LaunchedEffect(Unit) { viewModel.prefetchAll() }

    // FOMO is global, so any loaded scope's `me` carries it.
    val anyMe = state.byScope[LeaderboardScope.COUNTRY]?.me
        ?: state.byScope[LeaderboardScope.STATE]?.me
        ?: state.byScope[LeaderboardScope.CITY]?.me
    val visible = !hideAtZeroFomo || (anyMe != null && anyMe.fomoTotal > 0)

    if (visible) {
        Row(
            modifier = modifier
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(AppColors.White, RoundedCornerShape(16.dp))
                .border(1.5.dp, LbGreen, RoundedCornerShape(16.dp))
                .pressable(onClick = {
                    viewModel.resetScopeToState()
                    showSheet = true
                }, scale = 0.94f)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(LbGreenSoft),
                contentAlignment = Alignment.Center,
            ) { IconPodium(size = 23.dp) }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                // "leaderboard" in the wordmark font, green l.
                Text(
                    buildAnnotatedString {
                        "leaderboard".forEach { ch ->
                            if (ch == 'l') withStyle(SpanStyle(color = LbGreen)) { append(ch) } else append(ch)
                        }
                    },
                    style = AppType.Logo.copy(fontSize = 20.sp, color = LbInk),
                )
                Text(
                    leaderboardCtaSubtitle(state),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = LbSub),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("›", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = LbGreen))
        }
    }

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = SheetBg,
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

/**
 * Cascading rank line: show the BROADEST scope the user is still top-100 in
 * (country → state → city); "1st" drops the ordinal; outside top-100 everywhere
 * falls back to FOMO total + city rank.
 */
private fun leaderboardCtaSubtitle(state: LeaderboardViewModel.UiState): String {
    val country = state.byScope[LeaderboardScope.COUNTRY]?.me
    val stateDto = state.byScope[LeaderboardScope.STATE]
    val cityDto = state.byScope[LeaderboardScope.CITY]
    val stateMe = stateDto?.me
    val cityMe = cityDto?.me
    val stateLabel = stateDto?.location
    val cityLabel = cityDto?.location

    fun line(rank: Int, place: String) =
        if (rank == 1) "You have the most FOMO in $place"
        else "You have the ${ordinal(rank)} most FOMO in $place"

    return when {
        country?.inTop100 == true -> line(country.rank, "America")
        stateMe?.inTop100 == true && !stateLabel.isNullOrBlank() -> line(stateMe.rank, stateLabel)
        cityMe?.inTop100 == true && !cityLabel.isNullOrBlank() -> line(cityMe.rank, cityLabel)
        else -> {
            val fomo = country?.fomoTotal ?: stateMe?.fomoTotal ?: cityMe?.fomoTotal
            if (fomo != null && cityMe != null && !cityLabel.isNullOrBlank())
                "${groupThousands(fomo)}: ${ordinal(cityMe.rank)} in $cityLabel"
            else "See where you rank"
        }
    }
}

// ── B1 Podium sheet ──────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LeaderboardSheetContent(
    state: LeaderboardViewModel.UiState,
    onSelectScope: (LeaderboardScope) -> Unit,
) {
    val current = state.current
    val rows = current?.rows ?: emptyList()
    val me = current?.me
    val podium = rows.take(3)
    val listRows = rows.drop(3) // ranks 4..100
    // City scope: everyone shares the city, so show just the join date.
    // State/Country: show the member's home city too.
    val showCity = state.scope != LeaderboardScope.CITY

    // LazyColumn (not a scrolling Column) so up to 100 rows render lazily —
    // off-screen rows aren't composed.
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp),
    ) {
        // Sticky cluster: wordmark header + scope tabs + the caller's own row stay
        // pinned to the top while the rankings scroll underneath.
        stickyHeader("header") {
            Column(Modifier.fillMaxWidth().background(SheetBg)) {
                // Wordmark next to a bigger version of the card's podium icon.
                Row(
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(LbGreenSoft),
                        contentAlignment = Alignment.Center,
                    ) { IconPodium(size = 29.dp) }
                    Spacer(Modifier.width(13.dp))
                    Text(
                        buildAnnotatedString {
                            "local".forEach { ch ->
                                if (ch == 'l') withStyle(SpanStyle(color = LbGreen)) { append(ch) } else append(ch)
                            }
                            append(" leaderboard")
                        },
                        modifier = Modifier.weight(1f),
                        style = AppType.Logo.copy(fontSize = 26.sp, color = LbInk),
                    )
                }
                RegionTabs(
                    scope = state.scope,
                    cityLabel = state.byScope[LeaderboardScope.CITY]?.location ?: "City",
                    stateLabel = state.byScope[LeaderboardScope.STATE]?.location ?: "State",
                    countryLabel = "USA",
                    onSelect = onSelectScope,
                )
                Spacer(Modifier.height(8.dp))
                // The caller's own row, pinned with the header.
                if (me != null) {
                    Box(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp)) {
                        ListRow(me, isYou = true, showCity = showCity)
                    }
                }
            }
        }

        when {
            current == null && state.sheetLoading -> item("loading") {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LbGreen)
                }
            }
            current == null || rows.isEmpty() -> item("empty") {
                Text(
                    "No rankings here yet — be the first to rack up FOMO.",
                    style = TextStyle(fontFamily = AppFonts.Body, fontSize = 14.sp, color = LbSub),
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
                )
            }
            else -> {
                item("podium") {
                    Podium(podium.getOrNull(0), podium.getOrNull(1), podium.getOrNull(2))
                    Spacer(Modifier.height(12.dp))
                }
                items(listRows, key = { it.rank }) { e ->
                    Box(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                        ListRow(e, isYou = e.isMe, showCity = showCity)
                    }
                }
                // Pad to a minimum of 10 visible spots so a small scope never looks
                // empty below the podium. Real rows already grow up to 100.
                val maxRank = rows.lastOrNull()?.rank ?: 0
                val padStart = maxOf(4, maxRank + 1)
                if (padStart <= 10) {
                    items((padStart..10).toList(), key = { "ph$it" }) { r ->
                        Box(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                            PlaceholderRow(rank = r)
                        }
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
private fun Podium(p1: LeaderboardEntryDto?, p2: LeaderboardEntryDto?, p3: LeaderboardEntryDto?) {
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
            PodiumColumn(p2, place = 2, blockHeight = 86.dp, boSize = 60.dp, face = AppColors.Sky, edge = AppColors.SkyDk, modifier = Modifier.weight(1f))
            PodiumColumn(p1, place = 1, blockHeight = 120.dp, boSize = 88.dp, face = AppColors.Sun, edge = AppColors.SunDk, modifier = Modifier.weight(1f))
            PodiumColumn(p3, place = 3, blockHeight = 66.dp, boSize = 56.dp, face = AppColors.Orange, edge = AppColors.OrangeDk, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PodiumColumn(
    p: LeaderboardEntryDto?,
    place: Int,
    blockHeight: Dp,
    boSize: Dp,
    face: Color,
    edge: Color,
    modifier: Modifier = Modifier,
) {
    val empty = p == null
    val isYou = p?.isMe == true
    val level = if (p != null) statusTierForPoints(p.fomoTotal).boLevel.coerceIn(0, 8) else 0
    val blockBrush = if (empty) Brush.verticalGradient(listOf(Color(0xFFE3E0EA), Color(0xFFC8C3D4)))
                     else Brush.verticalGradient(listOf(face, edge))
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
        // Bo shown at its owner's real loyalty level — no crown/halo. Unclaimed
        // spots show a muted placeholder so the podium keeps a constant shape.
        if (empty) PlaceholderAvatar(boSize) else Bo(size = boSize, level = level, showShadow = false, showSparkles = false)
        Text(
            if (empty) "—" else if (isYou) "You" else (p!!.username ?: "Anonymous"),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (empty) LbSub else LbInk),
        )
        Spacer(Modifier.height(2.dp))
        // Block
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(blockHeight)
                .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                .background(blockBrush),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 8.dp)) {
                Text("$place", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = if (place == 1) 28.sp else 22.sp, color = AppColors.White))
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(AppColors.White.copy(alpha = 0.92f)).padding(horizontal = 9.dp, vertical = 2.dp),
                ) {
                    Text(if (empty) "—" else groupThousands(p!!.fomoTotal), style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 12.sp, color = if (empty) LbSub else edge))
                }
            }
        }
    }
}

@Composable
private fun ListRow(entry: LeaderboardEntryDto, isYou: Boolean, showCity: Boolean) {
    val level = statusTierForPoints(entry.fomoTotal).boLevel.coerceIn(0, 8)
    val joined = joinedShort(entry.joinedAt)
    val subtext = when {
        joined.isEmpty() -> null
        showCity && !entry.city.isNullOrBlank() -> "${entry.city} · $joined"
        else -> joined
    }

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
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isYou) "You" else (entry.username ?: "Anonymous"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = LbInk))
                if (isYou) {
                    Spacer(Modifier.width(7.dp))
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(LbGreen).padding(horizontal = 7.dp, vertical = 1.dp)) {
                        Text("YOU", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 9.sp, color = AppColors.White))
                    }
                }
            }
            if (subtext != null) {
                Text(subtext, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = LbSub))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(groupThousands(entry.fomoTotal), style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 18.sp, color = LbGreenDeep))
        Spacer(Modifier.width(4.dp))
        Text("FOMO", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = LbSub))
    }
}

/** Muted circle that stands in for a Bo on unclaimed leaderboard spots. */
@Composable
private fun PlaceholderAvatar(size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.82f)) { drawCircle(Color(0xFFE3E0EA)) }
    }
}

/** Filler row for ranks no one holds yet — keeps the list at least 10 tall. */
@Composable
private fun PlaceholderRow(rank: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.White.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", modifier = Modifier.width(26.dp), style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = LbSub.copy(alpha = 0.5f)))
        PlaceholderAvatar(38.dp)
        Spacer(Modifier.width(13.dp))
        Text("—", modifier = Modifier.weight(1f), style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = LbSub))
        Text("—", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 18.sp, color = LbSub))
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
            drawPath(flag, AppColors.Sun)
        }
        Text("1", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = (size.value * 0.2f).sp, color = AppColors.White), modifier = Modifier.padding(top = (size.value * 0.18f).dp))
    }
}

// ── helpers ──────────────────────────────────────────────────────────────────

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "2026-06-01T..." -> "Joined Jun '26". Returns "" when unparseable. */
private fun joinedShort(iso: String?): String {
    if (iso == null || iso.length < 7) return ""
    val month = iso.substring(5, 7).toIntOrNull() ?: return ""
    if (month !in 1..12) return ""
    val yy = iso.substring(2, 4)
    return "Joined ${MONTHS[month - 1]} '$yy"
}

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
