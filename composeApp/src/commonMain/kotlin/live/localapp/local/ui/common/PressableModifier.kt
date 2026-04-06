package live.localapp.local.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch

/**
 * A reusable modifier that adds a fluid press interaction:
 * - Scale animation (shrink on press, spring back on release)
 * - Haptic feedback on tap
 */
fun Modifier.pressable(
    onClick: () -> Unit,
    scale: Float = 0.94f,
    haptic: Boolean = true,
    enabled: Boolean = true,
): Modifier = composed {
    val animatable = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current

    this
        .graphicsLayer {
            scaleX = animatable.value
            scaleY = animatable.value
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    println("PRESSABLE: press detected, animating to $scale")
                    // Animate down
                    val downJob = scope.launch {
                        animatable.animateTo(scale, tween(80))
                    }
                    val released = tryAwaitRelease()
                    println("PRESSABLE: released=$released, animating back to 1f")
                    // Animate back up with spring overshoot
                    scope.launch {
                        animatable.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f))
                    }
                    if (released) {
                        if (haptic) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                    }
                },
            )
        }
}
