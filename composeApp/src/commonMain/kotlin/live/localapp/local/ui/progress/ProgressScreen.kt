@file:OptIn(ExperimentalMaterial3Api::class)
package live.localapp.local.ui.progress

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import live.localapp.local.ui.common.AppRefreshBox
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import live.localapp.local.ui.common.CircleShape
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import live.localapp.local.domain.model.ActionProgress
import live.localapp.local.domain.model.BadgeAction
import live.localapp.local.platform.hapticSelection
import live.localapp.local.domain.model.StateProgress
import live.localapp.local.domain.model.StatusTier
import live.localapp.local.domain.model.boLevel
import live.localapp.local.domain.model.nextStatusTier
import live.localapp.local.ProfanityFilter
import live.localapp.local.ui.common.AppHeader
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts
import kotlin.random.Random

@Composable
fun ProgressScreen(
    viewModel: ProgressViewModel,
    onLogout: () -> Unit = {},
    onTasteProfile: () -> Unit = {},
    onNotifications: () -> Unit = {},
    onSignIn: () -> Unit = {},
    onNameChanged: (String) -> Unit = {},
    onLocationClick: () -> Unit = {},
    onUpgrade: () -> Unit = {},
    isLoggedIn: Boolean = true,
    locationName: String = "",
    streakDays: Int = 0,
    isUnsupportedState: Boolean = false,
    radiusFilter: Double? = null,
) {
    val state by viewModel.uiState.collectAsState()
    var showNameDialog by remember { mutableStateOf(false) }

    if (showNameDialog) {
        // Same sheet the leaderboard + lobby use, routed through the one save path
        // (LeaderboardViewModel.saveUsername) so it earns FOMO, toasts, and
        // refreshes every screen — including this header, via dataChanged.
        val leaderboardVm = remember {
            org.koin.mp.KoinPlatform.getKoin().get<live.localapp.local.ui.leaderboard.LeaderboardViewModel>()
        }
        live.localapp.local.ui.leaderboard.UsernameSheet(
            initial = state.userName,
            onSave = { name, cb ->
                leaderboardVm.saveUsername(name) { res ->
                    if (res == live.localapp.local.domain.repository.UsernameUpdateResult.Success) onNameChanged(name)
                    cb(res)
                }
            },
            onDismiss = { showNameDialog = false },
        )
    }

    // Use current state directly for display — tier change animation only when lastSeen differs
    var displayTier by remember { mutableStateOf(state.currentTier) }
    var displayNextTier by remember { mutableStateOf(state.nextTier) }
    var animateToPoints by remember { mutableStateOf(viewModel.lastSeenPoints) }
    var animateToProgress by remember { mutableStateOf(viewModel.lastSeenProgress) }
    var showTierCelebration by remember { mutableStateOf(false) }
    var tierAnimationDone by remember { mutableStateOf(false) }

    // Keep display tier in sync with state (handles initial load race)
    LaunchedEffect(state.currentTier) {
        if (!tierAnimationDone) {
            displayTier = state.currentTier
            displayNextTier = state.nextTier
        }
    }

    LaunchedEffect(state.totalPoints) {
        if (state.totalPoints == 0) return@LaunchedEffect // data not loaded yet
        delay(300)

        val lastTier = viewModel.lastSeenTier
        val tierChanged = lastTier != state.currentTier
            && lastTier != StatusTier.NEW_IN_TOWN
            && viewModel.lastSeenPoints > 0 // only animate if we had a real previous session

        if (tierChanged) {
            tierAnimationDone = true
            displayTier = lastTier
            displayNextTier = state.currentTier
            // Step 1: Fill old bar to 100%
            animateToProgress = 1f
            animateToPoints = nextStatusTier(lastTier)?.threshold ?: state.totalPoints
            delay(1000)
            // Step 2: Flash celebration
            showTierCelebration = true
            delay(1200)
            showTierCelebration = false
            // Step 3: Switch to new tier, animate from 0 to current
            displayTier = state.currentTier
            displayNextTier = state.nextTier
            animateToProgress = 0f
            animateToPoints = state.currentTier.threshold
            delay(100)
            animateToProgress = state.tierProgress
            animateToPoints = state.totalPoints
        } else {
            animateToPoints = state.totalPoints
            animateToProgress = state.tierProgress
        }
    }

    // If state updates while on screen, animate immediately
    LaunchedEffect(state.totalPoints) {
        if (tierAnimationDone || viewModel.lastSeenPoints == 0) {
            animateToPoints = state.totalPoints
        }
    }
    LaunchedEffect(state.tierProgress) {
        if (tierAnimationDone || viewModel.lastSeenPoints == 0) {
            animateToProgress = state.tierProgress
        }
    }

    // Save snapshot and collapse states when leaving
    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveLastSeen()
            viewModel.collapseAllStates()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Snow),
        ) {
            AppHeader(
                title = "local",
                locationName = locationName,
                radiusFilter = radiusFilter,
                streakDays = streakDays,
                userInitial = state.userInitial,
                onProfileClick = {},
                onLocationClick = onLocationClick,
                isLoggedIn = isLoggedIn,
                isUnsupportedState = isUnsupportedState,
            )

            if (!isLoggedIn) {
                // ── Logged-out state (Bank 4) ──
                // Big Bo Pop-In, Excited expression, Sign in CTA, fresh copy
                // line per visit. Replaces the old static Edit-icon layout.
                live.localapp.local.ui.emptystate.BoEmptyState(
                    bank = live.localapp.local.ui.emptystate.EmptyStateCopy.ACCOUNT_SIGNED_OUT,
                    ctaLabel = "Sign in",
                    onCta = onSignIn,
                )
            } else {

            if (!state.isLoaded) {
                ProfileSkeletonLoader()
            } else {
            AppRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.refresh() },
                refreshFailed = state.refreshFailed,
                modifier = Modifier.fillMaxSize(),
            ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 120.dp),
            ) {
                // ── Hero Card ──
                HeroCard(state, animateToPoints, animateToProgress, displayTier, displayNextTier, showTierCelebration, onEditName = {
                    showNameDialog = true
                })

                Spacer(Modifier.height(16.dp))

                // Paid users keep the subscription card in its original (lower)
                // spot; trial/free users get it promoted above the stats so the
                // upgrade CTA is the first thing they see. Gate on the *paid*
                // signal — an active trial is not a paid sub, so trial users
                // should still get the promoted placement.
                val isPaidPro = remember { live.localapp.local.ui.chat.PremiumGate().hasPaidSubscription }

                // ── Subscription Status (promoted for non-paid users) ──
                if (!isPaidPro) {
                    SubscriptionSection(trialEndsAt = state.trialEndsAt, onUpgrade = onUpgrade)
                    Spacer(Modifier.height(20.dp))
                }

                // ── Local Leaderboard entry point (flag-gated) ──
                if (live.localapp.local.Config.FEATURE_LEADERBOARD_ENABLED) {
                    live.localapp.local.ui.leaderboard.LeaderboardAccountEntry(
                        remember { org.koin.mp.KoinPlatform.getKoin().get<live.localapp.local.ui.leaderboard.LeaderboardViewModel>() },
                    )
                    Spacer(Modifier.height(20.dp))
                }

                // ── Segment Control: Progress | Tracking ──
                ProgressTrackingCard(state, viewModel)

                Spacer(Modifier.height(24.dp))

                // ── Subscription Status (original placement for paid users) ──
                if (isPaidPro) {
                    SubscriptionSection(trialEndsAt = state.trialEndsAt, onUpgrade = onUpgrade)
                    Spacer(Modifier.height(16.dp))
                }

                // ── Support & Info ──
                SupportSection(
                    onRateApp = { live.localapp.local.platform.requestAppReview() },
                    onNotifications = onNotifications,
                )

                Spacer(Modifier.height(16.dp))

                // ── Log Out ──
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
            } // end PullToRefreshBox
            } // end else (isLoaded)
            } // end else (logged in)
        }

        // Celebrations are rendered globally in AppNavigation
    }
}

