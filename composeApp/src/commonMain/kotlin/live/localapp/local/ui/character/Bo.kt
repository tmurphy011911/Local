package live.localapp.local.ui.character

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Bo — Local's mascot character.
 *
 * A coral wisp/sprite with a wavy bottom, big anime eyes, and idle blinking.
 *
 * Usage:
 *   var trigger by remember { mutableStateOf(0) }
 *   Bo(
 *       size = 80.dp,
 *       expression = BoExpression.Default,
 *       trigger = trigger,
 *       modifier = Modifier.clickable { trigger++ }
 *   )
 */

object BoColors {
    val coral      = Color(0xFFFF6B6B)
    val coralLight = Color(0xFFFFB3B3)
    val coralDeep  = Color(0xFFE85555)
    val sage       = Color(0xFF4ECBA0)
    val sun        = Color(0xFFFFB830)
    val ink        = Color(0xFF1A1523)
    val paper      = Color(0xFFFAF7F2)
}

enum class BoExpression {
    Default,    // big sparkly eyes, soft smile — Bo's neutral state
    Happy,      // closed-arch eyes, big open smile — celebratory
    Sleepy,     // half-lidded eyes — quiet/tired states
    Thinking,   // eyes looking up — Bo is processing
    Surprised,  // wide eyes, "o" mouth — for moments of delight
}

private val BounceEasing = DefaultBounceEasing

@Composable
fun Bo(
    size: Dp = 80.dp,
    expression: BoExpression = BoExpression.Default,
    trigger: Int = 0,
    motion: BoMotion? = null,
    showSparkles: Boolean = true,
    showShadow: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // Motion animation — full library (Spin, Shake, PopIn, …).
    // When [motion] is null we fall back to the legacy tap-squish bounce.
    val motionProgress = remember { Animatable(0f) }
    LaunchedEffect(trigger, motion) {
        if (trigger > 0 && motion != null) {
            motionProgress.snapTo(0f)
            motionProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = motion.durationMs(), easing = motion.easing()),
            )
        }
    }
    val bounce = remember { Animatable(0f) }
    LaunchedEffect(trigger, motion) {
        if (trigger > 0 && motion == null) {
            bounce.snapTo(0f)
            bounce.animateTo(1f, tween(durationMillis = 800, easing = BounceEasing))
        }
    }

    // Idle blink — every 5s, eyes close briefly
    val infiniteTransition = rememberInfiniteTransition(label = "bo-idle")
    val blinkProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "bo-blink",
    )

    // Idle sparkle pulse — every 4s, sparkles get briefly larger
    val sparkleProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "bo-sparkle",
    )

    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 100f
        scale(s, s, pivot = Offset.Zero) {
            // Shadow
            if (showShadow) {
                drawOval(
                    color = BoColors.ink.copy(alpha = 0.18f),
                    topLeft = Offset(24f, 85f),
                    size = Size(52f, 6f),
                )
            }

            // Decorative sparkles
            if (showSparkles) {
                drawSparkles(sparkleProgress)
            }

            // Body group — motion transform (full library) takes precedence
            // when [motion] is set, otherwise the legacy bounce squish.
            val tx = if (motion != null) motionTransform(motion, motionProgress.value)
                     else bounceTransform(bounce.value)
            translate(left = tx.dx, top = tx.dy) {
                rotate(degrees = tx.rotationDeg, pivot = Offset(50f, 50f)) {
                    scale(tx.scaleX, tx.scaleY, pivot = Offset(50f, 80f)) {
                        drawBody()
                        drawCheeks()
                        drawEyes(expression, blinkProgress)
                        drawMouth(expression)
                    }
                }
            }
        }
    }
}

// ─── Body ───────────────────────────────────────────────────────────────────
private fun DrawScope.drawBody() {
    val path = Path().apply {
        moveTo(50f, 22f)
        cubicTo(76f, 22f, 88f, 36f, 88f, 52f)
        cubicTo(88f, 64f, 86f, 72f, 86f, 76f)
        // Wavy bottom — eight little dips
        quadraticBezierTo(82f, 80f, 78f, 78f)
        quadraticBezierTo(74f, 82f, 70f, 78f)
        quadraticBezierTo(66f, 82f, 62f, 78f)
        quadraticBezierTo(58f, 82f, 54f, 78f)
        quadraticBezierTo(50f, 82f, 46f, 78f)
        quadraticBezierTo(42f, 82f, 38f, 78f)
        quadraticBezierTo(34f, 82f, 30f, 78f)
        quadraticBezierTo(26f, 82f, 22f, 78f)
        quadraticBezierTo(18f, 80f, 14f, 76f)
        cubicTo(14f, 72f, 12f, 64f, 12f, 52f)
        cubicTo(12f, 36f, 24f, 22f, 50f, 22f)
        close()
    }
    drawPath(path, BoColors.coral)
    drawPath(path, BoColors.ink, style = Stroke(width = 3.5f, join = StrokeJoin.Round))
}

