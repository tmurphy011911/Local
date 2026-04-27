package live.localapp.local.ui.icons

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object LocalColors {
    val coral      = Color(0xFFFF6B6B)
    val coralLight = Color(0xFFFFB3B3)
    val sage       = Color(0xFF4ECBA0)
    val sageLight  = Color(0xFFA8E5CD)
    val sun        = Color(0xFFFFB830)
    val sunLight   = Color(0xFFFFD988)
    val ink        = Color(0xFF1A1523)
    val inkSoft    = Color(0xFF3A3540)
    val paper      = Color(0xFFFAF7F2)
    val paperDeep  = Color(0xFFF0EBE3)
}

private val BounceEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
private val SquashEasing = CubicBezierEasing(0.5f, 0f, 0.5f, 1f)
private val EaseOutFast  = CubicBezierEasing(0.25f, 0.5f, 0.4f, 1f)
private val Standard     = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

@Composable
private fun useAnimation(
    trigger: Int,
    durationMillis: Int = 700,
    delayMillis: Int = 0,
    easing: Easing = Standard,
): Float {
    val animatable = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            animatable.snapTo(0f)
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = durationMillis,
                    delayMillis = delayMillis,
                    easing = easing,
                ),
            )
        }
    }
    return animatable.value
}

private fun DrawScope.viewBoxScale(block: DrawScope.() -> Unit) {
    val s = size.minDimension / 100f
    scale(s, s, pivot = Offset.Zero) { block() }
}

