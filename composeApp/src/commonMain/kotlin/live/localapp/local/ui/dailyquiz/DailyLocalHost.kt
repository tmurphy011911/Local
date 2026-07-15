package live.localapp.local.ui.dailyquiz

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import live.localapp.local.domain.repository.LeaderboardScope
import live.localapp.local.domain.service.BadgeService
import live.localapp.local.feature.onboarding.OnboardingViewModel
import live.localapp.local.platform.presentNativeDailyQuiz
import live.localapp.local.platform.presentShareSheet
import live.localapp.local.ui.common.pressable
import live.localapp.local.ui.leaderboard.LeaderboardHost
import live.localapp.local.ui.leaderboard.LeaderboardViewModel
import live.localapp.local.ui.leaderboard.UsernameSheet
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts
import live.localapp.local.ui.theme.AppType
import org.koin.mp.KoinPlatform

/** "Xh Ym" until local midnight, for the "resets in …" copy on the done card. */
private fun resetInLabel(): String {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val minsLeft = (24 * 60) - (now.hour * 60 + now.minute)
    val h = minsLeft / 60
    val m = minsLeft % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/**
 * The Home "lobby" carousel: Daily Local's entry CTA beside the Local Leaderboard
 * CTA — structural twins, identical size, seamless swipe. Daily Local persists at
 * position 1 all day: a "start" state until played, then a "done" state showing
 * the score, streak, and reset countdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyLocalHost(
    quizVm: DailyQuizViewModel,
    leaderboardVm: LeaderboardViewModel,
    showLeaderboard: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val s by quizVm.uiState.collectAsState()
    val lb by leaderboardVm.uiState.collectAsState()
    var showSheet by remember { mutableStateOf(false) }
    var showUsernameDialog by remember { mutableStateOf(false) }
    var showPreferencesFlow by remember { mutableStateOf(false) }
    // True while a FRESH play is in progress, so closing the drawer afterwards
    // (not a re-view of a stored result) fires the "you earned FOMO" toast once.
    var pendingQuizToast by remember { mutableStateOf(false) }

    val badgeService = remember { KoinPlatform.getKoin().get<BadgeService>() }
    // Toast the FOMO earned today when the drawer closes after a fresh play.
    val fireQuizToast = {
        if (pendingQuizToast) {
            pendingQuizToast = false
            val earned = quizVm.uiState.value.quiz?.completion?.score ?: 0
            if (earned > 0) badgeService.emitFomoToast("Daily Local complete", earned)
        }
    }

    // The VM is an app-scoped singleton that fetches once at creation, so an
    // account switch or a server-side home_state fix wouldn't show until a cold
    // restart. Re-fetch whenever the lobby (re)enters the Home screen. Also warm
    // the leaderboard here (not just inside its card) so we know whether it has
    // anything to show BEFORE deciding to give it a carousel slot.
    LaunchedEffect(Unit) { quizVm.refresh(); leaderboardVm.prefetchAll() }

    val startQuiz = {
        pendingQuizToast = true
        val quizJson = quizVm.currentQuizJson()
        val presentedNatively = quizJson != null && presentNativeDailyQuiz(
            quizJson = quizJson,
            onSubmit = { answersJson, done -> quizVm.submitAnswersJson(answersJson, done) },
            onDismissed = { quizVm.refresh(); fireQuizToast() },
        )
        if (!presentedNatively) {
            quizVm.begin()
            showSheet = true
        }
    }

    // The leaderboard CTA hides itself at zero FOMO — a brand-new user isn't on
    // any board yet (matches LeaderboardHost's hideAtZeroFomo). Only give it a
    // carousel slot when it will actually render; otherwise the pager gains a
    // phantom empty page you can swipe to, with dots for a single real item.
    val lbMe = lb.byScope[LeaderboardScope.COUNTRY]?.me
        ?: lb.byScope[LeaderboardScope.STATE]?.me
        ?: lb.byScope[LeaderboardScope.CITY]?.me
    val leaderboardVisible = lbMe != null && lbMe.fomoTotal > 0

    // One-shot FOMO CTAs. Gated on profileLoaded so an existing user (custom
    // username / prefs already done) never flashes a card before their profile
    // read lands. Each disappears for good once its server flag flips true.
    val usernameNeeded = showLeaderboard && lb.profileLoaded && !lb.usernameIsCustom
    val prefsNeeded = lb.profileLoaded && !lb.preferencesCompleted

    val hasQuiz = s.showCard
    val pages = buildList {
        // Not done today: quiz P1, then username, preferences, leaderboard.
        // Done today: username, preferences, leaderboard, then the quiz's
        // countdown/"done" state slides to the back.
        if (hasQuiz && !s.alreadyCompleted) add("quiz")
        if (usernameNeeded) add("username")
        if (prefsNeeded) add("preferences")
        if (showLeaderboard && leaderboardVisible) add("leaderboard")
        if (hasQuiz && s.alreadyCompleted) add("quiz")
    }
    val pageCount = pages.size
    val pagerState = rememberPagerState(pageCount = { pageCount })

    Column(modifier.fillMaxWidth()) {
        if (pageCount > 0) HorizontalPager(state = pagerState) { page ->
            when (pages.getOrNull(page)) {
                "quiz" -> DailyLocalCard(
                    played = s.alreadyCompleted,
                    streak = s.streak,
                    score = s.quiz?.completion?.score ?: 0,
                    correctCount = s.quiz?.completion?.correctCount ?: 0,
                    onTap = {
                        if (s.alreadyCompleted) {
                            quizVm.viewStoredResults(); showSheet = true
                        } else startQuiz()
                    },
                )
                "username" -> LobbyFomoCard(
                    wordmarkLead = "add your ",
                    wordmarkAccent = "username",
                    accent = AppColors.OrangeDk,
                    tileBg = AppColors.OrangeLt,
                    border = AppColors.Orange,
                    icon = Icons.Filled.Person,
                    onTap = { showUsernameDialog = true },
                )
                "preferences" -> LobbyFomoCard(
                    wordmarkLead = "select your ",
                    wordmarkAccent = "preferences",
                    accent = AppColors.SkyDk,
                    tileBg = AppColors.SkyLt,
                    border = AppColors.Sky,
                    icon = Icons.Filled.Star,
                    onTap = { showPreferencesFlow = true },
                )
                else -> LeaderboardHost(leaderboardVm)
            }
        }

        if (pageCount > 1) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                repeat(pageCount) { i ->
                    Box(
                        Modifier.padding(horizontal = 3.dp).size(if (i == pagerState.currentPage) 8.dp else 6.dp)
                            .clip(CircleShape)
                            // Active page = very dark grey; others = medium dark grey.
                            .background(if (i == pagerState.currentPage) AppColors.Ink2 else AppColors.Ink3),
                    )
                }
            }
        }
    }

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSheet = false; quizVm.endSession(); fireQuizToast() },
            sheetState = sheetState,
            containerColor = AppColors.Snow,
        ) {
            DailyQuizSheetContent(
                vm = quizVm,
                onShare = { text -> presentShareSheet(text, "") },
            )
        }
    }

    if (showUsernameDialog) {
        UsernameSheet(
            initial = lb.username ?: "",
            onSave = leaderboardVm::saveUsername,
            onDismiss = { showUsernameDialog = false },
        )
    }

    if (showPreferencesFlow) {
        // Fresh OnboardingViewModel per open so beginPreferencesOnly() starts
        // clean. Full-height sheet ≈ full-screen takeover (the scrim covers the
        // tab bar). Completing flips preferences_completed server-side (awards
        // FOMO); reload the profile so the CTA drops out of the carousel.
        val prefsVm = remember { KoinPlatform.getKoin().get<OnboardingViewModel>() }
        val prefsSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showPreferencesFlow = false },
            sheetState = prefsSheet,
            containerColor = AppColors.Snow,
            dragHandle = null,
        ) {
            PreferencesFlowContent(
                vm = prefsVm,
                onComplete = {
                    showPreferencesFlow = false
                    leaderboardVm.onProfileChanged()
                    // Refresh the account screen's FOMO/stats too, then celebrate.
                    badgeService.notifyDataChanged()
                    badgeService.emitFomoToast("Preferences saved", 1000)
                },
                onClose = { showPreferencesFlow = false },
                modifier = Modifier.fillMaxHeight(),
            )
        }
    }
}

/**
 * The Daily Local entry CTA — a structural twin of the leaderboard CTA
 * (same 20/16/14.dp paddings, 16.dp corners, 1.5.dp border, 38.dp tile,
 * 20.sp wordmark + 12.sp subtitle), in coral. Two states: "start" (chevron) and
 * "done" (score pill + streak + reset countdown).
 */
@Composable
private fun DailyLocalCard(played: Boolean, streak: Int, score: Int, correctCount: Int, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .fillMaxWidth()
            .pressable(onClick = onTap, scale = 0.94f)
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.White, RoundedCornerShape(16.dp))
            .border(1.5.dp, AppColors.Sun, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(AppColors.SunLt),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.DateRange, contentDescription = null, tint = AppColors.SunDk, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    // "daily" + "oca" black; only local's two l's are yellow.
                    append("daily ")
                    withStyle(SpanStyle(color = AppColors.SunDk)) { append("l") }
                    append("oca")
                    withStyle(SpanStyle(color = AppColors.SunDk)) { append("l") }
                },
                style = AppType.Logo.copy(fontSize = 20.sp, color = AppColors.Ink),
            )
            val subtitle = when {
                played -> "$correctCount/3 · Play again in ${resetInLabel()}"
                streak > 0 -> "Extend streak to ${streak + 1} days"
                else -> "3 questions · 30 seconds · Start your streak"
            }
            Text(
                subtitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
            )
        }
        Spacer(Modifier.width(8.dp))
        if (played) {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(AppColors.SageLt).padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("+$score", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 14.sp, color = AppColors.SageDk))
            }
        } else {
            Text("›", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = AppColors.SunDk))
        }
    }
}

/**
 * A one-shot FOMO CTA — a structural twin of the Daily Local / leaderboard cards
 * (same 20/16/14.dp paddings, 16.dp corners, 1.5.dp border, 38.dp tile, 20.sp
 * two-tone wordmark). Used for "set your username" (orange) and "your
 * preferences" (blue); both advertise the 1,000 FOMO reward and vanish for good
 * once earned.
 */
@Composable
private fun LobbyFomoCard(
    wordmarkLead: String,
    wordmarkAccent: String,
    accent: Color,
    tileBg: Color,
    border: Color,
    icon: ImageVector,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .fillMaxWidth()
            .pressable(onClick = onTap, scale = 0.94f)
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.White, RoundedCornerShape(16.dp))
            .border(1.5.dp, border, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(tileBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    append(wordmarkLead)
                    withStyle(SpanStyle(color = accent)) { append(wordmarkAccent) }
                },
                style = AppType.Logo.copy(fontSize = 20.sp, color = AppColors.Ink),
            )
            Text(
                "Earn 1,000 FOMO",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("›", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = accent))
    }
}