// ─── Cheeks ─────────────────────────────────────────────────────────────────
private fun DrawScope.drawCheeks() {
    drawOval(
        color = BoColors.coralDeep.copy(alpha = 0.7f),
        topLeft = Offset(23.5f, 55f),
        size = Size(9f, 6f),
    )
    drawOval(
        color = BoColors.coralDeep.copy(alpha = 0.7f),
        topLeft = Offset(67.5f, 55f),
        size = Size(9f, 6f),
    )
}

// ─── Eyes ───────────────────────────────────────────────────────────────────
private fun DrawScope.drawEyes(expression: BoExpression, blinkProgress: Float) {
    // Idle blink window: roughly 92-96% of cycle, eye closes for ~4% (~200ms in 5s)
    val isBlinking = blinkProgress in 0.92f..0.96f
    val blinkScale = if (isBlinking) {
        // 0.92→1, 0.94→0 (closed), 0.96→1
        val phase = (blinkProgress - 0.92f) / 0.04f
        if (phase < 0.5f) 1f - phase * 2f else (phase - 0.5f) * 2f
    } else {
        1f
    }

    when (expression) {
        BoExpression.Default, BoExpression.Surprised -> drawDefaultEyes(blinkScale)
        BoExpression.Happy                            -> drawHappyEyes()
        BoExpression.Sleepy                           -> drawSleepyEyes()
        BoExpression.Thinking                         -> drawThinkingEyes(blinkScale)
    }
}

private fun DrawScope.drawDefaultEyes(blinkScale: Float) {
    val eyeY = 50f
    listOf(38f, 62f).forEachIndexed { i, eyeX ->
        scale(1f, blinkScale, pivot = Offset(eyeX, eyeY)) {
            // Eyeball
            drawOval(
                color = BoColors.ink,
                topLeft = Offset(eyeX - 5.5f, eyeY - 7f),
                size = Size(11f, 14f),
            )
            // Primary catchlight
            val shineX = if (i == 0) 40f else 64f
            drawOval(
                color = BoColors.paper,
                topLeft = Offset(shineX - 2.2f, eyeY - 2f - 2.7f),
                size = Size(4.4f, 5.4f),
            )
            // Secondary catchlight
            val shine2X = if (i == 0) 36f else 60f
            drawCircle(
                color = BoColors.paper,
                radius = 0.9f,
                center = Offset(shine2X, eyeY + 2f),
            )
        }
    }
}

private fun DrawScope.drawHappyEyes() {
    // Closed-arch eyes ◠ ◠
    val arcStrokeWidth = 3.5f
    val leftArc = Path().apply {
        moveTo(32f, 50f)
        quadraticBezierTo(38f, 44f, 44f, 50f)
    }
    val rightArc = Path().apply {
        moveTo(56f, 50f)
        quadraticBezierTo(62f, 44f, 68f, 50f)
    }
    drawPath(leftArc, BoColors.ink, style = Stroke(width = arcStrokeWidth, cap = StrokeCap.Round))
    drawPath(rightArc, BoColors.ink, style = Stroke(width = arcStrokeWidth, cap = StrokeCap.Round))
}

private fun DrawScope.drawSleepyEyes() {
    // Half-closed lids with thin pupils peeking
    val arcStrokeWidth = 3f
    val leftLid = Path().apply { moveTo(32f, 52f); quadraticBezierTo(38f, 48f, 44f, 52f) }
    val rightLid = Path().apply { moveTo(56f, 52f); quadraticBezierTo(62f, 48f, 68f, 52f) }
    drawPath(leftLid, BoColors.ink, style = Stroke(width = arcStrokeWidth, cap = StrokeCap.Round))
    drawPath(rightLid, BoColors.ink, style = Stroke(width = arcStrokeWidth, cap = StrokeCap.Round))
    // Thin pupils
    drawOval(BoColors.ink, Offset(35f, 52.5f), Size(6f, 3f))
    drawOval(BoColors.ink, Offset(59f, 52.5f), Size(6f, 3f))
}

