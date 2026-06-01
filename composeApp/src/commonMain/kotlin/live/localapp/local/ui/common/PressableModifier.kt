package live.localapp.local.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Adds a fluid press interaction (scale shrink + haptic) to any modifier
 * chain. Mirrors the inline pattern used by the home `EventCard` so the
 * feel is identical across the app.
 *
 * Implemented as a `@Composable` extension rather than via `Modifier.composed`
 * because `composed { }` doesn't reliably emit pointer events on iOS Compose
 * Multiplatform inside scrolling parents — the parent intercepts the gesture
 * before our `detectTapGestures` block runs and the shrink never fires.
 *
 * The shrink is deferred by [pressDelay] ms. A scroll moves the pointer past
 * touch-slop within that window, which cancels the press (`tryAwaitRelease`
 * returns false) and cancels the pending shrink before it ever animates — so
 * cards no longer flicker-shrink while the list is scrolling. A real tap holds
 * still long enough for the shrink to fire, then springs back on release.
 */
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    scale: Float = 0.94f,
    haptic: Boolean = true,
    enabled: Boolean = true,
    pressDelay: Long = 50L,
): Modifier {
    val animatable = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    // pointerInput(enabled) captures its block once and only re-launches when `enabled` flips,
    // so an `onClick` reference captured directly inside detectTapGestures goes stale across
    // recompositions (sticky until app restart). rememberUpdatedState routes through the latest.
    val currentOnClick by rememberUpdatedState(onClick)
    return this
        .graphicsLayer {
            scaleX = animatable.value
            scaleY = animatable.value
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    // While the finger is held, defer the shrink by [pressDelay].
                    // A scroll moves past touch-slop within that window and
                    // cancels the press, so this job is canceled before it
                    // animates — no flicker-shrink while the list scrolls.
                    val shrinkJob = scope.launch {
                        delay(pressDelay)
                        animatable.animateTo(scale, tween(80))
                    }
                    val released = tryAwaitRelease()
                    shrinkJob.cancel()
                    if (released) {
                        // Confirmed tap. Acknowledgment is decoupled from the
                        // hold-delay: even a tap too quick for the deferred
                        // shrink gets a crisp down→spring-back pulse, so a real
                        // click always registers visually. If the deferred
                        // shrink already ran, animateTo(scale) is a near no-op.
                        if (haptic) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch {
                            animatable.animateTo(scale, tween(60))
                            animatable.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f))
                        }
                        currentOnClick()
                    } else {
                        // Scroll / cancel — return to rest (no-op if never shrank).
                        scope.launch {
                            animatable.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f))
                        }
                    }
                },
            )
        }
}
