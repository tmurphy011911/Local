package live.localapp.local.ui.common

import androidx.compose.foundation.shape.CircleShape as ComposeCircleShape
import androidx.compose.foundation.shape.RoundedCornerShape as ComposeRoundedCornerShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Decorates a [Shape] so a degenerate layout size never reaches the delegate's
 * `createOutline`.
 *
 * Recent Compose Foundation hard-throws inside `CornerBasedShape.createOutline`
 * ("Corner size in Px can't be negative") when it's handed a size whose
 * dimension is negative — older Compose silently clamped to zero. On iOS 26.x
 * this surfaces as a fatal `requirePrecondition` crash with no app frame in the
 * backtrace, because the outline is rebuilt lazily inside the graphics layer
 * during a snapshot redraw (decoupled from where the `.clip(...)` was declared).
 *
 * A degenerate size is a transient layout artifact (a momentarily-collapsed or
 * over-squeezed node during a transition). For that single frame we return an
 * empty outline — nothing draws, which is correct for a zero/negative-area node
 * anyway — and the next valid layout pass renders normally. Net effect: the
 * crash class is impossible, with no visible change in steady state.
 */
private class SafeShape(val delegate: Shape) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val degenerate = !size.isSpecified ||
            size.width.isNaN() || size.height.isNaN() ||
            size.width <= 0f || size.height <= 0f
        if (degenerate) return Outline.Rectangle(Rect(0f, 0f, 0f, 0f))
        return try {
            delegate.createOutline(size, layoutDirection, density)
        } catch (_: IllegalArgumentException) {
            // A valid-size node can still throw if a corner radius resolves
            // negative — e.g. an animated/spring corner Dp that undershoots
            // below 0 for one frame ("Corner size in Px can't be negative").
            // Fall back to an un-rounded rect of the real size; the next pass,
            // with the corner back >= 0, renders the rounded shape normally.
            Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        }
    }

    override fun equals(other: Any?): Boolean = other is SafeShape && other.delegate == delegate
    override fun hashCode(): Int = delegate.hashCode()
}

/** Wrap any shape so a degenerate size can never crash its outline build. */
fun Shape.crashSafe(): Shape = if (this is SafeShape) this else SafeShape(this)

// ── Drop-in safe replacements ────────────────────────────────────────────────
// Import these in place of the Compose originals
// (`import live.localapp.local.ui.common.RoundedCornerShape` /
//  `import live.localapp.local.ui.common.CircleShape`) so every shape in the
// file is guarded without touching individual call sites. Signatures mirror the
// Compose factories used across the app; overload resolution prefers the
// single-arg forms over the all-defaults 4-arg form exactly as in Compose, so
// existing call sites resolve unchanged.

fun RoundedCornerShape(size: Dp): Shape = ComposeRoundedCornerShape(size).crashSafe()

fun RoundedCornerShape(percent: Int): Shape = ComposeRoundedCornerShape(percent).crashSafe()

fun RoundedCornerShape(
    topStartPercent: Int,
    topEndPercent: Int,
    bottomEndPercent: Int,
    bottomStartPercent: Int,
): Shape = ComposeRoundedCornerShape(
    topStartPercent,
    topEndPercent,
    bottomEndPercent,
    bottomStartPercent,
).crashSafe()

fun RoundedCornerShape(
    topStart: Dp = 0.dp,
    topEnd: Dp = 0.dp,
    bottomEnd: Dp = 0.dp,
    bottomStart: Dp = 0.dp,
): Shape = ComposeRoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart).crashSafe()

val CircleShape: Shape = ComposeCircleShape.crashSafe()