private fun DrawScope.drawThinkingEyes(blinkScale: Float) {
    // Same as default but no secondary catchlight, and pupils shifted up
    val eyeY = 50f
    listOf(38f, 62f).forEachIndexed { i, eyeX ->
        scale(1f, blinkScale, pivot = Offset(eyeX, eyeY)) {
            drawOval(
                color = BoColors.ink,
                topLeft = Offset(eyeX - 5.5f, eyeY - 7f),
                size = Size(11f, 14f),
            )
            val shineX = if (i == 0) 40f else 64f
            drawOval(
                color = BoColors.paper,
                topLeft = Offset(shineX - 2.2f, eyeY - 4f - 2.7f),
                size = Size(4.4f, 5.4f),
            )
        }
    }
}

// ─── Mouth ──────────────────────────────────────────────────────────────────
private fun DrawScope.drawMouth(expression: BoExpression) {
    when (expression) {
        BoExpression.Default, BoExpression.Sleepy -> {
            // Soft upward smile
            val path = Path().apply {
                moveTo(44f, 64f)
                quadraticBezierTo(50f, 68f, 56f, 64f)
            }
            drawPath(path, BoColors.ink, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
        }
        BoExpression.Happy -> {
            // Big open smile with tongue
            val path = Path().apply {
                moveTo(42f, 62f)
                quadraticBezierTo(50f, 70f, 58f, 62f)
                quadraticBezierTo(54f, 68f, 46f, 68f)
                close()
            }
            drawPath(path, BoColors.coralDeep)
            drawPath(path, BoColors.ink, style = Stroke(width = 2.5f, join = StrokeJoin.Round))
        }
        BoExpression.Surprised -> {
            // Small "o" mouth
            drawOval(
                color = BoColors.ink,
                topLeft = Offset(47.5f, 62f),
                size = Size(5f, 6f),
            )
        }
        BoExpression.Thinking -> {
            // Flat line
            val path = Path().apply { moveTo(44f, 65f); lineTo(56f, 65f) }
            drawPath(path, BoColors.ink, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
        }
    }
}

// ─── Sparkles ───────────────────────────────────────────────────────────────
private fun DrawScope.drawSparkles(idleProgress: Float) {
    // Each sparkle pulses on a slight delay during the idle cycle
    val pulse1 = sparklePulse(idleProgress)
    val pulse2 = sparklePulse((idleProgress + 0.33f) % 1f)
    val pulse3 = sparklePulse((idleProgress + 0.66f) % 1f)

    // Sun sparkle (left)
    drawSparkle(
        center = Offset(14f, 34f),
        scale = pulse1,
        fill = BoColors.sun,
        stroke = BoColors.ink,
    )
    // Coral dot (top right)
    scale(pulse2, pulse2, pivot = Offset(86f, 26f)) {
        drawCircle(BoColors.coral, radius = 2f, center = Offset(86f, 26f))
        drawCircle(BoColors.ink, radius = 2f, center = Offset(86f, 26f), style = Stroke(width = 1.2f))
    }
    // Sage dot (right)
    scale(pulse3, pulse3, pivot = Offset(88f, 58f)) {
        drawCircle(BoColors.sage, radius = 1.8f, center = Offset(88f, 58f))
    }
}

private fun DrawScope.drawSparkle(center: Offset, scale: Float, fill: Color, stroke: Color) {
    scale(scale, scale, pivot = center) {
        val path = Path().apply {
            // 4-point star
            moveTo(center.x, center.y - 6f)
            lineTo(center.x + 2f, center.y - 2f)
            lineTo(center.x + 6f, center.y)
            lineTo(center.x + 2f, center.y + 2f)
            lineTo(center.x, center.y + 6f)
            lineTo(center.x - 2f, center.y + 2f)
            lineTo(center.x - 6f, center.y)
            lineTo(center.x - 2f, center.y - 2f)
            close()
        }
        drawPath(path, fill)
        drawPath(path, stroke, style = Stroke(width = 1.2f, join = StrokeJoin.Round))
    }
}

// Returns a value 1.0 most of the time, briefly bumping to 1.3 for the pulse
private fun sparklePulse(t: Float): Float = when {
    t < 0.92f -> 1f
    t < 0.94f -> 1f + (t - 0.92f) / 0.02f * 0.3f
    t < 0.96f -> 1.3f - (t - 0.94f) / 0.02f * 0.3f
    else      -> 1f
}

// ─── Bounce transform ───────────────────────────────────────────────────────
// Legacy bounce shape lives in BoMotions.kt as `bounceTransform(t)` returning a
// MotionTransform. Keeping it there lets the motion library and the tap squish
// share the same transform pipeline.