// ═══════════════════════════════════════════
// ── Hero Card (gradient banner + avatar) ──
// ═══════════════════════════════════════════

@Composable
private fun ProfileSkeletonLoader() {
    val shimmerAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
    )
    val c = AppColors.Pebble.copy(alpha = 0.3f + shimmerAlpha * 0.2f)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp)) {
        // Hero card skeleton
        Box(Modifier.fillMaxWidth().height(200.dp).background(c, RoundedCornerShape(20.dp)))
        Spacer(Modifier.height(16.dp))
        // Stats row
        Box(Modifier.fillMaxWidth().height(80.dp).background(c, RoundedCornerShape(16.dp)))
        Spacer(Modifier.height(16.dp))
        // Progress rows
        repeat(4) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(40.dp).background(c, RoundedCornerShape(10.dp)))
                Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
                    Box(Modifier.width(140.dp).height(12.dp).background(c, RoundedCornerShape(6.dp)))
                    Box(Modifier.fillMaxWidth().height(8.dp).background(c, RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}

@Composable
private fun HeroCard(
    state: ProgressUiState,
    animateToPoints: Int = state.totalPoints,
    animateToProgress: Float = state.tierProgress,
    displayTier: StatusTier = state.currentTier,
    displayNextTier: StatusTier? = state.nextTier,
    showTierCelebration: Boolean = false,
    onEditName: () -> Unit = {},
) {
    val nextTier = displayNextTier
    val ptsToNext = if (nextTier != null) nextTier.threshold - state.totalPoints else 0

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp)
            .shadow(8.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(
                animateColorAsState(
                    targetValue = if (showTierCelebration) AppColors.Sage else AppColors.Coral,
                    animationSpec = tween(400),
                ).value
            ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
        ) {
            // Avatar + name row — the user's current Bo (blinking) is the avatar.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(AppColors.White.copy(alpha = 0.25f))
                        .border(3.dp, AppColors.White.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    live.localapp.local.ui.character.Bo(
                        size = 56.dp,
                        level = displayTier.boLevel,
                        showSparkles = false,
                        showShadow = false,
                        modifier = Modifier.size(56.dp),
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column {
                    if (state.userName.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { onEditName() },
                        ) {
                            Text(
                                state.userName,
                                style = TextStyle(
                                    fontFamily = AppFonts.Heading,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 22.sp,
                                    color = AppColors.White,
                                    letterSpacing = (-0.5).sp,
                                ),
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = "Edit username",
                                modifier = Modifier.size(14.dp),
                                tint = AppColors.White.copy(alpha = 0.5f),
                            )
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { onEditName() },
                        ) {
                            Text(
                                "Set username",
                                style = TextStyle(
                                    fontFamily = AppFonts.Heading,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 22.sp,
                                    color = AppColors.White.copy(alpha = 0.7f),
                                    letterSpacing = (-0.5).sp,
                                ),
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = "Edit username",
                                modifier = Modifier.size(16.dp),
                                tint = AppColors.White.copy(alpha = 0.7f),
                            )
                        }
                    }
                    Text(
                        formatMemberSince(state.createdAt),
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = AppColors.White.copy(alpha = 0.75f),
                        ),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Tier badge pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(30.dp))
                    .background(AppColors.White.copy(alpha = 0.2f))
                    .border(1.5.dp, AppColors.White.copy(alpha = 0.3f), RoundedCornerShape(30.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(AppColors.White),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        displayTier.label.uppercase(),
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp,
                            color = AppColors.White,
                            letterSpacing = 0.5.sp,
                        ),
                    )
                }
            }

            // Progress section
            if (nextTier != null) {
                Spacer(Modifier.height(18.dp))

                val animatedPoints by animateIntAsState(
                    targetValue = animateToPoints,
                    animationSpec = tween(800, easing = FastOutSlowInEasing),
                )

                // Fire a light selection haptic on each tick while the count ratchets up.
                LaunchedEffect(animatedPoints) {
                    if (animatedPoints != animateToPoints) hapticSelection()
                }

                // Big, prominent current points count
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        formatNumber(animatedPoints),
                        style = TextStyle(
                            fontFamily = AppFonts.Heading,
                            fontWeight = FontWeight.Bold,
                            fontSize = 42.sp,
                            color = AppColors.White,
                            letterSpacing = (-1.5).sp,
                        ),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "/ ${formatNumber(nextTier.threshold)} FOMO",
                        modifier = Modifier.padding(bottom = 8.dp),
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = AppColors.White.copy(alpha = 0.75f),
                        ),
                    )
                }

                Spacer(Modifier.height(10.dp))

                val animatedProgress by animateFloatAsState(
                    targetValue = animateToProgress,
                    animationSpec = tween(1000, easing = FastOutSlowInEasing),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(AppColors.White.copy(alpha = 0.22f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedProgress.coerceAtLeast(0.05f))
                            .height(18.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        AppColors.White.copy(alpha = 0.8f),
                                        AppColors.White,
                                    )
                                )
                            ),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        // Glossy knob riding the leading edge for a playful feel
                        Box(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(AppColors.White)
                                .border(2.dp, AppColors.Coral.copy(alpha = 0.4f), CircleShape),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                val animatedPtsToNext by animateIntAsState(
                    targetValue = if (nextTier != null) nextTier.threshold - animateToPoints else 0,
                    animationSpec = tween(800, easing = FastOutSlowInEasing),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${formatNumber(animatedPtsToNext)} FOMO to",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = AppColors.White.copy(alpha = 0.7f),
                        ),
                    )
                    Spacer(Modifier.width(7.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(30.dp))
                            .background(AppColors.White.copy(alpha = 0.2f))
                            .border(1.dp, AppColors.White.copy(alpha = 0.3f), RoundedCornerShape(30.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(AppColors.White),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                nextTier.label.uppercase(),
                                style = TextStyle(
                                    fontFamily = AppFonts.Body,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 10.sp,
                                    color = AppColors.White,
                                    letterSpacing = 0.5.sp,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ════════════════════════
// ══════════════════════════════════════════
// ── Progress | Earn Card ──
// ══════════════════════════════════════════

@Composable
private fun ProgressTrackingCard(state: ProgressUiState, viewModel: ProgressViewModel) {
    val segments = listOf("Earn FOMO", "Progress")
    val selectedIndex = state.selectedSegment

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        // Segment control
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AppColors.Mist)
                .padding(3.dp),
        ) {
            segments.forEachIndexed { index, label ->
                val isSelected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (isSelected) AppColors.White else Color.Transparent)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { viewModel.selectSegment(index) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp,
                            color = if (isSelected) AppColors.Ink else AppColors.Ink3,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Content
        when (selectedIndex) {
            0 -> GlobalTrackingCard(state, viewModel)
            1 -> JourneyCard(state.currentTier, state.totalPoints)
        }
    }
}

@Composable
private fun GlobalTrackingCard(state: ProgressUiState, viewModel: ProgressViewModel) {
    val milestoneActions = state.globalActions.filter { !it.action.isOneTime && !it.action.isPerAction }
    val perActionRewards = state.globalActions.filter { it.action.isPerAction }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(AppColors.White)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Milestone action rows (Events Saved, Events Shared, Plans Created, Plans Shared)
        milestoneActions.forEach { action ->
            val lastSeenCount = viewModel.getLastSeenActionCount("global", action.action)
            MilestoneRow(action, lastSeenCount)
        }

        if (perActionRewards.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AppColors.Mist),
            )
            Spacer(Modifier.height(4.dp))

            Text(
                "REWARDS",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.sp,
                    color = AppColors.Ink3,
                    letterSpacing = 1.sp,
                ),
                modifier = Modifier.padding(vertical = 6.dp),
            )

            perActionRewards.forEach { action ->
                PerActionRewardRow(action)
            }
        }
    }
}

