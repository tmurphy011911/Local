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
 * The shrink starts on touch-down (no delay). If the user drags to scroll,
 * `tryAwaitRelease` returns false and the modifier springs back without
 * firing `onClick`.
 *
 * [pressDelay] is preserved as a no-op for source compatibility with
 * earlier callers.
 */
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    scale: Float = 0.94f,
    haptic: Boolean = true,
    enabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") pressDelay: Long = 0L,
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
                    scope.launch { animatable.animateTo(scale, tween(80)) }
                    val released = tryAwaitRelease()
                    scope.launch {
                        animatable.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f))
                    }
                    if (released) {
                        if (haptic) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnClick()
                    }
                },
            )
        }
}
