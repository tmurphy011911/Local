package live.localapp.local.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import kotlin.math.sin

/**
 * Drop-in replacement for [PullToRefreshBox] used by the app's top-level feeds.
 * When [Config.FEATURE_BO_PTR_ENABLED] is on it renders the Bo mascot control;
 * otherwise it falls back to the stock Material3 spinner. The `content` keeps
 * the same `BoxScope` receiver as `PullToRefreshBox`, so a call site only swaps
 * the function name.
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
 * generic Material3 spinner on the app's top-level feeds.
 *
 * BEHAVIOR (Reddit / SwipeRefreshLayout style): the feed CONTENT is pushed DOWN
 * as the user pulls, and Bo sits in the revealed gap at the top. Content locks
 * ~104dp down while refreshing (Bo bouncing in the gap) and slides back up on
 * finish. This is deliberately NOT an overlay — product wants the content to
 * move.
 *
 * ARCHITECTURE: Material3's own [pullToRefresh] modifier owns the entire gesture
 * — arming only at scroll-top, the 0.5 drag multiplier (== the spec's rubber-band
 * resistance), the 72dp threshold, snap-back below threshold, and distance-based
 * (not velocity-based) release. We write NO nested-scroll code. The content push
 * is a single VISUAL graphicsLayer translation driven off [PullToRefreshState].
 * distanceFraction + one lock animator — it never consumes scroll and Material3
 * doesn't block scrolling during a refresh, so the feed still scrolls 1:1 while
 * pushed down (this is what the old hand-rolled version got wrong).
 *
 * A thin coordinator adds the three things Material3 can't express: the
 * once-per-drag threshold haptic, the [MIN_DISPLAY_MS] floor (via a derived
 * `held` flag fed back into the engine), and the release bounce.
 *
 * Empty/short feeds must still be a scrollable container (a `LazyColumn` with a
 * `fillParentMaxSize` item, or `verticalScroll`) — any pull-to-refresh needs a
 * scrolling child to arm.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BoRefreshControl(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    refreshFailed: Boolean = false,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val reduceMotion = remember { isReduceMotionEnabled() }
    val state = rememberPullToRefreshState()

    // ── Coordinator ─────────────────────────────────────────────────────────
    // Min-display floor: hold the indicator up until BOTH the real fetch clears
    // AND the floor elapses. `held` is what we feed the engine as "isRefreshing",
    // so Material3 keeps distanceFraction anchored (≈1) meanwhile.
    var floorElapsed by remember { mutableStateOf(true) }
    val held = isRefreshing || !floorElapsed

    val wrappedOnRefresh: () -> Unit = {
        floorElapsed = false     // start the min-display floor
        hapticImpactLight()      // release-triggers-refresh cue
        onRefresh()              // the real fetch
    }

    LaunchedEffect(held) {
        if (held && !floorElapsed) {
            delay(MIN_DISPLAY_MS)
            floorElapsed = true
        }
    }

    // Threshold cue: fire ONCE when the pull first crosses the line, re-arm only
    // after it returns near zero (a new drag). Suppressed while refreshing.
    LaunchedEffect(state) {
        var latched = false
        snapshotFlow { state.distanceFraction }.collect { f ->
            if (f >= 1f && !latched && !held) {
                hapticImpactMedium()
                latched = true
            }
            if (f <= 0.05f) latched = false
        }
    }

    // Settle cue on the falling edge of `held`.
    var wasHeld by remember { mutableStateOf(false) }
    LaunchedEffect(held) {
        if (wasHeld && !held) {
            if (refreshFailed) hapticNotifyError() else hapticNotifySuccess()
        }
        wasHeld = held
    }

    // Lock owns the gap once a refresh fires. On release we SEED it to the
    // release position (so the content settles monotonically down to 104 with no
    // dip — the old `max()` of a falling distanceFraction and a rising lock is
    // what caused the release bounce on iOS), then ramp to the 104dp rest; on
    // finish it slides back to 0. While lock is engaged the gap is driven purely
    // by lock, so Material3's distanceFraction settle can't cross it.
    val lock = remember { Animatable(0f) }
    // Scroll-to-dismiss: once the tray is up, the first scroll into the feed
    // animates it away into the top. We only OBSERVE the scroll (return Zero,
    // never consume), so the list scrolls 1:1, Material3's gesture is untouched,
    // and a later pull re-arms normally — the consuming version broke exactly
    // that. `dismissAway` 0→1 folds the gap closed independently of `lock`.
    var dismissed by remember { mutableStateOf(false) }
    val dismissAway = remember { Animatable(0f) }
    LaunchedEffect(held) {
        if (held) {
            dismissed = false
            dismissAway.snapTo(0f)
            val releaseFrac = (state.distanceFraction.coerceAtLeast(0f) * TRIGGER_THRESHOLD / LOCKED_HEIGHT)
                .coerceIn(0f, 1f)
            lock.snapTo(releaseFrac.coerceAtLeast(0.0001f)) // >0 so the gap switches to the lock term
            lock.animateTo(1f, tween(LOCK_MS, easing = LinearEasing))
        } else {
            lock.animateTo(0f, tween(COLLAPSE_MS, easing = CollapseEasing))
            dismissed = false
            dismissAway.snapTo(0f)
        }
    }
    LaunchedEffect(dismissed) {
        if (dismissed) dismissAway.animateTo(1f, tween(DISMISS_MS, easing = CollapseEasing))
    }

    val heldState = rememberUpdatedState(held)
    val dismissObserver = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (heldState.value && source == NestedScrollSource.Drag && available.y < 0f) {
                    dismissed = true
                }
                return Offset.Zero // observe only — never consume
            }
        }
    }

    // Revealed gap / content-push distance, in px. Single source of truth: track
    // the finger via distanceFraction while idle; once lock engages (release →
    // refresh) it owns the gap, folded closed by any scroll-dismiss.
    // (Read inside graphicsLayer lambdas → deferred, no recomposition storm.)
    fun androidx.compose.ui.graphics.GraphicsLayerScope.gapPx(): Float {
        return if (lock.value > 0f) {
            lock.value * LOCKED_HEIGHT.dp.toPx() * (1f - dismissAway.value)
        } else {
            state.distanceFraction.coerceAtLeast(0f) * TRIGGER_THRESHOLD.dp.toPx()
        }
    }

    // ── Layout ──────────────────────────────────────────────────────────────
    Box(
        modifier = modifier
            // Observe-only: while refreshing, the first scroll dismisses the tray
            // (animates it away). Never consumes, so scrolling stays 1:1.
            .nestedScroll(dismissObserver)
            .pullToRefresh(
                isRefreshing = held,
                state = state,
                enabled = enabled,
                threshold = TRIGGER_THRESHOLD.dp,
                onRefresh = wrappedOnRefresh,
            )
            .clipToBounds(), // content pushed past the bottom edge is clipped
    ) {
        // CONTENT — pushed DOWN by the gap (Reddit-style). Purely visual; the
        // LazyColumn's scroll + Material3's gesture are untouched. Overscroll
        // factory nulled so the feed's own top stretch can't fight the pull.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = gapPx() },
        ) {
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
                content()
            }
        }

        // Bo — centered in the revealed gap at the top.
        BoRefreshIndicator(
            gapPx = { gapPx() },
            distanceFraction = { state.distanceFraction },
            held = held,
            reduceMotion = reduceMotion,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/** Coral Bo, centered in the revealed pull-to-refresh gap at the top. */