@Composable
private fun PerActionRewardRow(progress: ActionProgress) {
    val icon = when (progress.action) {
        BadgeAction.INVITE_ACCEPTED -> Icons.Rounded.Check
        BadgeAction.GROUP_5_CONFIRMED -> Icons.Rounded.Groups
        BadgeAction.GROUP_10_CONFIRMED -> Icons.Rounded.Celebration
        else -> Icons.Rounded.Star
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(AppColors.SageLt, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = AppColors.Sage)
        }
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                progress.action.label,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink),
            )
            Text(
                "${progress.action.basePoints.formatPoints()} FOMO each time",
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3),
            )
        }

        // Count
        Text(
            "${progress.currentCount}",
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = AppColors.Ink),
        )
    }
}

private fun Int.formatPoints(): String = when {
    this >= 1000 -> "${this / 1000},${(this % 1000).toString().padStart(3, '0')}"
    else -> toString()
}

@Composable
private fun OneTimeChallengeRow(progress: ActionProgress) {
    val isComplete = progress.completedMilestones > 0
    val icon = when (progress.action) {
        BadgeAction.GROUP_5_CONFIRMED -> Icons.Rounded.Groups
        BadgeAction.GROUP_10_CONFIRMED -> Icons.Rounded.Celebration
        else -> Icons.Rounded.Star
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isComplete) AppColors.SageLt else AppColors.Mist),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isComplete) AppColors.Sage else AppColors.Ink3,
                modifier = Modifier.size(16.dp),
            )
        }

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                progress.action.label,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (isComplete) AppColors.Ink3 else AppColors.Ink,
                ),
            )
            if (!isComplete) {
                Text(
                    "Earn ${formatNumber(progress.action.basePoints)} FOMO",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = AppColors.Sky,
                    ),
                )
            }
        }

        // Status
        if (isComplete) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(AppColors.Sage),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = AppColors.White,
                    modifier = Modifier.size(13.dp),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.Mist)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    "LOCKED",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 10.sp,
                        color = AppColors.Ink3,
                        letterSpacing = 0.5.sp,
                    ),
                )
            }
        }
    }
}