private fun DrawScope.fillPath(
    fill: Color? = null,
    stroke: Color? = null,
    strokeWidth: Float = 0f,
    fillType: PathFillType = PathFillType.NonZero,
    block: Path.() -> Unit,
) {
    val p = Path().apply { this.fillType = fillType; block() }
    fill?.let { drawPath(p, it) }
    stroke?.let {
        drawPath(p, it, style = Stroke(width = strokeWidth, join = StrokeJoin.Round, cap = StrokeCap.Round))
    }
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

object LocalIcon {

    // ═══ NAVIGATION ═══════════════════════════════════════════════════════════

    @Composable
    fun Events(size: Dp = 56.dp, trigger: Int = 0, modifier: Modifier = Modifier) {
        val stamp = useAnimation(trigger, durationMillis = 700, easing = SquashEasing)
        val ring  = useAnimation(trigger, durationMillis = 550, delayMillis = 420, easing = EaseOutFast)
        Canvas(modifier) {
            viewBoxScale {
                drawRoundRect(LocalColors.ink.copy(alpha = 0.12f), Offset(16f, 28f), Size(72f, 60f), CornerRadius(8f, 8f))
                drawRoundRect(LocalColors.paper, Offset(14f, 24f), Size(72f, 60f), CornerRadius(8f, 8f))
                drawRoundRect(LocalColors.ink, Offset(14f, 24f), Size(72f, 60f), CornerRadius(8f, 8f), style = Stroke(width = 3.5f))
                fillPath(fill = LocalColors.coral, stroke = LocalColors.ink, strokeWidth = 3.5f) {
                    moveTo(14f, 32f)
                    cubicTo(14f, 24f, 22f, 24f, 22f, 24f)
                    lineTo(78f, 24f)
                    cubicTo(78f, 24f, 86f, 24f, 86f, 32f)
                    lineTo(86f, 42f)
                    lineTo(14f, 42f)
                    close()
                }
                drawRoundRect(LocalColors.ink, Offset(30f, 14f), Size(6f, 18f), CornerRadius(2f, 2f))
                drawRoundRect(LocalColors.ink, Offset(64f, 14f), Size(6f, 18f), CornerRadius(2f, 2f))
                val gridDots = listOf(
                    26f to 54f, 38f to 54f, 50f to 54f, 62f to 54f, 74f to 54f,
                    26f to 66f, 38f to 66f, 50f to 66f, 62f to 66f, 74f to 66f,
                    26f to 78f, 38f to 78f, 50f to 78f, 62f to 78f, 74f to 78f,
                )
                gridDots.forEach { (x, y) ->
                    drawCircle(LocalColors.inkSoft, radius = 2.5f, center = Offset(x, y))
                }
                if (ring > 0f && ring < 1f) {
                    val r = lerp(6f, 16f, ring)
                    val alpha = lerp(1f, 0f, ring)
                    drawCircle(LocalColors.coral.copy(alpha = alpha), radius = r, center = Offset(50f, 66f), style = Stroke(width = 2.5f))
                }
                val (sx, sy, dy) = if (trigger == 0) Transform(1f, 1f, 0f) else stampTransform(stamp)
                translate(left = 0f, top = dy) {
                    scale(sx, sy, pivot = Offset(50f, 66f)) {
                        drawCircle(LocalColors.coral, radius = 6f, center = Offset(50f, 66f))
                        drawCircle(LocalColors.ink, radius = 6f, center = Offset(50f, 66f), style = Stroke(width = 2f))
                    }
                }
            }
        }
    }

    @Composable
    fun Places(size: Dp = 56.dp, trigger: Int = 0, modifier: Modifier = Modifier) {
        val pin     = useAnimation(trigger, durationMillis = 850, easing = SquashEasing)
        val ripple1 = useAnimation(trigger, durationMillis = 700, delayMillis = 500, easing = EaseOutFast)
        val ripple2 = useAnimation(trigger, durationMillis = 700, delayMillis = 620, easing = EaseOutFast)
        Canvas(modifier) {
            viewBoxScale {
                drawLine(LocalColors.ink.copy(alpha = 0.15f), Offset(14f, 88f), Offset(86f, 88f), strokeWidth = 2f, cap = StrokeCap.Round)
                drawRipple(50f, 88f, ripple1, baseRx = 18f, baseRy = 4f, color = LocalColors.sage, strokeWidth = 2.5f)
                drawRipple(50f, 88f, ripple2, baseRx = 12f, baseRy = 3f, color = LocalColors.ink, strokeWidth = 2f)
                val (sx, sy, dy) = if (trigger == 0) Transform(1f, 1f, 0f) else pinDropTransform(pin)
                translate(left = 0f, top = dy) {
                    scale(sx, sy, pivot = Offset(50f, 88f)) {
                        fillPath(fill = LocalColors.sage, stroke = LocalColors.ink, strokeWidth = 3.5f) {
                            moveTo(50f, 14f)
                            cubicTo(32f, 14f, 22f, 28f, 22f, 44f)
                            cubicTo(22f, 60f, 38f, 76f, 50f, 88f)
                            cubicTo(62f, 76f, 78f, 60f, 78f, 44f)
                            cubicTo(78f, 28f, 68f, 14f, 50f, 14f)
                            close()
                        }
                        drawCircle(LocalColors.paper, radius = 11f, center = Offset(50f, 42f))
                        drawCircle(LocalColors.ink, radius = 11f, center = Offset(50f, 42f), style = Stroke(width = 3f))
                        drawCircle(LocalColors.ink, radius = 4f, center = Offset(50f, 42f))
                    }
                }
            }
        }
    }

    // ═══ CATEGORIES ═══════════════════════════════════════════════════════════

    @Composable
    fun ForYou(size: Dp = 56.dp, trigger: Int = 0, modifier: Modifier = Modifier) {
        val starP   = useAnimation(trigger, durationMillis = 800, easing = Standard)
        val sparkle1 = useAnimation(trigger, durationMillis = 600, delayMillis = 0,   easing = EaseOutFast)
        val sparkle2 = useAnimation(trigger, durationMillis = 600, delayMillis = 100, easing = EaseOutFast)
        val sparkle3 = useAnimation(trigger, durationMillis = 600, delayMillis = 200, easing = EaseOutFast)
        val sparkle4 = useAnimation(trigger, durationMillis = 600, delayMillis = 300, easing = EaseOutFast)
        Canvas(modifier) {
            viewBoxScale {
                drawSparkle(20f, 22f, 2.5f, LocalColors.sun, sparkle1, fromCenter = Offset(50f, 50f))
                drawSparkle(82f, 28f, 2f,   LocalColors.coral, sparkle2, fromCenter = Offset(50f, 50f))
                drawSparkle(78f, 78f, 2.5f, LocalColors.sage, sparkle3, fromCenter = Offset(50f, 50f))
                drawSparkle(22f, 80f, 2f,   LocalColors.coral, sparkle4, fromCenter = Offset(50f, 50f))

                val starScale = if (trigger == 0) 1f else 1f + 0.08f * (1f - kotlin.math.abs(starP - 0.5f) * 2f)
                rotate(starP * 360f, pivot = Offset(50f, 50f)) {
                    scale(starScale, starScale, pivot = Offset(50f, 50f)) {
                        fillPath(fill = LocalColors.sun, stroke = LocalColors.ink, strokeWidth = 3.5f) {
                            moveTo(50f, 14f); lineTo(57f, 43f); lineTo(86f, 50f); lineTo(57f, 57f)
                            lineTo(50f, 86f); lineTo(43f, 57f); lineTo(14f, 50f); lineTo(43f, 43f); close()
                        }
                        fillPath(fill = LocalColors.paper.copy(alpha = 0.5f)) {
                            moveTo(50f, 30f); lineTo(54f, 46f); lineTo(70f, 50f); lineTo(54f, 54f)
                            lineTo(50f, 70f); lineTo(46f, 54f); lineTo(30f, 50f); lineTo(46f, 46f); close()
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun Music(size: Dp = 56.dp, trigger: Int = 0, modifier: Modifier = Modifier) {
        val spin = useAnimation(trigger, durationMillis = 900, easing = Standard)
        Canvas(modifier) {
            viewBoxScale {
                rotate(spin * 360f, pivot = Offset(50f, 50f)) {
                    drawCircle(LocalColors.ink, radius = 36f, center = Offset(50f, 50f))
                    listOf(32f, 28f, 24f, 20f).forEach { r ->
                        drawCircle(LocalColors.inkSoft.copy(alpha = 0.5f), radius = r, center = Offset(50f, 50f), style = Stroke(width = 0.5f))
                    }
                    drawCircle(LocalColors.coral, radius = 14f, center = Offset(50f, 50f))
                    drawCircle(LocalColors.ink, radius = 14f, center = Offset(50f, 50f), style = Stroke(width = 2.5f))
                    drawCircle(LocalColors.paper, radius = 3f, center = Offset(50f, 50f))
                    drawCircle(LocalColors.ink, radius = 3f, center = Offset(50f, 50f), style = Stroke(width = 1.5f))
                }
            }
        }
    }

    @Composable
    fun Sports(size: Dp = 56.dp, trigger: Int = 0, modifier: Modifier = Modifier) {
        val bounce = useAnimation(trigger, durationMillis = 900, easing = BounceEasing)
        val dy = if (trigger == 0) 0f else when {
            bounce < 0.25f -> lerp(0f, 6f, bounce / 0.25f)
            bounce < 0.50f -> lerp(6f, -8f, (bounce - 0.25f) / 0.25f)
            bounce < 0.75f -> lerp(-8f, 3f, (bounce - 0.50f) / 0.25f)
            else           -> lerp(3f, 0f, (bounce - 0.75f) / 0.25f)
        }
        Canvas(modifier) {
            viewBoxScale {
                drawRoundRect(LocalColors.ink, Offset(32f, 78f), Size(36f, 6f), CornerRadius(2f, 2f))
                fillPath(fill = LocalColors.inkSoft) {
                    moveTo(40f, 72f); lineTo(60f, 72f); lineTo(62f, 78f); lineTo(38f, 78f); close()
                }
                translate(left = 0f, top = dy) {
                    fillPath(stroke = LocalColors.ink, strokeWidth = 3.5f) {
                        moveTo(28f, 32f); cubicTo(18f, 32f, 18f, 44f, 18f, 44f)
                        cubicTo(18f, 56f, 32f, 58f, 32f, 58f)
                    }
                    fillPath(stroke = LocalColors.ink, strokeWidth = 3.5f) {
                        moveTo(72f, 32f); cubicTo(82f, 32f, 82f, 44f, 82f, 44f)
                        cubicTo(82f, 56f, 68f, 58f, 68f, 58f)
                    }
                    fillPath(fill = LocalColors.sun, stroke = LocalColors.ink, strokeWidth = 3.5f) {
                        moveTo(28f, 24f); lineTo(72f, 24f); lineTo(70f, 56f)
                        cubicTo(70f, 70f, 50f, 70f, 50f, 70f)
                        cubicTo(30f, 70f, 30f, 56f, 30f, 56f); close()
                    }
                    drawRoundRect(LocalColors.sun, Offset(26f, 20f), Size(48f, 6f), CornerRadius(1f, 1f))
                    drawRoundRect(LocalColors.sun, Offset(26f, 20f), Size(48f, 6f), CornerRadius(1f, 1f), style = Stroke(width = 3.5f))
                    fillPath(fill = LocalColors.ink) {
                        moveTo(50f, 38f); lineTo(53f, 47f); lineTo(62f, 47f); lineTo(55f, 52f)
                        lineTo(58f, 61f); lineTo(50f, 56f); lineTo(42f, 61f); lineTo(45f, 52f)
                        lineTo(38f, 47f); lineTo(47f, 47f); close()
                    }
                }
            }
        }
    }

    @Composable
    fun Outdoors(size: Dp = 56.dp, trigger: Int = 0, modifier: Modifier = Modifier) {
        val sway = useAnimation(trigger, durationMillis = 800, easing = Standard)
        val sun  = useAnimation(trigger, durationMillis = 800, easing = BounceEasing)
        val swayAngle = if (trigger == 0) 0f else 3f * sin(sway * (PI * 2f).toFloat())
        val sunDy = if (trigger == 0) 0f else lerp(20f, 0f, sun)
        Canvas(modifier) {
            viewBoxScale {
                translate(left = 0f, top = sunDy) {
                    drawCircle(LocalColors.sun, radius = 10f, center = Offset(70f, 36f))
                    drawCircle(LocalColors.ink, radius = 10f, center = Offset(70f, 36f), style = Stroke(width = 3f))
                }
                fillPath(fill = LocalColors.sageLight, stroke = LocalColors.ink, strokeWidth = 3.5f) {
                    moveTo(14f, 78f); lineTo(38f, 38f); lineTo(62f, 78f); close()
                }
                rotate(swayAngle, pivot = Offset(60f, 78f)) {
                    fillPath(fill = LocalColors.sage, stroke = LocalColors.ink, strokeWidth = 3.5f) {
                        moveTo(36f, 78f); lineTo(60f, 22f); lineTo(84f, 78f); close()
                    }
                    fillPath(fill = LocalColors.paper, stroke = LocalColors.ink, strokeWidth = 2.5f) {
                        moveTo(52f, 38f); lineTo(60f, 22f); lineTo(68f, 38f)
                        lineTo(64f, 36f); lineTo(60f, 40f); lineTo(56f, 36f); close()
                    }
                }
                drawRect(LocalColors.ink, Offset(10f, 78f), Size(80f, 4f))
            }
        }
    }

    @Composable
    fun Experiences(size: Dp = 56.dp, trigger: Int = 0, modifier: Modifier = Modifier) {
        val rays  = useAnimation(trigger, durationMillis = 800, easing = BounceEasing)
        val core  = useAnimation(trigger, durationMillis = 700, delayMillis = 200, easing = Standard)
        val rayScale = if (trigger == 0) 1f else lerp(0.7f, 1f, rays)
        val rayRot   = if (trigger == 0) 0f else lerp(0f, 45f, rays)
        val corePulse = if (trigger == 0) 1f else 1f + 0.18f * (1f - kotlin.math.abs(core - 0.5f) * 2f)
        Canvas(modifier) {
            viewBoxScale {
                rotate(rayRot, pivot = Offset(50f, 50f)) {
                    scale(rayScale, rayScale, pivot = Offset(50f, 50f)) {
                        listOf(
                            { p: Path -> p.moveTo(50f, 8f); p.lineTo(52f, 22f); p.lineTo(48f, 22f); p.close() },
                            { p: Path -> p.moveTo(50f, 92f); p.lineTo(52f, 78f); p.lineTo(48f, 78f); p.close() },
                            { p: Path -> p.moveTo(8f, 50f); p.lineTo(22f, 48f); p.lineTo(22f, 52f); p.close() },
                            { p: Path -> p.moveTo(92f, 50f); p.lineTo(78f, 48f); p.lineTo(78f, 52f); p.close() },
                            { p: Path -> p.moveTo(20f, 20f); p.lineTo(30f, 26f); p.lineTo(26f, 30f); p.close() },
                            { p: Path -> p.moveTo(80f, 20f); p.lineTo(70f, 26f); p.lineTo(74f, 30f); p.close() },
                            { p: Path -> p.moveTo(20f, 80f); p.lineTo(30f, 74f); p.lineTo(26f, 70f); p.close() },
                            { p: Path -> p.moveTo(80f, 80f); p.lineTo(70f, 74f); p.lineTo(74f, 70f); p.close() },
                        ).forEach { build ->
                            fillPath(fill = LocalColors.coral, stroke = LocalColors.ink, strokeWidth = 2.5f) { build(this) }
                        }
                    }
                }
                scale(corePulse, corePulse, pivot = Offset(50f, 50f)) {
                    drawCircle(LocalColors.sun, radius = 16f, center = Offset(50f, 50f))
                    drawCircle(LocalColors.ink, radius = 16f, center = Offset(50f, 50f), style = Stroke(width = 3.5f))
                    drawCircle(LocalColors.paper, radius = 6f, center = Offset(50f, 50f))
                    drawCircle(LocalColors.ink, radius = 6f, center = Offset(50f, 50f), style = Stroke(width = 2f))
                }
            }
        }
    }

    @Composable
    fun Tours(size: Dp = 56.dp, trigger: Int = 0, modifier: Modifier = Modifier) {
        val spin = useAnimation(trigger, durationMillis = 1000, easing = Standard)
        val angle = if (trigger == 0) 0f else when {
            spin < 0.6f -> lerp(0f, 540f, spin / 0.6f)
            spin < 0.8f -> lerp(540f, 510f, (spin - 0.6f) / 0.2f)
            else        -> lerp(510f, 540f, (spin - 0.8f) / 0.2f)
        }
        Canvas(modifier) {
            viewBoxScale {
                drawCircle(LocalColors.sage, radius = 36f, center = Offset(50f, 50f))
                drawCircle(LocalColors.ink, radius = 36f, center = Offset(50f, 50f), style = Stroke(width = 3.5f))
                drawCircle(LocalColors.paper, radius = 30f, center = Offset(50f, 50f))
                drawCircle(LocalColors.ink, radius = 30f, center = Offset(50f, 50f), style = Stroke(width = 2f))
                rotate(angle, pivot = Offset(50f, 50f)) {
                    fillPath(fill = LocalColors.coral, stroke = LocalColors.ink, strokeWidth = 2f) {
                        moveTo(50f, 28f); lineTo(54f, 50f); lineTo(50f, 54f); lineTo(46f, 50f); close()
                    }
                    fillPath(fill = LocalColors.ink, stroke = LocalColors.ink, strokeWidth = 2f) {
                        moveTo(50f, 72f); lineTo(46f, 50f); lineTo(50f, 46f); lineTo(54f, 50f); close()
                    }
                    drawCircle(LocalColors.paper, radius = 3f, center = Offset(50f, 50f))
                    drawCircle(LocalColors.ink, radius = 3f, center = Offset(50f, 50f), style = Stroke(width = 1.5f))
                }
            }
        }
    }

    @Composable
    fun Nightlife(size: Dp = 56.dp, trigger: Int = 0, modifier: Modifier = Modifier) {
        val tilt  = useAnimation(trigger, durationMillis = 800, easing = Standard)
        val bob   = useAnimation(trigger, durationMillis = 800, easing = Standard)
        val tiltAngle = if (trigger == 0) 0f else 8f * sin(tilt * (PI * 2f).toFloat())
        val bobDy = if (trigger == 0) 0f else 2f * sin(bob * (PI * 2f).toFloat())
        Canvas(modifier) {
            viewBoxScale {
                rotate(tiltAngle, pivot = Offset(50f, 86f)) {
                    drawRoundRect(LocalColors.ink, Offset(34f, 84f), Size(32f, 4f), CornerRadius(1f, 1f))
                    drawRect(LocalColors.ink, Offset(48f, 56f), Size(4f, 30f))
                    fillPath(fill = LocalColors.sunLight, stroke = LocalColors.ink, strokeWidth = 3.5f) {
                        moveTo(22f, 22f); lineTo(78f, 22f); lineTo(52f, 56f); lineTo(48f, 56f); close()
                    }
                    drawLine(LocalColors.sun.copy(alpha = 0.6f), Offset(28f, 28f), Offset(72f, 28f), strokeWidth = 2f)
                    translate(left = 0f, top = bobDy) {
                        drawCircle(LocalColors.sage, radius = 5f, center = Offset(58f, 36f))
                        drawCircle(LocalColors.ink, radius = 5f, center = Offset(58f, 36f), style = Stroke(width = 2.5f))
                        drawCircle(LocalColors.coral, radius = 1.5f, center = Offset(60f, 34f))
                        drawLine(LocalColors.ink, Offset(58f, 36f), Offset(74f, 20f), strokeWidth = 2f, cap = StrokeCap.Round)
                    }
                }
            }
        }
    }

    @Composable
    fun Libraries(size: Dp = 56.dp, trigger: Int = 0, modifier: Modifier = Modifier) {
        val flip = useAnimation(trigger, durationMillis = 900, easing = Standard)
        val rightScaleX = if (trigger == 0) 1f else when {
            flip < 0.5f -> lerp(1f, -1f, flip / 0.5f)
            else        -> lerp(-1f, 1f, (flip - 0.5f) / 0.5f)
        }
        val leftScale = if (trigger == 0) 1f else 1f + 0.05f * (1f - kotlin.math.abs(flip - 0.5f) * 2f)
        Canvas(modifier) {
            viewBoxScale {
                drawOval(LocalColors.ink.copy(alpha = 0.15f), topLeft = Offset(14f, 81f), size = Size(72f, 6f))
                fillPath(fill = LocalColors.paper, stroke = LocalColors.ink, strokeWidth = 3.5f) {
                    moveTo(14f, 28f); lineTo(50f, 34f); lineTo(86f, 28f); lineTo(86f, 78f)
                    lineTo(50f, 84f); lineTo(14f, 78f); close()
                }
                drawLine(LocalColors.ink, Offset(50f, 34f), Offset(50f, 84f), strokeWidth = 2.5f)

                scale(leftScale, leftScale, pivot = Offset(32f, 56f)) {
                    fillPath(fill = LocalColors.sageLight, stroke = LocalColors.ink, strokeWidth = 3f) {
                        moveTo(14f, 28f); lineTo(50f, 34f); lineTo(50f, 78f); lineTo(14f, 72f); close()
                    }
                    drawLine(LocalColors.ink.copy(alpha = 0.5f), Offset(22f, 42f), Offset(42f, 44f), strokeWidth = 1.5f)
                    drawLine(LocalColors.ink.copy(alpha = 0.5f), Offset(22f, 50f), Offset(42f, 52f), strokeWidth = 1.5f)
                    drawLine(LocalColors.ink.copy(alpha = 0.5f), Offset(22f, 58f), Offset(36f, 60f), strokeWidth = 1.5f)
                }

                scale(rightScaleX, 1f, pivot = Offset(50f, 56f)) {
                    fillPath(fill = LocalColors.coralLight, stroke = LocalColors.ink, strokeWidth = 3f) {
                        moveTo(50f, 34f); lineTo(86f, 28f); lineTo(86f, 72f); lineTo(50f, 78f); close()
                    }
                    drawLine(LocalColors.ink.copy(alpha = 0.5f), Offset(58f, 44f), Offset(78f, 42f), strokeWidth = 1.5f)
                    drawLine(LocalColors.ink.copy(alpha = 0.5f), Offset(58f, 52f), Offset(78f, 50f), strokeWidth = 1.5f)
                    drawLine(LocalColors.ink.copy(alpha = 0.5f), Offset(58f, 60f), Offset(72f, 58f), strokeWidth = 1.5f)
                }

                fillPath(fill = LocalColors.coral, stroke = LocalColors.ink, strokeWidth = 2f) {
                    moveTo(68f, 32f); lineTo(68f, 50f); lineTo(72f, 46f); lineTo(76f, 50f); lineTo(76f, 31f)
                }
            }
        }
    }

    @Composable
    fun Theatre(size: Dp = 56.dp, trigger: Int = 0, modifier: Modifier = Modifier) {
        val curtains = useAnimation(trigger, durationMillis = 700, easing = Standard)
        val starRev  = useAnimation(trigger, durationMillis = 600, delayMillis = 300, easing = BounceEasing)
        val curtainShift = if (trigger == 0) 0f else lerp(0f, 14f, curtains)
        val starScale = if (trigger == 0) 1f else lerp(0f, 1f, starRev)
        Canvas(modifier) {
            viewBoxScale {
                drawRect(LocalColors.ink, Offset(10f, 80f), Size(80f, 6f))
                drawRect(LocalColors.paperDeep, Offset(14f, 20f), Size(72f, 60f))
                scale(starScale, starScale, pivot = Offset(50f, 56f)) {
                    fillPath(fill = LocalColors.sun, stroke = LocalColors.ink, strokeWidth = 2.5f) {
                        moveTo(50f, 38f); lineTo(54f, 52f); lineTo(68f, 52f); lineTo(57f, 60f)
                        lineTo(61f, 74f); lineTo(50f, 66f); lineTo(39f, 74f); lineTo(43f, 60f)
                        lineTo(32f, 52f); lineTo(46f, 52f); close()
                    }
                }
                drawRoundRect(LocalColors.ink, Offset(10f, 16f), Size(80f, 6f), CornerRadius(2f, 2f))
                translate(left = -curtainShift, top = 0f) {
                    fillPath(fill = LocalColors.coral, stroke = LocalColors.ink, strokeWidth = 3.5f) {
                        moveTo(14f, 22f); lineTo(42f, 22f); lineTo(40f, 80f)
                        cubicTo(34f, 78f, 28f, 80f, 28f, 80f)
                        cubicTo(22f, 78f, 14f, 80f, 14f, 80f); close()
                    }
                    drawLine(LocalColors.ink.copy(alpha = 0.4f), Offset(22f, 26f), Offset(22f, 76f), strokeWidth = 1.5f)
                    drawLine(LocalColors.ink.copy(alpha = 0.4f), Offset(30f, 26f), Offset(30f, 76f), strokeWidth = 1.5f)
                    drawLine(LocalColors.ink.copy(alpha = 0.4f), Offset(38f, 26f), Offset(38f, 76f), strokeWidth = 1.5f)
                    drawCircle(LocalColors.sun, radius = 3f, center = Offset(40f, 48f))
                    drawCircle(LocalColors.ink, radius = 3f, center = Offset(40f, 48f), style = Stroke(width = 2f))
                }
                translate(left = curtainShift, top = 0f) {
                    fillPath(fill = LocalColors.coral, stroke = LocalColors.ink, strokeWidth = 3.5f) {
                        moveTo(58f, 22f); lineTo(86f, 22f); lineTo(86f, 80f)
                        cubicTo(78f, 78f, 72f, 80f, 72f, 80f)
                        cubicTo(66f, 78f, 60f, 80f, 60f, 80f); close()
                    }
                    drawLine(LocalColors.ink.copy(alpha = 0.4f), Offset(66f, 26f), Offset(66f, 76f), strokeWidth = 1.5f)
                    drawLine(LocalColors.ink.copy(alpha = 0.4f), Offset(74f, 26f), Offset(74f, 76f), strokeWidth = 1.5f)
                    drawLine(LocalColors.ink.copy(alpha = 0.4f), Offset(82f, 26f), Offset(82f, 76f), strokeWidth = 1.5f)
                    drawCircle(LocalColors.sun, radius = 3f, center = Offset(60f, 48f))
                    drawCircle(LocalColors.ink, radius = 3f, center = Offset(60f, 48f), style = Stroke(width = 2f))
                }
            }
        }
    }

    @Composable
    fun Art(size: Dp = 56.dp, trigger: Int = 0, modifier: Modifier = Modifier) {
        val blob1 = useAnimation(trigger, durationMillis = 500, delayMillis = 0,   easing = EaseOutFast)
        val blob2 = useAnimation(trigger, durationMillis = 500, delayMillis = 100, easing = EaseOutFast)
        val blob3 = useAnimation(trigger, durationMillis = 500, delayMillis = 200, easing = EaseOutFast)
        val blob4 = useAnimation(trigger, durationMillis = 500, delayMillis = 300, easing = EaseOutFast)
        val brush = useAnimation(trigger, durationMillis = 700, easing = Standard)
        val brushDx = if (trigger == 0) 0f else lerp(-12f, 0f, brush)
        val brushDy = if (trigger == 0) 0f else lerp(8f, 0f, brush)

        fun pulse(p: Float): Float = if (p == 0f) 1f else 1f + 0.3f * (1f - kotlin.math.abs(p - 0.5f) * 2f)

        Canvas(modifier) {
            viewBoxScale {
                fillPath(fill = LocalColors.paper, stroke = LocalColors.ink, strokeWidth = 3.5f) {
                    moveTo(22f, 50f)
                    quadraticBezierTo(22f, 22f, 50f, 18f)
                    quadraticBezierTo(82f, 18f, 84f, 44f)
                    quadraticBezierTo(86f, 66f, 66f, 64f)
                    quadraticBezierTo(60f, 64f, 60f, 70f)
                    quadraticBezierTo(60f, 80f, 50f, 84f)
                    quadraticBezierTo(22f, 84f, 22f, 50f)
                    close()
                }
                drawOval(LocalColors.ink, topLeft = Offset(54f, 54f), size = Size(12f, 8f))

                scale(pulse(blob1), pulse(blob1), pivot = Offset(36f, 36f)) {
                    drawCircle(LocalColors.coral, radius = 6f, center = Offset(36f, 36f))
                    drawCircle(LocalColors.ink, radius = 6f, center = Offset(36f, 36f), style = Stroke(width = 2f))
                }
                scale(pulse(blob2), pulse(blob2), pivot = Offset(54f, 30f)) {
                    drawCircle(LocalColors.sage, radius = 5f, center = Offset(54f, 30f))
                    drawCircle(LocalColors.ink, radius = 5f, center = Offset(54f, 30f), style = Stroke(width = 2f))
                }
                scale(pulse(blob3), pulse(blob3), pivot = Offset(70f, 36f)) {
                    drawCircle(LocalColors.sun, radius = 5f, center = Offset(70f, 36f))
                    drawCircle(LocalColors.ink, radius = 5f, center = Offset(70f, 36f), style = Stroke(width = 2f))
                }
                scale(pulse(blob4), pulse(blob4), pivot = Offset(34f, 56f)) {
                    drawCircle(LocalColors.ink, radius = 4f, center = Offset(34f, 56f))
                }

                translate(left = brushDx, top = brushDy) {
                    drawLine(LocalColors.ink, Offset(74f, 22f), Offset(92f, 10f), strokeWidth = 3.5f, cap = StrokeCap.Round)
                    fillPath(fill = LocalColors.coral, stroke = LocalColors.ink, strokeWidth = 2f) {
                        moveTo(70f, 26f); lineTo(78f, 18f); lineTo(82f, 22f); lineTo(74f, 30f); close()
                    }
                }
            }
        }
    }

    @Composable
    fun Food(size: Dp = 56.dp, trigger: Int = 0, modifier: Modifier = Modifier) {
        val s1 = useAnimation(trigger, durationMillis = 1000, delayMillis = 0,   easing = EaseOutFast)
        val s2 = useAnimation(trigger, durationMillis = 1000, delayMillis = 150, easing = EaseOutFast)
        val s3 = useAnimation(trigger, durationMillis = 1000, delayMillis = 300, easing = EaseOutFast)
        Canvas(modifier) {
            viewBoxScale {
                drawSteam(36f, s1)
                drawSteam(50f, s2)
                drawSteam(64f, s3)
                drawOval(LocalColors.coralLight, topLeft = Offset(16f, 36f), size = Size(68f, 12f))
                drawOval(LocalColors.coralLight, topLeft = Offset(16f, 36f), size = Size(68f, 12f), style = Stroke(width = 3.5f))
                fillPath(fill = LocalColors.coral, stroke = LocalColors.ink, strokeWidth = 3.5f) {
                    moveTo(16f, 42f)
                    quadraticBezierTo(16f, 78f, 50f, 82f)
                    quadraticBezierTo(84f, 78f, 84f, 42f)
                    close()
                }
                fillPath(stroke = LocalColors.ink.copy(alpha = 0.4f), strokeWidth = 2.5f) {
                    moveTo(18f, 56f); quadraticBezierTo(50f, 60f, 82f, 56f)
                }
                drawOval(LocalColors.sun, topLeft = Offset(22f, 38f), size = Size(56f, 8f))
                drawCircle(LocalColors.paper, radius = 3f, center = Offset(42f, 42f))
                drawCircle(LocalColors.ink, radius = 3f, center = Offset(42f, 42f), style = Stroke(width = 1.5f))
                drawCircle(LocalColors.paper, radius = 3f, center = Offset(58f, 42f))
                drawCircle(LocalColors.ink, radius = 3f, center = Offset(58f, 42f), style = Stroke(width = 1.5f))
                drawOval(LocalColors.ink, topLeft = Offset(44f, 38f), size = Size(12f, 8f), style = Stroke(width = 0f))
                drawOval(LocalColors.ink, topLeft = Offset(16f, 36f), size = Size(68f, 12f), style = Stroke(width = 3.5f))
            }
        }
    }

    @Composable
    fun Comedy(size: Dp = 56.dp, trigger: Int = 0, modifier: Modifier = Modifier) {
        val mic    = useAnimation(trigger, durationMillis = 700, easing = Standard)
        val wave1  = useAnimation(trigger, durationMillis = 600, delayMillis = 0,   easing = EaseOutFast)
        val wave2  = useAnimation(trigger, durationMillis = 600, delayMillis = 100, easing = EaseOutFast)
        val wobble = if (trigger == 0) 0f else 6f * sin(mic * (PI * 2f).toFloat())
        Canvas(modifier) {
            viewBoxScale {
                drawOval(LocalColors.ink, topLeft = Offset(30f, 85f), size = Size(40f, 6f))
                drawRect(LocalColors.ink, Offset(48f, 58f), Size(4f, 28f))
                drawLine(LocalColors.ink, Offset(40f, 86f), Offset(60f, 86f), strokeWidth = 3.5f, cap = StrokeCap.Round)

                rotate(wobble, pivot = Offset(50f, 58f)) {
                    drawRoundRect(LocalColors.ink, Offset(46f, 48f), Size(8f, 14f), CornerRadius(2f, 2f))
                    drawOval(LocalColors.sun, topLeft = Offset(34f, 16f), size = Size(32f, 36f))
                    drawOval(LocalColors.sun, topLeft = Offset(34f, 16f), size = Size(32f, 36f), style = Stroke(width = 3.5f))
                    listOf(26f, 32f, 38f, 44f).forEachIndexed { i, y ->
                        fillPath(stroke = LocalColors.ink.copy(alpha = 0.6f), strokeWidth = 1.5f) {
                            moveTo(if (i % 2 == 0) 38f else 36f, y)
                            quadraticBezierTo(50f, y - 2f, if (i % 2 == 0) 62f else 64f, y)
                        }
                    }
                    drawOval(LocalColors.paper.copy(alpha = 0.5f), topLeft = Offset(41f, 21f), size = Size(6f, 10f))
                }

                drawWave(18f, 34f, wave1, flip = false)
                drawWave(82f, 34f, wave2, flip = true)
            }
        }
    }

    @Composable
    fun Museums(size: Dp = 56.dp, trigger: Int = 0, modifier: Modifier = Modifier) {
        val pediment = useAnimation(trigger, durationMillis = 700, easing = BounceEasing)
        val columns  = useAnimation(trigger, durationMillis = 500, delayMillis = 100, easing = BounceEasing)
        val pedDy = if (trigger == 0) 0f else lerp(-32f, 0f, pediment)
        val pedSx = if (trigger == 0) 1f else lerp(0.85f, 1f, pediment)
        val colDy = if (trigger == 0) 0f else lerp(20f, 0f, columns)
        val colAlpha = if (trigger == 0) 1f else lerp(0f, 1f, columns)
        Canvas(modifier) {
            viewBoxScale {
                drawRect(LocalColors.ink, Offset(10f, 80f), Size(80f, 6f))
                drawRect(LocalColors.paperDeep, Offset(14f, 74f), Size(72f, 6f))
                drawRect(LocalColors.ink, Offset(14f, 74f), Size(72f, 6f), style = Stroke(width = 2.5f))

                translate(left = 0f, top = colDy) {
                    listOf(20f, 36f, 54f, 70f).forEach { x ->
                        drawRect(LocalColors.paper.copy(alpha = colAlpha), Offset(x, 40f), Size(10f, 34f))
                        drawRect(LocalColors.ink.copy(alpha = colAlpha), Offset(x, 40f), Size(10f, 34f), style = Stroke(width = 2.5f))
                        drawRect(LocalColors.paper.copy(alpha = colAlpha), Offset(x - 2f, 38f), Size(14f, 4f))
                        drawRect(LocalColors.ink.copy(alpha = colAlpha), Offset(x - 2f, 38f), Size(14f, 4f), style = Stroke(width = 2.5f))
                    }
                }

                drawRect(LocalColors.paperDeep, Offset(14f, 34f), Size(72f, 6f))
                drawRect(LocalColors.ink, Offset(14f, 34f), Size(72f, 6f), style = Stroke(width = 2.5f))

                translate(left = 0f, top = pedDy) {
                    scale(pedSx, 1f, pivot = Offset(50f, 34f)) {
                        fillPath(fill = LocalColors.coral, stroke = LocalColors.ink, strokeWidth = 3.5f) {
                            moveTo(14f, 34f); lineTo(50f, 12f); lineTo(86f, 34f); close()
                        }
                        drawCircle(LocalColors.paper, radius = 3f, center = Offset(50f, 26f))
                        drawCircle(LocalColors.ink, radius = 3f, center = Offset(50f, 26f), style = Stroke(width = 2f))
                    }
                }
            }
        }
    }

    @Composable
    fun Other(size: Dp = 56.dp, trigger: Int = 0, modifier: Modifier = Modifier) {
        val sparkle = useAnimation(trigger, durationMillis = 800, easing = BounceEasing)
        val orbit   = useAnimation(trigger, durationMillis = 900, easing = Standard)
        val sparkleAngle = if (trigger == 0) 0f else lerp(0f, 180f, sparkle)
        val sparkleScale = if (trigger == 0) 1f else lerp(0.6f, 1f, sparkle)
        val orbitAngle = if (trigger == 0) 0f else lerp(0f, 180f, orbit)
        Canvas(modifier) {
            viewBoxScale {
                rotate(orbitAngle, pivot = Offset(50f, 50f)) {
                    drawCircle(LocalColors.coral, radius = 3f, center = Offset(50f, 14f))
                    drawCircle(LocalColors.ink, radius = 3f, center = Offset(50f, 14f), style = Stroke(width = 2f))
                    drawCircle(LocalColors.sage, radius = 3f, center = Offset(86f, 50f))
                    drawCircle(LocalColors.ink, radius = 3f, center = Offset(86f, 50f), style = Stroke(width = 2f))
                    drawCircle(LocalColors.sun, radius = 3f, center = Offset(50f, 86f))
                    drawCircle(LocalColors.ink, radius = 3f, center = Offset(50f, 86f), style = Stroke(width = 2f))
                    drawCircle(LocalColors.coralLight, radius = 3f, center = Offset(14f, 50f))
                    drawCircle(LocalColors.ink, radius = 3f, center = Offset(14f, 50f), style = Stroke(width = 2f))
                }
                rotate(sparkleAngle, pivot = Offset(50f, 50f)) {
                    scale(sparkleScale, sparkleScale, pivot = Offset(50f, 50f)) {
                        fillPath(fill = LocalColors.coral, stroke = LocalColors.ink, strokeWidth = 3f) {
                            moveTo(50f, 26f)
                            quadraticBezierTo(52f, 48f, 74f, 50f)
                            quadraticBezierTo(52f, 52f, 50f, 74f)
                            quadraticBezierTo(48f, 52f, 26f, 50f)
                            quadraticBezierTo(48f, 48f, 50f, 26f)
                            close()
                        }
                        drawCircle(LocalColors.paper, radius = 3f, center = Offset(50f, 50f))
                    }
                }
            }
        }
    }

    @Composable
    fun Kids(size: Dp = 56.dp, trigger: Int = 0, modifier: Modifier = Modifier) {
        val spin = useAnimation(trigger, durationMillis = 1000, easing = Standard)
        val angle = spin * 900f
        Canvas(modifier) {
            viewBoxScale {
                drawOval(LocalColors.ink.copy(alpha = 0.2f), topLeft = Offset(44f, 86.5f), size = Size(12f, 3f))
                drawRoundRect(LocalColors.ink, Offset(48f, 42f), Size(4f, 46f), CornerRadius(1.5f, 1.5f))
                rotate(angle, pivot = Offset(50f, 42f)) {
                    drawBlade(BladeDir.Top,    LocalColors.coral)
                    drawBlade(BladeDir.Right,  LocalColors.sun)
                    drawBlade(BladeDir.Bottom, LocalColors.sage)
                    drawBlade(BladeDir.Left,   LocalColors.coralLight)
                    drawCircle(LocalColors.ink, radius = 5f, center = Offset(50f, 42f))
                    drawCircle(LocalColors.paper, radius = 2f, center = Offset(50f, 42f))
                }
            }
        }
    }
}

private data class Transform(val sx: Float, val sy: Float, val dy: Float)
private operator fun Transform.component1() = sx
private operator fun Transform.component2() = sy
private operator fun Transform.component3() = dy

private fun pinDropTransform(t: Float): Transform = when {
    t < 0.35f -> { val k = t / 0.35f; Transform(lerp(0.88f, 0.94f, k), lerp(1.12f, 1.06f, k), lerp(-44f, -18f, k)) }
    t < 0.55f -> { val k = (t - 0.35f) / 0.20f; Transform(lerp(0.94f, 1.18f, k), lerp(1.06f, 0.82f, k), lerp(-18f, 0f, k)) }
    t < 0.72f -> { val k = (t - 0.55f) / 0.17f; Transform(lerp(1.18f, 0.94f, k), lerp(0.82f, 1.08f, k), lerp(0f, -8f, k)) }
    t < 0.88f -> { val k = (t - 0.72f) / 0.16f; Transform(lerp(0.94f, 1.04f, k), lerp(1.08f, 0.96f, k), lerp(-8f, 0f, k)) }
    else      -> { val k = (t - 0.88f) / 0.12f; Transform(lerp(1.04f, 1f, k), lerp(0.96f, 1f, k), 0f) }
}

private fun stampTransform(t: Float): Transform = when {
    t < 0.35f -> { val k = t / 0.35f; Transform(lerp(0.85f, 0.92f, k), lerp(1.15f, 1.08f, k), lerp(-44f, -16f, k)) }
    t < 0.50f -> { val k = (t - 0.35f) / 0.15f; Transform(lerp(0.92f, 1.30f, k), lerp(1.08f, 0.70f, k), lerp(-16f, 0f, k)) }
    t < 0.68f -> { val k = (t - 0.50f) / 0.18f; Transform(lerp(1.30f, 0.92f, k), lerp(0.70f, 1.10f, k), lerp(0f, -4f, k)) }
    t < 0.85f -> { val k = (t - 0.68f) / 0.17f; Transform(lerp(0.92f, 1.04f, k), lerp(1.10f, 0.96f, k), lerp(-4f, 0f, k)) }
    else      -> { val k = (t - 0.85f) / 0.15f; Transform(lerp(1.04f, 1f, k), lerp(0.96f, 1f, k), 0f) }
}

private fun DrawScope.drawRipple(
    cx: Float, cy: Float, t: Float,
    baseRx: Float, baseRy: Float,
    color: Color, strokeWidth: Float,
) {
    if (t <= 0f || t >= 1f) return
    val s = lerp(0.4f, 2.2f, t)
    val alpha = if (t < 0.2f) lerp(0f, 1f, t / 0.2f) else lerp(1f, 0f, (t - 0.2f) / 0.8f)
    drawOval(
        color = color.copy(alpha = alpha),
        topLeft = Offset(cx - baseRx * s, cy - baseRy * s),
        size = Size(baseRx * 2 * s, baseRy * 2 * s),
        style = Stroke(width = strokeWidth),
    )
}

private fun DrawScope.drawSparkle(
    cx: Float, cy: Float, r: Float, color: Color, t: Float, fromCenter: Offset,
) {
    val alpha: Float
    val scale: Float
    val (x, y) = if (t == 0f) {
        alpha = 1f; scale = 1f
        cx to cy
    } else {
        alpha = if (t < 0.2f) lerp(0f, 1f, t / 0.2f) else lerp(1f, 0f, (t - 0.2f) / 0.8f)
        scale = lerp(0.4f, 1.4f, t)
        val dx = (cx - fromCenter.x) * lerp(0.6f, 1.2f, t)
        val dy = (cy - fromCenter.y) * lerp(0.6f, 1.2f, t)
        (fromCenter.x + dx) to (fromCenter.y + dy)
    }
    drawCircle(color.copy(alpha = alpha), radius = r * scale, center = Offset(x, y))
}

private fun DrawScope.drawSteam(x: Float, t: Float) {
    val rise = lerp(0f, -22f, t)
    val alpha = if (t == 0f) 0.6f
        else if (t < 0.2f) lerp(0f, 0.6f, t / 0.2f)
        else lerp(0.6f, 0f, (t - 0.2f) / 0.8f)
    val p = Path().apply {
        moveTo(x, 36f + rise)
        quadraticBezierTo(x - 4f, 28f + rise, x, 22f + rise)
        quadraticBezierTo(x + 4f, 16f + rise, x, 10f + rise)
    }
    drawPath(p, LocalColors.ink.copy(alpha = alpha), style = Stroke(width = 3f, cap = StrokeCap.Round))
}

private fun DrawScope.drawWave(cx: Float, cy: Float, t: Float, flip: Boolean) {
    if (t <= 0f || t >= 1f) return
    val spread = lerp(0f, 6f, t)
    val alpha = if (t < 0.3f) lerp(0f, 1f, t / 0.3f) else lerp(1f, 0f, (t - 0.3f) / 0.7f)
    val sign = if (flip) 1f else -1f
    val p = Path().apply {
        moveTo(cx, cy - 6f)
        quadraticBezierTo(cx + sign * (4f + spread), cy, cx, cy + 6f)
    }
    drawPath(p, LocalColors.coral.copy(alpha = alpha), style = Stroke(width = 3f, cap = StrokeCap.Round))
}

private enum class BladeDir { Top, Right, Bottom, Left }

private fun DrawScope.drawBlade(dir: BladeDir, color: Color) {
    val cx = 50f
    val cy = 42f
    val p = Path().apply {
        moveTo(cx, cy)
        when (dir) {
            BladeDir.Top    -> { lineTo(cx, cy - 30f);    quadraticBezierTo(cx + 22f, cy - 30f, cx + 22f, cy - 8f) }
            BladeDir.Right  -> { lineTo(cx + 30f, cy);    quadraticBezierTo(cx + 30f, cy + 22f, cx + 8f, cy + 22f) }
            BladeDir.Bottom -> { lineTo(cx, cy + 30f);    quadraticBezierTo(cx - 22f, cy + 30f, cx - 22f, cy + 8f) }
            BladeDir.Left   -> { lineTo(cx - 30f, cy);    quadraticBezierTo(cx - 30f, cy - 22f, cx - 8f, cy - 22f) }
        }
        close()
    }
    drawPath(p, color)
    drawPath(p, LocalColors.ink, style = Stroke(width = 3f, join = StrokeJoin.Round))
}
