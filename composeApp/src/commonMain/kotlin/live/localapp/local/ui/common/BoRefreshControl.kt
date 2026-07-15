package live.localapp.local.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import live.localapp.local.Config
import live.localapp.local.platform.hapticImpactLight
import live.localapp.local.platform.hapticImpactMedium
import live.localapp.local.platform.hapticNotifyError
import live.localapp.local.platform.hapticNotifySuccess
import live.localapp.local.platform.isReduceMotionEnabled
import live.localapp.local.ui.character.Bo
import live.localapp.local.ui.character.BoExpression
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Drop-in replacement for [PullToRefreshBox] used by the app's top-level feeds.
 * When [Config.FEATURE_BO_PTR_ENABLED] is on it renders the Bo mascot control;
 * otherwise it falls back to the stock Material3 spinner. The `content` keeps
 * the same `BoxScope` receiver as `PullToRefreshBox`, so a call site only swaps
 * the function name.
 *
 * @param refreshFailed forwarded to [BoRefreshControl] to pick the settle
 *   haptic; ignored by the spinner fallback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    refreshFailed: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    if (Config.FEATURE_BO_PTR_ENABLED) {
        BoRefreshControl(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = modifier,
            refreshFailed = refreshFailed,
        ) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    } else {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = modifier,
            content = content,
        )
    }
}

/**
 * Bo pull-to-refresh — the coral-mascot loading indicator that replaces the
 * generic Material3 spinner on the app's top-level feeds. See the "Bo
 * Pull-to-Refresh" developer spec; this is the single shared control every
 * feed reuses, differing only in its [onRefresh] callback.
 *
 * State machine: idle → pulling → ready → refreshing → settle → idle, with a
 * snap-back branch when released below the trigger threshold. Bo holds still
 * while the finger drags (only fading in + scaling up), and only starts his
 * squish/float bounce loop once a refresh actually fires on release.
 *
 * The gesture is hand-rolled over [NestedScrollConnection] so it (a) arms only
 * when the inner scroll container is already at the top — leftover downward
 * scroll bubbles up here exactly then — and (b) matches the prototype physics
 * precisely: 0.5 rubber-band resistance, 72dp trigger threshold, 104dp locked
 * tray, and a once-per-drag threshold haptic latch that Material3's control
 * can't express.
 *
 * @param isRefreshing driven by the screen's ViewModel; the tray stays up
 *   until this clears AND the [MIN_DISPLAY_MS] floor has elapsed, so the bounce
 *   always reads even when the fetch is faster.
 * @param onRefresh fires the real data-fetch on release past threshold. This is
 *   the whole point — the gesture must actually refetch, not just animate.
 * @param refreshFailed picks the settle haptic (error vs. success). The result
 *   toast + NEW ribbons are owned by the screen, not this control.
 */