// ── Section Header ──
// ════════════════════════

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(horizontal = 22.dp),
        style = TextStyle(
            fontFamily = AppFonts.Heading,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = AppColors.Ink,
            letterSpacing = (-0.5).sp,
        ),
    )
}

// ════════════════════════════════════════════════
// ── Journey Card (vertical spine with tiers) ──
// ════════════════════════════════════════════════

@Composable
private fun JourneyCard(currentTier: StatusTier, totalPoints: Int) {
    val premiumGate = remember { live.localapp.local.ui.chat.PremiumGate() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .shadow(4.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(AppColors.White)
            .padding(22.dp),
    ) {
        val tiers = StatusTier.entries

        tiers.forEachIndexed { index, tier ->
            val isReached = totalPoints >= tier.threshold
            val isCurrent = tier == currentTier
            val isLast = index == tiers.lastIndex
            val reward = premiumGate.rewardForTier(tier)

            val nextReached = if (index + 1 < tiers.size) totalPoints >= tiers[index + 1].threshold else false
            val rowHeight = if (reward != null) 92.dp else 72.dp
            val stubHeight = (rowHeight - 56.dp) / 2

            Row(
                modifier = Modifier.fillMaxWidth().height(rowHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Spine (Bo node centered, connector stubs above/below) — the Bo
                // is vertically centered so it lines up with the tier name.
                Box(
                    modifier = Modifier.width(56.dp).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    // Connector up to the previous node.
                    if (index != 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .width(2.dp)
                                .height(stubHeight)
                                .background(
                                    if (isReached) AppColors.Sage.copy(alpha = 0.4f)
                                    else AppColors.Pebble.copy(alpha = 0.3f)
                                ),
                        )
                    }
                    // Connector down to the next node.
                    if (!isLast) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .width(2.dp)
                                .height(stubHeight)
                                .background(
                                    if (isReached && nextReached) AppColors.Sage.copy(alpha = 0.4f)
                                    else AppColors.Pebble.copy(alpha = 0.3f)
                                ),
                        )
                    }
                    // Each tier's Bo is the node — full color once reached/current,
                    // faded while locked. The current tier gets a highlight ring.
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .then(
                                if (isCurrent) Modifier
                                    .clip(CircleShape)
                                    .background(AppColors.Coral.copy(alpha = 0.2f))
                                    .border(2.dp, AppColors.Coral, CircleShape)
                                else Modifier
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        live.localapp.local.ui.character.Bo(
                            size = 50.dp,
                            level = tier.boLevel,
                            showSparkles = false,
                            showShadow = false,
                            modifier = Modifier
                                .size(50.dp)
                                .graphicsLayer { alpha = if (isReached || isCurrent) 1f else 0.35f },
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                // Content column
                Column(modifier = Modifier.weight(1f)) {
                    // Tier name + points on same row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            tier.label,
                            style = TextStyle(
                                fontFamily = AppFonts.Heading,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = if (isReached || isCurrent) AppColors.Ink else AppColors.Pebble,
                                letterSpacing = (-0.3).sp,
                            ),
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (tier == StatusTier.NEW_IN_TOWN) "START" else "${formatNumber(tier.threshold)} FOMO",
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (isReached || isCurrent) AppColors.Ink3 else AppColors.Pebble,
                            ),
                            maxLines = 1,
                        )
                    }

                    // Reward pill below tier name
                    if (reward != null) {
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isReached) AppColors.SageLt else AppColors.Mist)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                reward,
                                style = TextStyle(
                                    fontFamily = AppFonts.Body,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 11.sp,
                                    color = if (isReached) AppColors.Sage else AppColors.Ink2,
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════
// ── State Badges Grid (2-column with rings) ──
// ══════════════════════════════════════════════
// TODO(parity-backlog): state badges grid is defined but unwired — confirm
// product intent then either wire under ProgressTrackingCard or delete this block.

@Composable
private fun StateBadgesGrid(
    stateProgresses: List<StateProgress>,
    expandedState: String?,
    onToggle: (String) -> Unit,
    viewModel: ProgressViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Expanded state card (full width)
        val expanded = stateProgresses.firstOrNull { it.state == expandedState }
        if (expanded != null) {
            ExpandedStateCard(expanded, onToggle = { onToggle(expanded.state) }, viewModel = viewModel)
        }

        // 2-column grid for non-expanded states
        val collapsed = stateProgresses.filter { it.state != expandedState }
        val rows = collapsed.chunked(2)
        rows.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                row.forEach { sp ->
                    CollapsedStateCard(
                        stateProgress = sp,
                        onClick = { onToggle(sp.state) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Fill empty space if odd number
                if (row.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CollapsedStateCard(
    stateProgress: StateProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stateName = stateAbbrevToFull(stateProgress.state)
    val progress = stateProgress.completedMilestones.toFloat() / stateProgress.totalMilestones.toFloat()

    Box(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(AppColors.White)
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Radial progress ring
            RadialRing(
                progress = progress,
                numerator = stateProgress.completedMilestones,
                denominator = stateProgress.totalMilestones,
                size = 80,
            )

            Spacer(Modifier.height(14.dp))

            Text(
                stateName,
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = AppColors.Ink,
                    letterSpacing = (-0.3).sp,
                ),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${stateProgress.totalPoints} FOMO",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = AppColors.Sky,
                ),
            )
        }
    }
}

@Composable
private fun ExpandedStateCard(
    stateProgress: StateProgress,
    onToggle: () -> Unit,
    viewModel: ProgressViewModel,
) {
    val stateName = stateAbbrevToFull(stateProgress.state)
    val progress = stateProgress.completedMilestones.toFloat() / stateProgress.totalMilestones.toFloat()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(AppColors.White)
            .clickable(onClick = onToggle),
    ) {
        // Top section with ring + name
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RadialRing(
                progress = progress,
                numerator = stateProgress.completedMilestones,
                denominator = stateProgress.totalMilestones,
                size = 80,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                stateName,
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = AppColors.Ink,
                ),
            )
            Text(
                "${stateProgress.totalPoints} FOMO",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = AppColors.Sky,
                ),
            )
        }

        // Divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AppColors.Snow),
        )

        // Milestone rows
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            stateProgress.actions.forEach { action ->
                val lastSeenCount = viewModel.getLastSeenActionCount(stateProgress.state, action.action)
                MilestoneRow(action, lastSeenCount)
            }
        }
    }
}

