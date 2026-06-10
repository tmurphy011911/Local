package live.localapp.local.ui.character

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.cos
import kotlin.math.sin
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
    val paperShade = Color(0xFFE8E2D6)
    val blue       = Color(0xFF5B9BD5)
    val brown      = Color(0xFF9A6A43)
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
    /**
     * Loyalty level 0–8. When set, Bo wears the matching prop/face (suitcase,
     * map, plant, binoculars, pennant, sunglasses, coffee, beret, crown) and
     * renders in the extended viewBox so hats/props don't clip. When null, Bo
     * draws the legacy mascot (pixel-identical to before) and uses [expression].
     */
    level: Int? = null,
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

    // Legend halo slow spin (level 8 only)
    val haloSpin by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 24000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "bo-halo",
    )

    Canvas(modifier.size(size)) {
        val tx = if (motion != null) motionTransform(motion, motionProgress.value)
                 else bounceTransform(bounce.value)

        if (level == null) {
            // ── Legacy mascot — 0..100 box, pixel-identical to before ──
            val s = this.size.minDimension / 100f
            scale(s, s, pivot = Offset.Zero) {
                if (showShadow) {
                    drawOval(
                        color = BoColors.ink.copy(alpha = 0.18f),
                        topLeft = Offset(24f, 85f),
                        size = Size(52f, 6f),
                    )
                }
                if (showSparkles) drawSparkles(sparkleProgress)
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
        } else {
            // ── Level mode — extended viewBox (-20,-26 .. 120,104) so crown,
            // suitcase, beret etc. have headroom and never clip. ──
            val lvl = level.coerceIn(0, 8)
            val s = this.size.minDimension / 140f
            val offX = (this.size.width - 140f * s) / 2f
            // The viewBox reserves headroom above the body for crowns/hats, so a
            // naive centering leaves Bo sitting low. Bias up by ~half the body's
            // offset from the frame center (body midpoint ≈ design-y 52, frame
            // center ≈ 39) so the body reads as vertically centered in its box.
            val offY = (this.size.height - 130f * s) / 2f - 13f * s
            translate(left = offX, top = offY) {
                scale(s, s, pivot = Offset.Zero) {
                    translate(left = 20f, top = 26f) {
                        if (showShadow) {
                            drawOval(
                                color = BoColors.ink.copy(alpha = 0.18f),
                                topLeft = Offset(24f, 85f),
                                size = Size(52f, 6f),
                            )
                        }
                        if (lvl == 8) drawLegendHalo(haloSpin)
                        if (showSparkles) drawSparkles(sparkleProgress)
                        translate(left = tx.dx, top = tx.dy) {
                            rotate(degrees = tx.rotationDeg, pivot = Offset(50f, 50f)) {
                                scale(tx.scaleX, tx.scaleY, pivot = Offset(50f, 80f)) {
                                    drawBody()
                                    drawCheeks()
                                    if (lvl == 4) drawPennant()
                                    drawLevelEyes(lvl, blinkProgress)
                                    if (lvl == 5) drawSunglasses()
                                    drawLevelMouth(lvl)
                                    if (lvl == 2) drawPlant()
                                    if (lvl == 3) drawBinoculars()
                                    if (lvl == 1) drawMap()
                                    if (lvl == 6) drawCoffee()
                                    if (lvl == 0) drawSuitcase()
                                    if (lvl == 7) drawBeret()
                                    if (lvl == 8) drawCrown()
                                }
                            }
                        }
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

// ══════════════════════════════════════════════════════════════════════════
//  Level-mode parts — props + faces for loyalty levels 0–8.
//  All coordinates are in the design's body space (matching drawBody), drawn
//  inside the extended viewBox set up in Bo(). SVG paths ported 1:1 from the
//  design reference (Bo.jsx).
// ══════════════════════════════════════════════════════════════════════════

private fun DrawScope.drawLevelEyes(level: Int, blinkProgress: Float) {
    val isBlinking = blinkProgress in 0.92f..0.96f
    val blinkScale = if (isBlinking) {
        val phase = (blinkProgress - 0.92f) / 0.04f
        if (phase < 0.5f) 1f - phase * 2f else (phase - 0.5f) * 2f
    } else 1f
    drawLevelEyesScaled(level, blinkScale)
}

/** Level eyes with an explicit open fraction (0 = closed, 1 = open). */
internal fun DrawScope.drawLevelEyesScaled(level: Int, openFraction: Float) {
    // Levels 3 (binoculars) and 5 (sunglasses) cover the eyes.
    if (level == 3 || level == 5) return
    val lookUp = if (level == 0) -2f else 0f
    val eyeY = 50f + lookUp
    listOf(38f, 62f).forEachIndexed { i, eyeX ->
        scale(1f, openFraction.coerceIn(0f, 1f), pivot = Offset(eyeX, eyeY)) {
            drawOval(BoColors.ink, Offset(eyeX - 5.5f, eyeY - 7f), Size(11f, 14f))
            val shineX = if (i == 0) 40f else 64f
            drawOval(BoColors.paper, Offset(shineX - 2.2f, (47f + lookUp) - 2.7f), Size(4.4f, 5.4f))
            val dotX = if (i == 0) 36f else 60f
            drawCircle(BoColors.paper, radius = 0.9f, center = Offset(dotX, 53f + lookUp))
        }
    }
}

internal fun DrawScope.drawLevelMouth(level: Int) {
    // emotion per level: 0 uncertain, 1 curious, 2 cozy, 3 happy, 4 proud,
    //                    5 cool, 6 cozy, 7 charming, 8 radiant
    val ink = BoColors.ink
    fun stroke(p: Path) = drawPath(p, ink, style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    when (level) {
        0 -> stroke(Path().apply { // uncertain
            moveTo(44f, 65f); quadraticBezierTo(47f, 62f, 50f, 65f); quadraticBezierTo(53f, 68f, 56f, 65f)
        })
        1, 2, 6 -> stroke(Path().apply { // curious / cozy
            moveTo(45f, 64f); quadraticBezierTo(50f, 67f, 55f, 64f)
        })
        4 -> stroke(Path().apply { // proud
            moveTo(43f, 64f); quadraticBezierTo(50f, 70f, 57f, 64f)
        })
        5 -> stroke(Path().apply { // cool
            moveTo(44f, 65f); quadraticBezierTo(50f, 67f, 57f, 62f)
        })
        7 -> stroke(Path().apply { // charming
            moveTo(44f, 64f); quadraticBezierTo(50f, 68f, 57f, 63f)
        })
        8 -> { // radiant — filled
            val p = Path().apply {
                moveTo(43f, 63f); quadraticBezierTo(50f, 73f, 57f, 63f); close()
            }
            drawPath(p, ink)
            drawPath(p, ink, style = Stroke(width = 2.5f, join = StrokeJoin.Round))
        }
        else -> stroke(Path().apply { // happy / default
            moveTo(44f, 64f); quadraticBezierTo(50f, 68f, 56f, 64f)
        })
    }
}

internal fun DrawScope.svgEllipse(cx: Float, cy: Float, rx: Float, ry: Float, fill: Color, stroke: Color? = null, strokeW: Float = 2.5f) {
    drawOval(fill, Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2))
    if (stroke != null) drawOval(stroke, Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2), style = Stroke(width = strokeW))
}

internal fun DrawScope.svgRect(x: Float, y: Float, w: Float, h: Float, rx: Float, fill: Color, stroke: Color? = null, strokeW: Float = 3f, fillAlpha: Float = 1f) {
    val cr = CornerRadius(rx, rx)
    drawRoundRect(fill.copy(alpha = fillAlpha), Offset(x, y), Size(w, h), cr)
    if (stroke != null) drawRoundRect(stroke, Offset(x, y), Size(w, h), cr, style = Stroke(width = strokeW))
}

internal fun DrawScope.svgLine(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, w: Float, alpha: Float = 1f) {
    drawLine(color.copy(alpha = alpha), Offset(x1, y1), Offset(x2, y2), strokeWidth = w, cap = StrokeCap.Round)
}

// Level 0 — suitcase + sweat
internal fun DrawScope.drawSuitcase() {
    // sweat bead arc + droplet by the temple
    svgLine(70f, 36f, 73f, 33f, BoColors.blue, 2.4f)
    svgLine(73f, 33f, 76f, 36f, BoColors.blue, 2.4f)
    val drop = Path().apply {
        moveTo(81f, 40f); quadraticBezierTo(84f, 45f, 81f, 47.5f)
        quadraticBezierTo(77f, 47.5f, 77.5f, 44f); quadraticBezierTo(78.5f, 42f, 81f, 40f); close()
    }
    drawPath(drop, BoColors.blue)
    drawPath(drop, BoColors.ink, style = Stroke(width = 1.6f, join = StrokeJoin.Round))
    // rolling suitcase
    svgRect(82f, 66f, 22f, 18f, 3.5f, BoColors.blue, BoColors.ink, 3f)
    val handle = Path().apply {
        moveTo(88f, 66f); quadraticBezierTo(88f, 60f, 96f, 60f); quadraticBezierTo(104f, 60f, 104f, 66f)
    }
    drawPath(handle, BoColors.ink, style = Stroke(width = 3f, cap = StrokeCap.Round))
    svgLine(82f, 74f, 104f, 74f, BoColors.ink, 2f, alpha = 0.6f)
    svgRect(90f, 69f, 6f, 3f, 1f, BoColors.paper, BoColors.ink, 1.4f)
}

// Level 1 — map held in two hands
internal fun DrawScope.drawMap() {
    svgEllipse(30f, 68f, 5f, 3.6f, BoColors.coral, BoColors.ink, 2.5f)
    svgEllipse(70f, 68f, 5f, 3.6f, BoColors.coral, BoColors.ink, 2.5f)
    rotate(degrees = -3f, pivot = Offset(50f, 70f)) {
        svgRect(32f, 60f, 36f, 22f, 2f, BoColors.paper, BoColors.ink, 3f)
        svgLine(44f, 60f, 44f, 82f, BoColors.ink, 1.6f, alpha = 0.5f)
        svgLine(56f, 60f, 56f, 82f, BoColors.ink, 1.6f, alpha = 0.5f)
        val route = Path().apply {
            moveTo(37f, 66f); quadraticBezierTo(44f, 64f, 50f, 69f); quadraticBezierTo(57f, 72f, 63f, 70f)
        }
        drawPath(route, BoColors.blue, style = Stroke(width = 2f, cap = StrokeCap.Round))
        // X mark
        svgLine(60f, 67f, 63f, 70f, BoColors.coralDeep, 2.2f)
        svgLine(63f, 67f, 60f, 70f, BoColors.coralDeep, 2.2f)
        drawCircle(BoColors.sage, radius = 1.4f, center = Offset(38f, 65f))
    }
}

// Level 2 — potted plant
internal fun DrawScope.drawPlant() {
    val leafL = Path().apply {
        moveTo(16f, 71f); quadraticBezierTo(9f, 61f, 8f, 52f); quadraticBezierTo(16f, 55f, 18f, 67f); close()
    }
    val leafR = Path().apply {
        moveTo(18f, 71f); quadraticBezierTo(25f, 61f, 26f, 52f); quadraticBezierTo(18f, 55f, 18f, 67f); close()
    }
    drawPath(leafL, BoColors.sage); drawPath(leafL, BoColors.ink, style = Stroke(width = 2f, join = StrokeJoin.Round))
    drawPath(leafR, BoColors.sage); drawPath(leafR, BoColors.ink, style = Stroke(width = 2f, join = StrokeJoin.Round))
    val stem = Path().apply { moveTo(17f, 70f); quadraticBezierTo(17f, 58f, 17f, 51f) }
    drawPath(stem, BoColors.ink, style = Stroke(width = 2f, cap = StrokeCap.Round))
    drawCircle(BoColors.sun, radius = 1.9f, center = Offset(17f, 50f))
    drawCircle(BoColors.ink, radius = 1.9f, center = Offset(17f, 50f), style = Stroke(width = 1.4f))
    val pot = Path().apply { moveTo(12f, 71f); lineTo(24f, 71f); lineTo(22f, 84f); lineTo(14f, 84f); close() }
    drawPath(pot, BoColors.brown); drawPath(pot, BoColors.ink, style = Stroke(width = 3f, join = StrokeJoin.Round))
    svgRect(10f, 67.5f, 16f, 5.5f, 2f, BoColors.brown, BoColors.ink, 3f)
}

// Level 3 — binoculars (covers eyes)
internal fun DrawScope.drawBinoculars() {
    svgEllipse(27f, 61f, 5.2f, 3.8f, BoColors.coral, BoColors.ink, 2.5f)
    svgEllipse(73f, 61f, 5.2f, 3.8f, BoColors.coral, BoColors.ink, 2.5f)
    svgRect(29f, 41f, 15f, 23f, 6.5f, BoColors.ink)
    svgRect(56f, 41f, 15f, 23f, 6.5f, BoColors.ink)
    svgRect(32.5f, 45f, 2.6f, 15f, 1.3f, BoColors.paper, fillAlpha = 0.28f)
    svgRect(59.5f, 45f, 2.6f, 15f, 1.3f, BoColors.paper, fillAlpha = 0.28f)
    svgRect(42f, 46f, 16f, 10f, 3f, BoColors.ink)
    svgRect(45.5f, 40f, 9f, 8f, 2.5f, BoColors.ink)
    svgLine(48f, 41.5f, 48f, 46.5f, BoColors.paper, 0.9f, alpha = 0.45f)
    svgLine(50f, 41.5f, 50f, 46.5f, BoColors.paper, 0.9f, alpha = 0.45f)
    svgLine(52f, 41.5f, 52f, 46.5f, BoColors.paper, 0.9f, alpha = 0.45f)
    drawCircle(BoColors.sage, 5.4f, Offset(36.5f, 60f)); drawCircle(BoColors.ink, 5.4f, Offset(36.5f, 60f), style = Stroke(width = 2f))
    drawCircle(BoColors.sage, 5.4f, Offset(63.5f, 60f)); drawCircle(BoColors.ink, 5.4f, Offset(63.5f, 60f), style = Stroke(width = 2f))
    drawCircle(BoColors.paper, 1.5f, Offset(34.8f, 58.3f))
    drawCircle(BoColors.paper, 1.5f, Offset(61.8f, 58.3f))
}

// Level 4 — varsity pennant
internal fun DrawScope.drawPennant() {
    svgEllipse(70f, 69f, 5f, 3.6f, BoColors.coral, BoColors.ink, 2.5f)
    svgLine(73f, 80f, 79f, 34f, BoColors.brown, 3.2f)
    val flag = Path().apply { moveTo(78f, 37f); lineTo(103f, 45f); lineTo(78f, 54f); close() }
    drawPath(flag, BoColors.sun); drawPath(flag, BoColors.ink, style = Stroke(width = 3f, join = StrokeJoin.Round))
    svgLine(78f, 37f, 78f, 54f, BoColors.ink, 3f)
}

// Level 5 — sunglasses (covers eyes)
internal fun DrawScope.drawSunglasses() {
    svgRect(28f, 44f, 16f, 12f, 4f, BoColors.ink)
    svgRect(56f, 44f, 16f, 12f, 4f, BoColors.ink)
    val bridge = Path().apply { moveTo(44f, 48f); quadraticBezierTo(50f, 46f, 56f, 48f) }
    drawPath(bridge, BoColors.ink, style = Stroke(width = 3f, cap = StrokeCap.Round))
    svgLine(28f, 47f, 18f, 42f, BoColors.ink, 3f)
    svgLine(30f, 47f, 37f, 52f, BoColors.paper, 2f, alpha = 0.55f)
    svgLine(58f, 47f, 65f, 52f, BoColors.paper, 2f, alpha = 0.55f)
}

// Level 6 — to-go coffee cup
internal fun DrawScope.drawCoffee() {
    svgEllipse(68f, 70f, 5f, 3.6f, BoColors.coral, BoColors.ink, 2.5f)
    val cup = Path().apply { moveTo(70f, 58f); lineTo(86f, 58f); lineTo(83f, 80f); lineTo(73f, 80f); close() }
    drawPath(cup, BoColors.paper); drawPath(cup, BoColors.ink, style = Stroke(width = 3f, join = StrokeJoin.Round))
    drawRect(BoColors.coral, Offset(71.5f, 66f), Size(13f, 7f))
    drawRect(BoColors.ink, Offset(71.5f, 66f), Size(13f, 7f), style = Stroke(width = 2f))
    val lid = Path().apply { moveTo(69f, 58f); lineTo(87f, 58f); lineTo(86f, 53f); lineTo(70f, 53f); close() }
    drawPath(lid, BoColors.paperShade); drawPath(lid, BoColors.ink, style = Stroke(width = 3f, join = StrokeJoin.Round))
    svgRect(75f, 49f, 6f, 4f, 1.5f, BoColors.ink)
    val steam1 = Path().apply { moveTo(76f, 47f); quadraticBezierTo(73f, 43f, 76f, 39f) }
    val steam2 = Path().apply { moveTo(81f, 47f); quadraticBezierTo(84f, 43f, 81f, 39f) }
    drawPath(steam1, BoColors.sage, style = Stroke(width = 2f, cap = StrokeCap.Round))
    drawPath(steam2, BoColors.sage, style = Stroke(width = 2f, cap = StrokeCap.Round))
}

// Level 7 — beret
internal fun DrawScope.drawBeret() {
    rotate(degrees = -12f, pivot = Offset(50f, 16f)) {
        svgEllipse(48f, 17f, 23f, 8.5f, BoColors.coralDeep, BoColors.ink, 3f)
        val band = Path().apply { moveTo(27f, 19f); quadraticBezierTo(48f, 27f, 69f, 18f) }
        drawPath(band, BoColors.ink.copy(alpha = 0.55f), style = Stroke(width = 2.5f, cap = StrokeCap.Round))
        drawCircle(BoColors.coralDeep, 2.6f, Offset(48f, 9f))
        drawCircle(BoColors.ink, 2.6f, Offset(48f, 9f), style = Stroke(width = 2.5f))
    }
}

// Level 8 — gold crown
internal fun DrawScope.drawCrown() {
    val crown = Path().apply {
        moveTo(30f, 16f); lineTo(32f, -2f); lineTo(41f, 8f); lineTo(50f, -7f)
        lineTo(59f, 8f); lineTo(68f, -2f); lineTo(70f, 16f); close()
    }
    drawPath(crown, BoColors.sun); drawPath(crown, BoColors.ink, style = Stroke(width = 3f, join = StrokeJoin.Round))
    svgRect(30f, 14f, 40f, 7f, 2f, BoColors.sun, BoColors.ink, 3f)
    drawCircle(BoColors.coral, 2.4f, Offset(32f, -2f)); drawCircle(BoColors.ink, 2.4f, Offset(32f, -2f), style = Stroke(width = 1.6f))
    drawCircle(BoColors.coral, 2.8f, Offset(50f, -7f)); drawCircle(BoColors.ink, 2.8f, Offset(50f, -7f), style = Stroke(width = 1.6f))
    drawCircle(BoColors.coral, 2.4f, Offset(68f, -2f)); drawCircle(BoColors.ink, 2.4f, Offset(68f, -2f), style = Stroke(width = 1.6f))
    drawCircle(BoColors.sage, 1.8f, Offset(40f, 18f))
    drawCircle(BoColors.coralDeep, 1.8f, Offset(50f, 18f))
    drawCircle(BoColors.sage, 1.8f, Offset(60f, 18f))
}

// Level 8 — halo of 12 rays + soft glow, slow spin
internal fun DrawScope.drawLegendHalo(spinDeg: Float) {
    rotate(degrees = spinDeg, pivot = Offset(50f, 52f)) {
        drawCircle(BoColors.sun.copy(alpha = 0.12f), 50f, Offset(50f, 52f))
        drawCircle(BoColors.sun.copy(alpha = 0.10f), 40f, Offset(50f, 52f))
        for (i in 0 until 12) {
            val a = (i * 30) * (kotlin.math.PI / 180.0)
            val x1 = 50f + cos(a).toFloat() * 42f
            val y1 = 52f + sin(a).toFloat() * 42f
            val x2 = 50f + cos(a).toFloat() * 56f
            val y2 = 52f + sin(a).toFloat() * 56f
            svgLine(x1, y1, x2, y2, BoColors.sun, 3f, alpha = 0.85f)
        }
    }
}

// ─── Bounce transform ───────────────────────────────────────────────────────
// Legacy bounce shape lives in BoMotions.kt as `bounceTransform(t)` returning a
// MotionTransform. Keeping it there lets the motion library and the tap squish
// share the same transform pipeline.