@Composable
private fun BoRefreshIndicator(
    gapPx: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Float,
    distanceFraction: () -> Float,
    held: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
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

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Bo(
            size = BO_SIZE.dp,
            expression = if (held) BoExpression.Happy else BoExpression.Default,
            showSparkles = false,
            showShadow = false,
            modifier = Modifier.graphicsLayer {
                val gap = gapPx()
                val f = distanceFraction().coerceAtLeast(0f)
                val boPx = BO_SIZE.dp.toPx()

                val bouncing = held && !reduceMotion
                val floatPx = if (bouncing) {
                    -BOUNCE_FLOAT.dp.toPx() * ((sin(bouncePhase * TWO_PI) + 1f) / 2f)
                } else 0f
                // Center Bo vertically in the 0..gap strip.
                translationY = gap / 2f - boPx / 2f + floatPx

                alpha = if (held) {
                    // Full while the tray is up; fades out as it scrolls away.
                    (gap / (LOCKED_HEIGHT * 0.5f).dp.toPx()).coerceIn(0f, 1f)
                } else {
                    (f * TRIGGER_THRESHOLD.dp.toPx() / OPACITY_DIVISOR.dp.toPx()).coerceIn(0f, 1f)
                }

                val base = if (reduceMotion || held) 1f else (0.5f + 0.5f * f).coerceIn(0.5f, 1f)
                val squish = if (bouncing) BOUNCE_SQUISH * sin(bouncePhase * TWO_PI) else 0f
                scaleX = base * (1f + squish)
                scaleY = base * (1f - squish)
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            },
        )
    }
}

// ── Physics & timing (from the prototype) ─────────────────────────────────────
private const val TRIGGER_THRESHOLD = 72f  // dp — distanceFraction == 1.0 here
private const val LOCKED_HEIGHT = 104f     // dp — content push / gap while refreshing
private const val OPACITY_DIVISOR = 26f    // dp — Bo fades in over first ~26dp of pull
private const val BO_SIZE = 44f            // dp

private const val MIN_DISPLAY_MS = 1500L   // keep Bo up at least this long
private const val LOCK_MS = 220            // settle into the 104 gap on release
private const val COLLAPSE_MS = 500        // slide back up on settle / snap-back
private const val DISMISS_MS = 260         // tray folds away when scrolled during refresh
private const val BOUNCE_PERIOD_MS = 1000  // one squish+float cycle
private const val BOUNCE_SQUISH = 0.06f    // ± scale wobble
private const val BOUNCE_FLOAT = 8f        // dp — vertical float travel

private const val TWO_PI = (2.0 * PI).toFloat()
private val CollapseEasing = CubicBezierEasing(0.2f, 0.9f, 0.2f, 1f)