@Composable
private fun MilestoneRow(progress: ActionProgress, lastSeenCount: Int = progress.currentCount) {
    val isComplete = progress.nextMilestone == null
    val icon = when (progress.action) {
        BadgeAction.EVENT_SAVED -> Icons.Rounded.BookmarkBorder
        BadgeAction.EVENT_SHARED -> Icons.Rounded.Share
        BadgeAction.PLACE_SAVED -> Icons.Rounded.Place
        BadgeAction.PLAN_CREATED -> Icons.Rounded.CalendarMonth
        BadgeAction.INVITE_ACCEPTED -> Icons.Rounded.Check
        BadgeAction.GROUP_5_CONFIRMED -> Icons.Rounded.Groups
        BadgeAction.GROUP_10_CONFIRMED -> Icons.Rounded.Celebration
    }

    // If lastSeenCount is 0 and we have progress, this is first session — skip animation
    val hasRealLastSeen = lastSeenCount > 0 || progress.currentCount == 0
    val effectiveLastSeen = if (hasRealLastSeen) lastSeenCount else progress.currentCount

    // Detect milestone crossing: did we pass a threshold since last seen?
    val thresholds = live.localapp.local.domain.model.thresholdsFor(progress.action)
    val crossedMilestone = if (hasRealLastSeen) thresholds.lastOrNull { it in (effectiveLastSeen + 1)..progress.currentCount } else null
    val didCrossMilestone = crossedMilestone != null

    // Display milestone: during fill-up animation show old milestone, then switch
    var displayMilestone by remember { mutableStateOf(if (didCrossMilestone) crossedMilestone!! else progress.nextMilestone) }
    var showPointsEarned by remember { mutableStateOf(false) }
    val pointsEarned = if (didCrossMilestone) live.localapp.local.domain.model.pointsForMilestone(progress.action, crossedMilestone!!) else 0

    // Animate count
    var countTarget by remember { mutableStateOf(effectiveLastSeen) }
    val animatedCount by animateIntAsState(
        targetValue = countTarget,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
    )

    // Progress bar
    fun progressFor(count: Int, milestone: Int?): Float {
        if (milestone == null) return 1f
        return (count.toFloat() / milestone.toFloat()).coerceIn(0f, 1f)
    }

    var barTarget by remember { mutableStateOf(progressFor(effectiveLastSeen, displayMilestone)) }
    var barColor by remember { mutableStateOf(AppColors.Sky) }

    val animatedBar by animateFloatAsState(
        targetValue = barTarget,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
    )

    LaunchedEffect(progress.currentCount) {
        delay(300)
        if (didCrossMilestone) {
            // Step 1: Fill to 100% of old milestone
            countTarget = crossedMilestone
            barTarget = 1f
            delay(900)
            // Step 2: Flash green + show points
            barColor = AppColors.Sage
            showPointsEarned = true
            delay(1200)
            showPointsEarned = false
            barColor = AppColors.Sky
            // Step 3: Reset to new milestone
            displayMilestone = progress.nextMilestone
            barTarget = 0f
            countTarget = crossedMilestone
            delay(100)
            // Step 4: Animate to current position
            countTarget = progress.currentCount
            barTarget = progressFor(progress.currentCount, progress.nextMilestone)
        } else {
            countTarget = progress.currentCount
            barTarget = progressFor(progress.currentCount, progress.nextMilestone)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon box
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (isComplete) AppColors.SageLt else AppColors.SkyLt
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isComplete) AppColors.Sage else AppColors.Sky,
                modifier = Modifier.size(16.dp),
            )
        }

        Spacer(Modifier.width(10.dp))

        // Name + progress bar
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    progress.action.label,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = AppColors.Ink,
                    ),
                )
                // Points earned flash
                AnimatedVisibility(
                    visible = showPointsEarned,
                    enter = fadeIn(tween(200)) + expandVertically(),
                    exit = fadeOut(tween(200)) + shrinkVertically(),
                ) {
                    Text(
                        "  +${formatNumber(pointsEarned)} FOMO",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            color = AppColors.Sage,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(5.dp))

            // Mini progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(AppColors.Snow),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedBar.coerceAtLeast(0.01f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isComplete) AppColors.Sage else barColor),
                )
            }

            // Points hint — show current display milestone
            if (!isComplete && displayMilestone != null) {
                val ptsAtDisplay = live.localapp.local.domain.model.pointsForMilestone(progress.action, displayMilestone!!)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${formatNumber(ptsAtDisplay)} FOMO at ${formatNumber(displayMilestone!!)}",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        color = AppColors.Ink3,
                    ),
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        // Count or check
        if (isComplete) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(AppColors.Sage),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = AppColors.White,
                    modifier = Modifier.size(13.dp),
                )
            }
        } else {
            Text(
                "$animatedCount/${displayMilestone ?: progress.nextMilestone}",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    color = AppColors.Ink,
                ),
            )
        }
    }
}