@Composable
fun BoRefreshControl(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    refreshFailed: Boolean = false,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { TRIGGER_THRESHOLD.dp.toPx() }
    val maxPullPx = with(density) { MAX_PULL.dp.toPx() }
    val lockedPx = with(density) { LOCKED_HEIGHT.dp.toPx() }
    val opacityDivPx = with(density) { OPACITY_DIVISOR.dp.toPx() }
    val reduceMotion = remember { isReduceMotionEnabled() }

    var phase by remember { mutableStateOf(Phase.Idle) }
    // Raw finger travel past the top; display pull = overscroll * resistance.
    var overscrollPx by remember { mutableStateOf(0f) }
    var latched by remember { mutableStateOf(false) }
    var minDisplayDone by remember { mutableStateOf(false) }

    val pullPx = (overscrollPx * PULL_RESISTANCE).coerceIn(0f, maxPullPx)
    // Animatable that owns the tray height for every non-drag transition
    // (lock at 104, snap-back to 0, post-fetch collapse).
    val trayAnim = remember { Animatable(0f) }

    val trayHeightPx = when (phase) {
        Phase.Pulling, Phase.Ready -> pullPx
        Phase.Idle -> 0f
        else -> trayAnim.value // Refreshing / SnapBack / Settling
    }

    val connection = remember(enabled, thresholdPx, maxPullPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!enabled) return Offset.Zero
                // Consume upward drag into shrinking an existing pull before the
                // list itself scrolls, so releasing the pull feels 1:1.
                if (source == NestedScrollSource.Drag && available.y < 0 && overscrollPx > 0f &&
                    (phase == Phase.Pulling || phase == Phase.Ready)
                ) {
                    val consume = (-available.y).coerceAtMost(overscrollPx)
                    overscrollPx -= consume
                    updateDragPhase(
                        pullPx = (overscrollPx * PULL_RESISTANCE).coerceIn(0f, maxPullPx),
                        thresholdPx = thresholdPx,
                        setPhase = { phase = it },
                        latched = latched,
                        onLatch = { latched = true },
                    )
                    return Offset(0f, -consume)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!enabled) return Offset.Zero
                // Downward scroll the child didn't use => the list is at the top
                // and the user is pulling. Accumulate it as overscroll.
                if (source == NestedScrollSource.Drag && available.y > 0f &&
                    phase != Phase.Refreshing && phase != Phase.Settling
                ) {
                    overscrollPx = (overscrollPx + available.y)
                        .coerceAtMost(maxPullPx / PULL_RESISTANCE)
                    updateDragPhase(
                        pullPx = (overscrollPx * PULL_RESISTANCE).coerceIn(0f, maxPullPx),
                        thresholdPx = thresholdPx,
                        setPhase = { phase = it },
                        latched = latched,
                        onLatch = { latched = true },
                    )
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // Drag ended (fires even at zero velocity) — decide the release.
                if (!enabled || overscrollPx <= 0f) return Velocity.Zero
                val released = (overscrollPx * PULL_RESISTANCE).coerceIn(0f, maxPullPx)
                latched = false
                return if (released >= thresholdPx) {
                    hapticImpactLight()
                    phase = Phase.Refreshing
                    onRefresh()
                    available // consume so the list doesn't fling
                } else if (released > 0f) {
                    phase = Phase.SnapBack
                    available
                } else {
                    Velocity.Zero
                }
            }
        }
    }

    // Tray height transitions for every non-drag phase.
    LaunchedEffect(phase) {
        when (phase) {
            Phase.Refreshing -> {
                trayAnim.snapTo(pullPx.coerceAtLeast(0f))
                trayAnim.animateTo(lockedPx, tween(LOCK_MS, easing = LinearEasing))
            }
            Phase.SnapBack -> {
                trayAnim.snapTo(pullPx)
                trayAnim.animateTo(0f, tween(COLLAPSE_MS, easing = CollapseEasing))
                overscrollPx = 0f
                phase = Phase.Idle
            }
            Phase.Settling -> {
                trayAnim.animateTo(0f, tween(COLLAPSE_MS, easing = CollapseEasing))
                overscrollPx = 0f
                phase = Phase.Idle
            }
            Phase.Idle -> {
                overscrollPx = 0f
                latched = false
                trayAnim.snapTo(0f)
            }
            else -> {}
        }
    }

    // Min-display floor: keep Bo up for at least MIN_DISPLAY_MS after a refresh
    // starts so the bounce reads even when the fetch resolves instantly.
    LaunchedEffect(phase) {
        if (phase == Phase.Refreshing) {
            minDisplayDone = false
            delay(MIN_DISPLAY_MS)
            minDisplayDone = true
        }
    }
    // Settle once the fetch is done AND the floor has passed.
    LaunchedEffect(phase, isRefreshing, minDisplayDone) {
        if (phase == Phase.Refreshing && minDisplayDone && !isRefreshing) {
            if (refreshFailed) hapticNotifyError() else hapticNotifySuccess()
            phase = Phase.Settling
        }
    }

    Box(modifier.nestedScroll(connection)) {
        // Content, pushed down by the live tray height.
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(0, trayHeightPx.roundToInt()) },
        ) {
            content()
        }
        // The Bo tray, occupying the exposed strip at the top.
        BoRefreshTray(
            phase = phase,
            trayHeightPx = trayHeightPx,
            pullPx = pullPx,
            thresholdPx = thresholdPx,
            lockedPx = lockedPx,
            opacityDivPx = opacityDivPx,
            reduceMotion = reduceMotion,
            density = density,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/** The coral Bo indicator drawn in the exposed tray strip. */
@Composable
private fun BoRefreshTray(
    phase: Phase,
    trayHeightPx: Float,
    pullPx: Float,
    thresholdPx: Float,
    lockedPx: Float,
    opacityDivPx: Float,
    reduceMotion: Boolean,
    density: androidx.compose.ui.unit.Density,
    modifier: Modifier = Modifier,
) {
    if (trayHeightPx <= 0.5f) return
    val trayHeightDp = with(density) { trayHeightPx.toDp() }

    // Refreshing bounce loop — squish (scaleX/scaleY wobble) + vertical float.
    val bounce = rememberInfiniteTransition(label = "bo-ptr-bounce")
    val bouncePhase by bounce.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(BOUNCE_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "bo-ptr-bounce-phase",
    )

    val boAlpha: Float
    var boScale = 1f
    var squishX = 1f
    var squishY = 1f
    var floatDy = 0f

    when (phase) {
        Phase.Pulling, Phase.Ready -> {
            boAlpha = if (reduceMotion) 1f else (pullPx / opacityDivPx).coerceIn(0f, 1f)
            boScale = if (reduceMotion) 1f else (0.5f + 0.5f * (pullPx / thresholdPx)).coerceIn(0.5f, 1f)
        }
        Phase.Refreshing -> {
            boAlpha = 1f
            if (!reduceMotion) {
                val s = sin(bouncePhase * 2f * PI.toFloat())
                squishX = 1f + BOUNCE_SQUISH * s
                squishY = 1f - BOUNCE_SQUISH * s
                floatDy = -BOUNCE_FLOAT_PX * ((sin(bouncePhase * 2f * PI.toFloat()) + 1f) / 2f)
            }
        }
        Phase.SnapBack, Phase.Settling -> {
            // Shrink away with the collapsing tray.
            val k = (trayHeightPx / lockedPx).coerceIn(0f, 1f)
            boAlpha = if (reduceMotion) k else (trayHeightPx / thresholdPx).coerceIn(0f, 1f)
            boScale = if (reduceMotion) 1f else k
        }
        Phase.Idle -> boAlpha = 0f
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(trayHeightDp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Bo(
            size = BO_SIZE.dp,
            expression = if (phase == Phase.Refreshing) BoExpression.Happy else BoExpression.Default,
            showSparkles = false,
            showShadow = false,
            modifier = Modifier.graphicsLayer {
                alpha = boAlpha
                scaleX = boScale * squishX
                scaleY = boScale * squishY
                translationY = floatDy
                // Pivot at the bottom so the squish reads as landing weight.
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            },
        )
    }
}

private enum class Phase { Idle, Pulling, Ready, Refreshing, SnapBack, Settling }

/**
 * Recompute the drag-phase (pulling vs. ready) from the current pull and fire
 * the threshold haptic exactly once per drag on the first crossing. The latch
 * is only reset on release/idle — dipping back below the line mid-drag never
 * re-fires (spec: "do not repeat while the finger hovers around the line").
 */
private inline fun updateDragPhase(
    pullPx: Float,
    thresholdPx: Float,
    setPhase: (Phase) -> Unit,
    latched: Boolean,
    onLatch: () -> Unit,
) {
    when {
        pullPx >= thresholdPx -> {
            setPhase(Phase.Ready)
            if (!latched) {
                hapticImpactMedium()
                onLatch()
            }
        }
        pullPx > 0f -> setPhase(Phase.Pulling)
        else -> setPhase(Phase.Idle)
    }
}

// ── Physics & timing (from the prototype) ─────────────────────────────────────
private const val PULL_RESISTANCE = 0.5f   // rubber-band; halves finger travel
private const val MAX_PULL = 132f          // hard cap while dragging (dp)
private const val TRIGGER_THRESHOLD = 72f  // release >= this => refresh (dp)
private const val LOCKED_HEIGHT = 104f     // tray height during load (dp)
private const val OPACITY_DIVISOR = 26f    // Bo fades in over the first ~26dp of pull (converted to px)
private const val BO_SIZE = 44f            // Bo canvas size (dp)

private const val MIN_DISPLAY_MS = 1500L   // keep Bo up at least this long
private const val LOCK_MS = 220            // settle into the 104 lock on release
private const val COLLAPSE_MS = 500        // snap-back / post-fetch collapse
private const val BOUNCE_PERIOD_MS = 1000  // one squish+float cycle (~0.9–1.05s)
private const val BOUNCE_SQUISH = 0.06f    // ± scale wobble
private const val BOUNCE_FLOAT_PX = 8f     // vertical float travel

private val CollapseEasing = CubicBezierEasing(0.2f, 0.9f, 0.2f, 1f)