// ════════════════════════
// ── Radial Ring ──
// ════════════════════════

@Composable
private fun RadialRing(
    progress: Float,
    numerator: Int,
    denominator: Int,
    size: Int = 80,
) {
    Box(
        modifier = Modifier.size(size.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size.dp)) {
            val strokeWidth = 6.dp.toPx()
            val arcSize = Size(this.size.width - strokeWidth, this.size.height - strokeWidth)
            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

            // Background ring
            drawArc(
                color = AppColors.Snow,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            // Progress ring
            val sweepColor = if (progress >= 1f) AppColors.Sage else AppColors.Sky
            drawArc(
                color = sweepColor,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$numerator",
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = AppColors.Ink,
                    lineHeight = 20.sp,
                    letterSpacing = (-0.5).sp,
                ),
            )
            Text(
                "/ $denominator",
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

// ═══════════════════════════════════════
// ── Celebration: Milestone Toast ──
// ═══════════════════════════════════════

@Composable
private fun MilestoneToast(
    event: CelebrationEvent.MilestoneEarned,
    onDismiss: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val stateFullName = stateAbbrevToFull(event.state)

    LaunchedEffect(Unit) {
        visible = true
        delay(3000)
        visible = false
        delay(300)
        onDismiss()
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { -it * 2 },
            animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        ) + fadeIn(),
        exit = slideOutVertically(
            targetOffsetY = { -it * 2 },
            animationSpec = tween(250),
        ) + fadeOut(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 56.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(12.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(AppColors.Ink)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Icon box
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColors.CoralLt.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("\uD83C\uDF89", fontSize = 20.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${event.milestone} ${event.action.label} in $stateFullName!",
                        style = TextStyle(
                            fontFamily = AppFonts.Heading,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = AppColors.White,
                            letterSpacing = (-0.3).sp,
                        ),
                    )
                    Text(
                        "+${event.points} FOMO",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            color = AppColors.Coral,
                            letterSpacing = 0.5.sp,
                        ),
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════
// ── Celebration: Level Up Overlay ──
// ═══════════════════════════════════════

@Composable
private fun LevelUpOverlay(
    event: CelebrationEvent.StatusLevelUp,
    onDismiss: () -> Unit,
) {
    val scale = remember { Animatable(0.6f) }
    val alpha = remember { Animatable(0f) }
    val particleProgress = remember { Animatable(0f) }

    data class Particle(
        val x: Float, val y: Float,
        val dx: Float, val dy: Float,
        val color: Color, val size: Float,
    )

    val particles = remember {
        mutableStateListOf<Particle>().apply {
            repeat(60) {
                val angle = Random.nextFloat() * 360f
                val speed = Random.nextFloat() * 8f + 3f
                add(
                    Particle(
                        x = 0.5f, y = 0.4f,
                        dx = kotlin.math.cos(angle * kotlin.math.PI.toFloat() / 180f) * speed,
                        dy = kotlin.math.sin(angle * kotlin.math.PI.toFloat() / 180f) * speed,
                        color = listOf(
                            AppColors.Coral, AppColors.Sun, AppColors.Sage,
                            AppColors.Sky, AppColors.Plum, AppColors.Orange,
                        ).random(),
                        size = Random.nextFloat() * 8f + 4f,
                    )
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(300))
        scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow))
        particleProgress.animateTo(1f, tween(1500, easing = LinearEasing))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1523).copy(alpha = alpha.value * 0.95f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Particles
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = particleProgress.value
            particles.forEach { p ->
                val px = (p.x + p.dx * t * 0.05f) * size.width
                val py = (p.y + p.dy * t * 0.05f + t * t * 0.15f) * size.height
                val pAlpha = (1f - t).coerceIn(0f, 1f)
                drawCircle(
                    color = p.color.copy(alpha = pAlpha),
                    radius = p.size,
                    center = Offset(px, py),
                )
            }
        }

        Column(
            modifier = Modifier
                .scale(scale.value)
                .alpha(alpha.value),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "LEVEL UP",
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = AppColors.Coral,
                    letterSpacing = 4.sp,
                ),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                event.newTier.label,
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Black,
                    fontSize = 64.sp,
                    color = AppColors.White,
                    letterSpacing = (-2).sp,
                    lineHeight = 64.sp,
                ),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "${event.totalPoints} total FOMO",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = AppColors.White.copy(alpha = 0.6f),
                ),
            )
            Spacer(Modifier.height(32.dp))

            // Share button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(30.dp))
                    .background(AppColors.Coral)
                    .clickable { onDismiss() }
                    .padding(horizontal = 40.dp, vertical = 16.dp),
            ) {
                Text(
                    "Share Achievement",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        color = AppColors.White,
                        letterSpacing = 0.3.sp,
                    ),
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "TAP ANYWHERE TO DISMISS",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = AppColors.White.copy(alpha = 0.35f),
                    letterSpacing = 0.5.sp,
                ),
            )
        }
    }
}

// ═══════════════════════════════════════
// ── Celebration: New State Welcome ──
// ═══════════════════════════════════════

@Composable
private fun NewStateWelcome(
    event: CelebrationEvent.NewState,
    onDismiss: () -> Unit,
) {
    val stateFullName = stateAbbrevToFull(event.state)
    val alpha = remember { Animatable(0f) }
    val cardOffset = remember { Animatable(1f) } // 1 = offscreen bottom, 0 = visible

    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(200))
        cardOffset.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1523).copy(alpha = alpha.value * 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    // dismiss
                    onDismiss()
                },
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = (cardOffset.value * 400).dp)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(AppColors.White)
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Pin icon
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(AppColors.CoralLt.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("\uD83D\uDCCD", fontSize = 28.sp)
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "WELCOME TO",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    color = AppColors.Coral,
                    letterSpacing = 3.sp,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stateFullName,
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Black,
                    fontSize = 34.sp,
                    color = AppColors.Ink,
                    letterSpacing = (-1).sp,
                ),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Start exploring to earn milestones",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = AppColors.Ink3,
                ),
            )
            Spacer(Modifier.height(28.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(30.dp))
                    .background(AppColors.Ink)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 48.dp, vertical = 16.dp),
            ) {
                Text(
                    "Let's Go",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        color = AppColors.White,
                    ),
                )
            }
        }
    }
}

// ════════════════════════
// ── Helpers ──
// ════════════════════════

private fun formatNumber(n: Int): String {
    if (n < 1000) return n.toString()
    val s = n.toString()
    val sb = StringBuilder()
    s.forEachIndexed { i, c ->
        if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
        sb.append(c)
    }
    return sb.toString()
}

private fun stateAbbrevToFull(abbrev: String): String = when (abbrev.uppercase()) {
    "MA" -> "Massachusetts"
    "ME" -> "Maine"
    "NH" -> "New Hampshire"
    "VT" -> "Vermont"
    "RI" -> "Rhode Island"
    "CT" -> "Connecticut"
    "NY" -> "New York"
    "NJ" -> "New Jersey"
    "PA" -> "Pennsylvania"
    "CA" -> "California"
    "TX" -> "Texas"
    "FL" -> "Florida"
    "IL" -> "Illinois"
    "OH" -> "Ohio"
    "GA" -> "Georgia"
    "NC" -> "North Carolina"
    "MI" -> "Michigan"
    "WA" -> "Washington"
    "CO" -> "Colorado"
    "OR" -> "Oregon"
    "TN" -> "Tennessee"
    "VA" -> "Virginia"
    "MD" -> "Maryland"
    "MN" -> "Minnesota"
    "WI" -> "Wisconsin"
    "SC" -> "South Carolina"
    "MO" -> "Missouri"
    "IN" -> "Indiana"
    "DC" -> "Washington D.C."
    else -> abbrev
}

// ═══════════════════════════════════════════════════════════════════
// ACCOUNT SECTIONS
// ═══════════════════════════════════════════════════════════════════

/**
 * Days left on the app-side Local Pro trial (server-owned `trial_ends_at`).
 * Null when there's no trial, it's unparseable, or it's expired.
 *
 * Nearest-day rounding, floored at 1 while active (matches HomeScreen's
 * trialDaysRemaining) — ceil against the device clock overstated a
 * server-anchored N-day trial as N+1 under sub-day clock skew.
 */
private fun subscriptionTrialDaysRemaining(trialEndsAt: String?): Int? {
    if (trialEndsAt.isNullOrBlank()) return null
    val endMs = try {
        kotlinx.datetime.Instant.parse(trialEndsAt).toEpochMilliseconds()
    } catch (_: Exception) { return null }
    val nowMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
    if (endMs <= nowMs) return null
    return kotlin.math.max(1, kotlin.math.round((endMs - nowMs) / 1000.0 / 86_400.0).toInt())
}

@Composable
private fun SubscriptionSection(trialEndsAt: String?, onUpgrade: () -> Unit) {
    val gate = remember { live.localapp.local.ui.chat.PremiumGate() }
    var introPrice by remember { mutableStateOf<String?>(null) }
    var monthlyPrice by remember { mutableStateOf("$0.99") }
    LaunchedEffect(Unit) {
        live.localapp.local.platform.fetchSubscriptionPricing { m, _, i ->
            if (m != null) monthlyPrice = m
            introPrice = i
        }
    }
    // Three states: paid (RevenueCat) → active trial (server trial_ends_at) → free.
    // ProgressViewModel loads trialEndsAt once on init, and days-left is keyed on
    // that (unchanging) end-date string — so bump a tick on each ON_RESUME to make
    // the countdown re-evaluate against the current date instead of staying frozen.
    var trialResumeTick by remember { mutableStateOf(0) }
    val trialLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(trialLifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) trialResumeTick++
        }
        trialLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { trialLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val trialDaysLeft = remember(trialEndsAt, trialResumeTick) { subscriptionTrialDaysRemaining(trialEndsAt) }
    // Branch on the *paid* signal, not isPremium: isPremium is true during an
    // active trial too, which would mislabel a trialing user as paid "Active".
    val onTrial = !gate.hasPaidSubscription && trialDaysLeft != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .background(Color.White, RoundedCornerShape(16.dp))
            .border(1.dp, AppColors.Pebble, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text("SUBSCRIPTION", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = AppColors.Ink3, letterSpacing = 1.sp))
        Spacer(Modifier.height(10.dp))

        if (gate.hasPaidSubscription) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Local Pro", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppColors.Coral))
                Spacer(Modifier.width(8.dp))
                Text("Active", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = Color(0xFF2D9F73)),
                    modifier = Modifier.background(Color(0xFFE8F5E9), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp))
            }
            val expiry = gate.paidSubscriptionExpiry
            if (expiry.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("Renews $expiry", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Manage Subscription",
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.Sky),
                modifier = Modifier.clickable { live.localapp.local.platform.openAppSettings() },
            )
        } else if (onTrial) {
            val days = trialDaysLeft!!
            val dayLabel = if (days == 1) "day" else "days"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Local Pro Trial", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppColors.Coral))
                Spacer(Modifier.width(8.dp))
                Text("$days $dayLabel left", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = AppColors.CoralDk),
                    modifier = Modifier.background(AppColors.CoralLt, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "You have full access to Local Pro. Keep it for ${monthlyPrice}/mo.",
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
            )
            Spacer(Modifier.height(10.dp))
            // Chunky 3D "Duolingo" CTA — a deeper-coral edge peeks 4.dp below the
            // face with a matching stroke, the shared house-style depth.
            val upgradeEdge = Color(0xFFC94A4A)
            Box(Modifier.fillMaxWidth().height(52.dp)) {
                // 3D lip — behind the face, peeking 4.dp below it.
                Box(
                    Modifier.fillMaxWidth().height(48.dp).align(Alignment.BottomCenter)
                        .clip(RoundedCornerShape(12.dp)).background(upgradeEdge),
                )
                // Face.
                Box(
                    modifier = Modifier
                        .fillMaxWidth().height(48.dp).align(Alignment.TopCenter)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColors.Coral)
                        .border(1.5.dp, upgradeEdge, RoundedCornerShape(12.dp))
                        .clickable(onClick = onUpgrade),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Upgrade to Local Pro", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color.White))
                }
            }
        } else {
            Text("Free Plan", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppColors.Ink))
            Spacer(Modifier.height(4.dp))
            Text(
                if (introPrice != null) "Start today for ${introPrice}/mo" else "Upgrade for ${monthlyPrice}/mo",
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.Ink3),
            )
            Spacer(Modifier.height(10.dp))
            // Chunky 3D "Duolingo" CTA — a deeper-coral edge peeks 4.dp below the
            // face with a matching stroke, the shared house-style depth.
            val upgradeEdge = Color(0xFFC94A4A)
            Box(Modifier.fillMaxWidth().height(52.dp)) {
                // 3D lip — behind the face, peeking 4.dp below it.
                Box(
                    Modifier.fillMaxWidth().height(48.dp).align(Alignment.BottomCenter)
                        .clip(RoundedCornerShape(12.dp)).background(upgradeEdge),
                )
                // Face.
                Box(
                    modifier = Modifier
                        .fillMaxWidth().height(48.dp).align(Alignment.TopCenter)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColors.Coral)
                        .border(1.5.dp, upgradeEdge, RoundedCornerShape(12.dp))
                        .clickable(onClick = onUpgrade),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Upgrade to Local Pro", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color.White))
                }
            }
        }
    }
}

@Composable
private fun SupportSection(onRateApp: () -> Unit, onNotifications: () -> Unit) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .background(Color.White, RoundedCornerShape(16.dp))
            .border(1.dp, AppColors.Pebble, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text("SUPPORT & INFO", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = AppColors.Ink3, letterSpacing = 1.sp))
        Spacer(Modifier.height(10.dp))

        SupportRow("Notifications") { onNotifications() }
        Spacer(Modifier.height(10.dp))
        SupportRow("Help & Support") { live.localapp.local.platform.composeSupportEmail("support@localapp.live") }
        Spacer(Modifier.height(10.dp))
        SupportRow("Rate Local") { onRateApp() }
        Spacer(Modifier.height(10.dp))
        SupportRow("Share the App") {
            live.localapp.local.platform.presentShareSheet("Check out Local — discover events and plan nights out!", "https://localapp.live")
        }
        Spacer(Modifier.height(10.dp))
        SupportRow("Terms of Service") { uriHandler.openUri("https://localapp.live/terms") }
        Spacer(Modifier.height(10.dp))
        SupportRow("Privacy Policy") { uriHandler.openUri("https://localapp.live/privacy-policy") }
        // Apple requires an in-app account-deletion path; we route to contact-support. iOS only.
        if (live.localapp.local.platform.isIOS) {
            Spacer(Modifier.height(10.dp))
            SupportRow("Delete Account") { uriHandler.openUri("https://www.localapp.live/contact-support") }
        }
        Spacer(Modifier.height(10.dp))

        Text(
            live.localapp.local.platform.getAppVersion(),
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun SupportRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink), modifier = Modifier.weight(1f))
        Text("\u203A", style = TextStyle(fontSize = 18.sp, color = AppColors.Ink3))
    }
}

// \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
// \u2500\u2500 "Member since" formatter (KMP-friendly, no JDK) \u2500\u2500
// \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
//
// Accepts a Postgres-style timestamp ("2026-03-14T15:09:26.123456+00:00"),
// an ISO-8601 instant, or any string starting with "yyyy-MM" and renders
// "Member since Mar 26" (MMM YY). Falls back to "Member since recently" when
// the input is null/blank/unparseable so the UI never shows a hardcoded year.
private fun formatMemberSince(createdAt: String?): String {
    if (createdAt.isNullOrBlank()) return "Member since recently"
    // Cheap parse \u2014 pluck the leading "yyyy-MM" and look up the month name.
    // Avoids platform-specific java.time / NSDateFormatter divergence.
    val ym = createdAt.take(7) // "2026-03"
    val parts = ym.split('-')
    if (parts.size < 2) return "Member since recently"
    val year = parts[0].toIntOrNull() ?: return "Member since recently"
    val month = parts[1].toIntOrNull() ?: return "Member since recently"
    if (month !in 1..12) return "Member since recently"
    val monthName = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )[month - 1]
    val yy = (year % 100).toString().padStart(2, '0')
    return "Member since $monthName $yy"
}
